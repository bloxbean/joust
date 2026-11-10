package org.julclang.state.testkit;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.text.UplcParser;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.ScriptPurpose;
import org.julclang.ledger.StakingCredential;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ledger model's own rules (ADR-0016 §8), and its output sizes against cardano-client-lib's serialisation. */
class LedgerTest {

    static final BigInteger T = BigInteger.valueOf(1_800_000_000_000L);
    static final byte[] ALICE = StateHarness.fill(28, 0xA1);
    static final byte[] BOB = StateHarness.fill(28, 0xB0);
    static final byte[] OK = StateHarness.fill(28, 0x0C);
    static final byte[] NO = StateHarness.fill(28, 0x0F);
    static final Program ALWAYS = UplcParser.parseProgram("(program 1.1.0 (lam ctx (con unit ())))");
    static final Program NEVER = UplcParser.parseProgram("(program 1.1.0 (lam ctx (error)))");
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    static Address script(byte[] h) {
        return new Address(new Credential.ScriptCredential(new ScriptHash(h)), Optional.empty());
    }

    static PlutusData unit() {
        return new PlutusData.ConstrData(0, List.of());
    }

    Ledger ledger() {
        var l = new Ledger(Ledger.Params.mainnet(), T);
        l.register(OK, ALWAYS);
        l.register(NO, NEVER);
        return l;
    }

    // ------------------------------------------------------------------ sizes

    /** The output as cardano-client-lib serialises it in a transaction body. */
    static int cclSize(TxOut o) throws Exception {
        var a = o.address();
        byte[] payment = a.credential() instanceof Credential.ScriptCredential sc ? sc.hash().hash()
                : ((Credential.PubKeyCredential) a.credential()).hash().hash();
        boolean scriptPay = a.credential() instanceof Credential.ScriptCredential;
        byte[] addr;
        if (a.stakingCredential().isPresent()) {
            var st = (StakingCredential.StakingHash) a.stakingCredential().get();
            boolean scriptStake = st.credential() instanceof Credential.ScriptCredential;
            byte[] stake = scriptStake ? ((Credential.ScriptCredential) st.credential()).hash().hash()
                    : ((Credential.PubKeyCredential) st.credential()).hash().hash();
            addr = new byte[57];
            addr[0] = (byte) (((scriptPay ? 1 : 0) | (scriptStake ? 2 : 0)) << 4);
            System.arraycopy(payment, 0, addr, 1, 28);
            System.arraycopy(stake, 0, addr, 29, 28);
        } else {
            addr = new byte[29];
            addr[0] = (byte) ((scriptPay ? 0x70 : 0x60));
            System.arraycopy(payment, 0, addr, 1, 28);
        }
        var multi = new ArrayList<MultiAsset>();
        Ledger.entries(o.value()).forEach((p, names) -> {
            if (p.isEmpty()) return;
            var assets = new ArrayList<Asset>();
            names.forEach((n, q) -> assets.add(Asset.builder().name("0x" + n).value(q).build()));
            multi.add(MultiAsset.builder().policyId(p).assets(assets).build());
        });
        var value = com.bloxbean.cardano.client.transaction.spec.Value.builder().coin(o.value().lovelaceOf()).multiAssets(multi).build();
        var b = TransactionOutput.builder().address(new com.bloxbean.cardano.client.address.Address(addr).toBech32()).value(value);
        if (o.datum() instanceof OutputDatum.OutputDatumInline d) b.inlineDatum(PlutusDataAdapter.toClientLib(d.datum()));
        return CborSerializationUtil.serialize(b.build().serialize()).length;
    }

    @Test
    void outputSizesMatchCardanoClientLib() throws Exception {
        byte[] policy = StateHarness.fill(28, 0x77);
        var datum = new PlutusData.ConstrData(4, List.of(new PlutusData.BytesData(StateHarness.fill(32, 1)),
                new PlutusData.BytesData(StateHarness.fill(64, 2)), new PlutusData.IntData(BigInteger.TWO.pow(63)),
                new PlutusData.ListData(List.of(new PlutusData.BytesData(StateHarness.fill(28, 3)), new PlutusData.IntData(BigInteger.TEN)))));
        var outs = List.of(
                StateHarness.walletOut(ALICE, ADA.multiply(BigInteger.valueOf(5))),
                StateHarness.walletOut(ALICE, BigInteger.valueOf(10)),
                new TxOut(script(OK), Ledger.value(ADA.multiply(BigInteger.valueOf(3)), policy, StateHarness.fill(29, 0x4B)),
                        new OutputDatum.OutputDatumInline(datum), Optional.empty()),
                new TxOut(new Address(new Credential.ScriptCredential(new ScriptHash(OK)), Optional.of(new StakingCredential.StakingHash(
                        new Credential.PubKeyCredential(new PubKeyHash(BOB))))), Ledger.value(ADA, policy, new byte[]{1}, new byte[]{2, 3},
                        StateHarness.fill(32, 9)), new OutputDatum.OutputDatumInline(unit()), Optional.empty()));
        for (TxOut o : outs) {
            assertEquals(cclSize(o), Ledger.outputSize(o, 0), "size of " + o);
            assertEquals(BigInteger.valueOf((160L + cclSize(o)) * 4_310L), ledger().minLovelace(o));
        }
    }

    // ------------------------------------------------------------------ withdrawals (review F43)

    Ledger.Tx paidBy(Ledger l, byte[] key) {
        var t = new Ledger.Tx();
        TxOutRef w = l.fund(StateHarness.walletOut(key, ADA.multiply(BigInteger.valueOf(100))));
        t.inputs.add(w);
        t.witnesses.add(key);
        return t;
    }

    /** Change for {@code t}: everything in minus the fee, plus withdrawals, back to {@code key}. */
    Ledger.Tx balanced(Ledger l, Ledger.Tx t, byte[] key) {
        BigInteger in = BigInteger.ZERO;
        for (TxOutRef r : t.inputs) in = in.add(l.utxo(r).value().lovelaceOf());
        for (BigInteger w : t.withdrawals.values()) in = in.add(w);
        for (TxOut o : t.outputs) in = in.subtract(o.value().lovelaceOf());
        t.outputs.add(StateHarness.walletOut(key, in.subtract(t.fee)));
        return t;
    }

    @Test
    void withdrawals() {
        var l = ledger();
        var okCred = new Credential.ScriptCredential(new ScriptHash(OK));
        var t = paidBy(l, ALICE);
        t.withdrawals.put(okCred, BigInteger.ZERO);
        t.redeemers.put(new ScriptPurpose.Rewarding(okCred), unit());
        assertTrue(l.check(balanced(l, t.copy(), ALICE)).accepted(), "a zero withdrawal of a script, with its redeemer");
        var noRedeemer = t.copy();
        noRedeemer.redeemers.clear();
        assertReason(l, balanced(l, noRedeemer, ALICE), "missing redeemer");
        l.setReward(okCred, ADA.multiply(BigInteger.valueOf(5)));
        assertReason(l, balanced(l, t.copy(), ALICE), "a withdrawal must take the whole reward balance");
        var all = t.copy();
        all.withdrawals.put(okCred, ADA.multiply(BigInteger.valueOf(5)));
        var r = l.submit(balanced(l, all, ALICE));
        assertTrue(r.accepted(), r.toString());
        assertEquals(BigInteger.ZERO, l.reward(okCred), "the balance is withdrawn");
        var negative = paidBy(l, ALICE);
        negative.withdrawals.put(okCred, BigInteger.ONE.negate());
        negative.redeemers.put(new ScriptPurpose.Rewarding(okCred), unit());
        assertReason(l, balanced(l, negative, ALICE), "negative withdrawal");
        var bobCred = new Credential.PubKeyCredential(new PubKeyHash(BOB));
        var key = paidBy(l, ALICE);
        key.withdrawals.put(bobCred, BigInteger.ZERO);
        assertReason(l, balanced(l, key.copy(), ALICE), "a key withdrawal without its key's witness");
        key.witnesses.add(BOB);
        assertTrue(l.check(balanced(l, key, ALICE)).accepted(), "a key withdrawal witnessed by its key");
    }

    // ------------------------------------------------------------------ witnesses, signers, bounds, scripts

    static void assertReason(Ledger l, Ledger.Tx t, String reason) {
        var r = l.check(t);
        assertFalse(r.accepted(), "expected '" + reason + "'");
        assertTrue(r.reason().startsWith(reason), "expected '" + reason + "', got: " + r.reason());
    }

    @Test
    void witnessesAndRequiredSigners() {
        var l = ledger();
        var t = paidBy(l, ALICE);
        t.witnesses.clear();
        assertReason(l, balanced(l, t.copy(), ALICE), "input");
        var s = paidBy(l, ALICE);
        s.signers.add(BOB);
        assertReason(l, balanced(l, s.copy(), ALICE), "a required signer without a witness");
        s.witnesses.add(BOB);
        assertTrue(l.check(balanced(l, s, ALICE)).accepted());
    }

    @Test
    void validityBounds() {
        var l = ledger();
        var t = paidBy(l, ALICE).window(T, T.add(BigInteger.valueOf(120_000)));
        assertTrue(l.check(balanced(l, t.copy(), ALICE)).accepted());
        assertReason(l, balanced(l, paidBy(l, ALICE).window(T.subtract(BigInteger.valueOf(500)), T.add(BigInteger.valueOf(120_000))),
                ALICE), "a validity bound not on a slot boundary");
        assertReason(l, balanced(l, paidBy(l, ALICE).window(T.add(BigInteger.valueOf(1_000)), null), ALICE), "not valid yet");
        assertReason(l, balanced(l, paidBy(l, ALICE).window(null, T), ALICE), "expired");
    }

    @Test
    void everyFailingScriptIsNamed() {
        var l = ledger();
        TxOutRef a = l.fund(new TxOut(script(NO), Value.lovelace(ADA.multiply(BigInteger.TWO)), new OutputDatum.OutputDatumInline(unit()),
                Optional.empty()));
        TxOutRef b = l.fund(new TxOut(script(OK), Value.lovelace(ADA.multiply(BigInteger.TWO)), new OutputDatum.OutputDatumInline(unit()),
                Optional.empty()));
        var t = paidBy(l, ALICE);
        t.inputs.add(a);
        t.inputs.add(b);
        t.redeemers.put(new ScriptPurpose.Spending(a), unit());
        t.redeemers.put(new ScriptPurpose.Spending(b), unit());
        t.mint(NO, new byte[]{1}, BigInteger.ONE);
        t.redeemers.put(new ScriptPurpose.Minting(new PolicyId(NO)), unit());
        var bt = balanced(l, t, ALICE);
        bt.outputs.set(bt.outputs.size() - 1, new TxOut(bt.outputs.getLast().address(),
                Ledger.value(bt.outputs.getLast().value().lovelaceOf(), NO, new byte[]{1}), new OutputDatum.NoOutputDatum(), Optional.empty()));
        var r = l.check(bt);
        assertFalse(r.accepted());
        assertTrue(r.reason().contains("SPEND " + HexFormat.of().formatHex(a.txId().hash()).substring(0, 8)), r.reason());
        assertTrue(r.reason().contains("MINT " + HexFormat.of().formatHex(NO).substring(0, 8)), r.reason());
        assertFalse(r.reason().contains("SPEND " + HexFormat.of().formatHex(b.txId().hash()).substring(0, 8)), r.reason());
        assertEquals(3, r.scripts().size(), "every script ran");
    }

    @Test
    void referenceScriptSizes() {
        var l = ledger();
        var o = new TxOut(StateHarness.pkhAddress(ALICE), Value.lovelace(ADA), new OutputDatum.NoOutputDatum(),
                Optional.of(new ScriptHash(OK)));
        BigInteger unknown = l.minLovelace(o);
        l.setReferenceScriptSize(OK, 10_000);
        assertTrue(l.minLovelace(o).subtract(unknown).compareTo(BigInteger.valueOf(10_000L * 4_310)) >= 0,
                "a known reference script counts in full");
    }
}
