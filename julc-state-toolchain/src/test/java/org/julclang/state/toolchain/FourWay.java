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
import java.util.Arrays;
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
    /** The checked IR as exported (ADR-0018 D15), for the JavaScript leg ({@link JsLeg}). */
    final String irJson;
    /** Every call {@link #check} made, with the outcome the four legs agreed on, for the JavaScript leg. */
    final List<JsLeg.Call> jsCalls = new ArrayList<>();
    int calls, refusals, validatorRuns, rounds, batchRuns, mutantsRejected, attacksRejected, modifierRefusals;
    StateHarness.Verdict lastVerdict;

    /** The {@code .jsc} libraries' classes the program imports (ADR-0018 D9, M4): their records and variants are theirs. */
    final List<String> libraries = new ArrayList<>();
    /**
     * ADR-0018 D9 (M4b): whether a call is first answered by the reference interpreters. A call into a {@code java:}
     * library has no answer there (no reference semantics; its library is certified, with per-function JVM-vs-UPLC
     * vectors), so a contract that imports one is checked on three legs: the JVM is where the comparison starts, and the
     * transition program and the validator must agree with it. Calls that reach no Java library are still five-way.
     */
    boolean threeLegs;
    int interpreterAnswers, noAnswers;

    FourWay(String name, String jscSource, Map<String, String> transitions) throws Exception {
        this(name, jscSource, null, org.julclang.state.dsl.v2.JscModules.NONE, transitions);
    }

    /** The contract {@code name} of a build: the module {@code id}, with the {@code .jsc} libraries {@code modules} finds. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    FourWay(String name, String jscSource, String id, org.julclang.state.dsl.v2.JscModules modules, Map<String, String> transitions)
            throws Exception {
        this(name, jscSource, id, modules, org.julclang.state.dsl.v2.JavaLibraries.NONE, transitions);
    }

    /** The same, with the certified Java libraries its java: imports name (M4b). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    FourWay(String name, String jscSource, String id, org.julclang.state.dsl.v2.JscModules modules,
            org.julclang.state.dsl.v2.JavaLibraries javaLibs, Map<String, String> transitions) throws Exception {
        this.name = name;
        this.cls = Class.forName(PKG + "." + name);
        this.stateClass = Class.forName(PKG + "." + name + "$State");
        this.interp = JscV2.interpreter(jscSource, name + ".jsc", id, modules, javaLibs, HASHES);
        this.irJson = JscV2.lower(jscSource, name + ".jsc", id, modules, javaLibs).irJson();
        var jr = java.util.regex.Pattern.compile("\"import\": \"([A-Za-z0-9_.]+)\"").matcher(irJson);
        while (jr.find()) libraries.add(jr.group(1));
        var m = java.util.regex.Pattern.compile("\"library\": \"([A-Za-z0-9_.]+)\"").matcher(irJson);
        while (m.find()) libraries.add(m.group(1));
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
        return java(v, cls);
    }

    /** The same, with records and variants as nested classes of {@code c} (the port's, or its Java example's). */
    Object java(Object v, Class<?> c) {
        try {
            return switch (v) {
                case BigInteger i -> i;
                case Boolean x -> x;
                case Bytes x -> x.bytes();
                case Opt o -> o.present() ? Optional.of(java(o.value(), c)) : Optional.empty();
                case List<?> l -> {
                    if (l.isEmpty()) yield JulcList.empty();
                    var xs = new ArrayList<Object>();
                    for (Object x : l) xs.add(java(x, c));
                    yield JulcList.of(xs.toArray());
                }
                case MapV m -> {
                    // JulcMap.insert prepends: insert from the last key, so the map keeps the canonical order.
                    JulcMap<Object, Object> out = JulcMap.empty();
                    for (int i = m.keys().size() - 1; i >= 0; i--) out = out.insert(java(m.keys().get(i), c), java(m.values().get(i), c));
                    yield out;
                }
                // The stored form: the root of the contents now, no reads or writes.
                case BigMapV m -> new MerkleMap(root(m.now()), JulcList.empty(), JulcList.empty());
                case Rec r -> {
                    Class<?> rc = recordClass(c, r.type());
                    var comps = rc.getRecordComponents();
                    var types = new Class<?>[comps.length];
                    var vals = new Object[comps.length];
                    for (int i = 0; i < comps.length; i++) {
                        types[i] = comps[i].getType();
                        vals[i] = java(r.fields().get(i), c);
                    }
                    yield rc.getDeclaredConstructor(types).newInstance(vals);
                }
                default -> throw new IllegalArgumentException("value " + v);
            };
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A record's or variant's class: the program's own, else an imported {@code .jsc} or Java library's (M4). */
    Class<?> recordClass(Class<?> c, String type) throws ClassNotFoundException {
        try {
            return Class.forName(c.getName() + "$" + type);
        } catch (ClassNotFoundException e) {
            for (String lib : libraries) {
                try {
                    return Class.forName(lib + "$" + type);
                } catch (ClassNotFoundException ignored) {
                    // the next library
                }
            }
            throw e;
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

    /**
     * The interpreter's transition alone (no modifiers, semantics §2.6: what the JVM method and the transition program
     * compute), encoded as the runtime encodes a transition's result; "refused" when it fails.
     */
    String interpreted(String method, Rec state, List<Object> args, Tx t) {
        Interpreter.Outcome o;
        try {
            o = interp.transition(method, state, args, t);
        } catch (Interpreter.Refused e) {
            return "refused";
        }
        return encode(method, o);
    }

    /** An interpreter's outcome (either interpreter's: {@link JsLeg}) encoded as the runtime encodes a transition's result. */
    String encode(String method, Interpreter.Outcome o) {
        return switch (interp.kind(method)) {
            case "view" -> "ok:" + data(java(o.value())).serializeToHex();
            case "destroy" -> "ok:" + result(null, payouts(o.payouts()));
            default -> "ok:" + result(java(o.value()), payouts(o.payouts()));
        };
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
        // The transition's outcome builds the transaction; a broken modifier leaves it honest, for the validator to refuse.
        // The transition's outcome as the generated Java's values: the interpreter's, or (three legs, M4b: a Java library
        // call has no answer there) the JVM method's.
        boolean ok;
        Object nextJ = null;
        JulcList<Payout> paysJ = JulcList.empty();
        try {
            Interpreter.Outcome o = interp.transition(method, state, args, t);
            ok = true;
            nextJ = o.value() == null || kind.equals("destroy") || kind.equals("view") ? null : java(o.value());
            paysJ = payouts(o.payouts());
        } catch (Interpreter.Refused e) {
            ok = false;
        } catch (Interpreter.Limit e) {
            if (!threeLegs || !e.getMessage().contains("has no reference semantics")) throw e;
            Object r = run(new MerkleSupport(store), method, state == null ? null : java(state), args, t);
            ok = r != null;
            if (ok && kind.equals("destroy")) paysJ = (JulcList<Payout>) r;
            else if (ok) {
                nextJ = nextState(r);
                paysJ = outcomePayouts(r) == null ? JulcList.empty() : outcomePayouts(r);
            }
        }
        var redeemerArgs = new ArrayList<Object>();
        if (kind.equals("init")) redeemerArgs.add(harness.seed());
        for (Object a : args) redeemerArgs.add(java(a));
        if (interp.usesTx(method)) redeemerArgs.add(ctx(t));
        String variant = kind.equals("init") ? "CreateInstance" : Character.toUpperCase(method.charAt(0)) + method.substring(1);
        if (kind.equals("action") && batched(method) && !direct(method)) {
            // A 'batched only' action has no direct redeemer: only the batch can run it.
            Object before = java(state);
            Object next = ok ? nextJ : before;
            var v = harness.evaluate(batchTx(method, before, args, t, next, ok ? paysJ : JulcList.empty()));
            validatorRuns++;
            batchRuns++;
            lastVerdict = v;
            return v.accepted();
        }
        Object redeemer = construct(variant, redeemerArgs);
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
                Object first = ok ? nextJ : candidate == null ? null : java(candidate);
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
                if (batched(method)) {
                    // M1c: a batched action also runs as an intent, applied by a batch (both forms must agree).
                    Object next = ok ? nextJ : before;
                    JulcList<Payout> pays = ok ? paysJ : JulcList.empty();
                    var b = harness.evaluate(batchTx(method, before, args, t, next, pays));
                    if (!direct(method)) {
                        v = b;
                        break;
                    }
                    v = harness.evaluate(ok ? harness.act(live, redeemer, next, pays, signer, c)
                            : harness.act(live, redeemer, before, JulcList.empty(), signer, c));
                    assertEquals(v.accepted(), b.accepted(), name + "." + method + ": the direct call and the intent disagree\n" + v + b);
                    batchRuns++;
                } else if (!hasMaps()) {
                    v = harness.evaluate(ok ? harness.act(live, redeemer, nextJ, paysJ, signer, c)
                            : harness.act(live, redeemer, before, JulcList.empty(), signer, c));
                } else {
                    Object after = ok ? nextState(run(ms, method, before, args, t)) : before;
                    var p = ms.prepare(harness.policy(), (Class) stateClass, before, after, StateHarness.LIVE_REF);
                    v = harness.evaluate(harness.withClaims(harness.act(live, redeemer, p.stored(),
                            ok ? paysJ : JulcList.empty(), signer, c), p.claims()));
                }
            }
            case "destroy" -> {
                // A destroy's balance is everything the instance holds, deposit included.
                Object before = java(state);
                var live = harness.live(before, t.balance().subtract(BigInteger.valueOf(harness.minAda())));
                var tx = harness.destroy(live, redeemer, construct("BurnInstance", List.of()),
                        ok ? paysJ : Payouts.of(signer, live.lovelace()), signer, c);
                if (hasMaps()) {
                    if (ok) run(ms, method, before, args, t);
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

    // ------------------------------------------------------------------ batched actions (ADR-0004; M1c)

    /** The intent's lovelace on top of its payment (the processor's intent deposit). */
    static final BigInteger INTENT_DEPOSIT = BigInteger.valueOf(2_000_000);

    private static String cap(String method) {
        return Character.toUpperCase(method.charAt(0)) + method.substring(1);
    }

    private boolean exists(String cls) {
        try {
            Class.forName(cls);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Whether the action is batched, from the {@code .jsc} source's checked IR (review F8), never from the generated code
     * under test; the generated validator must agree (an intent variant {@code <M>Intent}, and a direct redeemer unless
     * {@code batched only}).
     */
    boolean batched(String method) {
        boolean source = interp.modifiers(method).stream().anyMatch(m -> m.startsWith("batched"));
        assertEquals(source, exists(PKG + "." + name + "Validator$" + cap(method) + "Intent"), name + "." + method + ": batched in the source and the validator");
        return source;
    }

    /** A direct call exists unless the action is {@code batched only} (from the source; the validator must agree). */
    boolean direct(String method) {
        boolean source = !interp.modifiers(method).contains("batched only");
        assertEquals(source, exists(PKG + "." + name + "Validator$" + cap(method)), name + "." + method + ": a direct redeemer in the source and the validator");
        return source;
    }

    @SuppressWarnings("unchecked")
    private org.julclang.state.runtime.IntentCodec<Object> intents() throws Exception {
        return (org.julclang.state.runtime.IntentCodec<Object>) Class.forName(PKG + "." + name + "Client").getField("INTENTS").get(null);
    }

    /**
     * A batch of one intent: {@code t.caller()} asks for {@code method(args)} paying {@code t.received()}, and the batch
     * proposes {@code next} and {@code pays} (the step's outcome as the test states it), over the harness's window. A
     * refused step proposes the input state, so the validator re-runs the step and must refuse it.
     */
    org.julclang.state.testkit.TxModel batchTx(String method, Object before, List<Object> args, Tx t, Object next, JulcList<Payout> pays)
            throws Exception {
        var codec = intents();
        var call = construct(cap(method) + "Intent", args.stream().map(this::java).toList(), "Validator$");
        var intent = codec.intent(harness.instanceId(), t.caller().bytes(), StateHarness.pkhAddress(t.caller().bytes()),
                StateHarness.T0.add(BigInteger.valueOf(3_600_000)), t.received(), call);
        var it = harness.liveIntent(1, intent, t.received().add(INTENT_DEPOSIT));
        var live = harness.live(before, t.balance());
        var pending = new org.julclang.state.runtime.Batcher.Pending(it.ref(), codec.read(intent), it.lovelace(), HEX.formatHex(it.token()), null);
        var stepPays = new ArrayList<Payout>();
        for (Payout po : pays) stepPays.add(po);
        var plan = new org.julclang.state.runtime.Batcher.Plan<>(next, t.balance().add(t.received()).subtract(StateHarness.total(pays)),
                List.of(pending), List.<List<Payout>>of(stepPays), Map.of());
        return harness.batch(live, codec, List.of(it), plan).tx();
    }

    // ------------------------------------------------------------------ mutation rejection (M1c)

    /** Values one step away from {@code v}: an Int ±1, a byte string with its last byte flipped, a map with an entry changed, dropped or added. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static List<Object> nearby(Object v) {
        var out = new ArrayList<Object>();
        switch (v) {
            case BigInteger i -> {
                out.add(i.add(BigInteger.ONE));
                out.add(i.subtract(BigInteger.ONE));
            }
            case byte[] b -> {
                byte[] c = b.length == 0 ? new byte[]{0} : b.clone();
                if (b.length > 0) c[c.length - 1] ^= 1;
                out.add(c);
            }
            case Boolean x -> out.add(!x);
            case JulcMap m -> {
                JulcMap<Object, Object> map = (JulcMap<Object, Object>) m;
                byte[] extra = new byte[28];
                Arrays.fill(extra, (byte) 0xEE);
                if (map.isEmpty()) {
                    out.add(map.insert(extra, BigInteger.ONE));
                } else {
                    Object k = map.keys().head();
                    Object val = map.lookup(k).get();
                    out.add(map.delete(k));
                    for (Object w : nearby(val)) out.add(map.delete(k).insert(k, w));
                }
            }
            default -> {
            }
        }
        return out;
    }

    /** Each state that differs from {@code s} in one non-map component by one step. */
    List<Object> nearbyStates(Object s) throws Exception {
        var comps = stateClass.getRecordComponents();
        var types = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) types[i] = comps[i].getType();
        var out = new ArrayList<Object>();
        for (int i = 0; i < comps.length; i++) {
            Object v = comps[i].getAccessor().invoke(s);
            for (Object w : nearby(v)) {
                var vals = new Object[comps.length];
                for (int j = 0; j < comps.length; j++) vals[j] = j == i ? w : comps[j].getAccessor().invoke(s);
                out.add(stateClass.getDeclaredConstructor(types).newInstance(vals));
            }
        }
        return out;
    }

    /**
     * Payout lists one step away from {@code pays}: an amount ±1, a recipient changed, one dropped, one more. In a
     * destroy, paying more than owed (an amount +1, one more payout) comes from the submitter's own inputs and is not an
     * attack (the validator requires each payout at least, as the attack suite's "under-paid by 1 lovelace"), so a
     * destroy's mutants only underpay; in an action every extra lovelace leaves the state output short.
     */
    static List<JulcList<Payout>> nearbyPayouts(JulcList<Payout> pays, byte[] caller) {
        return nearbyPayouts(pays, caller, true);
    }

    static List<JulcList<Payout>> nearbyPayouts(JulcList<Payout> pays, byte[] caller, boolean more) {
        var ps = new ArrayList<Payout>();
        for (Payout p : pays) ps.add(p);
        var out = new ArrayList<JulcList<Payout>>();
        for (int k = 0; k < ps.size(); k++) {
            Payout p = ps.get(k);
            byte[] other = new byte[28];
            Arrays.fill(other, (byte) 0xBB);
            for (Payout q : List.of(new Payout(p.to(), p.lovelace().add(BigInteger.ONE)), new Payout(p.to(), p.lovelace().subtract(BigInteger.ONE)),
                    new Payout(other, p.lovelace()))) {
                if (q.lovelace().signum() <= 0 || !more && q.lovelace().compareTo(p.lovelace()) > 0) continue;
                var c = new ArrayList<>(ps);
                c.set(k, q);
                out.add(list(c));
            }
            var dropped = new ArrayList<>(ps);
            dropped.remove(k);
            out.add(list(dropped));
        }
        if (more) {
            var extra = new ArrayList<>(ps);
            extra.add(new Payout(caller, BigInteger.valueOf(1_000_000)));
            out.add(list(extra));
        }
        return out;
    }

    private static JulcList<Payout> list(List<Payout> ps) {
        JulcList<Payout> l = JulcList.empty();
        for (int i = ps.size() - 1; i >= 0; i--) l = l.prepend(ps.get(i));
        return l;
    }

    /**
     * Mutation rejection: for a call the interpreter accepts, every transaction that proposes a next state one step
     * away from the interpreter's (one component changed; with maps, one extra map write too) or payouts one step away
     * (an amount ±1, another recipient, one dropped, one more) is rejected by the validator, and the accepted
     * transaction passes the attack suite ({@link org.julclang.state.testkit.Attacks}). Returns the mutants rejected.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    int mutants(String method, Rec state, List<Object> args, Tx t, byte[] signer) throws Exception {
        String kind = interp.kind(method);
        var o = interp.transition(method, state, args, t);
        var redeemerArgs = new ArrayList<Object>();
        if (kind.equals("init")) redeemerArgs.add(harness.seed());
        for (Object a : args) redeemerArgs.add(java(a));
        if (interp.usesTx(method)) redeemerArgs.add(ctx(t));
        Ctx c = interp.usesTx(method) ? ctx(t) : null;
        byte[] caller = t == null ? signer : t.caller().bytes();
        var txs = new ArrayList<org.julclang.state.testkit.TxModel>();
        org.julclang.state.testkit.TxModel accepted;
        var ms = new MerkleSupport(store);
        switch (kind) {
            case "init" -> {
                Object redeemer = construct("CreateInstance", redeemerArgs);
                Object first = java(o.value());
                if (hasMaps()) {
                    var p = ms.prepareCreation(harness.policy(), (Class) stateClass, first, harness.seed());
                    accepted = harness.withClaims(harness.create(redeemer, p.stored(), c), p.claims());
                    for (Object m : nearbyStates(p.stored())) txs.add(harness.withClaims(harness.create(redeemer, m, c), p.claims()));
                } else {
                    accepted = harness.create(redeemer, first, c);
                    for (Object m : nearbyStates(first)) txs.add(harness.create(redeemer, m, c));
                }
            }
            case "action" -> {
                Object before = java(state);
                JulcList<Payout> pays = payouts(o.payouts());
                if (batched(method)) {
                    Object next = java(o.value());
                    accepted = batchTx(method, before, args, t, next, pays);
                    for (Object m : nearbyStates(next)) txs.add(batchTx(method, before, args, t, m, pays));
                    for (var q : nearbyPayouts(pays, caller)) txs.add(batchTx(method, before, args, t, next, q));
                    break;
                }
                Object redeemer = construct(cap(method), redeemerArgs);
                var live = harness.live(before, t == null ? BigInteger.ZERO : t.balance());
                if (!hasMaps()) {
                    Object next = java(o.value());
                    accepted = harness.act(live, redeemer, next, pays, signer, c);
                    for (Object m : nearbyStates(next)) txs.add(harness.act(live, redeemer, m, pays, signer, c));
                    for (var q : nearbyPayouts(pays, caller)) txs.add(harness.act(live, redeemer, next, q, signer, c));
                } else {
                    Object after = nextState(run(ms, method, before, args, t));
                    var p = ms.prepare(harness.policy(), (Class) stateClass, before, after, StateHarness.LIVE_REF);
                    accepted = harness.withClaims(harness.act(live, redeemer, p.stored(), pays, signer, c), p.claims());
                    for (Object m : nearbyStates(p.stored())) txs.add(harness.withClaims(harness.act(live, redeemer, m, pays, signer, c), p.claims()));
                    for (var q : nearbyPayouts(pays, caller)) txs.add(harness.withClaims(harness.act(live, redeemer, p.stored(), q, signer, c), p.claims()));
                    // One extra map write the method did not make (a stranger's balance set), with honest witnesses for it.
                    var ms2 = new MerkleSupport(store);
                    Object tampered = ms2.run(() -> {
                        try {
                            Object n = nextState(invoke(method, before, args, t));
                            return extraWrite(n);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
                    var p2 = ms2.prepare(harness.policy(), (Class) stateClass, before, tampered, StateHarness.LIVE_REF);
                    txs.add(harness.withClaims(harness.act(live, redeemer, p2.stored(), pays, signer, c), p2.claims()));
                }
            }
            case "destroy" -> {
                Object before = java(state);
                Object redeemer = construct(cap(method), redeemerArgs);
                var live = harness.live(before, t.balance().subtract(BigInteger.valueOf(harness.minAda())));
                Object burn = construct("BurnInstance", List.of());
                JulcList<Payout> pays = payouts(o.payouts());
                List<org.julclang.state.api.mpf.MapClaim> claims = null;
                if (hasMaps()) {
                    run(ms, method, before, args, t);
                    claims = ms.prepare(harness.policy(), (Class) stateClass, before, null, StateHarness.LIVE_REF).claims();
                }
                accepted = harness.destroy(live, redeemer, burn, pays, signer, c);
                if (claims != null) accepted = harness.withClaims(accepted, claims);
                for (var q : nearbyPayouts(pays, caller, false)) {
                    var tx = harness.destroy(live, redeemer, burn, q, signer, c);
                    txs.add(claims != null ? harness.withClaims(tx, claims) : tx);
                }
            }
            default -> {
                return 0;
            }
        }
        var ok = harness.evaluate(accepted);
        assertTrue(ok.accepted(), name + "." + method + ": the unmutated transaction must be accepted\n" + ok);
        attacksRejected += org.julclang.state.testkit.Attacks.assertAllRejected(harness, accepted);
        int n = 0;
        for (var tx : txs) {
            var v = harness.evaluate(tx);
            assertFalse(v.accepted(), name + "." + method + ": mutant #" + n + " accepted\n" + v);
            n++;
        }
        assertTrue(n > 0, name + "." + method + ": no mutants");
        mutantsRejected += n;
        return n;
    }

    /** {@code next} with one more write to its first BigMap (a fresh key set to 1), as a cheating client would add. */
    private Object extraWrite(Object next) throws Exception {
        var comps = stateClass.getRecordComponents();
        var types = new Class<?>[comps.length];
        var vals = new Object[comps.length];
        boolean done = false;
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            vals[i] = comps[i].getAccessor().invoke(next);
            if (!done && vals[i] instanceof MerkleMap m) {
                byte[] k = new byte[28];
                Arrays.fill(k, (byte) 0xEE);
                vals[i] = MerkleMaps.putInt(m, k, BigInteger.ONE);
                done = true;
            }
        }
        return stateClass.getDeclaredConstructor(types).newInstance(vals);
    }

    private Object construct(String variant, List<Object> values) throws Exception {
        return construct(variant, values, "Validator$");
    }

    private Object construct(String variant, List<Object> values, String in) throws Exception {
        Class<?> rc = Class.forName(PKG + "." + name + in + variant);
        var comps = rc.getRecordComponents();
        var types = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) types[i] = comps[i].getType();
        return rc.getDeclaredConstructor(types).newInstance(values.toArray());
    }

    // ------------------------------------------------------------------ the Java example, as a reference (M1c review F8)

    Class<?> reference;
    String referenceInit;
    /** Calls whose outcome on the Java example differs from the port's (each test states which it expects). */
    final List<String> javaDiffs = new ArrayList<>();

    /** Also runs every vector on the Java example {@code pkg.<name>}, whose init is {@code init}, and records differences. */
    FourWay reference(String pkg, String init) throws Exception {
        this.reference = Class.forName(pkg + "." + name);
        this.referenceInit = init;
        return this;
    }

    /** The Java example's JVM outcome, encoded as {@link #jvm} encodes the port's ("refused" when it fails). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    String javaReference(String method, Rec state, List<Object> args, Tx t) throws Exception {
        String kind = interp.kind(method);
        Class<?> rs = Class.forName(reference.getName() + "$State");
        Object before = state == null ? null : java(state, reference);
        String m = kind.equals("init") ? referenceInit : method;
        Method found = null;
        for (Method x : reference.getDeclaredMethods()) if (x.getName().equals(m) && java.lang.reflect.Modifier.isStatic(x.getModifiers())) found = x;
        assertNotNull(found, reference.getName() + "." + m);
        var call = new ArrayList<Object>();
        if (before != null) call.add(before);
        for (Object a : args) call.add(java(a, reference));
        if (found.getParameterCount() > call.size()) call.add(ctx(t));
        final Method target = found;
        var ms = new MerkleSupport(store);
        Object[] out = new Object[1];
        Throwable[] failed = new Throwable[1];
        ms.run(() -> {
            try {
                out[0] = target.invoke(null, call.toArray());
            } catch (InvocationTargetException e) {
                failed[0] = e.getCause();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        if (failed[0] != null) {
            if (failed[0] instanceof RequireException || failed[0] instanceof ArithmeticException) return "refused";
            throw new IllegalStateException(reference.getName() + "." + m, failed[0]);
        }
        Object r = out[0];
        if (kind.equals("view")) return "ok:" + data(r).serializeToHex();
        if (r instanceof JulcList<?> l) return "ok:" + result(null, (JulcList<Payout>) l);
        Object next = rs.isInstance(r) ? r : r.getClass().getMethod("state").invoke(r);
        JulcList<Payout> pays = rs.isInstance(r) ? null : (JulcList<Payout>) r.getClass().getMethod("payouts").invoke(r);
        if (hasMaps()) {
            next = kind.equals("init") ? ms.prepareCreation(harness.policy(), (Class) rs, next, harness.seed()).stored()
                    : ms.prepare(harness.policy(), (Class) rs, before, next, StateHarness.LIVE_REF).stored();
        }
        return "ok:" + result(next, pays);
    }

    /**
     * All four legs (two for a view), asserted equal; returns the agreed outcome. The call is also recorded for the fifth
     * leg, the JavaScript interpreter, which {@link JsLeg#check} runs over every recorded call at the end of a suite.
     */
    String check(String method, Rec state, List<Object> args, Tx t, byte[] signer) throws Exception {
        return check(method, state, args, t, signer, null);
    }

    String check(String method, Rec state, List<Object> args, Tx t, byte[] signer, Rec candidate) throws Exception {
        // The interpreters see the keys that sign the transaction the validator evaluates (semantics §2.6: 'only f'). A
        // method that reads nothing else of the transaction is called without one on the other legs (usesCtx: false).
        Tx ti = interpreterTx(t, signer);
        String what = name + "." + method + " " + args + " " + (t == null ? "" : t);
        // The Java interpreter's outcome; a Limit (its stack ran out) propagates and fails the test (semantics §2.4).
        Interpreter.Outcome o = null;
        Interpreter.Refused refused = null;
        try {
            o = interp.call(method, state, args, ti);
        } catch (Interpreter.Refused e) {
            refused = e;
        } catch (Interpreter.Limit e) {
            // A java: library call (M4b): no answer from the interpreters; the JVM starts the comparison (three legs).
            if (!threeLegs || !e.getMessage().contains("has no reference semantics")) throw e;
            noAnswers++;
            String jv = jvm(method, state, args, t);
            if (!interp.kind(method).equals("view")) {
                assertEquals(jv, transition(method, state, args, t), "transition program vs JVM: " + what);
                Boolean accepted = validator(method, state, args, t, signer, candidate);
                if (accepted != null) assertEquals(!jv.equals("refused"), accepted, "validator vs JVM: " + what + " " + lastVerdict);
            }
            lastRefusal = null;
            calls++;
            if (jv.equals("refused")) refusals++;
            return jv;
        }
        interpreterAnswers++;
        String want = refused != null ? "refused" : encode(method, o);
        lastRefusal = refused == null ? null : refused.refusal();
        // The JVM method and the transition program compute the transition alone: a broken modifier (§2.6) is the
        // validator's to refuse, so they are compared with the interpreter's transition, which then succeeds.
        boolean modifier = refused != null && refused.refusal().startsWith("modifier:");
        String body = modifier ? interpreted(method, state, args, ti) : want;
        if (o != null) JsLeg.canonical(o.value(), what);
        assertEquals(body, jvm(method, state, args, t), "JVM vs interpreter: " + what);
        jsCalls.add(JsLeg.call(method, state, args, ti, want, o, refused, what));
        if (reference != null) {
            String ref = javaReference(method, state, args, t);
            if (!ref.equals(body)) javaDiffs.add(what + ": the Java example " + (ref.equals("refused") ? "refuses" : "accepts") + ", the port "
                    + (body.equals("refused") ? "refuses" : "accepts") + (ref.startsWith("ok:") && body.startsWith("ok:") ? " (different results)" : ""));
        }
        if (!interp.kind(method).equals("view")) {
            assertEquals(body, transition(method, state, args, t), "transition program vs interpreter: " + what);
            Boolean accepted = validator(method, state, args, t, signer, candidate);
            if (accepted != null) assertEquals(!want.equals("refused"), accepted, "validator vs interpreter: " + what + " " + lastVerdict);
        }
        calls++;
        if (want.equals("refused")) refusals++;
        return want;
    }

    /** The transaction the interpreters see: {@code t} signed by {@code signer}, or the signer's alone when {@code t} is null. */
    static Tx interpreterTx(Tx t, byte[] signer) {
        if (signer == null) return t;
        return t != null ? t.signedBy(List.of(Bytes.of(signer)))
                : new Tx(Bytes.of(signer), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
    }

    /** The class of the last refusal {@link #check} saw (semantics §2.5), or null when it was accepted. */
    String lastRefusal;

    /**
     * A call that breaks one of the method's modifiers ({@code only f}: signed by a stranger who is the caller;
     * {@code after f}/{@code before f}: a window on the wrong side of, or straddling, the bound), on all five legs
     * (ADR-0018 D10, semantics §2.6; M1c review S2/F2, M1 integration review F1): both interpreters refuse it with the
     * modifier's class, the JVM method and the transition program compute the transition (they leave the modifiers to
     * the validator) and agree with the interpreter's transition, and the validator refuses it.
     */
    void modifierRefused(String method, Rec state, List<Object> args, Tx t, byte[] signer) throws Exception {
        String what = name + "." + method + " " + args + " " + (t == null ? "" : t) + " signed by " + HEX.formatHex(signer).substring(0, 4);
        assertTrue(interpreted(method, state, args, interpreterTx(t, signer)).startsWith("ok:"),
                "the transition accepts it (stated): " + what);
        assertEquals("refused", check(method, state, args, t, signer), what);
        assertTrue(lastRefusal.startsWith("modifier:"), "refused by the modifier: " + what + " " + lastRefusal);
        modifierRefusals++;
    }

    /** The interpreter's next state of a successful transition (to chain calls; modifiers are not checked). */
    Rec next(String method, Rec state, List<Object> args, Tx t) {
        return (Rec) interp.transition(method, state, args, t).value();
    }

    static PlutusData unit() {
        return ConstrPlutusData.of(0);
    }
}
