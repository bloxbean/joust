package org.julclang.state.host;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.*;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** #160 Codex review: which map contracts a restored body touches, by its tokens. */
class MapWithdrawalPoliciesTest {
    static final String P = "ab".repeat(28), Q = "cd".repeat(28);

    /** Both contracts at their enterprise script addresses (no stakeSelf). */
    static final Map<String, Boolean> ENTERPRISE = Map.of(P, false, Q, false);

    static TransactionOutput out(String address, String policy) {
        var asset = MultiAsset.builder().policyId(policy).assets(new ArrayList<>(List.of(new Asset("0x" + "01".repeat(4), BigInteger.ONE)))).build();
        return TransactionOutput.builder().address(address)
                .value(Value.builder().coin(BigInteger.valueOf(2_000_000)).multiAssets(new ArrayList<>(List.of(asset))).build()).build();
    }

    @Test void onlyTheContractsOwnAddressCountsNotItsPaymentCredential() {
        // #160 Codex re-review: a base address with P's script payment credential and another staking credential is not
        // P's address; with stakeSelf, P's address is the base address staked by P itself.
        var p = Credential.fromScript(HexFormat.of().parseHex(P));
        String staked = AddressProvider.getBaseAddress(p, Credential.fromKey(HexFormat.of().parseHex("22".repeat(28))), Networks.testnet()).toBech32();
        String self = AddressProvider.getBaseAddress(p, p, Networks.testnet()).toBech32();
        String enterprise = AddressProvider.getEntAddress(p, Networks.testnet()).toBech32();
        var body = TransactionBody.builder().inputs(new ArrayList<>()).fee(BigInteger.ONE)
                .outputs(new ArrayList<>(List.of(out(staked, P)))).build();
        var tx = Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).build();
        assertEquals(Set.of(), MapWithdrawal.bodyPolicies(tx, ENTERPRISE), "another staking credential: not P's address");
        assertEquals(Set.of(), MapWithdrawal.bodyPolicies(tx, Map.of(P, true)));
        body.setOutputs(new ArrayList<>(List.of(out(self, P))));
        assertEquals(Set.of(), MapWithdrawal.bodyPolicies(tx, ENTERPRISE), "P is not staked by itself");
        assertEquals(Set.of(P), MapWithdrawal.bodyPolicies(tx, Map.of(P, true)));
        body.setOutputs(new ArrayList<>(List.of(out(enterprise, P))));
        assertEquals(Set.of(), MapWithdrawal.bodyPolicies(tx, Map.of(P, true)), "a stakeSelf contract lives at its base address");
        assertEquals(Set.of(P), MapWithdrawal.bodyPolicies(tx, ENTERPRISE));
    }

    @Test void walletChangeHoldingAnotherContractsTokenDoesNotFenceIt() {
        String wallet = AddressProvider.getEntAddress(Credential.fromKey(HexFormat.of().parseHex("11".repeat(28))), Networks.testnet()).toBech32();
        String atP = AddressProvider.getEntAddress(Credential.fromScript(HexFormat.of().parseHex(P)), Networks.testnet()).toBech32();
        var body = TransactionBody.builder().inputs(new ArrayList<>()).fee(BigInteger.ONE)
                .outputs(new ArrayList<>(List.of(out(atP, P), out(wallet, Q)))).build();
        var tx = Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).build();
        assertEquals(Set.of(P), MapWithdrawal.bodyPolicies(tx, ENTERPRISE), "Q's token is only wallet change");
        body.setMint(new ArrayList<>(List.of(MultiAsset.builder().policyId(Q)
                .assets(new ArrayList<>(List.of(new Asset("0x" + "02".repeat(4), BigInteger.ONE.negate())))).build())));
        assertEquals(Set.of(P, Q), MapWithdrawal.bodyPolicies(tx, ENTERPRISE), "a burn under Q is Q's lifecycle");
    }
}
