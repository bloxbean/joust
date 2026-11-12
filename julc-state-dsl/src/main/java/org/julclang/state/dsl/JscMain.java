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
     * generated Java: a {@code .jsc} 2 file's name may contain only ASCII letters, digits, '.', '_' and '-' (security S3);
     * a v1 file's name is escaped instead, so existing v1 files keep rebuilding (ADR-0018 D16, review F16).
     */
    public static Lowered lowerWithWarnings(String source, String fileName) {
        if (org.julclang.state.dsl.v2.JscV2.isV2(source)) {
            var r = org.julclang.state.dsl.v2.JscV2.lower(source, fileName);
            return new Lowered(r.java(), r.diagnostics().stream().map(w -> new Warning(w.line(), w.col(), w.code(), w.message())).toList(),
                    fileName, r.sourceMapJson(), r.abiJson());
        }
        // .jsc v1 (ADR-0005): frozen, not removed (ADR-0018 D16): it compiles exactly as before, so a deployed v1 script stays
        // rebuildable, and every build says it is deprecated and how to migrate.
        var r = Lowering.lower(Parser.parse(source), fileName);
        var warnings = new ArrayList<Warning>();
        var first = Lexer.lex(source).get(0);
        warnings.add(new Warning(first.line(), first.col(), "JSC9006", DEPRECATED));
        warnings.addAll(r.warnings());
        return new Lowered(r.java(), List.copyOf(warnings), fileName, null, null);
    }

    /** The v1 deprecation warning (ADR-0018 D16), at the first token of every v1 file. */
    public static final String DEPRECATED = "this file is .jsc v1, which is deprecated (ADR-0018 D16): it still builds, unchanged,"
            + " but new code is .jsc 2; run `joust migrate` (Gradle: the migrateJsc task) to translate it, as a new script";

    /** A located warning: line and column in the .jsc file, and its stable code ({@code .jsc} 2; null for v1) (D12). */
    public record Warning(int line, int col, String code, String message) {
        public Warning(int line, int col, String message) {
            this(line, col, null, message);
        }

        /** {@code file:line:col: warning[CODE]: message} ({@code warning:} without a code). */
        public String format(String file) {
            return file + ":" + line + ":" + col + ": warning" + (code == null ? "" : "[" + code + "]") + ": " + message;
        }
    }

    /**
     * Lowered Java and located warnings; for {@code .jsc} 2 also the source map and the ABI manifest (ADR-0018 D12),
     * null for v1.
     */
    public record Lowered(String java, java.util.List<Warning> diagnostics, String fileName, String sourceMapJson, String abiJson) {
        /** The warnings, each formatted {@code file:line:col: warning: message}. */
        public java.util.List<String> warnings() {
            return diagnostics.stream().map(w -> w.format(fileName)).toList();
        }
    }

    /** The package and class name the .jsc text declares, as a relative path {@code a/b/Name.java}. */
    static String outputPath(String source) {
        if (org.julclang.state.dsl.v2.JscV2.isV2(source)) return org.julclang.state.dsl.v2.JscV2.outputPath(source);
        var c = Parser.parse(source);
        return c.pkg().replace('.', '/') + "/" + c.name() + ".java";
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
        for (Path f : files) {
            String text = Files.readString(f);
            if (!org.julclang.state.dsl.v2.JscV2.isV2(text)) continue;
            String name = src.relativize(f).toString();
            var lowered = org.julclang.state.dsl.v2.JscV2.lower(text, f.getFileName().toString());
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
     * {@code JscMain --migrate <srcDir> <outDir> [--in-place]} (ADR-0018 D16; Gradle's {@code migrateJsc}): every
     * {@code .jsc} v1 file under {@code srcDir} translated to {@code .jsc} 2 ({@link Migrate}). By default each is written
     * to {@code outDir} under its relative path, and nothing in {@code srcDir} changes. With {@code --in-place} the v1 file
     * is replaced and its text kept beside it as {@code Name.jsc.v1}. 0 when every v1 file migrated, 1 on a refusal.
     */
    public static int migrate(Path src, Path out, boolean inPlace) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(src, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(src)) {
            files.add(src);
            src = src.getParent() == null ? Path.of(".") : src.getParent();
        } else if (Files.isDirectory(src)) {
            // Files.walk does not follow directory links (review r2 S2).
            try (Stream<Path> s = Files.walk(src)) {
                s.filter(p -> p.toString().endsWith(".jsc")).sorted().forEach(files::add);
            }
        }
        // Review r2 C2: without --in-place, nothing under the sources is written: the output may not be the source
        // directory or lie inside it (by path or by link), and no target may be a source file.
        if (!inPlace && within(out, src)) {
            System.err.println(src + ":1:1: error[JSC1108]: the output directory " + out + " is the source directory or inside it: without"
                    + " --in-place, migrate writes outside the sources and never changes them; choose another output directory, or"
                    + " pass --in-place to replace the v1 files (each kept as Name.jsc.v1)");
            return 1;
        }
        int migrated = 0, refused = 0;
        for (Path f : files) {
            String name = src.relativize(f).toString();
            try {
                if (inPlace && Files.isSymbolicLink(f)) {
                    // Review r2 S2: a link's target may be shared with other projects (still on v1) or lie outside the sources.
                    throw new JscException(1, 1, "JSC1109", "this file is a symbolic link: migrate --in-place never replaces a file"
                            + " through a link; migrate a copy, or the link's target in its own project");
                }
                String text = Files.readString(f);
                if (org.julclang.state.dsl.v2.JscV2.isV2(text)) continue;
                var r = Migrate.migrate(text, f.getFileName().toString());
                Path backup = f.resolveSibling(f.getFileName() + ".v1");
                Path target = inPlace ? f : out.resolve(name).normalize();
                if (!inPlace && (!target.toAbsolutePath().normalize().startsWith(out.toAbsolutePath().normalize())
                        || Files.exists(target) && Files.isSameFile(target, f))) {
                    throw new JscException(1, 1, "JSC1108", "the output " + target + " would replace the source: without --in-place,"
                            + " migrate never changes the sources");
                }
                if (inPlace) {
                    // Never through a link and never over an existing file, a dangling link included (review r2 S2).
                    try {
                        Files.writeString(backup, text, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    } catch (java.nio.file.FileAlreadyExistsException e) {
                        throw new JscException(1, 1, "JSC1107", backup.getFileName() + " exists already: migrate --in-place keeps the v1"
                                + " text there and never overwrites it; move it away first");
                    }
                    Files.writeString(f, r.source(), java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, r.source());
                }
                r.notes().forEach(n -> System.err.println(n.format(name)));
                System.out.println("jsc: migrated " + name + " to .jsc 2: " + target + (inPlace ? " (the v1 text is in " + backup.getFileName() + ")" : ""));
                migrated++;
            } catch (JscException e) {
                System.err.println(e.format(name));
                refused++;
            }
        }
        if (migrated > 0) {
            // Review r2 S1: the Gradle path records no deployments, so the warning is said here, once per run.
            System.err.println("jsc: warning: " + Migrate.NEW_SCRIPT);
            if (inPlace) {
                System.err.println("jsc: warning: the next build replaces the v1 contracts' generated Java, validators and clients: copy"
                        + " build/classes/java/main/META-INF (scripts, descriptions) and the generated clients first if v1 instances are"
                        + " deployed; the v1 text is in Name.jsc.v1, and rebuilding it with this JOUST version gives the same v1 scripts");
            }
        }
        if (migrated == 0 && refused == 0) System.out.println("jsc: no .jsc v1 file under " + src);
        return refused > 0 ? 1 : 0;
    }

    /** Whether {@code dir} is {@code root} or inside it, by path or, where both exist, by their real paths. */
    static boolean within(Path dir, Path root) throws IOException {
        Path d = dir.toAbsolutePath().normalize(), r = root.toAbsolutePath().normalize();
        if (d.startsWith(r)) return true;
        // The nearest existing ancestor of dir, by its real path (a link into the sources counts).
        Path e = d;
        while (e != null && !Files.exists(e)) e = e.getParent();
        return e != null && Files.exists(r) && e.toRealPath().resolve(e.relativize(d)).startsWith(r.toRealPath());
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
            if (!Files.exists(map)) continue;   // v1: no source map, but every generated line names its .jsc line
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
        String pkg = javaRel.contains("/") ? javaRel.substring(0, javaRel.lastIndexOf('/')).replace('/', '.') : "";
        String contract = javaRel.substring(javaRel.lastIndexOf('/') + 1).replace(".java", "");
        var found = new ArrayList<Path>();
        if (Files.isDirectory(src)) {
            try (Stream<Path> s = Files.walk(src)) {
                for (Path p : s.filter(x -> x.getFileName().toString().equals(bare) && Files.isRegularFile(x)).sorted().toList()) {
                    String text = Files.readString(p);
                    var pm = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z0-9_.]+)").matcher(text);
                    var cm = Pattern.compile("(?m)^\\s*contract\\s+([A-Za-z0-9_]+)").matcher(text);
                    if (pm.find() && pm.group(1).equals(pkg) && cm.find() && cm.group(1).equals(contract)) found.add(p);
                }
            }
        }
        return found.size() == 1 ? found.get(0).toString() : null;
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
        if (args.length == 3 && args[0].equals("--check-abi")) return checkAgainstDescriptions(Path.of(args[1]), Path.of(args[2]));
        if (args.length >= 3 && args[0].equals("--migrate")) {
            boolean inPlace = args.length == 4 && args[3].equals("--in-place");
            if (args.length > 4 || args.length == 4 && !inPlace) {
                System.err.println("usage: JscMain --migrate <srcDir> <outDir> [--in-place]");
                return 2;
            }
            return migrate(Path.of(args[1]), Path.of(args[2]), inPlace);
        }
        if (args.length == 4 && args[0].equals("--remap")) return remap(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
        boolean accept = args.length == 3 && args[2].equals("--accept-abi");
        if (args.length != 2 && !accept) {
            System.err.println("usage: JscMain <srcDir> <outDir> [--accept-abi]");
            return 2;
        }
        Path src = Path.of(args[0]);
        Path out = Path.of(args[1]);
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(src)) {
            try (Stream<Path> s = Files.walk(src)) {
                s.filter(p -> p.toString().endsWith(".jsc")).sorted().forEach(files::add);
            }
        }
        int errors = 0;
        var written = new java.util.HashSet<Path>();
        // Start clean: a deleted or renamed .jsc must not leave a stale contract behind.
        if (Files.isDirectory(out)) {
            try (Stream<Path> s = Files.walk(out)) {
                s.sorted(java.util.Comparator.reverseOrder()).filter(p -> !p.equals(out)).forEach(p -> p.toFile().delete());
            }
        }
        for (Path f : files) {
            String text = Files.readString(f);
            String name = src.relativize(f).toString();
            try {
                var lowered = lowerWithWarnings(text, f.getFileName().toString());
                lowered.diagnostics().forEach(w -> System.err.println(w.format(name)));
                String java = lowered.java();
                Path target = out.resolve(outputPath(text));
                if (Files.exists(target) && written.contains(target)) {
                    throw new JscException(1, 1, "another .jsc file already generates " + outputPath(text));
                }
                written.add(target);
                if (lowered.abiJson() != null) {
                    // ADR-0018 D12: the ABI manifest is committed beside the .jsc file. Checked first: Java whose
                    // manifest is refused is not written, so nothing can compile it (security S9).
                    String refused = checkAbi(f.resolveSibling(target.getFileName().toString().replace(".java", ".abi.json")), lowered.abiJson(),
                            name, accept);
                    if (refused != null) {
                        System.err.println(refused);
                        errors++;
                        continue;
                    }
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, java);
                if (lowered.sourceMapJson() != null) {
                    // The source map beside the generated Java (javac compiles only *.java).
                    Files.writeString(target.resolveSibling(target.getFileName().toString().replace(".java", ".jscmap.json")),
                            lowered.sourceMapJson());
                }
            } catch (JscException e) {
                System.err.println(e.format(name));
                errors++;
            } catch (RuntimeException e) {
                // A defect of the front end, not of the file: still reported against the file, and the build fails.
                System.err.println(name + ":1:1: error: internal error lowering this file: " + e);
                errors++;
            }
        }
        if (errors > 0) return 1;
        System.out.println("jsc: lowered " + files.size() + " file(s) into " + out);
        return 0;
    }
}
