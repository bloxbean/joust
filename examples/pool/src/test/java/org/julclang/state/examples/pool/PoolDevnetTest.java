package org.julclang.state.examples.pool;

import com.bloxbean.cardano.client.account.Account;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.IntentHandle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Batched intents on a live Yaci DevKit (ADR-0004): concurrent users, one batch, a cancel, an expired intent. */
@Tag("devnet")
class PoolDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    static BigInteger ada(long n) {
        return ADA.multiply(BigInteger.valueOf(n));
    }

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void usersLockIntentsConcurrently_oneBatchAppliesThem() {
        var runtime = DevKit.runtime();
        var admin = DevKit.fundedAccount(500);
        var batcher = DevKit.fundedAccount(200);
        var users = new ArrayList<Account>();
        for (int i = 0; i < 6; i++) users.add(DevKit.fundedAccount(100));

        var pool = PoolClient.deploy(runtime, admin, ada(10), ada(1));
        Instant later = Instant.now().plus(Duration.ofMinutes(30));

        // Six users lock intents at the same time: each spends only their own UTxOs, so nobody contends.
        var futures = new ArrayList<CompletableFuture<IntentHandle<Pool.State>>>();
        for (int i = 0; i < 6; i++) {
            Account u = users.get(i);
            int k = i;
            futures.add(CompletableFuture.supplyAsync(() -> switch (k) {
                case 0, 1 -> pool.donateIntent(u, ada(5 + k), later);
                // Buyers accept up to 20 ADA and pay 20: the change comes back as a payout.
                case 2, 3, 4 -> pool.buyIntent(u, ada(20), ada(20), later);
                // Expires before any batch window could end (the batch window is about 20 s).
                default -> pool.donateIntent(u, ada(3), Instant.now().plus(Duration.ofSeconds(5)));
            }));
        }
        List<IntentHandle<Pool.State>> intents = futures.stream().map(CompletableFuture::join).toList();
        assertEquals(6, pool.pendingIntents().size());

        // One user changes their mind.
        intents.get(1).cancel(users.get(1));
        assertFalse(intents.get(1).isPending());

        var result = pool.batch(batcher, 8);
        System.out.println("[pool batch] " + result.txHash() + " applied " + result.applied().size() + " skipped " + result.skipped());
        assertNotNull(result.txHash());
        assertEquals(4, result.applied().size(), "two donations minus one cancel, three buys");
        assertTrue(result.skipped().get(intents.get(5).intentId()).contains("deadline"));

        Pool.State s = pool.state();
        assertEquals(ada(5), s.donated());
        assertEquals(BigInteger.valueOf(3), s.sold());
        assertEquals(ada(13), s.price());
        // 5 donated + tickets at 10, 11 and 12
        assertEquals(ada(5 + 10 + 11 + 12).add(BigInteger.valueOf(PoolClient.SPEC.minAda())), pool.lovelace());
        for (int i : new int[]{0, 2, 3, 4}) assertFalse(intents.get(i).isPending());

        // The expired intent stays until its owner takes it back.
        assertTrue(intents.get(5).isPending());
        intents.get(5).cancel(users.get(5));
        assertTrue(pool.pendingIntents().isEmpty());

        // A direct call still works for the admin's (non-batched) action.
        var w = pool.withdraw(admin, ada(5));
        assertEquals(ada(5), w.payouts().get(0).lovelace());
    }
}
