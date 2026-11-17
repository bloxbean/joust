package org.julclang.state.dsl.v2;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * #134 round r2: a deterministic sweep of the two IR readers (opt-in: set JOUST_IR_SWEEP; never in CI, it takes 5 to 11
 * minutes). Every one-step mutation of every IR fixture, each shape (a path through the format with its node, statement
 * or method kind, a key, a mutation) at most SWEEP_PER_SHAPE times (default 2): each field removed, null, [], {}, "",
 * "zz", 0, -1 or true; a node's or statement's kind, a type or a result replaced by every one the fixtures hold; a list
 * without its first element, with it twice, with a null; an unknown field. With SWEEP_POOLS set (and SWEEP_PER_SHAPE=1),
 * also every value a key takes anywhere in the fixtures, and every element of the lists under it. It fails on any verdict
 * IrReaderFuzzTest fails, and writes each refusal of validate.js with the Java reader's answer to SWEEP_OUT
 * (build/ir-sweep.txt), from which #134's table of validate.js's refusals was made.
 */
@SuppressWarnings("unchecked")
class IrReaderSweepTest {

    static final int PER_SHAPE = Integer.parseInt(System.getenv().getOrDefault("SWEEP_PER_SHAPE", "2"));
    static final Map<String, Integer> seen = new HashMap<>();
    static final Map<String, String> failures = new TreeMap<>();
    static final Map<String, Integer> failCounts = new TreeMap<>();
    static final Map<String, Integer> jsRefusals = new TreeMap<>();   // "irCheck\tirWhat\tjava-outcome" -> count
    static int runs;

    static boolean shape(String key) {
        int n = seen.merge(key, 1, Integer::sum);
        return n <= PER_SHAPE;
    }

    static String tag(Map<String, Object> m) {
        for (String k : List.of("node", "stmt", "kind")) if (m.get(k) instanceof String s) return k + "=" + s;
        return "";
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 3, unit = java.util.concurrent.TimeUnit.HOURS)
    void sweep() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("JOUST_IR_SWEEP") != null, "opt-in: JOUST_IR_SWEEP");
        IrReaderFuzzTest.seeds();
        var nodeKinds = new TreeSet<String>();
        var stmtKinds = new TreeSet<String>();
        var types = new LinkedHashMap<String, Object>();
        for (Object s : IrReaderFuzzTest.SEEDS) gather(s, nodeKinds, stmtKinds, types);
        for (Object s : IrReaderFuzzTest.SEEDS) pools(s);
        System.out.println("SWEEP node kinds " + nodeKinds.size() + ", stmt kinds " + stmtKinds.size() + ", types " + types.size() + ", pooled keys " + POOL.size());
        for (int k = 0; k < IrReaderFuzzTest.SEEDS.size(); k++) {
            Object root = IrReaderFuzzTest.SEEDS.get(k);
            String name = IrReaderFuzzTest.SEED_NAMES.get(k);
            visit(root, "", root, name, nodeKinds, stmtKinds, types);
        }
        var out = new StringBuilder("runs " + runs + "\n\nFAILURES\n");
        failures.forEach((c, ex) -> out.append(failCounts.get(c)).append("\t").append(c).append("\n").append(ex).append("\n\n"));
        out.append("\nJS REFUSALS\n");
        jsRefusals.forEach((c, n) -> out.append(n).append("\t").append(c).append("\n"));
        Files.writeString(Path.of(System.getenv().getOrDefault("SWEEP_OUT", "build/ir-sweep.txt")), out.toString());
        System.out.println("SWEEP runs " + runs + ", failure classes " + failures.size());
        org.junit.jupiter.api.Assertions.assertTrue(failures.isEmpty(), "the readers disagree:\n" + String.join("\n", failures.values()));
    }

    /** Per key: the distinct values it takes anywhere (small ones), and the distinct elements of the lists under it. */
    static final Map<String, LinkedHashMap<String, Object>> POOL = new HashMap<>();
    static final Map<String, LinkedHashMap<String, Object>> ELEMS = new HashMap<>();
    static final boolean POOLS = System.getenv("SWEEP_POOLS") != null;

    static void pools(Object v) {
        if (v instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                String k = (String) e.getKey();
                Object x = e.getValue();
                String t = IrReaderFuzzTest.text(x);
                if (t.length() <= 200) POOL.computeIfAbsent(k, z -> new LinkedHashMap<>()).putIfAbsent(t, IrReaderFuzzTest.copy(x));
                if (x instanceof List<?> l) for (Object el : l) {
                    String te = IrReaderFuzzTest.text(el);
                    if (te.length() <= 200) ELEMS.computeIfAbsent(k, z -> new LinkedHashMap<>()).putIfAbsent(te, IrReaderFuzzTest.copy(el));
                }
                pools(x);
            }
        } else if (v instanceof List<?> l) {
            for (Object x : l) pools(x);
        }
    }

    static void gather(Object v, Set<String> nodes, Set<String> stmts, Map<String, Object> types) {
        if (v instanceof Map<?, ?> m) {
            if (m.get("node") instanceof String s) nodes.add(s);
            if (m.get("stmt") instanceof String s) stmts.add(s);
            if (m.containsKey("type") && m.get("type") != null) {
                String key = IrReaderFuzzTest.text(m.get("type"));
                if (key.length() < 120) types.putIfAbsent(key, m.get("type"));
            }
            for (Object x : m.values()) gather(x, nodes, stmts, types);
        } else if (v instanceof List<?> l) {
            for (Object x : l) gather(x, nodes, stmts, types);
        }
    }

    static void visit(Object v, String path, Object root, String seed, Set<String> nodes, Set<String> stmts, Map<String, Object> types) throws Exception {
        if (v instanceof Map<?, ?> raw) {
            var m = (Map<String, Object>) raw;
            String p = path + "{" + tag(m) + "}";
            for (String k : new ArrayList<>(m.keySet())) {
                Object old = m.get(k);
                List<Object[]> muts = new ArrayList<>();
                muts.add(new Object[]{"remove", null});
                muts.add(new Object[]{"null", null});
                muts.add(new Object[]{"[]", new ArrayList<>()});
                muts.add(new Object[]{"{}", new LinkedHashMap<>()});
                muts.add(new Object[]{"\"\"", ""});
                muts.add(new Object[]{"\"zz\"", "zz"});
                muts.add(new Object[]{"0", 0L});
                muts.add(new Object[]{"-1", -1L});
                muts.add(new Object[]{"true", Boolean.TRUE});
                if (k.equals("node")) for (String n : nodes) if (!n.equals(old)) muts.add(new Object[]{"node:" + n, n});
                if (k.equals("stmt")) for (String n : stmts) if (!n.equals(old)) muts.add(new Object[]{"stmt:" + n, n});
                if (k.equals("type") || k.equals("result")) {
                    for (var e : types.entrySet()) if (!e.getKey().equals(IrReaderFuzzTest.text(old))) muts.add(new Object[]{"type:" + e.getKey(), e.getValue()});
                }
                if (POOLS) {
                    String cur = IrReaderFuzzTest.text(old);
                    int c = 0;
                    for (var e : POOL.getOrDefault(k, new LinkedHashMap<>()).entrySet()) {
                        if (e.getKey().equals(cur) || c++ >= 60) continue;
                        muts.add(new Object[]{"pool:" + e.getKey(), IrReaderFuzzTest.copy(e.getValue())});
                    }
                    if (old instanceof List<?> l0) {
                        int d = 0;
                        for (var e : ELEMS.getOrDefault(k, new LinkedHashMap<>()).entrySet()) {
                            if (d++ >= 60) break;
                            var added = new ArrayList<Object>(l0);
                            added.add(IrReaderFuzzTest.copy(e.getValue()));
                            muts.add(new Object[]{"add:" + e.getKey(), added});
                        }
                    }
                }
                // a list's elements emptied, dropped, doubled
                if (old instanceof List<?> l && !l.isEmpty()) {
                    var dropped = new ArrayList<Object>(l);
                    dropped.remove(0);
                    muts.add(new Object[]{"drop0", dropped});
                    var doubled = new ArrayList<Object>(l);
                    doubled.add(IrReaderFuzzTest.copy(l.get(0)));
                    muts.add(new Object[]{"dup0", doubled});
                    var withNull = new ArrayList<Object>(l);
                    withNull.add(null);
                    muts.add(new Object[]{"addNull", withNull});
                }
                for (Object[] mu : muts) {
                    String key = p + "." + k + "=" + mu[0];
                    if (!shape(key)) continue;
                    if (mu[0].equals("remove")) m.remove(k);
                    else m.put(k, mu[1]);
                    run(root, seed, key);
                    // restore in order (LinkedHashMap: re-inserting a removed key moves it last; rebuild)
                    restore(m, k, old);
                }
                if (old instanceof List<?> || old instanceof Map<?, ?>) visit(old, p + "." + k, root, seed, nodes, stmts, types);
            }
            // an unknown key
            String key = p + ".+unknown";
            if (shape(key)) {
                m.put("zzUnknown", 1L);
                run(root, seed, key);
                m.remove("zzUnknown");
            }
        } else if (v instanceof List<?> l) {
            int i = 0;
            for (Object x : l) visit(x, path + "[]", root, seed, nodes, stmts, types);
        }
    }

    static void restore(Map<String, Object> m, String k, Object old) {
        m.put(k, old);   // the readers do not depend on key order
    }

    static void dump() throws Exception {
        var out = new StringBuilder("runs " + runs + "\n\nFAILURES\n");
        failures.forEach((c, ex) -> out.append(failCounts.get(c)).append("\t").append(c).append("\n").append(ex).append("\n\n"));
        out.append("\nJS REFUSALS\n");
        jsRefusals.forEach((c, n) -> out.append(n).append("\t").append(c).append("\n"));
        Files.writeString(Path.of(System.getenv().getOrDefault("SWEEP_OUT", "build/ir-sweep.txt")), out.toString());
    }

    static void run(Object root, String seed, String key) throws Exception {
        runs++;
        if (runs % 100_000 == 0) {
            dump();
            System.out.println("SWEEP progress " + runs + ", failure classes " + failures.size());
        }
        String text = IrReaderFuzzTest.text(root);
        String j = IrReaderFuzzTest.java(text);
        String js = IrReaderFuzzTest.js(text);
        String v = IrReaderFuzzTest.verdict(j, js);
        String jo = j.equals("ok") ? "java-ok" : j.startsWith("refused") ? "java-refused" : "java-crash";
        if (!js.startsWith("{\"ok\":true")) {
            var a = (Map<String, Object>) AbiCheck.Json.parse(js);
            String what = String.valueOf(a.get("irWhat") != null ? a.get("irWhat") : a.get("message"));
            if (what.length() > 160) what = what.substring(0, 160);
            jsRefusals.merge(a.get("irCheck") + "\t" + what + "\t" + jo + (v.startsWith("known") ? " " + v : ""), 1, Integer::sum);
        }
        if (v.startsWith("fail")) {
            String cls;
            if (js.startsWith("{\"ok\":true")) cls = "JS ok, " + jo + ": " + (j.length() > 200 ? j.substring(0, 200) : j);
            else {
                var a = (Map<String, Object>) AbiCheck.Json.parse(js);
                cls = v + " | " + jo + " | " + a.get("irCheck") + " " + a.get("irWhat");
            }
            cls = cls.replaceAll("\\d+", "N");
            failCounts.merge(cls, 1, Integer::sum);
            failures.putIfAbsent(cls, "  seed " + seed + " at " + key + "\n  Java: " + (j.length() > 300 ? j.substring(0, 300) : j)
                    + "\n  JS: " + (js.length() > 300 ? js.substring(0, 300) : js));
        }
    }
}
