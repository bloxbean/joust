package org.julclang.state.dsl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        // .jsc v1 (ADR-0005): compiled as before. Its deprecation warning arrives with M1, when the templates switch to
        // .jsc 2 and `joust migrate` exists (ADR-0018 Q5, D14, D16; review F9).
        var r = Lowering.lower(Parser.parse(source), fileName);
        return new Lowered(r.java(), r.warnings(), fileName, null, null);
    }

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
     * ADR-0018 D12: the ABI manifest is committed beside the {@code .jsc} file. A missing one is written (commit it); a
     * different one fails the build, unless the change is accepted ({@code -Djoust.acceptAbi=true}; Gradle
     * {@code -Pjoust.acceptAbi}), so a lowering change that would alter a datum or a redeemer cannot pass unnoticed.
     */
    static boolean checkAbi(Path committed, String abi, String name) throws IOException {
        boolean accept = Boolean.getBoolean("joust.acceptAbi");
        if (Files.exists(committed)) {
            String old = Files.readString(committed);
            if (old.equals(abi)) return true;
            if (!accept) {
                System.err.println(name + ":1:1: error[JSC0002]: the ABI manifest " + committed.getFileName() + " differs from the committed"
                        + " one: a datum or redeemer encoding changed, which strands deployed instances; if the change is intended,"
                        + " build with -Pjoust.acceptAbi (npm: joust build --accept-abi) and commit the new manifest");
                return false;
            }
            System.err.println(name + ": note: the ABI manifest " + committed.getFileName() + " changed and was accepted: commit it");
        } else {
            System.err.println(name + ": note: wrote the ABI manifest " + committed.getFileName() + ": commit it beside the .jsc file");
        }
        Files.writeString(committed, abi);
        return true;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: JscMain <srcDir> <outDir>");
            System.exit(2);
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
                Files.createDirectories(target.getParent());
                Files.writeString(target, java);
                if (lowered.sourceMapJson() != null) {
                    // ADR-0018 D12: the source map beside the generated Java (javac compiles only *.java), and the ABI
                    // manifest beside the .jsc file, where the project commits it.
                    Files.writeString(target.resolveSibling(target.getFileName().toString().replace(".java", ".jscmap.json")),
                            lowered.sourceMapJson());
                    if (!checkAbi(f.resolveSibling(target.getFileName().toString().replace(".java", ".abi.json")), lowered.abiJson(), name)) errors++;
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
        if (errors > 0) System.exit(1);
        System.out.println("jsc: lowered " + files.size() + " file(s) into " + out);
    }
}
