package org.julclang.state.dsl;

import org.julclang.state.dsl.v2.CorpusParse;
import org.julclang.state.dsl.v2.JscV2;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D13: the hand-written parsers and the tree-sitter grammar ({@code tree-sitter-jsc/}) agree on one shared
 * corpus. Each side parses (lexer and parser only, not the checker) the same files and must reach the same verdict:
 * <ul>
 *   <li>{@code tree-sitter-jsc/corpus/{v2,v1}/valid/*.jsc} parse; {@code …/invalid/*.jsc} are refused;</li>
 *   <li>every other {@code .jsc} file of the repository parses (examples, templates, migrated files: found, not listed;
 *       the Gradle template's {@code @@package@@} replaced by a package name);</li>
 *   <li>the research probes and ports under {@code docs/research} (some refused on purpose) have the verdict
 *       {@code tree-sitter-jsc/corpus/research-verdicts.txt} states.</li>
 * </ul>
 * The tree-sitter side is {@code tree-sitter-jsc/test/shared-corpus.mjs} ({@code npm test} there), over the same files.
 */
class JscCorpusTest {

    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final Path CORPUS = ROOT.resolve("tree-sitter-jsc/corpus");
    /** Directories never searched for repository .jsc files (build output, dependencies, the corpus itself). */
    static final Set<String> SKIP = Set.of("build", "node_modules", ".git", ".gradle", "tree-sitter-jsc", "dist", ".joust");

    /** Lexer and parser only, dispatched as the build does: a file whose first word is {@code jsc} is {@code .jsc} 2. */
    static void parse(String text) {
        if (JscV2.isV2(text)) CorpusParse.parse(text);
        else Parser.parse(text);
    }

    /** null when the file parses, else the parser's error. */
    static String verdict(Path f) throws IOException {
        String text = Files.readString(f).replace("@@package@@", "app");
        try {
            parse(text);
            return null;
        } catch (JscException e) {
            return e.format(f.getFileName().toString());
        }
    }

    static List<Path> jsc(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".jsc")).sorted().toList();
        }
    }

    @Test
    void corpus_validFilesParse_andInvalidOnesAreRefused() throws IOException {
        int valid = 0, invalid = 0;
        for (String version : List.of("v2", "v1")) {
            for (Path f : jsc(CORPUS.resolve(version).resolve("valid"))) {
                assertNull(verdict(f), version + "/valid/" + f.getFileName());
                // A valid file's version line is its directory's (an invalid one may lack it on purpose).
                assertEquals(version.equals("v2"), JscV2.isV2(Files.readString(f)), f.toString());
                valid++;
            }
            for (Path f : jsc(CORPUS.resolve(version).resolve("invalid"))) {
                assertNotNull(verdict(f), version + "/invalid/" + f.getFileName() + " parses, but the corpus says it is refused");
                invalid++;
            }
        }
        System.out.println("[corpus] " + valid + " valid and " + invalid + " invalid corpus files agree with the hand-written parsers");
        assertTrue(valid >= 6 && invalid >= 50, valid + " valid, " + invalid + " invalid");
    }

    /** Every .jsc file of the repository outside the corpus and docs/research. */
    static List<Path> repositoryFiles() throws IOException {
        var out = new ArrayList<Path>();
        try (Stream<Path> s = Files.walk(ROOT)) {
            s.filter(p -> p.toString().endsWith(".jsc") && Files.isRegularFile(p))
                    .filter(p -> {
                        Path rel = ROOT.relativize(p);
                        for (Path seg : rel) if (SKIP.contains(seg.toString())) return false;
                        return !rel.startsWith("docs/research");
                    })
                    .sorted().forEach(out::add);
        }
        return out;
    }

    @Test
    void repositoryFilesParse() throws IOException {
        List<Path> files = repositoryFiles();
        for (Path f : files) assertNull(verdict(f), ROOT.relativize(f).toString());
        System.out.println("[corpus] " + files.size() + " repository .jsc files parse");
        assertTrue(files.stream().anyMatch(f -> f.endsWith("js/create-joust/variants/jsc/contracts/app/Counter.jsc")), files.toString());
        assertTrue(files.size() >= 12, files.toString());
    }

    @Test
    void researchFiles_haveTheirStatedVerdicts() throws IOException {
        var stated = new TreeMap<String, String>();
        for (String line : Files.readAllLines(CORPUS.resolve("research-verdicts.txt"))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] kv = line.trim().split("\\s+");
            assertNull(stated.put(kv[0], kv[1]), kv[0] + " is listed twice");
        }
        var found = new TreeMap<String, String>();
        try (Stream<Path> s = Files.walk(ROOT.resolve("docs/research"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".jsc")).sorted().toList()) {
                found.put(ROOT.relativize(f).toString(), verdict(f) == null ? "valid" : "invalid");
            }
        }
        assertEquals(stated, found, "research-verdicts.txt states each research file's verdict (both sides check it)");
        System.out.println("[corpus] " + found.size() + " research files have their stated verdicts ("
                + found.values().stream().filter("valid"::equals).count() + " valid)");
    }

    /** Prints research-verdicts.txt's body (a helper for when a research file is added). */
    public static void main(String[] args) throws IOException {
        Map<String, String> found = new TreeMap<>();
        try (Stream<Path> s = Files.walk(ROOT.resolve("docs/research"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".jsc")).sorted().toList()) {
                found.put(ROOT.relativize(f).toString(), verdict(f) == null ? "valid" : "invalid");
            }
        }
        found.forEach((k, v) -> System.out.println(k + " " + v));
    }
}
