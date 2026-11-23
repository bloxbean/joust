package org.julclang.state.host;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import java.util.*;

/** Reward pointers follow the ledger order of reward accounts, including when both verifiers run. */
final class MapWithdrawal {
    static int pointer(Transaction tx, String reward) {
        var ws = tx.getBody().getWithdrawals();
        if (ws == null) return -1;
        return ws.stream().map(w -> w.getRewardAddress())
                .sorted(Comparator.comparing(a -> new Address(a).getBytes(), Arrays::compareUnsigned))
                .toList().indexOf(reward);
    }
}
