package org.julclang.state.dsl.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.ToLongFunction;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * S2 on #99: recovery is linear (up to a log) in the number of errors, and the index in the number of members. Each shape
 * is analysed at n and {@value #K}n; {@value #K}n may take at most {@value #BOUND} times n: linear is 8, n log n about 9,
 * n^1.5 about 23, quadratic 64. N1 on #99 r2 removed the two per-error searches that made recovering dropped members
 * superlinear; the recorded errors are capped.
 * <p>
 * #134: one slow sample on a shared runner broke the old 2n bound of 2.5 (3.27, 3.42 on CI), and a 4n bound of 8 let a
 * quadratic per-error search through (Codex W3: 5.5 to 6.3, its linear part hiding its square). Each size is now timed
 * {@value #RUNS} times, n and {@value #K}n interleaved after a warm-up of both, and the best of each is compared (a pause
 * slows one sample, never every sample of one size); the {@value #K}n file must take at least {@value #MIN_MS} ms, or the
 * test fails as unmeasurable rather than passing. The negative controls compile a mutated copy of the module's own source
 * (a per-error list search in the error cap; the pre-X6 linear inputAt) and require these same measurements to reject it.
 * The index exposes no operation count to assert instead.
 */
class JscLangScalingTest {

    /** The larger size, as a multiple of the smaller. */
    static final int K = 8;
    /** K n over n: linear is 8 (the shapes measure 7.7 to 10.6), n^1.5 23, quadratic 64 (the controls measure 22 to 26). */
    static final double BOUND = 16.0;
    static final int RUNS = 7;
    /** The K n file's best must take this long, or the ratio is timer noise and the test fails as unmeasurable. */
    static final long MIN_MS = 20;

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

    // ------------------------------------------------------------------ the sizes, shared by the checks and the controls

    static final int INVISIBLE = 320_000, ATS = 16_000, UNKNOWN = 4_000, OWNED = 2_000, UNCLOSED = 16_000;

    // ------------------------------------------------------------------ measurement

    private static final ToLongFunction<String> REAL = src -> {
        long t = System.nanoTime();
        JscLang.analyze(src, "C.jsc");
        return System.nanoTime() - t;
    };

    /** The best of {@code runs} timings of n and of K n, interleaved, after a warm-up of both: {best n, best K n}. */
    static long[] measure(ToLongFunction<String> analyze, IntFunction<String> shape, int n, int runs) {
        String small = shape.apply(n), big = shape.apply(K * n);
        analyze.applyAsLong(small);
        analyze.applyAsLong(big);
        long one = Long.MAX_VALUE, many = Long.MAX_VALUE;
        for (int k = 0; k < runs; k++) {
            one = Math.min(one, analyze.applyAsLong(small));
            many = Math.min(many, analyze.applyAsLong(big));
        }
        return new long[]{one, many};
    }

    static double ratio(long[] t) {
        return (double) t[1] / Math.max(t[0], 1);
    }

    /** Asserts that K n takes at most BOUND times n, and long enough to be measured. */
    static void linear(String what, IntFunction<String> shape, int n) {
        long[] t = measure(REAL, shape, n, RUNS);
        System.out.printf("S2 %s: n=%d %.1f ms, %dn %.1f ms, ratio %.2f (bound %.0f)%n", what, n, t[0] / 1e6, K, t[1] / 1e6, ratio(t), BOUND);
        assertTrue(t[1] >= MIN_MS * 1_000_000L, what + ": " + K + "n takes " + t[1] / 1e6 + " ms, too fast to measure: raise n");
        assertTrue(ratio(t) <= BOUND, what + ": " + K + "n takes " + String.format("%.2f", ratio(t)) + " times n (bound " + BOUND + ")");
    }

    private static void scales(String what, IntFunction<String> shape, int n, String code) {
        linear(what, shape, n);
        // #130 r5 (Codex X9): each shape gives its own error, as many as the cap holds, and says that more were found.
        var m = JscLang.analyze(shape.apply(2 * n), "C.jsc");
        var errors = m.diagnostics().stream().filter(d -> d.severity().equals("error")).toList();
        assertEquals(JscLang.MAX_ERRORS, errors.size(), what + ": the cap's errors recorded");
        assertTrue(errors.stream().allMatch(d -> code.equals(d.code())), what + ": every error is " + code + ": " + errors.subList(0, 3));
        assertTrue(m.advisory().contains("more-errors"), what + ": past the cap, the model says more were found");
    }

    @Test
    void invisibleCharactersInAComment() {
        scales("U+200B in one comment", JscLangScalingTest::invisible, INVISIBLE, "JSC0145");
    }

    @Test
    void oneRefusedCharacterPerLine() {
        scales("one @ per line", JscLangScalingTest::ats, ATS, "JSC0108");
    }

    @Test
    void actionsWithAnUnknownName() {
        scales("actions with an unknown name", JscLangScalingTest::unknown, UNKNOWN, "JSC0217");
    }

    /** X6: an accepted file (no errors) with n owned members, each with field references: linear up to a log. */
    @Test
    void ownedMembersWithObjectFieldReferences() {
        // An accepted file, so the whole file is checked and indexed with no error (the shapes above are recovery's).
        var model = JscLang.analyze(owned(10), "C.jsc");
        assertTrue(model.accepted(), "the shape is accepted: " + model.diagnostics());
        linear("owned members with object-field references", JscLangScalingTest::owned, OWNED);
    }

    @Test
    void actionsWithAnUnclosedParenthesis() {
        scales("actions with ( unclosed", JscLangScalingTest::unclosed, UNCLOSED, "JSC0110");
    }

    // ------------------------------------------------------------------ negative controls (#134 r3, Codex W3)

    /** The error cap's constant-time test replaced by a per-error search of the errors found (Codex W3). */
    static final String[] ERROR_CAP = {"JscLang.java", "if (diagnostics.size() >= MAX_ERRORS) {", "if (errors.indexOf(e) >= MAX_ERRORS) {"};

    /** inputAt before X6 (#130 r4): a scan of every declaration per name, O(R x M). */
    static final String[] LINEAR_INPUT_AT = {"JscLangIndex.java", "    private String inputAt(int line, int col) {\n",
            "    private String inputAt(int line, int col) {\n"
                    + "        for (Spans.Decl d : spans.decls) {\n"
                    + "            if (d.input == null || d.endLine < 0) continue;\n"
                    + "            boolean after = line > d.startLine || line == d.startLine && col >= d.startCol;\n"
                    + "            boolean before = line < d.endLine || line == d.endLine && col <= d.endCol;\n"
                    + "            if (after && before) return d.input;\n"
                    + "        }\n"
                    + "        if (spans != null) return null;\n"};

    /** The measurements above reject each quadratic mutant (best of 5: a mutant's K n file takes 0.7 to 1.7 s). */
    @Test
    void theMeasurementsRejectQuadraticImplementations() throws Exception {
        var errorCap = mutant(ERROR_CAP);
        // The error cap is one loop over every shape's errors: the cheapest error per member (an unclosed parenthesis) lets its
        // square show soonest (the unknown-name shape's checking hides it until far larger files).
        rejects("error cap: actions with ( unclosed", errorCap, JscLangScalingTest::unclosed, UNCLOSED);
        rejects("linear inputAt: owned members", mutant(LINEAR_INPUT_AT), JscLangScalingTest::owned, OWNED);
    }

    static void rejects(String what, ToLongFunction<String> analyze, IntFunction<String> shape, int n) {
        long[] t = measure(analyze, shape, n, 5);
        System.out.printf("S2 control %s: n=%d %.1f ms, %dn %.1f ms, ratio %.2f (bound %.0f)%n", what, n, t[0] / 1e6, K, t[1] / 1e6, ratio(t), BOUND);
        assertTrue(ratio(t) > BOUND, what + ": a quadratic implementation passes the bound at " + String.format("%.2f", ratio(t)));
    }

    /**
     * JscLang.analyze of the module's classes with one source file mutated ({file, text, replacement}): the file compiled
     * in memory against the test's class path, and every class of the module loaded beside it by one class loader.
     */
    static ToLongFunction<String> mutant(String[] m) throws Exception {
        Path src = Path.of("src/main/java/org/julclang/state/dsl/v2", m[0]);
        String text = Files.readString(src);
        int at = text.indexOf(m[1]);
        assertTrue(at >= 0 && text.indexOf(m[1], at + 1) < 0, m[0] + ": the mutated text occurs once (update the control): " + m[1]);
        String mutated = text.substring(0, at) + m[2] + text.substring(at + m[1].length());
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Map<String, ByteArrayOutputStream> out = new HashMap<>();
        var std = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        JavaFileManager files = new ForwardingJavaFileManager<>(std) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location l, String name, JavaFileObject.Kind k, FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + name.replace('.', '/') + ".class"), k) {
                    @Override
                    public OutputStream openOutputStream() {
                        return out.computeIfAbsent(name, x -> new ByteArrayOutputStream());
                    }
                };
            }
        };
        var unit = new SimpleJavaFileObject(URI.create("string:///" + m[0]), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) {
                return mutated;
            }
        };
        var log = new java.io.StringWriter();
        boolean ok = javac.getTask(log, files, null, List.of("-classpath", System.getProperty("java.class.path"), "-proc:none", "-nowarn"),
                null, List.of(unit)).call();
        assertTrue(ok, "the mutant compiles: " + log);
        ClassLoader parent = JscLangScalingTest.class.getClassLoader();
        ClassLoader loader = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (!name.startsWith("org.julclang.state.dsl.")) return super.loadClass(name, resolve);
                    Class<?> c = findLoadedClass(name);
                    if (c == null) {
                        byte[] b = out.containsKey(name) ? out.get(name).toByteArray() : read(parent, name);
                        if (b == null) return super.loadClass(name, resolve);
                        c = defineClass(name, b, 0, b.length);
                    }
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
        };
        Method analyze = loader.loadClass("org.julclang.state.dsl.v2.JscLang").getMethod("analyze", String.class, String.class);
        return s -> {
            long t = System.nanoTime();
            try {
                analyze.invoke(null, s, "C.jsc");
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            return System.nanoTime() - t;
        };
    }

    private static byte[] read(ClassLoader from, String name) {
        try (InputStream in = from.getResourceAsStream(name.replace('.', '/') + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ profile

    /** N1 on #99 r2: each shape's and each control's ratios at n/2, n and 2n (run with JSC_SCALING_PROFILE set). */
    @Test
    void profile() throws Exception {
        if (System.getenv("JSC_SCALING_PROFILE") == null && !Boolean.getBoolean("jsc.profileScaling")) return;
        var impls = new java.util.LinkedHashMap<String, ToLongFunction<String>>();
        impls.put("real", REAL);
        impls.put("errorCap", mutant(ERROR_CAP));
        impls.put("linearInputAt", mutant(LINEAR_INPUT_AT));
        var shapes = new java.util.LinkedHashMap<String, Object[]>();
        shapes.put("invisible", new Object[]{(IntFunction<String>) JscLangScalingTest::invisible, INVISIBLE});
        shapes.put("ats", new Object[]{(IntFunction<String>) JscLangScalingTest::ats, ATS});
        shapes.put("unknown", new Object[]{(IntFunction<String>) JscLangScalingTest::unknown, UNKNOWN});
        shapes.put("owned", new Object[]{(IntFunction<String>) JscLangScalingTest::owned, OWNED});
        shapes.put("unclosed", new Object[]{(IntFunction<String>) JscLangScalingTest::unclosed, UNCLOSED});
        for (var i : impls.entrySet()) {
            for (var s : shapes.entrySet()) {
                @SuppressWarnings("unchecked") var shape = (IntFunction<String>) s.getValue()[0];
                int n = (Integer) s.getValue()[1];
                var sb = new StringBuilder("S2 profile " + i.getKey() + " " + s.getKey() + ":");
                for (int m : new int[]{n / 2, n, 2 * n}) {
                    long[] t = measure(i.getValue(), shape, m, i.getKey().equals("real") ? RUNS : 3);
                    sb.append(String.format(" n=%d %.1f/%.1f ms %.2f;", m, t[0] / 1e6, t[1] / 1e6, ratio(t)));
                }
                System.out.println(sb);
            }
        }
    }
}
