package org.julclang.state.examples.registry.widest;

import org.julclang.core.PlutusData;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.ScriptPurpose;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.state.api.Ctx;
import org.julclang.state.runtime.OwnedObject;
import org.julclang.state.testkit.Ledger;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The widest contract (two keyed types and a plain owned type) on the ledger model: token kinds decide arms across
 * plain and keyed types (I52), Link is refused under a plain creation's mint redeemer (I48), a structural operation
 * cannot share its mint redeemer with a plain creation, and a zero payout is refused on a retire (I7, I55).
 * <p>
 * The starting UTxOs are put on the ledger directly (as if created earlier), with datums of the contract's ABI.
 */
class WidestTest {

    static final byte[] ANN = StateHarness.fill(28, 0xA1);
    static final byte[] BOB = StateHarness.fill(28, 0xB0);
    static final BigInteger T = BigInteger.valueOf(1_800_000_000_000L);
    static final byte[] B = {0x62};

    StateHarness<Widest.State> h;
    Ledger ledger;
    byte[] policy;
    byte[] inst;
    BigInteger minAda;
    TxOutRef state;
    TxOutRef entryHead;
    TxOutRef entry;
    TxOutRef vault;
    byte[] vaultId;

    /** keyId = 0x4B ++ blake2b_224(instance ++ I2OSP(len(name), 1) ++ utf8(name) ++ key) (ADR-0016 D2). */
    static byte[] keyIdOfType(byte[] instance, String typeName, byte[] key) {
        byte[] n = typeName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] pre = new byte[instance.length + 1 + n.length + key.length];
        System.arraycopy(instance, 0, pre, 0, instance.length);
        pre[instance.length] = (byte) n.length;
        System.arraycopy(n, 0, pre, instance.length + 1, n.length);
        System.arraycopy(key, 0, pre, instance.length + 1 + n.length, key.length);
        byte[] id = new byte[29];
        id[0] = 0x4B;
        System.arraycopy(com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash224(pre), 0, id, 1, 28);
        return id;
    }

    static PlutusData data(Object o) {
        return StateHarness.data(o);
    }

    TxOut at(Value v, Object datum) {
        return new TxOut(h.stateAddress(), v, new OutputDatum.OutputDatumInline(data(datum)), Optional.empty());
    }

    Value value(BigInteger lovelace, byte[] token) {
        return Ledger.value(lovelace, policy, token);
    }

    @BeforeEach
    void setUp() {
        h = StateHarness.of(WidestClient.SPEC);
        policy = h.policy();
        inst = h.instanceId();
        minAda = BigInteger.valueOf(h.minAda());
        ledger = new Ledger(Ledger.Params.mainnet(), T);
        ledger.register(policy, h.program());
        for (int i = 0; i < 5; i++) {
            ledger.fund(StateHarness.walletOut(ANN, BigInteger.valueOf(1_000_000_000L)));
            ledger.fund(StateHarness.walletOut(BOB, BigInteger.valueOf(1_000_000_000L)));
        }
        state = ledger.fund(at(value(minAda, inst), new Widest.State(ANN, true)));
        entryHead = ledger.fund(at(value(minAda, keyIdOfType(inst, "Entry", new byte[0])),
                new WidestValidator.Head_Entry(inst, B, BigInteger.ZERO)));
        entry = ledger.fund(at(value(minAda.add(BigInteger.valueOf(5_000_000)), keyIdOfType(inst, "Entry", B)),
                new WidestValidator.Keyed_Entry(inst, new byte[0], BigInteger.ZERO, new Widest.Entry(B, ANN, BigInteger.valueOf(5_000_000)))));
        vaultId = HexFormat.of().parseHex(OwnedObject.objectIdOf(HexFormat.of().formatHex(StateHarness.fill(32, 0x77)), 0));
        vault = ledger.fund(at(value(minAda, vaultId), new WidestValidator.Holding_Vault(inst, new Widest.Vault(ANN, BigInteger.ZERO))));
    }

    ScriptPurpose mint() {
        return new ScriptPurpose.Minting(new PolicyId(policy));
    }

    /** A transaction from {@code payer}'s wallet: change last, balanced. */
    Ledger.Tx tx(byte[] payer) {
        Ledger.Tx t = new Ledger.Tx();
        TxOutRef w = ledger.at(StateHarness.pkhAddress(payer)).get(0).getKey();
        t.inputs.add(w);
        t.signers.add(payer);
        t.witnesses.add(payer);
        t.window(T, T.add(BigInteger.valueOf(120_000)));
        return t;
    }

    Ledger.Tx balanced(Ledger.Tx t, byte[] payer) {
        var bal = new java.util.TreeMap<String, java.util.Map<String, BigInteger>>();
        for (TxOutRef in : t.inputs) add(bal, Ledger.entries(ledger.utxo(in).value()), 1);
        add(bal, t.mint, 1);
        for (TxOut o : t.outputs) add(bal, Ledger.entries(o.value()), -1);
        add(bal, java.util.Map.of("", java.util.Map.of("", t.fee)), -1);
        t.outputs.add(new TxOut(StateHarness.pkhAddress(payer), Ledger.valueOf(bal), new OutputDatum.NoOutputDatum(), Optional.empty()));
        return t;
    }

    static void add(java.util.Map<String, java.util.Map<String, BigInteger>> acc, java.util.Map<String, java.util.Map<String, BigInteger>> v,
                    int sign) {
        v.forEach((p, ns) -> ns.forEach((n, q) -> acc.computeIfAbsent(p, k -> new java.util.TreeMap<>())
                .merge(n, sign > 0 ? q : q.negate(), BigInteger::add)));
    }

    Ctx ctx(byte[] caller, BigInteger received, BigInteger balance) {
        return new Ctx(caller, received, balance, T, T.add(BigInteger.valueOf(120_000)));
    }

    void accepted(Ledger.Tx t, String what) {
        var r = ledger.check(t);
        assertTrue(r.accepted(), what + ": " + r);
    }

    void rejectedByAScript(Ledger.Tx t, String what) {
        var r = ledger.check(t);
        assertFalse(r.accepted(), what + ": expected rejection");
        assertTrue(r.reason().startsWith("script failed"), what + ": " + r.reason());
    }

    /** Refused by a script, with {@code label} among the purposes that failed (others may fail too). */
    void rejectedWith(Ledger.Tx t, String what, String label) {
        var r = ledger.check(t);
        assertFalse(r.accepted(), what + ": expected rejection");
        assertTrue(r.reason().startsWith("script failed: "), what + ": " + r.reason());
        assertTrue(java.util.Arrays.asList(r.reason().substring("script failed: ".length()).split(", ")).contains(label),
                what + ": expected " + label + " among the failing purposes: " + r.reason());
    }

    String mintLabel() {
        return "MINT " + HexFormat.of().formatHex(policy).substring(0, 8);
    }

    static String spendLabel(TxOutRef r) {
        return "SPEND " + HexFormat.of().formatHex(r.txId().hash()).substring(0, 8) + "#" + r.index();
    }

    /** Refused by a script, the purposes that failed being exactly {@code labels}. */
    void rejectedBy(Ledger.Tx t, String what, String... labels) {
        var r = ledger.check(t);
        assertFalse(r.accepted(), what + ": expected rejection");
        assertEquals("script failed: " + String.join(", ", labels), r.reason(), what);
    }

    // ------------------------------------------------------------------ the Ctx-less insert, C > 0 (Ballot)

    /**
     * Inserts Bob's ballot after the Ballot head (key at component index 1, no Ctx: only linkUpper bounds the window),
     * with the head last relinked at {@code headLinkedAt} and validity {@code [lower, upper)}.
     */
    Ledger.Tx castAfterHead(BigInteger headLinkedAt, BigInteger lower, BigInteger upper, BigInteger nodeLinkedAt) {
        TxOutRef head = ledger.fund(at(value(minAda, keyIdOfType(inst, "Ballot", new byte[0])),
                new WidestValidator.Head_Ballot(inst, new byte[0], headLinkedAt)));
        Ledger.Tx t = tx(BOB);
        t.window(lower, upper);
        t.referenceInputs.add(state);
        t.inputs.add(head);
        t.redeemers.put(new ScriptPurpose.Spending(head), data(new WidestValidator.Link()));
        t.outputs.add(at(value(minAda, keyIdOfType(inst, "Ballot", new byte[0])), new WidestValidator.Head_Ballot(inst, BOB, nodeLinkedAt)));
        byte[] id = keyIdOfType(inst, "Ballot", BOB);
        t.mint(policy, id, BigInteger.ONE);
        t.redeemers.put(mint(), data(new WidestValidator.Insert_cast(inst, head, BOB, BigInteger.TWO)));
        t.outputs.add(at(value(minAda, id), new WidestValidator.Keyed_Ballot(inst, new byte[0], nodeLinkedAt,
                Widest.cast(new Widest.State(ANN, true), BOB, BigInteger.TWO))));
        lastHead = head;
        return balanced(t, BOB);
    }

    TxOutRef lastHead;

    @Test
    void aCtxLessInsertIsBoundByTheLinkChecksAlone() {
        BigInteger w = BigInteger.valueOf(120_000);
        accepted(castAfterHead(BigInteger.ZERO, T, T.add(w), T.add(w)), "the Ctx-less insert");
        var unsigned = castAfterHead(BigInteger.ZERO, T, T.add(w), T.add(w));
        unsigned.signers.clear();
        rejectedBy(unsigned, "signedKey without the voter's signature", mintLabel());
        var infiniteUpper = castAfterHead(BigInteger.ZERO, T, T.add(w), T.add(w));
        infiniteUpper.upper = null;
        rejectedBy(infiniteUpper, "an infinite upper bound", mintLabel());
        var infiniteLower = castAfterHead(BigInteger.ZERO, T, T.add(w), T.add(w));
        infiniteLower.lower = null;
        rejectedBy(infiniteLower, "an infinite lower bound", mintLabel());
        BigInteger longer = T.add(w).add(BigInteger.valueOf(1_000));
        rejectedBy(castAfterHead(BigInteger.ZERO, T, longer, longer), "a window longer than W", mintLabel());
        // The head was relinked 30 s before T: with C = 60 s, a relink starting at T is too early, one at T + 30 s is not.
        BigInteger relinked = T.subtract(BigInteger.valueOf(30_000));
        rejectedBy(castAfterHead(relinked, T, T.add(w), T.add(w)), "lower < A.linkedAt + C", mintLabel());
        ledger.setNow(T.add(BigInteger.valueOf(30_000)));
        BigInteger lo = T.add(BigInteger.valueOf(30_000));
        accepted(castAfterHead(relinked, lo, lo.add(w), lo.add(w)), "lower == A.linkedAt + C");
    }

    @Test
    void keyAtIndexOneActsAndRetires() {
        BigInteger w = BigInteger.valueOf(120_000);
        var r = ledger.submit(castAfterHead(BigInteger.ZERO, T, T.add(w), T.add(w)));
        assertTrue(r.accepted(), r.toString());
        TxOutRef node = new TxOutRef(r.txId(), BigInteger.ONE);
        byte[] id = keyIdOfType(inst, "Ballot", BOB);
        Ledger.Tx change = tx(BOB);
        change.window(null, null);
        change.inputs.add(node);
        change.redeemers.put(new ScriptPurpose.Spending(node), data(new WidestValidator.Change(BigInteger.ONE)));
        change.outputs.add(at(value(minAda, id), new WidestValidator.Keyed_Ballot(inst, new byte[0], T.add(w),
                new Widest.Ballot(BigInteger.ONE, BOB))));
        accepted(balanced(change.copy(), BOB), "a keyed action on a key at index 1");
        Ledger.Tx moved = change.copy();
        moved.outputs.set(0, at(value(minAda, id), new WidestValidator.Keyed_Ballot(inst, new byte[0], T.add(w),
                new Widest.Ballot(BigInteger.ONE, ANN))));
        rejectedBy(balanced(moved, BOB), "the key at index 1 changed", spendLabel(node));
        Ledger.Tx leave = tx(BOB);
        leave.inputs.add(node);
        leave.redeemers.put(new ScriptPurpose.Spending(node), data(new WidestValidator.Leave(ctx(BOB, BigInteger.ZERO, BigInteger.ZERO))));
        leave.outputs.add(at(value(minAda, id), new WidestValidator.Retired_Ballot(inst, new byte[0], T.add(w), BOB)));
        accepted(balanced(leave, BOB), "a retire of a key at index 1");
    }

    // ------------------------------------------------------------------ C = 0 (Entry)

    @Test
    void noCooldown() {
        // b was relinked "in the future" (its last upper bound is T + 100 s): with C = 0 only the window rule applies.
        TxOutRef b = ledger.fund(at(value(minAda, keyIdOfType(inst, "Entry", new byte[]{0x70})),
                new WidestValidator.Keyed_Entry(inst, new byte[0], T.add(BigInteger.valueOf(100_000)),
                        new Widest.Entry(new byte[]{0x70}, ANN, BigInteger.ZERO))));
        java.util.function.BiFunction<BigInteger, BigInteger, Ledger.Tx> insert = (lower, upper) -> {
            byte[] c = {0x71};
            Ctx cx = new Ctx(BOB, BigInteger.ZERO, BigInteger.ZERO, lower, upper);
            Ledger.Tx t = tx(BOB);
            t.window(lower, upper);
            t.inputs.add(b);
            t.redeemers.put(new ScriptPurpose.Spending(b), data(new WidestValidator.Link()));
            TxOut bo = ledger.utxo(b);
            t.outputs.add(at(bo.value(), new WidestValidator.Keyed_Entry(inst, c, upper, new Widest.Entry(new byte[]{0x70}, ANN,
                    BigInteger.ZERO))));
            byte[] cid = keyIdOfType(inst, "Entry", c);
            t.mint(policy, cid, BigInteger.ONE);
            t.outputs.add(at(value(minAda, cid), new WidestValidator.Keyed_Entry(inst, new byte[0], upper, Widest.claim(c, cx))));
            t.redeemers.put(mint(), data(new WidestValidator.Insert_claim(inst, b, c, cx)));
            return balanced(t, BOB);
        };
        BigInteger w = BigInteger.valueOf(120_000);
        accepted(insert.apply(T, T.add(w)), "a relink before the previous upper bound, with C = 0");
        rejectedBy(insert.apply(T, T.add(w).add(BigInteger.valueOf(1_000))), "a window longer than W, with C = 0", mintLabel());
    }

    /** Spends {@code ref} with {@code redeemer}, continuing it with {@code next} (or unchanged). */
    Ledger.Tx spend(TxOutRef ref, Object redeemer, TxOut next, BigInteger received) {
        Ledger.Tx t = tx(ANN);
        t.inputs.add(ref);
        t.redeemers.put(new ScriptPurpose.Spending(ref), data(redeemer));
        t.outputs.add(next == null ? ledger.utxo(ref) : next);
        return balanced(t, ANN);
    }

    @Test
    void tokenKindsDecideArms() {
        // Controls: each arm on its own kind.
        BigInteger entryIn = ledger.utxo(entry).value().lovelaceOf();
        Ctx up = ctx(ANN, BigInteger.valueOf(2_000_000), entryIn.subtract(minAda));
        var e = new Widest.Entry(B, ANN, BigInteger.valueOf(7_000_000));
        TxOut entryNext = at(value(entryIn.add(BigInteger.valueOf(2_000_000)), keyIdOfType(inst, "Entry", B)),
                new WidestValidator.Keyed_Entry(inst, new byte[0], BigInteger.ZERO, e));
        accepted(spend(entry, new WidestValidator.TopUp(up), entryNext, up.received()), "a keyed action on its node");
        TxOut vaultNext = at(value(minAda, vaultId), new WidestValidator.Holding_Vault(inst, new Widest.Vault(ANN, BigInteger.TWO)));
        Ledger.Tx stash = spend(vault, new WidestValidator.Stash(B, BigInteger.TWO), vaultNext, BigInteger.ZERO);
        stash.window(null, null);
        accepted(stash, "a plain owned action on its object");
        // A keyed node spent by a plain owned arm, and a plain object by a keyed arm.
        Ledger.Tx onNode = spend(entry, new WidestValidator.Stash(B, BigInteger.TWO), null, BigInteger.ZERO);
        onNode.window(null, null);
        rejectedByAScript(onNode, "a keyed node spent with a plain owned redeemer");
        Ctx v = ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        rejectedByAScript(spend(vault, new WidestValidator.TopUp(v), null, BigInteger.ZERO), "a plain object spent with a keyed redeemer");
        rejectedByAScript(spend(vault, new WidestValidator.Link(), null, BigInteger.ZERO), "a plain object spent with Link");
        rejectedByAScript(spend(vault, new WidestValidator.Retire(v), null, BigInteger.ZERO), "a plain object retired");
    }

    /** OpenVault by {@code caller} (who must sign): a plain creation from a wallet seed, the instance by reference. */
    Ledger.Tx openVault(Ledger.Tx t, TxOutRef seed, byte[] caller) {
        byte[] id = HexFormat.of().parseHex(OwnedObject.objectIdOf(HexFormat.of().formatHex(seed.txId().hash()),
                seed.index().intValueExact()));
        Ctx c = ctx(caller, BigInteger.ZERO, BigInteger.ZERO);
        t.referenceInputs.add(state);
        t.mint(policy, id, BigInteger.ONE);
        t.redeemers.put(mint(), data(new WidestValidator.OpenVault(seed, inst, c)));
        t.outputs.add(at(value(minAda, id), new WidestValidator.Holding_Vault(inst, Widest.openVault(new Widest.State(ANN, true), c))));
        if (t.signers.stream().noneMatch(x -> java.util.Arrays.equals(x, caller))) t.signers.add(caller);
        if (t.witnesses.stream().noneMatch(x -> java.util.Arrays.equals(x, caller))) t.witnesses.add(caller);
        return t;
    }

    @Test
    void linkUnderAPlainCreation() {
        Ledger.Tx t = tx(ANN);
        openVault(t, t.inputs.get(0), ANN);
        accepted(balanced(t, ANN), "OpenVault alone");
        Ledger.Tx withLink = tx(ANN);
        openVault(withLink, withLink.inputs.get(0), ANN);
        withLink.inputs.add(entry);
        withLink.redeemers.put(new ScriptPurpose.Spending(entry), data(new WidestValidator.Link()));
        withLink.outputs.add(ledger.utxo(entry));
        rejectedByAScript(balanced(withLink, ANN), "a node spent with Link while the mint redeemer is OpenVault (CreateObject)");
    }

    @Test
    void anInsertCannotShareItsMintWithAPlainCreation() {
        // Insert c after the Entry's b (b is last), relinking b; plus a vault, under either redeemer.
        byte[] c = {0x63};
        BigInteger upper = T.add(BigInteger.valueOf(120_000));
        Ctx cx = ctx(BOB, BigInteger.ZERO, BigInteger.ZERO);
        Ledger.Tx t = tx(BOB);
        t.inputs.add(entry);
        t.redeemers.put(new ScriptPurpose.Spending(entry), data(new WidestValidator.Link()));
        TxOut b = ledger.utxo(entry);
        var bd = (PlutusData.ConstrData) ((OutputDatum.OutputDatumInline) b.datum()).datum();
        var fs = new java.util.ArrayList<>(bd.fields());
        fs.set(1, new PlutusData.BytesData(c));
        fs.set(2, new PlutusData.IntData(upper));
        t.outputs.add(new TxOut(b.address(), b.value(), new OutputDatum.OutputDatumInline(new PlutusData.ConstrData(bd.constructorTag(), fs)),
                Optional.empty()));
        byte[] cid = keyIdOfType(inst, "Entry", c);
        t.mint(policy, cid, BigInteger.ONE);
        t.outputs.add(at(value(minAda, cid), new WidestValidator.Keyed_Entry(inst, new byte[0], upper, Widest.claim(c, cx))));
        t.redeemers.put(mint(), data(new WidestValidator.Insert_claim(inst, entry, c, cx)));
        accepted(balanced(t.copy(), BOB), "the insert alone");
        // F44: Bob's vault creation, validated on its own before it is combined with Bob's insert.
        Ledger.Tx vaultOnly = tx(BOB);
        openVault(vaultOnly, vaultOnly.inputs.get(0), BOB);
        accepted(balanced(vaultOnly, BOB), "Bob's vault alone");
        Ledger.Tx both = t.copy();
        openVault(both, both.inputs.get(0), BOB);
        Ledger.Tx underInsert = both.copy();
        underInsert.redeemers.put(mint(), data(new WidestValidator.Insert_claim(inst, entry, c, cx)));
        rejectedWith(balanced(underInsert, BOB), "an insert and a vault under the insert's redeemer", mintLabel());
        rejectedWith(balanced(both, BOB), "an insert and a vault under OpenVault", mintLabel());
    }

    @Test
    void aRemoveOrAnInsertCannotShareItsMintWithPlainCalls() {
        // Remove b (the Entry list is head -> b): the head relinked to "", b released, its token burned.
        BigInteger upper = T.add(BigInteger.valueOf(120_000));
        BigInteger bIn = ledger.utxo(entry).value().lovelaceOf();
        Ctx rel = ctx(ANN, BigInteger.ZERO, bIn);
        Ledger.Tx rm = tx(ANN);
        rm.inputs.add(entryHead);
        rm.inputs.add(entry);
        rm.redeemers.put(new ScriptPurpose.Spending(entryHead), data(new WidestValidator.Link()));
        rm.redeemers.put(new ScriptPurpose.Spending(entry), data(new WidestValidator.Release(rel)));
        rm.mint(policy, keyIdOfType(inst, "Entry", B), BigInteger.ONE.negate());
        rm.redeemers.put(mint(), data(new WidestValidator.Remove_Entry(entryHead, entry)));
        rm.outputs.add(at(value(minAda, keyIdOfType(inst, "Entry", new byte[0])), new WidestValidator.Head_Entry(inst, new byte[0], upper)));
        rm.outputs.add(new TxOut(StateHarness.pkhAddress(ANN), Value.lovelace(bIn), new OutputDatum.OutputDatumInline(data(entry)),
                Optional.empty()));
        accepted(balanced(rm.copy(), ANN), "the remove alone");
        Ledger.Tx withVault = rm.copy();
        openVault(withVault, withVault.inputs.get(0), ANN);
        Ledger.Tx underRemove = withVault.copy();
        underRemove.redeemers.put(mint(), data(new WidestValidator.Remove_Entry(entryHead, entry)));
        rejectedWith(balanced(underRemove, ANN), "a remove and a vault under Remove_Entry", mintLabel());
        rejectedWith(balanced(withVault, ANN), "a remove and a vault under OpenVault", mintLabel());
        // The remove beside a plain destroy (closing the vault burns its 0x4F token under BurnInstance).
        Ctx close = ctx(ANN, BigInteger.ZERO, minAda);
        Ledger.Tx withClose = rm.copy();
        withClose.inputs.add(vault);
        withClose.redeemers.put(new ScriptPurpose.Spending(vault), data(new WidestValidator.CloseVault(close)));
        withClose.mint(policy, vaultId, BigInteger.ONE.negate());
        withClose.outputs.add(new TxOut(StateHarness.pkhAddress(ANN), Value.lovelace(minAda), new OutputDatum.OutputDatumInline(data(vault)),
                Optional.empty()));
        rejectedWith(balanced(withClose.copy(), ANN), "a remove and a plain destroy under Remove_Entry", mintLabel());
        withClose.redeemers.put(mint(), data(new WidestValidator.BurnInstance()));
        rejectedWith(balanced(withClose, ANN), "a remove and a plain destroy under BurnInstance", mintLabel());
    }

    @Test
    void aZeroPayoutIsRefusedOnARetire() {
        // An entry holding only its deposit: its retire pays out a zero payout, which is refused (payouts are positive).
        TxOutRef empty = ledger.fund(at(value(minAda, keyIdOfType(inst, "Entry", new byte[]{0x7a})),
                new WidestValidator.Keyed_Entry(inst, new byte[0], BigInteger.ZERO, new Widest.Entry(new byte[]{0x7a}, ANN, BigInteger.ZERO))));
        Ctx zero = ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        TxOut retired = at(value(minAda, keyIdOfType(inst, "Entry", new byte[]{0x7a})),
                new WidestValidator.Retired_Entry(inst, new byte[0], BigInteger.ZERO, new byte[]{0x7a}));
        rejectedByAScript(spend(empty, new WidestValidator.Retire(zero), retired, BigInteger.ZERO), "a retire with a zero payout");
        // The control: the funded entry retires, paying its 5 ADA stake (tagged with its input).
        Ctx five = ctx(ANN, BigInteger.ZERO, BigInteger.valueOf(5_000_000));
        Ledger.Tx t = tx(ANN);
        t.inputs.add(entry);
        t.redeemers.put(new ScriptPurpose.Spending(entry), data(new WidestValidator.Retire(five)));
        t.outputs.add(at(value(minAda, keyIdOfType(inst, "Entry", B)), new WidestValidator.Retired_Entry(inst, new byte[0], BigInteger.ZERO, B)));
        t.outputs.add(new TxOut(StateHarness.pkhAddress(ANN), Value.lovelace(BigInteger.valueOf(5_000_000)),
                new OutputDatum.OutputDatumInline(data(entry)), Optional.empty()));
        accepted(balanced(t, ANN), "a funded entry's retire");
    }
}
