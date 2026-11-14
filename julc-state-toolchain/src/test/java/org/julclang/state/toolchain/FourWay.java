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
import org.julclang.state.dsl.v2.Interpreter.AddrV;
import org.julclang.state.dsl.v2.Interpreter.CredV;
import org.julclang.state.dsl.v2.Interpreter.FundsV;
import org.julclang.state.dsl.v2.Interpreter.StakeHash;
import org.julclang.state.dsl.v2.Interpreter.StakePtr;
import org.julclang.state.dsl.v2.Interpreter.ValueV;
import org.julclang.state.dsl.v2.Interpreter.InstV;
import org.julclang.state.dsl.v2.Interpreter.DepositV;
import org.julclang.state.dsl.v2.Interpreter.DepositOut;
import org.julclang.state.api.Deposit;
import org.julclang.state.api.DepositClaim;
import org.julclang.state.api.ForeignInstance;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.StakingCredential;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.Funds;
import org.julclang.state.api.Send;
import org.julclang.state.runtime.Transition;
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

    /** The package of the generated classes, and the class loader that holds them (examples/dsl's: {@link #PKG}). */
    final String pkg;
    final ClassLoader loader;

    /** A class of the contract's build, by binary name, from {@link #loader}. */
    Class<?> load(String binaryName) throws ClassNotFoundException {
        return Class.forName(binaryName, true, loader);
    }
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
        this(name, jscSource, transitions, null);
    }

    /**
     * M2b: a contract deployed with {@code params} (an interpreter {@code Config} record), as every leg sees it: both
     * interpreters read it as {@code params.f}, the JVM method and the transition program take it, and the harness's
     * script is applied to it ({@code StateHarness.of(spec, params)}).
     */
    FourWay(String name, String jscSource, Map<String, String> transitions, Rec params) throws Exception {
        this(name, jscSource, transitions, params, org.julclang.state.dsl.v2.JscModules.NONE);
    }

    /** M2c: a contract that imports others ({@code import contract}), which {@code modules} holds (by bare file name). */
    FourWay(String name, String jscSource, Map<String, String> transitions, Rec params, org.julclang.state.dsl.v2.JscModules modules) throws Exception {
        this(name, jscSource, modules == org.julclang.state.dsl.v2.JscModules.NONE ? null : name + ".jsc", modules,
                org.julclang.state.dsl.v2.JavaLibraries.NONE, transitions, params);
    }

    /** The contract {@code name} of a build: the module {@code id}, with the {@code .jsc} libraries {@code modules} finds (M4). */
    FourWay(String name, String jscSource, String id, org.julclang.state.dsl.v2.JscModules modules, Map<String, String> transitions)
            throws Exception {
        this(name, jscSource, id, modules, org.julclang.state.dsl.v2.JavaLibraries.NONE, transitions);
    }

    /** The same, with the certified Java libraries its java: imports name (M4b). */
    FourWay(String name, String jscSource, String id, org.julclang.state.dsl.v2.JscModules modules,
            org.julclang.state.dsl.v2.JavaLibraries javaLibs, Map<String, String> transitions) throws Exception {
        this(name, jscSource, id, modules, javaLibs, transitions, null);
    }

    /** Every leg of a contract: its module id and modules (M2c, M4), its Java libraries (M4b) and its params (M2b). */
    FourWay(String name, String jscSource, String id, org.julclang.state.dsl.v2.JscModules modules,
            org.julclang.state.dsl.v2.JavaLibraries javaLibs, Map<String, String> transitions, Rec params) throws Exception {
        this(PKG, FourWay.class.getClassLoader(), name, jscSource, id, modules, javaLibs, transitions, params);
    }

    /**
     * The same, for a contract whose generated classes are in package {@code pkg} of {@code loader}: a contract built
     * while the tests run (the five-way fuzz test's generated contracts, compiled by javac with the processors as Gradle
     * compiles examples/dsl), rather than one of examples/dsl on the test class path.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    FourWay(String pkg, ClassLoader loader, String name, String jscSource, String id, org.julclang.state.dsl.v2.JscModules modules,
            org.julclang.state.dsl.v2.JavaLibraries javaLibs, Map<String, String> transitions, Rec params) throws Exception {
        this.pkg = pkg;
        this.loader = loader;
        this.name = name;
        this.cls = load(pkg + "." + name);
        this.stateClass = load(pkg + "." + name + "$State");
        Interpreter i = JscV2.interpreter(jscSource, name + ".jsc", id, modules, javaLibs, HASHES);
        this.params = params;
        this.interp = params == null ? i : i.withParams(params);
        this.irJson = JscV2.lower(jscSource, name + ".jsc", id, modules, javaLibs).irJson();
        var jr = java.util.regex.Pattern.compile("\"import\": \"([A-Za-z0-9_.]+)\"").matcher(irJson);
        while (jr.find()) libraries.add(jr.group(1));
        var lm = java.util.regex.Pattern.compile("\"library\": \"([A-Za-z0-9_.]+)\"").matcher(irJson);
        while (lm.find()) libraries.add(lm.group(1));
        this.transitions = transitions;
        var spec = (org.julclang.state.runtime.ContractSpec) load(pkg + "." + name + "Client").getField("SPEC").get(null);
        this.harness = (StateHarness<Object>) (params == null ? StateHarness.of(spec) : StateHarness.of(spec, java(params)));
        this.events = interp.methods().keySet().stream().anyMatch(interp::carriesEvents);
        this.bigMaps = interp.bigMaps();
        contents.put(HEX.formatHex(Mpf.emptyRoot()), Map.of());
        // M2: an asset-aware contract (a method takes Funds) carries sends, its mint and what it accepted on every leg.
        boolean a = false;
        for (Method m : cls.getDeclaredMethods()) {
            var ps = m.getParameterTypes();
            a |= ps.length > 0 && ps[ps.length - 1] == Funds.class;
        }
        // M2c: a contract that deposits (a result record with deposits) is asset-aware too (its step carries them).
        for (Class<?> nested : cls.getDeclaredClasses()) {
            if (nested.isRecord()) for (var rc : nested.getRecordComponents()) a |= rc.getName().equals("deposits");
        }
        this.assets = a;
    }

    /** Whether a method takes Funds (M2): an asset-aware contract's legs carry its native assets. */
    final boolean assets;

    /** The deploy-time params (M2b), or null. */
    final Rec params;

    /** Whether a result record of the contract has events (M2b): every step then carries them, and those redeemers too. */
    final boolean events;

    /**
     * The keys that sign the next call's transaction and the scripts that approve it (M2b: quorum, authorized), for the
     * next {@link #check} or {@link #mutants} only; null: the signer alone, no approvals.
     */
    private List<byte[]> signers, approvers;

    FourWay as(List<byte[]> signers, List<byte[]> approvers) {
        this.signers = signers;
        this.approvers = approvers;
        return this;
    }

    /** The transaction the interpreters see: {@link #interpreterTx}, then signed by {@link #signers} and approved by {@link #approvers}. */
    Tx itx(Tx t, byte[] signer) {
        Tx x = interpreterTx(t, signer);
        if (signers != null) {
            if (x == null) {
                x = new Tx(Bytes.of(signers.isEmpty() ? new byte[28] : signers.get(0)), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
            }
            // The caller is always among the signers (semantics §2, #80 review S6): M2b's signers are added beside it.
            var keys = new ArrayList<Bytes>(signers.stream().map(Bytes::of).toList());
            if (!keys.contains(x.caller())) keys.add(x.caller());
            x = x.signedBy(keys);
        }
        if (approvers != null && x != null) x = x.approvedBy(approvers.stream().map(Bytes::of).toList());
        return x;
    }

    /** The redeemer tag of a spend method: actions in source order (not {@code batched only}), then destroys (ADR-0009 D3). */
    int spendTag(String method) {
        var order = new ArrayList<String>();
        interp.methods().forEach((m, k) -> {
            if (k.equals("action") && !interp.modifiers(m).contains("batched only")) order.add(m);
        });
        interp.methods().forEach((m, k) -> {
            if (k.equals("destroy")) order.add(m);
        });
        return order.indexOf(method);
    }

    /**
     * The validator's transaction as signed and approved (M2b): the signers replaced by {@link #signers}; each approver a
     * zero withdrawal whose Approvals redeemer names this contract, instance and action (as examples/treasury's Guardian
     * approves). Then evaluated.
     */
    StateHarness.Verdict evaluate(String method, org.julclang.state.testkit.TxModel tx) {
        return harness.evaluate(signedAndApproved(method, tx));
    }

    /** {@code tx} with {@link #evaluate(String, org.julclang.state.testkit.TxModel)}'s signers and approvers. */
    org.julclang.state.testkit.TxModel signedAndApproved(String method, org.julclang.state.testkit.TxModel tx) {
        if (signers != null) {
            tx.signers.clear();
            for (byte[] k : signers) tx.signers.add(k);
        }
        if (approvers != null && !interp.kind(method).equals("init")) {
            var a = new org.julclang.state.api.Approval(harness.policy(), harness.instanceId(), BigInteger.valueOf(spendTag(method)));
            var data = StateHarness.data(new org.julclang.state.api.Approvals(JulcList.<org.julclang.state.api.Approval>empty().prepend(a)));
            for (byte[] h : approvers) tx.withdrawals.put(new Credential.ScriptCredential(new ScriptHash(h)), data);
        }
        return tx;
    }

    /** Whether a method of the generated contract takes the params (M2b). */
    boolean takesParams(String method) {
        for (Class<?> c : method(method).getParameterTypes()) if (c.getSimpleName().equals("Config") && c.getEnclosingClass() == cls) return true;
        return false;
    }

    /** An interpreter outcome's events as the generated Java's list (empty when none). */
    @SuppressWarnings("unchecked")
    JulcList<Object> events(Interpreter.Outcome o) {
        return o == null ? JulcList.empty() : (JulcList<Object>) java(o.events());
    }

    /**
     * A transition's result as the runtime encodes it: Constr 0 [Some(next) | None, payouts], and (M2b) its events when the
     * contract has any (the step's last field).
     */
    String encoded(Object next, JulcList<Payout> pays, JulcList<?> evs) {
        if (!events) return result(next, pays);
        var ps = new ArrayList<PlutusData>();
        if (pays != null) for (Payout p : pays) ps.add(data(p));
        var es = new ArrayList<PlutusData>();
        if (evs != null) for (Object e : evs) es.add(data(e));
        PlutusData n = next == null ? ConstrPlutusData.of(1) : ConstrPlutusData.of(0, data(next));
        return ConstrPlutusData.of(0, n, com.bloxbean.cardano.client.plutus.spec.ListPlutusData.builder().plutusDataList(ps).build(),
                com.bloxbean.cardano.client.plutus.spec.ListPlutusData.builder().plutusDataList(es).build()).serializeToHex();
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
                case ValueV x -> value(x);
                case CredV x -> credential(x);
                case AddrV x -> new Address(credential(x.payment()), x.stake().present() ? Optional.of(staking(x.stake().value())) : Optional.empty());
                // M2c: an instance of another contract (a params field), the API's ForeignInstance.
                case InstV x -> new ForeignInstance(x.policy().bytes(), x.id().bytes());
                case Rec r -> {
                    // The Java example's state record may have another name (dex's Pool; M2).
                    String type = c == reference && r.type().equals("State") ? referenceState : r.type();
                    // M2c: another contract's type (Oracle.State) is a nested class of that contract, in the same package;
                    // M4: a library's type is a nested class of its library (recordClass).
                    Class<?> rc = type.contains(".") ? load(c.getPackageName() + "." + type.replace('.', '$'))
                            : recordClass(c, type);
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
            return load(c.getName() + "$" + type);
        } catch (ClassNotFoundException e) {
            for (String lib : libraries) {
                try {
                    return load(lib + "$" + type);
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
            // M2c (D5): a value as data, in its wire form: each policy's token names by their encoding (shorter first, then
            // bytewise), as cardano-client-lib writes a datum; written here from the rule, independently of ValueAdapter.toWire.
            case ValueV x -> {
                var ps = new ArrayList<org.julclang.core.PlutusData.Pair>();
                for (var p : x.entries().entrySet()) {
                    var names = new ArrayList<>(p.getValue().keySet());
                    names.sort(java.util.Comparator.comparingInt(String::length).thenComparing(java.util.Comparator.naturalOrder()));
                    var ts = new ArrayList<org.julclang.core.PlutusData.Pair>();
                    for (String nm : names) ts.add(new org.julclang.core.PlutusData.Pair(org.julclang.core.PlutusData.bytes(HEX.parseHex(nm)),
                            org.julclang.core.PlutusData.integer(p.getValue().get(nm))));
                    ps.add(new org.julclang.core.PlutusData.Pair(org.julclang.core.PlutusData.bytes(HEX.parseHex(p.getKey())),
                            new org.julclang.core.PlutusData.MapData(ts)));
                }
                yield new org.julclang.core.PlutusData.MapData(ps);
            }
            case CredV x -> org.julclang.core.PlutusData.constr(x.script() ? 1 : 0, org.julclang.core.PlutusData.bytes(x.hash().bytes()));
            case AddrV x -> org.julclang.core.PlutusData.constr(0, raw(x.payment()), x.stake().present()
                    ? org.julclang.core.PlutusData.constr(0, x.stake().value() instanceof StakeHash h ? org.julclang.core.PlutusData.constr(0, raw(h.credential()))
                    : org.julclang.core.PlutusData.constr(1, org.julclang.core.PlutusData.integer(((StakePtr) x.stake().value()).slot()),
                            org.julclang.core.PlutusData.integer(((StakePtr) x.stake().value()).txIndex()),
                            org.julclang.core.PlutusData.integer(((StakePtr) x.stake().value()).certIndex())))
                    : org.julclang.core.PlutusData.constr(1));
            // M2c: an instance, as the API's ForeignInstance encodes (Constr 0 [policy, instance]).
            case InstV x -> org.julclang.core.PlutusData.constr(0, org.julclang.core.PlutusData.bytes(x.policy().bytes()),
                    org.julclang.core.PlutusData.bytes(x.id().bytes()));
            default -> throw new IllegalArgumentException("no argument form for " + v);
        };
    }

    // ------------------------------------------------------------------ native assets (M2)

    /** An interpreter value as the ledger's (canonical, in ledger order: Assets builds it so). */
    static Value value(ValueV v) {
        Value out = Assets.none();
        for (var p : v.entries().entrySet()) {
            for (var t : p.getValue().entrySet()) out = Assets.add(out, Assets.of(HEX.parseHex(p.getKey()), HEX.parseHex(t.getKey()), t.getValue()));
        }
        return out;
    }

    /** A ledger value as the interpreter's. */
    static ValueV valueV(Value v) {
        var m = new TreeMap<String, TreeMap<String, BigInteger>>();
        Assets.read(v).forEach((p, ts) -> m.put(p, new TreeMap<>(ts)));
        return new ValueV(m);
    }

    static Credential credential(CredV c) {
        return c.script() ? new Credential.ScriptCredential(new ScriptHash(c.hash().bytes())) : new Credential.PubKeyCredential(new PubKeyHash(c.hash().bytes()));
    }

    static StakingCredential staking(Object s) {
        return s instanceof StakeHash h ? new StakingCredential.StakingHash(credential(h.credential()))
                : new StakingCredential.StakingPtr(((StakePtr) s).slot(), ((StakePtr) s).txIndex(), ((StakePtr) s).certIndex());
    }

    /** The transaction with the native assets the call sees: offered and held, under this harness's policy and instance. */
    Tx funds(Tx t, ValueV received, ValueV held) {
        return t.withFunds(new FundsV(received, held, Bytes.of(harness.policy()), Bytes.of(harness.instanceId())));
    }

    /** The Funds a JVM method gets for a transaction (none offered and none held when it does not say). */
    Funds javaFunds(Tx t) {
        FundsV f = t.funds();
        // M2c: the deposits a receiving action sees, as the registry's claims (on this call's output, from the input the
        // harness spends, each memo as D5 encodes it; an untyped receiver's memo is any data).
        JulcList<DepositClaim> ds = JulcList.empty();
        for (int k = f.deposits().size() - 1; k >= 0; k--) {
            DepositV d = f.deposits().get(k);
            ds = ds.prepend(new DepositClaim(BigInteger.ZERO, depositor(k), value(d.value()), rawMemos != null ? rawMemos.get(k) : memoData(d.memo())));
        }
        return new Funds(f.policy().bytes(), f.self().bytes(), value(f.received()), value(f.held()), ds);
    }

    /** While {@link #jvmRaw} runs: the deposits' memos as raw data, in place of the honest ones. */
    private List<org.julclang.core.PlutusData> rawMemos;

    /**
     * M2c: the JVM leg of a receiving call whose deposits' memos are raw data ({@code memos}, one per deposit of {@code t}),
     * as {@link #receiverRaw} hands them to the transition program: "refused" (a RequireException or an arithmetic failure),
     * the step as the other legs encode it, or the exception the generated Java threw otherwise ("threw: …").
     */
    String jvmRaw(String method, Rec state, Tx t, List<org.julclang.core.PlutusData> memos) throws Exception {
        rawMemos = memos;
        lastJvmThrow = null;
        try {
            return jvm(method, state, List.of(), t);
        } catch (RuntimeException e) {
            lastJvmThrow = e;
            return "threw: " + e;
        } finally {
            rawMemos = null;
        }
    }

    /** The last JVM call's raw result (null when it refused): its next state is {@code nextState(r)}. */
    Object lastJvmResult;

    /** The next state of a JVM result (the record itself, or an outcome's state). */
    static Object nextStateOf(Object r) throws Exception {
        return nextState(r);
    }

    /** The exception the last {@link #jvmRaw} threw (its class and stack, for a caller that classifies it), or null. */
    RuntimeException lastJvmThrow;

    /** M2c: the k-th depositor's input in a receiver's transaction (the registry names it as a claim's by). */
    static org.julclang.ledger.TxOutRef depositor(int k) {
        return new org.julclang.ledger.TxOutRef(new org.julclang.ledger.TxId(StateHarness.fill(32, 0x50 + k)), BigInteger.ZERO);
    }

    /** M2c: a memo as data: D5's encoding of the interpreter's value; an untyped receiver's (null) as Constr 0 []. */
    org.julclang.core.PlutusData memoData(Object memo) {
        return memo == null ? org.julclang.core.PlutusData.constr(0) : raw(memo);
    }

    /**
     * Funds as data, as the runtime writes them: cardano-client-lib's canonical CBOR, which orders a policy's token names
     * by their encoding (shorter first), not the ledger's way ({@code 01} before {@code 00ff}). The transition program
     * converts them back (ValueAdapter.fundsFromWire), as the validator converts the offered assets (review r1, C1).
     */
    org.julclang.core.PlutusData fundsData(Tx t) {
        return StateHarness.data(javaFunds(t));
    }

    /** Whether a method of the generated contract takes Funds. */
    boolean takesFunds(String method) {
        var ps = method(method).getParameterTypes();
        return ps.length > 0 && ps[ps.length - 1] == Funds.class;
    }

    JulcList<Send> sends(List<Interpreter.SendV> ss) {
        if (ss.isEmpty()) return JulcList.empty();
        var xs = new ArrayList<Send>();
        for (var x : ss) {
            AddrV a = x.to();
            // M2c: a datum, encoded independently here by D5's rules (J17), inline.
            OutputDatum d = x.datum() == null ? new OutputDatum.NoOutputDatum() : new OutputDatum.OutputDatumInline(StateHarness.data(raw(x.datum())));
            xs.add(new Send(new Address(credential(a.payment()), a.stake().present() ? Optional.of(staking(a.stake().value())) : Optional.empty()),
                    value(x.value()), d));
        }
        return JulcList.of(xs.toArray(new Send[0]));
    }

    /** M2c: an outcome's deposits as the API's Deposit list (each memo encoded independently by D5's rules). */
    JulcList<Deposit> deposits(List<DepositOut> ds) {
        if (ds == null || ds.isEmpty()) return JulcList.empty();
        var xs = new ArrayList<Deposit>();
        for (var d : ds) xs.add(new Deposit(d.target().policy().bytes(), d.target().id().bytes(), value(d.value()), StateHarness.data(raw(d.memo()))));
        return JulcList.of(xs.toArray(new Deposit[0]));
    }

    /**
     * An asset-aware transition's result, as its transition program returns it (a step): Constr 0 [next as an option,
     * payouts, sends, mint, accepted, deposits], encoded by JuLC's writer, which keeps a value's ledger order.
     */
    static String step(Object next, JulcList<Payout> payouts, JulcList<Send> sends, Value mint, Value accepted) {
        return step(next, payouts, sends, mint, accepted, null);
    }

    /** The same, with (M2b) the events last when {@code evs} is not null (a contract with events). */
    static String step(Object next, JulcList<Payout> payouts, JulcList<Send> sends, Value mint, Value accepted, JulcList<?> evs) {
        return step(next, payouts, sends, mint, accepted, evs, null);
    }

    /** The same, with (M2c) the deposits in their place (empty when null). */
    static String step(Object next, JulcList<Payout> payouts, JulcList<Send> sends, Value mint, Value accepted, JulcList<?> evs,
                       JulcList<Deposit> deposits) {
        var ps = new ArrayList<org.julclang.core.PlutusData>();
        if (payouts != null) for (Payout p : payouts) ps.add(StateHarness.data(p));
        var ss = new ArrayList<org.julclang.core.PlutusData>();
        if (sends != null) {
            for (Send x : sends) ss.add(org.julclang.core.PlutusData.constr(0, x.to().toPlutusData(), x.value().toPlutusData(), x.datum().toPlutusData()));
        }
        var n = next == null ? org.julclang.core.PlutusData.constr(1) : org.julclang.core.PlutusData.constr(0, StateHarness.data(next));
        var fs = new ArrayList<>(List.of(n, new org.julclang.core.PlutusData.ListData(ps), new org.julclang.core.PlutusData.ListData(ss),
                (mint == null ? Assets.none() : mint).toPlutusData(), (accepted == null ? Assets.none() : accepted).toPlutusData(),
                new org.julclang.core.PlutusData.ListData(depositsData(deposits))));
        if (evs != null) {
            var es = new ArrayList<org.julclang.core.PlutusData>();
            for (Object e : evs) es.add(StateHarness.data(e));
            fs.add(new org.julclang.core.PlutusData.ListData(es));
        }
        var d = new org.julclang.core.PlutusData.ConstrData(0, fs);
        return HEX.formatHex(org.julclang.core.cbor.PlutusDataCborEncoder.encode(d));
    }

    /**
     * M2c: deposits as a transition program's step holds them: each value as the method returns it (the ledger's order,
     * as a step's sends, mint and accepted are written here), each memo the data the method built (a Value in it in its
     * wire form, D5).
     */
    static List<org.julclang.core.PlutusData> depositsData(JulcList<Deposit> ds) {
        var out = new ArrayList<org.julclang.core.PlutusData>();
        if (ds != null) {
            for (Deposit d : ds) {
                out.add(org.julclang.core.PlutusData.constr(0, org.julclang.core.PlutusData.bytes(d.policy()),
                        org.julclang.core.PlutusData.bytes(d.instance()), d.value().toPlutusData(), d.memo()));
            }
        }
        return out;
    }

    /** A component of a result record, or null when the record has none. */
    static Object component(Object r, String name) {
        try {
            return r.getClass().getMethod(name).invoke(r);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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
        if (assets && !interp.kind(method).equals("view")) {
            return "ok:" + step(interp.kind(method).equals("destroy") ? null : java(o.value()), payouts(o.payouts()), sends(o.sends()),
                    value(o.mint()), value(o.accepted()), events ? events(o) : null, deposits(o.deposits()));
        }
        return switch (interp.kind(method)) {
            case "view" -> "ok:" + data(java(o.value())).serializeToHex();
            case "destroy" -> "ok:" + encoded(null, payouts(o.payouts()), events(o));
            default -> "ok:" + encoded(java(o.value()), payouts(o.payouts()), events(o));
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
        // M2b: the params directly after the state (first in an init).
        if (takesParams(method)) call.add(java(params));
        // M2c: the read states directly after the params (ADR-0011 D2), as the transaction's reference inputs give them.
        for (String f : interp.reads(method)) call.add(java(t.reads().get(f)));
        for (Object a : args) call.add(java(a));
        if (interp.usesTx(method)) call.add(ctx(t));
        if (takesFunds(method)) call.add(javaFunds(t));
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
    private JulcList<Payout> outcomePayouts(Object r) {
        if (r instanceof JulcList<?> l) return (JulcList<Payout>) l;
        if (stateClass.isInstance(r)) return null;
        return (JulcList<Payout>) component(r, "payouts");
    }

    /** A JVM result's events (M2b), or null when it has none. */
    private JulcList<?> outcomeEvents(Object r) {
        if (r instanceof JulcList<?> || stateClass.isInstance(r)) return null;
        return (JulcList<?>) component(r, "events");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    String jvm(String method, Rec state, List<Object> args, Tx t) throws Exception {
        String kind = interp.kind(method);
        Object before = state == null ? null : java(state);
        var ms = new MerkleSupport(store);
        Object r = run(ms, method, before, args, t);
        lastJvmResult = r;
        if (r == null) return "refused";
        if (kind.equals("view")) return "ok:" + data(r).serializeToHex();
        if (assets) return "ok:" + jvmStep(r, kind, stateClass, events);
        if (r instanceof JulcList<?> l) return "ok:" + encoded(null, (JulcList<Payout>) l, null);
        if (kind.equals("destroy")) return "ok:" + encoded(null, outcomePayouts(r), outcomeEvents(r));
        Object next = nextState(r);
        if (hasMaps()) {
            // The state the runtime stores: each map's new root, its witnesses dropped.
            next = kind.equals("init") ? ms.prepareCreation(harness.policy(), (Class) stateClass, next, harness.seed()).stored()
                    : ms.prepare(harness.policy(), (Class) stateClass, before, next, StateHarness.LIVE_REF).stored();
        }
        return "ok:" + encoded(next, outcomePayouts(r), outcomeEvents(r));
    }

    /** An asset-aware method's JVM result as a step (M2): the next state, payouts, sends, mint and accepted, read by name. */
    static String jvmStep(Object r, String kind, Class<?> stateClass) {
        return jvmStep(r, kind, stateClass, false);
    }

    /** The same; with {@code events} (M2b), the step's events last. */
    @SuppressWarnings("unchecked")
    static String jvmStep(Object r, String kind, Class<?> stateClass, boolean events) {
        JulcList<?> none = events ? JulcList.empty() : null;
        if (r instanceof JulcList<?> l) return step(null, (JulcList<Payout>) l, null, null, null, none);
        Object next = kind.equals("destroy") ? null : stateClass.isInstance(r) ? r : component(r, "state");
        if (stateClass.isInstance(r)) return step(next, null, null, null, null, none);
        JulcList<?> evs = events ? (JulcList<?>) component(r, "events") : null;
        return step(next, (JulcList<Payout>) component(r, "payouts"), (JulcList<Send>) component(r, "sends"), (Value) component(r, "mint"),
                (Value) component(r, "accepted"), events && evs == null ? none : evs, (JulcList<Deposit>) component(r, "deposits"));
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
            // M2: a method with Funds takes them first in its arguments' record (the transition generator's leading fields).
            var in = rawArgs(args);
            if (takesFunds(method)) {
                var fs = new ArrayList<org.julclang.core.PlutusData>(List.of(fundsData(t)));
                fs.addAll(((org.julclang.core.PlutusData.ConstrData) in).fields());
                in = new org.julclang.core.PlutusData.ConstrData(0, fs);
            }
            // M2c: the read states lead the Funds (the transition generator's leading fields: params, reads, funds).
            if (!interp.reads(method).isEmpty()) {
                var fs = new ArrayList<org.julclang.core.PlutusData>();
                // As an attacker may write them (raw, not re-encoded canonically): a read state's map in its own order.
                for (String f : interp.reads(method)) fs.add(raw(t.reads().get(f)));
                fs.addAll(((org.julclang.core.PlutusData.ConstrData) in).fields());
                in = new org.julclang.core.PlutusData.ConstrData(0, fs);
            }
            // M2b: the params lead the arguments' record (the transition generator's joustParams), before the Funds.
            if (takesParams(method)) {
                var fs = new ArrayList<org.julclang.core.PlutusData>(List.of(raw(params)));
                fs.addAll(((org.julclang.core.PlutusData.ConstrData) in).fields());
                in = new org.julclang.core.PlutusData.ConstrData(0, fs);
            }
            return evaluateRaw(program, List.of(julc(state == null ? NONE : data(java(state))), julc(NONE), in, julc(ctxData)));
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
        String jvmSame = out == null ? "refused" : interp.kind(method).equals("destroy") ? "ok:" + encoded(null, outcomePayouts(out), outcomeEvents(out))
                : "ok:" + encoded(nextState(out), outcomePayouts(out), outcomeEvents(out));
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
        // M2: the interpreter's outcome also carries effects and events (null on a refusal, or with no answer: three legs).
        Interpreter.Outcome o = null;
        try {
            o = interp.transition(method, state, args, t);
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
        if (assets) return validatorAssets(method, state, args, t, signer, candidate, o);
        var redeemerArgs = new ArrayList<Object>();
        if (kind.equals("init")) redeemerArgs.add(harness.seed());
        for (Object a : args) redeemerArgs.add(java(a));
        if (interp.usesTx(method)) redeemerArgs.add(ctx(t));
        // M2b: the events the method returns, last (a refused call proposes none).
        if (interp.carriesEvents(method)) redeemerArgs.add(events(o));
        String variant = kind.equals("init") ? "CreateInstance" : Character.toUpperCase(method.charAt(0)) + method.substring(1);
        if (kind.equals("action") && batched(method) && !direct(method)) {
            // A 'batched only' action has no direct redeemer: only the batch can run it.
            Object before = java(state);
            Object next = ok ? nextJ : before;
            var v = evaluate(method, batchTx(method, before, args, t, next, ok ? paysJ : JulcList.empty()));
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
                v = evaluate(method, t, signer, tx);
            }
            case "action" -> {
                // An action's Ctx balance is what it may allocate: the instance's lovelace less the deposit (Ctx).
                Object before = java(state);
                var live = harness.live(before, t == null ? BigInteger.ZERO : t.balance());
                if (batched(method)) {
                    // M1c: a batched action also runs as an intent, applied by a batch (both forms must agree).
                    Object next = ok ? nextJ : before;
                    JulcList<Payout> pays = ok ? paysJ : JulcList.empty();
                    var b = evaluate(method, batchTx(method, before, args, t, next, pays));
                    if (!direct(method)) {
                        v = b;
                        break;
                    }
                    v = evaluate(method, t, signer, ok ? harness.act(live, redeemer, next, pays, signer, c)
                            : harness.act(live, redeemer, before, JulcList.empty(), signer, c));
                    assertEquals(v.accepted(), b.accepted(), name + "." + method + ": the direct call and the intent disagree\n" + v + b);
                    batchRuns++;
                } else if (!hasMaps()) {
                    v = evaluate(method, t, signer, withReads(method, ok ? harness.act(live, redeemer, nextJ, paysJ, signer, c)
                            : harness.act(live, redeemer, before, JulcList.empty(), signer, c), t));
                } else {
                    Object after = ok ? nextState(run(ms, method, before, args, t)) : before;
                    var p = ms.prepare(harness.policy(), (Class) stateClass, before, after, StateHarness.LIVE_REF);
                    v = evaluate(method, t, signer, harness.withClaims(harness.act(live, redeemer, p.stored(),
                            ok ? paysJ : JulcList.empty(), signer, c), p.claims()));
                }
            }
            case "destroy" -> {
                // A destroy's balance is everything the instance holds, deposit included.
                Object before = java(state);
                var live = harness.live(before, t.balance().subtract(BigInteger.valueOf(harness.minAda())));
                var tx = withReads(method, harness.destroy(live, redeemer, construct("BurnInstance", List.of()),
                        ok ? paysJ : Payouts.of(signer, live.lovelace()), signer, c), t);
                if (hasMaps()) {
                    if (ok) run(ms, method, before, args, t);
                    tx = harness.withClaims(tx, ms.prepare(harness.policy(), (Class) stateClass, before, null, StateHarness.LIVE_REF).claims());
                }
                v = evaluate(method, t, signer, tx);
            }
            default -> {
                return null;
            }
        }
        validatorRuns++;
        lastVerdict = v;
        return v.accepted();
    }

    // ------------------------------------------------------------------ asset-aware contracts (M2)

    /**
     * The redeemer of a call of an asset-aware contract, with the arguments as an attacker may send them (raw), and the
     * native assets offered ({@code assetsIn}, a payable method with Funds) in ledger order.
     */
    private Object assetRedeemer(String method, List<Object> args, Tx t, JulcList<Object> evs) throws Exception {
        String kind = interp.kind(method);
        String variant = kind.equals("init") ? "CreateInstance" : cap(method);
        var redeemerArgs = new ArrayList<Object>();
        if (kind.equals("init")) redeemerArgs.add(harness.seed());
        for (Object a : args) redeemerArgs.add(java(a));
        if (interp.usesTx(method)) redeemerArgs.add(ctx(t));
        Class<?> rc = load(pkg + "." + name + "Validator$" + variant);
        var comps = rc.getRecordComponents();
        boolean assetsIn = comps.length > 0 && comps[comps.length - 1].getName().equals("assetsIn");
        if (assetsIn) redeemerArgs.add(value(t.funds() == null ? ValueV.NONE : t.funds().received()).toPlutusData());
        if (interp.carriesEvents(method)) redeemerArgs.add(evs == null ? JulcList.empty() : evs);
        var encoded = (org.julclang.core.PlutusData.ConstrData) StateHarness.data(construct(variant, redeemerArgs));
        var fields = new ArrayList<>(encoded.fields());
        int offset = kind.equals("init") ? 1 : 0;
        for (int i = 0; i < args.size(); i++) fields.set(offset + i, raw(args.get(i)));
        return new org.julclang.core.PlutusData.ConstrData(encoded.constructorTag(), fields);
    }

    /** What the instance holds besides lovelace and its token, as the call's funds say (none when they do not). */
    private static Value heldOf(Tx t) {
        return t == null || t.funds() == null ? Assets.none() : value(t.funds().held());
    }

    /** The instance as the transaction spends it: an action's balance is what it may allocate; a destroy's everything. */
    private StateHarness.Held held(String kind, Object before, Tx t) {
        BigInteger funds = t == null ? BigInteger.ZERO : kind.equals("destroy") ? t.balance().subtract(BigInteger.valueOf(harness.minAda())) : t.balance();
        return harness.held(before, funds, heldOf(t));
    }

    /** The mint redeemer: ByAction(own) when the transition mints user tokens, else BurnInstance for a destroy, else none. */
    private Object mintRedeemer(String kind, StateHarness.Held h, Value mint) throws Exception {
        if (!Assets.isEmpty(mint)) return construct("ByAction", List.of(h.ref()));
        return kind.equals("destroy") ? construct("BurnInstance", List.of()) : null;
    }

    /** One transaction of an asset-aware call proposing {@code tr} (next is null for a destroy). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private org.julclang.state.testkit.TxModel assetTx(String method, Object redeemer, Rec state, Tx t, byte[] signer, Transition tr)
            throws Exception {
        String kind = interp.kind(method);
        Ctx c = interp.usesTx(method) ? ctx(t) : null;
        if (kind.equals("init")) return harness.createWith(redeemer, tr.next(), c, tr.accepted(), false, null);
        var h = held(kind, java(state), t);
        var tx = harness.stepWith(h, redeemer, mintRedeemer(kind, h, tr.mint()), tr, kind.equals("destroy") ? null : tr.next(), signer, c, null);
        return composed(method, tx, t, tr, h);
    }

    /** M2c: an output holding {@code inst}'s token (and {@code extra}) at its script's address, as another JOUST instance sits. */
    static TxOut instanceAt(InstV inst, Value extra) {
        Value v = Assets.add(Assets.add(Assets.lovelace(BigInteger.valueOf(2_000_000)), Assets.of(inst.policy().bytes(), inst.id().bytes(), BigInteger.ONE)), extra);
        return new TxOut(new Address(new Credential.ScriptCredential(new ScriptHash(inst.policy().bytes())), Optional.empty()), v,
                new OutputDatum.OutputDatumInline(org.julclang.core.PlutusData.constr(0)), Optional.empty());
    }

    /**
     * M2c: a call's transaction as the runtime composes it (ADR-0011): each read state a reference input holding its
     * instance's token at its script's address, with the state as its inline datum (D2); each deposit the method makes
     * into another instance, which the same transaction spends and continues holding the deposit, claimed in the registry
     * by this call's input (D3); and for a receiving action, each deposit claimed on its own continuing output, which holds
     * it, from a depositor's input. This harness evaluates its own spends (and the output verifier); the other contract's
     * are its own harness's (CLAUDE.md: cross-contract transactions are checked with each contract's harness).
     */
    private org.julclang.state.testkit.TxModel composed(String method, org.julclang.state.testkit.TxModel tx, Tx t, Transition<?> tr,
                                                         StateHarness.Held h) {
        withReads(method, tx, t);
        var claims = composedClaims(method, tx, t, tr, h);
        if (!claims.isEmpty()) {
            claims.sort(java.util.Comparator.comparing(DepositClaim::output));
            tx.depositClaims = claims;
        }
        return tx;
    }

    /** M2c: each read state a reference input holding its instance's token at its script's address (ADR-0011 D2). */
    org.julclang.state.testkit.TxModel withReads(String method, org.julclang.state.testkit.TxModel tx, Tx t) {
        for (String f : interp.reads(method)) {
            InstV inst = (InstV) params.fields().get(interp.paramsFields().indexOf(f));
            var out = instanceAt(inst, Assets.none());
            out = new TxOut(out.address(), out.value(), new OutputDatum.OutputDatumInline(raw(t.reads().get(f))), Optional.empty());
            tx.referenceInputs.add(new TxInInfo(new org.julclang.ledger.TxOutRef(new org.julclang.ledger.TxId(StateHarness.fill(32, 0x60 + tx.referenceInputs.size())),
                    BigInteger.ZERO), out));
        }
        return tx;
    }

    private List<DepositClaim> composedClaims(String method, org.julclang.state.testkit.TxModel tx, Tx t, Transition<?> tr, StateHarness.Held h) {
        var claims = new ArrayList<DepositClaim>();
        int k = 0;
        for (Deposit d : tr.deposits()) {
            InstV inst = new InstV(Bytes.of(d.policy()), Bytes.of(d.instance()));
            tx.inputs.add(new TxInInfo(new org.julclang.ledger.TxOutRef(new org.julclang.ledger.TxId(StateHarness.fill(32, 0x70 + k)), BigInteger.ZERO),
                    instanceAt(inst, Assets.none())));
            tx.outputs.add(instanceAt(inst, d.value()));
            claims.add(new DepositClaim(BigInteger.valueOf(tx.outputs.size() - 1), h.ref(), d.value(), d.memo()));
            k++;
        }
        if (interp.receives(method) && t != null && t.funds() != null && !t.funds().deposits().isEmpty() && tx.stateOutput >= 0) {
            Funds f = javaFunds(t);
            Value total = Assets.none();
            int j = 0;
            for (DepositClaim d : f.deposits()) {
                tx.inputs.add(new TxInInfo(d.by(), new TxOut(StateHarness.pkhAddress(StateHarness.fill(28, 0xD0)), Assets.lovelace(BigInteger.valueOf(50_000_000)),
                        new OutputDatum.NoOutputDatum(), Optional.empty())));
                claims.add(new DepositClaim(BigInteger.valueOf(tx.stateOutput), d.by(), d.value(), d.memo()));
                total = Assets.add(total, d.value());
                j++;
            }
            TxOut o = tx.outputs.get(tx.stateOutput);
            tx.outputs.set(tx.stateOutput, new TxOut(o.address(), Assets.add(o.value(), total), o.datum(), o.referenceScript()));
        }
        return claims;
    }

    /** The interpreter's outcome as a transition. */
    private Transition<Object> transitionOf(String kind, Interpreter.Outcome o) {
        return Transition.full(kind.equals("destroy") ? null : java(o.value()), payouts(o.payouts()), sends(o.sends()), value(o.mint()),
                value(o.accepted())).withEvents(events(o)).withDeposits(deposits(o.deposits()));
    }

    /**
     * The validator of an asset-aware contract (M2): the transaction is built as the runtime builds it ({@code createWith},
     * {@code stepWith}): the state output holds what came in, plus what was received and accepted and the mint, less the
     * payouts and sends, each send an output claimed in the registry; a refused call proposes the input state again with
     * no effects (an action), everything to the caller (a destroy) or {@code candidate} (an init).
     */
    Boolean validatorAssets(String method, Rec state, List<Object> args, Tx t, byte[] signer, Rec candidate, Interpreter.Outcome o) throws Exception {
        String kind = interp.kind(method);
        Object redeemer = assetRedeemer(method, args, t, o == null ? null : events(o));
        Transition<Object> tr;
        if (o != null) {
            tr = transitionOf(kind, o);
        } else {
            switch (kind) {
                case "init" -> {
                    if (candidate == null) return null;
                    tr = Transition.full(java(candidate), JulcList.empty(), JulcList.empty(), Assets.none(), Assets.none());
                }
                case "action" -> tr = Transition.full(java(state), JulcList.empty(), JulcList.empty(), Assets.none(), Assets.none());
                default -> tr = Transition.full(null, Payouts.of(signer, t.balance()), JulcList.empty(), Assets.none(), Assets.none());
            }
        }
        var v = evaluate(method, assetTx(method, redeemer, state, t, signer, tr));
        validatorRuns++;
        lastVerdict = v;
        return v.accepted();
    }

    // ------------------------------------------------------------------ hostile inputs as raw data (J15; M2a review r1)

    /**
     * The transition program on arguments given as raw data, in place of {@code honest}'s (a value the interpreters'
     * vector form cannot express: an unknown constructor tag, a missing or an extra field): "refused", or its result.
     */
    String transitionRaw(String method, Rec state, List<org.julclang.core.PlutusData> raw, Tx t) {
        String program = transitions.get(method);
        assertNotNull(program, name + " has no transition program " + method);
        PlutusData ctxData = interp.usesTx(method) ? data(ctx(t)) : NONE;
        var fields = new ArrayList<org.julclang.core.PlutusData>();
        if (takesFunds(method)) fields.add(fundsData(t));
        fields.addAll(raw);
        return evaluateRaw(program, List.of(julc(state == null ? NONE : data(java(state))), julc(NONE),
                new org.julclang.core.PlutusData.ConstrData(0, fields), julc(ctxData)));
    }

    /**
     * The validator of an asset-aware contract on a redeemer whose arguments are raw data ({@code raw}, in place of
     * {@code honest}'s), in the transaction built from {@code next} (the next state as data, nothing moved) or, when it
     * is null, from the interpreter's outcome of the honest call. True when every script accepts.
     */
    boolean validatorRaw(String method, Rec state, List<Object> honest, List<org.julclang.core.PlutusData> raw, Tx t, byte[] signer,
            org.julclang.core.PlutusData next) throws Exception {
        String kind = interp.kind(method);
        var encoded = (org.julclang.core.PlutusData.ConstrData) assetRedeemer(method, honest, t, null);
        var fields = new ArrayList<>(encoded.fields());
        int offset = kind.equals("init") ? 1 : 0;
        for (int i = 0; i < raw.size(); i++) fields.set(offset + i, raw.get(i));
        var redeemer = new org.julclang.core.PlutusData.ConstrData(encoded.constructorTag(), fields);
        Transition<Object> tr = next != null ? Transition.full(next, JulcList.empty(), JulcList.empty(), Assets.none(), Assets.none())
                : transitionOf(kind, interp.transition(method, state, honest, interpreterTx(t, signer)));
        var v = harness.evaluate(assetTx(method, redeemer, state, t, signer, tr));
        lastVerdict = v;
        return v.accepted();
    }

    // ------------------------------------------------------------------ hostile memos and read states as raw data (M2c, J15)

    /**
     * M2c: a receiving call whose deposits' memos are raw data ({@code memos}, one per deposit of {@code t}, in place of
     * the honest ones): the transition program's result ("refused", or its step), and whether the validator accepts the
     * transaction proposing {@code next} (null: the input state, nothing moved) with those claims on its output.
     */
    Object[] receiverRaw(String method, Rec state, Tx t, List<org.julclang.core.PlutusData> memos, Rec next, byte[] signer) throws Exception {
        return receiverRawJava(method, state, t, memos, next == null ? java(state) : java(next), signer);
    }

    /** The same, proposing {@code nx}, a next state of the generated Java (the JVM leg's own, {@link #lastJvmResult}). */
    Object[] receiverRawJava(String method, Rec state, Tx t, List<org.julclang.core.PlutusData> memos, Object nx, byte[] signer)
            throws Exception {
        Funds honest = javaFunds(t);
        JulcList<DepositClaim> ds = JulcList.empty();
        var claims = new ArrayList<DepositClaim>();
        int k = 0;
        for (DepositClaim d : honest.deposits()) {
            claims.add(new DepositClaim(d.output(), d.by(), d.value(), memos.get(k++)));
        }
        for (int i = claims.size() - 1; i >= 0; i--) ds = ds.prepend(claims.get(i));
        var funds = new Funds(honest.policy(), honest.self(), honest.received(), honest.held(), ds);
        String program = transitions.get(method);
        PlutusData ctxData = interp.usesTx(method) ? data(ctx(t)) : NONE;
        var in = new org.julclang.core.PlutusData.ConstrData(0, List.of(StateHarness.data(funds)));
        String tp = evaluateRaw(program, List.of(julc(data(java(state))), julc(NONE), in, julc(ctxData)));
        Object redeemer = assetRedeemer(method, List.of(), t, null);
        var h = held(interp.kind(method), java(state), t);
        var tx = harness.stepWith(h, redeemer, null, Transition.full(nx, JulcList.empty(), JulcList.empty(), Assets.none(), Assets.none()), nx,
                signer, interp.usesTx(method) ? ctx(t) : null, null);
        Value total = Assets.none();
        var onChain = new ArrayList<DepositClaim>();
        for (DepositClaim d : claims) {
            tx.inputs.add(new TxInInfo(d.by(), new TxOut(StateHarness.pkhAddress(StateHarness.fill(28, 0xD0)), Assets.lovelace(BigInteger.valueOf(50_000_000)),
                    new OutputDatum.NoOutputDatum(), Optional.empty())));
            onChain.add(new DepositClaim(BigInteger.valueOf(tx.stateOutput), d.by(), d.value(), d.memo()));
            total = Assets.add(total, d.value());
        }
        TxOut o = tx.outputs.get(tx.stateOutput);
        tx.outputs.set(tx.stateOutput, new TxOut(o.address(), Assets.add(o.value(), total), o.datum(), o.referenceScript()));
        tx.depositClaims = onChain;
        var v = harness.evaluate(tx);
        lastVerdict = v;
        return new Object[]{tp, v.accepted()};
    }

    /**
     * M2c: a reading call whose read state is raw data ({@code datum}, in place of the honest one for the params field
     * {@code field}): whether the validator accepts the transaction proposing the honest call's next state with that
     * reference input (the transition program is handed what the engine fetched and decoded: the validator's shape check,
     * ADR-0011 D2, is the guard; see the M2c report).
     */
    boolean readerRaw(String method, Rec state, List<Object> args, Tx t, String field, org.julclang.core.PlutusData datum, byte[] signer)
            throws Exception {
        var o = interp.transition(method, state, args, t);
        String kind = interp.kind(method);
        if (takesFunds(method)) {
            // An asset-aware reader (review r1, C4): the call's composed transaction, its read state's datum replaced.
            var tx = assetTx(method, assetRedeemer(method, args, t, null), state, t, signer, transitionOf(kind, o));
            int k = interp.reads(method).indexOf(field);
            var ref = tx.referenceInputs.get(k);
            tx.referenceInputs.set(k, new TxInInfo(ref.outRef(), new TxOut(ref.resolved().address(), ref.resolved().value(),
                    new OutputDatum.OutputDatumInline(datum), Optional.empty())));
            var v = harness.evaluate(tx);
            lastVerdict = v;
            return v.accepted();
        }
        Ctx c = interp.usesTx(method) ? ctx(t) : null;
        var redeemerArgs = new ArrayList<Object>();
        for (Object a : args) redeemerArgs.add(java(a));
        if (c != null) redeemerArgs.add(c);
        Object encoded = construct(cap(method), redeemerArgs);
        var live = harness.live(java(state), t == null ? BigInteger.ZERO : t.balance());
        var tx = harness.act(live, encoded, java(o.value()), payouts(o.payouts()), signer, c);
        InstV inst = (InstV) params.fields().get(interp.paramsFields().indexOf(field));
        var out = instanceAt(inst, Assets.none());
        tx.referenceInputs.add(new TxInInfo(new org.julclang.ledger.TxOutRef(new org.julclang.ledger.TxId(StateHarness.fill(32, 0x60)), BigInteger.ZERO),
                new TxOut(out.address(), out.value(), new OutputDatum.OutputDatumInline(datum), Optional.empty())));
        var v = harness.evaluate(tx);
        lastVerdict = v;
        return v.accepted();
    }

    /** Values one step away: each entry ±1 (a zero result drops it), and one entry added (a token named 0x6d under the own policy). */
    List<Value> nearbyValues(Value v, boolean grow) {
        var out = new ArrayList<Value>();
        for (var p : Assets.read(v).entrySet()) {
            for (var tk : p.getValue().entrySet()) {
                for (BigInteger d : grow ? List.of(BigInteger.ONE, BigInteger.ONE.negate()) : List.of(BigInteger.ONE.negate())) {
                    out.add(Assets.add(v, Assets.of(HEX.parseHex(p.getKey()), HEX.parseHex(tk.getKey()), d)));
                }
            }
        }
        if (grow) out.add(Assets.add(v, Assets.of(harness.policy(), new byte[]{0x6d}, BigInteger.ONE)));
        return out;
    }

    /**
     * Send lists one step away: one dropped, one sending one unit less of its first entry, one to another address, and
     * with {@code more}, one sending one unit more (what the one receiving it would want: taken from the instance, it
     * leaves the continuing output one short). Not for a destroy: there the instance's value is accounted exactly by its
     * payouts, sends and burn, so a send output holding more holds the submitter's own assets (a send is "at least its
     * value", I30), which the ledger's balance refuses to take from the instance.
     */
    static List<JulcList<Send>> nearbySends(JulcList<Send> sends, boolean more) {
        var ss = new ArrayList<Send>();
        for (Send x : sends) ss.add(x);
        var out = new ArrayList<JulcList<Send>>();
        byte[] other = new byte[28];
        Arrays.fill(other, (byte) 0xBB);
        for (int k = 0; k < ss.size(); k++) {
            Send x = ss.get(k);
            var dropped = new ArrayList<>(ss);
            dropped.remove(k);
            out.add(sendList(dropped));
            var moved = new ArrayList<>(ss);
            moved.set(k, new Send(new Address(new Credential.PubKeyCredential(new PubKeyHash(other)), Optional.empty()), x.value(), x.datum()));
            out.add(sendList(moved));
            var r = Assets.read(x.value());
            if (!r.isEmpty()) {
                var p = r.firstEntry();
                var tk = p.getValue().firstEntry();
                var less = new ArrayList<>(ss);
                less.set(k, new Send(x.to(), Assets.subtract(x.value(), Assets.of(HEX.parseHex(p.getKey()), HEX.parseHex(tk.getKey()), BigInteger.ONE)), x.datum()));
                out.add(sendList(less));
                if (more) {
                    var plus = new ArrayList<>(ss);
                    plus.set(k, new Send(x.to(), Assets.add(x.value(), Assets.of(HEX.parseHex(p.getKey()), HEX.parseHex(tk.getKey()), BigInteger.ONE)), x.datum()));
                    out.add(sendList(plus));
                }
            }
        }
        return out;
    }

    /** M2c: send lists whose one send's datum is one step away: dropped (none), or inline but other data. */
    static List<JulcList<Send>> nearbyDatums(JulcList<Send> sends) {
        var ss = new ArrayList<Send>();
        for (Send x : sends) ss.add(x);
        var out = new ArrayList<JulcList<Send>>();
        for (int k = 0; k < ss.size(); k++) {
            Send x = ss.get(k);
            if (!(x.datum() instanceof OutputDatum.OutputDatumInline)) continue;
            for (OutputDatum d : List.<OutputDatum>of(new OutputDatum.NoOutputDatum(),
                    new OutputDatum.OutputDatumInline(org.julclang.core.PlutusData.constr(9)))) {
                var l = new ArrayList<>(ss);
                l.set(k, new Send(x.to(), x.value(), d));
                out.add(sendList(l));
            }
        }
        return out;
    }

    /** M2c: deposit lists one step away: one dropped, one lovelace less, its memo other data. */
    static List<JulcList<Deposit>> nearbyDeposits(JulcList<Deposit> deposits) {
        var ds = new ArrayList<Deposit>();
        for (Deposit d : deposits) ds.add(d);
        var out = new ArrayList<JulcList<Deposit>>();
        for (int k = 0; k < ds.size(); k++) {
            Deposit d = ds.get(k);
            var dropped = new ArrayList<>(ds);
            dropped.remove(k);
            out.add(depositList(dropped));
            if (Assets.lovelaceOf(d.value()).compareTo(BigInteger.ONE) > 0) {
                var less = new ArrayList<>(ds);
                less.set(k, new Deposit(d.policy(), d.instance(), Assets.subtract(d.value(), Assets.lovelace(BigInteger.ONE)), d.memo()));
                out.add(depositList(less));
            }
            var memo = new ArrayList<>(ds);
            memo.set(k, new Deposit(d.policy(), d.instance(), d.value(), org.julclang.core.PlutusData.constr(9)));
            out.add(depositList(memo));
        }
        return out;
    }

    private static JulcList<Deposit> depositList(List<Deposit> ds) {
        JulcList<Deposit> l = JulcList.empty();
        for (int i = ds.size() - 1; i >= 0; i--) l = l.prepend(ds.get(i));
        return l;
    }

    private static JulcList<Send> sendList(List<Send> ss) {
        JulcList<Send> l = JulcList.empty();
        for (int i = ss.size() - 1; i >= 0; i--) l = l.prepend(ss.get(i));
        return l;
    }

    /**
     * Mutation rejection for an asset-aware call (M2): every transaction proposing a next state, payouts, sends, mint or
     * accepted value one step away from the interpreter's is rejected, and the accepted one passes the attack suite.
     */
    int mutantsAssets(String method, Rec state, List<Object> args, Tx t, byte[] signer) throws Exception {
        String kind = interp.kind(method);
        var o = interp.transition(method, state, args, t);
        Object redeemer = assetRedeemer(method, args, t, events(o));
        var tr = transitionOf(kind, o);
        byte[] caller = t == null ? signer : t.caller().bytes();
        var txs = new ArrayList<org.julclang.state.testkit.TxModel>();
        var accepted = assetTx(method, redeemer, state, t, signer, tr);
        if (!kind.equals("destroy")) {
            for (Object m : nearbyStates(tr.next())) txs.add(assetTx(method, redeemer, state, t, signer, Transition.full(m, tr.payouts(), tr.sends(), tr.mint(), tr.accepted())));
        }
        if (!kind.equals("init")) {
            for (var q : nearbyPayouts(tr.payouts(), caller, kind.equals("action"))) {
                txs.add(assetTx(method, redeemer, state, t, signer, Transition.full(tr.next(), q, tr.sends(), tr.mint(), tr.accepted())));
            }
            for (var q : nearbySends(tr.sends(), kind.equals("action"))) {
                txs.add(assetTx(method, redeemer, state, t, signer, Transition.full(tr.next(), tr.payouts(), q, tr.mint(), tr.accepted())));
            }
            // A contract that never mints has no ByAction: its mint mutants are those of a contract that mints (Coins, Dex).
            if (exists(pkg + "." + name + "Validator$ByAction")) {
                for (var q : nearbyValues(tr.mint(), true)) {
                    txs.add(assetTx(method, redeemer, state, t, signer, Transition.full(tr.next(), tr.payouts(), tr.sends(), q, tr.accepted())));
                }
            }
            for (var q : nearbyValues(tr.accepted(), false)) {
                txs.add(assetTx(method, redeemer, state, t, signer, Transition.full(tr.next(), tr.payouts(), tr.sends(), tr.mint(), q)));
            }
            // M2c: a send's datum one step away (dropped, or other data), and the deposits one step away.
            for (var q : nearbyDatums(tr.sends())) {
                txs.add(assetTx(method, redeemer, state, t, signer, Transition.full(tr.next(), tr.payouts(), q, tr.mint(), tr.accepted())
                        .withEvents(events(o)).withDeposits(tr.deposits())));
            }
            for (var q : nearbyDeposits(tr.deposits())) {
                txs.add(assetTx(method, redeemer, state, t, signer, Transition.full(tr.next(), tr.payouts(), tr.sends(), tr.mint(), tr.accepted())
                        .withEvents(events(o)).withDeposits(q)));
            }
        }
        var ok = evaluate(method, accepted);
        assertTrue(ok.accepted(), name + "." + method + ": the unmutated transaction must be accepted\n" + ok);
        attacksRejected += org.julclang.state.testkit.Attacks.assertAllRejected(harness, accepted);
        int n = 0;
        for (var tx : txs) {
            var v = evaluate(method, tx);
            assertFalse(v.accepted(), name + "." + method + ": mutant #" + n + " accepted\n" + v);
            n++;
        }
        assertTrue(n > 0, name + "." + method + ": no mutants");
        mutantsRejected += n;
        return n;
    }

    // ------------------------------------------------------------------ batched actions (ADR-0004; M1c)

    /** The intent's lovelace on top of its payment (the processor's intent deposit). */
    static final BigInteger INTENT_DEPOSIT = BigInteger.valueOf(2_000_000);

    private static String cap(String method) {
        return Character.toUpperCase(method.charAt(0)) + method.substring(1);
    }

    private boolean exists(String cls) {
        try {
            load(cls);
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
        assertEquals(source, exists(pkg + "." + name + "Validator$" + cap(method) + "Intent"), name + "." + method + ": batched in the source and the validator");
        return source;
    }

    /** A direct call exists unless the action is {@code batched only} (from the source; the validator must agree). */
    boolean direct(String method) {
        boolean source = !interp.modifiers(method).contains("batched only");
        assertEquals(source, exists(pkg + "." + name + "Validator$" + cap(method)), name + "." + method + ": a direct redeemer in the source and the validator");
        return source;
    }

    @SuppressWarnings("unchecked")
    private org.julclang.state.runtime.IntentCodec<Object> intents() throws Exception {
        return (org.julclang.state.runtime.IntentCodec<Object>) load(pkg + "." + name + "Client").getField("INTENTS").get(null);
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
    int mutants(String method, Rec state, List<Object> args, Tx t, byte[] signer) throws Exception {
        try {
            return assets ? mutantsAssets(method, state, args, t, signer) : mutantsPlain(method, state, args, t, signer);
        } finally {
            as(null, null);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int mutantsPlain(String method, Rec state, List<Object> args, Tx t, byte[] signer) throws Exception {
        String kind = interp.kind(method);
        var o = interp.transition(method, state, args, t);
        var redeemerArgs = new ArrayList<Object>();
        if (kind.equals("init")) redeemerArgs.add(harness.seed());
        for (Object a : args) redeemerArgs.add(java(a));
        if (interp.usesTx(method)) redeemerArgs.add(ctx(t));
        // M2b: the events the method returns, last; the mutants below also carry events one step away.
        boolean ev = interp.carriesEvents(method);
        if (ev) redeemerArgs.add(events(o));
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
                    if (ev) for (Object r : nearbyEventRedeemers(method, redeemerArgs)) txs.add(harness.act(live, r, next, pays, signer, c));
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
                if (ev) for (Object r : nearbyEventRedeemers(method, redeemerArgs)) txs.add(harness.destroy(live, r, burn, pays, signer, c));
                for (var q : nearbyPayouts(pays, caller, false)) {
                    var tx = harness.destroy(live, redeemer, burn, q, signer, c);
                    txs.add(claims != null ? harness.withClaims(tx, claims) : tx);
                }
            }
            default -> {
                return 0;
            }
        }
        // M2c: the read states the call references, in every transaction (ADR-0011 D2).
        accepted = withReads(method, accepted, t);
        for (var x : txs) withReads(method, x, t);
        var ok = evaluate(method, accepted);
        assertTrue(ok.accepted(), name + "." + method + ": the unmutated transaction must be accepted\n" + ok);
        attacksRejected += org.julclang.state.testkit.Attacks.assertAllRejected(harness, accepted);
        int n = 0;
        for (var tx : txs) {
            var v = evaluate(method, tx);
            assertFalse(v.accepted(), name + "." + method + ": mutant #" + n + " accepted\n" + v);
            n++;
        }
        assertTrue(n > 0, name + "." + method + ": no mutants");
        mutantsRejected += n;
        return n;
    }

    /**
     * M2b: the method's redeemer with its events one step away (I35): each dropped, the first repeated, the first one's
     * first field one step away. The validator must refuse each, since the redeemer then carries other events than the
     * method returns.
     */
    @SuppressWarnings("unchecked")
    List<Object> nearbyEventRedeemers(String method, List<Object> redeemerArgs) throws Exception {
        var evs = new ArrayList<Object>();
        for (Object e : (JulcList<Object>) redeemerArgs.getLast()) evs.add(e);
        var lists = new ArrayList<List<Object>>();
        for (int k = 0; k < evs.size(); k++) {
            var dropped = new ArrayList<>(evs);
            dropped.remove(k);
            lists.add(dropped);
        }
        if (!evs.isEmpty()) {
            var twice = new ArrayList<>(evs);
            twice.add(evs.getFirst());
            lists.add(twice);
            Object first = evs.getFirst();
            var comps = first.getClass().getRecordComponents();
            if (comps.length > 0) {
                var types = new Class<?>[comps.length];
                for (int i = 0; i < comps.length; i++) types[i] = comps[i].getType();
                for (Object w : nearby(comps[0].getAccessor().invoke(first))) {
                    var vals = new Object[comps.length];
                    for (int j = 0; j < comps.length; j++) vals[j] = j == 0 ? w : comps[j].getAccessor().invoke(first);
                    var changed = new ArrayList<>(evs);
                    changed.set(0, first.getClass().getDeclaredConstructor(types).newInstance(vals));
                    lists.add(changed);
                }
            }
        }
        var out = new ArrayList<Object>();
        for (var l : lists) {
            var args = new ArrayList<>(redeemerArgs);
            args.set(args.size() - 1, l.isEmpty() ? JulcList.empty() : JulcList.of(l.toArray()));
            out.add(construct(cap(method), args));
        }
        return out;
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
        Class<?> rc = load(pkg + "." + name + in + variant);
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

    /** The Java example's state record (dex's is Pool; M2). */
    String referenceState = "State";

    /** Also runs every vector on the Java example {@code pkg.<name>}, whose init is {@code init}, and records differences. */
    FourWay reference(String pkg, String init) throws Exception {
        this.reference = load(pkg + "." + name);
        this.referenceInit = init;
        return this;
    }

    /** M2c: as {@link #reference(String, String)}, the Java example's contract named {@code javaName} (oracle's Shop for Kiosk). */
    FourWay referenceNamed(String pkg, String javaName, String init) throws Exception {
        this.reference = load(pkg + "." + javaName);
        this.referenceInit = init;
        return this;
    }

    /** As {@link #reference(String, String)}, the Java example's state record named {@code state}. */
    FourWay reference(String pkg, String init, String state) throws Exception {
        this.referenceState = state;
        return reference(pkg, init);
    }

    /** The Java example's JVM outcome, encoded as {@link #jvm} encodes the port's ("refused" when it fails). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    String javaReference(String method, Rec state, List<Object> args, Tx t) throws Exception {
        String kind = interp.kind(method);
        Class<?> rs = load(reference.getName() + "$" + referenceState);
        Object before = state == null ? null : java(state, reference);
        String m = kind.equals("init") ? referenceInit : method;
        Method found = null;
        for (Method x : reference.getDeclaredMethods()) if (x.getName().equals(m) && java.lang.reflect.Modifier.isStatic(x.getModifiers())) found = x;
        assertNotNull(found, reference.getName() + "." + m);
        var call = new ArrayList<Object>();
        if (before != null) call.add(before);
        // M2b: the Java example's params record, where its method takes it (directly after the state, first in an init).
        if (params != null && call.size() < found.getParameterCount() && found.getParameterTypes()[call.size()].getSimpleName().equals("Config")) {
            call.add(java(params, reference));
        }
        // M2c: the read states, right after the params.
        for (String f : interp.reads(method)) call.add(java(t.reads().get(f), reference));
        for (Object a : args) call.add(java(a, reference));
        // The Ctx and the Funds (M2) the Java method takes after its arguments, by type.
        for (int i = call.size(); i < found.getParameterCount(); i++) call.add(found.getParameterTypes()[i] == Funds.class ? javaFunds(t) : ctx(t));
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
        if (assets) return "ok:" + jvmStep(r, kind, rs, events);
        if (r instanceof JulcList<?> l) return "ok:" + encoded(null, (JulcList<Payout>) l, null);
        Object next = kind.equals("destroy") ? null : rs.isInstance(r) ? r : r.getClass().getMethod("state").invoke(r);
        JulcList<Payout> pays = rs.isInstance(r) ? null : (JulcList<Payout>) component(r, "payouts");
        JulcList<?> evs = rs.isInstance(r) ? null : (JulcList<?>) component(r, "events");
        if (hasMaps()) {
            next = kind.equals("init") ? ms.prepareCreation(harness.policy(), (Class) rs, next, harness.seed()).stored()
                    : ms.prepare(harness.policy(), (Class) rs, before, next, StateHarness.LIVE_REF).stored();
        }
        return "ok:" + encoded(next, pays, evs);
    }

    /**
     * All four legs (two for a view), asserted equal; returns the agreed outcome. The call is also recorded for the fifth
     * leg, the JavaScript interpreter, which {@link JsLeg#check} runs over every recorded call at the end of a suite.
     */
    String check(String method, Rec state, List<Object> args, Tx t, byte[] signer) throws Exception {
        return check(method, state, args, t, signer, null);
    }

    String check(String method, Rec state, List<Object> args, Tx t, byte[] signer, Rec candidate) throws Exception {
        try {
            return checkOnce(method, state, args, t, signer, candidate);
        } finally {
            as(null, null);
        }
    }

    private String checkOnce(String method, Rec state, List<Object> args, Tx t, byte[] signer, Rec candidate) throws Exception {
        // The interpreters see the keys that sign the transaction the validator evaluates (semantics §2.6: 'only f'). A
        // method that reads nothing else of the transaction is called without one on the other legs (usesCtx: false).
        // A batched action's step in a batch is signed by the batcher, its caller being the intent's owner, who signed the
        // intent (ADR-0004; semantics §2, #80 review S5): its τ.signers are the intent's, the caller alone.
        Tx ti = t != null && interp.kind(method).equals("action") && batched(method) ? t.signedBy(List.of(t.caller())) : itx(t, signer);
        String what = name + "." + method + " " + args + " " + (t == null ? "" : t);
        // The Java interpreter's outcome; a Limit (its stack ran out) propagates and fails the test (semantics §2, the transaction-level rules).
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
        // M2b: so are the output checks (an invariant, a quorum's shape: semantics §2.7), which only the validator makes.
        boolean modifier = refused != null && (refused.refusal().startsWith("modifier:") || refused.refusal().startsWith("invariant:"));
        String body = modifier ? interpreted(method, state, args, ti) : want;
        if (o != null) JsLeg.canonical(o.value(), what);
        assertEquals(body, jvm(method, state, args, t), "JVM vs interpreter: " + what);
        jsCalls.add(JsLeg.call(method, state, args, ti, params, want, o, refused, what));
        if (reference != null) {
            String ref = javaReference(method, state, args, t);
            if (!ref.equals(body)) javaDiffs.add(what + ": the Java example " + (ref.equals("refused") ? "refuses" : "accepts") + ", the port "
                    + (body.equals("refused") ? "refuses" : "accepts") + (ref.startsWith("ok:") && body.startsWith("ok:") ? " (different results)" : ""));
        }
        if (!interp.kind(method).equals("view")) {
            assertEquals(body, transition(method, state, args, t), "transition program vs interpreter: " + what);
            Boolean accepted = validator(method, state, args, t, signer, candidate);
            if (transactionLevel) {
                assertEquals(Boolean.FALSE, accepted, "a transaction-level failure: the validator (or the ledger) refuses " + what + " " + lastVerdict);
            } else if (accepted != null) {
                assertEquals(!want.equals("refused"), accepted, "validator vs interpreter: " + what + " " + lastVerdict);
            }
        }
        calls++;
        if (want.equals("refused")) refusals++;
        return want;
    }

    /** Set while {@link #transactionLevel} runs a call. */
    private boolean transactionLevel;

    /**
     * A call that fails only at the transaction (semantics §2, the transaction-level rules): both interpreters, the JVM
     * method and the transition program accept it and agree, and the transaction built from their outcome is refused, by
     * the validator or by the ledger (StateHarness.ledgerProblem). Returns the agreed outcome; {@link #lastVerdict} says
     * which refused.
     */
    String transactionLevel(String method, Rec state, List<Object> args, Tx t, byte[] signer) throws Exception {
        transactionLevel = true;
        try {
            String out = check(method, state, args, t, signer);
            assertTrue(out.startsWith("ok:"), "the call itself succeeds: " + name + "." + method + " " + args);
            transactionLevelRefusals++;
            return out;
        } finally {
            transactionLevel = false;
        }
    }

    /** Calls that fail only at the transaction ({@link #transactionLevel}). */
    int transactionLevelRefusals;

    /**
     * The transaction the interpreters see: {@code t} signed by {@code signer} and by its caller (semantics §2: the
     * caller is among the signers; review S6 on #76), or the signer's alone when {@code t} is null. The validator's
     * transaction is signed by the same keys ({@link #evaluate}).
     */
    static Tx interpreterTx(Tx t, byte[] signer) {
        if (signer == null) return t;
        if (t == null) return new Tx(Bytes.of(signer), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
        var keys = new ArrayList<Bytes>(List.of(Bytes.of(signer)));
        if (!keys.contains(t.caller())) keys.add(t.caller());
        return t.signedBy(keys);
    }

    /**
     * Evaluates {@code tx} signed by the keys the interpreters see ({@link #itx}): the signer (or M2b's {@link #signers})
     * and the caller, and approved by {@link #approvers}.
     */
    private StateHarness.Verdict evaluate(String method, Tx t, byte[] signer, org.julclang.state.testkit.TxModel tx) {
        signedAndApproved(method, tx);
        Tx ti = itx(t, signer);
        if (ti != null && (signer != null || signers != null) && tx.signers.stream().noneMatch(k -> java.util.Arrays.equals(k, ti.caller().bytes()))) {
            tx.signers.add(ti.caller().bytes());
        }
        if (txTweak != null) txTweak.accept(tx);
        return harness.evaluate(tx);
    }

    /**
     * A change made to the next validator transactions before they are evaluated (null: none): what an attacker may do
     * to an honest transaction that the interpreters' form cannot express, such as an unbounded validity end (FiveWayFuzzTest).
     */
    java.util.function.Consumer<org.julclang.state.testkit.TxModel> txTweak;

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
        assertTrue(interpreted(method, state, args, itx(t, signer)).startsWith("ok:"),
                "the transition accepts it (stated): " + what);
        assertEquals("refused", check(method, state, args, t, signer), what);
        assertTrue(lastRefusal.startsWith("modifier:"), "refused by the modifier: " + what + " " + lastRefusal);
        modifierRefusals++;
    }

    /**
     * M2b (semantics §2.7): a call whose next state breaks an invariant or a quorum's shape, on all five legs: both
     * interpreters refuse it with {@code cls} ({@code invariant:<name>}, {@code invariant:quorum <keys> <threshold>}), the
     * JVM method and the transition program compute it (the validator checks the outputs), and the validator refuses it.
     */
    void invariantRefused(String cls, String method, Rec state, List<Object> args, Tx t, byte[] signer) throws Exception {
        String what = name + "." + method + " " + args;
        assertTrue(interpreted(method, state, args, itx(t, signer)).startsWith("ok:"), "the transition accepts it (stated): " + what);
        assertEquals("refused", check(method, state, args, t, signer), what);
        assertEquals(cls, lastRefusal, what);
        invariantRefusals++;
    }

    int invariantRefusals;

    /** The interpreter's next state of a successful transition (to chain calls; modifiers are not checked). */
    Rec next(String method, Rec state, List<Object> args, Tx t) {
        return (Rec) interp.transition(method, state, args, t).value();
    }

    static PlutusData unit() {
        return ConstrPlutusData.of(0);
    }
}
