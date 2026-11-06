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
        return Lowering.lower(Parser.parse(source), fileName).java();
    }

    /** The Java source and warnings for one .jsc file's text. */
    public static Lowered lowerWithWarnings(String source, String fileName) {
        var r = Lowering.lower(Parser.parse(source), fileName);
        return new Lowered(r.java(), r.warnings());
    }

    /** Lowered Java and warnings. */
    public record Lowered(String java, java.util.List<String> warnings) {}

    /** The package and class name the .jsc text declares, as a relative path {@code a/b/Name.java}. */
    static String outputPath(String source) {
        var c = Parser.parse(source);
        return c.pkg().replace('.', '/') + "/" + c.name() + ".java";
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
                lowered.warnings().forEach(System.err::println);
                String java = lowered.java();
                Path target = out.resolve(outputPath(text));
                if (Files.exists(target) && written.contains(target)) {
                    throw new JscException(1, 1, "another .jsc file already generates " + outputPath(text));
                }
                written.add(target);
                Files.createDirectories(target.getParent());
                Files.writeString(target, java);
            } catch (JscException e) {
                System.err.println(e.format(name));
                errors++;
            }
        }
        if (errors > 0) System.exit(1);
        System.out.println("jsc: lowered " + files.size() + " file(s) into " + out);
    }
}
