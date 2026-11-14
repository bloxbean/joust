package org.julclang.state.toolchain;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.state.dsl.v2.JavaLibraries;
import org.julclang.state.dsl.v2.JscV2;
import org.julclang.state.processor.Records;
import org.julclang.state.processor.SubsetLint;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Certifies the Java (JuLC) libraries a {@code .jsc} build imports with {@code java:} (ADR-0018 D9, M4b; J7). Given a
 * library's on-chain sources, JuLC's scoped javac attribution (with JOUST's API and the JuLC stdlib on the pool, as on
 * Gradle's processor path) gives:
 * <ul>
 *   <li>each {@code @OnchainLibrary} class's public static methods with their <b>exact</b> Java signatures, mapped to
 *       {@code .jsc} types or refused with the reason (fixed-width numbers, {@code PlutusData}, generics, maps and values,
 *       void, types without a counterpart; an overloaded name);</li>
 *   <li>the records those signatures reach: read-only {@code .jsc} records where the encoding is verified ({@code Constr 0}
 *       of its fields, every field mapped, no constructor logic), opaque otherwise;</li>
 *   <li>the certification of every body reachable from the class's public static methods, transitively through the
 *       library's sources and stopping at the trusted base (the pinned JuLC stdlib through D8's binding table, the JOUST
 *       API's {@code Require} and adapters, and an allow-list of {@code BigInteger}, {@code JulcList}, {@code JulcMap} and
 *       {@code Optional} operations whose JVM and on-chain meanings agree): {@code SubsetLint}'s rules, no casts, no
 *       reference equality, no {@code equals} but {@code BigInteger}'s, and none of the catalogue of known JVM/on-chain
 *       differences ({@code docs/reference/jsc-2-java-libraries.md}).</li>
 * </ul>
 * Nothing of javac's model leaves the attribution query: the result is plain values. A library with any problem is
 * described with them, and the front end refuses its import (JSC0721).
 */
public final class JavaLibraryCertifier {

    private JavaLibraryCertifier() {}

    static final String ONCHAIN_LIBRARY = "org.julclang.stdlib.annotation.OnchainLibrary";

    /** The {@link JavaLibraries.Certifier} the front end finds through {@link java.util.ServiceLoader}. */
    public static final class Provider implements JavaLibraries.Certifier {
        @Override
        public JavaLibraries certify(Map<String, String> sources) {
            return JavaLibraries.of(JavaLibraryCertifier.certify(sources));
        }
    }

    // ------------------------------------------------------------------ the trusted base (D8, D9)

    /** {@code BigInteger} operations with one meaning on the JVM and on-chain (truncating division, as D6). */
    static final Set<String> BIG_INTEGER = Set.of("add", "subtract", "multiply", "divide", "remainder", "negate", "abs", "compareTo",
            "signum", "equals", "valueOf");
    /** {@code JulcList} operations allowed whatever the element type. {@code contains}/{@code indexOf}: see {@link #MEMBERSHIP}. */
    static final Set<String> JULC_LIST = Set.of("empty", "of", "isEmpty", "head", "tail", "prepend", "concat", "reverse", "map", "filter",
            "any", "all", "size");
    /** {@code JulcMap} operations allowed whatever the types; {@code get} is never (a miss is null on the JVM, a failure on-chain). */
    static final Set<String> JULC_MAP = Set.of("empty", "insert", "delete", "lookup", "keys", "values", "isEmpty", "size");
    /** Membership tests: allowed only on element (key) types compared by content on both sides (D6, F16). */
    static final Set<String> MEMBERSHIP = Set.of("contains", "indexOf", "containsKey");
    static final Set<String> OPTIONAL = Set.of("of", "empty", "isPresent", "isEmpty", "get");
    /**
     * Beside D8's rows, the trusted base's functions the {@code .jsc} lowering itself calls with one meaning on every
     * runtime: JOUST's {@code Require}, and the byte-string comparisons D6 lowers {@code ==} and {@code <} to.
     */
    static final Set<String> API = Set.of("org.julclang.state.api.Require.that(boolean,java.lang.String):void",
            "org.julclang.stdlib.lib.ByteStringLib.equals(byte[],byte[]):boolean",
            "org.julclang.stdlib.lib.ByteStringLib.lessThan(byte[],byte[]):boolean",
            "org.julclang.stdlib.lib.ByteStringLib.lessThanEquals(byte[],byte[]):boolean");

    /** D8's binding rows, by exact signature: the stdlib functions and JOUST adapters a library may call. */
    static Set<String> bindingSignatures() {
        return new TreeSet<>(JscV2.bindingSignatures());
    }

    // ------------------------------------------------------------------ certification

    /**
     * Every {@code @OnchainLibrary} class of {@code sources} (path to text), described and certified, by class name. A
     * source set that does not compile is described by its errors, for every class it declares.
     */
    public static Map<String, JavaLibraries.Library> certify(Map<String, String> sources) {
        var roots = new ArrayList<JavacFrontend.Unit>();
        var paths = new TreeMap<String, String>();
        sources.forEach((path, text) -> {
            String p = path.replace('\\', '/');
            paths.put(p, text);
            roots.add(new JavacFrontend.Unit(text, java.nio.file.Path.of(p)));
        });
        var pool = new ArrayList<JavacFrontend.Unit>();
        LibrarySourceResolver.scanClasspathSources(Toolchain.class.getClassLoader(), p -> {})
                .values().forEach(l -> pool.add(JavacFrontend.Unit.jar(l.source())));
        Set<String> bindings = bindingSignatures();
        Map<String, JavaLibraries.Library> out = new LinkedHashMap<>();
        JavacFrontend.shared().attribute(roots, pool, a -> {
            var errors = new ArrayList<String>();
            for (var d : a.result().diagnostics()) {
                if (!d.message().startsWith("warning")) errors.add(d.fileName() + ":" + d.line() + ": " + d.message());
            }
            if (!a.result().isAccepted() || a.trees() == null) {
                for (String fqcn : declaredClasses(paths)) {
                    out.put(fqcn, new JavaLibraries.Library(fqcn, hash(paths), List.of(), List.of(), List.of("its sources do not compile: "
                            + String.join("; ", errors)), List.of()));
                }
                return null;
            }
            new Certification(a.trees(), a.elements(), a.roots(), paths, bindings).run(out);
            return null;
        });
        return out;
    }

    /** {@code package.Class} of each top-level class a source declares, read from its text (when it does not compile). */
    static List<String> declaredClasses(Map<String, String> paths) {
        var out = new ArrayList<String>();
        for (String text : paths.values()) {
            var pkg = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(text);
            var cls = java.util.regex.Pattern.compile("(?m)^\\s*public\\s+(?:final\\s+)?class\\s+(\\w+)").matcher(text);
            if (cls.find()) out.add((pkg.find() ? pkg.group(1) + "." : "") + cls.group(1));
        }
        return out;
    }

    /** The SHA-256 of a set of sources: each path, its length and its text, in path order (J12). */
    static String hash(Map<String, String> paths) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            for (var e : new TreeMap<>(paths).entrySet()) {
                byte[] text = e.getValue().getBytes(StandardCharsets.UTF_8);
                md.update((e.getKey() + "\n" + text.length + "\n").getBytes(StandardCharsets.UTF_8));
                md.update(text);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One attribution's certification: plain values out, no javac model kept. */
    private static final class Certification {
        final Trees trees;
        final Elements elements;
        final List<? extends CompilationUnitTree> units;
        final Map<String, String> paths;
        final Set<String> bindings;
        /** Every method and constructor declared in the library's sources, with its tree and unit. */
        final Map<ExecutableElement, TreePath> declared = new LinkedHashMap<>();
        /** Every class declared in the library's sources, by binary name, with its unit's path. */
        final Map<String, String> classPath = new LinkedHashMap<>();
        /** Classes declared in more than one source (a second variant of one class, D9). */
        final Set<String> duplicated = new TreeSet<>();
        /** Each class the sources declare, by binary name. */
        final Map<String, TypeElement> types = new LinkedHashMap<>();
        /** Each source's compilation unit, by its path. */
        final Map<String, CompilationUnitTree> unitByPath = new LinkedHashMap<>();
        /** Classes the sources declare in a package of the trusted base or the platform (security S3, r2). */
        final Set<String> reserved = new TreeSet<>();

        Certification(Trees trees, Elements elements, List<? extends CompilationUnitTree> units, Map<String, String> paths,
                      Set<String> bindings) {
            this.trees = trees;
            this.elements = elements;
            this.units = units;
            this.paths = paths;
            this.bindings = bindings;
        }

        void run(Map<String, JavaLibraries.Library> out) {
            var libraries = new ArrayList<TypeElement>();
            for (CompilationUnitTree unit : units) {
                String path = pathOf(unit);
                unitByPath.put(path, unit);
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitClass(ClassTree node, Void unused) {
                        if (trees.getElement(getCurrentPath()) instanceof TypeElement t) {
                            String bin = elements.getBinaryName(t).toString();
                            if (classPath.put(bin, path) != null) duplicated.add(bin);
                            if (reservedPackage(bin)) reserved.add(bin);
                            types.put(bin, t);
                            if (t.getNestingKind().isNested() == false && annotated(t, ONCHAIN_LIBRARY)) libraries.add(t);
                        }
                        return super.visitClass(node, unused);
                    }

                    @Override
                    public Void visitMethod(MethodTree node, Void unused) {
                        if (trees.getElement(getCurrentPath()) instanceof ExecutableElement m) declared.put(m, getCurrentPath());
                        return super.visitMethod(node, unused);
                    }
                }.scan(unit, null);
            }
            for (TypeElement lib : libraries) out.put(lib.getQualifiedName().toString(), library(lib));
        }

        /** The messages the bodies being certified can trace (D12, J20), collected by {@link #body}. */
        List<JavaLibraries.Message> messages;

        JavaLibraries.Library library(TypeElement lib) {
            String fqcn = lib.getQualifiedName().toString();
            var problems = new ArrayList<String>();
            messages = new ArrayList<>();
            var records = new LinkedHashMap<String, JavaLibraries.Rec>();
            var fns = new ArrayList<JavaLibraries.Fn>();
            var names = new java.util.HashMap<String, Integer>();
            var entry = new ArrayList<ExecutableElement>();
            for (Element e : lib.getEnclosedElements()) {
                if (e instanceof ExecutableElement m && m.getKind() == ElementKind.METHOD && m.getModifiers().contains(Modifier.PUBLIC)
                        && m.getModifiers().contains(Modifier.STATIC)) {
                    entry.add(m);
                    names.merge(m.getSimpleName().toString(), 1, Integer::sum);
                }
            }
            for (ExecutableElement m : entry) {
                String name = m.getSimpleName().toString();
                var pnames = new ArrayList<String>();
                var ptypes = new ArrayList<JavaLibraries.JType>();
                String refusal = names.get(name) > 1 ? "it is overloaded (.jsc calls a fn by name)" : null;
                for (VariableElement p : m.getParameters()) {
                    pnames.add(p.getSimpleName().toString());
                    JavaLibraries.JType t = map(p.asType(), records, problems);
                    ptypes.add(t);
                    if (refusal == null && t.unmappable() != null) refusal = "its parameter " + p.getSimpleName() + " is " + p.asType() + ": " + t.unmappable();
                }
                JavaLibraries.JType result = m.getReturnType().getKind() == TypeKind.VOID ? JavaLibraries.JType.unmappable("it returns nothing")
                        : map(m.getReturnType(), records, problems);
                if (refusal == null && result.unmappable() != null) refusal = "its result is " + m.getReturnType() + ": " + result.unmappable();
                if (refusal == null && !m.getTypeParameters().isEmpty()) refusal = "it is generic (no generic fns in .jsc)";
                fns.add(new JavaLibraries.Fn(name, List.copyOf(pnames), List.copyOf(ptypes), result, refusal));
            }
            // Every body the public methods reach, transitively, stopping at the trusted base.
            var certified = new TreeSet<String>();
            var seen = new LinkedHashSet<ExecutableElement>();
            var work = new ArrayDeque<ExecutableElement>(entry);
            var classes = new LinkedHashSet<TypeElement>();
            while (!work.isEmpty()) {
                ExecutableElement m = work.poll();
                if (!seen.add(m)) continue;
                if (classPath.containsKey(elements.getBinaryName((TypeElement) m.getEnclosingElement()).toString())) {
                    certified.add(elements.getBinaryName((TypeElement) m.getEnclosingElement()).toString());
                }
                TreePath path = declared.get(m);
                // An implicit member (a record's accessors and canonical constructor, a default constructor): javac's own
                // field reads and assignments, which JuLC compiles as the record's encoding.
                if (path == null || elements.getOrigin(m) != Elements.Origin.EXPLICIT) continue;
                TypeElement owner = outermost(m);
                classes.add(owner);
                certified.add(elements.getBinaryName((TypeElement) m.getEnclosingElement()).toString());
                body(path, problems, work);
            }
            for (TypeElement c : classes) {
                SubsetLint.lint(trees, c, (t, node, cu, message) -> {
                    long pos = t.getSourcePositions().getStartPosition(cu, node);
                    problems.add(where(cu, pos) + ": " + message);
                });
            }
            for (String d : duplicated) {
                if (certified.contains(d) || d.equals(elements.getBinaryName(lib).toString())) {
                    problems.add(d + " is declared in more than one source: a library has one on-chain source per class, compiled for both"
                            + " sides (no second variant, ADR-0018 D9)");
                }
            }
            // The sources it is built from (security S2, S3; #82 S1, r2): every source file its class's file names, through any
            // name javac resolves (a type, a call, a field, an import), transitively. That closure is what the build compiles
            // into the consumer, what the lock pins (a record's field order lives in the record's own file), and its classes
            // (binary names from javac, nested ones too) are what the runtime and the duplicate-class check look at.
            var files = closure(classPath.get(elements.getBinaryName(lib).toString()));
            for (JavaLibraries.Rec r : records.values()) if (classPath.get(r.binaryName()) != null) files.addAll(closure(classPath.get(r.binaryName())));
            var texts = new TreeMap<String, String>();
            for (String f : files) if (paths.containsKey(f)) texts.put(f, paths.get(f));
            var closureClasses = new TreeSet<String>();
            classPath.forEach((bin, f) -> { if (files.contains(f)) closureClasses.add(bin); });
            // Security S3 (r2): a library's sources never declare a class of JOUST, JuLC, cardano-client-lib or the JDK; the
            // build compiles a library's closure into the consumer, ahead of those jars (a no-op JavaLibraryCheck, say). Only
            // a library whose closure holds such a class is refused: a file outside every closure is never compiled
            // (review r2: #79 F8, #82 F10).
            for (String r : reserved) {
                if (closureClasses.contains(r)) {
                    problems.add(classPath.get(r) + " declares " + r + ", in a package of JOUST, JuLC, cardano-client-lib or the JDK: a"
                            + " library's sources never declare those (they would be compiled into the build, ahead of the real classes)");
                }
            }
            for (String bin : closureClasses) {
                TypeElement t = types.get(bin);
                if (t != null && t.getNestingKind() == javax.lang.model.element.NestingKind.TOP_LEVEL) classLevel(t, problems);
            }
            return new JavaLibraries.Library(fqcn, hash(texts), List.copyOf(fns), List.copyOf(records.values()), List.copyOf(new LinkedHashSet<>(problems)),
                    List.copyOf(certified), List.copyOf(new LinkedHashSet<>(messages)), List.copyOf(texts.keySet()), List.copyOf(closureClasses));
        }

        /** The library source files {@code start} needs, transitively: each file a resolved name in it is declared in. */
        TreeSet<String> closure(String start) {
            var out = new TreeSet<String>();
            if (start == null) return out;
            var work = new ArrayDeque<String>(List.of(start));
            while (!work.isEmpty()) {
                String f = work.poll();
                if (!out.add(f)) continue;
                CompilationUnitTree unit = unitByPath.get(f);
                if (unit == null) continue;
                new TreePathScanner<Void, Void>() {
                    void note() {
                        Element el = trees.getElement(getCurrentPath());
                        TypeElement top = el == null ? null : topLevel(el);
                        if (top == null) return;
                        String file = classPath.get(elements.getBinaryName(top).toString());
                        if (file != null && !out.contains(file)) work.add(file);
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
            return out;
        }

        /** The top-level type {@code e} is in, or null (a package, a module). */
        static TypeElement topLevel(Element e) {
            Element c = e;
            while (c != null && !(c instanceof TypeElement t && t.getNestingKind() == javax.lang.model.element.NestingKind.TOP_LEVEL)) {
                if (c instanceof PackageElement) return null;
                c = c.getEnclosingElement();
            }
            return (TypeElement) c;
        }

        /**
         * Security S4 (r2): what JuLC refuses later, refused by the certifier itself, so that "certified" does not depend on
         * the JuLC version: overloads of any name, initialiser blocks, static fields not initialised by a literal,
         * constructors and explicit accessors of a library record.
         */
        void classLevel(TypeElement c, List<String> problems) {
            if (c.getKind() == ElementKind.ANNOTATION_TYPE) {
                problems.add(c.getQualifiedName() + " is an annotation type (a library declares none: its members escape the static-state rules)");
                return;
            }
            var names = new java.util.HashMap<String, Integer>();
            for (Element m : c.getEnclosedElements()) {
                if (m.getKind() == ElementKind.METHOD) names.merge(m.getSimpleName().toString(), 1, Integer::sum);
            }
            names.forEach((n, k) -> {
                if (k > 1) problems.add(c.getQualifiedName() + "." + n + " is overloaded (JuLC calls a method by name: one method per name)");
            });
            // javac's element model has no initialiser blocks: the class's tree does.
            if (trees.getTree(c) instanceof ClassTree ct) {
                for (Tree member : ct.getMembers()) {
                    if (member instanceof com.sun.source.tree.BlockTree) problems.add(c.getQualifiedName() + " has an initialiser block (not lowered on-chain)");
                }
            }
            var components = c.getKind() == ElementKind.RECORD ? Records.components(c).stream().map(x -> x.getSimpleName().toString()).toList()
                    : List.<String>of();
            for (Element m : c.getEnclosedElements()) {
                switch (m.getKind()) {
                    case FIELD -> {
                        if (!m.getModifiers().contains(Modifier.STATIC)) break;
                        var tree = trees.getTree(m);
                        Tree init = tree instanceof com.sun.source.tree.VariableTree v ? v.getInitializer() : null;
                        // Codex C13 (r3): an array is mutable state on the JVM (final keeps the reference, not the contents): a
                        // caller that changes a returned static byte[] changes every later call, while UPLC keeps the constant.
                        // A static field is a final scalar (BigInteger, boolean, String) initialised by a literal. A certified
                        // library cannot express a byte constant at all (array creation is refused in bodies too, and D8's
                        // table builds no bytes from a literal): it takes byte constants from its caller (review r4, F10;
                        // jsc-2-java-libraries.md, #88).
                        if (m.asType().getKind() == TypeKind.ARRAY) {
                            problems.add(c.getQualifiedName() + "." + m.getSimpleName() + ": a static array is mutable on the JVM (a caller can"
                                    + " change it for every later call; UPLC keeps the constant): no static arrays");
                        } else if (!literal(init) || !m.getModifiers().contains(Modifier.FINAL)) {
                            problems.add(c.getQualifiedName() + "." + m.getSimpleName() + ": a static field is final and initialised by a literal"
                                    + " (its initialiser runs on the JVM only)");
                        }
                    }
                    case CONSTRUCTOR -> {
                        if (c.getKind() == ElementKind.RECORD && elements.getOrigin(m) == Elements.Origin.EXPLICIT) {
                            problems.add(c.getQualifiedName() + " declares a constructor: its checks would run on the JVM, while JuLC builds a"
                                    + " record as Constr 0 of its fields (catalogue: record constructors)");
                        }
                    }
                    case METHOD -> {
                        if (components.contains(m.getSimpleName().toString()) && ((ExecutableElement) m).getParameters().isEmpty()
                                && declared.containsKey((ExecutableElement) m)) {   // a method written in the source (javac's own has no tree)
                            problems.add(c.getQualifiedName() + "." + m.getSimpleName() + "() overrides a record accessor (JuLC reads the field)");
                        } else if (!m.getModifiers().contains(Modifier.STATIC) && declared.containsKey((ExecutableElement) m)) {
                            // Security S5 (r3): an instance method (of a record, a class or an interface) is reached by dispatch or
                            // through an object, which JuLC does not compile: a library's methods are static.
                            problems.add(c.getQualifiedName() + "." + m.getSimpleName() + "() is an instance method (a library's methods are static)");
                        }
                    }
                    case RECORD, CLASS, INTERFACE, ENUM -> classLevel((TypeElement) m, problems);
                    // Codex C21 (r4): an annotation type can hold static fields and types the rules above would not see.
                    case ANNOTATION_TYPE -> problems.add(c.getQualifiedName() + "." + m.getSimpleName() + " is an annotation type (a library"
                            + " declares none: its members escape the static-state rules)");
                    default -> { }
                }
            }
        }

        /** A literal other than null (a negative number is a unary minus of one). */
        static boolean literal(Tree t) {
            if (t instanceof com.sun.source.tree.UnaryTree u && u.getKind() == Tree.Kind.UNARY_MINUS) t = u.getExpression();
            return t instanceof com.sun.source.tree.LiteralTree && t.getKind() != Tree.Kind.NULL_LITERAL;
        }

        /** A class of the JDK, cardano-client-lib, JuLC or JOUST (no exception: the example library is com.example's, r3 F7). */
        static boolean reservedPackage(String bin) {
            for (String p : List.of("java.", "javax.", "jdk.", "sun.", "com.sun.", "com.bloxbean.", "org.julclang.")) {
                if (bin.startsWith(p)) return true;
            }
            return false;
        }

        static boolean annotated(Element e, String annotation) {
            return e.getAnnotationMirrors().stream().anyMatch(a ->
                    ((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotation));
        }

        static TypeElement outermost(Element e) {
            Element c = e;
            while (!(c instanceof TypeElement t && t.getNestingKind() == javax.lang.model.element.NestingKind.TOP_LEVEL)) c = c.getEnclosingElement();
            return (TypeElement) c;
        }

        /** The path a unit was given by (the library's own, relative), whatever javac made of it. */
        String pathOf(CompilationUnitTree cu) {
            String n = cu.getSourceFile().getName().replace('\\', '/');
            for (String p : paths.keySet()) if (n.equals(p) || n.endsWith("/" + p)) return p;
            return n;
        }

        String where(CompilationUnitTree cu, long pos) {
            String f = pathOf(cu);
            return pos < 0 ? f : f + ":" + cu.getLineMap().getLineNumber(pos);
        }

        String where(TreePath path) {
            CompilationUnitTree cu = path.getCompilationUnit();
            return where(cu, trees.getSourcePositions().getStartPosition(cu, path.getLeaf()));
        }

        // ---------------------------------------------------------- signatures

        /** A Java type as {@code .jsc} sees it; a record of the library's sources is described into {@code records}. */
        JavaLibraries.JType map(TypeMirror t, Map<String, JavaLibraries.Rec> records, List<String> problems) {
            switch (t.getKind()) {
                case BOOLEAN:
                    return JavaLibraries.JType.BOOL;
                case INT: case LONG: case SHORT: case BYTE: case CHAR: case FLOAT: case DOUBLE:
                    return JavaLibraries.JType.unmappable("a fixed-width number (only JOUST's adapters of D8 bridge those, after a domain check)");
                case ARRAY:
                    return ((ArrayType) t).getComponentType().getKind() == TypeKind.BYTE ? JavaLibraries.JType.BYTES
                            : JavaLibraries.JType.unmappable("an array other than byte[]");
                case TYPEVAR:
                    return JavaLibraries.JType.unmappable("a type variable (no generics in .jsc)");
                case DECLARED:
                    break;
                default:
                    return JavaLibraries.JType.unmappable("no .jsc counterpart");
            }
            DeclaredType d = (DeclaredType) t;
            TypeElement e = (TypeElement) d.asElement();
            String q = e.getQualifiedName().toString();
            switch (q) {
                case "java.math.BigInteger":
                    return JavaLibraries.JType.INT;
                case "java.lang.Boolean":
                    return JavaLibraries.JType.BOOL;
                case "org.julclang.core.types.JulcList": {
                    // Codex C6 (r2): a raw type has no argument; it is unmappable, per fn, never a crash of the whole library.
                    if (d.getTypeArguments().isEmpty()) return JavaLibraries.JType.unmappable("a raw JulcList (no element type)");
                    JavaLibraries.JType el = map(d.getTypeArguments().get(0), records, problems);
                    return JavaLibraries.JType.list(el);
                }
                case "java.util.Optional": {
                    if (d.getTypeArguments().isEmpty()) return JavaLibraries.JType.unmappable("a raw Optional (no element type)");
                    TypeMirror arg = d.getTypeArguments().get(0);
                    if (arg instanceof DeclaredType ad && ((TypeElement) ad.asElement()).getQualifiedName().contentEquals("java.util.Optional")) {
                        return JavaLibraries.JType.unmappable("Optional<Optional<…>> (JavaScript cannot tell Some(None) from None, D5)");
                    }
                    return JavaLibraries.JType.option(map(arg, records, problems));
                }
                case "org.julclang.core.types.JulcMap":
                    return JavaLibraries.JType.unmappable("a map (a map crossing into .jsc needs a canonical-order check, D5; not in M4b)");
                case "org.julclang.core.PlutusData":
                    return JavaLibraries.JType.unmappable("PlutusData (no raw data in .jsc, D5)");
                default:
                    break;
            }
            if (e.getKind() == ElementKind.RECORD && classPath.containsKey(elements.getBinaryName(e).toString())) {
                return JavaLibraries.JType.record(record(e, records, problems).binaryName());
            }
            if (q.startsWith("org.julclang.ledger.") || q.contains("Value") || q.startsWith("org.julclang.state.api.")) {
                return JavaLibraries.JType.unmappable(q + " (ledger and value types come with M2 and M3; D5)");
            }
            return JavaLibraries.JType.unmappable(q + " has no .jsc counterpart");
        }

        /** A library record: read-only and verified when its encoding is its fields in order, every field mapped, no constructor logic. */
        JavaLibraries.Rec record(TypeElement e, Map<String, JavaLibraries.Rec> records, List<String> problems) {
            String bin = elements.getBinaryName(e).toString();
            JavaLibraries.Rec done = records.get(bin);
            if (done != null) return done;
            // A placeholder while its fields are mapped (a record containing itself is opaque).
            records.put(bin, new JavaLibraries.Rec(bin, e.getSimpleName().toString(), "", "", "", List.of(), List.of(), false, "being described"));
            var fieldNames = new ArrayList<String>();
            var fieldTypes = new ArrayList<JavaLibraries.JType>();
            String why = null;
            for (var c : Records.components(e)) {
                fieldNames.add(c.getSimpleName().toString());
                JavaLibraries.JType ft = map(c.asType(), records, problems);
                fieldTypes.add(ft);
                if (why == null && ft.unmappable() != null) why = "its field " + c.getSimpleName() + " is " + c.asType() + ": " + ft.unmappable();
                if (why == null && ft.kind().equals("Record") && !records.get(ft.record()).verified()) {
                    why = "its field " + c.getSimpleName() + " is the opaque record " + records.get(ft.record()).simpleName();
                }
            }
            // A declared constructor: its logic runs on the JVM, and JuLC builds a record as Constr 0 of its fields
            // (catalogue: record constructors). Opaque here, and a problem of the library (below).
            for (Element m : e.getEnclosedElements()) {
                if (m.getKind() == ElementKind.CONSTRUCTOR && elements.getOrigin(m) == Elements.Origin.EXPLICIT) {
                    why = "it declares a constructor (its logic runs on the JVM; JuLC builds a record from its fields)";
                    problems.add(e.getQualifiedName() + " declares a constructor: its checks would run on the JVM, while JuLC builds a record"
                            + " as Constr 0 of its fields (catalogue: record constructors); validate in a fn instead");
                }
            }
            Element owner = e.getEnclosingElement();
            String canonicalOwner = owner instanceof PackageElement p ? p.getQualifiedName().toString() : ((TypeElement) owner).getQualifiedName().toString();
            TypeElement top = outermost(e);
            String sourceName = e.getQualifiedName().toString().substring(top.getQualifiedName().length() - top.getSimpleName().length());
            var r = new JavaLibraries.Rec(bin, e.getSimpleName().toString(), canonicalOwner, top.getQualifiedName().toString(), sourceName,
                    why == null ? List.copyOf(fieldNames) : List.of(), why == null ? List.copyOf(fieldTypes) : List.of(), why == null, why);
            records.put(bin, r);
            return r;
        }

        // ---------------------------------------------------------- bodies

        /** The certification of one body: its findings into {@code problems}, the library methods it calls into {@code work}. */
        void body(TreePath path, List<String> problems, ArrayDeque<ExecutableElement> work) {
            String in = ((ExecutableElement) trees.getElement(path)).getSimpleName().toString();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                    Element el = trees.getElement(getCurrentPath());
                    if (el instanceof ExecutableElement callee) {
                        call(callee, node, getCurrentPath(), problems, work);
                        traced(callee, node, getCurrentPath(), in, problems);
                    }
                    else problems.add(where(getCurrentPath()) + ": a call javac does not resolve");
                    return super.visitMethodInvocation(node, unused);
                }

                @Override
                public Void visitNewClass(NewClassTree node, Void unused) {
                    Element el = trees.getElement(getCurrentPath());
                    TypeElement t = el == null ? null : (TypeElement) el.getEnclosingElement();
                    boolean own = t != null && classPath.containsKey(elements.getBinaryName(t).toString());
                    if (own && t.getKind() == ElementKind.RECORD) {
                        if (el instanceof ExecutableElement ctor) work.add(ctor);
                    } else if (t != null && t.getQualifiedName().contentEquals("java.math.BigInteger")) {
                        // new BigInteger("…"): JuLC folds a literal (D5); anything else is not a constant.
                        if (node.getArguments().size() != 1 || node.getArguments().get(0).getKind() != Tree.Kind.STRING_LITERAL) {
                            problems.add(where(getCurrentPath()) + ": new BigInteger(…) of anything but a string literal");
                        }
                    } else {
                        problems.add(where(getCurrentPath()) + ": constructs " + (t == null ? "an unknown class" : t.getQualifiedName())
                                + " (only the library's own records, and BigInteger literals)");
                    }
                    return super.visitNewClass(node, unused);
                }

                @Override
                public Void visitNewArray(NewArrayTree node, Void unused) {
                    problems.add(where(getCurrentPath()) + ": creates an array (no arrays on-chain but byte strings)");
                    return super.visitNewArray(node, unused);
                }

                @Override
                public Void visitTypeCast(TypeCastTree node, Void unused) {
                    // Catalogue: reference casts. (R) (Object) Builtins.constrData(…) throws on the JVM and passes on-chain.
                    problems.add(where(getCurrentPath()) + ": a cast (" + node.getType() + "): an unchecked representation cast holds on-chain"
                            + " and throws on the JVM (ADR-0018 D9)");
                    return super.visitTypeCast(node, unused);
                }

                @Override
                public Void visitBinary(BinaryTree node, Void unused) {
                    if (node.getKind() == Tree.Kind.AND || node.getKind() == Tree.Kind.OR || node.getKind() == Tree.Kind.XOR) {
                        TypeMirror l = trees.getTypeMirror(new TreePath(getCurrentPath(), node.getLeftOperand()));
                        if (l != null && (l.getKind() == TypeKind.BOOLEAN || l.toString().equals("java.lang.Boolean"))) {
                            problems.add(where(getCurrentPath()) + ": a non-short-circuit boolean operator (" + node.getKind().name().toLowerCase()
                                    + "); use && or ||");
                        }
                    }
                    if (node.getKind() == Tree.Kind.EQUAL_TO || node.getKind() == Tree.Kind.NOT_EQUAL_TO) {
                        TypeMirror l = trees.getTypeMirror(new TreePath(getCurrentPath(), node.getLeftOperand()));
                        TypeMirror r = trees.getTypeMirror(new TreePath(getCurrentPath(), node.getRightOperand()));
                        if (reference(l) || reference(r)) {
                            // Catalogue: reference equality. On-chain == compares contents; on the JVM, references.
                            problems.add(where(getCurrentPath()) + ": '" + node.getKind().name().toLowerCase().replace('_', ' ') + "' on references ("
                                    + l + "): it compares contents on-chain and references on the JVM; use ByteStringLib.equals or compareTo");
                        }
                    }
                    return super.visitBinary(node, unused);
                }

                /** For-each loops around the code being scanned (a lambda body starts again at 0). */
                int forEach;

                @Override
                public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                    int outer = forEach;
                    forEach = 0;
                    try {
                        return super.visitLambdaExpression(node, unused);
                    } finally {
                        forEach = outer;
                    }
                }

                // Security S4 (r2): refused by the certifier itself, not left to JuLC.
                @Override
                public Void visitMemberReference(com.sun.source.tree.MemberReferenceTree node, Void unused) {
                    problems.add(where(getCurrentPath()) + ": a method reference (" + node + "): its target is not followed; write a lambda");
                    return super.visitMemberReference(node, unused);
                }

                @Override
                public Void visitLiteral(com.sun.source.tree.LiteralTree node, Void unused) {
                    if (node.getKind() == Tree.Kind.NULL_LITERAL) problems.add(where(getCurrentPath()) + ": null (no null on-chain; use Optional)");
                    return super.visitLiteral(node, unused);
                }

                // Security S6 (r4): writing an array element changes the caller's array on the JVM (a state's key bytes,
                // for every later read); there are no arrays on-chain but immutable byte strings.
                void arrayWrite(Tree target) {
                    // Codex C25, design F11 (r5): (b[0]) = 9 writes the element too.
                    while (target instanceof com.sun.source.tree.ParenthesizedTree p) target = p.getExpression();
                    if (target instanceof com.sun.source.tree.ArrayAccessTree) {
                        problems.add(where(getCurrentPath()) + ": writes an array element (it changes the caller's array on the JVM; byte"
                                + " strings are immutable on-chain)");
                    }
                }

                @Override
                public Void visitAssignment(com.sun.source.tree.AssignmentTree node, Void unused) {
                    arrayWrite(node.getVariable());
                    return super.visitAssignment(node, unused);
                }

                @Override
                public Void visitCompoundAssignment(com.sun.source.tree.CompoundAssignmentTree node, Void unused) {
                    arrayWrite(node.getVariable());
                    return super.visitCompoundAssignment(node, unused);
                }

                @Override
                public Void visitUnary(com.sun.source.tree.UnaryTree node, Void unused) {
                    switch (node.getKind()) {
                        case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> arrayWrite(node.getExpression());
                        default -> { }
                    }
                    return super.visitUnary(node, unused);
                }

                @Override
                public Void visitContinue(com.sun.source.tree.ContinueTree node, Void unused) {
                    problems.add(where(getCurrentPath()) + ": continue (not lowered on-chain)");
                    return super.visitContinue(node, unused);
                }

                @Override
                public Void visitEnhancedForLoop(com.sun.source.tree.EnhancedForLoopTree node, Void unused) {
                    forEach++;
                    try {
                        return super.visitEnhancedForLoop(node, unused);
                    } finally {
                        forEach--;
                    }
                }

                @Override
                public Void visitReturn(com.sun.source.tree.ReturnTree node, Void unused) {
                    if (forEach > 0) problems.add(where(getCurrentPath()) + ": a return inside a for-each loop (not lowered on-chain)");
                    return super.visitReturn(node, unused);
                }

                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    problems.add(where(getCurrentPath()) + ": a class declared inside a method body");
                    return null;
                }
            }.scan(path, null);
        }

        /**
         * The messages a call can trace: a {@code Require.that} literal (a message that is not one is a problem, so the
         * map stays complete, J20), and a D8 adapter's fixed messages.
         */
        void traced(ExecutableElement callee, MethodInvocationTree node, TreePath at, String in, List<String> problems) {
            TypeElement owner = (TypeElement) callee.getEnclosingElement();
            String sig = owner.getQualifiedName() + "." + callee.getSimpleName() + "(" + String.join(",",
                    callee.getParameters().stream().map(p -> erased(p.asType())).toList()) + "):" + erased(callee.getReturnType());
            CompilationUnitTree cu = at.getCompilationUnit();
            long pos = trees.getSourcePositions().getStartPosition(cu, node);
            int line = pos < 0 ? 0 : (int) cu.getLineMap().getLineNumber(pos);
            if (sig.equals("org.julclang.state.api.Require.that(boolean,java.lang.String):void")) {
                var msg = node.getArguments().get(1);
                if (msg instanceof com.sun.source.tree.LiteralTree lit && lit.getValue() instanceof String text) {
                    messages.add(new JavaLibraries.Message(text, "require", pathOf(cu), line, in));
                } else {
                    problems.add(where(at) + ": a Require message that is not a string literal (every message a script can trace is"
                            + " listed in its map, D12)");
                }
                return;
            }
            for (String m : JscV2.bindingMessages(sig)) messages.add(new JavaLibraries.Message(m, "operation", pathOf(cu), line, in));
        }

        static boolean reference(TypeMirror t) {
            return t != null && (t.getKind() == TypeKind.DECLARED || t.getKind() == TypeKind.ARRAY);
        }

        /** A call: into the library (certified in turn), into the trusted base (allowed or not), or elsewhere (refused). */
        void call(ExecutableElement callee, MethodInvocationTree node, TreePath at, List<String> problems, ArrayDeque<ExecutableElement> work) {
            TypeElement owner = (TypeElement) callee.getEnclosingElement();
            String ownerName = owner.getQualifiedName().toString();
            String name = callee.getSimpleName().toString();
            // Catalogue: equals, hashCode and toString compare or show contents on-chain and identities on the JVM (a
            // record holding bytes compares its arrays by reference), whoever declares them; BigInteger's equals is exact.
            String sig0 = ownerName + "." + name + "(" + String.join(",", callee.getParameters().stream().map(p -> erased(p.asType())).toList())
                    + "):" + erased(callee.getReturnType());
            // A record's or class's implicit super() (java.lang.Record, java.lang.Object): no code of its own.
            if (callee.getKind() == ElementKind.CONSTRUCTOR && (ownerName.equals("java.lang.Record") || ownerName.equals("java.lang.Object"))) return;
            if ((name.equals("equals") || name.equals("hashCode") || name.equals("toString")) && !ownerName.equals("java.math.BigInteger")
                    && !API.contains(sig0)) {
                problems.add(where(at) + ": " + name + "() (content on-chain, identity or text on the JVM: catalogue F16; compare with"
                        + " ByteStringLib.equals, compareTo or a field-by-field fn)");
                return;
            }
            if (classPath.containsKey(elements.getBinaryName(owner).toString())) {
                // Security S4 (r2): an abstract method (an interface's) dispatches to a body the certifier does not follow.
                if (callee.getModifiers().contains(Modifier.ABSTRACT)) {
                    problems.add(where(at) + ": calls the abstract " + ownerName + "." + name + " (dynamic dispatch: the body that runs is not"
                            + " certified); call a static method");
                    return;
                }
                work.add(callee);
                return;
            }
            String sig = ownerName + "." + name + "(" + String.join(",", callee.getParameters().stream().map(p -> erased(p.asType())).toList())
                    + "):" + erased(callee.getReturnType());
            switch (ownerName) {
                case "java.math.BigInteger" -> {
                    if (!BIG_INTEGER.contains(name)) problems.add(where(at) + ": BigInteger." + name + " (not one of " + new TreeSet<>(BIG_INTEGER)
                            + ": e.g. mod and pow mean other things, or take fixed-width numbers)");
                }
                case "org.julclang.core.types.JulcList" -> {
                    if (name.equals("find")) {
                        problems.add(where(at) + ": JulcList.find (null on the JVM on a miss, a failing field read on-chain: catalogue F17)");
                    } else if (MEMBERSHIP.contains(name)) {
                        membership(at, node, "JulcList." + name, problems);
                    } else if (!JULC_LIST.contains(name)) {
                        problems.add(where(at) + ": JulcList." + name + " (not in the allow-list " + new TreeSet<>(JULC_LIST) + ")");
                    }
                }
                case "org.julclang.core.types.JulcMap" -> {
                    if (name.equals("get")) {
                        problems.add(where(at) + ": JulcMap.get (null on the JVM on a miss, a failure on-chain, for every key and value type:"
                                + " use lookup, an Optional on both sides; catalogue)");
                    } else if (MEMBERSHIP.contains(name) || name.equals("lookup") || name.equals("delete") || name.equals("insert")) {
                        // Security S4 (r2): a lookup, delete or insert compares keys too.
                        membership(at, node, "JulcMap." + name, problems);
                    } else if (!JULC_MAP.contains(name)) {
                        problems.add(where(at) + ": JulcMap." + name + " (not in the allow-list " + new TreeSet<>(JULC_MAP) + ")");
                    }
                }
                case "java.util.Optional" -> {
                    if (!OPTIONAL.contains(name)) problems.add(where(at) + ": Optional." + name + " (not in the allow-list " + new TreeSet<>(OPTIONAL) + ")");
                }
                default -> {
                    if (API.contains(sig) || bindings.contains(sig)) return;
                    if (ownerName.equals("java.lang.Object") || name.equals("equals") || name.equals("hashCode") || name.equals("toString")) {
                        problems.add(where(at) + ": " + name + "() (content on-chain, identity or text on the JVM: catalogue; compare with"
                                + " ByteStringLib.equals or compareTo)");
                        return;
                    }
                    problems.add(where(at) + ": calls " + sig + ", which is outside the library and the trusted base (the JuLC stdlib through"
                            + " D8's binding table, JOUST's Require and adapters; ADR-0018 D9): a builtin or library function is admitted only"
                            + " with the same semantics and failures on every runtime");
                }
            }
        }

        /** Membership by content: only element (key) types compared by content on both sides (BigInteger, Boolean, byte[]). */
        void membership(TreePath at, MethodInvocationTree node, String what, List<String> problems) {
            TypeMirror receiver = node.getMethodSelect() instanceof com.sun.source.tree.MemberSelectTree ms
                    ? trees.getTypeMirror(new TreePath(at, ms.getExpression())) : null;
            TypeMirror elem = receiver instanceof DeclaredType d && !d.getTypeArguments().isEmpty() ? d.getTypeArguments().get(0) : null;
            String e = elem == null ? "" : erased(elem);
            if (!(e.equals("java.math.BigInteger") || e.equals("java.lang.Boolean") || e.equals("byte[]"))) {
                problems.add(where(at) + ": " + what + " on " + (elem == null ? "an unknown type" : elem) + " (on a type holding bytes in a"
                        + " record, list or option it compares references on the JVM and contents on-chain: catalogue F16)");
            }
        }

        String erased(TypeMirror t) {
            if (t.getKind() == TypeKind.ARRAY) return erased(((ArrayType) t).getComponentType()) + "[]";
            if (t instanceof DeclaredType d) return ((TypeElement) d.asElement()).getQualifiedName().toString();
            return t.toString();
        }
    }
}
