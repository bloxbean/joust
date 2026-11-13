package org.julclang.state.toolchain;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.OptimizationConfiguration;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.ValueAdapter;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalResult;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.ExBudget;
import org.julclang.vm.JulcVm;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.TermExtractor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D9 (implementation review r1, F5 and S4): {@code ValueAdapter}'s JVM body against its on-chain body, function by
 * function. Each public function is compiled by JuLC (a probe method calling it, over the library pool the toolchain uses,
 * so the on-chain variant from {@code src/onchain/java}) and evaluated on the VM with the ledger's PV11 semantics; the JVM
 * body is called directly. Both must refuse, or give the same data, on every case of the grid: quantities at the edges
 * of the signed 128-bit range, zero and negatives; token names of 0, 1, 28, 29 (0x4F, 0x49, 0x4B), 32 and 33 bytes and
 * prefix-ordered ones; a 33-byte policy; lovelace; the own policy and a foreign one; {@code scale} by 0, -1, 2 and 2^200.
 * The arguments are canonical values (the adapter's precondition), except {@code fromWire}'s, which are also hand-built
 * wire encodings: token names in canonical CBOR order or any order, repeated names, zero quantities, empty token maps,
 * policies out of order or repeated, names over 32 bytes, quantities out of range. Three wires never reach the JVM body
 * as written, because JuLC's JVM {@code Value} rewrites them (lovelace's entry first; a non-adjacent repeated key read
 * back wrong): lovelace after a policy, a repeated name, a repeated policy. The on-chain body refuses all three.
 * <p>
 * M2c (review r1, F3, C3): {@code toWire}, {@code data}, {@code depositable} and {@code fromData} on every canonical value;
 * {@code fromWire} and {@code fromData} on each value's wire form ({@code fromWire(toWire(v))} is v); {@code fromData} on
 * every wire above, the three the JVM's {@code Value} rewrites included (it decides on the data as it came), and on data
 * that is no value; {@code claimsFromWire} and {@code fundsFromWire} over lists of claims holding canonical values and
 * wires. Which functions are covered is checked by reflection: every public static method of {@code ValueAdapter}.
 */
class ValueAdapterDifferentialTest {

    static final BigInteger MAX = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE), MIN = BigInteger.ONE.shiftLeft(127).negate();
    static final List<BigInteger> QS = List.of(MIN.subtract(BigInteger.ONE), MIN, MIN.add(BigInteger.ONE), BigInteger.valueOf(-1),
            BigInteger.ZERO, BigInteger.ONE, MAX.subtract(BigInteger.ONE), MAX, MAX.add(BigInteger.ONE));
    static final byte[] P = fill(28, 0x70), Q = fill(28, 0x71), OWN = fill(28, 0x7A), P33 = fill(33, 0x72), ADA = new byte[0];
    static final HexFormat HEX = HexFormat.of();
    static final JulcVm VM = JulcVm.create();

    static final String PROBE = """
            package vaprobe;

            import java.math.BigInteger;
            import org.julclang.core.PlutusData;
            import org.julclang.core.types.JulcList;
            import org.julclang.ledger.Value;
            import org.julclang.stdlib.Builtins;
            import org.julclang.state.api.DepositClaim;
            import org.julclang.state.api.Funds;
            import org.julclang.state.api.ValueAdapter;

            public class VaProbe {
                public static PlutusData none() {
                    return (PlutusData) (Object) ValueAdapter.none();
                }
                public static PlutusData lovelace(PlutusData q) {
                    return (PlutusData) (Object) ValueAdapter.lovelace(Builtins.unIData(q));
                }
                public static PlutusData asset(PlutusData p, PlutusData n, PlutusData q) {
                    return (PlutusData) (Object) ValueAdapter.asset(Builtins.unBData(p), Builtins.unBData(n), Builtins.unIData(q));
                }
                public static PlutusData add(PlutusData a, PlutusData b) {
                    return (PlutusData) (Object) ValueAdapter.add((Value) (Object) a, (Value) (Object) b);
                }
                public static PlutusData subtract(PlutusData a, PlutusData b) {
                    return (PlutusData) (Object) ValueAdapter.subtract((Value) (Object) a, (Value) (Object) b);
                }
                public static PlutusData scale(PlutusData k, PlutusData v) {
                    return (PlutusData) (Object) ValueAdapter.scale(Builtins.unIData(k), (Value) (Object) v);
                }
                public static PlutusData quantity(PlutusData v, PlutusData p, PlutusData n) {
                    return Builtins.iData(ValueAdapter.quantity((Value) (Object) v, Builtins.unBData(p), Builtins.unBData(n)));
                }
                public static PlutusData lovelaceOf(PlutusData v) {
                    return Builtins.iData(ValueAdapter.lovelaceOf((Value) (Object) v));
                }
                public static PlutusData covers(PlutusData a, PlutusData b) {
                    return ValueAdapter.covers((Value) (Object) a, (Value) (Object) b) ? Builtins.iData(1L) : Builtins.iData(0L);
                }
                public static PlutusData same(PlutusData a, PlutusData b) {
                    return ValueAdapter.same((Value) (Object) a, (Value) (Object) b) ? Builtins.iData(1L) : Builtins.iData(0L);
                }
                public static PlutusData isEmpty(PlutusData v) {
                    return ValueAdapter.isEmpty((Value) (Object) v) ? Builtins.iData(1L) : Builtins.iData(0L);
                }
                public static PlutusData withoutLovelace(PlutusData v) {
                    return (PlutusData) (Object) ValueAdapter.withoutLovelace((Value) (Object) v);
                }
                public static PlutusData sendable(PlutusData v, PlutusData p) {
                    return ValueAdapter.sendable((Value) (Object) v, Builtins.unBData(p)) ? Builtins.iData(1L) : Builtins.iData(0L);
                }
                public static PlutusData acceptable(PlutusData v, PlutusData p) {
                    return ValueAdapter.acceptable((Value) (Object) v, Builtins.unBData(p)) ? Builtins.iData(1L) : Builtins.iData(0L);
                }
                public static PlutusData mintable(PlutusData n) {
                    return ValueAdapter.mintable(Builtins.unBData(n)) ? Builtins.iData(1L) : Builtins.iData(0L);
                }
                public static PlutusData mintAdd(PlutusData acc, PlutusData p, PlutusData n, PlutusData q) {
                    return (PlutusData) (Object) ValueAdapter.mintAdd((Value) (Object) acc, Builtins.unBData(p), Builtins.unBData(n),
                            Builtins.unIData(q));
                }
                public static PlutusData fromWire(PlutusData v) {
                    return (PlutusData) (Object) ValueAdapter.fromWire((Value) (Object) v);
                }
                public static PlutusData toWire(PlutusData v) {
                    return (PlutusData) (Object) ValueAdapter.toWire((Value) (Object) v);
                }
                public static PlutusData data(PlutusData v) {
                    return ValueAdapter.data((Value) (Object) v);
                }
                public static PlutusData fromData(PlutusData d) {
                    return (PlutusData) (Object) ValueAdapter.fromData(d);
                }
                public static PlutusData depositable(PlutusData v) {
                    return ValueAdapter.depositable((Value) (Object) v) ? Builtins.iData(1L) : Builtins.iData(0L);
                }
                static PlutusData claimValues(JulcList<DepositClaim> cs) {
                    if (cs.isEmpty()) {
                        return Builtins.mkNilData();
                    } else {
                        DepositClaim c = cs.head();
                        return Builtins.mkCons((PlutusData) (Object) c.value(), claimValues(cs.tail()));
                    }
                }
                public static PlutusData claimsFromWire(PlutusData ds) {
                    JulcList<DepositClaim> cs = (JulcList<DepositClaim>) (Object) Builtins.unListData(ds);
                    return Builtins.listData(claimValues(ValueAdapter.claimsFromWire(cs)));
                }
                public static PlutusData fundsFromWire(PlutusData fd) {
                    Funds f = ValueAdapter.fundsFromWire((Funds) (Object) fd);
                    return Builtins.listData(Builtins.mkCons((PlutusData) (Object) f.received(), Builtins.mkCons((PlutusData) (Object) f.held(),
                            claimValues(f.deposits()))));
                }
            }
            """;

    /** The JVM body of each function, over the same data arguments. */
    static final Map<String, Function<List<PlutusData>, Object>> JVM = new LinkedHashMap<>();

    static {
        JVM.put("none", a -> ValueAdapter.none());
        JVM.put("lovelace", a -> ValueAdapter.lovelace(i(a.get(0))));
        JVM.put("asset", a -> ValueAdapter.asset(b(a.get(0)), b(a.get(1)), i(a.get(2))));
        JVM.put("add", a -> ValueAdapter.add(v(a.get(0)), v(a.get(1))));
        JVM.put("subtract", a -> ValueAdapter.subtract(v(a.get(0)), v(a.get(1))));
        JVM.put("scale", a -> ValueAdapter.scale(i(a.get(0)), v(a.get(1))));
        JVM.put("quantity", a -> ValueAdapter.quantity(v(a.get(0)), b(a.get(1)), b(a.get(2))));
        JVM.put("lovelaceOf", a -> ValueAdapter.lovelaceOf(v(a.get(0))));
        JVM.put("covers", a -> ValueAdapter.covers(v(a.get(0)), v(a.get(1))));
        JVM.put("same", a -> ValueAdapter.same(v(a.get(0)), v(a.get(1))));
        JVM.put("isEmpty", a -> ValueAdapter.isEmpty(v(a.get(0))));
        JVM.put("withoutLovelace", a -> ValueAdapter.withoutLovelace(v(a.get(0))));
        JVM.put("sendable", a -> ValueAdapter.sendable(v(a.get(0)), b(a.get(1))));
        JVM.put("acceptable", a -> ValueAdapter.acceptable(v(a.get(0)), b(a.get(1))));
        JVM.put("mintable", a -> ValueAdapter.mintable(b(a.get(0))));
        JVM.put("mintAdd", a -> ValueAdapter.mintAdd(v(a.get(0)), b(a.get(1)), b(a.get(2)), i(a.get(3))));
        JVM.put("fromWire", a -> ValueAdapter.fromWire(v(a.get(0))));
        // M2c (review r1, F3): the stored-value, memo and deposit functions.
        JVM.put("toWire", a -> ValueAdapter.toWire(v(a.get(0))));
        JVM.put("data", a -> ValueAdapter.data(v(a.get(0))));
        JVM.put("fromData", a -> ValueAdapter.fromData(a.get(0)));
        JVM.put("depositable", a -> ValueAdapter.depositable(v(a.get(0))));
        JVM.put("claimsFromWire", a -> values(ValueAdapter.claimsFromWire(claims(((PlutusData.ListData) a.get(0)).items()))));
        JVM.put("fundsFromWire", a -> {
            var fs = ((PlutusData.ConstrData) a.get(0)).fields();
            var f = ValueAdapter.fundsFromWire(new org.julclang.state.api.Funds(b(fs.get(0)), b(fs.get(1)), v(fs.get(2)), v(fs.get(3)),
                    claims(((PlutusData.ListData) fs.get(4)).items())));
            var out = new ArrayList<PlutusData>(List.of(f.received().toPlutusData(), f.held().toPlutusData()));
            out.addAll(((PlutusData.ListData) values(f.deposits())).items());
            return new PlutusData.ListData(out);
        });
    }

    /** Deposit claims (JVM) from their data: {@code Constr 0 [output, by, value, memo]}. */
    static org.julclang.core.types.JulcList<org.julclang.state.api.DepositClaim> claims(List<PlutusData> ds) {
        org.julclang.core.types.JulcList<org.julclang.state.api.DepositClaim> out = org.julclang.core.types.JulcList.empty();
        for (int k = ds.size() - 1; k >= 0; k--) {
            var fs = ((PlutusData.ConstrData) ds.get(k)).fields();
            var by = ((PlutusData.ConstrData) fs.get(1)).fields();
            out = out.prepend(new org.julclang.state.api.DepositClaim(i(fs.get(0)), new org.julclang.ledger.TxOutRef(
                    new org.julclang.ledger.TxId(b(by.get(0))), i(by.get(1))), v(fs.get(2)), fs.get(3)));
        }
        return out;
    }

    static PlutusData values(org.julclang.core.types.JulcList<org.julclang.state.api.DepositClaim> cs) {
        var out = new ArrayList<PlutusData>();
        for (var c = cs; !c.isEmpty(); c = c.tail()) out.add(c.head().value().toPlutusData());
        return new PlutusData.ListData(out);
    }

    static PlutusData claim(PlutusData value) {
        return PlutusData.constr(0, PlutusData.integer(1), PlutusData.constr(0, PlutusData.bytes(fill(32, 9)), PlutusData.integer(0)), value,
                PlutusData.constr(0));
    }

    static PlutusData listOf(List<PlutusData> xs) {
        return new PlutusData.ListData(xs);
    }

    static final Map<String, Program> PROGRAMS = new LinkedHashMap<>();

    @BeforeAll
    static void compile() {
        Map<String, LibrarySource> pool = LibrarySourceResolver.scanClasspathSources(
                ValueAdapterDifferentialTest.class.getClassLoader(), problem -> { });
        var options = OptimizationConfiguration.apply(new CompilerOptions().setTarget(CompilerTarget.PLUTUS_V3_PV11),
                OptimizationLevel.DEFAULT_PROFILE_ID, null);
        options.setJarLibrarySources(LibrarySourceResolver.jarSourceTexts(pool, pool));
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
        var libs = LibrarySourceResolver.resolveLibrarySources(PROBE, pool).stream().map(LibrarySource::source).toList();
        for (String m : JVM.keySet()) {
            var r = compiler.compileMethod(PROBE, m, libs);
            assertFalse(r.hasErrors(), m + ": " + r.diagnostics());
            // Through the script's CBOR, as a transition program travels.
            PROGRAMS.put(m, JulcScriptAdapter.toProgram(JulcScriptAdapter.fromProgram(r.program()).getCborHex()));
        }
    }

    // ------------------------------------------------------------------ the two bodies

    static BigInteger i(PlutusData d) {
        return ((PlutusData.IntData) d).value();
    }

    static byte[] b(PlutusData d) {
        return ((PlutusData.BytesData) d).value();
    }

    static Value v(PlutusData d) {
        return Value.fromPlutusData(d);
    }

    static PlutusData asData(Object r) {
        if (r instanceof Value x) return x.toPlutusData();
        if (r instanceof BigInteger x) return PlutusData.integer(x);
        if (r instanceof Boolean x) return PlutusData.integer(x ? 1 : 0);
        if (r instanceof PlutusData x) return x;
        throw new IllegalStateException("unexpected result " + r);
    }

    static String jvm(String fn, List<PlutusData> args) {
        try {
            return "ok:" + HEX.formatHex(PlutusDataCborEncoder.encode(asData(JVM.get(fn).apply(args))));
        } catch (org.julclang.state.api.RequireException | IllegalArgumentException | ArithmeticException e) {
            return "refused";
        }
    }

    static String onChain(String fn, List<PlutusData> args) {
        EvalResult r = VM.evaluateWithArgs(PROGRAMS.get(fn), LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), args,
                new ExBudget(10_000_000_000L, 14_000_000L), EvalOptions.DEFAULT);
        if (r instanceof EvalResult.Success s) {
            return "ok:" + HEX.formatHex(PlutusDataCborEncoder.encode(TermExtractor.extractData(s.resultTerm())));
        }
        return "refused";
    }

    int cases, refusedBoth, jvmUnrepresentable;
    final List<String> disagreements = new ArrayList<>();
    final Map<String, Integer> perFunction = new LinkedHashMap<>();

    void check(String fn, PlutusData... args) {
        var a = List.of(args);
        String j = jvm(fn, a), u = onChain(fn, a);
        cases++;
        perFunction.merge(fn, 1, Integer::sum);
        if (j.equals("refused") && u.equals("refused")) refusedBoth++;
        if (!j.equals(u)) disagreements.add(fn + a + ": JVM " + j + ", on-chain " + u);
    }

    /** Whether JuLC's JVM {@code Value} rewrites this data (so a JVM claim or funds cannot hold it as written). */
    static boolean rewritten(PlutusData w) {
        try {
            return !Value.fromPlutusData(w).toPlutusData().equals(w);
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ inputs

    static byte[] fill(int n, int x) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) x);
        return b;
    }

    static byte[] first(int n, int x) {
        byte[] b = fill(n, 0x33);
        b[0] = (byte) x;
        return b;
    }

    static PlutusData bd(byte[] b) {
        return PlutusData.bytes(b);
    }

    static PlutusData in(BigInteger q) {
        return PlutusData.integer(q);
    }

    static final List<byte[]> NAMES = List.of(new byte[0], new byte[]{1}, new byte[]{1, 0}, new byte[]{0, (byte) 0xFF}, new byte[]{(byte) 0xFF},
            "HOSKY".getBytes(), fill(28, 0x6C), first(29, 0x4F), first(29, 0x49), first(29, 0x4B), fill(32, 7), fill(33, 7));

    /** Canonical values, each built afresh by every call (so two calls give equal values in distinct allocations). */
    static List<Supplier<Value>> pool() {
        byte[] h = "HOSKY".getBytes();
        List<Supplier<Value>> out = new ArrayList<>();
        out.add(Assets::none);
        for (BigInteger q : List.of(BigInteger.valueOf(5), BigInteger.valueOf(-1), MAX, MIN)) out.add(() -> Assets.lovelace(q));
        for (BigInteger q : List.of(BigInteger.ONE, BigInteger.valueOf(-1), MAX, MIN, BigInteger.valueOf(7))) out.add(() -> Assets.of(P, h, q));
        out.add(() -> Assets.add(Assets.of(P, new byte[]{1}, BigInteger.ONE), Assets.of(P, new byte[]{1, 0}, BigInteger.TWO)));
        out.add(() -> Assets.add(Assets.of(P, new byte[]{0, (byte) 0xFF}, BigInteger.ONE), Assets.of(P, new byte[]{1}, BigInteger.ONE)));
        out.add(() -> Assets.add(Assets.of(P, h, BigInteger.valueOf(3)), Assets.lovelace(BigInteger.valueOf(7))));
        out.add(() -> Assets.add(Assets.of(P, h, BigInteger.valueOf(5)), Assets.of(Q, h, BigInteger.valueOf(-2))));
        out.add(() -> Assets.add(Assets.of(P, h, MAX), Assets.add(Assets.of(Q, h, MIN), Assets.lovelace(MAX))));
        for (byte[] n : List.of(fill(32, 7), first(29, 0x4F), first(29, 0x49), first(29, 0x4B), new byte[0])) {
            out.add(() -> Assets.of(OWN, n, BigInteger.ONE));
        }
        out.add(() -> Assets.add(Assets.of(OWN, fill(28, 0x6C), BigInteger.valueOf(4)), Assets.of(P, h, BigInteger.ONE)));
        return out;
    }

    static PlutusData map(PlutusData.Pair... ps) {
        return new PlutusData.MapData(List.of(ps));
    }

    static PlutusData.Pair pair(PlutusData k, PlutusData v) {
        return new PlutusData.Pair(k, v);
    }

    static PlutusData tokens(Object... nq) {
        var ps = new ArrayList<PlutusData.Pair>();
        for (int k = 0; k < nq.length; k += 2) ps.add(pair(bd((byte[]) nq[k]), in((BigInteger) nq[k + 1])));
        return new PlutusData.MapData(ps);
    }

    // ------------------------------------------------------------------ the grid

    @Test
    void theJvmBodyAndTheOnChainBodyAgree() {
        var pool = pool();
        // Constructors at every edge: quantities, names, policies (lovelace's empty one, the own, a foreign, 33 bytes).
        check("none");
        for (BigInteger q : QS) check("lovelace", in(q));
        for (byte[] p : List.of(ADA, P, OWN, P33)) {
            for (byte[] n : NAMES) for (BigInteger q : QS) check("asset", bd(p), bd(n), in(q));
        }
        // Binary operations over every pair (the second built afresh: equal values in distinct allocations).
        for (var x : pool) {
            for (var y : pool) {
                PlutusData a = x.get().toPlutusData(), c = y.get().toPlutusData();
                for (String fn : List.of("add", "subtract", "covers", "same")) check(fn, a, c);
            }
        }
        // Unary operations, scale by 0, -1, 2, 2^200 and the range's edges, and the effects' checks.
        for (var x : pool) {
            PlutusData a = x.get().toPlutusData();
            for (BigInteger k : List.of(BigInteger.ZERO, BigInteger.valueOf(-1), BigInteger.TWO, BigInteger.TWO.pow(200), MAX, MIN)) {
                check("scale", in(k), a);
            }
            for (String fn : List.of("lovelaceOf", "isEmpty", "withoutLovelace", "fromWire", "toWire", "data", "depositable", "fromData")) {
                check(fn, a);
            }
            for (byte[] p : List.of(ADA, P, Q, OWN, P33)) {
                for (byte[] n : List.of(new byte[0], "HOSKY".getBytes(), fill(32, 7), fill(33, 7))) check("quantity", a, bd(p), bd(n));
            }
            for (byte[] p : List.of(P, OWN)) {
                check("sendable", a, bd(p));
                check("acceptable", a, bd(p));
            }
        }
        for (byte[] n : NAMES) check("mintable", bd(n));
        // mintAdd: the net mint of a name, at the range's edges; names mintable allows, and 32/33-byte ones (the generated
        // code calls mintable first, but the bodies agree without it too: insertCoin refuses a key over 32 bytes).
        for (var acc : List.<Supplier<Value>>of(Assets::none, () -> Assets.of(OWN, "HOSKY".getBytes(), MAX),
                () -> Assets.of(OWN, "HOSKY".getBytes(), MIN), () -> Assets.of(OWN, new byte[]{1}, BigInteger.TWO))) {
            for (byte[] n : List.of("HOSKY".getBytes(), new byte[]{1}, new byte[0], fill(28, 0x6C), first(29, 0x4B), fill(32, 0x6C), fill(33, 0x6C))) {
                for (BigInteger q : QS) check("mintAdd", acc.get().toPlutusData(), bd(OWN), bd(n), in(q));
            }
        }

        // fromWire on wire encodings, canonical or not.
        byte[] h = "HOSKY".getBytes(), a01 = {1}, a00ff = {0, (byte) 0xFF}, a0100 = {1, 0};
        BigInteger one = BigInteger.ONE, two = BigInteger.TWO, zero = BigInteger.ZERO;
        List<PlutusData> wires = new ArrayList<>(List.of(
                map(),
                map(pair(bd(P), tokens(a01, one, a00ff, one))),                                   // canonical CBOR order (length first)
                map(pair(bd(P), tokens(a00ff, one, a01, one))),                                   // ledger order
                map(pair(bd(P), tokens(a0100, two, a01, one))),                                   // a prefix after its extension
                map(pair(bd(P), tokens(h, one, a01, two, a00ff, BigInteger.valueOf(-3)))),        // any order, a negative
                map(pair(bd(ADA), tokens(new byte[0], BigInteger.valueOf(9))), pair(bd(P), tokens(a01, one, a00ff, one))),
                map(pair(bd(P), tokens(a01, one, a01, one))),                                     // a repeated name
                map(pair(bd(P), tokens(a01, one, a00ff, one, a01, two))),                         // repeated, not adjacent
                map(pair(bd(P), tokens(a01, zero))),                                              // a zero quantity
                map(pair(bd(P), tokens(a01, one, a00ff, zero))),
                map(pair(bd(P), tokens())),                                                       // an empty token map
                map(pair(bd(Q), tokens(h, one)), pair(bd(P), tokens(h, one))),                    // policies out of order
                map(pair(bd(P), tokens(h, one)), pair(bd(P), tokens(a01, one))),                  // a repeated policy
                map(pair(bd(P), tokens(h, one)), pair(bd(ADA), tokens(new byte[0], one))),        // lovelace after a policy
                map(pair(bd(P), tokens(fill(33, 7), one))),                                       // a 33-byte name
                map(pair(bd(P33), tokens(h, one))),                                               // a 33-byte policy
                map(pair(bd(P), tokens(fill(32, 7), one, a01, one))),
                // Review r2, C6: policies of other lengths than lovelace's and 28 bytes (fromWire takes them in the ledger's
                // order; fromData refuses them in any order: canonical CBOR would write #ff before #0000).
                map(pair(bd(new byte[]{0, 0}), tokens(a01, one)), pair(bd(new byte[]{(byte) 0xFF}), tokens(a01, one))),
                map(pair(bd(new byte[]{(byte) 0xFF}), tokens(a01, one)), pair(bd(new byte[]{0, 0}), tokens(a01, one))),
                map(pair(bd(new byte[]{1}), tokens(a01, one))),
                map(pair(bd(fill(29, 0x70)), tokens(a01, one))),
                map(pair(bd(ADA), tokens(new byte[0], one)), pair(bd(fill(27, 0x70)), tokens(a01, one))),
                map(pair(bd(fill(32, 0x70)), tokens(a01, one)))));
        for (BigInteger q : QS) {
            wires.add(map(pair(bd(P), tokens(a01, one, a00ff, q))));
            wires.add(map(pair(bd(ADA), tokens(new byte[0], q))));
        }
        for (PlutusData w : wires) {
            if (!Value.fromPlutusData(w).toPlutusData().equals(w)) {
                // JuLC's JVM Value cannot hold three of these wires as written: it writes lovelace's entry first, and a
                // repeated key that is not adjacent comes back with another entry's quantity or name. They never reach the
                // JVM body as such (the runtime builds values with Assets); the on-chain body must refuse them, failing closed.
                assertEquals("refused", onChain("fromWire", List.of(w)), "a wire the JVM's Value cannot hold: " + w);
                jvmUnrepresentable++;
                System.out.println("[ValueAdapter differential] the JVM's Value rewrites " + w + " as " + Value.fromPlutusData(w).toPlutusData());
                continue;
            }
            check("fromWire", w);
        }
        assertEquals(3, jvmUnrepresentable, "lovelace after a policy, a repeated name, a repeated policy: the wires the JVM's Value rewrites");

        // M2c (review r1, C3, F3): fromData decides on the data as it came, so it agrees on every wire, the three the JVM's
        // Value rewrites included (lovelace after a policy is refused on both), and on data that is no value at all.
        for (PlutusData w : wires) check("fromData", w);
        for (PlutusData d : List.<PlutusData>of(PlutusData.integer(5), PlutusData.bytes(P), PlutusData.constr(0), new PlutusData.ListData(List.of()),
                map(pair(in(BigInteger.ONE), tokens(h, one))), map(pair(bd(P), in(one))), map(pair(bd(P), map(pair(in(one), in(one))))),
                map(pair(bd(P), map(pair(bd(h), bd(h))))))) {
            check("fromData", d);
        }
        // A stored value's wire form and back, on every canonical value: fromWire(toWire(v)) is v.
        for (var x : pool) {
            PlutusData a = x.get().toPlutusData();
            String wire = onChain("toWire", List.of(a));
            if (wire.startsWith("ok:")) {
                PlutusData w = org.julclang.core.cbor.PlutusDataCborDecoder.decode(HEX.parseHex(wire.substring(3)));
                check("fromWire", w);
                check("fromData", w);
                assertEquals("ok:" + HEX.formatHex(PlutusDataCborEncoder.encode(a)), onChain("fromWire", List.of(w)), "fromWire(toWire(v)) is v");
            }
        }
        // Deposit claims and funds: no claim, one, several; each claim's value canonical or a wire (in the wire's order,
        // malformed); a wire the JVM's Value rewrites must be refused on-chain (as for fromWire above).
        List<PlutusData> claimValues = new ArrayList<>();
        for (var x : pool) claimValues.add(x.get().toPlutusData());
        claimValues.addAll(wires);
        check("claimsFromWire", listOf(List.of()));
        for (PlutusData c : claimValues) {
            for (PlutusData lists : List.of(listOf(List.of(claim(c))), listOf(List.of(claim(claimValues.get(5)), claim(c))))) {
                if (rewritten(c)) {
                    assertEquals("refused", onChain("claimsFromWire", List.of(lists)), "a claim the JVM's Value cannot hold: " + c);
                    continue;
                }
                check("claimsFromWire", lists);
            }
            if (rewritten(c)) continue;
            for (PlutusData held : List.of(claimValues.get(0), claimValues.get(5), c)) {
                check("fundsFromWire", PlutusData.constr(0, bd(OWN), bd(fill(28, 0x5E)), c, held, listOf(List.of(claim(claimValues.get(5)), claim(c)))));
            }
        }


        perFunction.forEach((fn, n) -> assertTrue(n > 0, fn));
        // Every public function of ValueAdapter (by reflection, so a new one without vectors fails here: review r1, F3).
        var publicFunctions = new java.util.TreeSet<String>();
        for (var m : ValueAdapter.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers()) && java.lang.reflect.Modifier.isStatic(m.getModifiers())) publicFunctions.add(m.getName());
        }
        assertEquals(publicFunctions, new java.util.TreeSet<>(perFunction.keySet()), "every public function of ValueAdapter has vectors");
        assertEquals(publicFunctions, new java.util.TreeSet<>(JVM.keySet()), "every public function of ValueAdapter has a JVM body here");
        System.out.println("[ValueAdapter differential] " + cases + " cases, " + refusedBoth + " refused on both, " + disagreements.size()
                + " disagree (" + jvmUnrepresentable
                + " wire the JVM's Value cannot hold, refused on-chain); per function " + perFunction);
        disagreements.forEach(d -> System.out.println("[ValueAdapter differential] DISAGREE " + d));
        assertEquals(List.of(), disagreements);
    }
}
