package org.julclang.state.toolchain;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.MapEntry;
import org.julclang.state.api.MerkleMap;
import org.julclang.state.api.MerkleMaps;
import org.julclang.state.api.Payout;
import org.julclang.state.api.mpf.Mpf;
import org.julclang.state.dsl.JscException;
import org.julclang.state.dsl.JscMain;
import org.julclang.state.dsl.Migrate;
import org.julclang.state.dsl.v2.Interpreter;
import org.julclang.state.dsl.v2.Interpreter.BigMapV;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.JscV2;
import org.julclang.state.runtime.InMemoryNodeStore;
import org.julclang.state.runtime.MerkleSupport;
import org.julclang.stdlib.Builtins;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D16, J13: {@code joust migrate} preserves behaviour, checked differentially on generated v1 programs. Each
 * program's v1 lowering runs on the JVM (compiled here); its migration runs on the {@code .jsc} 2 reference interpreter
 * (D15), which is written independently of both lowerings. For random states and arguments both must refuse, or both
 * give the same next state (scalar fields, and the map's contents on every key a call can name) and the same multiset
 * of payouts (v1 lists them in reverse source order, {@code .jsc} 2 in source order).
 * <p>
 * The generator covers what the SSA translation must get right: reads after assignments ({@code n += a; let x = n}),
 * {@code this.f} against a parameter, ifs that assign in one or both arms (nested), checks and payouts in arms, map
 * writes and deletes in straight-line code and in arms (same and different keys), reads and {@code has} through earlier
 * writes, and divisions by zero in arms that must not run.
 */
class JscMigrateDifferentialTest {

    static final HexFormat HEX = HexFormat.of();
    static final byte[][] KEYS = {fill(0x11), fill(0x22), fill(0x33)};
    static final String PKG = "gen.migrate";

    static byte[] fill(int b) {
        byte[] k = new byte[28];
        java.util.Arrays.fill(k, (byte) b);
        return k;
    }

    // ------------------------------------------------------------------ the generator (valid v1 by construction, mostly)

    static final class Gen {
        final Random rnd;
        final boolean map;
        int lets;
        final List<String> scope = new ArrayList<>();
        final List<String> unused = new ArrayList<>();
        final List<String> params;

        Gen(Random rnd, boolean map, List<String> params) {
            this.rnd = rnd;
            this.map = map;
            this.params = params;
        }

        String key() {
            return switch (rnd.nextInt(3)) {
                case 0 -> "k";
                case 1 -> "caller";
                default -> "owner";
            };
        }

        String intExpr(int depth) {
            int r = rnd.nextInt(depth <= 0 ? 6 : 13);
            return switch (r) {
                case 0 -> String.valueOf(rnd.nextInt(12));
                case 1 -> params.get(rnd.nextInt(params.size()));
                case 2 -> rnd.nextBoolean() ? "n" : "m";
                case 3 -> rnd.nextBoolean() ? "this.n" : "this.m";
                case 4 -> !scope.isEmpty() ? use(scope.get(rnd.nextInt(scope.size()))) : "a";
                case 5 -> map ? "pts[" + key() + "]" : "b";
                case 6, 7 -> "(" + intExpr(depth - 1) + " " + "+-*".charAt(rnd.nextInt(3)) + " " + intExpr(depth - 1) + ")";
                case 8 -> "(" + intExpr(depth - 1) + " " + (rnd.nextBoolean() ? "/" : "%") + " " + intExpr(depth - 1) + ")";
                case 9 -> (rnd.nextBoolean() ? "min(" : "max(") + intExpr(depth - 1) + ", " + intExpr(depth - 1) + ")";
                case 10 -> "-" + intExpr(depth - 1);
                default -> "(" + intExpr(depth - 1) + " - " + intExpr(depth - 1) + ")";
            };
        }

        String use(String let) {
            unused.remove(let);
            return let;
        }

        String boolExpr(int depth) {
            int r = rnd.nextInt(depth <= 0 ? 4 : 8);
            return switch (r) {
                case 0 -> "(" + intExpr(1) + " " + List.of("<", "<=", ">", ">=", "==", "!=").get(rnd.nextInt(6)) + " " + intExpr(1) + ")";
                case 1 -> "flag";
                case 2 -> map ? "has(pts, " + key() + ")" : "flag";
                case 3 -> "(" + key() + " == " + key() + ")";
                case 4 -> "!" + boolExpr(depth - 1);
                case 5, 6 -> "(" + boolExpr(depth - 1) + (rnd.nextBoolean() ? " && " : " || ") + boolExpr(depth - 1) + ")";
                default -> "(" + intExpr(1) + " < " + intExpr(1) + ")";
            };
        }

        void stmts(StringBuilder out, String ind, int n, int depth, boolean pays) {
            for (int i = 0; i < n; i++) stmt(out, ind, depth, pays);
        }

        void stmt(StringBuilder out, String ind, int depth, boolean pays) {
            int r = rnd.nextInt(depth > 0 ? 11 : 9);
            switch (r) {
                case 0, 1 -> {
                    String f = rnd.nextBoolean() ? "n" : "m";
                    out.append(ind).append(params.contains(f) ? "this." + f : f).append(List.of(" = ", " += ", " -= ").get(rnd.nextInt(3)))
                            .append(intExpr(2)).append('\n');
                }
                case 2 -> out.append(ind).append("flag = ").append(boolExpr(1)).append('\n');
                case 3 -> {
                    if (!map) {
                        out.append(ind).append("owner = ").append(rnd.nextBoolean() ? "k" : "caller").append('\n');
                    } else if (rnd.nextInt(4) == 0) {
                        out.append(ind).append("delete pts[").append(key()).append("]\n");
                    } else {
                        out.append(ind).append("pts[").append(key()).append("]").append(List.of(" = ", " += ", " -= ").get(rnd.nextInt(3)))
                                .append(intExpr(1)).append('\n');
                    }
                }
                case 4 -> {
                    String x = "x" + (++lets);
                    out.append(ind).append("let ").append(x).append(" = ").append(intExpr(2)).append('\n');
                    scope.add(x);
                    unused.add(x);
                }
                case 5 -> out.append(ind).append("require ").append(boolExpr(2)).append(" \"check ").append(rnd.nextInt(100)).append("\"\n");
                case 6 -> {
                    if (pays) out.append(ind).append("pay ").append(intExpr(1)).append(" to ").append(key()).append('\n');
                    else out.append(ind).append("m += 1\n");
                }
                case 7 -> out.append(ind).append("this.n = ").append(intExpr(1)).append('\n');
                case 8 -> {
                    if (map) out.append(ind).append("pts[").append(key()).append("] += ").append(rnd.nextInt(5)).append('\n');
                    else out.append(ind).append("n += ").append(intExpr(1)).append('\n');
                }
                default -> {
                    out.append(ind).append("if ").append(boolExpr(2)).append(" {\n");
                    int mark = scope.size();
                    stmts(out, ind + "    ", 1 + rnd.nextInt(3), depth - 1, pays);
                    useAll(out, ind + "    ", mark);
                    out.append(ind).append("}");
                    if (rnd.nextBoolean()) {
                        out.append(" else {\n");
                        stmts(out, ind + "    ", 1 + rnd.nextInt(3), depth - 1, pays);
                        useAll(out, ind + "    ", mark);
                        out.append(ind).append("}");
                    }
                    out.append('\n');
                }
            }
        }

        /** Lets declared since {@code mark} (in an arm) leave scope here: each unused one is used by a check. */
        void useAll(StringBuilder out, String ind, int mark) {
            while (scope.size() > mark) {
                String x = scope.remove(scope.size() - 1);
                if (unused.remove(x)) out.append(ind).append("require ").append(x).append(" >= ").append(x).append(" - 1 \"use ").append(x).append("\"\n");
            }
        }
    }

    static String program(int seed) {
        var rnd = new Random(seed * 0x9E3779B97F4A7C15L);
        boolean map = seed % 2 == 0;
        var src = new StringBuilder("package " + PKG + "\n\n// generated v1 program " + seed + "\ncontract G" + seed + " {\n");
        src.append("    state n: Int\n    state m: Int\n    state flag: Bool\n    state owner: Key\n");
        if (map) src.append("    state pts: Map<Key, Int>\n");
        src.append("\n    init(a: Int) {\n        owner = caller\n        n = a\n    }\n");
        for (int i = 0; i < 3; i++) {
            boolean pays = rnd.nextInt(3) == 0;
            // A parameter named like a field (n) in one action: this.n is the field, n the parameter.
            var params = i == 2 ? List.of("n", "b") : List.of("a", "b");
            var g = new Gen(rnd, map, params);
            src.append("\n    action act").append(i).append("(").append(params.get(0)).append(": Int, b: Int, k: Key)")
                    .append(pays ? " open" : "").append(" {\n");
            var body = new StringBuilder();
            g.stmts(body, "        ", 2 + rnd.nextInt(4), 2, pays);
            g.useAll(body, "        ", 0);
            src.append(body).append("    }\n");
        }
        src.append("\n    destroy close() only owner {\n        pay balance to owner\n    }\n");
        src.append("\n    view total(): Int = n + m").append(map ? " + pts[owner]" : "").append("\n}\n");
        return src.toString();
    }

    // ------------------------------------------------------------------ the two legs

    record Prog(int seed, String v1, boolean map, Class<?> cls, Interpreter v2, String migrated) {}

    @Test
    void migratedProgramsBehaveAsTheirV1Originals() throws Exception {
        var progs = new ArrayList<Prog>();
        var sources = new LinkedHashMap<String, String>();
        var texts = new LinkedHashMap<Integer, String>();
        var migrated = new LinkedHashMap<Integer, String>();
        var refusals = new TreeMap<String, Integer>();
        int invalid = 0;
        for (int seed = 1; texts.size() < 160; seed++) {
            String v1 = program(seed);
            String java;
            try {
                java = JscMain.lower(v1, "G" + seed + ".jsc");
            } catch (JscException e) {
                invalid++;
                continue;
            }
            try {
                migrated.put(seed, Migrate.migrate(v1, "G" + seed + ".jsc").source());
            } catch (JscException e) {
                if (!"JSC1106".equals(e.code) && !"JSC1105".equals(e.code)) System.out.println("[migrate differential] refused " + e.format("G" + seed + ".jsc") + "\n" + v1);
                refusals.merge(e.code + " " + e.getMessage().replaceAll("[0-9]+", "#").substring(0, Math.min(90, e.getMessage().length())), 1, Integer::sum);
                continue;
            }
            texts.put(seed, v1);
            sources.put(PKG.replace('.', '/') + "/G" + seed + ".java", java);
        }
        System.out.println("[migrate differential] programs: " + texts.size() + " migrated, " + invalid + " invalid v1 skipped, refusals: " + refusals);
        // Only refusals that state a real limit (a dead value that can fail; a key computed in an arm).
        refusals.keySet().forEach(r -> assertTrue(r.startsWith("JSC1106") || r.startsWith("JSC1105"), r));
        assertTrue(refusals.values().stream().mapToInt(Integer::intValue).sum() * 5 < texts.size(), "too many refusals: " + refusals);

        var skipped = new java.util.TreeSet<String>();
        ClassLoader loader = compile(sources, skipped);
        System.out.println("[migrate differential] v1 lowerings javac refuses (a Map never written: no MerkleMap import): " + skipped.size());
        for (var e : texts.entrySet()) {
            int seed = e.getKey();
            if (skipped.contains(PKG.replace('.', '/') + "/G" + seed + ".java")) continue;
            progs.add(new Prog(seed, e.getValue(), e.getValue().contains("Map<Key"), loader.loadClass(PKG + ".G" + seed),
                    JscV2.interpreter(migrated.get(seed), "G" + seed + ".jsc", Interpreter.JDK_HASHES), migrated.get(seed)));
        }
        int calls = 0, refused = 0, maps = 0, paid = 0;
        for (Prog p : progs) {
            if (p.map()) maps++;
            var rnd = new Random(p.seed() * 31L);
            for (int i = 0; i < 40; i++) {
                String method = i == 0 ? "init" : i % 13 == 0 ? "close" : i % 7 == 0 ? "total" : "act" + rnd.nextInt(3);
                var st = new In(rnd, p.map());
                List<BigInteger> ints = List.of(BigInteger.valueOf(rnd.nextInt(14) - 3), BigInteger.valueOf(rnd.nextInt(14) - 3));
                byte[] k = KEYS[rnd.nextInt(3)], caller = KEYS[rnd.nextInt(3)];
                long balance = rnd.nextInt(5) * 1_000_000L;
                String a = v1(p, method, st, ints, k, caller, balance), b = v2(p, method, st, ints, k, caller, balance);
                assertEquals(a, b, "G" + p.seed() + "." + method + " on " + st + " " + ints + " k=" + k[0] + " caller=" + caller[0]
                        + "\n--- v1\n" + p.v1() + "--- migrated\n" + p.migrated());
                calls++;
                if (a.equals("refused")) refused++;
                if (a.startsWith("ok") && !a.endsWith("pay=[]") && !method.equals("close")) paid++;
            }
        }
        System.out.println("[migrate differential] " + progs.size() + " programs (" + maps + " with a Map), " + calls + " calls, "
                + refused + " refused on both, " + paid + " actions with payouts: v1 on the JVM and the migration on the reference interpreter agree");
        assertTrue(maps > progs.size() / 4 && maps < progs.size() * 3 / 4, "maps " + maps);
        assertTrue(paid > 50, "payouts " + paid);
        assertTrue(refused > calls / 20 && refused < calls * 3 / 4, "refusals " + refused + " of " + calls);
    }

    /** A random input state. */
    static final class In {
        final BigInteger n, m;
        final boolean flag;
        final byte[] owner;
        final Map<Integer, BigInteger> pts = new TreeMap<>();

        In(Random rnd, boolean map) {
            n = BigInteger.valueOf(rnd.nextInt(20) - 4);
            m = BigInteger.valueOf(rnd.nextInt(20) - 4);
            flag = rnd.nextBoolean();
            owner = KEYS[rnd.nextInt(3)];
            if (map) for (int i = 0; i < 3; i++) if (rnd.nextBoolean()) pts.put(i, BigInteger.valueOf(1 + rnd.nextInt(9)));
        }

        @Override
        public String toString() {
            return "{n=" + n + ", m=" + m + ", flag=" + flag + ", owner=" + owner[0] + ", pts=" + pts + "}";
        }
    }

    static String outcome(BigInteger n, BigInteger m, boolean flag, byte[] owner, Map<Integer, BigInteger> pts, List<String> payouts) {
        var sorted = new ArrayList<>(payouts);
        java.util.Collections.sort(sorted);
        return "ok: n=" + n + " m=" + m + " flag=" + flag + " owner=" + HEX.formatHex(owner).substring(0, 2) + " pts=" + pts + " pay=" + sorted;
    }

    // v1: the lowering's Java on the JVM, map reads served from a node store.
    final InMemoryNodeStore store = new InMemoryNodeStore();

    MerkleMap root(Map<Integer, BigInteger> pts) {
        if (pts.isEmpty()) return MerkleMaps.empty();
        var sorted = new TreeMap<String, byte[]>();
        pts.forEach((i, v) -> sorted.put(HEX.formatHex(KEYS[i]), Builtins.integerToByteString(true, 0, v)));
        JulcList<MapEntry> net = JulcList.empty();
        for (var e : sorted.descendingMap().entrySet()) net = net.prepend(new MapEntry(HEX.parseHex(e.getKey()), true, e.getValue()));
        byte[] r = new MerkleSupport.TrieProver(store).write(Mpf.emptyRoot(), net).root();
        return new MerkleMap(r, JulcList.empty(), JulcList.empty());
    }

    String v1(Prog p, String method, In in, List<BigInteger> ints, byte[] k, byte[] caller, long balance) throws Exception {
        Class<?> stateClass = Class.forName(p.cls().getName() + "$State", true, p.cls().getClassLoader());
        Object state = p.map() ? stateClass.getDeclaredConstructors()[0].newInstance(in.n, in.m, in.flag, in.owner, root(in.pts))
                : stateClass.getDeclaredConstructors()[0].newInstance(in.n, in.m, in.flag, in.owner);
        Ctx ctx = new Ctx(caller, BigInteger.ZERO, BigInteger.valueOf(balance), BigInteger.ZERO, BigInteger.ZERO);
        Method target = null;
        for (Method x : p.cls().getMethods()) if (x.getName().equals(method)) target = x;
        assertNotNull(target, method);
        var args = new ArrayList<Object>();
        for (var t : target.getParameterTypes()) {
            if (t == stateClass) args.add(state);
            else if (t == Ctx.class) args.add(ctx);
            else if (t == byte[].class) args.add(k);
            else args.add(ints.get((int) args.stream().filter(a -> a instanceof BigInteger).count()));
        }
        Method call = target;
        var ms = new MerkleSupport(store);
        Object[] out = new Object[1];
        boolean[] failed = new boolean[1];
        ms.run(() -> {
            try {
                out[0] = call.invoke(null, args.toArray());
            } catch (InvocationTargetException e) {
                failed[0] = true;
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        if (failed[0]) return "refused";
        Object r = out[0];
        if (method.equals("total")) return "ok:" + r;
        var payouts = new ArrayList<String>();
        Object next = r;
        if (r instanceof JulcList<?> l) {
            for (Object o : l) payouts.add(pay((Payout) o));
            return outcome(BigInteger.ZERO, BigInteger.ZERO, false, new byte[1], Map.of(), payouts);
        }
        if (r.getClass().getSimpleName().equals("Outcome")) {
            next = r.getClass().getMethod("state").invoke(r);
            for (Object o : (JulcList<?>) r.getClass().getMethod("payouts").invoke(r)) payouts.add(pay((Payout) o));
        }
        var comps = stateClass.getRecordComponents();
        Object nextState = next;
        var pts = new TreeMap<Integer, BigInteger>();
        if (p.map()) {
            MerkleMap mm = (MerkleMap) comps[4].getAccessor().invoke(nextState);
            new MerkleSupport(store).run(() -> {
                for (int i = 0; i < 3; i++) {
                    BigInteger v = MerkleMaps.getInt(mm, KEYS[i], BigInteger.ZERO);
                    if (v.signum() != 0) pts.put(i, v);
                }
                return null;
            });
        }
        return outcome((BigInteger) comps[0].getAccessor().invoke(nextState), (BigInteger) comps[1].getAccessor().invoke(nextState),
                (Boolean) comps[2].getAccessor().invoke(nextState), (byte[]) comps[3].getAccessor().invoke(nextState), pts, payouts);
    }

    static String pay(Payout p) {
        return "pay " + HEX.formatHex(p.to()).substring(0, 2) + " " + p.lovelace();
    }

    // v2: the reference interpreter on the migrated program.
    String v2(Prog p, String method, In in, List<BigInteger> ints, byte[] k, byte[] caller, long balance) {
        var fields = new ArrayList<Object>(List.of(in.n, in.m, in.flag, Bytes.of(in.owner)));
        if (p.map()) {
            var contents = new LinkedHashMap<Bytes, Object>();
            in.pts.forEach((i, v) -> contents.put(Bytes.of(KEYS[i]), v));
            fields.add(BigMapV.of(contents));
        }
        Rec state = new Rec("State", fields);
        Tx tx = new Tx(Bytes.of(caller), BigInteger.ZERO, BigInteger.valueOf(balance), BigInteger.ZERO, BigInteger.ZERO);
        List<Object> args = switch (method) {
            case "init" -> List.of(ints.get(0));
            case "close", "total" -> List.of();
            default -> List.of(ints.get(0), ints.get(1), Bytes.of(k));
        };
        Interpreter.Outcome o;
        try {
            o = p.v2().call(method, method.equals("init") ? null : state, args, method.equals("total") ? null : tx);
        } catch (RuntimeException e) {
            return "refused";
        }
        if (method.equals("total")) return "ok:" + o.value();
        var payouts = new ArrayList<String>();
        for (var x : o.payouts()) payouts.add("pay " + HEX.formatHex(x.to().bytes()).substring(0, 2) + " " + x.lovelace());
        if (method.equals("close")) return outcome(BigInteger.ZERO, BigInteger.ZERO, false, new byte[1], Map.of(), payouts);
        Rec next = (Rec) o.value();
        var pts = new TreeMap<Integer, BigInteger>();
        if (p.map()) {
            var now = ((BigMapV) next.fields().get(4)).now();
            for (int i = 0; i < 3; i++) {
                Object v = now.get(Bytes.of(KEYS[i]));
                if (v != null && ((BigInteger) v).signum() != 0) pts.put(i, (BigInteger) v);
            }
        }
        return outcome((BigInteger) next.fields().get(0), (BigInteger) next.fields().get(1), (Boolean) next.fields().get(2),
                ((Bytes) next.fields().get(3)).bytes(), pts, payouts);
    }

    /**
     * The v1 lowerings, compiled together (no annotation processing: only their methods run). A lowering javac refuses
     * is dropped, with its seed in {@code skipped}: v1 is frozen (ADR-0018 D16), and a contract with a Map field it never
     * writes lowers without the MerkleMap import (a v1 defect, reported in the M1d report).
     */
    static ClassLoader compile(Map<String, String> sources, java.util.Set<String> skipped) throws Exception {
        Path dir = Files.createTempDirectory("jsc-migrate-diff");
        Path src = dir.resolve("src"), out = dir.resolve("out");
        var files = new LinkedHashMap<String, String>();
        for (var e : sources.entrySet()) {
            Path f = src.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            files.put(f.toString(), e.getKey());
        }
        Files.createDirectories(out);
        for (int attempt = 0; attempt < 3; attempt++) {
            var args = new ArrayList<>(List.of("-proc:none", "-nowarn", "-d", out.toString(), "-cp", System.getProperty("java.class.path")));
            args.addAll(files.keySet());
            var err = new java.io.ByteArrayOutputStream();
            int code = ToolProvider.getSystemJavaCompiler().run(null, null, err, args.toArray(String[]::new));
            if (code == 0) break;
            var failed = new java.util.TreeSet<String>();
            var m = java.util.regex.Pattern.compile("^(.+\\.java):\\d+: error: cannot find symbol\\R.*\\R.*\\R +symbol: +class MerkleMap",
                    java.util.regex.Pattern.MULTILINE).matcher(err.toString());
            while (m.find()) failed.add(m.group(1));
            assertFalse(failed.isEmpty(), err.toString());
            for (String f : failed) skipped.add(files.remove(f));
        }
        return new URLClassLoader(new java.net.URL[]{out.toUri().toURL()}, JscMigrateDifferentialTest.class.getClassLoader());
    }

    @Test
    void theGeneratorsKeysAreDistinct() {
        assertEquals(3, new TreeSet<>(List.of(HEX.formatHex(KEYS[0]), HEX.formatHex(KEYS[1]), HEX.formatHex(KEYS[2]))).size());
    }
}
