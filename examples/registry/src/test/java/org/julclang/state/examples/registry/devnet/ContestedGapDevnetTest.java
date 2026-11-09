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
        var honestRig = M2Kit.rig(null, 8);   // room to observe the losses, if any
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
        regH.claim(honest, new byte[]{0x6a}, BigInteger.ONE, guardians(honest), BigInteger.ONE, ADA.multiply(BigInteger.TWO));
        long took = System.currentTimeMillis() - t0;
        stop.set(true);
        hostile.join();
        int attempts = honestRig.built("Registry.claim").size();
        System.out.println("[contested] the honest insert landed in " + took / 1000 + " s, at its attempt " + attempts
                + "; the attacker landed " + contests.get() + " contesting transactions");
        // Each contested instant is a race between two clients submitting at the same slot on one node: the attacker,
        // running this same runtime, wins about half of them. Landing within 3 instants is therefore the likely case
        // (7/8), not a guarantee; the test requires that it lands, and reports the instants it took.
        assertTrue(attempts <= 9, "landed: " + attempts);
        List<String> keys = regH.entryKeys().stream().map(KeyedList.Entry::keyHex).toList();
        assertTrue(keys.contains("6a"), keys.toString());
    }
}
