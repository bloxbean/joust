package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRetirement;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDeregistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** #152: {@link BodyBalance} refuses a body that does not balance per unit, on the spending and the collateral path. */
class BodyBalanceTest {
    static final String POLICY = "00".repeat(28);
    static final String UNIT = POLICY + "01";

    static Utxo utxo(String address, String tag, long lovelace, long tokens) {
        return AssetCollectionTest.utxo(address, tag, lovelace, tokens);
    }


    static final String ADDR = new Account(Networks.testnet()).baseAddress();

    static ProtocolParams pp() {
        var p = new ProtocolParams();
        p.setKeyDeposit("2000000");
        p.setPoolDeposit("500000000");
        return p;
    }

    static TransactionOutput out(long lovelace, long tokens) {
        var v = Value.builder().coin(BigInteger.valueOf(lovelace)).multiAssets(new ArrayList<>()).build();
        if (tokens != 0) {
            v.getMultiAssets().add(MultiAsset.builder().policyId(POLICY)
                    .assets(new ArrayList<>(List.of(new Asset("0x01", BigInteger.valueOf(tokens))))).build());
        }
        return TransactionOutput.builder().address(ADDR).value(v).build();
    }

    static TransactionBody body(List<TransactionInput> ins, long fee, TransactionOutput... outs) {
        var b = new TransactionBody();
        b.getInputs().addAll(ins);
        b.getOutputs().addAll(List.of(outs));
        b.setFee(BigInteger.valueOf(fee));
        return b;
    }

    static final TransactionInput IN_A = new TransactionInput("aa".repeat(32), 0);
    static final TransactionInput IN_B = new TransactionInput("bb".repeat(32), 0);
    final Map<TransactionInput, Utxo> chain = new HashMap<>(Map.of(
            IN_A, utxo(ADDR, "aa", 10_000_000, 100),
            IN_B, utxo(ADDR, "bb", 5_000_000, 0)));

    private void check(TransactionBody b) {
        BodyBalance.check(b, chain::get, pp(), "test");
    }

    private void refused(TransactionBody b, String reason) {
        var e = assertThrows(BodyBalance.UnbalancedException.class, () -> check(b));
        assertTrue(e.getMessage().contains(reason), e::getMessage);
    }

    @Test
    void aBalancedBodyPassesPerUnit() {
        check(body(List.of(IN_A, IN_B), 200_000, out(3_000_000, 40), out(11_800_000, 60)));
    }

    @Test
    void negativeChangeIsRefusedEvenWhenItCancels() {
        // The #152 shape: the payment and a negative change sum to what the inputs hold.
        refused(body(List.of(IN_B), 200_000, out(2_000_000, 100), out(2_800_000, -100)), "negative quantity");
        refused(body(List.of(IN_B), 200_000, out(2_000_000, 100), out(2_800_000, 0)), "value is not conserved");
    }

    @Test
    void aDoubleCountedInputIsRefused() {
        // A UTxO collected twice: QuickTx keeps one input but counts its value twice in the change (ADR-0015 §13.8).
        refused(body(List.of(IN_A), 200_000, out(19_800_000, 200)), "value is not conserved");
        var b = body(List.of(), 0, out(9_800_000, 100));
        b.setInputs(new ArrayList<>(List.of(IN_A, IN_A)));
        b.setFee(BigInteger.valueOf(200_000));
        refused(b, "spent twice");
    }

    @Test
    void unknownInputsAndCertificatesAreRefused() {
        refused(body(List.of(new TransactionInput("cc".repeat(32), 0)), 0), "cannot resolve input");
        var b = body(List.of(IN_B), 200_000, out(2_800_000, 0));
        b.getCerts().add(new StakeRegistration(StakeCredential.fromKeyHash(new byte[28])));
        check(b);   // the legacy registration locks the protocol's key deposit
        var r = body(List.of(IN_B), 200_000, out(2_800_000, 0));
        r.getCerts().add(new RegCert(StakeCredential.fromKeyHash(new byte[28]), BigInteger.valueOf(2_000_000)));
        check(r);
        var d = body(List.of(IN_B), 200_000, out(6_800_000, 0));
        d.getCerts().add(new StakeDeregistration(StakeCredential.fromKeyHash(new byte[28])));
        check(d);   // refunds it
        var p = body(List.of(IN_B), 200_000, out(4_800_000, 0));
        p.getCerts().add(new PoolRetirement(new byte[28], 10));
        check(p);
        var unknown = body(List.of(IN_B), 200_000, out(4_800_000, 0));
        unknown.getCerts().add(com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration.builder().build());
        refused(unknown, "PoolRegistration certificate is not checked");
    }

    @Test
    void ledgerInvalidValuesAreRefused() {
        // T3: one input spelled in two cases is one input, counted once.
        var twice = body(List.of(), 200_000, out(19_800_000, 200));
        var upper = new TransactionInput(IN_A.getTransactionId().toUpperCase(), 0);
        chain.put(upper, chain.get(IN_A));
        twice.setInputs(new ArrayList<>(List.of(IN_A, upper)));
        refused(twice, "spent twice");
        // A zero-quantity asset in an output.
        var zero = body(List.of(IN_A), 200_000, out(9_800_000, 100));
        zero.getOutputs().getFirst().getValue().getMultiAssets().add(MultiAsset.builder().policyId("cc".repeat(28))
                .assets(new ArrayList<>(List.of(new Asset("0x02", BigInteger.ZERO)))).build());
        refused(zero, "zero quantity");
        // Quantities outside the ledger's range, even when they balance.
        var big = BigInteger.TWO.pow(70);
        chain.put(new TransactionInput("dd".repeat(32), 0), utxo(ADDR, "dd", 10_000_000, 0));
        chain.get(new TransactionInput("dd".repeat(32), 0)).getAmount().add(new Amount(UNIT, big));
        var huge = body(List.of(new TransactionInput("dd".repeat(32), 0)), 200_000, out(9_800_000, 0));
        huge.getOutputs().getFirst().getValue().getMultiAssets().add(MultiAsset.builder().policyId(POLICY)
                .assets(new ArrayList<>(List.of(new Asset("0x01", big)))).build());
        refused(huge, "outside the ledger's range");
        var mint = body(List.of(IN_B), 200_000, out(4_800_000, Long.MAX_VALUE));
        mint.getMint().add(MultiAsset.builder().policyId(POLICY)
                .assets(new ArrayList<>(List.of(new Asset("0x01", BigInteger.valueOf(Long.MAX_VALUE))))).build());
        check(mint);   // Int64's maximum is a valid mint
        var over = body(List.of(IN_B), 200_000, out(4_800_000, 0));
        over.getOutputs().getFirst().getValue().getMultiAssets().add(MultiAsset.builder().policyId(POLICY)
                .assets(new ArrayList<>(List.of(new Asset("0x01", BigInteger.TWO.pow(63))))).build());
        over.getMint().add(MultiAsset.builder().policyId(POLICY)
                .assets(new ArrayList<>(List.of(new Asset("0x01", BigInteger.TWO.pow(63))))).build());
        refused(over, "mint quantity");
    }

    @Test
    void aPreparedBodyTakesFromACoCallerOnlyWhatItPaysIn() {
        // T1 defence in depth (TxRunner.checkCoCallers): B's input holds 10 ADA and 100 tokens, B pays in 40 tokens.
        String a = new Account(Networks.testnet()).baseAddress();
        var bIn = utxo(ADDR, "aa", 10_000_000, 100);
        var spends = Map.of(IN_A, bIn, IN_B, utxo(a, "bb", 5_000_000, 0));
        var paid = Map.of(ADDR, org.julclang.state.api.Assets.of(new byte[28], new byte[]{1}, BigInteger.valueOf(40)));
        var t = new com.bloxbean.cardano.client.transaction.spec.Transaction();
        t.setBody(body(List.of(IN_A, IN_B), 200_000, out(10_000_000, 60), outTo(a, 4_800_000, 40)));
        TxRunner.checkCoCallers(t, spends, a, paid, "test");   // B gets back its lovelace and the 60 tokens it keeps
        t.setBody(body(List.of(IN_A, IN_B), 200_000, out(9_000_000, 60), outTo(a, 5_800_000, 40)));
        var e = assertThrows(JoustException.class, () -> TxRunner.checkCoCallers(t, spends, a, paid, "test"));
        assertTrue(e.getMessage().contains("1000000 lovelace"), e::getMessage);
        t.setBody(body(List.of(IN_A, IN_B), 200_000, out(10_000_000, 50), outTo(a, 4_800_000, 50)));
        assertThrows(JoustException.class, () -> TxRunner.checkCoCallers(t, spends, a, paid, "test"));
        // R8: an output unit in another spelling is the same unit.
        var upper = out(10_000_000, 0);
        upper.getValue().getMultiAssets().add(MultiAsset.builder().policyId(POLICY.toUpperCase())
                .assets(new ArrayList<>(List.of(new Asset("0x01", BigInteger.valueOf(60))))).build());
        t.setBody(body(List.of(IN_A, IN_B), 200_000, upper, outTo(a, 4_800_000, 40)));
        TxRunner.checkCoCallers(t, spends, a, paid, "test");
        // R7: a spent input without an address is refused, whoever it may belong to.
        var anonymous = utxo(null, "aa", 10_000_000, 100);
        var unknown = assertThrows(JoustException.class, () -> TxRunner.checkCoCallers(t, Map.of(IN_A, anonymous, IN_B, spends.get(IN_B)),
                a, paid, "test"));
        assertTrue(unknown.getMessage().contains("cannot tell whose input"), unknown::getMessage);
    }

    static TransactionOutput outTo(String address, long lovelace, long tokens) {
        var o = out(lovelace, tokens);
        o.setAddress(address);
        return o;
    }

    @Test
    void aCollateralInputWithoutAnAddressIsAClearRefusal() {
        // R3-3: inputOwners sees collateral inputs that checkCoCallers does not; no address is a JoustException, not an NPE.
        var anonymous = utxo(ADDR, "bb", 5_000_000, 0);
        anonymous.setAddress(null);
        var e = assertThrows(JoustException.class, () -> TxRunner.inputOwners(Map.of(IN_B, anonymous)));
        assertTrue(e.getMessage().contains("no address for input " + "bb".repeat(32) + "#0"), e::getMessage);
        assertEquals(1, TxRunner.inputOwners(Map.of(IN_A, chain.get(IN_A))).size());
    }

    @Test
    void donationsAndCertificateCoinsAreRangeChecked() {
        // F-W2: a negative donation would add 5 ADA to the inputs' side (10 ADA in, 15 ADA out).
        var negative = body(List.of(IN_A), 0, out(15_000_000, 100));
        negative.setDonation(BigInteger.valueOf(-5_000_000));
        refused(negative, "donation is not positive");
        var zero = body(List.of(IN_A), 200_000, out(9_800_000, 100));
        zero.setDonation(BigInteger.ZERO);
        refused(zero, "donation is not positive");
        var donated = body(List.of(IN_A), 200_000, out(8_800_000, 100));
        donated.setDonation(BigInteger.valueOf(1_000_000));
        check(donated);
        var refund = body(List.of(IN_B), 200_000, out(9_800_000, 0));
        refund.getCerts().add(new RegCert(StakeCredential.fromKeyHash(new byte[28]), BigInteger.valueOf(-5_000_000)));
        refused(refund, "a certificate's deposit is outside the ledger's range");
    }

    @Test
    void unitSpellingsAreOneUnit() {
        // An output's policy in upper case is the input's unit (normalised), not a new one.
        var b = body(List.of(IN_A), 200_000, out(9_800_000, 0));
        b.getOutputs().getFirst().getValue().getMultiAssets().add(MultiAsset.builder().policyId(POLICY.toUpperCase())
                .assets(new ArrayList<>(List.of(new Asset("0x01", BigInteger.valueOf(100))))).build());
        check(b);
    }

    @Test
    void theProtocolParametersAreReadOnlyForALegacyStakeCertificate() {
        // R3: no read without such a certificate; a failed read is a JoustException, not an NPE.
        BodyBalance.check(body(List.of(IN_A, IN_B), 200_000, out(3_000_000, 40), out(11_800_000, 60)), chain::get,
                () -> { throw new AssertionError("read"); }, "test");
        var r = body(List.of(IN_B), 200_000, out(2_800_000, 0));
        r.getCerts().add(new StakeRegistration(StakeCredential.fromKeyHash(new byte[28])));
        var e = assertThrows(JoustException.class, () -> BodyBalance.check(r, chain::get, () -> null, "test"));
        assertTrue(e.getMessage().contains("no protocol parameters"), e::getMessage);
    }

    @Test
    void theCollateralPathBalancesToo() {
        var b = body(List.of(IN_A, IN_B), 200_000, out(3_000_000, 40), out(11_800_000, 60));
        b.getCollateral().add(IN_B);
        b.setCollateralReturn(out(4_700_000, 0));
        b.setTotalCollateral(BigInteger.valueOf(300_000));
        check(b);
        b.setTotalCollateral(BigInteger.valueOf(400_000));
        refused(b, "on the collateral path");
        b.setTotalCollateral(null);
        check(b);   // without a total, the ledger keeps what is left
    }

    @Test
    void theTotalCollateralIsRangeCheckedAndNeedsCollateralInputs() {
        // R3-1: 5 ADA of collateral, a 10 ADA return and a total of -5 ADA cancel out; the total is outside the range.
        var b = body(List.of(IN_A, IN_B), 200_000, out(3_000_000, 40), out(11_800_000, 60));
        b.getCollateral().add(IN_B);
        b.setCollateralReturn(out(10_000_000, 0));
        b.setTotalCollateral(BigInteger.valueOf(-5_000_000));
        refused(b, "the total collateral is outside the ledger's range");
        b.setCollateralReturn(null);
        b.setTotalCollateral(BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(5_000_000)));
        refused(b, "the total collateral is outside the ledger's range");
        // No collateral inputs: neither a return nor a total may be set.
        var orphanReturn = body(List.of(IN_A, IN_B), 200_000, out(3_000_000, 40), out(11_800_000, 60));
        orphanReturn.setCollateralReturn(out(1_000_000, 0));
        refused(orphanReturn, "without collateral inputs");
        var orphanTotal = body(List.of(IN_A, IN_B), 200_000, out(3_000_000, 40), out(11_800_000, 60));
        orphanTotal.setTotalCollateral(BigInteger.valueOf(300_000));
        refused(orphanTotal, "without collateral inputs");
    }
}
