package org.julclang.state.dsl.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * S2 on #99: recovery is linear (up to a log) in the number of errors. Each hostile shape is analysed at n and 2n
 * errors; 2n may take at most 2.5 times n (best of five, after a warm-up: linear is 2, quadratic 4; N1 on #99 r2 removed
 * the two per-error searches that made recovering dropped members superlinear), and the recorded errors are capped.
 */
class JscLangScalingTest {

    private static final String HEAD = "jsc 1\npackage app\n\ncontract C {\n  state { n: Int }\n\n  init() {\n    return { n: 0 }\n  }\n";

    static String invisible(int n) {
        return HEAD + "  // " + "​".repeat(n) + "\n}\n";
    }

    static String ats(int n) {
        return HEAD + "  @\n".repeat(n) + "}\n";
    }

    static String unknown(int n) {
        var b = new StringBuilder(HEAD);
        for (int i = 0; i < n; i++) b.append("  action a").append(i).append("() {\n    require(nope > 0, \"x\")\n    return state\n  }\n");
        return b.append("}\n").toString();
    }

    /**
     * #130 r4 (Codex X6): an accepted file whose owned members and bare object-field references both grow: one owned type
     * and n owned actions, each reading its object's field (each name is looked up among the owned members).
     */
    static String owned(int n) {
        var b = new StringBuilder("jsc 1\npackage app\n\ncontract C {\n  state { k: Int }\n  owned Item { owner: Key, n: Int }\n"
                + "  init() { return { k: 0 } }\n  owned init Item.open() { return { owner: caller, n: 0 } }\n"
                + "  owned destroy Item.close() only owner { pay(owner, balance) }\n");
        for (int i = 0; i < n; i++) b.append("  owned action Item.inc").append(i).append("() only owner {\n    return { ...state, n: n + 1 }\n  }\n");
        return b.append("}\n").toString();
    }

    static String unclosed(int n) {
        var b = new StringBuilder(HEAD);
        for (int i = 0; i < n; i++) b.append("  action a").append(i).append("( {\n    return state\n  }\n");
        return b.append("}\n").toString();
    }

    private static long best(String src) {
        long best = Long.MAX_VALUE;
        for (int k = 0; k < 5; k++) {
            long t = System.nanoTime();
            JscLang.analyze(src, "C.jsc");
            best = Math.min(best, System.nanoTime() - t);
        }
        return best;
    }

    private static void scales(String what, IntFunction<String> shape, int n, String code) {
        JscLang.analyze(shape.apply(n / 4), "C.jsc");   // warm-up
        long one = best(shape.apply(n)), two = best(shape.apply(2 * n));
        double ratio = (double) two / Math.max(one, 1);
        System.out.printf("S2 %s: n=%d %.1f ms, 2n %.1f ms, ratio %.2f%n", what, n, one / 1e6, two / 1e6, ratio);
        // Below a few milliseconds the ratio is noise.
        assertTrue(ratio <= 2.5 || two < 20_000_000L, what + ": " + ratio);
        // #130 r5 (Codex X9): each shape gives its own error, as many as the cap holds, and says that more were found.
        var m = JscLang.analyze(shape.apply(2 * n), "C.jsc");
        var errors = m.diagnostics().stream().filter(d -> d.severity().equals("error")).toList();
        assertEquals(JscLang.MAX_ERRORS, errors.size(), what + ": the cap's errors recorded");
        assertTrue(errors.stream().allMatch(d -> code.equals(d.code())), what + ": every error is " + code + ": " + errors.subList(0, 3));
        assertTrue(m.advisory().contains("more-errors"), what + ": past the cap, the model says more were found");
    }

    /** N1 on #99 r2: the growth of each shape over n, 2n, 4n, 8n (run with -Djsc.profileScaling=true). */
    @Test
    void profile() {
        if (!Boolean.getBoolean("jsc.profileScaling")) return;
        for (var e : java.util.Map.<String, IntFunction<String>>of("unknown", JscLangScalingTest::unknown, "unclosed", JscLangScalingTest::unclosed,
                "ats", JscLangScalingTest::ats).entrySet()) {
            JscLang.analyze(e.getValue().apply(500), "C.jsc");
            var sb = new StringBuilder("S2 profile " + e.getKey() + ":");
            for (int n = 1000; n <= 8000; n *= 2) sb.append(String.format(" %d=%.1fms", n, best(e.getValue().apply(n)) / 1e6));
            System.out.println(sb);
        }
    }

    @Test
    void invisibleCharactersInAComment() {
        scales("U+200B in one comment", JscLangScalingTest::invisible, 4000, "JSC0145");
    }

    @Test
    void oneRefusedCharacterPerLine() {
        scales("one @ per line", JscLangScalingTest::ats, 4000, "JSC0108");
    }

    @Test
    void actionsWithAnUnknownName() {
        scales("actions with an unknown name", JscLangScalingTest::unknown, 2000, "JSC0217");
    }

    /** X6: an accepted file (no errors) with n owned members, each with field references: linear up to a log. */
    @Test
    void ownedMembersWithObjectFieldReferences() {
        // An accepted file, so the whole file is checked and indexed with no error (the shapes above are recovery's).
        var model = JscLang.analyze(owned(10), "C.jsc");
        assertTrue(model.accepted(), "the shape is accepted: " + model.diagnostics());
        java.util.function.IntToLongFunction best = n -> {
            String src = owned(n);
            long b = Long.MAX_VALUE;
            for (int k = 0; k < 5; k++) {
                long t = System.nanoTime();
                JscLang.analyze(src, "C.jsc");
                b = Math.min(b, System.nanoTime() - t);
            }
            return b;
        };
        best.applyAsLong(500);   // warm-up
        long one = best.applyAsLong(2000), two = best.applyAsLong(4000);
        double ratio = (double) two / Math.max(one, 1);
        System.out.printf("S2 owned members with object-field references: n=2000 %.1f ms, 2n %.1f ms, ratio %.2f%n", one / 1e6, two / 1e6, ratio);
        assertTrue(ratio <= 2.5 || two < 20_000_000L, "owned members: " + ratio);
    }

    @Test
    void actionsWithAnUnclosedParenthesis() {
        scales("actions with ( unclosed", JscLangScalingTest::unclosed, 2000, "JSC0110");
    }
}
