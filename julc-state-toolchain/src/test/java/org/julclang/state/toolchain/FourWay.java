package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.MapEntry;
import org.julclang.state.api.MerkleMap;
import org.julclang.state.api.MerkleMaps;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Payouts;
import org.julclang.state.api.RequireException;
import org.julclang.state.api.mpf.Mpf;
import org.julclang.state.dsl.v2.Interpreter;
import org.julclang.state.dsl.v2.Interpreter.BigMapV;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.MapV;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.JscV2;
import org.julclang.state.runtime.InMemoryNodeStore;
import org.julclang.state.runtime.MerkleSupport;
import org.julclang.state.testkit.StateHarness;
import org.julclang.stdlib.Builtins;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.julclang.state.toolchain.TransitionParityTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * J3's four legs for one {@code .jsc} 2 contract (ADR-0018 §9): the reference interpreter over the checked IR (the
 * independent oracle), the generated Java on the JVM (Gradle's build of examples/dsl), the UPLC transition program (the
 * npm toolchain's build, as the engine runs it) and the validator on-chain ({@link StateHarness}: the interpreter's
 * outcome must be accepted, a refusal rejected). Values cross between the legs as data, encoded as the runtime does.
 * <p>
 * BigMap contracts (M1a): the interpreter's map is its contents; the JVM's a MerkleMap over a node store. A state crosses
 * as the runtime stores it (each map's root of its contents, no reads or writes), so the JVM's next state, prepared
 * as the runtime prepares it ({@link MerkleSupport}), must equal the interpreter's byte for byte. The transition
 * program reads keys by discovery rounds (ADR-0015 D3) and must give exactly the JVM's result on the same proven reads;
 * the validator gets the map witnesses the runtime builds.
 */
final class FourWay {

    /** blake2b from cardano-client-lib, independent of JuLC's crypto binding. */
    static final Interpreter.Hashes HASHES = (name, in) -> switch (name) {
        case "blake2b_224" -> Blake2bUtil.blake2bHash224(in);
        case "blake2b_256" -> Blake2bUtil.blake2bHash256(in);
        default -> Interpreter.JDK_HASHES.hash(name, in);
    };

    static final String PKG = "org.julclang.state.examples.dsl";
    static final HexFormat HEX = HexFormat.of();
    static final int ROUND_LIMIT = 8;

    final String name;
    final Class<?> cls;
    final Class<?> stateClass;
    final Interpreter interp;
    final Map<String, String> transitions;
    final StateHarness<Object> harness;
    final LinkedHashMap<String, Boolean> bigMaps;
    final InMemoryNodeStore store = new InMemoryNodeStore();
    /** Each root this harness built, with its contents (key hex → stored bytes), for the transition leg's reads. */
    final Map<String, Map<String, byte[]>> contents = new LinkedHashMap<>();
    int calls, refusals, validatorRuns, rounds;
    StateHarness.Verdict lastVerdict;

    @SuppressWarnings({"unchecked", "rawtypes"})
    FourWay(String name, String jscSource, Map<String, String> transitions) throws Exception {
        this.name = name;
        this.cls = Class.forName(PKG + "." + name);
        this.stateClass = Class.forName(PKG + "." + name + "$State");
        this.interp = JscV2.interpreter(jscSource, name + ".jsc", HASHES);
        this.transitions = transitions;
        var spec = (org.julclang.state.runtime.ContractSpec) Class.forName(PKG + "." + name + "Client").getField("SPEC").get(null);
        this.harness = (StateHarness<Object>) StateHarness.of(spec);
        this.bigMaps = interp.bigMaps();
        contents.put(HEX.formatHex(Mpf.emptyRoot()), Map.of());
    }

    boolean hasMaps() {
        return !bigMaps.isEmpty();
    }

    // ------------------------------------------------------------------ values

    static Bytes b(byte[] x) {
        return Bytes.of(x);
    }

    static Rec rec(String type, Object... fields) {
        return new Rec(type, List.of(fields));
    }

    static Tx tx(byte[] caller, long received, long balance, BigInteger from, BigInteger to) {
        return new Tx(Bytes.of(caller), BigInteger.valueOf(received), BigInteger.valueOf(balance), from, to);
    }

    /** A map's stored entry: an Int as its minimal big-endian bytes (MerkleMaps.putInt), Bytes as they are. */
    static byte[] stored(Object v) {
        return v instanceof BigInteger i ? Builtins.integerToByteString(true, 0, i) : ((Bytes) v).bytes();
    }

    /** The root of a map holding {@code m}'s contents, its nodes written to this harness's store. */
    byte[] root(Map<Bytes, Object> m) {
        if (m.isEmpty()) return Mpf.emptyRoot();
        var sorted = new TreeMap<String, byte[]>();
        m.forEach((k, v) -> sorted.put(HEX.formatHex(k.bytes()), stored(v)));
        JulcList<MapEntry> net = JulcList.empty();
        for (var e : sorted.descendingMap().entrySet()) net = net.prepend(new MapEntry(HEX.parseHex(e.getKey()), true, e.getValue()));
        byte[] root = new MerkleSupport.TrieProver(store).write(Mpf.emptyRoot(), net).root();
        contents.put(HEX.formatHex(root), sorted);
        return root;
    }

    /** An interpreter value as the generated Java's value (records and variants by their generated classes). */
    Object java(Object v) {
        try {
            return switch (v) {
                case BigInteger i -> i;
                case Boolean x -> x;
                case Bytes x -> x.bytes();
                case Opt o -> o.present() ? Optional.of(java(o.value())) : Optional.empty();
                case List<?> l -> {
                    if (l.isEmpty()) yield JulcList.empty();
                    var xs = new ArrayList<Object>();
                    for (Object x : l) xs.add(java(x));
                    yield JulcList.of(xs.toArray());
                }
                case MapV m -> {
                    // JulcMap.insert prepends: insert from the last key, so the map keeps the canonical order.
                    JulcMap<Object, Object> out = JulcMap.empty();
                    for (int i = m.keys().size() - 1; i >= 0; i--) out = out.insert(java(m.keys().get(i)), java(m.values().get(i)));
                    yield out;
                }
                // The stored form: the root of the contents now, no reads or writes.
                case BigMapV m -> new MerkleMap(root(m.now()), JulcList.empty(), JulcList.empty());
                case Rec r -> {
                    Class<?> rc = Class.forName(cls.getName() + "$" + r.type());
                    var comps = rc.getRecordComponents();
                    var types = new Class<?>[comps.length];
                    var vals = new Object[comps.length];
                    for (int i = 0; i < comps.length; i++) {
                        types[i] = comps[i].getType();
                        vals[i] = java(r.fields().get(i));
                    }
                    yield rc.getDeclaredConstructor(types).newInstance(vals);
                }
                default -> throw new IllegalArgumentException("value " + v);
            };
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * An interpreter value as data by D5's rules, written here independently of the runtime's codec: records Constr 0,
     * a variant Constr of its declaration index, Option Constr 0 [x] / Constr 1 [], Bool Constr 0/1, lists, and maps
     * as pairs in the value's own order. Arguments cross this way, so a map that is not canonical, or has a duplicate
     * key, reaches the transition program and the validator as an attacker would send it (J15).
     */
    org.julclang.core.PlutusData raw(Object v) {
        return switch (v) {
            case BigInteger i -> org.julclang.core.PlutusData.integer(i);
            case Boolean x -> org.julclang.core.PlutusData.constr(x ? 1 : 0);
            case Bytes x -> org.julclang.core.PlutusData.bytes(x.bytes());
            case Opt o -> o.present() ? org.julclang.core.PlutusData.constr(0, raw(o.value())) : org.julclang.core.PlutusData.constr(1);
            case List<?> l -> new org.julclang.core.PlutusData.ListData(l.stream().map(this::raw).toList());
            case MapV m -> {
                var pairs = new ArrayList<org.julclang.core.PlutusData.Pair>();
                for (int i = 0; i < m.keys().size(); i++) pairs.add(new org.julclang.core.PlutusData.Pair(raw(m.keys().get(i)), raw(m.values().get(i))));
                yield new org.julclang.core.PlutusData.MapData(pairs);
            }
            case Rec r -> new org.julclang.core.PlutusData.ConstrData(interp.tag(r.type()), r.fields().stream().map(this::raw).toList());
            default -> throw new IllegalArgumentException("no argument form for " + v);
        };
    }

    private org.julclang.core.PlutusData rawArgs(List<Object> args) {
        return new org.julclang.core.PlutusData.ConstrData(0, args.stream().map(this::raw).toList());
    }

    static Ctx ctx(Tx t) {
        return t == null ? null : new Ctx(t.caller().bytes(), t.received(), t.balance(), t.validFrom(), t.validTo());
    }

    static JulcList<Payout> payouts(List<Interpreter.Payment> ps) {
        if (ps.isEmpty()) return JulcList.empty();
        var xs = new ArrayList<Payout>();
        for (var p : ps) xs.add(new Payout(p.to().bytes(), p.lovelace()));
        return JulcList.of(xs.toArray(new Payout[0]));
    }

    // ------------------------------------------------------------------ the legs

    /** The interpreter's outcome, encoded as the runtime encodes a transition's result; "refused" when it fails. */
    String interpreted(String method, Rec state, List<Object> args, Tx t) {
        try {
            var o = interp.call(method, state, args, t);
            return switch (interp.kind(method)) {
                case "view" -> "ok:" + data(java(o.value())).serializeToHex();
                case "destroy" -> "ok:" + result(null, payouts(o.payouts()));
                default -> "ok:" + result(java(o.value()), payouts(o.payouts()));
            };
        } catch (Interpreter.Refused e) {
            return "refused";
        }
    }

    private Method method(String method) {
        Method m = null;
        for (Method x : cls.getDeclaredMethods()) if (x.getName().equals(method) && java.lang.reflect.Modifier.isStatic(x.getModifiers())) m = x;
        assertNotNull(m, method);
        return m;
    }

    /** A JVM call's raw result, or null when it is refused (a Require, or an arithmetic failure). */
    private Object invoke(String method, Object state, List<Object> args, Tx t) throws Exception {
        var call = new ArrayList<Object>();
        if (state != null) call.add(state);
        for (Object a : args) call.add(java(a));
        if (interp.usesTx(method)) call.add(ctx(t));
        try {
            return method(method).invoke(null, call.toArray());
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RequireException || e.getCause() instanceof ArithmeticException) return null;
            // A builtin's failure on the JVM (as on-chain): integerToByteString past 8,192 bytes (review C4).
            if (e.getCause() instanceof IllegalArgumentException x && x.getMessage() != null && x.getMessage().startsWith("integerToByteString")) return null;
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw e;
        }
    }

    /** Runs a JVM call with map reads served from this harness's store; RuntimeException-free. */
    private Object run(MerkleSupport ms, String method, Object state, List<Object> args, Tx t) throws Exception {
        Object[] out = new Object[1];
        Exception[] err = new Exception[1];
        ms.run(() -> {
            try {
                out[0] = invoke(method, state, args, t);
            } catch (Exception e) {
                err[0] = e;
            }
            return null;
        });
        if (err[0] != null) throw err[0];
        return out[0];
    }

    private static Object nextState(Object r) throws Exception {
        if (r.getClass().getSimpleName().equals("Outcome")) return r.getClass().getMethod("state").invoke(r);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static JulcList<Payout> outcomePayouts(Object r) throws Exception {
        if (r instanceof JulcList<?> l) return (JulcList<Payout>) l;
        if (r.getClass().getSimpleName().equals("Outcome")) return (JulcList<Payout>) r.getClass().getMethod("payouts").invoke(r);
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    String jvm(String method, Rec state, List<Object> args, Tx t) throws Exception {
        String kind = interp.kind(method);
        Object before = state == null ? null : java(state);
        var ms = new MerkleSupport(store);
        Object r = run(ms, method, before, args, t);
        if (r == null) return "refused";
        if (kind.equals("view")) return "ok:" + data(r).serializeToHex();
        if (r instanceof JulcList<?> l) return "ok:" + result(null, (JulcList<Payout>) l);
        Object next = nextState(r);
        if (hasMaps()) {
            // The state the runtime stores: each map's new root, its witnesses dropped.
            next = kind.equals("init") ? ms.prepareCreation(harness.policy(), (Class) stateClass, next, harness.seed()).stored()
                    : ms.prepare(harness.policy(), (Class) stateClass, before, next, StateHarness.LIVE_REF).stored();
        }
        return "ok:" + result(next, outcomePayouts(r));
    }

    /**
     * The transition program. With maps, by discovery rounds: each key it reads outside its proven reads comes back as a
     * {@code joust:read} trace; the reads are proven from the contents and the program runs again, until it reads nothing
     * new. The final result must equal the JVM's on the same reads, byte for byte; returned is what the interpreter's
     * outcome is compared with: "refused", or the JVM's result prepared as the runtime stores it.
     */
    String transition(String method, Rec state, List<Object> args, Tx t) throws Exception {
        String program = transitions.get(method);
        assertNotNull(program, name + " has no transition program " + method);
        var javaArgs = args.stream().map(this::java).toArray();
        PlutusData ctxData = interp.usesTx(method) ? data(ctx(t)) : NONE;
        if (!hasMaps() || state == null) {
            return evaluateRaw(program, List.of(julc(state == null ? NONE : data(java(state))), julc(NONE), rawArgs(args), julc(ctxData)));
        }
        Object before = java(state);
        var proven = new LinkedHashMap<String, List<MapEntry>>();   // root hex -> proven entries, in discovery order
        ReadDiscoveryTest.Run r = null;
        int round = 1;
        for (; round <= ROUND_LIMIT; round++) {
            r = ReadDiscoveryTest.run(program, List.of(julc(data(opened(before, proven))), julc(NONE), rawArgs(args), julc(ctxData)), true);
            boolean fresh = false;
            for (String[] read : r.rootedReads()) {
                var entries = proven.computeIfAbsent(read[0], k -> new ArrayList<>());
                if (entries.stream().anyMatch(e -> HEX.formatHex(e.key()).equals(read[1]))) continue;
                Map<String, byte[]> backing = contents.get(read[0]);
                assertNotNull(backing, "a read of a map this harness did not build: " + read[0]);
                byte[] v = backing.get(read[1]);
                entries.add(new MapEntry(HEX.parseHex(read[1]), v != null, v != null ? v : new byte[0]));
                fresh = true;
            }
            if (!fresh) break;
        }
        assertTrue(round <= ROUND_LIMIT, name + "." + method + ": over the round limit");
        rounds = Math.max(rounds, round);
        // The JVM on exactly the proven reads (a lookup outside them fails the test).
        Object given = opened(before, proven);
        Object out = MerkleMaps.withReads((root, key) -> {
            throw new AssertionError("the JVM read " + HEX.formatHex(key) + " outside the proven reads");
        }, () -> {
            try {
                return invoke(method, given, args, t);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        String jvmSame = out == null ? "refused" : interp.kind(method).equals("destroy") ? "ok:" + result(null, outcomePayouts(out))
                : "ok:" + result(nextState(out), outcomePayouts(out));
        assertEquals(jvmSame, r.result(), name + "." + method + ": transition program vs the JVM on the same reads");
        return r.result().equals("refused") ? "refused" : jvm(method, state, args, t);
    }

    /** {@code state} with each map opened with the reads proven for its root (as the engine opens it). */
    private Object opened(Object state, Map<String, List<MapEntry>> proven) throws Exception {
        var comps = stateClass.getRecordComponents();
        var types = new Class<?>[comps.length];
        var vals = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            vals[i] = comps[i].getAccessor().invoke(state);
            if (vals[i] instanceof MerkleMap m) {
                JulcList<MapEntry> reads = JulcList.empty();
                var es = proven.getOrDefault(HEX.formatHex(m.root()), List.of());
                for (int k = es.size() - 1; k >= 0; k--) reads = reads.prepend(es.get(k));
                vals[i] = new MerkleMap(m.root(), reads, JulcList.empty());
            }
        }
        return stateClass.getDeclaredConstructor(types).newInstance(vals);
    }

    /**
     * The validator, given the interpreter's outcome: accepted when the call succeeded, rejected when it was refused.
     * For a refusal the transaction proposes the input state again (an action), everything to the caller (a destroy),
     * or {@code candidate} (an init; skipped when null). Map witnesses are built as the runtime builds them.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Boolean validator(String method, Rec state, List<Object> args, Tx t, byte[] signer, Rec candidate) throws Exception {
        String kind = interp.kind(method);
        Interpreter.Outcome o;
        try {
            o = interp.call(method, state, args, t);
        } catch (Interpreter.Refused e) {
            o = null;
        }
        var redeemerArgs = new ArrayList<Object>();
        if (kind.equals("init")) redeemerArgs.add(harness.seed());
        for (Object a : args) redeemerArgs.add(java(a));
        if (interp.usesTx(method)) redeemerArgs.add(ctx(t));
        Object redeemer = construct(kind.equals("init") ? "CreateInstance" : Character.toUpperCase(method.charAt(0)) + method.substring(1),
                redeemerArgs);
        // The arguments as an attacker may send them (raw, not re-encoded canonically): the redeemer's fields replaced.
        var encoded = (org.julclang.core.PlutusData.ConstrData) StateHarness.data(redeemer);
        var fields = new ArrayList<>(encoded.fields());
        int offset = kind.equals("init") ? 1 : 0;
        for (int i = 0; i < args.size(); i++) fields.set(offset + i, raw(args.get(i)));
        redeemer = new org.julclang.core.PlutusData.ConstrData(encoded.constructorTag(), fields);
        Ctx c = interp.usesTx(method) ? ctx(t) : null;
        var ms = new MerkleSupport(store);
        StateHarness.Verdict v;
        switch (kind) {
            case "init" -> {
                Object first = o != null ? java(o.value()) : candidate == null ? null : java(candidate);
                if (first == null) return null;
                var tx = harness.create(redeemer, first, c);
                if (hasMaps()) {
                    var p = ms.prepareCreation(harness.policy(), (Class) stateClass, first, harness.seed());
                    tx = harness.withClaims(harness.create(redeemer, p.stored(), c), p.claims());
                }
                v = harness.evaluate(tx);
            }
            case "action" -> {
                // An action's Ctx balance is what it may allocate: the instance's lovelace less the deposit (Ctx).
                Object before = java(state);
                var live = harness.live(before, t == null ? BigInteger.ZERO : t.balance());
                if (!hasMaps()) {
                    v = harness.evaluate(o != null ? harness.act(live, redeemer, java(o.value()), payouts(o.payouts()), signer, c)
                            : harness.act(live, redeemer, before, JulcList.empty(), signer, c));
                } else {
                    Object after = o != null ? nextState(run(ms, method, before, args, t)) : before;
                    var p = ms.prepare(harness.policy(), (Class) stateClass, before, after, StateHarness.LIVE_REF);
                    v = harness.evaluate(harness.withClaims(harness.act(live, redeemer, p.stored(),
                            o != null ? payouts(o.payouts()) : JulcList.empty(), signer, c), p.claims()));
                }
            }
            case "destroy" -> {
                // A destroy's balance is everything the instance holds, deposit included.
                Object before = java(state);
                var live = harness.live(before, t.balance().subtract(BigInteger.valueOf(harness.minAda())));
                var tx = harness.destroy(live, redeemer, construct("BurnInstance", List.of()),
                        o != null ? payouts(o.payouts()) : Payouts.of(signer, live.lovelace()), signer, c);
                if (hasMaps()) {
                    if (o != null) run(ms, method, before, args, t);
                    tx = harness.withClaims(tx, ms.prepare(harness.policy(), (Class) stateClass, before, null, StateHarness.LIVE_REF).claims());
                }
                v = harness.evaluate(tx);
            }
            default -> {
                return null;
            }
        }
        validatorRuns++;
        lastVerdict = v;
        return v.accepted();
    }

    private Object construct(String variant, List<Object> values) throws Exception {
        Class<?> rc = Class.forName(PKG + "." + name + "Validator$" + variant);
        var comps = rc.getRecordComponents();
        var types = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) types[i] = comps[i].getType();
        return rc.getDeclaredConstructor(types).newInstance(values.toArray());
    }

    /** All four legs (two for a view), asserted equal; returns the agreed outcome. */
    String check(String method, Rec state, List<Object> args, Tx t, byte[] signer) throws Exception {
        return check(method, state, args, t, signer, null);
    }

    String check(String method, Rec state, List<Object> args, Tx t, byte[] signer, Rec candidate) throws Exception {
        String what = name + "." + method + " " + args + " " + (t == null ? "" : t);
        String want = interpreted(method, state, args, t);
        assertEquals(want, jvm(method, state, args, t), "JVM vs interpreter: " + what);
        if (!interp.kind(method).equals("view")) {
            assertEquals(want, transition(method, state, args, t), "transition program vs interpreter: " + what);
            Boolean accepted = validator(method, state, args, t, signer, candidate);
            if (accepted != null) assertEquals(!want.equals("refused"), accepted, "validator vs interpreter: " + what + " " + lastVerdict);
        }
        calls++;
        if (want.equals("refused")) refusals++;
        return want;
    }

    /** The interpreter's next state of a successful call (to chain calls). */
    Rec next(String method, Rec state, List<Object> args, Tx t) {
        return (Rec) interp.call(method, state, args, t).value();
    }

    static PlutusData unit() {
        return ConstrPlutusData.of(0);
    }
}
