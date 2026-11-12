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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D16, J13: {@code joust migrate} preserves behaviour, checked differentially on generated v1 programs. Each
 * program's v1 lowering runs on the JVM (compiled here); its migration runs on the {@code .jsc} 2 reference interpreter
 * (D15), written independently of both lowerings. For random states and arguments (and every argument zero, for the
 * divisions) both must refuse, or both give the same next state (scalar fields, and each map's contents on every key a
 * call can name) and the same multiset of payouts (v1 lists them in reverse source order, {@code .jsc} 2 in source
 * order).
 * <p>
 * Three generators: {@link Gen1} (one map; ifs nested two deep; reads after writes; a parameter named like a field;
 * since review r2 also same-named locals on one line in sibling arms (C1), and a value that can fail read once in a lazy
 * position, then overwritten (F1)); {@link Gen2}, the security review's independent generator (two maps, a Bytes field,
 * {@code received}/{@code balance}, {@code else if}, nesting three deep, aliasing keys, a reassignable owner); and the
 * review's repros. {@link #eachMutantOfMigrateFailsTheDifferential} keeps the differential honest: each named mutation of
 * {@code Migrate.java} must make it fail.
 */
class JscMigrateDifferentialTest {

    static final HexFormat HEX = HexFormat.of();
    static final byte[][] KEYS = {fill(0x11, 28), fill(0x22, 28), fill(0x33, 28)};
    static final byte[][] BYTES = {new byte[0], fill(0x11, 28), fill(0xab, 3), fill(0x22, 28)};
    static final String PKG = "gen.migrate";
    static final int CALLS = 40;

    static byte[] fill(int b, int n) {
        byte[] k = new byte[n];
        Arrays.fill(k, (byte) b);
        return k;
    }

    // ------------------------------------------------------------------ generator 1

    static final class Gen1 {
        final Random rnd;
        final boolean map;
        int lets;
        final List<String> scope = new ArrayList<>();
        final List<String> unused = new ArrayList<>();
        final List<String> params;

        Gen1(Random rnd, boolean map, List<String> params) {
            this.rnd = rnd;
            this.map = map;
            this.params = params;
        }

        String key() {
            return List.of("k", "caller", "owner").get(rnd.nextInt(3));
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

        String field(String f) {
            return params.contains(f) ? "this." + f : f;
        }

        void stmts(StringBuilder out, String ind, int n, int depth, boolean pays) {
            for (int i = 0; i < n; i++) stmt(out, ind, depth, pays);
        }

        void stmt(StringBuilder out, String ind, int depth, boolean pays) {
            int r = rnd.nextInt(depth > 0 ? 13 : 11);
            switch (r) {
                case 0, 1 -> out.append(ind).append(field(rnd.nextBoolean() ? "n" : "m")).append(List.of(" = ", " += ", " -= ").get(rnd.nextInt(3)))
                        .append(intExpr(2)).append('\n');
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
                case 7 -> {
                    // Review r2 F1: a value that can fail, read once where it may not be evaluated, then overwritten.
                    String f = field("n");
                    out.append(ind).append(f).append(" = ").append(intExpr(1)).append(" / ").append(params.get(rnd.nextInt(params.size()))).append('\n');
                    out.append(ind).append(switch (rnd.nextInt(3)) {
                        case 0 -> "if " + boolExpr(1) + " { " + field("m") + " = " + f + " }";
                        case 1 -> "require " + boolExpr(1) + " || " + f + " > 0 \"lazy\"";
                        default -> "if " + boolExpr(1) + " { require " + f + " != 3 \"lazy\" }";
                    }).append('\n');
                    out.append(ind).append(f).append(" = ").append(intExpr(1)).append('\n');
                }
                case 8 -> {
                    // Review r2 C1: same-named locals on one line, in sibling arms; the else arm's may be dead.
                    String x = "y" + (++lets);
                    String f = field("n");
                    out.append(ind).append("if ").append(boolExpr(1)).append(" { let ").append(x).append(" = ").append(intExpr(0)).append(" / ")
                            .append(params.get(rnd.nextInt(params.size()))).append(" ").append(f).append(" = ").append(x).append(" } else { let ")
                            .append(x).append(" = ").append(intExpr(0)).append(" / ").append(params.get(rnd.nextInt(params.size()))).append(" ")
                            .append(f).append(" = ").append(x).append(rnd.nextBoolean() ? " " + f + " = 2" : "").append(" }\n");
                }
                case 9 -> out.append(ind).append("this.n = ").append(intExpr(1)).append('\n');
                case 10 -> {
                    if (map) out.append(ind).append("pts[").append(key()).append("] += ").append(rnd.nextInt(5)).append('\n');
                    else out.append(ind).append(field("n")).append(" += ").append(intExpr(1)).append('\n');
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
                var g = new Gen1(rnd, map, params);
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
    }

    // ------------------------------------------------------------------ generator 2 (security review r1, rev72-sec-probe)

    static final class Gen2 {
        final Random r;
        int lets;
        final List<String> scope = new ArrayList<>();
        final List<String> unused = new ArrayList<>();

        Gen2(Random r) {
            this.r = r;
        }

        String key() {
            return List.of("k", "j", "caller", "owner", "this.owner").get(r.nextInt(5));
        }

        String map() {
            return r.nextBoolean() ? "a" : "b";
        }

        String ie(int d) {
            int x = r.nextInt(d <= 0 ? 8 : 16);
            return switch (x) {
                case 0 -> String.valueOf(r.nextInt(9));
                case 1 -> r.nextBoolean() ? "x" : "y";
                case 2 -> r.nextBoolean() ? "n" : "this.n";
                case 3 -> scope.isEmpty() ? "x" : use(scope.get(r.nextInt(scope.size())));
                case 4 -> map() + "[" + key() + "]";
                case 5 -> r.nextBoolean() ? "received" : "balance";
                case 6 -> "m";
                case 7 -> "-" + (r.nextBoolean() ? "x" : "n");
                case 8, 9 -> "(" + ie(d - 1) + " " + "+-*".charAt(r.nextInt(3)) + " " + ie(d - 1) + ")";
                case 10 -> "(" + ie(d - 1) + (r.nextBoolean() ? " / " : " % ") + ie(d - 1) + ")";
                case 11 -> (r.nextBoolean() ? "min(" : "max(") + ie(d - 1) + ", " + ie(d - 1) + ")";
                case 12 -> "-" + ie(d - 1);
                case 13 -> ie(d - 1) + " - " + ie(d - 1);
                default -> "(" + ie(d - 1) + " - (" + ie(d - 1) + " - " + ie(d - 1) + "))";
            };
        }

        String use(String s) {
            unused.remove(s);
            return s;
        }

        String be(int d) {
            int x = r.nextInt(d <= 0 ? 6 : 10);
            return switch (x) {
                case 0 -> "(" + ie(1) + " " + List.of("<", "<=", ">", ">=", "==", "!=").get(r.nextInt(6)) + " " + ie(1) + ")";
                case 1 -> r.nextBoolean() ? "flag" : "!flag";
                case 2 -> "has(" + map() + ", " + key() + ")";
                case 3 -> "(" + key() + " == " + key() + ")";
                case 4 -> "(tag == t)";
                case 5 -> "(tag != #00)";
                case 6 -> "!" + be(d - 1);
                default -> "(" + be(d - 1) + (r.nextBoolean() ? " && " : " || ") + be(d - 1) + ")";
            };
        }

        void stmts(StringBuilder o, String ind, int n, int d, boolean pays) {
            for (int i = 0; i < n; i++) stmt(o, ind, d, pays);
        }

        void stmt(StringBuilder o, String ind, int d, boolean pays) {
            int x = r.nextInt(d > 0 ? 14 : 11);
            switch (x) {
                case 0, 1 -> o.append(ind).append(r.nextBoolean() ? "n" : "m").append(List.of(" = ", " += ", " -= ").get(r.nextInt(3))).append(ie(2)).append('\n');
                case 2 -> o.append(ind).append("flag = ").append(be(1)).append('\n');
                case 3 -> o.append(ind).append(r.nextBoolean() ? "owner = " + (r.nextBoolean() ? "k" : "j") : "tag = " + (r.nextBoolean() ? "t" : "#0a0b")).append('\n');
                case 4 -> o.append(ind).append("delete ").append(map()).append("[").append(key()).append("]\n");
                case 5, 6 -> o.append(ind).append(map()).append("[").append(key()).append("]").append(List.of(" = ", " += ", " -= ").get(r.nextInt(3))).append(ie(1)).append('\n');
                case 7 -> {
                    String v = "v" + (++lets);
                    o.append(ind).append("let ").append(v).append(" = ").append(ie(2)).append('\n');
                    scope.add(v);
                    unused.add(v);
                }
                case 8 -> o.append(ind).append("require ").append(be(2)).append(" \"c").append(r.nextInt(99)).append("\"\n");
                case 9 -> {
                    if (pays) o.append(ind).append("pay ").append(ie(1)).append(" to ").append(key()).append('\n');
                    else o.append(ind).append("m += 1\n");
                }
                case 10 -> o.append(ind).append("this.n = ").append(ie(1)).append('\n');
                default -> {
                    o.append(ind).append("if ").append(be(2)).append(" {\n");
                    int mark = scope.size();
                    stmts(o, ind + "    ", 1 + r.nextInt(3), d - 1, pays);
                    useAll(o, ind + "    ", mark);
                    o.append(ind).append("}");
                    int e = r.nextInt(3);
                    if (e == 1) {
                        o.append(" else {\n");
                        stmts(o, ind + "    ", 1 + r.nextInt(3), d - 1, pays);
                        useAll(o, ind + "    ", mark);
                        o.append(ind).append("}");
                    } else if (e == 2) {
                        o.append(" else if ").append(be(1)).append(" {\n");
                        stmts(o, ind + "    ", 1 + r.nextInt(2), d - 1, pays);
                        useAll(o, ind + "    ", mark);
                        o.append(ind).append("} else {\n");
                        stmts(o, ind + "    ", 1 + r.nextInt(2), d - 1, pays);
                        useAll(o, ind + "    ", mark);
                        o.append(ind).append("}");
                    }
                    o.append('\n');
                }
            }
        }

        void useAll(StringBuilder o, String ind, int mark) {
            while (scope.size() > mark) {
                String v = scope.remove(scope.size() - 1);
                if (unused.remove(v)) o.append(ind).append("require ").append(v).append(" != ").append(v).append(" + 1 \"use\"\n");
            }
        }

        static String program(int seed) {
            var r = new Random(seed * 7919L + 13);
            var src = new StringBuilder("package " + PKG + "\n\ncontract H" + seed + " {\n    state n: Int\n    state m: Int\n    state flag: Bool\n"
                    + "    state owner: Key\n    state tag: Bytes\n    state a: Map<Key, Int>\n    state b: Map<Key, Int>\n\n    init(x: Int) {\n"
                    + "        owner = caller\n        tag = #00\n        n = x\n    }\n");
            for (int i = 0; i < 3; i++) {
                boolean pays = r.nextInt(2) == 0;
                var g = new Gen2(r);
                var body = new StringBuilder();
                g.stmts(body, "        ", 2 + r.nextInt(4), 3, pays);
                g.useAll(body, "        ", 0);
                body.append("        a[k] += 0\n        b[j] += 0\n");
                src.append("\n    action act").append(i).append("(x: Int, y: Int, k: Key, j: Key, t: Bytes)").append(pays ? " open" : "")
                        .append(" payable {\n").append(body).append("    }\n");
            }
            src.append("\n    destroy close() only owner {\n        pay balance to owner\n    }\n}\n");
            return src.toString();
        }
    }

    /** The review's repros (r2 C1, F1), as programs every run includes. */
    static final List<String> REPROS = List.of("""
            package gen.migrate
            contract R1 {
              state n: Int
              init() {}
              action act(d: Int, flag: Bool) { if flag { let x = 1 / d n = x } else { let x = 1 / d n = x n = 2 } }
            }
            """, """
            package gen.migrate
            contract R2 {
              state n: Int
              state m: Int
              init() {}
              action a(x: Int, c: Bool) { n = 10 / x
                if c { m = n }
                n = 1 }
            }
            """, """
            package gen.migrate
            contract R3 {
              state n: Int
              state m: Int
              init() {}
              action a(x: Int, c: Bool) { n = 10 / x
                require c || n > 0 "lazy"
                n = 1 }
            }
            """);

    // ------------------------------------------------------------------ the programs, their calls and v1's answers

    record F(String name, String type) {}

    record Call(String method, List<Object> vals, List<Object> args, byte[] caller, BigInteger received, BigInteger balance) {}

    record Prog(String name, String v1, List<F> fields, List<Call> calls, List<String> v1Results) {}

    static final List<Prog> PROGS = new ArrayList<>();
    static int invalid, javacRefused, gen1, gen2;
    static final InMemoryNodeStore STORE = new InMemoryNodeStore();

    @BeforeAll
    static void programs() throws Exception {
        var sources = new LinkedHashMap<String, String>();   // name -> v1 text
        for (int seed = 1; gen1 < 160; seed++) {
            if (add(sources, "G" + seed, Gen1.program(seed))) gen1++;
        }
        for (int seed = 1; gen2 < 100; seed++) {
            if (add(sources, "H" + seed, Gen2.program(seed))) gen2++;
        }
        for (int i = 0; i < REPROS.size(); i++) assertTrue(add(sources, "R" + (i + 1), REPROS.get(i)), "repro R" + (i + 1));
        var java = new LinkedHashMap<String, String>();
        sources.forEach((n, v1) -> java.put(PKG.replace('.', '/') + "/" + n + ".java", JscMain.lower(v1, n + ".jsc")));
        var skipped = new java.util.TreeSet<String>();
        ClassLoader loader = compile(java, skipped);
        javacRefused = skipped.size();
        for (var e : sources.entrySet()) {
            if (skipped.contains(PKG.replace('.', '/') + "/" + e.getKey() + ".java")) continue;
            PROGS.add(prepare(e.getKey(), e.getValue(), loader.loadClass(PKG + "." + e.getKey())));
        }
        System.out.println("[migrate differential] " + PROGS.size() + " programs (" + gen1 + " from Gen1, " + gen2 + " from Gen2, "
                + REPROS.size() + " repros); " + invalid + " invalid v1 skipped, " + javacRefused + " lowerings javac refuses (a Map never written)");
    }

    /** Adds a valid v1 program. */
    static boolean add(Map<String, String> sources, String name, String v1) {
        try {
            JscMain.lower(v1, name + ".jsc");
        } catch (JscException e) {
            invalid++;
            return false;
        }
        sources.put(name, v1);
        return true;
    }

    static Prog prepare(String name, String v1, Class<?> cls) throws Exception {
        var fields = new ArrayList<F>();
        var fm = Pattern.compile("state\\s+(\\w+)\\s*:\\s*(Int|Bool|Bytes|Key|Map<Key, Int>)").matcher(v1);
        while (fm.find()) fields.add(new F(fm.group(1), fm.group(2)));
        Class<?> stateClass = Class.forName(cls.getName() + "$State", true, cls.getClassLoader());
        var methods = new ArrayList<Method>();
        for (Method m : cls.getDeclaredMethods()) if (Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())) methods.add(m);
        methods.sort(java.util.Comparator.comparing(Method::getName));
        var rnd = new Random(name.hashCode() * 31L);
        var calls = new ArrayList<Call>();
        var results = new ArrayList<String>();
        for (int i = 0; i < CALLS; i++) {
            Method m = methods.get(rnd.nextInt(methods.size()));
            // The first calls of each method have every Int argument zero (the divisions), with either Bool.
            boolean zero = i < 2 * methods.size();
            if (zero) m = methods.get(i % methods.size());
            var vals = new ArrayList<Object>();
            for (F f : fields) vals.add(randomField(rnd, f.type()));
            var args = new ArrayList<Object>();
            for (var p : m.getParameters()) {
                Class<?> t = p.getType();
                if (t == stateClass || t == Ctx.class) continue;
                if (t == BigInteger.class) args.add(zero ? BigInteger.ZERO : BigInteger.valueOf(rnd.nextInt(14) - 3));
                else if (t == boolean.class) args.add(zero ? i >= methods.size() : rnd.nextBoolean());
                else if (t == byte[].class) args.add(rnd.nextInt(5) == 0 ? BYTES[rnd.nextInt(BYTES.length)] : KEYS[rnd.nextInt(3)]);
                else throw new IllegalStateException(t.toString());
            }
            var call = new Call(m.getName(), vals, args, KEYS[rnd.nextInt(3)], BigInteger.valueOf(rnd.nextInt(4) * 1_000_000L),
                    BigInteger.valueOf(rnd.nextInt(6) * 1_000_000L));
            calls.add(call);
            results.add(v1(m, stateClass, fields, call));
        }
        return new Prog(name, v1, fields, calls, results);
    }

    static Object randomField(Random rnd, String type) {
        return switch (type) {
            case "Int" -> BigInteger.valueOf(rnd.nextInt(18) - 4);
            case "Bool" -> rnd.nextBoolean();
            case "Key" -> KEYS[rnd.nextInt(3)];
            case "Bytes" -> BYTES[rnd.nextInt(BYTES.length)];
            default -> {
                var m = new TreeMap<Integer, BigInteger>();
                for (int i = 0; i < 3; i++) if (rnd.nextBoolean()) m.put(i, BigInteger.valueOf(1 + rnd.nextInt(9)));
                yield m;
            }
        };
    }

    static MerkleMap root(Map<Integer, BigInteger> pts) {
        if (pts.isEmpty()) return MerkleMaps.empty();
        var sorted = new TreeMap<String, byte[]>();
        pts.forEach((i, v) -> sorted.put(HEX.formatHex(KEYS[i]), Builtins.integerToByteString(true, 0, v)));
        JulcList<MapEntry> net = JulcList.empty();
        for (var e : sorted.descendingMap().entrySet()) net = net.prepend(new MapEntry(HEX.parseHex(e.getKey()), true, e.getValue()));
        return new MerkleMap(new MerkleSupport.TrieProver(STORE).write(Mpf.emptyRoot(), net).root(), JulcList.empty(), JulcList.empty());
    }

    static String show(Object v) {
        if (v instanceof byte[] b) return HEX.formatHex(b);
        if (v instanceof Bytes b) return HEX.formatHex(b.bytes());
        return String.valueOf(v);
    }

    static String pay(byte[] to, BigInteger lovelace) {
        return HEX.formatHex(to).substring(0, 2) + ":" + lovelace;
    }

    /** v1: the lowering's Java on the JVM, map reads served from a node store. */
    @SuppressWarnings("unchecked")
    static String v1(Method m, Class<?> stateClass, List<F> fields, Call c) throws Exception {
        var args = new ArrayList<Object>();
        var plain = new java.util.ArrayDeque<>(c.args());
        for (var p : m.getParameters()) {
            if (p.getType() == stateClass) {
                var st = new Object[fields.size()];
                for (int i = 0; i < st.length; i++) {
                    st[i] = fields.get(i).type().startsWith("Map") ? root((Map<Integer, BigInteger>) c.vals().get(i)) : c.vals().get(i);
                }
                args.add(stateClass.getDeclaredConstructors()[0].newInstance(st));
            } else if (p.getType() == Ctx.class) {
                args.add(new Ctx(c.caller(), c.received(), c.balance(), BigInteger.ZERO, BigInteger.ZERO));
            } else {
                args.add(plain.poll());
            }
        }
        Object[] out = new Object[1];
        boolean[] failed = new boolean[1];
        new MerkleSupport(STORE).run(() -> {
            try {
                out[0] = m.invoke(null, args.toArray());
            } catch (InvocationTargetException e) {
                failed[0] = true;
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        if (failed[0]) return "refused";
        Object r = out[0];
        String rt = m.getReturnType().getSimpleName();
        if (!rt.equals("State") && !rt.equals("Outcome") && !(r instanceof JulcList<?>)) return "view " + show(r);
        var payouts = new ArrayList<String>();
        if (r instanceof JulcList<?> l) {
            for (Object o : l) payouts.add(pay(((Payout) o).to(), ((Payout) o).lovelace()));
            Collections.sort(payouts);
            return "destroy pay=" + payouts;
        }
        Object next = r;
        if (rt.equals("Outcome")) {
            next = r.getClass().getMethod("state").invoke(r);
            for (Object o : (JulcList<?>) r.getClass().getMethod("payouts").invoke(r)) payouts.add(pay(((Payout) o).to(), ((Payout) o).lovelace()));
        }
        Collections.sort(payouts);
        var comps = stateClass.getRecordComponents();
        var sb = new StringBuilder("ok ");
        for (int i = 0; i < comps.length; i++) {
            Object v = comps[i].getAccessor().invoke(next);
            if (v instanceof MerkleMap mm) {
                var pts = new TreeMap<Integer, BigInteger>();
                new MerkleSupport(STORE).run(() -> {
                    for (int k = 0; k < 3; k++) {
                        BigInteger x = MerkleMaps.getInt(mm, KEYS[k], BigInteger.ZERO);
                        if (x.signum() != 0) pts.put(k, x);
                    }
                    return null;
                });
                v = pts;
            }
            sb.append(fields.get(i).name()).append('=').append(show(v)).append(' ');
        }
        return sb + "pay=" + payouts;
    }

    /** v2: the reference interpreter on the migrated program. */
    @SuppressWarnings("unchecked")
    static String v2(Interpreter in, Prog p, Call c, String v1Result) {
        boolean view = v1Result.startsWith("view") || c.method().equals("total");
        boolean init = c.method().equals("init");
        var st = new ArrayList<Object>();
        for (int i = 0; i < p.fields().size(); i++) {
            Object v = c.vals().get(i);
            if (p.fields().get(i).type().startsWith("Map")) {
                var m = new LinkedHashMap<Bytes, Object>();
                ((Map<Integer, BigInteger>) v).forEach((k, x) -> m.put(Bytes.of(KEYS[k]), x));
                st.add(BigMapV.of(m));
            } else {
                st.add(v instanceof byte[] b ? Bytes.of(b) : v);
            }
        }
        var args = new ArrayList<Object>();
        for (Object a : c.args()) args.add(a instanceof byte[] b ? Bytes.of(b) : a);
        Interpreter.Outcome o;
        try {
            o = in.call(c.method(), init ? null : new Rec("State", st), args,
                    view ? null : new Tx(Bytes.of(c.caller()), c.received(), c.balance(), BigInteger.ZERO, BigInteger.ZERO));
        } catch (RuntimeException e) {
            return "refused";
        }
        if (view) return "view " + show(o.value());
        var payouts = new ArrayList<String>();
        for (var x : o.payouts()) payouts.add(pay(x.to().bytes(), x.lovelace()));
        Collections.sort(payouts);
        if (o.value() == null) return "destroy pay=" + payouts;
        Rec next = (Rec) o.value();
        var sb = new StringBuilder("ok ");
        for (int i = 0; i < p.fields().size(); i++) {
            Object v = next.fields().get(i);
            if (v instanceof BigMapV bm) {
                var pts = new TreeMap<Integer, BigInteger>();
                for (int k = 0; k < 3; k++) {
                    Object x = bm.now().get(Bytes.of(KEYS[k]));
                    if (x != null && ((BigInteger) x).signum() != 0) pts.put(k, (BigInteger) x);
                }
                v = pts;
            }
            sb.append(p.fields().get(i).name()).append('=').append(show(v)).append(' ');
        }
        return sb + "pay=" + payouts;
    }

    // ------------------------------------------------------------------ the differential

    /** A migration: the .jsc 2 text, or the refusal's code. */
    record Migrated(String source, String code) {}

    record Summary(int programs, int migrated, int calls, int refusedByBoth, int paid, Map<String, Integer> refusals, List<String> disagreements) {
        int unexpectedRefusals() {
            return refusals.entrySet().stream().filter(e -> !e.getKey().equals("JSC1105") && !e.getKey().equals("JSC1106"))
                    .mapToInt(Map.Entry::getValue).sum();
        }
    }

    static Summary differential(BiFunction<String, String, Migrated> migrate) {
        var refusals = new TreeMap<String, Integer>();
        var disagreements = new ArrayList<String>();
        int migrated = 0, calls = 0, both = 0, paid = 0;
        for (Prog p : PROGS) {
            Migrated m = migrate.apply(p.v1(), p.name() + ".jsc");
            if (m.source() == null) {
                refusals.merge(m.code(), 1, Integer::sum);
                continue;
            }
            Interpreter in;
            try {
                in = JscV2.interpreter(m.source(), p.name() + ".jsc", Interpreter.JDK_HASHES);
            } catch (RuntimeException e) {
                refusals.merge("unchecked " + e.getClass().getSimpleName(), 1, Integer::sum);
                continue;
            }
            migrated++;
            for (int i = 0; i < p.calls().size(); i++) {
                Call c = p.calls().get(i);
                String a = p.v1Results().get(i), b = v2(in, p, c, a);
                calls++;
                if (!a.equals(b)) {
                    if (disagreements.size() < 5) {
                        disagreements.add(p.name() + "." + c.method() + " args " + c.args().stream().map(JscMigrateDifferentialTest::show).toList()
                                + "\n  v1: " + a + "\n  v2: " + b + "\n--- v1\n" + p.v1() + "--- migrated\n" + m.source());
                    } else {
                        disagreements.add(p.name());
                    }
                } else if (a.equals("refused")) {
                    both++;
                } else if (a.startsWith("ok") && !a.endsWith("pay=[]")) {
                    paid++;
                }
            }
        }
        return new Summary(PROGS.size(), migrated, calls, both, paid, refusals, disagreements);
    }

    static Migrated real(String v1, String name) {
        try {
            return new Migrated(Migrate.migrate(v1, name).source(), null);
        } catch (JscException e) {
            return new Migrated(null, e.code);
        }
    }

    @Test
    void migratedProgramsBehaveAsTheirV1Originals() {
        Summary s = differential(JscMigrateDifferentialTest::real);
        System.out.println("[migrate differential] " + s.migrated() + " of " + s.programs() + " programs migrated (refusals " + s.refusals() + "); "
                + s.calls() + " calls, " + s.refusedByBoth() + " refused by both, " + s.paid() + " actions with payouts: v1 on the JVM and the"
                + " migration on the reference interpreter agree");
        assertTrue(s.disagreements().isEmpty(), s.disagreements().size() + " disagreements:\n" + String.join("\n", s.disagreements().subList(0,
                Math.min(5, s.disagreements().size()))));
        // Only refusals that state a real limit (a key computed in an arm; a dead value that can fail).
        assertEquals(0, s.unexpectedRefusals(), s.refusals().toString());
        // Most refusals are JSC1106: the generators write values that can fail and then overwrite them unread on purpose.
        assertTrue(s.migrated() > s.programs() / 2, "migrated " + s.migrated() + " of " + s.programs());
        assertTrue(s.refusedByBoth() > s.calls() / 20 && s.refusedByBoth() < s.calls() * 3 / 4, "refusals " + s.refusedByBoth());
        assertTrue(s.paid() > 100, "payouts " + s.paid());
        // The repros: R1 (C1) is refused, as its dead division would be lost; R2 and R3 (F1) migrate and agree.
        assertEquals("JSC1106", real(REPROS.get(0), "R1.jsc").code());
        assertNull(real(REPROS.get(1), "R2.jsc").code());
        assertNull(real(REPROS.get(2), "R3.jsc").code());
    }

    // ------------------------------------------------------------------ mutation checks (review r2 F1)

    /** A named mutation of {@code Migrate.java}: its one occurrence of {@code find} replaced. */
    record Mutant(String name, String find, String replace) {}

    static final List<Mutant> MUTANTS = List.of(
            new Mutant("F1: a value that can fail inlined into a lazy position",
                    "if (uses != 1 || !totalVal(k.value()) && !strict) continue;", "if (uses != 1) continue;"),
            new Mutant("C1: consts identified by name and line",
                    "if (it instanceof Const c2 && c2.id() == k.id()) return true;",
                    "if (it instanceof Const c2 && c2.name().equals(k.name()) && c2.line() == k.line()) return true;"),
            new Mutant("a merge's arms swapped",
                    "var v = new IfVal(cond, values(thenItems, a), a, values(elseItems, b), b);",
                    "var v = new IfVal(cond, values(elseItems, b), b, values(thenItems, a), a);"),
            new Mutant("a read through a write ignores the write",
                    "r = w[0].equals(k.text()) ? v : new Text(", "r = w[0].equals(k.text()) ? r : new Text("),
            new Mutant("has() after a write is always true",
                    "Text present = new Text(w[1] + \" != 0\", EQ, true);", "Text present = new Text(\"true\", PRIMARY, true);"),
            new Mutant("delete is a no-op",
                    "env.maps.put(d.map(), ch.with(k.text(), \"0\"));", "env.maps.put(d.map(), ch);"),
            new Mutant("no parentheses on an equal-precedence right operand",
                    "String rt = r.prec() <= p ?", "String rt = r.prec() < p ?"),
            new Mutant("the arm merge reads the base map, not the chain so far",
                    "Text current = read(ch, new Text(w[0], PRIMARY, true));", "Text current = read(base, new Text(w[0], PRIMARY, true));"));

    @Test
    void eachMutantOfMigrateFailsTheDifferential() throws Exception {
        String source = Files.readString(Path.of("../julc-state-dsl/src/main/java/org/julclang/state/dsl/Migrate.java"));
        URL dsl = Migrate.class.getProtectionDomain().getCodeSource().getLocation();
        var survived = new ArrayList<String>();
        for (Mutant mu : MUTANTS) {
            int at = source.indexOf(mu.find());
            assertTrue(at >= 0 && source.indexOf(mu.find(), at + 1) < 0, mu.name() + ": its line must occur once in Migrate.java: " + mu.find());
            String patched = source.replace(mu.find(), mu.replace());
            Method migrate = compileMutant(patched, dsl);
            Summary s = differential((v1, name) -> {
                try {
                    Object r = migrate.invoke(null, v1, name);
                    return new Migrated((String) r.getClass().getMethod("source").invoke(r), null);
                } catch (InvocationTargetException e) {
                    try {
                        return new Migrated(null, (String) e.getCause().getClass().getField("code").get(e.getCause()));
                    } catch (ReflectiveOperationException x) {
                        return new Migrated(null, "crash " + e.getCause());
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
            boolean caught = !s.disagreements().isEmpty() || s.unexpectedRefusals() > 0;
            System.out.println("[migrate mutant] " + mu.name() + ": " + (caught ? "caught (" + s.disagreements().size() + " disagreements, refusals "
                    + s.refusals() + ")" : "SURVIVED"));
            if (!caught) survived.add(mu.name());
        }
        assertTrue(survived.isEmpty(), "mutants the differential does not catch: " + survived);
    }

    /** {@code Migrate.java} as patched, compiled against the dsl module, in a loader of its own; its {@code migrate}. */
    static Method compileMutant(String source, URL dsl) throws Exception {
        Path dir = Files.createTempDirectory("jsc-migrate-mutant");
        Path f = dir.resolve("src/org/julclang/state/dsl/Migrate.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, source);
        Path out = Files.createDirectories(dir.resolve("out"));
        var err = new java.io.ByteArrayOutputStream();
        int code = ToolProvider.getSystemJavaCompiler().run(null, null, err, "-proc:none", "-nowarn", "-d", out.toString(), "-cp",
                Path.of(dsl.toURI()).toString(), f.toString());
        assertEquals(0, code, err.toString());
        // The mutant first, then the dsl module; no parent but the platform's, so the mutant replaces Migrate in its package.
        var loader = new URLClassLoader(new URL[]{out.toUri().toURL(), dsl}, ClassLoader.getPlatformClassLoader());
        return loader.loadClass("org.julclang.state.dsl.Migrate").getMethod("migrate", String.class, String.class);
    }

    /**
     * The v1 lowerings, compiled together (no annotation processing: only their methods run). A lowering javac refuses
     * is dropped, with its path in {@code skipped}: v1 is frozen (ADR-0018 D16), and a contract with a Map field it never
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
            var m = Pattern.compile("^(.+\\.java):\\d+: error: cannot find symbol\\R.*\\R.*\\R +symbol: +class MerkleMap", Pattern.MULTILINE).matcher(err.toString());
            while (m.find()) failed.add(m.group(1));
            assertFalse(failed.isEmpty(), err.toString());
            for (String f : failed) skipped.add(files.remove(f));
        }
        return new URLClassLoader(new URL[]{out.toUri().toURL()}, JscMigrateDifferentialTest.class.getClassLoader());
    }
}
