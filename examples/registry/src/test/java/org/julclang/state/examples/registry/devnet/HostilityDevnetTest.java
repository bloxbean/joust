package org.julclang.state.examples.registry.devnet;

import com.bloxbean.cardano.client.account.Account;
import org.julclang.core.types.JulcList;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.registry.RegistryClient;
import org.julclang.state.runtime.Keys;
import org.julclang.state.runtime.Relay;
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

/** ADR-0016 M2 hostility on DevKit: a hostile predecessor (§2, D5, D10). */
@Tag("devnet")
class HostilityDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    static JulcList<byte[]> guardians(Account a) {
        return JulcList.<byte[]>empty().prepend(Keys.pkh(a));
    }

    /**
     * The predecessor's owner updates it again and again (each update waits for its inclusion, about every two blocks on
     * DevKit); the victim's retire spends only its own UTxO and lands at once.
     */
    @Test
    void aHostilePredecessorUpdatingEveryBlockCannotBlockTheRetire() throws Exception {
        var attackerRig = M2Kit.rig();
        var victimRig = M2Kit.rig();
        var attacker = M2Kit.wallet(3, 300);
        var victim = M2Kit.wallet(3, 300);
        var reg = RegistryClient.deploy(attackerRig.runtime(), attacker);
        var a = reg.claim(attacker, new byte[]{0x61}, BigInteger.ONE, guardians(attacker), BigInteger.ONE, ADA.multiply(BigInteger.TWO));
        var regV = RegistryClient.at(victimRig.runtime(), reg.instanceId());
        var b = regV.claim(victim, new byte[]{0x62}, BigInteger.ONE, guardians(victim), BigInteger.ONE, ADA.multiply(BigInteger.valueOf(5)));
        var stop = new AtomicBoolean();
        var updates = new AtomicInteger();
        var hostile = CompletableFuture.runAsync(() -> {
            while (!stop.get()) {
                a.upgrade(attacker, BigInteger.valueOf(updates.get() % 4), ADA);
                updates.incrementAndGet();
            }
        });
        M2Kit.sleep(4_000);
        long t0 = System.currentTimeMillis();
        var r = b.retire(victim);
        long took = System.currentTimeMillis() - t0;
        M2Kit.sleep(4_000);
        stop.set(true);
        hostile.join();
        System.out.println("[hostility] the retire landed in " + took / 1000 + " s, in " + victimRig.built("Registry.retire").size()
                + " attempt(s), while the predecessor was updated " + updates.get() + " times");
        assertEquals(1, victimRig.built("Registry.retire").size(), "the retire is never displaced by the predecessor's updates");
        assertTrue(updates.get() >= 3, "the predecessor kept moving");
        assertTrue(regV.entry(new byte[]{0x62}).retired());
        assertEquals(ADA.multiply(BigInteger.valueOf(5)), r.payouts().get(0).lovelace());
    }

    /**
     * A remove spends its predecessor too: the predecessor's owner updates it while the remove is in flight, so the
     * remove is rejected, rebuilt against the updated predecessor (with the same designated input), and lands.
     */
    @Test
    void aRemoveRetriesAgainstAnUpdatedPredecessorAndLands() {
        var attackerRig = M2Kit.rig();
        var attacker = M2Kit.wallet(3, 300);
        var victim = M2Kit.wallet(3, 300);
        var reg = RegistryClient.deploy(attackerRig.runtime(), attacker);
        var x = reg.claim(attacker, new byte[]{0x78}, BigInteger.ONE, guardians(attacker), BigInteger.ONE, ADA.multiply(BigInteger.TWO));
        var armed = new AtomicBoolean();
        var victimRig = M2Kit.rig(backend -> {
            Relay node = Relay.of(backend);
            return List.of(cbor -> {
                if (armed.getAndSet(false)) {
                    // The predecessor moves first: the victim's transaction now spends a spent input.
                    x.upgrade(attacker, BigInteger.TWO, ADA);
                }
                return node.submit(cbor);
            });
        });
        var regV = RegistryClient.at(victimRig.runtime(), reg.instanceId());
        var y = regV.claim(victim, new byte[]{0x79}, BigInteger.ONE, guardians(victim), BigInteger.ONE, ADA.multiply(BigInteger.TWO));
        armed.set(true);
        var r = y.release(victim);
        var built = victimRig.built("Registry.release");
        System.out.println("[hostility] the remove landed in " + built.size() + " attempts: " + r.txHash());
        assertTrue(built.size() >= 2, "rejected once, rebuilt");
        M2Kit.designatedInput(victimRig.backend(), built, victim);   // pinned across the attempts: an ordinary input of the payer
        assertFalse(M2Kit.onChain(victimRig.backend(), built.get(0).hash()), "the first attempt never landed");
        assertFalse(regV.entryExists(new byte[]{0x79}));
        assertEquals(List.of("78"), regV.entryKeys().stream().map(e -> e.keyHex()).toList());
    }
}
