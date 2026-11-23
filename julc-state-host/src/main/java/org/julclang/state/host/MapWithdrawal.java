package org.julclang.state.host;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.client.address.CredentialType;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.state.runtime.snapshot.SuppliedProver;
import java.util.*;
import static org.julclang.state.host.Wire.bad;

/** Reward pointers follow the ledger order of reward accounts, including when both verifiers run. */
final class MapWithdrawal {
    static int pointer(Transaction tx, String reward) {
        var ws = tx.getBody().getWithdrawals();
        if (ws == null) return -1;
        return ws.stream().map(w -> w.getRewardAddress())
                .sorted(Comparator.comparing(a -> new Address(a).getBytes(), Arrays::compareUnsigned))
                .toList().indexOf(reward);
    }

    record Restored(String claimsCbor, Set<String> policies) {}

    /**
     * The contracts a body touches by their tokens: a token at its own contract's address (a state or owned-object
     * continuation) or minted or burned (a lifecycle). A user token of another map contract in wallet change does not
     * fence that contract (#160 Codex review), nor does one at an address that shares only the contract's payment
     * credential (#160 Codex re-review). {@code contracts}: each catalogued policy with whether its address is staked by
     * the script itself (stakeSelf: the base address); otherwise the enterprise address.
     */
    static Set<String> bodyPolicies(Transaction tx, Map<String, Boolean> contracts) {
        var out = new HashSet<String>();
        for (var o : tx.getBody().getOutputs()) {
            var address = new Address(o.getAddress());
            var payment = address.getPaymentCredential();
            if (payment.isEmpty() || payment.get().getType() != CredentialType.Script) continue;
            String script = HexFormat.of().formatHex(payment.get().getBytes());
            Boolean stakeSelf = contracts.get(script);
            if (stakeSelf == null) continue;
            var delegation = address.getDelegationCredential();
            boolean own = stakeSelf
                    ? address.getAddressType() == AddressType.Base && delegation.isPresent()
                        && delegation.get().getType() == CredentialType.Script
                        && Arrays.equals(delegation.get().getBytes(), payment.get().getBytes())
                    : address.getAddressType() == AddressType.Enterprise;
            if (!own) continue;
            for (var asset : o.getValue().getMultiAssets()) if (asset.getPolicyId().equals(script)) out.add(script);
        }
        if (tx.getBody().getMint() != null) for (var asset : tx.getBody().getMint()) out.add(asset.getPolicyId());
        return out;
    }

    static Restored restored(Transaction tx, String reward, Contracts catalog) {
        if (reward == null) return null;
        int index = pointer(tx, reward);
        if (index < 0) return null;
        var ws = tx.getWitnessSet();
        if (ws != null && ws.getRedeemers() != null) for (var r : ws.getRedeemers()) {
            if (r.getTag() != RedeemerTag.Reward || r.getIndex().intValueExact() != index) continue;
            var claims = SuppliedProver.decodeClaims(PlutusDataAdapter.fromClientLib(r.getData()));
            var policies = new HashSet<String>();
            for (var claim : claims.claims()) policies.add(HexFormat.of().formatHex(claim.policy()));
            // The hash identifies the body. Even a damaged journal witness must not remove
            // a fence for a real map token that body returns or burns.
            var addresses = new HashMap<String, Boolean>();
            for (var c : catalog.all().values()) if (c.maps()) addresses.put(c.hash(), c.stakeSelf());
            var bodyPolicies = bodyPolicies(tx, addresses);
            for (var c : catalog.all().values()) if (c.maps() && bodyPolicies.contains(c.hash())) policies.add(c.hash());
            return new Restored(r.getData().serializeToHex(), Set.copyOf(policies));
        }
        throw bad("missing restored map verifier redeemer");
    }
}
