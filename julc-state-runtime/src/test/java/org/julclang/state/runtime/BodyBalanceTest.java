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
}
