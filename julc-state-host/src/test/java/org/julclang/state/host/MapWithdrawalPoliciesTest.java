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

    static TransactionOutput out(String address, String policy) {
        var asset = MultiAsset.builder().policyId(policy).assets(new ArrayList<>(List.of(new Asset("0x" + "01".repeat(4), BigInteger.ONE)))).build();
        return TransactionOutput.builder().address(address)
                .value(Value.builder().coin(BigInteger.valueOf(2_000_000)).multiAssets(new ArrayList<>(List.of(asset))).build()).build();
    }

    @Test void walletChangeHoldingAnotherContractsTokenDoesNotFenceIt() {
        String wallet = AddressProvider.getEntAddress(Credential.fromKey(HexFormat.of().parseHex("11".repeat(28))), Networks.testnet()).toBech32();
        String atP = AddressProvider.getEntAddress(Credential.fromScript(HexFormat.of().parseHex(P)), Networks.testnet()).toBech32();
        var body = TransactionBody.builder().inputs(new ArrayList<>()).fee(BigInteger.ONE)
                .outputs(new ArrayList<>(List.of(out(atP, P), out(wallet, Q)))).build();
        var tx = Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).build();
        assertEquals(Set.of(P), MapWithdrawal.bodyPolicies(tx), "Q's token is only wallet change");
        body.setMint(new ArrayList<>(List.of(MultiAsset.builder().policyId(Q)
                .assets(new ArrayList<>(List.of(new Asset("0x" + "02".repeat(4), BigInteger.ONE.negate())))).build())));
        assertEquals(Set.of(P, Q), MapWithdrawal.bodyPolicies(tx), "a burn under Q is Q's lifecycle");
    }
}
