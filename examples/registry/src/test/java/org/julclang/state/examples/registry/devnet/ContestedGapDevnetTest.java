package org.julclang.state.examples.registry.devnet;

import com.bloxbean.cardano.client.account.Account;
import org.julclang.core.types.JulcList;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.registry.RegistryClient;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.KeyedList;
import org.julclang.state.runtime.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0016 §2 "timed races" on DevKit (the registry's Entry: C = 60 s; windows of 20 s, the runtime's TTL): one attacker contests the gap after
 * {@code a} by alternately inserting and releasing a throwaway key ({@code e}) with the same runtime, each at the instant
 * its cooldown ends. An honest insert of {@code j} into that gap races it at each of those instants until it wins one
 * (D7's retries); the instants it took are reported (§2: each is a fair race).
 */
@Tag("devnet")
class ContestedGapDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    static JulcList<byte[]> guardians(Account a) {
        return JulcList.<byte[]>empty().prepend(Keys.pkh(a));
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, unit = java.util.concurrent.TimeUnit.MINUTES)
    void anHonestInsertLandsAgainstOneContestingAttacker() {
        var attackerRig = M2Kit.rig();
        // 8 retries, not the default 3: each contested instant is a fair race, so with 3 an honest insert gives up in about
        // 1 run in 16 (four lost races in a row); this test is about landing under contention, not the retry budget.
        var honestRig = M2Kit.rig(null, 8);
        var attacker = M2Kit.wallet(4, 300);
        var honest = M2Kit.wallet(3, 300);
        var reg = RegistryClient.deploy(attackerRig.runtime(), attacker);
        reg.claim(attacker, new byte[]{0x61}, BigInteger.ONE, guardians(attacker), BigInteger.ONE, ADA.multiply(BigInteger.TWO));

        var stop = new AtomicBoolean();
        var contests = new AtomicInteger();
        var firstInsert = new CompletableFuture<Void>();
        var hostile = CompletableFuture.runAsync(() -> {
            while (!stop.get()) {
                try {
                    var e = reg.claim(attacker, new byte[]{0x65}, BigInteger.ONE, guardians(attacker), BigInteger.ONE,
                            ADA.multiply(BigInteger.TWO));
                    contests.incrementAndGet();
                    firstInsert.complete(null);
                    if (stop.get()) break;
                    e.release(attacker);
                    contests.incrementAndGet();
                } catch (JoustException lost) {
                    System.out.println("  [attacker] lost a race: " + lost.getMessage());
                }
            }
        });
        firstInsert.join();   // the gap is contested from here on

        var regH = RegistryClient.at(honestRig.runtime(), reg.instanceId());
        long t0 = System.currentTimeMillis();
        int before = contests.get();
        regH.claim(honest, new byte[]{0x6a}, BigInteger.ONE, guardians(honest), BigInteger.ONE, ADA.multiply(BigInteger.TWO));
        long took = System.currentTimeMillis() - t0;
        int during = contests.get() - before;
        stop.set(true);
        hostile.join();
        var honestAttempts = honestRig.built("Registry.claim");
        int attempts = honestAttempts.size();
        // Contested: an attacker transaction was built for the same instant as one of the honest attempts (its window
        // starts inside that attempt's window), whichever of the two won.
        var attackerTxs = new java.util.ArrayList<M2Kit.Built>(attackerRig.built("Registry.claim"));
        attackerTxs.addAll(attackerRig.built("Registry.release"));
        long raced = attackerTxs.stream().filter(x -> honestAttempts.stream().anyMatch(h ->
                x.validFrom() >= h.validFrom() && x.validFrom() < h.validTo())).count();
        System.out.println("[contested] the honest insert landed in " + took / 1000 + " s, at its attempt " + attempts
                + "; the attacker landed " + contests.get() + " contesting transactions, " + during + " of them while it waited, and"
                + " built " + raced + " for the honest attempts' instants");
        // Each contested instant is a race between two clients submitting at the same slot on one node: the attacker,
        // running this same runtime, wins about half of them. Landing within 3 instants is therefore the likely case
        // (7/8), not a guarantee (an attacker with a latency edge can hold the gap for its fees; ADR-0016 M2 notes);
        // the test requires that the attacker raced the honest insert for at least one of its instants (whoever won), and
        // that the honest insert landed. (The first version required an attacker transaction to *land* meanwhile, which
        // fails whenever the honest insert wins its first race.)
        assertTrue(raced >= 1, "the attacker raced the honest insert at its instant (R-D6)");
        List<String> keys = regH.entryKeys().stream().map(KeyedList.Entry::keyHex).toList();
        assertTrue(keys.contains("6a"), keys.toString());
    }
}
