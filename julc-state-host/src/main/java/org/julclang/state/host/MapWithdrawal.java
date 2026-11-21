package org.julclang.state.host;

import com.bloxbean.cardano.client.address.Address;
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
            var bodyPolicies = new HashSet<String>();
            for (var out : tx.getBody().getOutputs())
                for (var asset : out.getValue().getMultiAssets()) bodyPolicies.add(asset.getPolicyId());
            if (tx.getBody().getMint() != null)
                for (var asset : tx.getBody().getMint()) bodyPolicies.add(asset.getPolicyId());
            for (var c : catalog.all().values()) if (c.maps() && bodyPolicies.contains(c.hash())) policies.add(c.hash());
            return new Restored(r.getData().serializeToHex(), Set.copyOf(policies));
        }
        throw bad("missing restored map verifier redeemer");
    }
}
