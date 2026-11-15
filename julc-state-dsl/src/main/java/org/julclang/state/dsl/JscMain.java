package org.julclang.state.dsl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lowers every {@code .jsc} file under a source directory to Java (ADR-0005 D5):
 * {@code JscMain <srcDir> <outDir>}. Errors print as {@code file:line:col: error: message}; the exit code is 1 if any.
 */
public final class JscMain {

    private JscMain() {}

    /** The Java source for one .jsc file's text. */
    public static String lower(String source, String fileName) {
        return lowerWithWarnings(source, fileName).java();
    }

    /**
     * The Java source and warnings for one .jsc file's text. {@code fileName} is the bare file name, embedded in the
     * generated Java: it may contain only ASCII letters, digits, '.', '_' and '-' (security S3).
     */
    public static Lowered lowerWithWarnings(String source, String fileName) {
        return lowerWithWarnings(source, fileName, null, org.julclang.state.dsl.v2.JscModules.NONE);
    }

    /**
     * As {@link #lowerWithWarnings(String, String)}, for the module {@code id} of a build whose other {@code .jsc} 2 files
     * {@code modules} finds (ADR-0018 D9): a contract's library imports (M4) and {@code import contract} imports (M2c)
     * resolve, and a library lowers to its {@code @OnchainLibrary} class.
     */
    public static Lowered lowerWithWarnings(String source, String fileName, String id, org.julclang.state.dsl.v2.JscModules modules) {
        return lowerWithWarnings(source, fileName, id, modules, org.julclang.state.dsl.v2.JavaLibraries.NONE);
    }

    /** As above, with the certified Java libraries its {@code java:} imports may name (ADR-0018 D9, M4b). */
    public static Lowered lowerWithWarnings(String source, String fileName, String id, org.julclang.state.dsl.v2.JscModules modules,
                                            org.julclang.state.dsl.v2.JavaLibraries javaLibraries) {
        var r = org.julclang.state.dsl.v2.JscV2.lower(source, fileName, id, modules, javaLibraries);
        return new Lowered(r.java(), r.diagnostics().stream().map(w -> new Warning(w.line(), w.col(), w.code(), w.message())).toList(),
                fileName, r.sourceMapJson(), r.abiJson(), r.irJson(), r.libraries());
    }

    /** A located warning: line and column in the .jsc file, and its stable code (D12). */
    public record Warning(int line, int col, String code, String message) {
        /** {@code file:line:col: warning[CODE]: message} ({@code warning:} without a code). */
        public String format(String file) {
            return file + ":" + line + ":" + col + ": warning" + (code == null ? "" : "[" + code + "]") + ": " + message;
        }
    }

    /**
     * Lowered Java and located warnings, the source map and the ABI manifest (ADR-0018 D12) and the checked IR as JSON
     * (D15, M1b).
     */
    public record Lowered(String java, java.util.List<Warning> diagnostics, String fileName, String sourceMapJson, String abiJson,
                          String irJson, java.util.List<org.julclang.state.dsl.v2.JscV2.LibraryUse> libraries) {
        /** The warnings, each formatted {@code file:line:col: warning: message}. */
        public java.util.List<String> warnings() {
            return diagnostics.stream().map(w -> w.format(fileName)).toList();
        }
    }

    /**
     * The text of JSC0005: an internal error of the front end on this file (an exception or an Error, a
     * StackOverflowError included), which the build reports at the file instead of crashing (ADR-0018 J18).
     */
    public static String internalError(Throwable e) {
        String what = e instanceof StackOverflowError ? "the front end ran out of stack (a construct nested deeper than the nesting cap"
                + " lets through)" : e.toString();
        return "internal error lowering this file: " + what + " (an internal error of the .jsc front end: please report it)";
    }

    /** The package and class name the .jsc text declares, as a relative path {@code a/b/Name.java}. */
    static String outputPath(String source) {
        return outputPath(source, null);
    }

    /** As {@link #outputPath(String)}; a library's class is its capitalised file name {@code fileName}. */
    public static String outputPath(String source, String fileName) {
        return org.julclang.state.dsl.v2.JscV2.outputPath(source, fileName);
    }

    /**
     * ADR-0018 D12 (review F1, S3): the ABI manifest is committed beside the {@code .jsc} file, and a build compares the
     * lowering's manifest with it. Equal passes. A different or missing manifest fails the build (JSC0002, JSC0003), unless
     * the build was asked to accept it: {@code JscMain --accept-abi} (Gradle: the {@code acceptJscAbi} task; npm:
     * {@code joust build --accept-abi}), which writes it, to be committed. Nothing else accepts: no system or Gradle
     * property, so neither {@code gradle.properties} nor an environment can switch the gate off.
     *
     * @return the diagnostic to print, or null when the manifest is accepted
     */
    /**
     * ADR-0021 D7a (ADR-0018 D12 as amended): an acceptance of one contract, bound to the committed manifest's SHA-256
     * ({@code none} when nothing is committed) and the new one's: {@code --accept-abi=<Contract>@<committed>..<new>}
     * (Gradle: {@code acceptJscAbi --contract C --committed-sha256 h1 --sha256 h2}).
     */
    public record Bound(String contract, String committed, String now) {
        static Bound parse(String arg) {
            // S5, F5 on #99: the contract by its qualified name (app.A), or a simple name only when it is unambiguous.
            var m = java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)@(none|[0-9a-f]{64})\\.\\.([0-9a-f]{64})").matcher(arg);
            return m.matches() ? new Bound(m.group(1), m.group(2), m.group(3)) : null;
        }
    }

    /**
     * S5, F5 on #99: the one {@code .jsc} 2 contract file {@code contract} names among {@code files}: by its qualified
     * name ({@code app.A}), or by its simple name when exactly one contract has it. {@code {file, null}}, or
     * {@code {null, error}}.
     */
    static Object[] resolveContract(Path src, List<Path> files, String contract) throws IOException {
        var hits = new ArrayList<Path>();
        var names = new ArrayList<String>();
        for (Path f : files) {
            String rel;
            try {
                rel = outputPath(Files.readString(f), f.getFileName().toString());
            } catch (RuntimeException e) {
                continue;
            }
            String qualified = rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
            String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
            if (contract.contains(".") ? qualified.equals(contract) : simple.equals(contract)) {
                hits.add(f);
                names.add(qualified + " (" + src.relativize(f) + ")");
            }
        }
        if (hits.size() == 1) return new Object[]{hits.get(0), null};
        if (hits.isEmpty()) return new Object[]{null, "no .jsc 2 contract " + contract + " under " + src};
        return new Object[]{null, "the name " + contract + " is ambiguous: " + String.join(", ", names) + "; name the contract by its qualified name"};
    }

    static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The bound acceptance's check of one contract's manifest: null to accept it (and write it), else the refusal. The
     * committed manifest must still be the reviewed one, and the manifest the build computes the reviewed new one.
     */
    static String checkBound(Path committed, String abi, String name, Bound bound) throws IOException {
        boolean exists = Files.exists(committed);
        String old = exists ? Files.readString(committed) : null;
        String was = old == null ? "none" : sha256(old);
        String now = sha256(abi);
        if (!was.equals(bound.committed())) {
            return name + ":1:1: error[JSC0002]: not accepted: the committed manifest " + committed.getFileName() + " is " + (old == null ? "gone"
                    : "now " + was) + ", not the " + (bound.committed().equals("none") ? "absent one" : bound.committed()) + " that was"
                    + " reviewed (it changed, a git pull say); review the diff again (jscAbiDiff, Accept ABI…)";
        }
        if (!now.equals(bound.now())) {
            return name + ":1:1: error[JSC0002]: not accepted: the manifest the build computes now is " + now + ", not the reviewed "
                    + bound.now() + " (a source changed since the diff); review the diff again (jscAbiDiff, Accept ABI…)";
        }
        if (abi.equals(old)) return null;
        Files.writeString(committed, abi);
        System.err.println(name + ": note: " + (exists ? "accepted the changed" : "created the") + " ABI manifest " + committed.getFileName()
                + " (" + bound.committed() + " -> " + now + "): commit it beside the .jsc file");
        return null;
    }

    static String checkAbi(Path committed, String abi, String name, boolean accept) throws IOException {
        boolean exists = Files.exists(committed);
        String old = exists ? Files.readString(committed) : null;
        if (abi.equals(old)) return null;
        if (accept) {
            Files.writeString(committed, abi);
            System.err.println(name + ": note: " + (exists ? "accepted the changed" : "created the") + " ABI manifest " + committed.getFileName()
                    + ": commit it beside the .jsc file");
            return null;
        }
        if (!exists) {
            return name + ":1:1: error[JSC0003]: no ABI manifest " + committed.getFileName() + " beside this file: the encoding of its datum"
                    + " and redeemers must be committed; create it with the acceptJscAbi task (npm: joust build --accept-abi), review it"
                    + " and commit it";
        }
        return name + ":1:1: error[JSC0002]: the ABI manifest " + committed.getFileName() + " differs from the committed one at "
                + firstDifference(old, abi) + ": a datum or redeemer encoding changed, which strands deployed instances; if the change"
                + " is intended, accept it with the acceptJscAbi task (npm: joust build --accept-abi) and commit the new manifest";
    }

    /** {@code line N: expected …, found …}, for the first line where two manifests differ. */
    static String firstDifference(String committed, String now) {
        String[] a = committed.split("\n", -1), b = now.split("\n", -1);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            // A trailing comma is not a difference of its own: an entry added after a line adds one to it.
            String x = i < a.length ? a[i].strip().replaceAll(",$", "") : "(end)", y = i < b.length ? b[i].strip().replaceAll(",$", "") : "(end)";
            if (!x.equals(y)) return "line " + (i + 1) + ": committed " + x + ", now " + y;
        }
        return "(whitespace only)";
    }

    /**
     * {@code JscMain --check-abi <srcDir> <descriptionsDir>} (Gradle's {@code checkJscAbi}, after {@code compileJava};
     * review F12, S10): every {@code .jsc} 2 file's ABI manifest checked against the processor's description of its
     * contract ({@code <descriptionsDir>/<package>.<Contract>.json}), as the toolchain checks it at every build
     * (JSC0004). 0 when all agree.
     */
    public static int checkAgainstDescriptions(Path src, Path descriptions) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(src)) {
            try (Stream<Path> s = Files.walk(src)) {
                s.filter(p -> p.toString().endsWith(".jsc")).sorted().forEach(files::add);
            }
        }
        int errors = 0, checked = 0;
        var modules = modules(src, files);
        for (Path f : files) {
            String text = Files.readString(f);
            if (org.julclang.state.dsl.v2.JscV2.isLibrary(text)) continue;
            String name = src.relativize(f).toString();
            var lowered = org.julclang.state.dsl.v2.JscV2.lower(text, f.getFileName().toString(), id(src, f), modules, javaLibraries);
            String path = lowered.javaPath();
            String pkg = path.contains("/") ? path.substring(0, path.lastIndexOf('/')).replace('/', '.') : "";
            String contract = path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
            Path description = descriptions.resolve(pkg + "." + contract + ".json");
            List<String> problems = Files.exists(description)
                    ? org.julclang.state.dsl.v2.AbiCheck.compare(lowered.abiJson(), Files.readString(description), pkg, contract)
                    : List.of("the processor wrote no description " + description.getFileName());
            for (String p : problems) {
                System.err.println(name + ":1:1: error[JSC0004]: the ABI manifest of " + contract + " disagrees with the processor's"
                        + " encoding: " + p + " (an internal error of the .jsc lowering: please report it)");
            }
            if (!problems.isEmpty()) errors++;
            checked++;
        }
        if (errors > 0) return 1;
        System.out.println("jsc: " + checked + " ABI manifest(s) agree with the processor's descriptions");
        return 0;
    }

    /**
     * {@code JscMain --remap <javacLog> <generatedDir> <srcDir>} (ADR-0018 D12; Gradle runs it after {@code compileJava}
     * fails): javac's and the processors' diagnostics on Java lowered from a {@code .jsc} 2 file
     * ({@code path:line: error: message}, then the source line and a caret) printed again at the {@code .jsc} line and
     * column of the source map beside the Java, with the generated location as a note, as the npm toolchain reports them.
     * Always 0: the compile has failed already, and says so.
     */
    public static int remap(Path log, Path generated, Path src) throws IOException {
        for (String line : remapped(Files.exists(log) ? Files.readString(log) : "", generated, src)) System.err.println(line);
        return 0;
    }

    /** The diagnostics of a javac log that fall on generated Java, at their {@code .jsc} positions (see {@link #remap}). */
    public static List<String> remapped(String log, Path generated, Path src) throws IOException {
        var out = new ArrayList<String>();
        var lines = log.split("\\R");
        Path root = generated.toAbsolutePath().normalize();
        var pattern = Pattern.compile("^(.+\\.java):(\\d+): (error|warning): (.*)$");
        for (int i = 0; i < lines.length; i++) {
            var m = pattern.matcher(lines[i]);
            if (!m.matches()) continue;
            Path unit = Path.of(m.group(1)).toAbsolutePath().normalize();
            if (!unit.startsWith(root)) continue;
            Path map = unit.resolveSibling(unit.getFileName().toString().replace(".java", ".jscmap.json"));
            if (!Files.exists(map)) continue;
            int javaLine = Integer.parseInt(m.group(2));
            // javac prints the source line, then a caret under the column.
            int col = i + 2 < lines.length && lines[i + 2].strip().equals("^") ? lines[i + 2].indexOf('^') + 1 : -1;
            String json = Files.readString(map);
            var name = Pattern.compile("\"source\": \"([^\"]+)\"").matcher(json);
            String bare = name.find() ? name.group(1) : unit.getFileName().toString().replace(".java", ".jsc");
            String rel = root.relativize(unit).toString().replace('\\', '/');
            String jsc = jscPath(src, bare, rel);
            if (jsc == null) {
                // No single .jsc file declares this unit: keep javac's location, say so.
                out.add(unit + ":" + javaLine + ": " + m.group(3) + ": " + m.group(4) + " (generated from " + bare + ", but no single file"
                        + " named " + bare + " under " + src + " declares " + rel.replace(".java", "").replace('/', '.') + ")");
                continue;
            }
            var at = Pattern.compile("\\[" + javaLine + ", (\\d+), (\\d+)\\]").matcher(json);
            boolean mapped = at.find();
            out.add(jsc + ":" + (mapped ? at.group(1) + ":" + at.group(2) : "1:1") + ": " + m.group(3) + ": " + m.group(4)
                    + " (in the Java generated from " + bare + ": " + rel + ":" + javaLine + (col > 0 ? ":" + col : "")
                    + (mapped ? "" : ", a generated helper") + ")");
        }
        if (!out.isEmpty()) out.add("jsc: " + out.size() + " diagnostic(s) above fall on Java generated from .jsc, shown at their .jsc positions");
        return out;
    }

    /**
     * The {@code .jsc} file a generated unit came from, or null: among the files under {@code src} with its source map's
     * file name, the one that declares the unit's package and contract (review r2 C3: as the toolchain names units by
     * package path; two packages may each have a {@code Contract.jsc}). None or several: null, and the diagnostic keeps its
     * generated location.
     */
    static String jscPath(Path src, String bare, String javaRel) throws IOException {
        var found = new ArrayList<Path>();
        if (Files.isDirectory(src)) {
            try (Stream<Path> s = Files.walk(src)) {
                for (Path p : s.filter(x -> x.getFileName().toString().equals(bare) && Files.isRegularFile(x)).sorted().toList()) {
                    // The unit a file declares, as the parser reads it: comments and strings are never declarations
                    // (review r3 C3).
                    try {
                        if (outputPath(Files.readString(p), p.getFileName().toString()).equals(javaRel)) found.add(p);
                    } catch (JscException | IOException e) {
                        // A file that does not parse generated nothing.
                    }
                }
            }
        }
        return found.size() == 1 ? found.get(0).toString() : null;
    }

    /** The texts of the Java libraries' sources of this run, by path. */
    static java.util.Map<String, String> javaSourceTexts = java.util.Map.of();

    /** The Java libraries of this run ({@code --java-libraries}); none unless given. */
    static org.julclang.state.dsl.v2.JavaLibraries javaLibraries = org.julclang.state.dsl.v2.JavaLibraries.NONE;

    /**
     * The Java libraries whose on-chain sources are under {@code dir} (every {@code .java}, by path), certified lazily: a
     * build without {@code java:} imports never runs the certifier (ADR-0018 D9, M4b).
     */
    static org.julclang.state.dsl.v2.JavaLibraries javaLibraries(Path dir) throws IOException {
        javaSourceTexts = java.util.Map.of();
        if (dir == null || !Files.isDirectory(dir)) return org.julclang.state.dsl.v2.JavaLibraries.NONE;
        var sources = librarySources(dir);
        javaSourceTexts = sources;
        var certified = new org.julclang.state.dsl.v2.JavaLibraries[1];
        return fqcn -> {
            if (certified[0] == null) certified[0] = org.julclang.state.dsl.v2.JavaLibraries.certify(sources);
            return certified[0].describe(fqcn);
        };
    }

    /** Every {@code .java} file under {@code dir}, by path relative to it ('/'-separated). */
    public static java.util.Map<String, String> librarySources(Path dir) throws IOException {
        var out = new java.util.TreeMap<String, String>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java") && Files.isRegularFile(x)).sorted().toList()) {
                out.put(dir.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        return out;
    }

    /** The suffix of the file generateJsc writes beside a contract's Java: the classes of its java: libraries, one per line. */
    public static final String LIBRARY_CLASSES = ".java-libraries";

    /**
     * The classes of the {@code java:} libraries each contract lowered into {@code generated} uses, by contract (its
     * fully qualified name): binary names from javac, through the certifier, nested classes too (review r2, S2, F5).
     */
    static java.util.Map<String, List<String>> libraryClasses(Path generated) throws IOException {
        var out = new java.util.TreeMap<String, List<String>>();
        if (generated == null || !Files.isDirectory(generated)) return out;
        try (Stream<Path> s = Files.walk(generated)) {
            for (Path f : s.filter(x -> x.toString().endsWith(LIBRARY_CLASSES) && Files.isRegularFile(x)).sorted().toList()) {
                String rel = generated.relativize(f).toString().replace('\\', '/');
                String contract = rel.substring(0, rel.length() - LIBRARY_CLASSES.length()).replace('/', '.');
                out.put(contract, Files.readAllLines(f).stream().map(String::strip).filter(l -> !l.isEmpty()).toList());
            }
        }
        return out;
    }

    /**
     * {@code JscMain --check-library-classes <generatedDir> <classpath>} (ADR-0018 D9, M4b; the Gradle plugin's
     * {@code checkJoustLibraries}, before {@code compileJava}): a class compiled from a {@code java:} library's certified
     * sources must not also reach the compile or runtime class path from a jar or directory, where it could shadow the
     * compiled one: the JVM side runs the certified sources, never a jar's classes. {@code classpath} is the class path
     * without the project's own outputs. The classes are those generateJsc listed (javac's binary names, nested ones too).
     * 0 when none does.
     */
    public static int checkLibraryClasses(Path generated, String classpath) throws IOException {
        var classes = new java.util.TreeSet<String>();
        libraryClasses(generated).values().forEach(classes::addAll);
        int found = 0;
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path p = Path.of(entry);
            if (Files.isDirectory(p)) {
                for (String c : classes) {
                    if (Files.exists(p.resolve(c.replace('.', '/') + ".class"))) {
                        System.err.println(p + ": error[JSC0727]: " + c + " is on the class path here, and is also compiled"
                                + " from the java: library's sources: the JVM side must run those (ADR-0018 D9); remove this dependency, or use the"
                                + " library through the joustLibrary configuration only");
                        found++;
                    }
                }
            } else if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) {
                try (var jar = new java.util.jar.JarFile(p.toFile())) {
                    for (String c : classes) {
                        String name = c.replace('.', '/') + ".class";
                        boolean versioned = jar.stream().anyMatch(e -> e.getName().startsWith("META-INF/versions/") && e.getName().endsWith("/" + name));
                        if (jar.getEntry(name) != null || versioned) {
                            System.err.println(p + ": error[JSC0727]: " + c + " is in this jar on the class path, and is also"
                                    + " compiled from the java: library's sources: the jar's class could shadow the certified one (ADR-0018 D9);"
                                    + " take the library only through the joustLibrary configuration");
                            found++;
                        }
                    }
                }
            }
        }
        if (found > 0) return 1;
        System.out.println("jsc: no class of the " + classes.size() + " java: library class(es) reaches the class path from elsewhere");
        return 0;
    }

    /** Where a contract's record of its java: library classes is, in its classes (one resource per contract). */
    public static String libraryRecord(String contract) {
        return "META-INF/joust/java-libraries/" + contract + ".json";
    }

    /**
     * {@code JscMain --library-manifest <generatedDir> <classesDir>} (ADR-0018 D9, M4b; after {@code compileJava}): for
     * each contract built with {@code java:} libraries, the SHA-256 of every class file compiled from their certified
     * sources, into {@code <classesDir>/META-INF/joust/java-libraries/<contract>.json} (a resource per contract, so that a
     * merged jar keeps each; review r2 F2). The contract's client checks the bytes that define each class against it when the
     * contract is loaded ({@code JavaLibraryCheck}), and refuses to run on a mismatch or when its record is missing. A
     * project whose contracts use no {@code java:} library gets no record. 1 when a listed class was not compiled.
     */
    public static int libraryManifest(Path generated, Path classes) throws IOException {
        Path dir = classes.resolve("META-INF/joust/java-libraries");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.deleteIfExists(classes.resolve("META-INF/joust/java-libraries.json"));   // the r1 form: one file per output
        int n = 0;
        for (var e : libraryClasses(generated).entrySet()) {
            var hashes = new java.util.TreeMap<String, String>();
            for (String c : e.getValue()) {
                Path f = classes.resolve(c.replace('.', '/') + ".class");
                if (!Files.isRegularFile(f)) {
                    System.err.println(f + ": error[JSC0727]: " + c + ", a class of a java: library " + e.getKey() + " uses, was not compiled"
                            + " into this project's classes (ADR-0018 D9)");
                    return 1;
                }
                hashes.put(c, sha256(Files.readAllBytes(f)));
            }
            Path out = classes.resolve(libraryRecord(e.getKey()));
            Files.createDirectories(out.getParent());
            var json = new StringBuilder("{\n  \"version\": 2,\n  \"contract\": \"").append(e.getKey()).append("\",\n  \"classes\": {");
            int i = 0;
            for (var h : hashes.entrySet()) json.append(i++ == 0 ? "\n" : ",\n").append("    \"").append(h.getKey()).append("\": \"").append(h.getValue()).append('"');
            json.append(hashes.isEmpty() ? "}\n}\n" : "\n  }\n}\n");
            Files.writeString(out, json.toString());
            n += hashes.size();
        }
        System.out.println("jsc: recorded the class files of " + n + " java: library class(es) for " + libraryClasses(generated).size() + " contract(s)");
        return 0;
    }

    static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A file's module id in a build over {@code src}: its relative path, '/'-separated (ADR-0018 D9, M4). */
    static String id(Path src, Path f) {
        return src.relativize(f).toString().replace('\\', '/');
    }

    /** The {@code .jsc} files of a source tree as modules, by id: what their {@code ./x.jsc} imports resolve to. */
    static org.julclang.state.dsl.v2.JscModules modules(Path src, List<Path> files) throws IOException {
        var texts = new java.util.LinkedHashMap<String, String>();
        for (Path f : files) texts.put(id(src, f), Files.readString(f));
        return org.julclang.state.dsl.v2.JscModules.of(texts);
    }

    /**
     * ADR-0021 D7a: the committed ABI manifest of {@code contract} and the one the build computes now, each with its SHA-256
     * ({@code none} when nothing is committed); printed, and with {@code out} written as JSON there (the extension's own
     * storage). Nothing is written into the project. 0, or 1 when there is no such .jsc 2 contract or it does not lower.
     */
    static int abiDiff(Path src, String contract, Path out) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(src)) {
            try (Stream<Path> s = Files.walk(src)) {
                s.filter(p -> p.toString().endsWith(".jsc")).sorted().forEach(files::add);
            }
        }
        var modules = modules(src, files);
        Object[] target = resolveContract(src, files, contract);
        if (target[0] == null) {
            System.err.println("jsc: " + target[1]);
            return 1;
        }
        for (Path f : List.of((Path) target[0])) {
            String text = Files.readString(f);
            String rel = outputPath(text, f.getFileName().toString());
            String qualified = rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
            contract = qualified.substring(qualified.lastIndexOf('.') + 1);
            Lowered lowered;
            try {
                lowered = lowerWithWarnings(text, f.getFileName().toString(), id(src, f), modules, javaLibraries);
            } catch (JscException e) {
                System.err.println(e.format(src.relativize(f).toString()));
                return 1;
            }
            if (lowered.abiJson() == null) continue;
            Path committed = f.resolveSibling(contract + ".abi.json");
            String old = Files.exists(committed) ? Files.readString(committed) : null;
            String was = old == null ? "none" : sha256(old);
            String now = sha256(lowered.abiJson());
            System.out.println("--- committed " + src.relativize(committed) + " sha256 " + was);
            System.out.println(old == null ? "(none)" : old);
            System.out.println("+++ now sha256 " + now);
            System.out.println(lowered.abiJson());
            System.out.println(was.equals(now) ? "unchanged" : "accept it with: ./gradlew acceptJscAbi --contract " + qualified
                    + " --committed-sha256 " + was + " --sha256 " + now);
            if (out != null) {
                // C10 on #99 r2: one base for both backends: "path" is relative to the tool's working directory (the project:
                // npm's joust runs at its root, Gradle's JavaExec at the project's directory), as joust abi diff writes it;
                // "file" is the .jsc file under the sources directory. A manifest outside the working directory: absolute.
                Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
                Path abs = committed.toAbsolutePath().normalize();
                String path = (abs.startsWith(cwd) ? cwd.relativize(abs) : abs).toString().replace('\\', '/');
                var o = new StringBuilder("{\"contract\":");
                org.julclang.state.dsl.v2.JscLangJson.str(o, qualified);
                o.append(",\"file\":");
                org.julclang.state.dsl.v2.JscLangJson.str(o, src.relativize(f).toString().replace('\\', '/'));
                o.append(",\"path\":");
                org.julclang.state.dsl.v2.JscLangJson.str(o, path);
                o.append(",\"committed\":{\"sha256\":\"").append(was).append("\",\"text\":");
                org.julclang.state.dsl.v2.JscLangJson.str(o, old);
                o.append("},\"now\":{\"sha256\":\"").append(now).append("\",\"text\":");
                org.julclang.state.dsl.v2.JscLangJson.str(o, lowered.abiJson());
                o.append("}}\n");
                Files.writeString(out, o.toString());
            }
            return 0;
        }
        System.err.println("jsc: no .jsc 2 contract " + contract + " under " + src);
        return 1;
    }

    /** {@code --fmt}'s exit codes (#120 F5, §15): formatted (or, with --check, already); not formatted; refused; usage. */
    static final int FMT_OK = 0, FMT_NOT_FORMATTED = 1, FMT_REFUSED = 2, FMT_USAGE = 3;

    /**
     * {@code --fmt [--check] <file or dir>...} (ADR-0021 r9 §15): formats each {@code .jsc} file in place (a directory:
     * every {@code .jsc} file under it, links not followed), writing only a file whose text changes; with {@code --check},
     * writes nothing and lists the files that would change. A file is read as UTF-8, strictly (#120 F1), and formatted as
     * a module of its directory argument's files (C3: its imports read as the build reads them; a file argument with its
     * own directory's). A file is refused, and left as it was, when it does not lex or parse (F4), is not valid UTF-8,
     * or is read-only (F2: it is never made writable); a rewritten file keeps its permissions. A linked directory
     * argument is refused (S2). Exit 0: every file formatted (with --check: already was); 1 (--check): a file is not
     * formatted; 2: a file was refused; 3: a usage error.
     */
    static int fmt(List<String> args) throws IOException {
        boolean check = args.contains("--check");
        var paths = args.stream().filter(a -> !a.equals("--check")).toList();
        if (paths.isEmpty() || paths.stream().anyMatch(a -> a.startsWith("--"))) {
            System.err.println("usage: JscMain --fmt [--check] <file or dir>...   (exit 0 formatted, 1 not formatted (--check), 2 refused, 3 usage)");
            return FMT_USAGE;
        }
        // Each file with the root its module id is relative to (its directory argument, or its own directory).
        var files = new ArrayList<Path[]>();
        for (String a : paths) {
            Path p = Path.of(a);
            if (Files.isSymbolicLink(p)) {
                System.err.println("jsc --fmt: " + p + ": a link (not followed): give the path it leads to");
                return FMT_USAGE;
            }
            if (Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> s = Files.walk(p)) {
                    for (Path f : s.filter(f -> f.toString().endsWith(".jsc") && Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                        files.add(new Path[]{f, p});
                    }
                }
            } else if (Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                files.add(new Path[]{p, p.toAbsolutePath().getParent()});
            } else {
                System.err.println("jsc --fmt: " + p + ": not a regular file or a directory");
                return FMT_USAGE;
            }
        }
        var projects = new java.util.HashMap<Path, java.util.Map<String, String>>();
        int refused = 0, changed = 0;
        for (Path[] fr : files) {
            Path f = fr[0];
            String text;
            try {
                text = strictUtf8(Files.readAllBytes(f));
            } catch (java.nio.charset.CharacterCodingException e) {
                System.err.println(f + ": error: not valid UTF-8 (not formatted)");
                refused++;
                continue;
            }
            java.util.Map<String, String> project = projects.computeIfAbsent(fr[1], JscMain::jscFilesUnder);
            String id = fr[1].toAbsolutePath().normalize().relativize(f.toAbsolutePath().normalize()).toString().replace('\\', '/');
            var r = org.julclang.state.dsl.v2.JscFormat.format(text, id, org.julclang.state.dsl.v2.JscModules.of(project));
            if (!r.ok()) {
                System.err.println(f + ":" + r.line() + ":" + r.col() + ": error[" + r.code() + "] " + r.error() + " (not formatted)");
                refused++;
                continue;
            }
            if (r.text().equals(text)) continue;
            if (!check && !Files.isWritable(f)) {
                System.err.println(f + ": error: read-only (not formatted; it is never made writable)");
                refused++;
                continue;
            }
            changed++;
            if (check) {
                System.out.println(f + ": not formatted");
                continue;
            }
            // Written whole or not at all: a temporary file beside it, with its permissions, then moved over it.
            Path tmp = Files.createTempFile(f.toAbsolutePath().getParent(), "." + f.getFileName(), ".fmt");
            try {
                Files.writeString(tmp, r.text());
                try {
                    Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(f));
                } catch (UnsupportedOperationException e) {
                    // not a POSIX file system: the platform's defaults
                }
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
            System.out.println(f + ": formatted");
        }
        System.out.println("jsc --fmt: " + files.size() + " file(s), " + changed + (check ? " not formatted" : " formatted") + ", " + refused + " refused");
        return refused > 0 ? FMT_REFUSED : check && changed > 0 ? FMT_NOT_FORMATTED : FMT_OK;
    }

    /** The bytes as UTF-8, refusing malformed input (#120 F1: never a replacement character). */
    static String strictUtf8(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    /** The .jsc files under {@code root} that read as UTF-8, by module id (links not followed), for imports (C3). */
    static java.util.Map<String, String> jscFilesUnder(Path root) {
        var out = new java.util.LinkedHashMap<String, String>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path f : s.filter(f -> f.toString().endsWith(".jsc") && Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                try {
                    out.put(root.relativize(f).toString().replace('\\', '/'), strictUtf8(Files.readAllBytes(f)));
                } catch (java.nio.charset.CharacterCodingException e) {
                    // not a module the build could read either
                }
            }
        } catch (IOException e) {
            // an unreadable directory: the files alone
        }
        return out;
    }

    public static void main(String[] args) throws IOException {
        int code = run(args);
        if (code != 0) System.exit(code);
    }

    /**
     * {@code JscMain <srcDir> <outDir> [--accept-abi]}: lowers every {@code .jsc} file; 0 when all lowered and every
     * {@code .jsc} 2 manifest is accepted, 1 on an error, 2 on a usage error.
     */
    public static int run(String[] args) throws IOException {
        return run(args, DEFAULT);
    }

    /** The front end of a build: {@link #lowerWithWarnings}, with the build's other files as modules (M2c). */
    private static final FrontEnd DEFAULT = JscMain::lowerWithWarnings;

    /** How {@link #run} lowers one file's text: {@link #lowerWithWarnings}, or a test's stand-in. */
    @FunctionalInterface
    public interface FrontEnd {
        Lowered lower(String source, String fileName);
    }

    /**
     * {@link #run(String[])} with {@code frontEnd} lowering each file. A test passes a front end that fails in a known
     * way (a real StackOverflowError, say) to check that the build reports it as JSC0005 at the file and never crashes,
     * whatever the stack size (M1 hardening: the depth test no longer depends on how much stack a nested input uses).
     */
    public static int run(String[] args, FrontEnd frontEnd) throws IOException {
        // ADR-0021 r9 §15 (M5d): JscMain --fmt [--check] <file or dir>...: the formatter, before any other mode.
        if (args.length >= 1 && args[0].equals("--fmt")) return fmt(java.util.Arrays.asList(args).subList(1, args.length));
        // ADR-0018 D9 (M4b): --java-libraries <dir>, the on-chain sources of the Java libraries java: imports may name (a
        // library jar's META-INF/plutus-sources, extracted), anywhere after the mode; certified once, when needed.
        var rest = new ArrayList<String>();
        Path javaDir = null;
        Path lock = null;
        boolean acceptLock = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--java-libraries") && i + 1 < args.length) javaDir = Path.of(args[++i]);
            else if (args[i].equals("--lock") && i + 1 < args.length) lock = Path.of(args[++i]);   // J12: joust.lock
            else if (args[i].equals("--accept-lock")) acceptLock = true;
            else rest.add(args[i]);
        }
        args = rest.toArray(new String[0]);
        javaLibraries = javaLibraries(javaDir);
        if (args.length == 3 && args[0].equals("--check-library-classes")) return checkLibraryClasses(Path.of(args[1]), args[2]);
        if (args.length == 3 && args[0].equals("--library-manifest")) return libraryManifest(Path.of(args[1]), Path.of(args[2]));
        if (args.length == 3 && args[0].equals("--check-abi")) return checkAgainstDescriptions(Path.of(args[1]), Path.of(args[2]));
        if (args.length == 4 && args[0].equals("--remap")) return remap(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
        // ADR-0021 D7a: JscMain --abi-diff <srcDir> <Contract> [<out.json>]: the committed manifest and the new one.
        if ((args.length == 3 || args.length == 4) && args[0].equals("--abi-diff")) {
            return abiDiff(Path.of(args[1]), args[2], args.length == 4 ? Path.of(args[3]) : null);
        }
        Bound bound = args.length == 3 && args[2].startsWith("--accept-abi=") ? Bound.parse(args[2].substring("--accept-abi=".length())) : null;
        if (args.length == 3 && args[2].startsWith("--accept-abi=") && bound == null) {
            System.err.println("usage: JscMain <srcDir> <outDir> --accept-abi=<Contract>@<committed-sha256 or none>..<new-sha256>");
            return 2;
        }
        boolean accept = args.length == 3 && args[2].equals("--accept-abi");
        if (args.length != 2 && !accept && bound == null) {
            System.err.println("usage: JscMain <srcDir> <outDir> [--accept-abi[=<Contract>@<committed>..<new>]]");
            return 2;
        }
        Path src = Path.of(args[0]);
        // Codex C15 (r3): one normalised, absolute root for resolution, containment and collisions (./out is out).
        Path out = Path.of(args[1]).toAbsolutePath().normalize();
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(src)) {
            try (Stream<Path> s = Files.walk(src)) {
                s.filter(p -> p.toString().endsWith(".jsc")).sorted().forEach(files::add);
            }
        }
        int errors = 0;
        Path boundFile = null;
        if (bound != null) {
            Object[] target = resolveContract(src, files, bound.contract());
            if (target[0] == null) {
                // Nothing accepted, nothing written (S5 on #99: never another contract of the same simple name).
                System.err.println("jsc: error[JSC0002]: --accept-abi=" + bound.contract() + "@…: " + target[1] + "; nothing accepted");
                return 1;
            }
            boundFile = (Path) target[0];
        }
        var written = new java.util.HashSet<Path>();
        // ADR-0018 D9: the .jsc libraries the files import (M4) and the contracts they import (M2c) are files of the same
        // tree, by relative path.
        var modules = modules(src, files);
        var used = new java.util.TreeMap<String, org.julclang.state.dsl.v2.JscV2.LibraryUse>();
        // Start clean: a deleted or renamed .jsc must not leave a stale contract behind.
        if (Files.isDirectory(out)) {
            try (Stream<Path> s = Files.walk(out)) {
                s.sorted(java.util.Comparator.reverseOrder()).filter(p -> !p.equals(out)).forEach(p -> p.toFile().delete());
            }
        }
        // ADR-0018 D9 (review r2: S2, S3, F5): the certified sources of the java: libraries the contracts use are written
        // beside the generated Java, and only they are compiled into the project (never a library jar's other sources).
        var librarySourcesWritten = new java.util.HashMap<Path, String>();
        for (Path f : files) {
            String text = Files.readString(f);
            String name = src.relativize(f).toString();
            try {
                var javaUsed = new java.util.TreeSet<String>();
                var all = javaLibraries;
                org.julclang.state.dsl.v2.JavaLibraries tracked = fqcn -> {
                    var l = all.describe(fqcn);
                    if (l != null) javaUsed.add(fqcn);
                    return l;
                };
                var lowered = frontEnd == DEFAULT ? lowerWithWarnings(text, f.getFileName().toString(), id(src, f), modules, tracked)
                        : frontEnd.lower(text, f.getFileName().toString());
                lowered.diagnostics().forEach(w -> System.err.println(w.format(name)));
                for (var u : lowered.libraries()) used.put(u.name(), u);
                String java = lowered.java();
                String rel = outputPath(text, f.getFileName().toString());
                Path target = out.resolve(rel);
                if (Files.exists(target) && (written.contains(target) || librarySourcesWritten.containsKey(target))) {
                    throw new JscException(1, 1, "another .jsc file already generates " + rel);
                }
                written.add(target);
                if (lowered.abiJson() != null) {
                    // ADR-0018 D12: the ABI manifest is committed beside the .jsc file. Checked first: Java whose
                    // manifest is refused is not written, so nothing can compile it (security S9).
                    Path committedAbi = f.resolveSibling(target.getFileName().toString().replace(".java", ".abi.json"));
                    String refused = f.equals(boundFile) ? checkBound(committedAbi, lowered.abiJson(), name, bound)
                            : checkAbi(committedAbi, lowered.abiJson(), name, accept);
                    if (refused != null) {
                        System.err.println(refused);
                        errors++;
                        continue;
                    }
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, java);
                if (!javaUsed.isEmpty()) {
                    var classes = new java.util.TreeSet<String>();
                    for (String fqcn : javaUsed) {
                        var lib = all.describe(fqcn);
                        classes.addAll(lib.classes());
                        for (String path : lib.sources()) {
                            Path t = out.resolve(path).normalize();
                            String body = javaSourceTexts.get(path);
                            if (!t.startsWith(out) || body == null) throw new JscException(1, 1, "the java: library source " + path + " is not under the library path");
                            if (written.contains(t)) throw new JscException(1, 1, "the java: library source " + path + " is also a contract's generated Java");
                            if (librarySourcesWritten.putIfAbsent(t, body) == null) {
                                Files.createDirectories(t.getParent());
                                Files.writeString(t, body);
                            }
                        }
                    }
                    Files.writeString(target.resolveSibling(target.getFileName().toString().replace(".java", LIBRARY_CLASSES)),
                            String.join("\n", classes) + "\n");
                }
                if (lowered.sourceMapJson() != null) {
                    // The source map beside the generated Java (javac compiles only *.java).
                    Files.writeString(target.resolveSibling(target.getFileName().toString().replace(".java", ".jscmap.json")),
                            lowered.sourceMapJson());
                }
                if (lowered.irJson() != null) {
                    // ADR-0018 D15 (M1b): the checked IR, which the JavaScript reference interpreter evaluates.
                    Files.writeString(target.resolveSibling(target.getFileName().toString().replace(".java", ".ir.json")), lowered.irJson());
                }
            } catch (JscException e) {
                System.err.println(e.format(name));
                errors++;
            } catch (RuntimeException | Error e) {
                // A defect of the front end, not of the file: still reported against the file, with a code, and the build
                // fails. A StackOverflowError (or any Error) is caught too: the nesting cap (ADR-0018 J18) should make it
                // unreachable, and this keeps a crash from escaping as one (review S2).
                System.err.println(name + ":1:1: error[JSC0005]: " + internalError(e));
                errors++;
            }
        }
        // ADR-0018 J12: every library the build used, with its hash; the external ones pinned by joust.lock.
        for (var u : used.values()) {
            System.out.println("jsc: library " + u.name() + " sha256 " + u.sha256() + (u.external() ? (u.kind().equals("java")
                    ? " (statically certified; pinned)" : " (pinned)") : " (the project's)"));
        }
        // Review r2 (#82 S6): only acceptJoustLock (--accept-lock) writes the lock; --accept-abi does not.
        if (lock != null) errors += checkLock(lock, used.values(), acceptLock);
        if (errors > 0) return 1;
        System.out.println("jsc: lowered " + files.size() + " file(s) into " + out);
        return 0;
    }

    /**
     * ADR-0018 J12: {@code joust.lock} pins every library from outside the project's sources (a Java library; an npm
     * package's {@code .jsc} library) by the SHA-256 of its on-chain sources. A library whose hash differs from its pin, or
     * that has none, fails the build (JSC0731, JSC0732); {@code accept} ({@code acceptJoustLock}, {@code joust build
     * --accept-lock}) writes the lock instead, to be reviewed and committed. The project's own {@code .jsc} libraries are
     * its code, pinned by its version control. 0 when every pin holds.
     */
    public static int checkLock(Path lock, java.util.Collection<org.julclang.state.dsl.v2.JscV2.LibraryUse> used, boolean accept) throws IOException {
        var now = new java.util.TreeMap<String, String>();
        for (var u : used) if (u.external()) now.put(u.name(), u.sha256());
        var pinned = new java.util.TreeMap<String, String>();
        boolean exists = Files.exists(lock);
        if (exists) {
            Object j;
            try {
                j = org.julclang.state.dsl.v2.AbiCheck.Json.parse(Files.readString(lock));
            } catch (RuntimeException e) {
                j = null;
            }
            if (j instanceof java.util.Map<?, ?> m && "1".equals(String.valueOf(m.get("lock"))) && m.get("libraries") instanceof java.util.Map<?, ?> libs) {
                libs.forEach((k, v) -> pinned.put(String.valueOf(k), String.valueOf(v)));
            } else if (!accept) {
                System.err.println(lock + ":1:1: error[JSC0732]: joust.lock is not a version 1 lock ({\"lock\": 1, \"libraries\": {...}}): fix it,"
                        + " or rewrite it with the acceptJoustLock task (npm: joust build --accept-lock)");
                return 1;
            }
        }
        if (now.equals(pinned) && (exists || now.isEmpty())) return 0;
        if (accept) {
            var json = new StringBuilder("{\n  \"lock\": 1,\n  \"libraries\": {");
            int i = 0;
            for (var e : now.entrySet()) json.append(i++ == 0 ? "\n" : ",\n").append("    \"").append(e.getKey()).append("\": \"").append(e.getValue()).append('"');
            json.append(now.isEmpty() ? "}\n}\n" : "\n  }\n}\n");
            Files.writeString(lock, json.toString());
            System.err.println(lock + ": note: wrote joust.lock (" + now.size() + " pinned librar" + (now.size() == 1 ? "y" : "ies") + "): review and commit it");
            return 0;
        }
        int errors = 0;
        for (var e : now.entrySet()) {
            String pin = pinned.get(e.getKey());
            if (pin == null) {
                System.err.println(lock + ":1:1: error[JSC0731]: the library " + e.getKey() + " (sha256 " + e.getValue() + ") is not pinned in"
                        + " joust.lock: a library from outside the sources is pinned by the hash of its on-chain sources (ADR-0018 J12); pin it"
                        + " with the acceptJoustLock task (npm: joust build --accept-lock), review the library, and commit joust.lock");
                errors++;
            } else if (!pin.equals(e.getValue())) {
                System.err.println(lock + ":1:1: error[JSC0732]: the library " + e.getKey() + " has other on-chain sources than joust.lock pins (sha256 "
                        + e.getValue() + ", pinned " + pin + "): its code changed; review the change, then accept it with the acceptJoustLock task"
                        + " (npm: joust build --accept-lock)");
                errors++;
            }
        }
        for (String k : pinned.keySet()) {
            if (!now.containsKey(k)) System.err.println(lock + ": note: joust.lock pins " + k + ", which the build no longer uses (accepting removes it)");
        }
        return errors;
    }
}
