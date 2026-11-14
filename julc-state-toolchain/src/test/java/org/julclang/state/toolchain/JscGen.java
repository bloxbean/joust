package org.julclang.state.toolchain;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeSet;

/**
 * A generator of well-typed {@code .jsc} 2 contracts and call sequences, decoded from a choice source (Jazzer's bytes, or
 * a seeded random for the generator's own test): the five-way fuzz test's programs ({@link FiveWayFuzzTest}). Every
 * program it emits must build ({@code Toolchain.build}) and compile with javac and the processors, as JuLC's ADR-063 H6
 * requires of its generators: a program that does not is a generator bug and fails the run ({@code JscGenTest}).
 * <p>
 * The grammar ({@link #VERSION}) covers, by feature:
 * <ul>
 *   <li><b>M1</b>: a state of Key, Int, Bytes, Bool, List&lt;Int&gt;, Option&lt;Int&gt;, a record and an enum; init, actions,
 *   views and a destroy; {@code only owner} and {@code open}; {@code const}, {@code require}, statement {@code if}/else,
 *   {@code for} over a list, {@code pay}; integer arithmetic with {@code /} and {@code %} (refusals at zero), {@code min},
 *   {@code max}, {@code abs}, ternaries and value {@code if}s, comparisons, Boolean operators, equality at every type;
 *   lists ({@code length}, {@code sum}, {@code fold}, {@code map}, {@code filter}, {@code any}, {@code contains},
 *   {@code take}, {@code drop}, {@code reverse}, {@code head}, {@code find}, spreads); options ({@code ??}, {@code Some},
 *   {@code None}, {@code match}); records and their spreads; enums and {@code match}; {@code std/math} (pow, div, mod,
 *   sign), {@code std/bytes} (length, concat, slice, take, drop, toInt), {@code std/crypto} (blake2b_224);
 *   {@code after}/{@code before} at the top of a require; top-level fns, one recursive.</li>
 *   <li><b>M2</b>: {@code payable} and {@code received}; {@code Value.lovelace}, value addition, {@code .lovelace},
 *   {@code covers} and {@code std/value}'s {@code isEmpty}; M2b's {@code params}, {@code invariant}, {@code event} and
 *   {@code emit}.</li>
 *   <li><b>M4</b>: a {@code .jsc} library in its own file holding the record, the enum and the fns (one with a
 *   {@code require} of its own), imported by the contract.</li>
 * </ul>
 * Values are bounded where the chain bounds them and the interpreters do not (so a mismatch is the legs', not a datum
 * size or a budget): every Int stored in the state is reduced modulo 1,000,003, stored bytes and lists are truncated,
 * payouts are 2 to 5 ADA from a 100 ADA balance, recursion is at most 12 deep. Intermediate values are not bounded:
 * arguments reach 2^200 and the 128-bit edges.
 */
final class JscGen {
    private JscGen() {}

    /** The grammar's version, saved with every failure. */
    static final String VERSION = "JscGen r2";
    static final String PKG = "org.julclang.state.fuzz";
    static final String DIR = "org/julclang/state/fuzz/";
    static final BigInteger T0 = BigInteger.valueOf(1_800_000_000_000L);
    static final BigInteger WINDOW = BigInteger.valueOf(120_000);
    static final byte[] OWNER = fill(28, 0x0A), STRANGER = fill(28, 0x5A), THIRD = fill(28, 0x3C);

    static byte[] fill(int n, int b) {
        byte[] x = new byte[n];
        Arrays.fill(x, (byte) b);
        return x;
    }

    // ------------------------------------------------------------------ choices

    /** Where the generator's choices come from. Exhausted, it answers the smallest choice, so a program always ends. */
    interface Src {
        int pick(int lo, int hi);

        long anyLong();

        /** True one time in two; false once the choices are exhausted. */
        default boolean coin() {
            return pick(0, 1) == 1;
        }

        /** True one time in {@code oneIn}; false once the choices are exhausted (so an exhausted input is a plain program). */
        default boolean chance(int oneIn) {
            return pick(0, oneIn - 1) == oneIn - 1;
        }
    }

    static Src of(FuzzedDataProvider d) {
        return new Src() {
            public int pick(int lo, int hi) {
                return d.consumeInt(lo, hi);
            }

            public long anyLong() {
                return d.consumeLong();
            }
        };
    }

    static Src of(SplittableRandom r) {
        return new Src() {
            public int pick(int lo, int hi) {
                return r.nextInt(lo, hi + 1);
            }

            public long anyLong() {
                return r.nextLong();
            }
        };
    }

    // ------------------------------------------------------------------ types and scopes

    enum T { INT, BOOL, BYTES, KEY, LIST, OPT, TIER, PT, MAP }

    record Var(String name, T type) {}

    /** What an expression may read: its variables, and whether it is in a method (state, caller), a payable one, a fn. */
    record Scope(List<Var> vars, boolean method, boolean payable, boolean tx, boolean state) {
        Scope(List<Var> vars, boolean method, boolean payable, boolean tx) {
            this(vars, method, payable, tx, method);
        }

        Scope with(Var v) {
            var vs = new ArrayList<>(vars);
            vs.add(v);
            return new Scope(vs, method, payable, tx, state);
        }

        List<Var> of(T t) {
            return vars.stream().filter(v -> v.type() == t).toList();
        }
    }

    /** A call of the sequence: on the chained state (the last accepted one), or on a state of its own. */
    /**
     * A call of the sequence: on the chained state (the last accepted one), or on a state of its own. {@code cosigners} sign
     * the transaction beside the caller (who always signs, semantics §2); {@code mutants}: the accepted call's transaction is
     * also checked with every next state, payout and event one step away, and the attack suite ({@link FourWay#mutants}:
     * the validator must refuse each); {@code candidate}: for an init, the first state a creation proposes when the
     * interpreters refuse it (the validator must refuse that creation too, review r1 Codex C2).
     */
    record Step(String method, Rec state, List<Object> args, byte[] caller, long received, List<byte[]> cosigners, boolean mutants,
                Rec candidate, BigInteger[] window, org.julclang.state.dsl.v2.Interpreter.ValueV offered) {
        Step(String method, Rec state, List<Object> args, byte[] caller, long received) {
            this(method, state, args, caller, received, List.of(), false, null, null, null);
        }
    }

    /** A generated program: its files, the contract's name and file, the params (or null) and the calls. */
    record Plan(String name, Map<String, String> files, String contractFile, Rec params, List<Step> steps, List<String> features,
                Map<String, String> kinds) {
        String source() {
            return files.get(contractFile);
        }

        /** The program and its calls, as a failure record shows them. */
        String describe() {
            var sb = new StringBuilder("generator: " + VERSION + "\nfeatures: " + features + "\n");
            files.forEach((f, s) -> sb.append("---- ").append(f).append('\n').append(s));
            sb.append("---- params: ").append(params).append('\n');
            for (Step s : steps) {
                sb.append("call ").append(s.method()).append(' ').append(s.args()).append(" caller ").append(HexFormat.of().formatHex(s.caller()))
                        .append(" received ").append(s.received()).append(s.state() == null ? " on the chained state" : " on " + s.state()).append('\n');
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ the generator

    final static class G {
        final Src s;
        final StringBuilder lib = new StringBuilder(), top = new StringBuilder(), body = new StringBuilder();
        final TreeSet<String> math = new TreeSet<>(), bytes = new TreeSet<>(), crypto = new TreeSet<>(), value = new TreeSet<>();
        final List<String> features = new ArrayList<>();
        boolean library, params, invariant, events, values, time, tier, pt, maps, assets;
        /** The library's (or the file's) fns: name, parameter types, and whether it is the recursive one. */
        final List<String[]> fns = new ArrayList<>();
        int consts, msgs, binders;

        /** A fresh name for a lambda's, a match arm's or a loop's binding (a name is bound once in a method). */
        String fresh(String base) {
            return base + (binders++);
        }
        final Map<String, String> kinds = new LinkedHashMap<>();
        final Map<String, List<T>> methodParams = new LinkedHashMap<>();
        final Map<String, Boolean> payableMethods = new LinkedHashMap<>();
        /** The method that accepts native assets (M2a), or null. */
        String assetMethod;

        G(Src s) {
            this.s = s;
        }

        String msg() {
            return "m" + (msgs++);
        }

        // ---- literals

        static final String[] INT_LITS = {"0", "1", "-1", "2", "3", "7", "10", "255", "256", "1_000_000", "-1_000_000",
                "9_223_372_036_854_775_807", "-9_223_372_036_854_775_808", "18_446_744_073_709_551_616",
                "170_141_183_460_469_231_731_687_303_715_884_105_727", "-170_141_183_460_469_231_731_687_303_715_884_105_728"};

        String intLit() {
            return INT_LITS[s.pick(0, INT_LITS.length - 1)];
        }

        String bytesLit() {
            return switch (s.pick(0, 4)) {
                case 0 -> "#";
                case 1 -> "#00";
                case 2 -> "#00ff";
                case 3 -> "#" + "a1".repeat(28);
                default -> "#" + "7f".repeat(s.pick(1, 33));
            };
        }

        // ---- expressions

        String var(Scope sc, T t) {
            var vs = sc.of(t);
            return vs.isEmpty() ? null : vs.get(s.pick(0, vs.size() - 1)).name();
        }

        String integer(Scope sc, int d) {
            if (d <= 0 || s.chance(3)) {
                String v = var(sc, T.INT);
                if (v != null && s.coin()) return v;
                if (params && sc.method() && s.chance(6)) return "params.cap";
                if (sc.payable() && s.chance(4)) return "received";
                return intLit();
            }
            int k = s.pick(0, 21);
            return switch (k) {
                case 0 -> "(" + integer(sc, d - 1) + " + " + integer(sc, d - 1) + ")";
                case 1 -> "(" + integer(sc, d - 1) + " - " + integer(sc, d - 1) + ")";
                case 2 -> "(" + integer(sc, d - 1) + " * " + integer(sc, d - 1) + ")";
                case 3 -> "(" + integer(sc, d - 1) + " / " + integer(sc, d - 1) + ")";
                case 4 -> "(" + integer(sc, d - 1) + " % " + integer(sc, d - 1) + ")";
                case 5 -> "-(" + integer(sc, d - 1) + ")";
                case 6 -> new String[]{"min", "max"}[s.pick(0, 1)] + "(" + integer(sc, d - 1) + ", " + integer(sc, d - 1) + ")";
                case 7 -> "abs(" + integer(sc, d - 1) + ")";
                case 8 -> "(" + bool(sc, d - 1) + " ? " + integer(sc, d - 1) + " : " + integer(sc, d - 1) + ")";
                case 9 -> "if " + bool(sc, d - 1) + " { " + integer(sc, d - 1) + " } else { " + integer(sc, d - 1) + " }";
                case 10 -> list(sc, d - 1) + ".length";
                case 11 -> list(sc, d - 1) + ".sum()";
                case 12 -> {
                    String acc = fresh("acc"), x = fresh("x");
                    var in = sc.with(new Var(acc, T.INT)).with(new Var(x, T.INT));
                    yield list(sc, d - 1) + ".fold(" + integer(sc, d - 1) + ", (" + acc + ", " + x + ") => (" + acc + " + " + x + " * " + integer(in, d - 1) + "))";
                }
                case 13 -> "(" + opt(sc, d - 1) + " ?? " + integer(sc, d - 1) + ")";
                case 14 -> {
                    bytes.add("length");
                    yield "length(" + bytesExpr(sc, d - 1) + ")";
                }
                case 15 -> {
                    String f = new String[]{"div", "mod", "pow", "sign"}[s.pick(0, 3)];
                    math.add(f);
                    yield switch (f) {
                        case "pow" -> "pow(" + integer(sc, d - 1) + ", abs(" + integer(sc, d - 1) + ") % 9)";
                        case "sign" -> "sign(" + integer(sc, d - 1) + ")";
                        default -> f + "(" + integer(sc, d - 1) + ", " + integer(sc, d - 1) + ")";
                    };
                }
                case 16 -> {
                    bytes.add("toInt");
                    bytes.add("take");
                    yield "toInt(take(" + bytesExpr(sc, d - 1) + ", 8))";
                }
                case 17 -> {
                    if (!tier) yield integer(sc, d - 1);
                    String bk = fresh("k");
                    yield "match " + tierExpr(sc, d - 1) + " { Lo => (" + integer(sc, d - 1) + "), Hi { k: " + bk + " } => (" + bk + " + "
                            + integer(sc.with(new Var(bk, T.INT)), d - 1) + ") }";
                }
                case 18 -> {
                    if (var(sc, T.MAP) != null && s.coin()) {
                        yield s.coin() ? "(" + mapExpr(sc, d - 1) + ".get(" + bytesExpr(sc, d - 1) + ") ?? " + integer(sc, d - 1) + ")"
                                : mapExpr(sc, d - 1) + ".size";
                    }
                    yield pt ? ptExpr(sc, d - 1) + "." + (s.coin() ? "x" : "y") : integer(sc, d - 1);
                }
                case 19 -> callFn(sc, d - 1);
                case 20 -> {
                    if (!values) yield integer(sc, d - 1);
                    yield s.coin() ? "Value.lovelace(" + integer(sc, d - 1) + ").lovelace"
                            : "(Value.lovelace(" + integer(sc, d - 1) + ") + Value.lovelace(" + integer(sc, d - 1) + ")).lovelace";
                }
                default -> {
                    String v = fresh("v");
                    yield "match " + opt(sc, d - 1) + " { Some(" + v + ") => (" + v + " - " + integer(sc.with(new Var(v, T.INT)), d - 1) + "), None => ("
                            + integer(sc, d - 1) + ") }";
                }
            };
        }

        String callFn(Scope sc, int d) {
            if (fns.isEmpty()) return integer(sc, d);
            String[] f = fns.get(s.pick(0, fns.size() - 1));
            if (f[1].equals("rec")) return f[0] + "(min(abs(" + integer(sc, d) + "), 12))";
            var args = new ArrayList<String>();
            for (int i = 1; i < f.length; i++) args.add(expr(sc, T.valueOf(f[i]), d));
            return f[0] + "(" + String.join(", ", args) + ")";
        }

        /** A Boolean that reads the binding {@code x} (an unused binding is an error). */
        String uses(String x, Scope sc, int d) {
            String cmp = new String[]{" < ", " <= ", " > ", " >= ", " == ", " != "}[s.pick(0, 5)];
            String e = "(" + x + cmp + integer(sc, d) + ")";
            return s.coin() ? e : "(" + e + (s.coin() ? " && " : " || ") + bool(sc, d) + ")";
        }

        String bool(Scope sc, int d) {
            if (d <= 0 || s.chance(4)) {
                String v = var(sc, T.BOOL);
                if (v != null && s.coin()) return v;
                return s.coin() ? "true" : "false";
            }
            return switch (s.pick(0, 13)) {
                case 0 -> "(" + integer(sc, d - 1) + new String[]{" < ", " <= ", " > ", " >= "}[s.pick(0, 3)] + integer(sc, d - 1) + ")";
                case 1 -> {
                    T t = T.values()[s.pick(0, T.values().length - 1)];
                    if ((t == T.TIER && !tier) || (t == T.PT && !pt) || (t == T.MAP && var(sc, T.MAP) == null)) t = T.INT;
                    yield "(" + expr(sc, t, d - 1) + (s.coin() ? " == " : " != ") + expr(sc, t, d - 1) + ")";
                }
                case 2 -> "!(" + bool(sc, d - 1) + ")";
                case 3 -> "(" + bool(sc, d - 1) + " && " + bool(sc, d - 1) + ")";
                case 4 -> "(" + bool(sc, d - 1) + " || " + bool(sc, d - 1) + ")";
                case 5 -> "(" + bytesExpr(sc, d - 1) + (s.coin() ? " < " : " >= ") + bytesExpr(sc, d - 1) + ")";
                case 6 -> list(sc, d - 1) + ".contains(" + integer(sc, d - 1) + ")";
                case 7 -> {
                    String x = fresh("x");
                    yield list(sc, d - 1) + (s.coin() ? ".any" : ".all") + "(" + x + " => " + uses(x, sc.with(new Var(x, T.INT)), d - 1) + ")";
                }
                case 8 -> list(sc, d - 1) + ".isEmpty";
                case 9 -> {
                    if (!values) yield bool(sc, d - 1);
                    if (s.coin()) {
                        value.add("isEmpty");
                        yield "isEmpty(Value.lovelace(" + integer(sc, d - 1) + "))";
                    }
                    yield "Value.lovelace(" + integer(sc, d - 1) + ").covers(Value.lovelace(" + integer(sc, d - 1) + "))";
                }
                case 10 -> var(sc, T.MAP) != null && s.coin() ? (s.coin() ? mapExpr(sc, d - 1) + ".has(" + bytesExpr(sc, d - 1) + ")" : mapExpr(sc, d - 1) + ".isEmpty")
                        : "(" + keyExpr(sc) + " == " + keyExpr(sc) + ")";
                default -> "(" + integer(sc, d - 1) + (s.coin() ? " == " : " != ") + integer(sc, d - 1) + ")";
            };
        }

        String keyExpr(Scope sc) {
            String v = var(sc, T.KEY);
            if (v != null && s.coin()) return v;
            if (sc.method() && sc.tx() && s.coin()) return "caller";
            if (sc.state()) return "owner";
            return v != null ? v : "asKey(#" + "0a".repeat(28) + ")";
        }

        String bytesExpr(Scope sc, int d) {
            if (d <= 0 || s.chance(3)) {
                String v = var(sc, T.BYTES);
                if (v != null && s.coin()) return v;
                return bytesLit();
            }
            return switch (s.pick(0, 5)) {
                case 0 -> {
                    bytes.add("concat");
                    yield "concat(" + bytesExpr(sc, d - 1) + ", " + bytesExpr(sc, d - 1) + ")";
                }
                case 1 -> {
                    bytes.add("slice");
                    yield "slice(" + bytesExpr(sc, d - 1) + ", " + integer(sc, d - 1) + ", " + integer(sc, d - 1) + ")";
                }
                case 2 -> {
                    String f = s.coin() ? "take" : "drop";
                    bytes.add(f);
                    yield f + "(" + bytesExpr(sc, d - 1) + ", " + integer(sc, d - 1) + ")";
                }
                case 3 -> {
                    crypto.add("blake2b_224");
                    yield "blake2b_224(" + bytesExpr(sc, d - 1) + ")";
                }
                default -> "if " + bool(sc, d - 1) + " { " + bytesExpr(sc, d - 1) + " } else { " + bytesExpr(sc, d - 1) + " }";
            };
        }

        String list(Scope sc, int d) {
            if (d <= 0 || s.chance(3)) {
                String v = var(sc, T.LIST);
                if (v != null && s.coin()) return v;
                return s.coin() ? "[" + intLit() + "]" : "[" + intLit() + ", " + intLit() + "]";
            }
            return switch (s.pick(0, 6)) {
                case 0 -> "[" + integer(sc, d - 1) + ", ..." + list(sc, d - 1) + "]";
                case 1 -> list(sc, d - 1) + (s.coin() ? ".take(" : ".drop(") + integer(sc, d - 1) + ")";
                case 2 -> {
                    String x = fresh("x");
                    yield list(sc, d - 1) + ".map(" + x + " => (" + x + " * " + integer(sc.with(new Var(x, T.INT)), d - 1) + "))";
                }
                case 3 -> {
                    String x = fresh("x");
                    yield list(sc, d - 1) + ".filter(" + x + " => " + uses(x, sc.with(new Var(x, T.INT)), d - 1) + ")";
                }
                case 4 -> list(sc, d - 1) + ".reverse()";
                case 5 -> list(sc, d - 1) + ".concat(" + list(sc, d - 1) + ")";
                default -> "[" + integer(sc, d - 1) + "]";
            };
        }

        String opt(Scope sc, int d) {
            if (d <= 0 || s.chance(3)) {
                String v = var(sc, T.OPT);
                if (v != null && s.coin()) return v;
                return "Some(" + intLit() + ")";
            }
            return switch (s.pick(0, 3)) {
                case 0 -> "Some(" + integer(sc, d - 1) + ")";
                case 1 -> list(sc, d - 1) + ".head";
                case 2 -> {
                    String x = fresh("x");
                    yield list(sc, d - 1) + ".find(" + x + " => " + uses(x, sc.with(new Var(x, T.INT)), d - 1) + ")";
                }
                default -> "if " + bool(sc, d - 1) + " { " + opt(sc, d - 1) + " } else { None }";
            };
        }

        String tierExpr(Scope sc, int d) {
            String v = var(sc, T.TIER);
            if (v != null && s.coin()) return v;
            return s.coin() ? "Lo" : "Hi { k: " + integer(sc, d - 1) + " }";
        }

        String ptExpr(Scope sc, int d) {
            String v = var(sc, T.PT);
            if (v != null && s.coin()) return v;
            return "Pt { x: " + integer(sc, d - 1) + ", y: " + integer(sc, d - 1) + " }";
        }

        /** A Map<Bytes, Int>: a variable, or one set or deleted (a key over 64 bytes fails Map.set: domain:Map.set). */
        String mapExpr(Scope sc, int d) {
            // Only where a map variable is in scope: Map.empty() has no type of its own outside a typed position.
            String v = var(sc, T.MAP);
            if (d <= 0 || s.chance(3)) return v;
            String inner = mapExpr(sc, d - 1);
            return s.coin() ? inner + ".set(" + bytesExpr(sc, d - 1) + ", " + integer(sc, d - 1) + ")"
                    : inner + ".delete(" + bytesExpr(sc, d - 1) + ")";
        }

        String expr(Scope sc, T t, int d) {
            return switch (t) {
                case INT -> integer(sc, d);
                case BOOL -> bool(sc, d);
                case BYTES -> bytesExpr(sc, d);
                case KEY -> keyExpr(sc);
                case LIST -> list(sc, d);
                case OPT -> opt(sc, d);
                case TIER -> tierExpr(sc, d);
                case PT -> ptExpr(sc, d);
                case MAP -> mapExpr(sc, d);
            };
        }

        static String typeName(T t) {
            return switch (t) {
                case INT -> "Int";
                case BOOL -> "Bool";
                case BYTES -> "Bytes";
                case KEY -> "Key";
                case LIST -> "List<Int>";
                case OPT -> "Option<Int>";
                case TIER -> "Tier";
                case PT -> "Pt";
                case MAP -> "Map<Bytes, Int>";
            };
        }

        // ---- the state's fields: the values a method may store (bounded, see the class comment)

        List<Var> stateVars() {
            var vs = new ArrayList<>(List.of(new Var("owner", T.KEY), new Var("n", T.INT), new Var("m", T.INT), new Var("b", T.BYTES),
                    new Var("f", T.BOOL), new Var("xs", T.LIST), new Var("o", T.OPT)));
            if (tier) vs.add(new Var("t", T.TIER));
            if (pt) vs.add(new Var("p", T.PT));
            if (maps) vs.add(new Var("mp", T.MAP));
            return vs;
        }

        String stored(Var field, Scope sc, int d) {
            return switch (field.type()) {
                case INT -> "(" + integer(sc, d) + ") % 1_000_003";
                case BYTES -> {
                    bytes.add("take");
                    yield "take(" + bytesExpr(sc, d) + ", 40)";
                }
                case LIST -> {
                    String x = fresh("x");
                    yield "(" + list(sc, d) + ").take(4).map(" + x + " => " + x + " % 1_000_003)";
                }
                case OPT -> "if " + bool(sc, d) + " { Some((" + integer(sc, d) + ") % 1_000_003) } else { None }";
                case TIER -> s.coin() ? "Lo" : "Hi { k: (" + integer(sc, d) + ") % 1_000_003 }";
                case PT -> "Pt { x: (" + integer(sc, d) + ") % 1_000_003, y: (" + integer(sc, d) + ") % 1_000_003 }";
                case KEY -> keyExpr(sc);
                case BOOL -> bool(sc, d);
                // At most a few entries, each value reduced (a state is stored; its size is bounded, see the class comment).
                case MAP -> {
                    bytes.add("take");
                    String m0 = var(sc, T.MAP);
                    if (m0 == null || s.chance(4)) yield "Map.empty()";
                    yield s.coin() ? m0 : "(if " + m0 + ".size < 4 { " + m0 + " } else { " + m0 + ".delete(#) }).set(take(" + bytesExpr(sc, d) + ", 16), ("
                            + integer(sc, d) + ") % 1_000_003)";
                }
            };
        }

        // ---- statements

        /** Statements of a method body; consts join the scope. Returns the scope after them. */
        Scope statements(StringBuilder out, Scope sc, String indent, boolean mayPay, boolean mayEmit) {
            for (int i = s.pick(0, 3); i > 0; i--) {
                switch (s.pick(0, 7)) {
                    case 0, 1 -> {
                        String c = "c" + (consts++);
                        T t = s.coin() ? T.INT : new T[]{T.BYTES, T.BOOL, T.LIST}[s.pick(0, 2)];
                        out.append(indent).append("const ").append(c).append(" = ").append(expr(sc, t, 2)).append('\n');
                        // A const must be used: a check of it follows (it may be used again later).
                        out.append(indent).append("require((").append(c).append(s.coin() ? " == " : " != ").append(expr(sc, t, 1)).append(") || ")
                                .append(bool(sc, 1)).append(", \"").append(msg()).append("\")\n");
                        sc = sc.with(new Var(c, t));
                    }
                    case 2, 3 -> out.append(indent).append("require(").append(bool(sc, 3)).append(", \"").append(msg()).append("\")\n");
                    case 4 -> {
                        out.append(indent).append("if ").append(bool(sc, 2)).append(" {\n");
                        out.append(indent).append("  require(").append(bool(sc, 2)).append(", \"").append(msg()).append("\")\n");
                        if (mayPay && s.coin()) out.append(indent).append("  ").append(pay(sc)).append('\n');
                        out.append(indent).append("} else {\n");
                        out.append(indent).append("  require(").append(bool(sc, 2)).append(", \"").append(msg()).append("\")\n");
                        out.append(indent).append("}\n");
                    }
                    case 5 -> {
                        if (mayPay) out.append(indent).append(pay(sc)).append('\n');
                    }
                    case 6 -> {
                        if (mayEmit && events) out.append(indent).append("emit(Ev { v: (").append(integer(sc, 2)).append(") % 1_000_003 })\n");
                    }
                    default -> {
                        if (time && sc.tx()) {
                            String[] at = {"1_799_999_999_999", "1_800_000_000_000", "1_800_000_000_001", "1_800_000_120_000", "1_800_000_120_001"};
                            out.append(indent).append("require(").append(s.coin() ? "after(" : "before(").append(at[s.pick(0, at.length - 1)])
                                    .append("), \"").append(msg()).append("\")\n");
                        } else {
                            String v = fresh("v");
                            Scope in = sc.with(new Var(v, T.INT));
                            out.append(indent).append("for (const ").append(v).append(" of ").append(list(sc, 1)).append(") {\n");
                            out.append(indent).append("  require(").append(uses(v, in, 2)).append(", \"").append(msg()).append("\")\n");
                            out.append(indent).append("}\n");
                        }
                    }
                }
            }
            return sc;
        }

        String pay(Scope sc) {
            return "pay(" + (sc.tx() && s.coin() ? "caller" : "owner") + ", 2_000_000 + abs(" + integer(sc, 2) + ") % 3_000_000)";
        }

        // ---- declarations

        String fnDecls() {
            var sb = new StringBuilder();
            int n = s.pick(1, 3);
            for (int i = 0; i < n; i++) {
                String name = "g" + i;
                switch (s.pick(0, 3)) {
                    case 0 -> {
                        Scope sc = new Scope(List.of(new Var("a", T.INT), new Var("c", T.INT)), false, false, false);
                        sb.append("fn ").append(name).append("(a: Int, c: Int): Int = ").append(integer(sc, 3)).append('\n');
                        fns.add(new String[]{name, "INT", "INT"});
                    }
                    case 1 -> {
                        Scope sc = new Scope(List.of(new Var("a", T.INT)), false, false, false);
                        sb.append("fn ").append(name).append("(a: Int): Int {\n  require(").append(bool(sc, 2)).append(", \"").append(msg())
                                .append(" (the library's)\")\n  ").append(integer(sc, 2)).append("\n}\n");
                        fns.add(new String[]{name, "INT"});
                    }
                    case 2 -> {
                        sb.append("fn ").append(name).append("(a: Int): Int = if a <= 0 { 0 } else { a + ").append(name).append("(a - 1) }\n");
                        fns.add(new String[]{name, "rec"});
                    }
                    default -> {
                        if (tier) {
                            Scope sc = new Scope(List.of(new Var("a", T.INT)), false, false, false);
                            sb.append("fn ").append(name).append("(t: Tier, a: Int): Int = match t {\n  Lo => (").append(integer(sc, 2)).append(")")
                                    .append(",\n  Hi { k } => (k + ").append(integer(sc.with(new Var("k", T.INT)), 2)).append(")\n}\n");
                            fns.add(new String[]{name, "TIER", "INT"});
                        } else {
                            Scope sc = new Scope(List.of(new Var("a", T.BYTES)), false, false, false);
                            sb.append("fn ").append(name).append("(a: Bytes): Int = ").append(integer(sc, 2)).append('\n');
                            fns.add(new String[]{name, "BYTES"});
                        }
                    }
                }
            }
            return sb.toString();
        }

        static String imports(String from, TreeSet<String> names) {
            return names.isEmpty() ? "" : "import { " + String.join(", ", names) + " } from \"" + from + "\"\n";
        }

        Plan plan() {
            library = s.chance(3);
            tier = s.coin();
            pt = s.coin();
            params = s.chance(4);
            invariant = s.chance(5);
            events = s.chance(4);
            values = s.chance(3);
            maps = s.chance(3);
            assets = s.chance(4);
            time = s.chance(3);
            if (library) features.add("M4 library");
            if (params) features.add("M2b params");
            if (invariant) features.add("M2b invariant");
            if (events) features.add("M2b events");
            if (values) features.add("M2 values");
            if (maps) features.add("M1 map");
            if (assets) features.add("M2 assets (mint, send, accept)");
            if (time) features.add("time");
            if (tier) features.add("enum");
            if (pt) features.add("record");

            // Declarations: in the library (M4) or at the top of the contract's file.
            var decls = new StringBuilder();
            if (pt) decls.append("type Pt = { x: Int, y: Int }\n");
            if (tier) decls.append("enum Tier { Lo, Hi { k: Int } }\n");
            // fns are generated with their own std imports, which go to the file they are in.
            var outer = new TreeSet[]{math, bytes, crypto, value};
            var saved = new ArrayList<TreeSet<String>>();
            for (var t : outer) saved.add(new TreeSet<String>(t));
            String fnText = fnDecls();
            String libImports = "";
            if (library) {
                libImports = imports("std/math", math) + imports("std/bytes", bytes) + imports("std/crypto", crypto) + imports("std/value", value);
                math.clear();
                bytes.clear();
                crypto.clear();
                value.clear();
            }

            // The contract.
            var sv = stateVars();
            var c = new StringBuilder();
            String stateDecl = String.join(", ", sv.stream().map(v -> v.name() + ": " + typeName(v.type())).toList());
            c.append("  state { ").append(stateDecl).append(" }\n");
            if (params) c.append("  params { cap: Int }\n");
            if (events) c.append("  event Ev { v: Int }\n");
            c.append('\n');

            // init
            Scope initScope = new Scope(List.of(new Var("a", T.INT), new Var("k", T.BYTES)), true, false, true, false);
            // An init reads no state: its scope has only its parameters (and caller, the params).
            c.append("  init(a: Int, k: Bytes) {\n");
            Scope si = statements(c, initScope, "    ", false, false);
            var init = new ArrayList<String>();
            init.add("owner: caller");
            for (Var v : sv) {
                if (v.name().equals("owner")) continue;
                init.add(v.name() + ": " + stored(v, si, 2));
            }
            c.append("    return { ").append(String.join(", ", init)).append(" }\n  }\n\n");
            kinds.put("init", "init");
            methodParams.put("init", List.of(T.INT, T.BYTES));

            // actions
            int actions = s.pick(1, 3);
            for (int i = 0; i < actions; i++) {
                String name = "a" + i;
                var ps = new ArrayList<Var>();
                for (int j = s.pick(0, 3); j > 0; j--) {
                    T t = T.values()[s.pick(0, T.values().length - 1)];
                    if ((t == T.TIER && !tier) || (t == T.PT && !pt) || (t == T.MAP && !maps)) t = T.KEY;
                    ps.add(new Var("p" + i + "_" + ps.size(), t));
                }
                boolean payable = values && s.coin();
                int who = s.pick(0, 2);   // 0 none, 1 only owner, 2 open
                boolean pays = who != 0 && s.coin();
                var mods = new ArrayList<String>();
                if (payable) mods.add("payable");
                if (who == 1) mods.add("only owner");
                if (who == 2) mods.add("open");
                var vs = new ArrayList<Var>(sv);
                vs.addAll(ps);
                Scope sc = new Scope(vs, true, payable, true);
                c.append("  action ").append(name).append("(").append(String.join(", ", ps.stream().map(p -> p.name() + ": " + typeName(p.type())).toList()))
                        .append(")").append(mods.isEmpty() ? "" : " " + String.join(" ", mods)).append(" {\n");
                Scope after = statements(c, sc, "    ", pays, true);
                var upd = new ArrayList<String>();
                for (Var v : sv) if (s.chance(3)) upd.add(v.name() + ": " + stored(v, after, 2));
                c.append("    return ").append(upd.isEmpty() ? "state" : "{ ...state, " + String.join(", ", upd) + " }").append("\n  }\n\n");
                kinds.put(name, "action");
                methodParams.put(name, ps.stream().map(Var::type).toList());
                payableMethods.put(name, payable);
            }

            // M2a (review r1, design F2): an action that accepts what is offered, mints the own policy's token #6d and
            // sometimes sends what it minted to the caller; the rest stays in the instance's output.
            if (assets) {
                String name = "x" + kinds.size();
                var vs = new ArrayList<Var>(sv);
                vs.add(new Var("q", T.INT));
                Scope sc = new Scope(vs, true, true, true);
                String k = "c" + (consts++);
                c.append("  action ").append(name).append("(q: Int) payable open {\n");
                c.append("    accept(funds.received)\n");
                c.append("    const ").append(k).append(" = (").append(integer(sc, 2)).append(") % 7\n");
                c.append("    mint(#6d, ").append(k).append(")\n");
                if (s.coin()) c.append("    send(wallet(caller), Value.asset(funds.policy, #6d, ").append(s.coin() ? k : "abs(" + k + ")").append("))\n");
                c.append("    return { ...state, n: (n + ").append(k).append(") % 1_000_003 }\n  }\n\n");
                kinds.put(name, "action");
                methodParams.put(name, List.of(T.INT));
                payableMethods.put(name, true);
                assetMethod = name;
            }

            // views
            for (int i = s.pick(0, 2); i > 0; i--) {
                String name = "w" + kinds.size();
                var vs = new ArrayList<Var>(sv);
                vs.add(new Var("q", T.INT));
                Scope sc = new Scope(vs, true, false, false);
                boolean isInt = s.coin();
                c.append("  view ").append(name).append("(q: Int): ").append(isInt ? "Int" : "Bool").append(" = ")
                        .append(isInt ? integer(sc, 3) : bool(sc, 3)).append("\n\n");
                kinds.put(name, "view");
                methodParams.put(name, List.of(T.INT));
            }

            // destroy
            if (s.coin()) {
                Scope sc = new Scope(new ArrayList<>(sv), true, false, true);
                c.append("  destroy close() only owner {\n");
                if (s.coin()) c.append("    require(").append(bool(sc, 2)).append(", \"").append(msg()).append("\")\n");
                c.append("    pay(owner, balance)\n  }\n\n");
                kinds.put("close", "destroy");
                methodParams.put("close", List.of());
            }

            if (invariant) {
                var vs = new ArrayList<Var>(sv);
                vs.removeIf(v -> v.type() == T.KEY);
                Scope sc = new Scope(vs, false, false, false);
                c.append("  invariant inv = ").append(bool(sc, 2)).append('\n');
            }

            String src = c.toString();
            String hash = sha(decls + fnText + libImports + src + features).substring(0, 10);
            String name = "C" + hash;
            String libName = "l" + hash;
            var files = new LinkedHashMap<String, String>();
            var head = new StringBuilder("jsc 2\npackage " + PKG + "\n\n");
            if (library) {
                var names = new ArrayList<String>();
                if (pt) names.add("Pt");
                if (tier) names.add("Tier");
                for (String[] f : fns) names.add(f[0]);
                head.append("import { ").append(String.join(", ", names)).append(" } from \"./").append(libName).append(".jsc\"\n");
                files.put(DIR + libName + ".jsc", "jsc 2\nlibrary " + PKG + "\n\n" + libImports + "\n" + decls + fnText);
            }
            head.append(imports("std/math", math)).append(imports("std/bytes", bytes)).append(imports("std/crypto", crypto))
                    .append(imports("std/value", value)).append('\n');
            if (!library) head.append(decls).append(fnText).append('\n');
            head.append("contract ").append(name).append(assets ? "(maxDatumBytes = 2048, assetSlots = 4) {\n" : "(maxDatumBytes = 2048) {\n")
                    .append(src).append("}\n");
            files.put(DIR + name + ".jsc", head.toString());

            Rec params = this.params ? new Rec("Config", List.of(value(T.INT))) : null;
            return new Plan(name, files, DIR + name + ".jsc", params, steps(sv), List.copyOf(features), Map.copyOf(kinds));
        }

        // ---- calls and values

        List<Step> steps(List<Var> sv) {
            var out = new ArrayList<Step>();
            out.add(new Step("init", null, List.of(value(T.INT), value(T.BYTES)), s.chance(8) ? STRANGER : OWNER, 0, cosigners(),
                    !s.chance(3), state(sv), window(), null));
            var methods = new ArrayList<>(kinds.keySet());
            methods.remove("init");
            for (int i = s.pick(1, 7); i > 0 && !methods.isEmpty(); i--) {
                String m = methods.get(s.pick(0, methods.size() - 1));
                var args = new ArrayList<Object>();
                for (T t : methodParams.get(m)) args.add(value(t));
                Rec state = s.chance(4) ? state(sv) : null;
                byte[] caller = s.chance(4) ? STRANGER : OWNER;
                long received = Boolean.TRUE.equals(payableMethods.get(m))
                        ? new long[]{0, 1, 999_999, 1_000_000, 2_000_000, 50_000_000}[s.pick(0, 5)] : 0;
                // Native assets offered only to the method that accepts them (one without accept must be offered none:
                // the validator's rule, which the interpreters do not state, semantics §5.4).
                var offered = m.equals(assetMethod) ? offered() : null;
                out.add(new Step(m, state, args, caller, received, cosigners(), !s.chance(3), null, window(), offered));
            }
            return out;
        }

        /**
         * The call's validity window [validFrom, validTo): mostly the default [T0, T0 + 2 min] (null); sometimes zero width,
         * as wide as maxValidityMs allows (an hour), ending at T0, or starting at 0 (review r1, design F2). The time
         * constants after/before read sit at these edges. An unbounded end is the validator's alone to refuse (an interval
         * the interpreters cannot express): FiveWayFuzzTest checks it as a transaction-level attack.
         */
        BigInteger[] window() {
            if (!s.chance(3)) return null;
            BigInteger hour = BigInteger.valueOf(3_600_000);
            return switch (s.pick(0, 4)) {
                case 0 -> new BigInteger[]{T0, T0};
                case 1 -> new BigInteger[]{T0, T0.add(hour)};
                case 2 -> new BigInteger[]{T0.subtract(WINDOW), T0};
                case 3 -> new BigInteger[]{T0.add(WINDOW), T0.add(WINDOW).add(BigInteger.ONE)};
                default -> new BigInteger[]{BigInteger.ZERO, BigInteger.ONE};
            };
        }

        /** What a call offers the accepting method: nothing, one foreign token in an amount, or two policies' tokens. */
        org.julclang.state.dsl.v2.Interpreter.ValueV offered() {
            var m = new java.util.TreeMap<String, java.util.TreeMap<String, BigInteger>>();
            String p1 = "70".repeat(28), p2 = "71".repeat(28), name = HexFormat.of().formatHex("HOSKY".getBytes(StandardCharsets.UTF_8));
            switch (s.pick(0, 3)) {
                case 0 -> { }
                case 1, 2 -> m.computeIfAbsent(p1, k -> new java.util.TreeMap<>()).put(name, BigInteger.valueOf(s.pick(1, 9)));
                default -> {
                    m.computeIfAbsent(p1, k -> new java.util.TreeMap<>()).put(name, BigInteger.valueOf(s.pick(1, 9)));
                    m.computeIfAbsent(p2, k -> new java.util.TreeMap<>()).put("01", BigInteger.ONE);
                }
            }
            return new org.julclang.state.dsl.v2.Interpreter.ValueV(m);
        }

        /** Keys that sign beside the caller: none mostly; the owner, a stranger or a third key sometimes. */
        List<byte[]> cosigners() {
            if (!s.chance(4)) return List.of();
            return List.of(new byte[][]{OWNER, STRANGER, THIRD}[s.pick(0, 2)]);
        }

        Object value(T t) {
            return switch (t) {
                case INT -> s.chance(3) ? FuzzData.EDGES.get(s.pick(0, FuzzData.EDGES.size() - 1)) : BigInteger.valueOf(s.pick(-20, 1_000_000));
                case BOOL -> s.coin();
                case BYTES -> Bytes.of(fill(new int[]{0, 1, 2, 8, 28, 32, 33}[s.pick(0, 6)], s.pick(0, 255)));
                // A Key argument is a checked input: 28 bytes, or (rarely) not, which both interpreters refuse as input:<name>.
                case KEY -> Bytes.of(fill(s.chance(10) ? new int[]{0, 27, 29}[s.pick(0, 2)] : 28, s.pick(0, 255)));
                case LIST -> {
                    var xs = new ArrayList<Object>();
                    for (int i = s.pick(0, 4); i > 0; i--) xs.add(value(T.INT));
                    yield xs;
                }
                case OPT -> s.coin() ? Opt.NONE : Opt.some(value(T.INT));
                case TIER -> s.coin() ? new Rec("Lo", List.of()) : new Rec("Hi", List.of(value(T.INT)));
                case PT -> new Rec("Pt", List.of(value(T.INT), value(T.INT)));
                // A map argument travels raw (FourWay.raw): sometimes its keys out of order, repeated or too long, which the
                // checked-input rule refuses (input:<parameter>) on every leg.
                case MAP -> {
                    var ks = new ArrayList<Bytes>();
                    var vs = new ArrayList<Object>();
                    for (int i = s.pick(0, 3); i > 0; i--) {
                        ks.add(Bytes.of(fill(s.chance(8) ? 65 : s.pick(0, 3), s.pick(0, 255))));
                        vs.add(value(T.INT));
                    }
                    if (!s.chance(4)) {
                        // Mostly canonical: ascending by the canonical key order, no repeated key.
                        var sorted = new java.util.TreeMap<String, Object>();
                        for (int i = 0; i < ks.size(); i++) sorted.put(HexFormat.of().formatHex(ks.get(i).bytes()), vs.get(i));
                        ks.clear();
                        vs.clear();
                        var keys = new ArrayList<>(sorted.keySet());
                        keys.sort(java.util.Comparator.comparingInt(String::length).thenComparing(java.util.Comparator.naturalOrder()));
                        for (String k : keys) {
                            ks.add(Bytes.of(HexFormat.of().parseHex(k)));
                            vs.add(sorted.get(k));
                        }
                    }
                    yield new org.julclang.state.dsl.v2.Interpreter.MapV(ks, vs);
                }
            };
        }

        /** A state of its own (as a datum the validator may find), with values the chained state could hold. */
        Rec state(List<Var> sv) {
            var fs = new ArrayList<Object>();
            for (Var v : sv) {
                Object x = switch (v.type()) {
                    case KEY -> Bytes.of(s.coin() ? OWNER : STRANGER);
                    case INT -> BigInteger.valueOf(s.pick(-1_000_002, 1_000_002));
                    case LIST -> {
                        var xs = new ArrayList<Object>();
                        for (int i = s.pick(0, 4); i > 0; i--) xs.add(BigInteger.valueOf(s.pick(-1_000_002, 1_000_002)));
                        yield xs;
                    }
                    case OPT -> s.coin() ? Opt.NONE : Opt.some(BigInteger.valueOf(s.pick(-1_000_002, 1_000_002)));
                    case TIER -> s.coin() ? new Rec("Lo", List.of()) : new Rec("Hi", List.of(BigInteger.valueOf(s.pick(-1_000_002, 1_000_002))));
                    case PT -> new Rec("Pt", List.of(BigInteger.valueOf(s.pick(-1_000_002, 1_000_002)), BigInteger.valueOf(s.pick(-9, 9))));
                    // A stored map is canonical: the validator never writes another.
                    case MAP -> {
                        var sorted = new java.util.TreeMap<String, Object>(java.util.Comparator.comparingInt(String::length)
                                .thenComparing(java.util.Comparator.naturalOrder()));
                        for (int i = s.pick(0, 3); i > 0; i--) {
                            sorted.put(HexFormat.of().formatHex(fill(s.pick(0, 3), s.pick(0, 255))), BigInteger.valueOf(s.pick(-1_000_002, 1_000_002)));
                        }
                        var ks = new ArrayList<Bytes>();
                        var vals = new ArrayList<Object>();
                        sorted.forEach((k, q) -> {
                            ks.add(Bytes.of(HexFormat.of().parseHex(k)));
                            vals.add(q);
                        });
                        yield new org.julclang.state.dsl.v2.Interpreter.MapV(ks, vals);
                    }
                    default -> value(v.type());
                };
                fs.add(x);
            }
            return new Rec("State", fs);
        }
    }

    static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Plan generate(Src s) {
        return new G(s).plan();
    }
}
