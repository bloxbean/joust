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
import org.julclang.state.dsl.v2.AbiCheck;
import org.julclang.state.processor.JoustGenerators;
import org.julclang.state.processor.SubsetLint;
import org.julclang.stdlib.StdlibRegistry;

import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
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

    /**
     * A finding, from javac, JOUST (reader and lint) or JuLC; {@code line} and {@code col} are 1-based, or -1 when
     * unknown; {@code code} is a {@code .jsc} 2 diagnostic's stable code (ADR-0018 D12), else null.
     */
    public record Diagnostic(Severity severity, String message, String file, long line, long col, String source, String code) {
        public Diagnostic(Severity severity, String message, String file, long line, long col, String source) {
            this(severity, message, file, line, col, source, null);
        }

        public Diagnostic(Severity severity, String message, String file, long line, String source) {
            this(severity, message, file, line, -1, source);
        }
    }

    /** One compiled contract. */
    public record Contract(String name, String packageName, String validatorName, String validatorSource,
                           String scriptCborHex, String scriptHash, int scriptSizeBytes, String descriptionJson,
                           String transitionsSource, Map<String, String> transitions) {}

    /** A plain validator of the project, read inside the attribution query: plain strings, no javac model. */
    private record PlainValidator(String name, String packageName, String purpose, String source) {}

    /** A {@code @Param} of a plain validator: its name and Java type, as JuLC reports them. */
    public record Param(String name, String type) {}

    /**
     * A plain JuLC validator of the project ({@code @WithdrawValidator}, {@code @SpendingValidator}, ...), compiled as
     * JuLC's annotation processor does: a governor for {@code @AuthorizedBy} (ADR-0009 D3), for instance. {@code purpose}:
     * spend, mint, withdraw, publish, vote, propose or multi; {@code scriptHash} is empty while it takes parameters.
     */
    public record Validator(String name, String packageName, String purpose, String scriptCborHex, String scriptHash,
                            int scriptSizeBytes, List<Param> params) {}

    /**
     * The Java a {@code .jsc} file lowered to (ADR-0018 D12: written to {@code build/joust/generated/} by the npm CLI),
     * with its source map, ABI manifest and checked IR ({@code .jsc} 2, ADR-0018 D12, D15; null for v1). {@code javaFile} is
     * {@code a/b/Name.java}.
     */
    public record Generated(String jscFile, String javaFile, String java, String sourceMapJson, String abiJson, String irJson,
                            List<org.julclang.state.dsl.v2.JscV2.LibraryUse> libraries) {
        public Generated(String jscFile, String javaFile, String java, String sourceMapJson, String abiJson, String irJson) {
            this(jscFile, javaFile, java, sourceMapJson, abiJson, irJson, List.of());
        }
    }

    /**
     * The build's result: the contracts, the plain validators, every diagnostic ({@code ok} when there is no error), and
     * the Java lowered from each {@code .jsc} file.
     */
    public record Result(boolean ok, List<Contract> contracts, List<Validator> validators, List<Diagnostic> diagnostics,
                         List<Generated> generated, List<org.julclang.state.dsl.v2.JscV2.LibraryUse> libraries) {
        public Result(boolean ok, List<Contract> contracts, List<Validator> validators, List<Diagnostic> diagnostics,
                      List<Generated> generated) {
            this(ok, contracts, validators, diagnostics, generated, List.of());
        }

        public Result(boolean ok, List<Contract> contracts, List<Validator> validators, List<Diagnostic> diagnostics) {
            this(ok, contracts, validators, diagnostics, List.of());
        }

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
        return build(files, options, Map.of());
    }

    /**
     * As {@link #build(Map, Map)}, with the on-chain sources of the Java (JuLC) libraries the build's {@code .jsc} files may
     * import with {@code java:} (ADR-0018 D9, M4b): path to text, as a library jar's {@code META-INF/plutus-sources} or an
     * npm package's {@code joust-lib/} holds them. They are certified ({@link JavaLibraryCertifier}) before any
     * {@code .jsc} file that imports one is lowered, and they are on javac's and JuLC's library pools, as a library jar's
     * sources are on Gradle's processor path.
     */
    public static Result build(Map<String, String> files, Map<String, String> options, Map<String, String> javaLibrarySources) {
        return build(files, options, javaLibrarySources, Map.<String, String>of());
    }

    /**
     * As above, with the {@code .jsc} libraries of the project's npm packages ({@code packages}: by
     * {@code <package>/<path>}, the path inside the package's {@code joust-lib/}; ADR-0018 D14, M4c), which
     * {@code import { fee } from "@acme/fees/fees.jsc"} names.
     */
    public static Result build(Map<String, String> files, Map<String, String> options, Map<String, String> javaLibrarySources,
                               Map<String, String> packages) {
        var generated = new ArrayList<Generated>();
        Result r;
        try {
            r = build(files, options, javaLibrarySources, packages, generated);
        } catch (StackOverflowError e) {
            // A safety net past the front end (javac, the processor, JuLC): the build fails with a diagnostic, it does not
            // crash its caller (the npm CLI's engine, an editor) (ADR-0018 J18, review S2).
            return new Result(false, List.of(), List.of(), List.of(new Diagnostic(Severity.ERROR, "internal error: the compiler ran out of"
                    + " stack (please report it, with the file)", null, -1, "toolchain")), List.copyOf(generated));
        }
        var diagnostics = new ArrayList<Diagnostic>();
        // Review r6 (Codex C31, security's observation): a diagnostic on an offered library source names it by its joust-lib/
        // path too (javac names it by its class's file name only).
        for (Diagnostic d : remap(r.diagnostics(), generated, files.keySet())) {
            boolean project = d.file() != null && files.containsKey(d.file().replace('\\', '/').replaceFirst("^/+", ""));
            diagnostics.add(project || d.file() == null || javaLibrarySources.isEmpty() ? d : new Diagnostic(d.severity(),
                    withOfferedPaths(d.message(), d.file(), javaLibrarySources), d.file(), d.line(), d.col(), d.source(), d.code()));
        }
        // ADR-0018 D12 (review F2): every .jsc 2 manifest agrees with the processor's description of the contract.
        for (Contract c : r.contracts()) {
            for (Generated g : generated) {
                if (g.abiJson() == null || !g.javaFile().equals(c.packageName().replace('.', '/') + "/" + c.name() + ".java")) continue;
                for (String problem : AbiCheck.compare(g.abiJson(), c.descriptionJson(), c.packageName(), c.name())) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "the ABI manifest of " + c.name() + " disagrees with the processor's"
                            + " encoding: " + problem + " (an internal error of the .jsc lowering: please report it)", g.jscFile(), 1, 1,
                            "jsc", "JSC0004"));
                }
            }
        }
        boolean ok = r.ok() && diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR);
        return new Result(ok, r.contracts(), r.validators(), diagnostics, List.copyOf(generated), r.libraries());
    }

    /**
     * The top-level classes hand-written Java sources name, read from javac's attribution (review r3, Codex C16: not from
     * the text, whose layout and comments would decide what javac may see). Attributed with every Java library source
     * offered, only to find which of them the sources reach (an import in any form, a qualified name); the build itself
     * then compiles only those libraries' closures. Empty when the sources do not attribute (their build fails anyway).
     */
    /**
     * What javac found in the build's sources: the top-level classes they name, the packages they declare, and (review r4,
     * security S10) the errors of the offered library sources when the attribution failed because of them.
     */
    record Discovery(java.util.Set<String> classes, java.util.Set<String> packages, List<String> libraryErrors) {}

    /**
     * Review r5 (design, on S10; security's note): the discovery over the offered library sources, retried without a
     * source javac refuses, so that a broken or duplicate file no contract reaches does not fail the build; when the
     * contracts do need it, its errors are reported, each with the offered path (a joust-lib/ path, not only the file's
     * simple name). {@code owned}: the build's own sources (hand-written, and lowered from the project's .jsc), whose
     * packages are the project's (Codex C28: never a package library's).
     */
    static Discovery discover(Map<String, String> javaFiles, Map<String, String> javaLibrarySources, java.util.Set<String> owned) {
        // A source in a package of JOUST, JuLC, CCL or the JDK is never a usable library (the certifier refuses it): kept out
        // of discovery, where it would take precedence over the real class (a duplicate org.julclang.state.api.Require).
        var offered = new LinkedHashMap<String, String>();
        javaLibrarySources.forEach((path, text) -> { if (!reservedName(path.replace('/', '.'))) offered.put(path, text); });
        Discovery first = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            Discovery d = referencedClasses(javaFiles, offered, owned);
            if (first == null) first = d;
            if (d.libraryErrors().isEmpty()) return d;
            var bad = new java.util.TreeSet<String>();
            for (String e : d.libraryErrors()) bad.addAll(offeredPaths(e, offered));
            if (bad.isEmpty()) break;
            bad.forEach(offered::remove);
        }
        var errors = new ArrayList<String>();
        for (String e : first.libraryErrors()) {
            var paths = offeredPaths(e, javaLibrarySources);
            errors.add(e + (paths.isEmpty() ? "" : " (offered as " + String.join(", ", paths.stream().map(x -> "joust-lib/" + x).toList()) + ")"));
        }
        return new Discovery(first.classes(), first.packages(), errors);
    }

    /** A name in a package of the JDK, cardano-client-lib, JuLC or JOUST (as {@code JavaLibraryCertifier}'s rule). */
    static boolean reservedName(String name) {
        for (String p : List.of("java.", "javax.", "jdk.", "sun.", "com.sun.", "com.bloxbean.", "org.julclang.")) if (name.startsWith(p)) return true;
        return false;
    }

    /** The offered paths a javac error names by file (javac names a library unit by its simple file name). */
    static java.util.Set<String> offeredPaths(String error, Map<String, String> offered) {
        int colon = error.indexOf(':');
        return offeredFiles(colon < 0 ? "" : error.substring(0, colon), offered);
    }

    /**
     * The offered sources a file name javac reports may be: javac names a library unit by its class's file name
     * ({@code Require.java}), which is the offered file's own name or, when they differ (security's r5 observation:
     * {@code JulcListX.java} declaring {@code JulcList}), the name of a type it declares.
     */
    static java.util.Set<String> offeredFiles(String file, Map<String, String> offered) {
        var out = new java.util.TreeSet<String>();
        if (file == null || !file.endsWith(".java")) return out;
        String simple = file.substring(file.lastIndexOf('/') + 1);
        String type = simple.substring(0, simple.length() - ".java".length());
        var declares = java.util.regex.Pattern.compile("\\b(class|record|interface|enum)\\s+" + java.util.regex.Pattern.quote(type) + "\\b");
        offered.forEach((p, text) -> {
            if (p.equals(file) || p.endsWith("/" + file) || p.equals(simple) || p.endsWith("/" + simple) || declares.matcher(text).find()) out.add(p);
        });
        return out;
    }

    /** {@code message}, naming the offered sources {@code file} may be by their joust-lib/ paths, when there are any. */
    static String withOfferedPaths(String message, String file, Map<String, String> offered) {
        var paths = offeredFiles(file, offered);
        return paths.isEmpty() || message.contains("(offered as ") ? message
                : message + " (offered as " + String.join(", ", paths.stream().map(x -> "joust-lib/" + x).toList()) + ")";
    }

    static Discovery referencedClasses(Map<String, String> javaFiles, Map<String, String> javaLibrarySources, java.util.Set<String> owned) {
        var roots = new ArrayList<JavacFrontend.Unit>();
        javaFiles.forEach((name, text) -> roots.add(new JavacFrontend.Unit(text, java.nio.file.Path.of(name))));
        var pool = new ArrayList<JavacFrontend.Unit>();
        LibrarySourceResolver.scanClasspathSources(Toolchain.class.getClassLoader(), problem -> {})
                .values().forEach(l -> pool.add(JavacFrontend.Unit.jar(l.source())));
        javaLibrarySources.values().forEach(t -> pool.add(JavacFrontend.Unit.jar(t)));
        var ownedPaths = new java.util.HashSet<java.nio.file.Path>();
        for (String n : owned) ownedPaths.add(java.nio.file.Path.of(n).toAbsolutePath().normalize());
        Discovery found = JavacFrontend.shared().attribute(roots, pool, a -> {
            var out = new java.util.TreeSet<String>();
            var packages = new java.util.TreeSet<String>();
            var errors = new ArrayList<String>();
            if (!a.result().isAccepted()) {
                for (var d : a.result().diagnostics()) {
                    String f = d.fileName() == null ? "" : d.fileName().replace('\\', '/');
                    boolean project = javaFiles.keySet().stream().anyMatch(n -> f.equals(n) || f.endsWith("/" + n) || n.endsWith("/" + f));
                    if (!project && !d.message().startsWith("warning")) errors.add((f.isEmpty() ? "" : f + ":" + d.line() + ": ") + d.message());
                }
            }
            if (a.trees() == null) return new Discovery(out, packages, errors);
            for (var unit : a.roots()) {
                // Codex C29 (r6): ownership by the source's exact identity, never by a suffix (a project Fees.java is not
                // acme/fees/Fees.java): the front end names a root by its path made absolute, so each owned key is compared
                // through the same transformation.
                boolean own = ownedPaths.contains(java.nio.file.Path.of(unit.getSourceFile().getName()).normalize());
                if (own && unit.getPackageName() != null) packages.add(unit.getPackageName().toString());
                new com.sun.source.util.TreePathScanner<Void, Void>() {
                    void note() {
                        javax.lang.model.element.Element e = a.trees().getElement(getCurrentPath());
                        while (e != null && !(e instanceof javax.lang.model.element.TypeElement t
                                && t.getNestingKind() == javax.lang.model.element.NestingKind.TOP_LEVEL)) {
                            if (e instanceof javax.lang.model.element.PackageElement) return;
                            e = e.getEnclosingElement();
                        }
                        if (e != null) out.add(((javax.lang.model.element.TypeElement) e).getQualifiedName().toString());
                    }

                    @Override
                    public Void visitIdentifier(com.sun.source.tree.IdentifierTree node, Void unused) {
                        note();
                        return super.visitIdentifier(node, unused);
                    }

                    @Override
                    public Void visitMemberSelect(com.sun.source.tree.MemberSelectTree node, Void unused) {
                        note();
                        return super.visitMemberSelect(node, unused);
                    }
                }.scan(unit, null);
            }
            return new Discovery(out, packages, errors);
        });
        return found == null ? new Discovery(java.util.Set.of(), java.util.Set.of(), List.of()) : found;
    }

    /**
     * ADR-0018 D12: a diagnostic on Java lowered from a {@code .jsc} file (javac's, the JOUST processor's or lint's) is
     * reported at the {@code .jsc} line and column the source map gives, with the generated location as a note; on a
     * generated helper with no source position, at the {@code .jsc} file.
     */
    static List<Diagnostic> remap(List<Diagnostic> diagnostics, List<Generated> generated, java.util.Collection<String> files) {
        var byJava = new java.util.HashMap<String, Generated>();
        for (Generated g : generated) byJava.put("/" + g.javaFile(), g);
        // javac reports a unit by its simple file name (review F4): a generated file is found by it when no other source
        // of the build has the same simple name.
        var bySimple = new java.util.HashMap<String, Generated>();
        var simpleCount = new java.util.HashMap<String, Integer>();
        for (Generated g : generated) {
            String n = g.javaFile().substring(g.javaFile().lastIndexOf('/') + 1);
            bySimple.put(n, g);
            simpleCount.merge(n, 1, Integer::sum);
        }
        for (String f : files) {
            if (f.endsWith(".java")) simpleCount.merge(f.substring(Math.max(f.lastIndexOf('/'), f.lastIndexOf('\\')) + 1), 1, Integer::sum);
        }
        var out = new ArrayList<Diagnostic>();
        for (Diagnostic d : diagnostics) {
            String f = d.file() == null ? null : (d.file().startsWith("/") ? d.file() : "/" + d.file());
            Generated g = f == null ? null : byJava.get(f);
            // A unit named by its path (javac, the processor): its path ends with the generated file's.
            if (g == null && f != null) {
                for (Generated x : generated) if (f.endsWith("/" + x.javaFile())) g = x;
            }
            if (g == null && d.file() != null && !d.file().contains("/") && simpleCount.getOrDefault(d.file(), 0) == 1) g = bySimple.get(d.file());
            if (g == null || "jsc".equals(d.source())) {
                out.add(d);
                continue;
            }
            long line = -1, col = -1;
            if (g.sourceMapJson() != null && d.line() > 0) {
                var m = java.util.regex.Pattern.compile("\\[" + d.line() + ", (\\d+), (\\d+)\\]").matcher(g.sourceMapJson());
                if (m.find()) {
                    line = Long.parseLong(m.group(1));
                    col = Long.parseLong(m.group(2));
                }
            }
            String note = " (in the Java generated from " + g.jscFile() + ": " + g.javaFile() + (d.line() > 0 ? ":" + d.line() : "")
                    + (line < 0 && d.line() > 0 ? ", a generated helper" : "") + ")";
            out.add(new Diagnostic(d.severity(), d.message() + note, g.jscFile(), line, col, d.source(), d.code()));
        }
        return out;
    }

    private static Result build(Map<String, String> files, Map<String, String> options, Map<String, String> javaLibrarySources,
                                Map<String, String> packages, List<Generated> generatedOut) {
        var diagnostics = new ArrayList<Diagnostic>();
        // 1. .jsc → Java. A .jsc 2 file's ./x.jsc imports name other files of the build, by path (ADR-0018 D9, M4); its
        //    java: imports name certified Java libraries (M4b), certified once, when the first one is needed.
        var sources = new LinkedHashMap<String, String>();
        // ADR-0018 D9: the build's .jsc files and its npm packages' libraries (M4), which import contract (M2c) and
        // library imports resolve to.
        var modules = org.julclang.state.dsl.v2.JscModules.of(files, packages);
        var certified = new Map[1];
        org.julclang.state.dsl.v2.JavaLibraries javaLibraries = fqcn -> {
            if (certified[0] == null) certified[0] = javaLibrarySources.isEmpty() ? Map.of() : JavaLibraryCertifier.certify(javaLibrarySources);
            return (org.julclang.state.dsl.v2.JavaLibraries.Library) certified[0].get(fqcn);
        };
        files.forEach((name, text) -> {
            if (name.endsWith(".jsc")) {
                try {
                    // The bare file name, as Gradle's generateJsc passes it: the lowering embeds it. A .jsc 2 file's name
                    // must be [A-Za-z0-9][A-Za-z0-9._-]* (else a 1:1 error, security S3); a v1 file's name is escaped.
                    String base = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
                    var lowered = JscMain.lowerWithWarnings(text, base, name.replace('\\', '/'), modules, javaLibraries);
                    sources.put(name.replaceAll("\\.jsc$", ".java"), lowered.java());
                    generatedOut.add(new Generated(name, javaPath(lowered.java(), name), lowered.java(), lowered.sourceMapJson(), lowered.abiJson(),
                            lowered.irJson(), lowered.libraries()));
                    // ADR-0018 D12: warnings reach the npm path with their line and column (F13).
                    for (var w : lowered.diagnostics()) {
                        diagnostics.add(new Diagnostic(Severity.WARNING, w.message(), name, w.line(), w.col(), "jsc", w.code()));
                    }
                } catch (org.julclang.state.dsl.JscException e) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, e.getMessage(), name, e.line, e.col, "jsc", e.code));
                } catch (RuntimeException | Error e) {
                    // Any Error too, a StackOverflowError included (ADR-0018 J18, review S2): a located, coded diagnostic,
                    // never a crash of the build.
                    diagnostics.add(new Diagnostic(Severity.ERROR, JscMain.internalError(e), name, 1, 1, "jsc", "JSC0005"));
                }
            } else if (name.endsWith(".java")) {
                sources.put(name, text);
            }
        });
        // 1b. The .jsc libraries of npm packages the build imports (each contract's IR lists them, transitively; M4c): lowered
        //     once each, by their package path, and compiled with the project's sources, as a project's own library is.
        var packageLibraryPaths = new java.util.HashSet<String>();
        if (!hasErrors(diagnostics)) {
            var done = new java.util.HashSet<String>();
            for (Generated g : List.copyOf(generatedOut)) {
                for (var use : g.libraries()) {
                    String id = use.name();
                    if (!id.startsWith(org.julclang.state.dsl.v2.JscModules.PACKAGE) || !done.add(id)) continue;
                    String text = packages.get(id.substring(org.julclang.state.dsl.v2.JscModules.PACKAGE.length()));
                    try {
                        var lowered = JscMain.lowerWithWarnings(text, id.substring(id.lastIndexOf('/') + 1), id, modules, javaLibraries);
                        String path = javaPath(lowered.java(), id);
                        if (sources.containsKey(path)) {
                            diagnostics.add(new Diagnostic(Severity.ERROR, "the library " + id.substring(4) + " is the class " + path
                                    + ", which the project also declares", g.jscFile(), 1, 1, "jsc", "JSC0709"));
                            continue;
                        }
                        sources.put(path, lowered.java());
                        packageLibraryPaths.add(path);
                    } catch (org.julclang.state.dsl.JscException e) {
                        // Checked already, at the import (JSC0712): reported at the first importer.
                        diagnostics.add(new Diagnostic(Severity.ERROR, e.getMessage(), g.jscFile(), 1, 1, "jsc", e.code));
                    }
                }
            }
        }
        // 1c. The Java library sources the build compiles (review r2: #82 F2, S2, C7; #79 S3): the source closure of each
        //     java: library a .jsc file imports, and of each library a hand-written Java contract imports, which joust.lock
        //     then pins as well; no other source of a package reaches javac or JuLC.
        var pooledJava = new java.util.TreeMap<String, String>();
        var javaUses = new java.util.TreeMap<String, org.julclang.state.dsl.v2.JscV2.LibraryUse>();
        if (!javaLibrarySources.isEmpty() && !hasErrors(diagnostics)) {
            var wanted = new java.util.TreeSet<String>();
            for (Generated g : generatedOut) for (var u : g.libraries()) if (u.kind().equals("java")) wanted.add(u.name().substring("java:".length()));
            // The build's Java (hand-written, and lowered from .jsc, which hand-written code may name) as javac reads it.
            // The project's own packages (security S9, r4): a package's library may not declare a class in one of them, where
            // it would take the place of a class the project's code names unqualified (a no-op app.Require).
            var projectPackages = new java.util.TreeSet<String>();
            for (Generated g : generatedOut) {
                int slash = g.javaFile().lastIndexOf('/');
                projectPackages.add(slash < 0 ? "" : g.javaFile().substring(0, slash).replace('/', '.'));
            }
            if (files.keySet().stream().anyMatch(n -> n.endsWith(".java"))) {
                var owned = new java.util.TreeSet<String>(sources.keySet());
                owned.removeAll(packageLibraryPaths);   // a package's .jsc library is attributed, but its package is not the project's
                Discovery found = discover(sources, javaLibrarySources, owned);
                projectPackages.addAll(found.packages());
                for (String e : found.libraryErrors()) {
                    diagnostics.add(new Diagnostic(Severity.ERROR, "a Java library source offered to the build (an npm package's joust-lib/)"
                            + " does not compile, so the build cannot tell which libraries its Java contracts use: " + e, null, -1, "toolchain"));
                }
                for (String fqcn : found.classes()) {
                    if (reservedName(fqcn)) continue;   // the platform and JOUST's API, never a package's library
                    var lib = javaLibraries.describe(fqcn);
                    if (lib != null && lib.sources().isEmpty() && !lib.problems().isEmpty()) {
                        // Security S10 (r4): the contract uses this library, but the offered sources do not compile: say so,
                        // naming the files at fault, rather than leaving javac to report the library's package missing.
                        diagnostics.add(new Diagnostic(Severity.ERROR, "the Java library " + fqcn + ", which a Java contract uses, cannot be"
                                + " built: " + String.join("; ", lib.problems()), null, -1, "toolchain"));
                        continue;
                    }
                    if (lib == null || lib.sources().isEmpty()) continue;
                    // Security S11 (r6): a Java contract need not use a certified library (its body is JuLC's to compile),
                    // but the closure rules hold on every path, as JSC0721 holds them for java: imports: no class in a
                    // package of JOUST, JuLC, CCL or the JDK, and one source per class.
                    var closure = lib.problems().stream().filter(x -> x.contains("in a package of JOUST, JuLC, cardano-client-lib or the JDK")
                            || x.contains("is declared in more than one source")).toList();
                    if (!closure.isEmpty()) {
                        diagnostics.add(new Diagnostic(Severity.ERROR, "the Java library " + fqcn + ", which a Java contract uses, is refused: "
                                + String.join("; ", closure), null, -1, "toolchain"));
                        continue;
                    }
                    wanted.add(fqcn);
                    javaUses.put("java:" + fqcn, new org.julclang.state.dsl.v2.JscV2.LibraryUse(org.julclang.state.dsl.v2.JscV2.LibraryUse.JAVA_SOURCE, "java:" + fqcn,
                            lib.sourceSha256()));
                }
            }
            for (String fqcn : wanted) {
                var lib = javaLibraries.describe(fqcn);
                if (lib != null) {
                    for (String bin : lib.classes()) {
                        String top = bin.contains("$") ? bin.substring(0, bin.indexOf('$')) : bin;
                        String pkg = top.contains(".") ? top.substring(0, top.lastIndexOf('.')) : "";
                        if (projectPackages.contains(pkg)) {
                            diagnostics.add(new Diagnostic(Severity.ERROR, "the Java library " + fqcn + " declares " + bin + " in the package "
                                    + (pkg.isEmpty() ? "(the unnamed package)" : pkg) + ", which the project's own sources declare: a library"
                                    + " never adds classes to the project's packages (it would take the place of a class the project names"
                                    + " unqualified, review r4)", null, -1, "toolchain"));
                        }
                    }
                }
                if (lib != null) for (String path : lib.sources()) pooledJava.put(path, javaLibrarySources.get(path));
            }
        }
        if (hasErrors(diagnostics)) return new Result(false, List.of(), diagnostics);

        // 2. JuLC's library pool, as on Gradle's processor path: JOUST's API sources (and any other on-chain library
        //    sources on this class path), plus the project's own sources.
        Map<String, LibrarySource> classpathLibraries = LibrarySourceResolver.scanClasspathSources(
                Toolchain.class.getClassLoader(), problem -> diagnostics.add(new Diagnostic(Severity.WARNING, problem, null, -1, "julc")));

        // 3. Attribute with JuLC's embedded javac (the project's sources as roots, the library sources as JAR units), and
        // 4. lint, read and generate each @StateContract, as the annotation processor does. JuLC hands the attributed
        //    model to one scoped query: the model is used only inside it, on this thread, and nothing of it (no
        //    Element, TypeMirror or Tree) is kept past it: the query returns plain values (JuLC ADR-068).
        var roots = new ArrayList<JavacFrontend.Unit>();
        // Java lowered from a .jsc file is named by its package path, so javac's diagnostics on it name it uniquely
        // (review F13: two packages may each have a Foo.java) and can be remapped to the .jsc file.
        var generatedPaths = new java.util.HashSet<String>();
        for (Generated g : generatedOut) generatedPaths.add(g.javaFile());
        generatedPaths.addAll(packageLibraryPaths);
        // Hand-written Java is named by the path the build was given (review F15: other/Zed.java declaring Foo is
        // reported as other/Zed.java, not Foo.java).
        sources.forEach((name, t) -> {
            String path = javaPath(t, name);
            boolean lowered = name.endsWith(".java") && files.containsKey(name.replaceAll("\\.java$", ".jsc")) && generatedPaths.contains(path);
            // A build may be given Windows separators; javac checks a public class against the unit's base name, so
            // the path is always '/'-separated, whatever the host (review r4 C9).
            roots.add(new JavacFrontend.Unit(t, java.nio.file.Path.of(lowered ? path : name.replace('\\', '/'))));
        });
        var pool = new ArrayList<JavacFrontend.Unit>();
        classpathLibraries.values().forEach(l -> pool.add(JavacFrontend.Unit.jar(l.source())));
        // The Java libraries' on-chain sources (M4b): library sources, as on Gradle's processor path.
        pooledJava.values().forEach(t -> pool.add(JavacFrontend.Unit.jar(t)));
        var generated = new ArrayList<JoustGenerators.Generated>();
        var plain = new ArrayList<PlainValidator>();   // the project's plain validators, as strings
        boolean attributed = JavacFrontend.shared().attribute(roots, pool, attribution -> {
            for (var d : attribution.result().diagnostics()) {
                diagnostics.add(new Diagnostic(d.message().startsWith("warning") ? Severity.WARNING : Severity.ERROR, d.message(),
                        d.fileName(), d.line(), d.column(), "javac"));
            }
            if (!attribution.result().isAccepted() || attribution.trees() == null) return false;
            var env = new AttributedEnvironment(attribution.elements(), attribution.types(), attribution.trees(), options, diagnostics);
            for (CompilationUnitTree unit : attribution.roots()) {
                for (Tree decl : unit.getTypeDecls()) {
                    if (!(decl instanceof ClassTree)) continue;
                    var element = attribution.trees().getElement(new TreePath(new TreePath(unit), decl));
                    if (element instanceof TypeElement type && validatorPurpose(type) != null) {
                        String qualified = type.getQualifiedName().toString();
                        try {
                            plain.add(new PlainValidator(type.getSimpleName().toString(),
                                    qualified.contains(".") ? qualified.substring(0, qualified.lastIndexOf('.')) : "",
                                    validatorPurpose(type), unit.getSourceFile().getCharContent(true).toString()));
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                        continue;
                    }
                    // A .jsc library's generated class is checked by SubsetLint's rules too (ADR-0018 D1, D9; M4).
                    if (element instanceof TypeElement lib && isOnchainLibrary(lib)
                            && generatedPaths.contains(unit.getSourceFile().getName().replaceFirst("^/", ""))) {
                        SubsetLint.lint(attribution.trees(), lib, (trees, node, cu, message) -> {
                            long pos = trees.getSourcePositions().getStartPosition(cu, node);
                            diagnostics.add(new Diagnostic(Severity.ERROR, message, cu.getSourceFile().getName(),
                                    pos < 0 ? -1 : cu.getLineMap().getLineNumber(pos), "joust-lint"));
                        });
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
            return true;
        });
        if (!attributed || hasErrors(diagnostics)) return new Result(false, List.of(), diagnostics);

        // 5. Compile each validator with JuLC, over the processor's pool: the class path's libraries and the project's
        //    on-chain library sources (a @StateContract is one; JuLC's resolver adds the annotation's own source).
        Map<String, LibrarySource> project = new LinkedHashMap<>();
        for (String text : sources.values()) {
            LibrarySource l = LibrarySourceResolver.librarySource(text);
            if (l != null) project.put(l.fqcn(), l);
        }
        Map<String, LibrarySource> libraryPool = new LinkedHashMap<>(classpathLibraries);
        for (String text : pooledJava.values()) {
            LibrarySource l = LibrarySourceResolver.librarySource(text);
            if (l != null) libraryPool.put(l.fqcn(), l);
        }
        libraryPool.putAll(project);
        var compilerOptions = OptimizationConfiguration.apply(new CompilerOptions().setTarget(CompilerTarget.PLUTUS_V3_PV11),
                OptimizationLevel.DEFAULT_PROFILE_ID, null);
        compilerOptions.setJarLibrarySources(LibrarySourceResolver.jarSourceTexts(libraryPool, classpathLibraries));
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), compilerOptions);
        var contracts = new ArrayList<Contract>();
        for (var g : generated) {
            var resolved = LibrarySourceResolver.resolveLibrarySources(g.validatorSource(), libraryPool);
            if (Boolean.getBoolean("joust.toolchain.debug")) {
                System.err.println("[toolchain] " + g.validatorName() + " libraries: " + resolved.stream().map(LibrarySource::fqcn).toList());
            }
            org.julclang.compiler.CompileResult result;
            try {
                result = compiler.compileContract(g.validatorSource(), resolved.stream().map(LibrarySource::source).toList())
                        .compileResult();
            } catch (RuntimeException e) {
                // JuLC reports some problems by throwing (a method declared twice, F7): a diagnostic, not a crash.
                diagnostics.add(new Diagnostic(Severity.ERROR, "JuLC could not compile " + g.validatorName() + ": " + e.getMessage(),
                        g.validatorName(), -1, "julc"));
                continue;
            }
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
                var libs = discovery(LibrarySourceResolver.resolveLibrarySources(transitionsSource, libraryPool));
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
        for (var v : plain) {
            var libs = LibrarySourceResolver.resolveLibrarySources(v.source(), libraryPool);
            var result = compiler.compileContract(v.source(), libs.stream().map(LibrarySource::source).toList()).compileResult();
            if (result.hasErrors()) {
                result.diagnostics().forEach(d -> diagnostics.add(new Diagnostic(Severity.ERROR, String.valueOf(d), v.name(), -1, "julc")));
                continue;
            }
            var program = result.program();
            var params = new ArrayList<Param>();
            result.params().forEach(p -> params.add(new Param(p.name(), p.type())));
            validators.add(new Validator(v.name(), v.packageName(), v.purpose(), JulcScriptAdapter.fromProgram(program).getCborHex(),
                    result.isParameterized() ? "" : JulcScriptAdapter.scriptHash(program), result.scriptSizeBytes(),
                    List.copyOf(params)));
        }
        return new Result(!hasErrors(diagnostics), contracts, validators, diagnostics, List.of(), List.copyOf(javaUses.values()));
    }

    /** {@code a/b/Name.java} for generated Java: its package and its public class. */
    static String javaPath(String text, String fallback) {
        var pkg = java.util.regex.Pattern.compile("(?m)^package ([\\w.]+);").matcher(text);
        var cls = java.util.regex.Pattern.compile("(?m)^public class (\\w+)").matcher(text);
        if (!pkg.find() || !cls.find()) return fallback.replaceAll("\\.jsc$", ".java");
        return pkg.group(1).replace('.', '/') + "/" + cls.group(1) + ".java";
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

    static final String ONCHAIN_LIBRARY = "org.julclang.stdlib.annotation.OnchainLibrary";

    private static boolean isOnchainLibrary(TypeElement type) {
        return type.getAnnotationMirrors().stream().anyMatch(a ->
                ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals(ONCHAIN_LIBRARY));
    }

    private static boolean isStateContract(TypeElement type) {
        return type.getAnnotationMirrors().stream().anyMatch(a ->
                ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals(STATE_CONTRACT));
    }

    private static boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }
}
