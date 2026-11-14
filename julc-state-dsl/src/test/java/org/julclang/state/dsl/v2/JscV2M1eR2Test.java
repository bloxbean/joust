package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1e review round r1 (#71): each finding's repro, as a regression test. Codex C1–C3, C5; security S2, S3, S5; design
 * F3, F5, F6. The toolchain side (linked scripts, hashes, four legs) is in {@code JscV2M1eR2BuildTest}.
 */
class JscV2M1eR2Test {

    static JscV2.Lowered lower(String src) {
        try {
            return JscV2.lower(src, "D.jsc");
        } catch (JscException e) {
            throw new AssertionError("expected to lower, got " + e.line + ":" + e.col + ": [" + e.code + "] " + e.getMessage(), e);
        }
    }

    static JscException refused(String src, String code) {
        var e = assertThrows(JscException.class, () -> JscV2.lower(src, "D.jsc"));
        assertEquals(code, e.code, e.line + ":" + e.col + ": " + e.getMessage());
        return e;
    }

    static String contract(String members) {
        return "jsc 1\npackage t\ncontract D {\n  state { n: Int }\n  init() { return { n: 0 } }\n" + members + "\n}\n";
    }

    /** {@code fn f(x)}: branches returning 0 … n-1 for x == 0 … n-1, else {@code f(x - 1)} (Codex C1's Recursive9). */
    static String recursive(int n) {
        var s = new StringBuilder("if x == 0 { 0 }");
        for (int k = 1; k < n; k++) s.append(" else if x == ").append(k).append(" { ").append(k).append(" }");
        s.append(" else { f(x - 1) }");
        return contract("  view v(x: Int): Int = f(x)\n  fn f(x: Int): Int = " + s);
    }

    // ------------------------------------------------------------------ C1, F6, S5: recursion in else-if chains

    @Test
    void c1_directRecursionInAValueChainOfAnyLength_staysASelfCall() {
        for (int n : new int[]{8, 9, 12, 40}) {
            var l = lower(recursive(n));
            assertTrue(l.recursiveFns().contains("f"), n + ": f is recursive");
            assertFalse(l.java().contains("f__value"), n + ": the chain stays inline, so f calls itself directly");
            var in = JscV2.interpreter(recursive(n), "D.jsc", Interpreter.JDK_HASHES);
            assertEquals(BigInteger.valueOf(n - 1), in.callFn("f", List.of(BigInteger.valueOf(n + 5))), n + " branches");
        }
        // An inline chain nests a level per branch, so a recursive chain longer than the cap is JSC0150, located.
        var e = refused(recursive(80), "JSC0150");
        assertTrue(e.line > 0 && e.col > 0);
        // A recursive call in a condition of an inline chain is a self-call too.
        lower(contract("  fn f(x: Int): Int = " + "if x <= 0 { 0 }" + " else if f(x - 1) > 100 { 1 }".repeat(1)
                + " else if x == 2 { 2 } else if x == 3 { 3 } else if x == 4 { 4 } else if x == 5 { 5 } else if x == 6 { 6 }"
                + " else if x == 7 { 7 } else if x == 8 { 8 } else if x == 9 { 9 } else { x }"));
    }

    @Test
    void s5_aRecursiveCallInAConditionTheLoweringPutsInAHelper_isJsc0601AtTheCall() {
        // A value chain with statements: every condition is inside the chain's helpers.
        String withStmts = contract("  fn f(n: Int): Int = if n <= 0 { 0 } else if f(n - 1) > 1000 { const k = 1\n k }"
                + " else if n == 2 { 2 } else { n }");
        var e = refused(withStmts, "JSC0601");
        assertEquals(6, e.line);
        String line6 = withStmts.split("\n")[5];
        assertEquals(line6.indexOf("f(n - 1)") + 1, e.col, "at the call: " + e.getMessage());
        assertTrue(e.getMessage().contains("else-if chain"), e.getMessage());
        // A statement if: the second condition is in the helper of the first branch's else.
        String stmt = contract("  fn f(n: Int): Int {\n    if n <= 0 { require(true, \"a\") } else if f(n - 1) > 3 { require(true, \"b\") }\n    n\n  }");
        refused(stmt, "JSC0601");
        // The first condition of a statement if is where the if is: a direct self-call.
        lower(contract("  fn f(n: Int): Int {\n    if n > 0 && f(n - 1) > 3 { require(true, \"b\") }\n    n\n  }"));
    }

    // ------------------------------------------------------------------ C2, S3: structural type depth

    static String types(int named, int wrappers, String root) {
        var t = new StringBuilder();
        for (int k = 0; k < named; k++) {
            String inner = k == named - 1 ? "Key" : "T" + (k + 1);
            t.append("type T").append(k).append(" = { f: ").append("List<".repeat(wrappers)).append(inner).append(">".repeat(wrappers)).append(" }\n");
        }
        return "jsc 1\npackage t\n" + t + "contract D {\n  state { n: Int }\n  init() { return { n: 0 } }\n" + root + "\n}\n";
    }

    @Test
    void c2_s3_listOptionAndMapLayersCountTowardsTheTypeCap() {
        // Codex C2: 2 records each wrapping the next in 40 lists (82 levels); security S3: 4 x 60.
        var e = refused(types(2, 40, "  action a(t: T0) { return state }"), "JSC0340");
        assertEquals(3, e.line, "at T0's declaration, before any method is checked");
        refused(types(4, 60, "  action a(v: T0) { require(v == v, \"same\")\n return state }"), "JSC0340");
        lower(types(2, 20, "  action a(t: T0) { return state }"));
        // Roots: a parameter's and a state field's written type count their layers on top of the named type's.
        var p = refused(types(1, 30, "  action a(t: " + "List<".repeat(40) + "T0" + ">".repeat(40) + ") { return state }"), "JSC0340");
        assertEquals(7, p.line, "at the parameter's type");
        refused(types(1, 30, "  state2").replace("state { n: Int }", "state { n: Int, deep: " + "List<".repeat(40) + "T0" + ">".repeat(40)
                + " }").replace("  state2", ""), "JSC0340");
    }

    // ------------------------------------------------------------------ C3, S2, F5: the matching rule and JSC0430

    @Test
    void s2_c3_anAuthorsMessageMayNotHoldAKeysFormAnywhere() {
        for (String m : List.of("#pay:spend0.1", "prefix: #pay:spend0.1", " #pay:spend0.1", "x: #input:spend0.0", "see #pay:")) {
            refused(contract("  action a(x: Int) { require(x != 5, \"" + m + "\")\n return state }"), "JSC0430");
        }
        // A '#' that is not a key's form is the author's.
        for (String m : List.of("# of items: none", "#tag", "#1: first", "a#B:c")) {
            String java = lower(contract("  action a(x: Int) { require(x != 5, \"" + m + "\")\n return state }")).java();
            assertTrue(java.contains(JscV2.javaString(m)), m);
        }
    }

    @Test
    void c3_explainMatchesWholeMessages_andTheLongestWins() {
        var pays = new StringBuilder();
        for (int k = 1; k <= 11; k++) pays.append("    pay(caller, x - ").append(k).append(")\n");
        var l = lower("jsc 1\npackage t\ncontract D {\n  state { n: Int }\n  init() { return { n: 0 } }\n  destroy close(x: Int) open {\n"
                + pays + "    pay(caller, balance)\n  }\n}\n");
        assertEquals(List.of("D.jsc:7:5 (a payout in close is negative)"), l.explain("#pay:spend0.1"));
        assertEquals(List.of("D.jsc:16:5 (a payout in close is negative)"), l.explain("#pay:spend0.10"));
        assertEquals(List.of("D.jsc:16:5 (a payout in close is negative)"), l.explain("close: #pay:spend0.10"));
        assertEquals(List.of(), l.explain("x #pay:spend0.1"));
    }

    /** The vectors every matcher (this, the runtime's JscMessages, the JS runtime's) answers alike (review r1 F5). */
    static List<String[]> vectors() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/jsc2-message-matching.json"));
        var out = new ArrayList<String[]>();
        Matcher v = Pattern.compile("\\{ \"trace\": \"((?:[^\"\\\\]|\\\\.)*)\", \"expected\": (null|\"((?:[^\"\\\\]|\\\\.)*)\") \\}").matcher(json);
        while (v.find()) out.add(new String[]{v.group(1), v.group(3)});
        return out;
    }

    static List<String> vectorMessages() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/jsc2-message-matching.json"));
        Matcher a = Pattern.compile("\"messages\": \\[([^\\]]*)\\]").matcher(json);
        assertTrue(a.find());
        var out = new ArrayList<String>();
        Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(a.group(1));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    void f5_theSharedMatchingVectors() throws Exception {
        var msgs = vectorMessages();
        var vs = vectors();
        assertTrue(vs.size() >= 10);
        for (String[] v : vs) assertEquals(v[1], JscV2.matching(v[0], msgs), "trace '" + v[0] + "'");
    }

    // ------------------------------------------------------------------ C5, F3: adapters' messages at their call sites

    @Test
    void c5_f3_everyAdapterRowRecordsItsMessagesAtTheCallSite() {
        var l = lower("jsc 1\npackage t\nimport { pow, div } from \"std/math\"\ncontract D {\n  state { n: Int }\n  init() { return { n: 0 } }\n"
                + "  action a(e: Int) { return { ...state, n: pow(2, e) + div(7, e) } }\n}\n");
        String line7 = "  action a(e: Int) { return { ...state, n: pow(2, e) + div(7, e) } }";
        assertEquals(List.of("D.jsc:7:" + (line7.indexOf("pow(") + 1) + " (in a)"), l.explain("pow: the exponent must be in [0, 1024]"));
        assertEquals(List.of("D.jsc:7:" + (line7.indexOf("div(") + 1) + " (in a)"), l.explain("div: division by zero"));
    }

    /** The table of adapter messages equals the adapters' own Require messages (the binding rows' targets). */
    @Test
    void f3_theAdapterMessageTableMatchesTheAdapters() throws Exception {
        Path api = Path.of("../julc-state-api/src/main/java/org/julclang/state/api");
        for (Std.Binding b : Std.ROWS.values()) {
            if (b.policy() != Std.Policy.ADAPTER) continue;
            String cls = b.target().substring(0, b.target().indexOf('.')), method = b.target().substring(b.target().indexOf('.') + 1);
            String src = Files.readString(api.resolve(cls + ".java"));
            int at = src.indexOf("public static ", src.indexOf(" " + method + "(") - 40);
            int start = src.indexOf(" " + method + "(", at);
            int end = src.indexOf("public static ", start + 1);
            String body = src.substring(start, end < 0 ? src.length() : end);
            var found = new TreeSet<String>();
            Matcher m = Pattern.compile("Require\\.that\\([^;]*?\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\);", Pattern.DOTALL).matcher(body);
            while (m.find()) found.add(m.group(1));
            assertEquals(found, new TreeSet<>(Std.MESSAGES.getOrDefault(b.name(), List.of())), b.name() + " (" + b.target() + ")");
        }
        String merkle = Files.readString(Path.of("../julc-state-api/src/onchain/java/org/julclang/state/api/MerkleMaps.java"));
        assertTrue(merkle.contains("\"" + Std.PUT_INT + "\""), "MerkleMaps.putInt's on-chain message");
    }
}
