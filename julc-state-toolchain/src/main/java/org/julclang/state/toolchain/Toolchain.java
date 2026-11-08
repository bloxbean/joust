package org.julclang.state.toolchain;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.OptimizationConfiguration;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.state.dsl.JscMain;
import org.julclang.state.processor.JoustGenerators;
import org.julclang.state.processor.SubsetLint;
import org.julclang.stdlib.StdlibRegistry;

import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds JOUST contracts from source without Gradle or a JDK (ADR-0015 D2): {@code .jsc} is lowered to Java, JuLC's
 * embedded javac attributes the sources, JOUST's generators (the same {@link JoustGenerators} the annotation processor
 * runs) write each contract's validator and description, and JuLC compiles the validator exactly as its annotation
 * processor does ({@code JulcCompiler.compileContract} over the same library pool). Pure Java: runs on the JVM, and in
 * the toolchain's Wasm engine.
 */
public final class Toolchain {

    static final String STATE_CONTRACT = "org.julclang.state.api.StateContract";

    public enum Severity {
        ERROR, WARNING, NOTE;

        static Severity of(javax.tools.Diagnostic.Kind k) {
            return switch (k) {
                case ERROR -> ERROR;
                case WARNING, MANDATORY_WARNING -> WARNING;
                default -> NOTE;
            };
        }
    }

    /** A finding, from javac, JOUST (reader and lint) or JuLC. */
    public record Diagnostic(Severity severity, String message, String file, long line, String source) {}

    /** One compiled contract. */
    public record Contract(String name, String packageName, String validatorName, String validatorSource,
                           String scriptCborHex, String scriptHash, int scriptSizeBytes, String descriptionJson,
                           String transitionsSource, Map<String, String> transitions) {}

    /** A {@code @Param} of a plain validator: its name and Java type, as JuLC reports them. */
    public record Param(String name, String type) {}

    /**
     * A plain JuLC validator of the project ({@code @WithdrawValidator}, {@code @SpendingValidator}, ...), compiled as
     * JuLC's annotation processor does: a governor for {@code @AuthorizedBy} (ADR-0009 D3), for instance. {@code purpose}:
     * spend, mint, withdraw, publish, vote, propose or multi; {@code scriptHash} is empty while it takes parameters.
     */
    public record Validator(String name, String packageName, String purpose, String scriptCborHex, String scriptHash,
                            int scriptSizeBytes, List<Param> params) {}

    /** The build's result: the contracts, the plain validators, and every diagnostic; {@code ok} when there is no error. */
    public record Result(boolean ok, List<Contract> contracts, List<Validator> validators, List<Diagnostic> diagnostics) {
        public Result(boolean ok, List<Contract> contracts, List<Diagnostic> diagnostics) {
            this(ok, contracts, List.of(), diagnostics);
        }
    }

    /** JuLC's validator annotations, by simple name, and the purpose each compiles for. */
    static final Map<String, String> VALIDATORS = Map.of(
            "SpendingValidator", "spend", "MintingValidator", "mint", "WithdrawValidator", "withdraw",
            "CertifyingValidator", "publish", "VotingValidator", "vote", "ProposingValidator", "propose",
            "MultiValidator", "multi");
    static final String VALIDATOR_PACKAGE = "org.julclang.stdlib.annotation.";

    private Toolchain() {
    }

    /**
     * Builds every {@code @StateContract} in {@code files} (name → text; {@code .java} or {@code .jsc}).
     * {@code options} are JOUST's processor options ({@code joust.traces}, ...).
     */
    public static Result build(Map<String, String> files, Map<String, String> options) {
        var diagnostics = new ArrayList<Diagnostic>();
        // 1. .jsc → Java.
        var sources = new LinkedHashMap<String, String>();
        files.forEach((name, text) -> {
            if (name.endsWith(".jsc")) {
                try {
                    // The bare file name, as Gradle's generateJsc passes it: the lowering embeds it.
                    String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
                    sources.put(name.replaceAll("\\.jsc$", ".java"), JscMain.lower(text, base));
                } catch (RuntimeException e) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, e.getMessage(), name, -1, "jsc"));
                }
            } else if (name.endsWith(".java")) {
                sources.put(name, text);
            }
        });
        if (hasErrors(diagnostics)) return new Result(false, List.of(), diagnostics);

        // 2. JuLC's library pool, as on Gradle's processor path: JOUST's API sources (and any other on-chain library
        //    sources on this class path), plus the project's own sources.
        Map<String, LibrarySource> classpathLibraries = LibrarySourceResolver.scanClasspathSources(
                Toolchain.class.getClassLoader(), problem -> diagnostics.add(new Diagnostic(Severity.WARNING, problem, null, -1, "julc")));

        // 3. Attribute with JuLC's embedded javac: the project's sources as roots, the library sources as JAR units.
        var roots = new ArrayList<JavacFrontend.Unit>();
        sources.values().forEach(t -> roots.add(JavacFrontend.Unit.of(t)));
        var pool = new ArrayList<JavacFrontend.Unit>();
        classpathLibraries.values().forEach(l -> pool.add(JavacFrontend.Unit.jar(l.source())));
        JavacFrontend.Attribution attribution = JavacFrontend.shared().attribute(roots, pool);
        for (var d : attribution.result().diagnostics()) {
            diagnostics.add(new Diagnostic(d.message().startsWith("warning") ? Severity.WARNING : Severity.ERROR, d.message(),
                    d.fileName(), d.line(), "javac"));
        }
        if (!attribution.result().isAccepted() || attribution.trees() == null) return new Result(false, List.of(), diagnostics);

        // 4. Lint, read and generate each @StateContract, as the annotation processor does.
        var env = new AttributedEnvironment(attribution.elements(), attribution.types(), attribution.trees(), options, diagnostics);
        var generated = new ArrayList<JoustGenerators.Generated>();
        var plain = new ArrayList<Map.Entry<TypeElement, String>>();   // plain validators: the type, its purpose
        var plainSources = new LinkedHashMap<TypeElement, String>();
        for (CompilationUnitTree unit : attribution.roots()) {
            for (Tree decl : unit.getTypeDecls()) {
                if (!(decl instanceof ClassTree)) continue;
                var element = attribution.trees().getElement(new TreePath(new TreePath(unit), decl));
                if (element instanceof TypeElement type && validatorPurpose(type) != null) {
                    plain.add(Map.entry(type, validatorPurpose(type)));
                    try {
                        plainSources.put(type, unit.getSourceFile().getCharContent(true).toString());
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    continue;
                }
                if (!(element instanceof TypeElement type) || !isStateContract(type)) continue;
                boolean clean = SubsetLint.lint(attribution.trees(), type, (trees, node, cu, message) -> {
                    long pos = trees.getSourcePositions().getStartPosition(cu, node);
                    diagnostics.add(new Diagnostic(Severity.ERROR, message, cu.getSourceFile().getName(),
                            pos < 0 ? -1 : cu.getLineMap().getLineNumber(pos), "joust-lint"));
                });
                if (!clean) continue;
                try {
                    var o = JoustGenerators.Options.of(env);
                    // Transition programs are on unless asked otherwise: the runtime engine runs them (D3).
                    o = new JoustGenerators.Options(o.traces(), o.javaClient(), o.portable(),
                            !"false".equals(options.get("joust.transitions")));
                    JoustGenerators.generate(env, type, o).ifPresent(generated::add);
                } catch (RuntimeException e) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "JOUST could not generate " + type.getSimpleName() + ": " + e,
                            null, -1, "joust"));
                }
            }
        }
        if (hasErrors(diagnostics)) return new Result(false, List.of(), diagnostics);

        // 5. Compile each validator with JuLC, over the processor's pool: the class path's libraries and the project's
        //    @OnchainLibrary sources (a @StateContract is one, meta-annotated; JuLC ADR-071), with its marker.
        Map<String, LibrarySource> project = new LinkedHashMap<>();
        for (String text : sources.values()) {
            LibrarySource l = LibrarySourceResolver.librarySource(text);
            if (l != null) project.put(l.fqcn(), l);
        }
        Map<String, LibrarySource> libraryPool = new LinkedHashMap<>(classpathLibraries);
        libraryPool.putAll(project);
        var compilerOptions = OptimizationConfiguration.apply(new CompilerOptions().setTarget(CompilerTarget.PLUTUS_V3_PV11),
                OptimizationLevel.DEFAULT_PROFILE_ID, null);
        compilerOptions.setJarLibrarySources(LibrarySourceResolver.jarSourceTexts(libraryPool, classpathLibraries));
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), compilerOptions);
        var contracts = new ArrayList<Contract>();
        for (var g : generated) {
            var resolved = new ArrayList<>(LibrarySourceResolver.resolveLibrarySources(g.validatorSource(), libraryPool));
            var names = new HashSet<String>();
            resolved.forEach(l -> names.add(l.fqcn()));
            for (var l : List.copyOf(resolved)) {
                if (project.containsKey(l.fqcn()) && l.source().contains("@StateContract") && names.add(STATE_CONTRACT)
                        && libraryPool.containsKey(STATE_CONTRACT)) {
                    resolved.add(libraryPool.get(STATE_CONTRACT));
                }
            }
            if (Boolean.getBoolean("joust.toolchain.debug")) {
                System.err.println("[toolchain] " + g.validatorName() + " libraries: " + resolved.stream().map(LibrarySource::fqcn).toList());
            }
            var result = compiler.compileContract(g.validatorSource(), resolved.stream().map(LibrarySource::source).toList())
                    .compileResult();
            if (result.hasErrors()) {
                result.diagnostics().forEach(d -> diagnostics.add(new Diagnostic(Severity.ERROR, String.valueOf(d), g.validatorName(), -1, "julc")));
                continue;
            }
            var program = result.program();
            // Transition programs (D3): each transition method compiled on its own over the same pool.
            var transitions = new LinkedHashMap<String, String>();
            String transitionsSource = null;
            if (g.transitions() != null) {
                transitionsSource = g.transitions().source();
                var libs = discovery(withMarker(LibrarySourceResolver.resolveLibrarySources(transitionsSource, libraryPool), project, libraryPool));
                for (String m : g.transitions().methods()) {
                    try {
                        var tr = compiler.compileMethod(transitionsSource, m, libs.stream().map(LibrarySource::source).toList());
                        if (tr.hasErrors()) {
                            tr.diagnostics().forEach(d -> diagnostics.add(new Diagnostic(Severity.WARNING,
                                    "transition " + m + " not compiled: " + d, g.contractName(), -1, "julc")));
                        } else {
                            transitions.put(m, JulcScriptAdapter.fromProgram(tr.program()).getCborHex());
                        }
                    } catch (RuntimeException e) {
                        diagnostics.add(new Diagnostic(Severity.WARNING, "transition " + m + " not compiled: " + e.getMessage(),
                                g.contractName(), -1, "julc"));
                    }
                }
            }
            contracts.add(new Contract(g.contractName(), g.packageName(), g.validatorName(), g.validatorSource(),
                    JulcScriptAdapter.fromProgram(program).getCborHex(),
                    result.isParameterized() ? "" : JulcScriptAdapter.scriptHash(program),
                    result.scriptSizeBytes(), g.descriptionJson(), transitionsSource, transitions));
        }
        // 6. The project's plain validators (governors, ...), compiled as JuLC's annotation processor does.
        var validators = new ArrayList<Validator>();
        for (var e : plain) {
            TypeElement type = e.getKey();
            String source = plainSources.get(type);
            var libs = LibrarySourceResolver.resolveLibrarySources(source, libraryPool);
            var result = compiler.compileContract(source, libs.stream().map(LibrarySource::source).toList()).compileResult();
            String simple = type.getSimpleName().toString();
            if (result.hasErrors()) {
                result.diagnostics().forEach(d -> diagnostics.add(new Diagnostic(Severity.ERROR, String.valueOf(d), simple, -1, "julc")));
                continue;
            }
            var program = result.program();
            var params = new ArrayList<Param>();
            result.params().forEach(p -> params.add(new Param(p.name(), p.type())));
            String pkg = type.getQualifiedName().toString();
            pkg = pkg.contains(".") ? pkg.substring(0, pkg.lastIndexOf('.')) : "";
            validators.add(new Validator(simple, pkg, e.getValue(), JulcScriptAdapter.fromProgram(program).getCborHex(),
                    result.isParameterized() ? "" : JulcScriptAdapter.scriptHash(program), result.scriptSizeBytes(),
                    List.copyOf(params)));
        }
        return new Result(!hasErrors(diagnostics), contracts, validators, diagnostics);
    }

    /** The purpose of a plain JuLC validator (its annotation), or null for any other type. */
    private static String validatorPurpose(TypeElement type) {
        for (var a : type.getAnnotationMirrors()) {
            String n = ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().toString();
            if (n.startsWith(VALIDATOR_PACKAGE) && VALIDATORS.containsKey(n.substring(VALIDATOR_PACKAGE.length()))) {
                return VALIDATORS.get(n.substring(VALIDATOR_PACKAGE.length()));
            }
        }
        return null;
    }

    /**
     * The transition programs' library variants (ADR-0015 D3): a library with a source under
     * {@code META-INF/joust/discovery/} on the class path replaces the on-chain one (MerkleMaps' read discovery).
     */
    private static List<LibrarySource> discovery(List<LibrarySource> libs) {
        var out = new ArrayList<LibrarySource>(libs.size());
        for (LibrarySource l : libs) {
            String path = "META-INF/joust/discovery/" + l.fqcn().replace('.', '/') + ".java";
            try (var in = Toolchain.class.getClassLoader().getResourceAsStream(path)) {
                out.add(in == null ? l : LibrarySourceResolver.librarySource(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("cannot read " + path, e);
            }
        }
        return out;
    }

    /** The resolved libraries, plus JOUST's {@code @StateContract} marker for a project contract (JuLC ADR-071). */
    private static List<LibrarySource> withMarker(List<LibrarySource> resolved, Map<String, LibrarySource> project,
                                                  Map<String, LibrarySource> pool) {
        var out = new ArrayList<>(resolved);
        var names = new HashSet<String>();
        out.forEach(l -> names.add(l.fqcn()));
        for (var l : List.copyOf(out)) {
            if (project.containsKey(l.fqcn()) && l.source().contains("@StateContract") && names.add(STATE_CONTRACT)
                    && pool.containsKey(STATE_CONTRACT)) {
                out.add(pool.get(STATE_CONTRACT));
            }
        }
        return out;
    }

    private static boolean isStateContract(TypeElement type) {
        return type.getAnnotationMirrors().stream().anyMatch(a ->
                ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals(STATE_CONTRACT));
    }

    private static boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }
}
