package org.julclang.state.examples.registry;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.Address;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.ScriptPurpose;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.runtime.StateContractHandle;
import org.julclang.state.testkit.Ledger;
import org.julclang.state.testkit.StateHarness;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The registry on the ledger model (ADR-0016 §8): parties with funded wallets, honest transactions for every keyed
 * operation, and a reference model of every list kept by the test.
 * <p>
 * Nothing here comes from the code under test except the contract's ABI (the generated redeemer and datum records, as
 * encoded data) and the contract's own methods, which the runtime runs on the JVM too. Key tokens, list order and the
 * expected lists are computed here, from the ADR's definitions.
 */
final class RegistryWorld {

    static final BigInteger T0 = BigInteger.valueOf(1_800_000_000_000L);
    /** The registry's link cooldown and window: the defaults, C = 60 s and W = min(120 s, maxValidityMs). */
    static final long C = 60_000L;
    static final long W = 120_000L;
    static final BigInteger FEE = BigInteger.valueOf(300_000L);

    static final byte[] ADMIN = StateHarness.fill(28, 0xAD);
    static final List<byte[]> PARTIES = new ArrayList<>();

    static {
        for (int i = 1; i <= 12; i++) PARTIES.add(StateHarness.fill(28, 0x10 + i));
    }

    /** The two keyed types of {@link Registry}, with what the ADR defines for them (D2). */
    enum Kind {
        BALLOT("Ballot", 28), ENTRY("Entry", 64);

        final String name;
        final int maxKey;

        Kind(String name, int maxKey) {
            this.name = name;
            this.maxKey = maxKey;
        }

        /** typeId = I2OSP(len(name), 1) ++ utf8(name). */
        byte[] typeId() {
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            byte[] t = new byte[n.length + 1];
            t[0] = (byte) n.length;
            System.arraycopy(n, 0, t, 1, n.length);
            return t;
        }
    }

    /** One list: an instance and a keyed type. */
    record ListId(String instance, Kind kind) {}

    /** Keys in the ADR's order (D2): unsigned lexicographic, a proper prefix first. */
    static final Comparator<String> KEY_ORDER = Comparator.comparing(HexFormat.of()::parseHex, Arrays::compareUnsigned);

    final StateHarness<Registry.State> h;
    final Ledger ledger;
    final byte[] policy;
    final Address state;
    final BigInteger minAda;
    /** Attack hook: a forced anchor (or predecessor) for the next structural operation, instead of the model's. */
    Utxo forced;
    /** Attack hooks: relink windows that do not wait for the cooldown, and their length. */
    boolean waitForCooldown = true;
    long windowLength = W;

    /** The reference model: every list's keys (hex) and whether each is retired. Heads are implicit. */
    final Map<ListId, TreeMap<String, Boolean>> model = new LinkedHashMap<>();

    RegistryWorld(Ledger.Params params) {
        this(params, T0);
    }

    /** A world whose clock starts at {@code start} (POSIX ms). */
    RegistryWorld(Ledger.Params params, BigInteger start) {
        this.h = StateHarness.of(RegistryClient.SPEC);
        this.policy = h.policy();
        this.state = h.stateAddress();
        this.minAda = BigInteger.valueOf(h.minAda());
        this.ledger = new Ledger(params, start);
        ledger.register(policy, h.program());
        for (int i = 0; i < 20; i++) ledger.fund(StateHarness.walletOut(ADMIN, ada(10_000)));
        for (byte[] p : PARTIES) for (int i = 0; i < 20; i++) ledger.fund(StateHarness.walletOut(p, ada(10_000)));
    }

    RegistryWorld() {
        this(Ledger.Params.mainnet());
    }

    static BigInteger ada(long n) {
        return BigInteger.valueOf(n * 1_000_000L);
    }

    // ------------------------------------------------------------------ identities (ADR-0016 D2), computed here

    /** keyId = 0x4B ++ blake2b_224(instance ++ typeId ++ key). */
    static byte[] keyId(byte[] instance, Kind kind, byte[] key) {
        byte[] t = kind.typeId();
        byte[] pre = new byte[instance.length + t.length + key.length];
        System.arraycopy(instance, 0, pre, 0, instance.length);
        System.arraycopy(t, 0, pre, instance.length, t.length);
        System.arraycopy(key, 0, pre, instance.length + t.length, key.length);
        byte[] h = Blake2bUtil.blake2bHash224(pre);
        byte[] id = new byte[29];
        id[0] = 0x4B;
        System.arraycopy(h, 0, id, 1, 28);
        return id;
    }

    /** keyId for a type by name (for heads of types the contract does not have). */
    static byte[] keyIdOfType(byte[] instance, String typeName, byte[] key) {
        byte[] n = typeName.getBytes(StandardCharsets.UTF_8);
        byte[] pre = new byte[instance.length + 1 + n.length + key.length];
        System.arraycopy(instance, 0, pre, 0, instance.length);
        pre[instance.length] = (byte) n.length;
        System.arraycopy(n, 0, pre, instance.length + 1, n.length);
        System.arraycopy(key, 0, pre, instance.length + 1 + n.length, key.length);
        byte[] id = new byte[29];
        id[0] = 0x4B;
        System.arraycopy(Blake2bUtil.blake2bHash224(pre), 0, id, 1, 28);
        return id;
    }

    static byte[] hex(String s) {
        return HexFormat.of().parseHex(s);
    }

    static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    // ------------------------------------------------------------------ the chain, read

    record Utxo(TxOutRef ref, TxOut out) {
        BigInteger lovelace() {
            return out.value().lovelaceOf();
        }

        PlutusData.ConstrData datum() {
            return (PlutusData.ConstrData) ((OutputDatum.OutputDatumInline) out.datum()).datum();
        }
    }

    /** The one UTxO holding {@code name} of the policy (fails if none or several). */
    Utxo holder(byte[] name) {
        var hs = ledger.holding(policy, name);
        if (hs.size() != 1) fail("expected one UTxO holding " + hex(name) + ", found " + hs.size());
        return new Utxo(hs.get(0).getKey(), hs.get(0).getValue());
    }

    Utxo instanceUtxo(byte[] instance) {
        return holder(instance);
    }

    Utxo node(byte[] instance, Kind kind, byte[] key) {
        return holder(keyId(instance, kind, key));
    }

    /** A wallet UTxO of {@code pkh} with at least {@code min} lovelace and nothing else; never one of {@code avoid}. */
    TxOutRef wallet(byte[] pkh, BigInteger min, List<TxOutRef> avoid) {
        Address a = StateHarness.pkhAddress(pkh);
        for (var e : ledger.at(a)) {
            var en = Ledger.entries(e.getValue().value());
            if (en.size() == 1 && e.getValue().value().lovelaceOf().compareTo(min) >= 0 && !avoid.contains(e.getKey())) {
                return e.getKey();
            }
        }
        throw new IllegalStateException("no wallet UTxO for " + hex(pkh).substring(0, 6));
    }

    static byte[] bytesAt(PlutusData.ConstrData d, int i) {
        return ((PlutusData.BytesData) d.fields().get(i)).value();
    }

    static BigInteger intAt(PlutusData.ConstrData d, int i) {
        return ((PlutusData.IntData) d.fields().get(i)).value();
    }

    static Registry.Ballot ballotOf(PlutusData.ConstrData node) {
        var v = (PlutusData.ConstrData) node.fields().get(3);
        return new Registry.Ballot(bytesAt(v, 0), intAt(v, 1));
    }

    static Registry.Entry entryOf(PlutusData.ConstrData node) {
        var v = (PlutusData.ConstrData) node.fields().get(3);
        JulcList<byte[]> gs = JulcList.empty();
        var items = ((PlutusData.ListData) v.fields().get(4)).items();
        for (int i = items.size() - 1; i >= 0; i--) gs = gs.prepend(((PlutusData.BytesData) items.get(i)).value());
        return new Registry.Entry(bytesAt(v, 0), bytesAt(v, 1), intAt(v, 2), intAt(v, 3), gs, intAt(v, 5));
    }

    // ------------------------------------------------------------------ datums (the contract's ABI)

    static PlutusData data(Object o) {
        return StateHarness.data(o);
    }

    static PlutusData head(Kind kind, byte[] inst, byte[] next, BigInteger linkedAt) {
        return data(kind == Kind.BALLOT ? new RegistryValidator.Head_Ballot(inst, next, linkedAt)
                : new RegistryValidator.Head_Entry(inst, next, linkedAt));
    }

    static PlutusData keyed(Kind kind, byte[] inst, byte[] next, BigInteger linkedAt, Object value) {
        return data(kind == Kind.BALLOT ? new RegistryValidator.Keyed_Ballot(inst, next, linkedAt, (Registry.Ballot) value)
                : new RegistryValidator.Keyed_Entry(inst, next, linkedAt, (Registry.Entry) value));
    }

    static PlutusData retired(Kind kind, byte[] inst, byte[] next, BigInteger linkedAt, byte[] key) {
        return data(kind == Kind.BALLOT ? new RegistryValidator.Retired_Ballot(inst, next, linkedAt, key)
                : new RegistryValidator.Retired_Entry(inst, next, linkedAt, key));
    }

    /** The datum with {@code next} and {@code linkedAt} replaced (D4b). */
    static PlutusData relinkedDatum(PlutusData.ConstrData d, byte[] next, BigInteger upper) {
        var fs = new ArrayList<>(d.fields());
        fs.set(1, new PlutusData.BytesData(next));
        fs.set(2, new PlutusData.IntData(upper));
        return new PlutusData.ConstrData(d.constructorTag(), fs);
    }

    TxOut stateOut(Value v, PlutusData datum) {
        return new TxOut(state, v, new OutputDatum.OutputDatumInline(datum), Optional.empty());
    }

    // ------------------------------------------------------------------ building transactions

    /** An honest transaction, the indexes of its interesting outputs, and its effect on the reference model. */
    static final class Built {
        final String op;
        final Ledger.Tx tx = new Ledger.Tx();
        byte[] payer;
        int change = -1;
        int anchorOut = -1;
        int nodeOut = -1;
        TxOutRef anchor;
        TxOutRef node;
        ListId list;
        byte[] key;
        /** Applies the operation to the reference model once the transaction is accepted. */
        Runnable effect = () -> {};

        Built(String op) {
            this.op = op;
        }
    }

    ScriptPurpose mint() {
        return new ScriptPurpose.Minting(new PolicyId(policy));
    }

    static ScriptPurpose spend(TxOutRef ref) {
        return new ScriptPurpose.Spending(ref);
    }

    /** Adds the payer's wallet input and change, balancing lovelace: inputs + mint = outputs + fee. */
    Built finish(Built b, byte[] payer) {
        b.payer = payer;
        TxOutRef w = wallet(payer, ada(500), b.tx.inputs);
        b.tx.inputs.add(w);
        b.tx.witnesses.add(payer);   // the payer witnesses its input; it is a required signer only if the call needs it
        b.tx.fee = FEE;
        b.tx.outputs.add(StateHarness.walletOut(payer, BigInteger.ONE));
        b.change = b.tx.outputs.size() - 1;
        if (!rebalance(b.tx, b.change)) throw new IllegalStateException("cannot balance " + b.op);
        return b;
    }

    /**
     * Sets output {@code change} to whatever balances the transaction (lovelace and any tokens left over); false if
     * something would be negative (the transaction then cannot be valid in phase 1).
     */
    boolean rebalance(Ledger.Tx tx, int change) {
        // A builder witnesses every required signer it lists (dropping a signer keeps its witness).
        for (byte[] s : tx.signers) if (tx.witnesses.stream().noneMatch(x -> Arrays.equals(x, s))) tx.witnesses.add(s);
        var bal = new TreeMap<String, Map<String, BigInteger>>();
        for (TxOutRef in : tx.inputs) {
            TxOut o = ledger.utxo(in);
            if (o == null) return false;
            addTo(bal, Ledger.entries(o.value()), 1);
        }
        addTo(bal, tx.mint, 1);
        for (int i = 0; i < tx.outputs.size(); i++) if (i != change) addTo(bal, Ledger.entries(tx.outputs.get(i).value()), -1);
        addTo(bal, Map.of("", Map.of("", tx.fee)), -1);
        for (var p : bal.values()) for (var q : p.values()) if (q.signum() < 0) return false;
        TxOut old = tx.outputs.get(change);
        tx.outputs.set(change, new TxOut(old.address(), Ledger.valueOf(bal), old.datum(), old.referenceScript()));
        return true;
    }

    private static void addTo(Map<String, Map<String, BigInteger>> acc, Map<String, Map<String, BigInteger>> v, int sign) {
        v.forEach((p, ns) -> ns.forEach((n, q) -> acc.computeIfAbsent(p, k -> new TreeMap<>())
                .merge(n, sign > 0 ? q : q.negate(), BigInteger::add)));
    }

    /** A validity window starting now (on a slot boundary), of {@code length} ms. */
    BigInteger[] windowNow(long length) {
        BigInteger lo = ledger.slotStart();
        return new BigInteger[]{lo, lo.add(BigInteger.valueOf(length))};
    }

    /**
     * The window of a relink of {@code x} (D7 step 4): from now, or from the first slot at or after x.linkedAt + C,
     * waiting (advancing the clock) until then; at most W long.
     */
    BigInteger[] linkWindow(Utxo x) {
        BigInteger linkedAt = intAt(x.datum(), 2);
        BigInteger ready = linkedAt.add(BigInteger.valueOf(C));
        BigInteger slot = BigInteger.valueOf(Ledger.SLOT_MS);
        BigInteger readySlot = ready.add(slot).subtract(BigInteger.ONE).divide(slot).multiply(slot);   // round up
        BigInteger lo = waitForCooldown ? ledger.slotStart().max(readySlot) : ledger.slotStart();
        if (lo.compareTo(ledger.now()) > 0) ledger.setNow(lo);
        return new BigInteger[]{lo, lo.add(BigInteger.valueOf(windowLength))};
    }

    /**
     * Tagged payout outputs. One owed less than the ledger minimum (a node can hold a gift below it, D4b) is padded up
     * to that minimum by the payer: the payout check asks for at least what is owed.
     */
    void payouts(Built b, TxOutRef tag, JulcList<Payout> payouts) {
        Map<String, BigInteger> owed = new LinkedHashMap<>();
        for (Payout p : payouts) owed.merge(hex(p.to()), p.lovelace(), BigInteger::add);
        for (var e : owed.entrySet()) {
            TxOut o = new TxOut(StateHarness.pkhAddress(hex(e.getKey())), Value.lovelace(e.getValue()),
                    new OutputDatum.OutputDatumInline(data(tag)), Optional.empty());
            BigInteger amount = e.getValue();
            for (int i = 0; i < 3; i++) {   // a larger amount can take a wider encoding, and so raise the minimum
                TxOut c = new TxOut(o.address(), Value.lovelace(amount), o.datum(), o.referenceScript());
                amount = amount.max(ledger.minLovelace(c));
            }
            b.tx.outputs.add(new TxOut(o.address(), Value.lovelace(amount), o.datum(), o.referenceScript()));
        }
    }

    // ------------------------------------------------------------------ operations

    /** CreateInstance with both heads (D3). Returns the instance id. */
    byte[] createInstance() {
        Built b = createInstanceTx();
        var r = ledger.submit(b.tx);
        if (!r.accepted()) fail("createInstance rejected: " + r);
        b.effect.run();
        return hex(b.list.instance());
    }

    Built createInstanceTx() {
        Built b = new Built("createInstance");
        TxOutRef seed = wallet(ADMIN, ada(500), List.of());
        byte[] inst = hex(StateContractHandle.instanceIdOf(hex(seed.txId().hash()), seed.index().intValueExact()));
        BigInteger[] win = windowNow(W);
        Ctx ctx = new Ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO, win[0], win[1]);
        b.tx.window(win[0], win[1]);
        b.tx.inputs.add(seed);
        b.tx.outputs.add(stateOut(Ledger.value(minAda, policy, inst), data(Registry.init(ctx))));
        for (Kind k : Kind.values()) {
            byte[] hid = keyId(inst, k, new byte[0]);
            b.tx.outputs.add(stateOut(Ledger.value(minAda, policy, hid), head(k, inst, new byte[0], BigInteger.ZERO)));
            b.tx.mint(policy, hid, BigInteger.ONE);
        }
        b.tx.mint(policy, inst, BigInteger.ONE);
        b.tx.redeemers.put(mint(), data(new RegistryValidator.CreateInstance(seed, ctx)));
        b.tx.signers.add(ADMIN);
        b.list = new ListId(hex(inst), Kind.BALLOT);
        b.effect = () -> {
            for (Kind k : Kind.values()) model.put(new ListId(hex(inst), k), new TreeMap<>(KEY_ORDER));
        };
        return finish(b, ADMIN);
    }

    /** The predecessor of {@code key} in the reference model: the greatest key below it, or the head (""). */
    String predecessor(ListId l, String key) {
        String p = model.get(l).lowerKey(key);
        return p == null ? "" : p;
    }

    /**
     * An insert of {@code key} (D4): the anchor from the reference model, its window after the cooldown, then the
     * method's value and redeemer from {@code make} (given the Ctx), the relinked anchor and the new node.
     */
    Built insert(String op, byte[] inst, Kind kind, byte[] key, byte[] caller, BigInteger received,
                 java.util.function.BiFunction<Ctx, TxOutRef, Object[]> make, boolean shared) {
        Built b = new Built(op);
        ListId l = new ListId(hex(inst), kind);
        String pred = predecessor(l, hex(key));
        Utxo f = forcedAnchor();
        Utxo a = f != null ? f : node(inst, kind, hex(pred));
        BigInteger[] win = linkWindow(a);
        Ctx ctx = new Ctx(caller, received, BigInteger.ZERO, win[0], win[1]);
        Object[] vr = make.apply(ctx, a.ref());   // {value, redeemer}
        b.tx.window(win[0], win[1]);
        b.tx.inputs.add(a.ref());
        b.tx.redeemers.put(spend(a.ref()), data(new RegistryValidator.Link()));
        if (shared) b.tx.referenceInputs.add(instanceUtxo(inst).ref());
        byte[] id = keyId(inst, kind, key);
        b.tx.mint(policy, id, BigInteger.ONE);
        b.tx.redeemers.put(mint(), data(vr[1]));
        byte[] after = bytesAt(a.datum(), 1);
        b.tx.outputs.add(new TxOut(a.out().address(), a.out().value(), new OutputDatum.OutputDatumInline(
                relinkedDatum(a.datum(), key, win[1])), Optional.empty()));
        b.anchorOut = b.tx.outputs.size() - 1;
        b.tx.outputs.add(stateOut(Ledger.value(minAda.add(received), policy, id), keyed(kind, inst, after, win[1], vr[0])));
        b.nodeOut = b.tx.outputs.size() - 1;
        b.tx.signers.add(caller);
        b.anchor = a.ref();
        b.list = l;
        b.key = key;
        b.effect = () -> model.get(l).put(hex(key), false);
        return finish(b, caller);
    }

    Built cast(byte[] inst, byte[] voter, BigInteger choice) {
        return castBy(inst, voter, voter, choice);
    }

    /** A ballot for {@code voter} cast by {@code caller} (signedKey then requires the voter's signature too). */
    Built castBy(byte[] inst, byte[] caller, byte[] voter, BigInteger choice) {
        Registry.State s = (Registry.State) stateOf(inst);
        return insert("cast", inst, Kind.BALLOT, voter, caller, BigInteger.ZERO, (ctx, a) -> new Object[]{
                Registry.cast(s, voter, choice, ctx), new RegistryValidator.Insert_cast(inst, a, voter, choice, ctx)}, true);
    }

    Built claim(byte[] inst, byte[] caller, byte[] name, BigInteger pay, BigInteger tier, JulcList<byte[]> guardians,
                BigInteger needed) {
        return insert("claim", inst, Kind.ENTRY, name, caller, pay, (ctx, a) -> new Object[]{
                Registry.claim(name, tier, guardians, needed, ctx),
                new RegistryValidator.Insert_claim(inst, a, name, tier, guardians, needed, ctx)}, false);
    }

    Built claim(byte[] inst, byte[] caller, byte[] name, BigInteger pay) {
        JulcList<byte[]> none = JulcList.empty();
        return claim(inst, caller, name, pay, BigInteger.ONE, none.prepend(caller), BigInteger.ONE);
    }

    Built grant(byte[] inst, byte[] name, byte[] owner) {
        Registry.State s = (Registry.State) stateOf(inst);
        return insert("grant", inst, Kind.ENTRY, name, ADMIN, BigInteger.ZERO, (ctx, a) -> new Object[]{
                Registry.grant(s, name, owner, ctx), new RegistryValidator.Insert_grant(inst, a, name, owner, ctx)}, true);
    }

    Object stateOf(byte[] inst) {
        var d = instanceUtxo(inst).datum();
        return new Registry.State(bytesAt(d, 0), ((PlutusData.ConstrData) d.fields().get(1)).constructorTag().signum() != 0);
    }

    /** A keyed action on a live node: {@code run} gives the next value and payouts from the current value and Ctx. */
    Built act(String op, byte[] inst, Kind kind, byte[] key, byte[] signer, BigInteger received, boolean usesCtx,
              Function<Object[], Object[]> run, List<byte[]> extraSigners) {
        Built b = new Built(op);
        Utxo n = node(inst, kind, key);
        Object current = kind == Kind.BALLOT ? ballotOf(n.datum()) : entryOf(n.datum());
        Ctx ctx = null;
        if (usesCtx) {
            BigInteger[] win = windowNow(W);
            ctx = new Ctx(signer, received, n.lovelace().subtract(minAda), win[0], win[1]);
            b.tx.window(win[0], win[1]);
        }
        Object[] r = run.apply(new Object[]{current, ctx});   // {next value, payouts (or null), redeemer}
        @SuppressWarnings("unchecked")
        JulcList<Payout> pay = r[1] == null ? JulcList.empty() : (JulcList<Payout>) r[1];
        b.tx.inputs.add(n.ref());
        b.tx.redeemers.put(spend(n.ref()), data(r[2]));
        b.tx.outputs.add(stateOut(Ledger.value(n.lovelace().add(received).subtract(StateHarness.total(pay)), policy,
                keyId(inst, kind, key)), keyed(kind, inst, bytesAt(n.datum(), 1), intAt(n.datum(), 2), r[0])));
        b.nodeOut = b.tx.outputs.size() - 1;
        payouts(b, n.ref(), pay);
        b.tx.signers.add(signer);
        for (byte[] s : extraSigners) b.tx.signers.add(s);
        b.node = n.ref();
        b.list = new ListId(hex(inst), kind);
        b.key = key;
        return finish(b, signer);
    }

    Built change(byte[] inst, byte[] voter, BigInteger choice) {
        return act("change", inst, Kind.BALLOT, voter, voter, BigInteger.ZERO, false, c -> new Object[]{
                Registry.change((Registry.Ballot) c[0], choice), null, new RegistryValidator.Change(choice)}, List.of());
    }

    Built upgrade(byte[] inst, byte[] name, byte[] owner, BigInteger pay, BigInteger tier) {
        return act("upgrade", inst, Kind.ENTRY, name, owner, pay, true, c -> new Object[]{
                Registry.upgrade((Registry.Entry) c[0], tier, (Ctx) c[1]), null,
                new RegistryValidator.Upgrade(tier, (Ctx) c[1])}, List.of());
    }

    Built upgrade(byte[] inst, byte[] name, byte[] owner, BigInteger pay) {
        return upgrade(inst, name, owner, pay, BigInteger.TWO);
    }

    Built withdraw(byte[] inst, byte[] name, byte[] owner, BigInteger amount) {
        return act("withdraw", inst, Kind.ENTRY, name, owner, BigInteger.ZERO, true, c -> {
            var r = Registry.withdraw((Registry.Entry) c[0], amount, (Ctx) c[1]);
            return new Object[]{r.state(), r.payouts(), new RegistryValidator.Withdraw(amount, (Ctx) c[1])};
        }, List.of());
    }

    Built recover(byte[] inst, byte[] name, byte[] newOwner, List<byte[]> guardians) {
        return act("recover", inst, Kind.ENTRY, name, guardians.get(0), BigInteger.ZERO, false, c -> new Object[]{
                Registry.recover((Registry.Entry) c[0], newOwner), null, new RegistryValidator.Recover(newOwner)},
                guardians.subList(1, guardians.size()));
    }

    /** The retiring exit (D10): payouts of everything but the deposit, the node continues as Retired_T (+ topUp). */
    Built retire(byte[] inst, Kind kind, byte[] key, BigInteger topUp) {
        Built b = new Built(kind == Kind.BALLOT ? "leave" : "retire");
        Utxo n = node(inst, kind, key);
        PlutusData.ConstrData d = n.datum();
        byte[] owner = kind == Kind.BALLOT ? ballotOf(d).voter() : entryOf(d).owner();
        BigInteger[] win = windowNow(W);
        Ctx ctx = new Ctx(owner, BigInteger.ZERO, n.lovelace().subtract(minAda), win[0], win[1]);
        JulcList<Payout> pay = kind == Kind.BALLOT ? Registry.leave(ballotOf(d), ctx) : Registry.retire(entryOf(d), ctx);
        b.tx.window(win[0], win[1]);
        b.tx.inputs.add(n.ref());
        b.tx.redeemers.put(spend(n.ref()), data(kind == Kind.BALLOT ? new RegistryValidator.Leave(ctx)
                : new RegistryValidator.Retire(ctx)));
        b.tx.outputs.add(stateOut(Ledger.value(minAda.add(topUp), policy, keyId(inst, kind, key)),
                retired(kind, inst, bytesAt(d, 1), intAt(d, 2), key)));
        b.nodeOut = 0;
        payouts(b, n.ref(), pay);
        b.tx.signers.add(owner);
        b.node = n.ref();
        b.list = new ListId(hex(inst), kind);
        b.key = key;
        ListId l = b.list;
        b.effect = () -> model.get(l).put(hex(key), true);
        return finish(b, owner);
    }

    /** A remove (D5): N spent with its removing destroy, P with Link and relinked past N, N's token burned. */
    Built release(byte[] inst, byte[] name) {
        Built b = new Built("release");
        ListId l = new ListId(hex(inst), Kind.ENTRY);
        Utxo n = node(inst, Kind.ENTRY, name);
        Utxo f = forcedAnchor();
        Utxo p = f != null ? f : node(inst, Kind.ENTRY, hex(predecessor(l, hex(name))));
        // A retired N (an attack) has no record to run release on: its former owner signs and takes everything.
        boolean live = n.datum().constructorTag().intValueExact() == 4;
        Registry.Entry e = live ? entryOf(n.datum()) : new Registry.Entry(name, PARTIES.get(0), BigInteger.ZERO,
                BigInteger.ZERO, JulcList.empty(), BigInteger.ZERO);
        BigInteger[] win = linkWindow(p);
        Ctx ctx = new Ctx(e.owner(), BigInteger.ZERO, n.lovelace(), win[0], win[1]);
        JulcList<Payout> pay = Registry.release(e, ctx);
        b.tx.window(win[0], win[1]);
        b.tx.inputs.add(p.ref());
        b.tx.inputs.add(n.ref());
        b.tx.redeemers.put(spend(p.ref()), data(new RegistryValidator.Link()));
        b.tx.redeemers.put(spend(n.ref()), data(new RegistryValidator.Release(ctx)));
        b.tx.mint(policy, keyId(inst, Kind.ENTRY, name), BigInteger.ONE.negate());
        b.tx.redeemers.put(mint(), data(new RegistryValidator.Remove_Entry(p.ref(), n.ref())));
        b.tx.outputs.add(new TxOut(p.out().address(), p.out().value(), new OutputDatum.OutputDatumInline(
                relinkedDatum(p.datum(), bytesAt(n.datum(), 1), win[1])), Optional.empty()));
        b.anchorOut = 0;
        payouts(b, n.ref(), pay);
        b.tx.signers.add(e.owner());
        b.anchor = p.ref();
        b.node = n.ref();
        b.list = l;
        b.key = name;
        b.effect = () -> model.get(l).remove(hex(name));
        return finish(b, e.owner());
    }

    private Utxo forcedAnchor() {
        Utxo f = forced;
        forced = null;
        return f;
    }

    /** Forces the anchor (or predecessor) of the next structural operation. */
    RegistryWorld anchoredAt(byte[] inst, Kind kind, byte[] key) {
        forced = node(inst, kind, key);
        return this;
    }

    /** Forces the anchor (or predecessor) of the next structural operation to the UTxO at {@code ref}. */
    RegistryWorld anchoredAt(TxOutRef ref) {
        forced = new Utxo(ref, ledger.utxo(ref));
        return this;
    }

    /** A copy of {@code b}'s transaction, changed by {@code f} and rebalanced through its change output. */
    Ledger.Tx mutate(Built b, java.util.function.Consumer<Ledger.Tx> f) {
        return mutate(b, b.change, f);
    }

    /** As {@link #mutate(Built, java.util.function.Consumer)}, when the change output ends up at index {@code change}. */
    Ledger.Tx mutate(Built b, int change, java.util.function.Consumer<Ledger.Tx> f) {
        Ledger.Tx t = b.tx.copy();
        f.accept(t);
        if (!rebalance(t, change)) throw new IllegalStateException(b.op + ": the mutation cannot be balanced");
        return t;
    }

    // ------------------------------------------------------------------ submitting

    Ledger.Result submit(Built b) {
        Ledger.Result r = ledger.submit(b.tx);
        if (r.accepted()) b.effect.run();
        return r;
    }

    Ledger.Result accept(Built b) {
        Ledger.Result r = submit(b);
        if (!r.accepted()) fail(b.op + ": expected acceptance:\n" + r);
        checkLists();
        return r;
    }

    /**
     * The attack must be refused by a script (phase 2): a refusal by the ledger's own rules would say nothing about the
     * validator. Use {@link #rejectByLedger} for the ledger model's own cases.
     */
    void reject(Built b, Ledger.Tx tx, String what) {
        Ledger.Result r = ledger.check(tx);
        if (r.accepted()) fail(b.op + " / " + what + ": expected rejection, but the ledger model accepted it:\n" + r);
        if (!r.reason().startsWith("script failed")) {
            fail(b.op + " / " + what + ": expected a script to refuse it, but phase 1 did: " + r.reason());
        }
    }

    /** The attack must be refused by the ledger's own rules (phase 1), with a reason starting with {@code reason}. */
    void rejectByLedger(Built b, Ledger.Tx tx, String reason, String what) {
        Ledger.Result r = ledger.check(tx);
        if (r.accepted()) fail(b.op + " / " + what + ": expected rejection, but the ledger model accepted it:\n" + r);
        if (!r.reason().startsWith(reason)) fail(b.op + " / " + what + ": expected '" + reason + "', got: " + r.reason());
    }

    // ------------------------------------------------------------------ I47

    /**
     * I47, checked from the UTxO set against the reference model: for each list, following next from the head visits
     * strictly increasing keys, each node of its own instance and type, and exactly the model's keys (retired ones
     * marked); and the 0x4B units held anywhere are exactly those of the walks, each with total quantity 1.
     */
    void checkLists() {
        var expectedUnits = new TreeMap<String, BigInteger>();
        for (var e : model.entrySet()) {
            ListId l = e.getKey();
            byte[] inst = hex(l.instance());
            var walked = new TreeMap<String, Boolean>(KEY_ORDER);
            String key = "";
            String prev = null;
            int guard = 0;
            while (true) {
                byte[] unit = keyId(inst, l.kind(), hex(key));
                expectedUnits.merge(hex(unit), BigInteger.ONE, BigInteger::add);
                Utxo x = holder(unit);
                assertEquals(state, x.out().address(), "a list UTxO left the state address");
                assertTrue(x.lovelace().compareTo(minAda) >= 0, "a list UTxO holds less than its deposit");
                assertEquals(1, Ledger.entries(x.out().value()).size() - 1, "a list UTxO holds another policy");
                PlutusData.ConstrData d = x.datum();
                assertTrue(Arrays.equals(inst, bytesAt(d, 0)), "a node of another instance in the walk");
                int tag = d.constructorTag().intValueExact();
                int headTag = l.kind() == Kind.BALLOT ? 0 : 3;
                if (key.isEmpty()) {
                    assertEquals(headTag, tag, "the head is not a Head_T");
                } else {
                    assertTrue(tag == headTag + 1 || tag == headTag + 2, "a list node is neither live nor retired: " + tag);
                    byte[] k = tag == headTag + 1 ? bytesAt((PlutusData.ConstrData) d.fields().get(3), 0) : bytesAt(d, 3);
                    assertEquals(key, hex(k), "a node's key does not match its token");
                    if (prev != null) assertTrue(KEY_ORDER.compare(prev, key) < 0, "keys not strictly increasing");
                    walked.put(key, tag == headTag + 2);
                    prev = key;
                }
                String next = hex(bytesAt(d, 1));
                if (next.isEmpty()) break;
                key = next;
                if (++guard > 100_000) fail("cycle in " + l);
            }
            assertEquals(e.getValue(), walked, "the head walk of " + l + " differs from the reference set");
        }
        var held = new TreeMap<String, BigInteger>();
        ledger.tokensOf(policy).forEach((n, q) -> {
            if (n.length() == 58 && n.startsWith("4b")) held.put(n, q);
        });
        assertEquals(expectedUnits, held, "the 0x4B units held anywhere differ from the walks");
    }
}
