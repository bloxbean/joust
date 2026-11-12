package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.julclang.state.dsl.JscMain;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D12 and J5: every {@code .jsc} 2 error and warning carries a stable code, and the catalogue
 * ({@code docs/reference/jsc-2-diagnostics.md}) lists exactly the codes the front end raises, each with one severity.
 */
class JscV2DiagnosticsTest {

    static final Path V2 = Path.of("src/main/java/org/julclang/state/dsl/v2");
    static final Pattern CODE = Pattern.compile("\"(JSC\\d{4})\"");

    static String source(String f) throws Exception {
        return Files.readString(V2.resolve(f));
    }

    @Test
    void everyDiagnosticHasACode_andTheCatalogueListsExactlyThem() throws Exception {
        var used = new TreeMap<String, String>();   // code -> severity
        for (String f : List.of("Lexer.java", "Parser.java", "Checker.java", "JscV2.java")) {
            String s = source(f);
            // Every exception the front end builds goes through Pos.error(code, …) or the coded constructor.
            var bare = Pattern.compile("new JscException\\(([^\"]*?), \"(?!JSC)").matcher(s);
            while (bare.find()) {
                if (!bare.group(1).contains("e.line")) fail(f + ": an exception without a code: " + bare.group());
            }
            var m = Pattern.compile("(\\.error\\(|new JscException\\([^,]+, [^,]+, |\\bwarn\\([^,]+, )\"(JSC\\d{4})\"").matcher(s);
            while (m.find()) {
                String sev = m.group(1).startsWith("warn") ? "warning" : "error";
                String prior = used.put(m.group(2), sev);
                assertTrue(prior == null || prior.equals(sev), m.group(2) + " is both an error and a warning");
            }
            int calls = count(s, "\\.error\\(") + count(s, "\\bwarn\\([^)]");
            int coded = count(s, "\\.error\\(\"JSC") + count(s, "\\bwarn\\([^,]+, \"JSC");
            assertEquals(calls - count(s, "void warn\\("), coded, f + ": every error(…) and warn(…) names its code");
        }
        // The build's own (JscMain): the ABI manifest check.
        var main = Pattern.compile("error\\[(JSC\\d{4})\\]").matcher(Files.readString(Path.of("src/main/java/org/julclang/state/dsl/JscMain.java")));
        while (main.find()) used.put(main.group(1), "error");
        // The toolchain's: the manifest checked against the processor's description.
        var tool = Pattern.compile("\"(JSC\\d{4})\"").matcher(Files.readString(Path.of("../julc-state-toolchain/src/main/java/org/julclang/state/toolchain/Toolchain.java")));
        while (tool.find()) used.put(tool.group(1), "error");
        assertTrue(used.size() > 200, "codes found: " + used.size());
        String doc = Files.readString(Path.of("../docs/reference/jsc-2-diagnostics.md"));
        var listed = new TreeMap<String, String>();
        var row = Pattern.compile("^\\| (JSC\\d{4}) \\| (error|warning) \\|", Pattern.MULTILINE).matcher(doc);
        while (row.find()) assertNull(listed.put(row.group(1), row.group(2)), row.group(1) + " is listed twice");
        assertEquals(used, listed, "the catalogue lists exactly the codes the front end raises, with their severities");
    }

    static int count(String s, String regex) {
        var m = Pattern.compile(regex).matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    @Test
    void codesReachTheMessages_onBothFormats() {
        var e = assertThrows(JscException.class, () -> JscV2.lower("jsc 2\npackage t\ncontract C {\n  state { n: Int }\n"
                + "  init() { let x = 1\n return { n: 0 } }\n}\n", "C.jsc"));
        assertNotNull(e.code);
        assertTrue(e.format("C.jsc").startsWith("C.jsc:5:12: error[" + e.code + "]: values are immutable"), e.format("C.jsc"));
        var w = JscMain.lowerWithWarnings("jsc 2\npackage t\ncontract C {\n  state { owner: Key, last: Int }\n"
                + "  init() { return { owner: caller, last: 0 } }\n  action a() { return { ...state, last: validFrom } }\n}\n", "C.jsc");
        assertEquals(1, w.diagnostics().size());
        assertTrue(w.warnings().get(0).matches("C\\.jsc:6:41: warning\\[JSC9\\d{3}\\]: a stores a value of the validity window in last.*"),
                w.warnings().get(0));
        // v1 keeps its format: no code.
        var v1 = assertThrows(JscException.class, () -> JscMain.lower("package t\ncontract C { state n: Int\n init() { n = } }\n", "C.jsc"));
        assertNull(v1.code);
        assertTrue(v1.format("C.jsc").contains(": error: "), v1.format("C.jsc"));
    }

    @Test
    void theSameRuleHasTheSameCodeEverywhere() {
        var codes = new TreeSet<String>();
        for (String src : List.of("action a() { require(validFrom < deadline, \"x\")\n return state }",
                "action a() { require(xs.all(d => validFrom < d), \"x\")\n return state }",
                "action a() { require(validFrom == deadline, \"x\")\n return state }")) {
            var e = assertThrows(JscException.class, () -> JscV2.lower(JscV2CheckerTest.contract(src), "T.jsc"));
            codes.add(e.code);
        }
        assertEquals(1, codes.size(), codes.toString());
    }
}
