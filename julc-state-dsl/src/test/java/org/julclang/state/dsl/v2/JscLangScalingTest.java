package org.julclang.state.dsl.v2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * S2 on #99: recovery is linear (up to a log) in the number of errors. Each hostile shape is analysed at n and 2n
 * errors; 2n may take at most 3 times n (best of five, after a warm-up: linear is 2, quadratic 4), and the recorded errors are capped.
 */
class JscLangScalingTest {

    private static final String HEAD = "jsc 2\npackage app\n\ncontract C {\n  state { n: Int }\n\n  init() {\n    return { n: 0 }\n  }\n";

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

    static String unclosed(int n) {
        var b = new StringBuilder(HEAD);
        for (int i = 0; i < n; i++) b.append("  action a").append(i).append("( {\n    return state\n  }\n");
        return b.append("}\n").toString();
    }

    private static long best(String src) {
        long best = Long.MAX_VALUE;
        for (int k = 0; k < 5; k++) {
            long t = System.nanoTime();
            JscLang.analyze(src, "app/C.jsc");
            best = Math.min(best, System.nanoTime() - t);
        }
        return best;
    }

    private static void scales(String what, IntFunction<String> shape, int n) {
        JscLang.analyze(shape.apply(n / 4), "app/C.jsc");   // warm-up
        long one = best(shape.apply(n)), two = best(shape.apply(2 * n));
        double ratio = (double) two / Math.max(one, 1);
        System.out.printf("S2 %s: n=%d %.1f ms, 2n %.1f ms, ratio %.2f%n", what, n, one / 1e6, two / 1e6, ratio);
        // Below a few milliseconds the ratio is noise.
        assertTrue(ratio <= 3.0 || two < 20_000_000L, what + ": " + ratio);
        var m = JscLang.analyze(shape.apply(2 * n), "app/C.jsc");
        long errors = m.diagnostics().stream().filter(d -> d.severity().equals("error")).count();
        assertTrue(errors <= JscLang.MAX_ERRORS, what + ": " + errors + " errors recorded");
        assertTrue(errors >= 1, what + ": the first error is kept");
    }

    @Test
    void invisibleCharactersInAComment() {
        scales("U+200B in one comment", JscLangScalingTest::invisible, 4000);
    }

    @Test
    void oneRefusedCharacterPerLine() {
        scales("one @ per line", JscLangScalingTest::ats, 4000);
    }

    @Test
    void actionsWithAnUnknownName() {
        scales("actions with an unknown name", JscLangScalingTest::unknown, 2000);
    }

    @Test
    void actionsWithAnUnclosedParenthesis() {
        scales("actions with ( unclosed", JscLangScalingTest::unclosed, 2000);
    }
}
