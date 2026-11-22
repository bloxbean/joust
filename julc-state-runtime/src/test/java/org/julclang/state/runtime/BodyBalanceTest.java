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
        p.setMaxValSize("5000");
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
        var returns = Map.of(ADDR, back(10_000_000, 60));
        t.setBody(body(List.of(IN_A, IN_B), 200_000, out(10_000_000, 60), outTo(a, 4_800_000, 40)));
        TxRunner.checkCoCallers(t, spends, a, paid, returns, "test");   // B gets back its lovelace and the 60 tokens it keeps
        // Built from a 9 ADA listing (its return records 9 ADA): the 1 ADA the chain holds beyond it is taken from B.
        t.setBody(body(List.of(IN_A, IN_B), 200_000, out(9_000_000, 60), outTo(a, 5_800_000, 40)));
        var e = assertThrows(JoustException.class, () -> TxRunner.checkCoCallers(t, spends, a, paid,
                Map.of(ADDR, back(9_000_000, 60)), "test"));
        assertTrue(e.getMessage().contains("take 1000000 lovelace"), e::getMessage);
        // Not the return recorded: nothing returned at all.
        var none = assertThrows(JoustException.class, () -> TxRunner.checkCoCallers(t, spends, a, paid, returns, "test"));
        assertTrue(none.getMessage().contains("would take 100 " + UNIT + " from " + ADDR), none::getMessage);
        t.setBody(body(List.of(IN_A, IN_B), 200_000, out(10_000_000, 50), outTo(a, 4_800_000, 50)));
        assertThrows(JoustException.class, () -> TxRunner.checkCoCallers(t, spends, a, paid, Map.of(ADDR, back(10_000_000, 50)), "test"));
        // R8: an output unit in another spelling is the same unit.
        var upper = out(10_000_000, 0);
        upper.getValue().getMultiAssets().add(MultiAsset.builder().policyId(POLICY.toUpperCase())
                .assets(new ArrayList<>(List.of(new Asset("0x01", BigInteger.valueOf(60))))).build());
        t.setBody(body(List.of(IN_A, IN_B), 200_000, upper, outTo(a, 4_800_000, 40)));
        TxRunner.checkCoCallers(t, spends, a, paid, returns, "test");
        // R7: a spent input without an address is refused, whoever it may belong to.
        var anonymous = utxo(null, "aa", 10_000_000, 100);
        var unknown = assertThrows(JoustException.class, () -> TxRunner.checkCoCallers(t, Map.of(IN_A, anonymous, IN_B, spends.get(IN_B)),
                a, paid, returns, "test"));
        assertTrue(unknown.getMessage().contains("cannot tell whose input"), unknown::getMessage);
    }

    /** A return's value: {@code lovelace} and {@code tokens} of the test unit. */
    static org.julclang.ledger.Value back(long lovelace, long tokens) {
        var v = org.julclang.state.api.Assets.lovelace(BigInteger.valueOf(lovelace));
        return tokens == 0 ? v : org.julclang.state.api.Assets.add(v,
                org.julclang.state.api.Assets.of(new byte[28], new byte[]{1}, BigInteger.valueOf(tokens)));
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
        // R3: no read without such a certificate (nor an output holding assets, whose value size needs the maximum:
        // #165 Codex); a failed read is a JoustException, not an NPE.
        BodyBalance.check(body(List.of(IN_B), 200_000, out(4_800_000, 0)), chain::get,
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

    // ---------------------------------------------------------------- #165 Codex round

    @Test
    void twoWithdrawalsFromOneRewardAccountAreRefused() {
        // CBOR keys withdrawals by reward account: 5 ADA in, 2 + 2 ADA withdrawn, 8.8 ADA out and a 0.2 ADA fee balance
        // as objects, but the bytes keep one withdrawal and are 2 ADA short.
        String reward = new Account(Networks.testnet()).stakeAddress();
        var b = body(List.of(IN_B), 200_000, out(8_800_000, 0));
        b.getWithdrawals().add(new com.bloxbean.cardano.client.transaction.spec.Withdrawal(reward, BigInteger.valueOf(2_000_000)));
        b.getWithdrawals().add(new com.bloxbean.cardano.client.transaction.spec.Withdrawal(reward, BigInteger.valueOf(2_000_000)));
        refused(b, "two withdrawals from reward account");
        b.getWithdrawals().removeLast();
        b.getOutputs().getFirst().getValue().setCoin(BigInteger.valueOf(6_800_000));
        check(b);
    }

    @Test
    void aPolicyOrAssetListedTwiceIsRefused() {
        // A value is a map of maps in CBOR: two entries for one policy (or one name) keep only one of them.
        var twice = body(List.of(IN_A), 200_000, out(9_800_000, 50));
        twice.getOutputs().getFirst().getValue().getMultiAssets().add(MultiAsset.builder().policyId(POLICY.toUpperCase())
                .assets(new ArrayList<>(List.of(new Asset("0x01", BigInteger.valueOf(50))))).build());
        refused(twice, "lists policy");
        var name = body(List.of(IN_A), 200_000, out(9_800_000, 0));
        name.getOutputs().getFirst().getValue().getMultiAssets().add(MultiAsset.builder().policyId(POLICY)
                .assets(new ArrayList<>(List.of(new Asset("0x01", BigInteger.valueOf(50)), new Asset("0x01", BigInteger.valueOf(50))))).build());
        refused(name, "lists asset");
    }

    @Test
    void theHandedOutBodyIsTheOneItsBytesHold() {
        // What the wallet signs is the bytes: the checks read a copy decoded from them, which nothing else holds.
        String reward = new Account(Networks.testnet()).stakeAddress();
        var t = new com.bloxbean.cardano.client.transaction.spec.Transaction();
        t.setBody(body(List.of(IN_B), 200_000, out(8_800_000, 0)));
        t.getBody().getWithdrawals().add(new com.bloxbean.cardano.client.transaction.spec.Withdrawal(reward, BigInteger.valueOf(2_000_000)));
        t.getBody().getWithdrawals().add(new com.bloxbean.cardano.client.transaction.spec.Withdrawal(reward, BigInteger.valueOf(2_000_000)));
        var handed = TxRunner.handedOut(t, "test");
        assertEquals(1, handed.getBody().getWithdrawals().size(), "one withdrawal survives the bytes");
        var e = assertThrows(BodyBalance.UnbalancedException.class, () -> BodyBalance.check(handed.getBody(), chain::get, pp(), "test"));
        assertTrue(e.getMessage().contains("lovelace=-2000000"), e::getMessage);
        // An observer keeping the built object changes nothing handed out.
        var ok = new com.bloxbean.cardano.client.transaction.spec.Transaction();
        ok.setBody(body(List.of(IN_B), 200_000, out(4_800_000, 0)));
        var copy = TxRunner.handedOut(ok, "test");
        String hash = com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(copy);
        ok.getBody().getOutputs().getFirst().getValue().setCoin(BigInteger.valueOf(4_800_001));
        assertEquals(hash, com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(copy));
        assertEquals(BigInteger.valueOf(4_800_000), copy.getBody().getOutputs().getFirst().getValue().getCoin());
    }

    @Test
    void onlyTheRecordedReturnIsACoCallersReturn() {
        // B's input resolves to 10 ADA; its return, built from an 8 ADA listing, records 8 ADA. Another output to B (a
        // tagged payout, a plain one: a send, an intent's refund to an enterprise address that is also a wallet's) brings
        // B's outputs to 10 ADA but is owed to B by someone else: it must not cover the 2 ADA taken (#165 Codex, Fable).
        String a = new Account(Networks.testnet()).baseAddress();
        var spends = Map.of(IN_A, utxo(ADDR, "aa", 10_000_000, 0), IN_B, utxo(a, "bb", 5_000_000, 0));
        Map<String, org.julclang.ledger.Value> paid = Map.of();
        var returns = Map.of(ADDR, back(8_000_000, 0));
        var payout = out(2_000_000, 0);
        payout.setInlineDatum(com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(7));
        var t = new com.bloxbean.cardano.client.transaction.spec.Transaction();
        t.setBody(body(List.of(IN_A, IN_B), 200_000, out(8_000_000, 0), payout, outTo(a, 4_800_000, 0)));
        var e = assertThrows(JoustException.class, () -> TxRunner.checkCoCallers(t, spends, a, paid, returns, "test"));
        assertTrue(e.getMessage().contains("take 2000000 lovelace from " + ADDR), e::getMessage);
        // A plain output beside the short return: not the return either.
        t.setBody(body(List.of(IN_A, IN_B), 200_000, out(2_000_000, 0), out(8_000_000, 0), outTo(a, 4_800_000, 0)));
        var plain = assertThrows(JoustException.class, () -> TxRunner.checkCoCallers(t, spends, a, paid, returns, "test"));
        assertTrue(plain.getMessage().contains("take 2000000 lovelace from " + ADDR), plain::getMessage);
        // B's whole 10 ADA back (so recorded), beside the payout: fine.
        t.setBody(body(List.of(IN_A, IN_B), 200_000, out(10_000_000, 0), payout, outTo(a, 2_800_000, 0)));
        TxRunner.checkCoCallers(t, spends, a, paid, Map.of(ADDR, back(10_000_000, 0)), "test");
    }

    @Test
    void theCollateralIsThePayers() {
        // A collateral input resolving to B's address (a listing at the payer's that the provider answers otherwise), or
        // a collateral return to B: the phase-2 path would take from B or pay B what is the payer's.
        String a = new Account(Networks.testnet()).baseAddress();
        var IN_C = new TransactionInput("cc".repeat(32), 0);
        var spends = Map.of(IN_B, utxo(a, "bb", 5_000_000, 0), IN_C, utxo(ADDR, "cc", 5_000_000, 0));
        var t = new com.bloxbean.cardano.client.transaction.spec.Transaction();
        t.setBody(body(List.of(IN_B), 200_000, outTo(a, 4_800_000, 0)));
        t.getBody().getCollateral().add(IN_C);
        var e = assertThrows(JoustException.class, () -> TxRunner.checkCoCallers(t, spends, a, Map.of(), Map.of(), "test"));
        assertTrue(e.getMessage().contains("collateral input " + "cc".repeat(32) + "#0 is not at the payer's address"), e::getMessage);
        var own = Map.of(IN_B, utxo(a, "bb", 5_000_000, 0), IN_C, utxo(a, "cc", 5_000_000, 0));
        TxRunner.checkCoCallers(t, own, a, Map.of(), Map.of(), "test");
        t.getBody().setCollateralReturn(out(4_000_000, 0));   // to ADDR, not the payer
        var r = assertThrows(JoustException.class, () -> TxRunner.checkCoCallers(t, own, a, Map.of(), Map.of(), "test"));
        assertTrue(r.getMessage().contains("the collateral return goes to " + ADDR), r::getMessage);
        t.getBody().setCollateralReturn(outTo(a, 4_000_000, 0));
        TxRunner.checkCoCallers(t, own, a, Map.of(), Map.of(), "test");
    }

    // ---------------------------------------------------------------- #165 Codex round 2 and the Opus review

    @Test
    void aCoCallerGetsBackExactlyWhatItsInputsHoldLessItsPayment() {
        // B's input is listed at 5 ADA and resolves to 3 ADA: its return (5 ADA, from the listing) would give B 2 ADA of
        // the payer's (whose input resolves 2 ADA over its listing, so the body balances).
        String a = new Account(Networks.testnet()).baseAddress();
        var spends = Map.of(IN_A, utxo(ADDR, "aa", 3_000_000, 0), IN_B, utxo(a, "bb", 12_000_000, 0));
        var t = new com.bloxbean.cardano.client.transaction.spec.Transaction();
        t.setBody(body(List.of(IN_A, IN_B), 200_000, out(5_000_000, 0), outTo(a, 9_800_000, 0)));
        var e = assertThrows(JoustException.class, () -> TxRunner.checkCoCallers(t, spends, a, Map.of(),
                Map.of(ADDR, back(5_000_000, 0)), "test"));
        assertTrue(e.getMessage().contains("would give 2000000 lovelace to " + ADDR), e::getMessage);
        t.setBody(body(List.of(IN_A, IN_B), 200_000, out(3_000_000, 0), outTo(a, 11_800_000, 0)));
        TxRunner.checkCoCallers(t, spends, a, Map.of(), Map.of(ADDR, back(3_000_000, 0)), "test");
    }

    @Test
    void aPolicySpelledWithItsPrefixIsTheSamePolicy() {
        // cardano-client-lib strips an optional 0x when it writes the key: "0x" + p and p are one policy in the bytes.
        var twice = body(List.of(IN_A), 200_000, out(9_800_000, 50));
        twice.getOutputs().getFirst().getValue().getMultiAssets().add(MultiAsset.builder().policyId("0x" + POLICY)
                .assets(new ArrayList<>(List.of(new Asset("0x01", BigInteger.valueOf(50))))).build());
        refused(twice, "lists policy");
        var mint = body(List.of(IN_B), 200_000, out(4_800_000, 2));
        mint.getMint().add(MultiAsset.builder().policyId(POLICY).assets(new ArrayList<>(List.of(new Asset("0x01", BigInteger.ONE)))).build());
        mint.getMint().add(MultiAsset.builder().policyId("0x" + POLICY).assets(new ArrayList<>(List.of(new Asset("0x01", BigInteger.ONE)))).build());
        refused(mint, "the mint lists policy");
    }

    /** {@code n} distinct 32-byte names (from {@code from}) of another policy, one each, beside {@code lovelace}. */
    static TransactionOutput many(long lovelace, int n) {
        return many(lovelace, 0, n);
    }

    static TransactionOutput many(long lovelace, int from, int n) {
        var assets = new ArrayList<Asset>();
        for (int i = from; i < from + n; i++) assets.add(new Asset("0x" + String.format("%064x", i), BigInteger.ONE));
        var v = Value.builder().coin(BigInteger.valueOf(lovelace)).multiAssets(new ArrayList<>(List.of(
                MultiAsset.builder().policyId("11".repeat(28)).assets(assets).build()))).build();
        return TransactionOutput.builder().address(ADDR).value(v).build();
    }

    @Test
    void anOutputAboveTheMaximumValueSizeIsRefused() {
        // 160 names of 32 bytes: a value above 5,000 bytes. The inputs hold them (balanced), the ledger refuses the output.
        chain.put(IN_A, withAssets(utxo(ADDR, "aa", 30_000_000, 0), many(0, 160)));
        refused(body(List.of(IN_A), 200_000, many(29_800_000, 160)), "above the ledger's maximum of 5000 bytes");
        check(body(List.of(IN_A), 200_000, many(15_000_000, 0, 80), many(14_800_000, 80, 80)));   // two outputs fit
        // The collateral return too.
        var b = body(List.of(IN_B), 200_000, out(4_800_000, 0));
        b.getCollateral().add(IN_A);
        b.setCollateralReturn(many(29_000_000, 160));
        b.setTotalCollateral(BigInteger.valueOf(1_000_000));
        refused(b, "above the ledger's maximum");
        // No maximum known: an output holding assets cannot be checked, so it is refused.
        var e = assertThrows(JoustException.class, () -> BodyBalance.check(body(List.of(IN_A), 200_000, many(29_800_000, 160)),
                chain::get, new ProtocolParams(), "test"));
        assertTrue(e.getMessage().contains("no maximum value size"), e::getMessage);
        var odd = pp();
        odd.setMaxValSize("five thousand");
        var n = assertThrows(JoustException.class, () -> BodyBalance.check(body(List.of(IN_A), 200_000, many(29_800_000, 160)),
                chain::get, odd, "test"));
        assertTrue(n.getMessage().contains("is not a number"), n::getMessage);
    }

    /** {@code u} holding {@code o}'s assets too. */
    static Utxo withAssets(Utxo u, TransactionOutput o) {
        var amounts = new ArrayList<>(u.getAmount());
        for (var a : com.bloxbean.cardano.client.api.util.ValueUtil.toAmountList(o.getValue())) {
            if (!a.getUnit().equals("lovelace")) amounts.add(a);
        }
        u.setAmount(amounts);
        return u;
    }

    @Test
    void anObserverGetsACopy() {
        // #165 Codex: what an onBuilt observer does with its transaction, then or later, reaches nothing the runtime keeps.
        var kept = new java.util.concurrent.atomic.AtomicReference<com.bloxbean.cardano.client.transaction.spec.Transaction>();
        var rt = StateRuntime.builder(new org.julclang.state.runtime.snapshot.SnapshotBackend(new org.julclang.state.runtime.snapshot.Snapshot()))
                .network(Networks.testnet()).chainTime(new SlotChainTime(0, 0, 1000, null))
                .onBuilt(b -> { b.tx().getBody().getInputs().clear(); kept.set(b.tx()); }).build();
        var t = new com.bloxbean.cardano.client.transaction.spec.Transaction();
        t.setBody(body(List.of(IN_B), 200_000, out(4_800_000, 0)));
        rt.built("test", t, true);
        assertEquals(List.of(IN_B), t.getBody().getInputs());
        assertNotSame(t, kept.get());
    }
}
