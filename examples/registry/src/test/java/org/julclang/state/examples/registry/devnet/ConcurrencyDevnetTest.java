package org.julclang.state.examples.registry.devnet;

import com.bloxbean.cardano.client.account.Account;
import org.julclang.core.types.JulcList;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.registry.RegistryClient;
import org.julclang.state.runtime.Keys;
import org.julclang.state.runtime.KeyedList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0016 M2 concurrency on DevKit (the registry's Entry: C = 60 s): an insert into an empty list; inserts from
 * separate threads into different gaps of a pre-populated list, one of them at the tail; two inserts into one gap, of
 * which one retries after the other's relink and cooldown. The threads share one wallet funded with more ordinary
 * UTxOs than threads: each operation designates its own (D7).
 */
@Tag("devnet")
class ConcurrencyDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    static JulcList<byte[]> guardians(Account a) {
        return JulcList.<byte[]>empty().prepend(Keys.pkh(a));
    }

    static List<String> keys(RegistryClient reg) {
        return reg.entryKeys().stream().map(KeyedList.Entry::keyHex).toList();
    }

    @Test
    void insertsIntoDifferentGapsAndOneContestedGap() {
        var rig = M2Kit.rig();
        var wallet = M2Kit.wallet(8, 200);
        var reg = RegistryClient.deploy(rig.runtime(), wallet);
        var g = guardians(wallet);
        // An insert into the empty list (anchored at the head), then two more after the head: each waits for the head's
        // cooldown.
        for (int k : new int[]{0x6e, 0x66, 0x62}) reg.claim(wallet, new byte[]{(byte) k}, BigInteger.ONE, g, BigInteger.ONE, ADA.multiply(BigInteger.TWO));
        assertEquals(List.of("62", "66", "6e"), keys(reg));
        M2Kit.sleep(80_000);   // past every node's cooldown (C plus the 20 s window)

        // Four threads, four gaps: before b (the head), b..f, f..n, and after n (the tail).
        long t0 = System.currentTimeMillis();
        var futures = new java.util.ArrayList<CompletableFuture<Void>>();
        for (int k : new int[]{0x61, 0x63, 0x68, 0x70}) {
            futures.add(CompletableFuture.runAsync(() -> reg.claim(wallet, new byte[]{(byte) k}, BigInteger.ONE, g, BigInteger.ONE,
                    ADA.multiply(BigInteger.TWO))));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        System.out.println("[concurrency] four threads into four gaps: " + (System.currentTimeMillis() - t0) / 1000 + " s, "
                + rig.built("Registry.claim").size() + " transactions built in all");
        assertEquals(List.of("61", "62", "63", "66", "68", "6e", "70"), keys(reg));

        // Two threads into one gap (c..f), both anchored at c, just inserted: they race at its cooldown's end; the loser
        // is rejected (its anchor was spent), rebuilds against the winner's node, and lands after that one's cooldown.
        int before = rig.built("Registry.claim").size();
        long t1 = System.currentTimeMillis();
        var d = CompletableFuture.runAsync(() -> reg.claim(wallet, new byte[]{0x64}, BigInteger.ONE, g, BigInteger.ONE, ADA.multiply(BigInteger.TWO)));
        var e = CompletableFuture.runAsync(() -> reg.claim(wallet, new byte[]{0x65}, BigInteger.ONE, g, BigInteger.ONE, ADA.multiply(BigInteger.TWO)));
        CompletableFuture.allOf(d, e).join();
        int built = rig.built("Registry.claim").size() - before;
        System.out.println("[concurrency] two threads into one gap: " + (System.currentTimeMillis() - t1) / 1000 + " s, " + built
                + " transactions built");
        assertTrue(built >= 3, "one of the two retried");
        assertEquals(List.of("61", "62", "63", "64", "65", "66", "68", "6e", "70"), keys(reg));
    }
}
