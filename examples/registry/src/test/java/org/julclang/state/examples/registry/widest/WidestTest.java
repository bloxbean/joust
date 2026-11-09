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

    /** OpenVault: a plain creation from a wallet seed, under the instance as a reference input. */
    Ledger.Tx openVault(Ledger.Tx t, TxOutRef seed) {
        byte[] id = HexFormat.of().parseHex(OwnedObject.objectIdOf(HexFormat.of().formatHex(seed.txId().hash()),
                seed.index().intValueExact()));
        Ctx c = ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        t.referenceInputs.add(state);
        t.mint(policy, id, BigInteger.ONE);
        t.redeemers.put(mint(), data(new WidestValidator.OpenVault(seed, inst, c)));
        t.outputs.add(at(value(minAda, id), new WidestValidator.Holding_Vault(inst, Widest.openVault(new Widest.State(ANN, true), c))));
        return t;
    }

    @Test
    void linkUnderAPlainCreation() {
        Ledger.Tx t = tx(ANN);
        openVault(t, t.inputs.get(0));
        accepted(balanced(t, ANN), "OpenVault alone");
        Ledger.Tx withLink = tx(ANN);
        openVault(withLink, withLink.inputs.get(0));
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
        Ledger.Tx both = t.copy();
        openVault(both, both.inputs.get(0));
        Ledger.Tx underInsert = both.copy();
        underInsert.redeemers.put(mint(), data(new WidestValidator.Insert_claim(inst, entry, c, cx)));
        rejectedByAScript(balanced(underInsert, BOB), "an insert and a vault under the insert's redeemer");
        rejectedByAScript(balanced(both, BOB), "an insert and a vault under OpenVault");
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
        openVault(withVault, withVault.inputs.get(0));
        Ledger.Tx underRemove = withVault.copy();
        underRemove.redeemers.put(mint(), data(new WidestValidator.Remove_Entry(entryHead, entry)));
        rejectedByAScript(balanced(underRemove, ANN), "a remove and a vault under Remove_Entry");
        rejectedByAScript(balanced(withVault, ANN), "a remove and a vault under OpenVault");
        // The remove beside a plain destroy (closing the vault burns its 0x4F token under BurnInstance).
        Ctx close = ctx(ANN, BigInteger.ZERO, minAda);
        Ledger.Tx withClose = rm.copy();
        withClose.inputs.add(vault);
        withClose.redeemers.put(new ScriptPurpose.Spending(vault), data(new WidestValidator.CloseVault(close)));
        withClose.mint(policy, vaultId, BigInteger.ONE.negate());
        withClose.outputs.add(new TxOut(StateHarness.pkhAddress(ANN), Value.lovelace(minAda), new OutputDatum.OutputDatumInline(data(vault)),
                Optional.empty()));
        rejectedByAScript(balanced(withClose.copy(), ANN), "a remove and a plain destroy under Remove_Entry");
        withClose.redeemers.put(mint(), data(new WidestValidator.BurnInstance()));
        rejectedByAScript(balanced(withClose, ANN), "a remove and a plain destroy under BurnInstance");
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
