package org.julclang.state.toolchain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 J18 (r12, review S2 on #66) end to end on the JVM path: the toolchain (front end, javac, the processor,
 * JuLC) builds each case below or refuses it with the cap's code at a {@code .jsc} position, never with a crash. The
 * Web Image runs the same cases on a default-stack Node ({@code src/test/js/depth-in-node.mjs}, in
 * {@code toolchainWasmTest}), and must give the same outcome.
 */
class JscV2DepthBuildTest {

    static String contract(String members) {
        return "jsc 2\npackage t\ncontract D {\n  state { n: Int }\n  init() { return { n: 0 } }\n" + members + "\n}\n";
    }

    static String action(String value) {
        return contract("  action a(x: Int) { return { ...state, n: " + value + " } }");
    }

    /** The cases: name → source. Outcome "ok" or the expected code; kept equal to depth-in-node.mjs. */
    static Map<String, String[]> cases() {
        var c = new LinkedHashMap<String, String[]>();
        c.put("parentheses 56", new String[]{action("(".repeat(56) + "x" + ")".repeat(56)), "ok"});
        c.put("parentheses 1000", new String[]{action("(".repeat(1000) + "x" + ")".repeat(1000)), "JSC0149"});
        c.put("blocks 25", new String[]{blocks(25), "ok"});
        c.put("blocks 200", new String[]{blocks(200), "JSC0149"});
        c.put("lambdas 12", new String[]{lambdas(12), "ok"});
        c.put("lambdas 200", new String[]{lambdas(200), "JSC0149"});
        c.put("match 12", new String[]{matches(12), "ok"});
        c.put("match 200", new String[]{matches(200), "JSC0149"});
        c.put("value ifs 20", new String[]{action("if x > 0 { ".repeat(20) + "x" + " } else { 0 }".repeat(20)), "ok"});
        c.put("value ifs 200", new String[]{action("if x > 0 { ".repeat(200) + "x" + " } else { 0 }".repeat(200)), "JSC0149"});
        c.put("sum of 5000", new String[]{sum(5000), "ok"});
        c.put("&& of 3000", new String[]{contract("  view v(x: Int): Bool = x > 0" + " && x > 1".repeat(2999)), "ok"});
        // The Web Image runs 200 here: JuLC's passes nest with the compiled term, and its 1 MB stack holds about 250.
        c.put("else-if statements 200", new String[]{elseIfs(200), "ok"});
        c.put("else-if statements 1000", new String[]{elseIfs(1000), "ok"});
        c.put("else-if values 1000", new String[]{elseIfValues(1000), "ok"});
        c.put("/ chain 200", new String[]{action("x" + " / 2".repeat(200)), "JSC0150"});
        c.put("types 100", new String[]{types(100), "JSC0340"});
        return c;
    }

    static String blocks(int n) {
        return contract("  action a(x: Int) {\n    " + "if x > 0 { ".repeat(n) + "require(x > 0, \"p\")" + " }".repeat(n) + "\n    return state\n  }");
    }

    static String lambdas(int n) {
        var s = new StringBuilder("xs.any(a0 => ");
        for (int k = 1; k < n; k++) s.append("xs.any(a").append(k).append(" => a").append(k - 1).append(" > 0 && ");
        s.append("true").append(")".repeat(n));
        return contract("  view v(xs: List<Int>): Bool = " + s);
    }

    static String matches(int n) {
        var s = new StringBuilder();
        for (int k = 0; k < n; k++) s.append("match o { Some(v").append(k).append(") => v").append(k).append(" + ");
        s.append("0").append(", None => 0 }".repeat(n));
        return contract("  action a(o: Option<Int>) { return { ...state, n: " + s + " } }");
    }

    static String sum(int n) {
        var s = new StringBuilder("x");
        for (int k = 1; k < n; k++) s.append(k % 3 == 0 ? " - " : " + ").append(k);
        return action(s.toString());
    }

    static String elseIfs(int n) {
        var s = new StringBuilder("    if x == 0 { require(x >= 0, \"a0\") }");
        for (int k = 1; k < n; k++) s.append(" else if x == ").append(k).append(" { require(x != ").append(k).append(", \"a").append(k).append("\") }");
        s.append(" else { require(x > ").append(n).append(", \"z\") }");
        return contract("  action a(x: Int) {\n" + s + "\n    return { ...state, n: x }\n  }");
    }

    static String elseIfValues(int n) {
        var s = new StringBuilder("if x == 0 { 100 }");
        for (int k = 1; k < n; k++) s.append(" else if x == ").append(k).append(" { ").append(100 + k).append(" }");
        return action(s + " else { 7 }");
    }

    static String types(int n) {
        var t = new StringBuilder();
        for (int k = 0; k < n; k++) t.append("type T").append(k).append(" = { f: ").append(k == n - 1 ? "Int" : "T" + (k + 1)).append(" }\n");
        return "jsc 2\npackage t\n" + t + "contract D {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n";
    }

    @Test
    void everyCase_buildsOrIsRefusedWithItsCode() {
        for (var e : cases().entrySet()) {
            long t = System.nanoTime();
            var r = Toolchain.build(Map.of("t/D.jsc", e.getValue()[0]), Map.of());
            String want = e.getValue()[1];
            if (want.equals("ok")) {
                assertTrue(r.ok(), e.getKey() + ": " + r.diagnostics().toString().substring(0, Math.min(800, r.diagnostics().toString().length())));
            } else {
                assertFalse(r.ok(), e.getKey());
                var d = r.diagnostics().stream().filter(x -> x.severity() == Toolchain.Severity.ERROR).findFirst().orElseThrow();
                assertEquals(want, d.code(), e.getKey() + ": " + d);
                assertEquals("t/D.jsc", d.file());
                assertTrue(d.line() > 0 && d.col() > 0, e.getKey() + ": located");
            }
            System.out.printf("[depth jvm] %-24s %s in %d ms%n", e.getKey(), want, (System.nanoTime() - t) / 1_000_000);
        }
    }
}
