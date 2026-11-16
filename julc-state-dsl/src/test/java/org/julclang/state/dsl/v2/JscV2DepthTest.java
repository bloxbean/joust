package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.julclang.state.dsl.JscMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 J18 (r12, review S2 on #66): the front end never recurses deeper than a fixed bound. Nesting is capped at
 * {@value Parser#MAX_DEPTH} levels with a located, coded error (JSC0149 while parsing, JSC0150 for chains measured on
 * the tree, JSC0340 for types); chains of {@code + - * && ||} are balanced and else-if chains are one node, so they
 * lower, run in the interpreter and keep their value at any length; a fn chain carrying a time value is analysed from
 * a work list; and any Error that still escapes the front end is JSC0005, never a crash. The Web Image runs the same
 * cases ({@code build-in-node.mjs}, {@code toolchainWasmTest}); the toolchain builds them end to end
 * ({@code JscV2DepthBuildTest}).
 */
class JscV2DepthTest {

    static String contract(String members) {
        return "jsc 1\npackage t\ncontract D {\n  state { n: Int }\n  init() { return { n: 0 } }\n" + members + "\n}\n";
    }

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
        assertTrue(e.line > 0 && e.col > 0);
        return e;
    }

    /** {@code action a(x: Int) { return { ...state, n: e } }}, run in the interpreter with {@code x}. */
    static BigInteger run(String src, long x) {
        var in = JscV2.interpreter(src, "D.jsc", Interpreter.JDK_HASHES);
        var out = in.call("a", new Interpreter.Rec("State", List.of(BigInteger.ZERO)), List.of(BigInteger.valueOf(x)),
                new Interpreter.Tx(new Interpreter.Bytes(new byte[28]), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ONE));
        return (BigInteger) ((Interpreter.Rec) out.value()).fields().get(0);
    }

    static String action(String value) {
        return contract("  action a(x: Int) { return { ...state, n: " + value + " } }");
    }

    // ------------------------------------------------------------------ the cap

    @Test
    void deepParentheses_capped() {
        lower(action("(".repeat(40) + "x" + ")".repeat(40)));
        var e = refused(action("(".repeat(1000) + "x" + ")".repeat(1000)), "JSC0149");
        assertEquals(6, e.line, "at the parenthesis that opens the level past the cap");
        assertTrue(e.getMessage().contains("64 levels"), e.getMessage());
    }

    @Test
    void deepBlocks_capped() {
        String ok = "if x > 0 { ".repeat(20) + "require(x > 0, \"p\")" + " }".repeat(20);
        lower(contract("  action a(x: Int) {\n    " + ok + "\n    return state\n  }"));
        String deep = "if x > 0 { ".repeat(200) + "require(x > 0, \"p\")" + " }".repeat(200);
        refused(contract("  action a(x: Int) {\n    " + deep + "\n    return state\n  }"), "JSC0149");
    }

    @Test
    void deepLambdas_capped() {
        String ok = "xs.any(a0 => " + nestedAny(1, 12) + ")";
        lower(contract("  view v(xs: List<Int>): Bool = " + ok));
        refused(contract("  view v(xs: List<Int>): Bool = xs.any(a0 => " + nestedAny(1, 200) + ")"), "JSC0149");
    }

    static String nestedAny(int i, int n) {
        var s = new StringBuilder();
        for (int k = i; k < n; k++) s.append("xs.any(a").append(k).append(" => a").append(k - 1).append(" > 0 && ");
        s.append("true");
        s.append(")".repeat(n - i));
        return s.toString();
    }

    @Test
    void deepMatchAndIf_capped() {
        lower(nestedMatch(12));
        refused(nestedMatch(200), "JSC0149");
        lower(action(nestedIf(20)));
        refused(action(nestedIf(200)), "JSC0149");
        // A ternary chain nests: capped too.
        var t = new StringBuilder();
        for (int k = 0; k < 200; k++) t.append("x == ").append(k).append(" ? ").append(k).append(" : ");
        refused(action(t + "0"), "JSC0149");
    }

    static String nestedMatch(int n) {
        String src = "contract D {\n  state { n: Int }\n  init() { return { n: 0 } }\n  action a(o: Option<Int>) { return { ...state, n: ";
        var s = new StringBuilder();
        for (int k = 0; k < n; k++) s.append("match o { Some(v").append(k).append(") => v").append(k).append(" + ");
        s.append("0");
        s.append(", None => 0 }".repeat(n));
        return "jsc 1\npackage t\n" + src + s + " } }\n}\n";
    }

    static String nestedIf(int n) {
        return "if x > 0 { ".repeat(n) + "x" + " } else { 0 }".repeat(n);
    }

    @Test
    void unaryAndTypeNesting_capped() {
        lower(action("- ".repeat(30) + "x"));
        refused(action("- ".repeat(500) + "x"), "JSC0149");
        refused(contract("  view v(xs: " + "List<".repeat(100) + "Int" + ">".repeat(100) + "): Int = 0"), "JSC0149");
        // A type that contains another, 100 deep: refused at the declaration (the walks over values follow the chain).
        var types = new StringBuilder();
        for (int k = 0; k < 100; k++) types.append("type T").append(k).append(" = { f: ").append(k == 99 ? "Int" : "T" + (k + 1)).append(" }\n");
        var e = assertThrows(JscException.class, () -> JscV2.lower("jsc 1\npackage t\n" + types + "contract D {\n  state { n: Int }\n"
                + "  init() { return { n: 0 } }\n}\n", "D.jsc"));
        assertEquals("JSC0340", e.code, e.getMessage());
        var few = new StringBuilder();
        for (int k = 0; k < 20; k++) few.append("type T").append(k).append(" = { f: ").append(k == 19 ? "Int" : "T" + (k + 1)).append(" }\n");
        lower("jsc 1\npackage t\n" + few + "contract D {\n  state { n: Int, t: T0 }\n  init(t: T0) { return { n: 0, t } }\n}\n");
    }

    @Test
    void unbalancedChains_measuredOnTheTree() {
        lower(action("x" + " / 2".repeat(30)));
        var e = refused(action("x" + " / 2".repeat(200)), "JSC0150");
        assertEquals(6, e.line);
        refused(action("x" + " % 7".repeat(200)), "JSC0150");
    }

    // ------------------------------------------------------------------ chains: any length

    @Test
    void longOperatorChains_lowerAndKeepTheirValue() {
        int n = 5000;
        // a sum, with minus signs that a balanced split must flip inside its right part
        var sum = new StringBuilder("x");
        BigInteger want = BigInteger.valueOf(7);
        for (int k = 1; k < n; k++) {
            boolean minus = k % 3 == 0;
            sum.append(minus ? " - " : " + ").append(k);
            want = minus ? want.subtract(BigInteger.valueOf(k)) : want.add(BigInteger.valueOf(k));
        }
        String src = action(sum.toString());
        String java = lower(src).java();
        assertTrue(java.length() > n, "lowered");
        assertEquals(want, run(src, 7), "the balanced tree has the value written left to right");
        // the depth of the Java expression stays small
        assertTrue(maxParenDepth(java) < 64, "generated Java nests " + maxParenDepth(java));

        var prod = "x" + " * 1".repeat(3000);
        assertEquals(BigInteger.valueOf(9), run(action(prod), 9));
        var and = "x > 0" + " && x > 1".repeat(3000);
        lower(contract("  view v(x: Int): Bool = " + and));
        var or = "x == 0" + " || x == 1".repeat(3000);
        lower(contract("  view v(x: Int): Bool = " + or));
        // short chains keep their shape: left to right, as before
        String shortJava = lower(action("x + 1 - 2 + 3")).java();
        assertTrue(shortJava.contains("x.add(BigInteger.valueOf(1L)).subtract(BigInteger.valueOf(2L)).add(BigInteger.valueOf(3L))")
                || shortJava.contains("x.add(BigInteger.ONE).subtract(BigInteger.TWO).add(BigInteger.valueOf(3L))")
                || shortJava.matches("(?s).*x\\.add\\([^)]*\\)\\.subtract\\([^)]*\\)\\.add\\([^)]*\\).*"), shortJava);
    }

    static int maxParenDepth(String java) {
        int d = 0, max = 0;
        for (char c : java.toCharArray()) {
            if (c == '(') max = Math.max(max, ++d);
            else if (c == ')') d--;
        }
        return max;
    }

    @Test
    void longElseIfChains_lowerAndChooseTheRightArm() {
        int n = 1000;
        // statements in every arm
        var st = new StringBuilder("    if x == 0 { require(x >= 0, \"a0\") }");
        for (int k = 1; k < n; k++) st.append(" else if x == ").append(k).append(" { require(x != ").append(k).append(", \"a").append(k).append("\") }");
        st.append(" else { require(x > ").append(n).append(", \"z\") }");
        String src = contract("  action a(x: Int) {\n" + st + "\n    return { ...state, n: x }\n  }");
        String java = lower(src).java();
        assertTrue(maxParenDepth(java) < 64, "generated Java nests " + maxParenDepth(java));
        assertEquals(BigInteger.ZERO, run(src, 0));
        var refusal = assertThrows(Interpreter.Refused.class, () -> run(src, 500));
        assertEquals("a500", refusal.getMessage());
        assertEquals(BigInteger.valueOf(n + 1), run(src, n + 1));
        assertThrows(Interpreter.Refused.class, () -> run(src, n));

        // a value chain without statements
        var v = new StringBuilder("if x == 0 { 100 }");
        for (int k = 1; k < n; k++) v.append(" else if x == ").append(k).append(" { ").append(100 + k).append(" }");
        v.append(" else { 7 }");
        String vs = action(v.toString());
        String vj = lower(vs).java();
        assertTrue(maxParenDepth(vj) < 64, "generated Java nests " + maxParenDepth(vj));
        assertEquals(BigInteger.valueOf(100), run(vs, 0));
        assertEquals(BigInteger.valueOf(100 + 777), run(vs, 777));
        assertEquals(BigInteger.valueOf(7), run(vs, 5000));

        // a short value chain without statements stays inline conditionals (a recursive fn may use it)
        String fib = contract("  view v(x: Int): Int = f(x)\n  fn f(n: Int): Int = if n == 0 { 0 } else if n == 1 { 1 } else { f(n - 1) + f(n - 2) }");
        assertTrue(lower(fib).java().contains("? BigInteger"), "inline conditionals");
    }

    @Test
    void aLongChainOfFnsCarryingATimeValue_isAnalysedWithoutRecursion() {
        int n = 400;
        var fns = new StringBuilder();
        for (int k = 0; k < n; k++) fns.append("  fn f").append(k).append("(t: Int): Int = ").append(k == n - 1 ? "t + 1" : "f" + (k + 1) + "(t)").append("\n");
        String src = "jsc 1\npackage t\ncontract D {\n  state { n: Int, last: Int }\n  init() { return { n: 0, last: 0 } }\n"
                + "  action a() { return { ...state, last: f0(validFrom) } }\n" + fns + "}\n";
        lower(src);
        // A time value reaching a divisor at the end of the chain is refused there, naming every call on the way.
        String bad = src.replace("t + 1", "100 / t");
        var e = assertThrows(JscException.class, () -> JscV2.lower(bad, "D.jsc"));
        assertTrue(e.getMessage().contains("(in f" + (n - 1) + ", called with a time value at") && e.getMessage().contains("(in f0, called"),
                e.getMessage().substring(0, Math.min(400, e.getMessage().length())));
    }

    // ------------------------------------------------------------------ the safety net (JSC0005)

    /** Recurses until the stack runs out: a real StackOverflowError, raised wherever it is called from. */
    static int exhaust(int n) {
        return exhaust(n + 1) + 1;
    }

    /**
     * Any Error that escapes the front end is JSC0005 at the file, never a crash. The front end is replaced by one that
     * starts lowering and then runs out of stack for real, so the outcome no longer depends on how much stack a nested
     * input needs on this JVM (M1 hardening: a small-stack thread did not always overflow after {@code clean}, and the
     * run then failed at the ABI check, JSC0003). The other files of the run are still lowered and written.
     */
    @Test
    void anErrorInTheFrontEnd_isJsc0005_notACrash(@TempDir Path dir) throws Exception {
        assertTrue(JscMain.internalError(new StackOverflowError()).contains("ran out of stack"));
        Path src = Files.createDirectories(dir.resolve("src/t"));
        Files.writeString(src.resolve("D.jsc"), action("(".repeat(60) + "x" + ")".repeat(60)));
        Files.writeString(src.resolve("E.jsc"), contract("").replace("contract D", "contract E"));
        var overflowed = new java.util.concurrent.atomic.AtomicBoolean();
        JscMain.FrontEnd deep = (text, file) -> {
            if (!file.equals("D.jsc")) return JscMain.lowerWithWarnings(text, file);
            JscMain.lowerWithWarnings(text, file);   // the real front end accepts it (60 levels, under the cap)
            try {
                exhaust(0);
            } catch (StackOverflowError e) {
                overflowed.set(true);
                throw e;
            }
            throw new AssertionError("unreachable");
        };
        var err = new ByteArrayOutputStream();
        PrintStream old = System.err;
        int code;
        try {
            System.setErr(new PrintStream(err, true));
            code = JscMain.run(new String[]{dir.resolve("src").toString(), dir.resolve("out").toString(), "--accept-abi"}, deep);
        } finally {
            System.setErr(old);
        }
        assertTrue(overflowed.get(), "a real StackOverflowError was raised");
        assertEquals(1, code, err.toString());
        assertTrue(err.toString().contains("t/D.jsc:1:1: error[JSC0005]: internal error lowering this file: the front end ran out of stack"),
                err.toString());
        assertFalse(err.toString().contains("JSC0003"), err.toString());
        assertTrue(Files.exists(dir.resolve("out/t/E.java")), "the other file is still lowered");
        assertFalse(Files.exists(dir.resolve("out/t/D.java")), "nothing is written for the file that failed");

        // Any other Error or RuntimeException of the front end is JSC0005 too, with its text.
        err.reset();
        try {
            System.setErr(new PrintStream(err, true));
            code = JscMain.run(new String[]{dir.resolve("src").toString(), dir.resolve("out").toString(), "--accept-abi"}, (text, file) -> {
                throw new IllegalStateException("boom in " + file);
            });
        } finally {
            System.setErr(old);
        }
        assertEquals(1, code);
        assertTrue(err.toString().contains("t/D.jsc:1:1: error[JSC0005]: internal error lowering this file: java.lang.IllegalStateException: boom in D.jsc"),
                err.toString());
    }
}
