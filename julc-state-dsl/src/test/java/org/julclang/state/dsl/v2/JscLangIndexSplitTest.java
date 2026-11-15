package org.julclang.state.dsl.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-0021 r8 (M5c; the M5c row's r7 commitment): the per-edit diagnostics model carries no index, and the index entry
 * gives exactly the full model, from the last diagnostics analysis or from a fresh one.
 */
class JscLangIndexSplitTest {

    static final Path DSL = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");

    @Test
    void theIndexIsTheFullModelAndTheDiagnosticsModelCarriesNone() throws Exception {
        int files = 0;
        long full = 0, diag = 0;
        try (Stream<Path> s = Files.list(DSL)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".jsc")).sorted().toList()) {
                String src = Files.readString(p);
                String name = p.getFileName().toString();
                String whole = JscLang.analyzeJson(src, name);
                String d = JscLang.diagnosticsJson(src, name, null, null);
                // From the last analysis (the same inputs), and from a fresh one (another file analysed between).
                assertEquals(whole, JscLang.indexJson(src, name, null, null), name);
                JscLang.diagnosticsJson("jsc 1\npackage x\n", "X.jsc", null, null);
                assertEquals(whole, JscLang.indexJson(src, name, null, null), name + " (fresh)");
                assertFalse(d.contains("\"references\":"), name);
                assertFalse(d.contains("\"exprs\":"), name);
                // The same diagnostics and folding.
                assertEquals(section(whole, "\"diagnostics\":", "],\"declarations\""), section(d, "\"diagnostics\":", "],\"declarations\""), name);
                assertEquals(section(whole, "\"folding\":", "]]"), section(d, "\"folding\":", "]]"), name);
                full += whole.length();
                diag += d.length();
                files++;
            }
        }
        System.out.printf("[index] %d files: the diagnostics models are %d characters, the full ones %d (%.0f%%)%n", files, diag, full,
                100.0 * diag / full);
        assertTrue(files > 20);
        assertTrue(diag < full / 2, diag + " vs " + full);
    }

    private static String section(String json, String from, String to) {
        int a = json.indexOf(from);
        int b = json.indexOf(to, a);
        return a < 0 || b < 0 ? "" : json.substring(a, b);
    }
}
