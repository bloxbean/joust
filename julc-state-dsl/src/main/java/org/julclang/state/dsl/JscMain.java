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
                    fileName, r.sourceMapJson(), r.abiJson(), r.irJson());
        }
        // .jsc v1 (ADR-0005): frozen, not removed (ADR-0018 D16): it compiles exactly as before, so a deployed v1 script stays
        // rebuildable, and every build says it is deprecated and how to migrate.
        var r = Lowering.lower(Parser.parse(source), fileName);
        var warnings = new ArrayList<Warning>();
        var first = Lexer.lex(source).get(0);
        warnings.add(new Warning(first.line(), first.col(), "JSC9006", DEPRECATED));
        warnings.addAll(r.warnings());
        return new Lowered(r.java(), List.copyOf(warnings), fileName, null, null, null);
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
     * Lowered Java and located warnings; for {@code .jsc} 2 also the source map and the ABI manifest (ADR-0018 D12) and
     * the checked IR as JSON (D15, M1b), null for v1.
     */
    public record Lowered(String java, java.util.List<Warning> diagnostics, String fileName, String sourceMapJson, String abiJson,
                          String irJson) {
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
        return migrate(src, out, inPlace, Path.of("").toAbsolutePath());
    }

    /** {@link #migrate(Path, Path, boolean)}, with links checked below {@code workingDir} (issue #75; a test's own). */
    static int migrate(Path src, Path out, boolean inPlace, Path workingDir) throws IOException {
        List<Path> files = new ArrayList<>();
        boolean fileArgument = false;
        if (Files.isRegularFile(src, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(src)) {
            files.add(src);
            fileArgument = true;
            src = src.getParent() == null ? Path.of(".") : src.getParent();
        } else if (Files.isDirectory(src)) {
            // Files.walk does not follow directory links (review r2 S2).
            try (Stream<Path> s = Files.walk(src)) {
                s.filter(p -> p.toString().endsWith(".jsc")).sorted().forEach(files::add);
            }
        }
        // Review r2 C2: without --in-place, nothing under the sources is written: the output may not be the source
        // directory or lie inside it (by path or by link), and no target may be a source.
        if (!inPlace && within(out, src)) {
            System.err.println(src + ":1:1: error[JSC1108]: the output directory " + out + " is the source directory or inside it: without"
                    + " --in-place, migrate writes outside the sources and never changes them; choose another output directory, or"
                    + " pass --in-place to replace the v1 files (each kept as Name.jsc.v1)");
            return 1;
        }
        int migrated = 0, refused = 0;
        // Review r3 C2: every target checked before anything is written. A target is refused when, through any link on
        // its path (resolved at its nearest existing ancestor), it lies in the sources, or is the same file as any source.
        if (!inPlace) {
            int bad = 0;
            for (Path f : files) {
                Path target = out.resolve(src.relativize(f).toString()).normalize();
                String why = targetProblem(target, out, src, files);
                if (why != null) {
                    System.err.println(src.relativize(f) + ":1:1: error[JSC1108]: the output " + target + " " + why + ": without --in-place,"
                            + " migrate never changes the sources; nothing was written");
                    bad++;
                }
            }
            if (bad > 0) return 1;
        }
        for (Path f : files) {
            String name = src.relativize(f).toString();
            try {
                if (inPlace) inPlaceProblem(f, src, fileArgument, workingDir);
                String text = Files.readString(f);
                if (org.julclang.state.dsl.v2.JscV2.isV2(text)) continue;
                var r = Migrate.migrate(text, f.getFileName().toString());
                Path backup = f.resolveSibling(f.getFileName() + ".v1");
                Path target = inPlace ? f : out.resolve(name).normalize();
                if (inPlace) {
                    // Never through a link and never over an existing file, a dangling link included (review r2 S2).
                    try {
                        Files.writeString(backup, text, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    } catch (java.nio.file.FileAlreadyExistsException e) {
                        throw new JscException(1, 1, "JSC1107", backup.getFileName() + " exists already: migrate --in-place keeps the v1"
                                + " text there and never overwrites it; move it away first");
                    }
                    // Review r3 S5: a new file renamed over the source replaces the directory entry, never the contents of a
                    // shared inode or a link's target.
                    renameOver(f, r.source());
                } else {
                    // Review r4 C5: a new file renamed over the target, never a write into an existing one, so no inode
                    // a target shares with a source is ever changed.
                    Files.createDirectories(target.getParent());
                    renameOver(target, r.source());
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

    /** Writes {@code text} to a new file beside {@code target} and renames it over {@code target} (review r3 S5, r4 C5). */
    static void renameOver(Path target, String text) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".migrating");
        try {
            Files.writeString(tmp, text);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Why a default-mode target may not be written, or null (review r2 C2, r3 C2, r4 C5). */
    static String targetProblem(Path target, Path out, Path src, List<Path> sources) throws IOException {
        if (!target.toAbsolutePath().normalize().startsWith(out.toAbsolutePath().normalize())) return "is outside the output directory";
        if (within(target, src)) return "lies in the sources (through a link on its path)";
        Path real = resolved(target);
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            // The same file as a source by identity (a hard link has its own path, but the source's inode).
            for (Path s : sources) {
                if (Files.exists(s) && Files.isSameFile(target, s)) return "is the source " + s + " (the same file)";
            }
            if (links(target) > 1) return "has more than one hard link (another path shares its contents)";
        }
        for (Path s : sources) {
            if (Files.exists(s) && real.equals(s.toRealPath())) return "is the source " + s;
        }
        return null;
    }

    /** The hard links of {@code f} (1 where the file system does not say). */
    static int links(Path f) throws IOException {
        try {
            return Files.getAttribute(f, "unix:nlink", java.nio.file.LinkOption.NOFOLLOW_LINKS) instanceof Integer n ? n : 1;
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return 1;
        }
    }

    /**
     * Review r3 S5: a file migrated in place is a plain file of the sources: no symbolic link (JSC1109), no hard link
     * shared with another path, and no link on its path under the source directory (its real path lies under the source
     * directory's, and no directory between them is a link), nor anywhere on its path below {@code workingDir} (issue
     * #75).
     */
    static void inPlaceProblem(Path f, Path src, boolean fileArgument, Path workingDir) throws IOException {
        if (Files.isSymbolicLink(f)) {
            throw new JscException(1, 1, "JSC1109", "this file is a symbolic link: migrate --in-place never replaces a file through a"
                    + " link; migrate a copy, or the link's target in its own project");
        }
        try {
            Object links = Files.getAttribute(f, "unix:nlink", java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (links instanceof Integer n && n > 1) {
                throw new JscException(1, 1, "JSC1109", "this file has " + n + " hard links: another path shares its contents (another"
                        + " project, still on v1?), so migrate --in-place does not replace it; migrate a copy");
            }
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            // No unix attributes (Windows): the real-path rule below still applies.
        }
        Path root = src.toAbsolutePath().normalize();
        Path abs = f.toAbsolutePath().normalize();
        // Review r4 S8: a file given by itself has its directory as its root: that directory may not be a link either.
        // (Links further up, such as macOS's /tmp, are the user's file system, not a shared source.)
        if (fileArgument && Files.isSymbolicLink(root)) {
            throw new JscException(1, 1, "JSC1109", "the directory " + root.getFileName() + " is a symbolic link: migrate --in-place never"
                    + " replaces a file through a link; migrate the link's target in its own project");
        }
        for (Path p = abs.getParent(); p != null && p.startsWith(root) && !p.equals(root); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) {
                throw new JscException(1, 1, "JSC1109", "the directory " + root.relativize(p) + " is a symbolic link: migrate --in-place"
                        + " never replaces a file through a link; migrate the link's target in its own project");
            }
        }
        // Issue #75 (security S8): above the source directory too, every directory below the working directory (src in
        // src/lib/Points.jsc, linked to ../sharedsrc, say). Links at or above the working directory (macOS's /tmp and
        // /var, a linked home) are the user's file system, not a shared source, and are not refused.
        Path cwd = workingDir.toAbsolutePath().normalize();
        for (Path p = abs.getParent(); p != null && p.startsWith(cwd) && !p.equals(cwd); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) {
                throw new JscException(1, 1, "JSC1109", "the directory " + cwd.relativize(p) + " is a symbolic link: migrate --in-place"
                        + " never replaces a file through a link; migrate the link's target in its own project");
            }
        }
        if (!f.toRealPath().startsWith(src.toRealPath())) {
            throw new JscException(1, 1, "JSC1109", "this file's real path " + f.toRealPath() + " is not under the source directory: migrate"
                    + " --in-place replaces only files of the sources");
        }
    }

    /** {@code p}'s real path, through its nearest existing ancestor (the rest appended as written). */
    static Path resolved(Path p) throws IOException {
        Path d = p.toAbsolutePath().normalize();
        Path e = d;
        while (e != null && !Files.exists(e)) e = e.getParent();
        return e == null ? d : e.toRealPath().resolve(e.relativize(d)).normalize();
    }

    /** Whether {@code dir} is {@code root} or inside it, by path or, where both exist, by their real paths. */
    static boolean within(Path dir, Path root) throws IOException {
        Path d = dir.toAbsolutePath().normalize(), r = root.toAbsolutePath().normalize();
        if (d.startsWith(r)) return true;
        // The nearest existing ancestor of dir, by its real path (a link into the sources counts).
        return Files.exists(r) && resolved(d).startsWith(r.toRealPath());
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
        var found = new ArrayList<Path>();
        if (Files.isDirectory(src)) {
            try (Stream<Path> s = Files.walk(src)) {
                for (Path p : s.filter(x -> x.getFileName().toString().equals(bare) && Files.isRegularFile(x)).sorted().toList()) {
                    // The unit a file declares, as the parser reads it: comments and strings are never declarations
                    // (review r3 C3).
                    try {
                        if (outputPath(Files.readString(p)).equals(javaRel)) found.add(p);
                    } catch (JscException | IOException e) {
                        // A file that does not parse generated nothing.
                    }
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
        return run(args, JscMain::lowerWithWarnings);
    }

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
                var lowered = frontEnd.lower(text, f.getFileName().toString());
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
        if (errors > 0) return 1;
        System.out.println("jsc: lowered " + files.size() + " file(s) into " + out);
        return 0;
    }
}
