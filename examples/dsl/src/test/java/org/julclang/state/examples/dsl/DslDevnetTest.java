package org.julclang.state.examples.dsl;

import com.bloxbean.cardano.client.account.Account;
import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** .jsc contracts on a live Yaci DevKit (ADR-0005): the generated clients end to end. */
@Tag("devnet")
class DslDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    static BigInteger ada(long n) {
        return ADA.multiply(BigInteger.valueOf(n));
    }

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void jar_tipsAsIntents_thenWithdrawAndClose() {
        var runtime = DevKit.runtime();
        Account owner = DevKit.fundedAccount(300);
        Account batcher = DevKit.fundedAccount(100);
        Account ann = DevKit.fundedAccount(100);
        Account ben = DevKit.fundedAccount(100);

        var jar = JarClient.deploy(runtime, owner, ada(50), ada(5));
        jar.tip(ann, ada(3));                                   // a direct call
        Instant later = Instant.now().plus(Duration.ofMinutes(30));
        var a = CompletableFuture.supplyAsync(() -> jar.tipIntent(ann, ada(4), later));
        var b = CompletableFuture.supplyAsync(() -> jar.tipIntent(ben, ada(6), later));
        CompletableFuture.allOf(a, b).join();
        var batch = jar.batch(batcher, 8);
        assertEquals(2, batch.applied().size(), "skipped: " + batch.skipped());
        assertEquals(ada(18), jar.state().total());
        assertEquals(BigInteger.valueOf(3), jar.state().tips());
        assertFalse(jar.reached());

        // Before the goal: at most half (the if arm's require runs).
        assertThrows(RequireException.class, () -> jar.withdraw(owner, ada(10)));
        assertEquals(ada(9), jar.withdraw(owner, ada(9)).payouts().get(0).lovelace());
        assertThrows(RequireException.class, () -> jar.withdraw(ann, ada(1)), "only owner");
        var closed = jar.close(owner);
        assertEquals(ada(9).add(BigInteger.valueOf(JarClient.SPEC.minAda())), closed.payouts().get(0).lovelace());
    }

    @Test
    void points_awardGiveSpendClose() {
        var runtime = DevKit.runtime();
        Account admin = DevKit.fundedAccount(200);
        Account ann = DevKit.fundedAccount(50);
        Account ben = DevKit.fundedAccount(50);

        var points = PointsClient.deploy(runtime, admin);
        points.award(admin, Keys.pkh(ann), BigInteger.valueOf(30));
        points.give(ann, Keys.pkh(ben), BigInteger.valueOf(10));
        assertEquals(BigInteger.valueOf(20), points.pointsOf(Keys.pkh(ann)));
        assertEquals(BigInteger.valueOf(10), points.pointsOf(Keys.pkh(ben)));
        points.spend(ben, BigInteger.valueOf(10));               // everything: the holder is removed
        points.spend(ann, BigInteger.valueOf(5));                // part
        assertEquals(BigInteger.ZERO, points.pointsOf(Keys.pkh(ben)));
        assertEquals(BigInteger.valueOf(15), points.state().supply());
        assertThrows(RequireException.class, () -> points.close(admin), "points are still out");
        points.spend(ann, BigInteger.valueOf(15));
        points.close(admin);
    }
}
