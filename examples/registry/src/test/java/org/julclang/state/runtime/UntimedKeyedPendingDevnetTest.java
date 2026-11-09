package org.julclang.state.runtime;

import com.bloxbean.cardano.client.common.model.Networks;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.registry.RegistryClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * F65, R-E2 on DevKit: {@code Registry.change} uses no time (no Ctx, no time rules), yet an attempt with a designated
 * input expires: it carries the runtime's TTL as its validity window. When its delivery is unknown the call is pending,
 * the designated input stays reserved until that window has passed (and no longer), and the attempt never lands after
 * it. (In the runtime's package, to see the reservation.)
 */
@Tag("devnet")
class UntimedKeyedPendingDevnetTest {

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void anUntimedKeyedActionGoingPending_hasAFiniteWindowAndKeepsItsInputUntilThen() {
        var ann = DevKit.fundedAccount(300);
        var reg = RegistryClient.deploy(DevKit.runtime(), ann);
        reg.cast(ann, Keys.pkh(ann), BigInteger.ONE);

        var backend = DevKit.backend();
        var time = DevKit.chainTime(backend);
        var built = Collections.synchronizedList(new ArrayList<StateRuntime.BuiltTx>());
        var runtime = StateRuntime.builder(backend).network(Networks.testnet()).chainTime(time).ttl(Duration.ofSeconds(20))
                .evaluator(DevKit.runtime().evaluator()).log(m -> System.out.println("  [joust] " + m))
                .onBuilt(built::add)
                .relays(List.of(cbor -> Relay.Outcome.unknown("test: timed out, never delivered")))
                .build();
        var regU = RegistryClient.at(runtime, reg.instanceId());
        long tip = time.tipSlot();
        var e = assertThrows(PendingTransactionException.class, () -> regU.ballot(Keys.pkh(ann)).change(ann, BigInteger.TWO));
        var attempts = built.stream().filter(b -> b.what().equals("Registry.change")).toList();
        assertEquals(1, attempts.size(), "an unknown outcome is never rebuilt");
        var body = attempts.get(0).tx().getBody();
        System.out.println("[untimed] the attempt's window: [" + body.getValidityStartInterval() + ", " + body.getTtl()
                + "), tip " + tip + "; " + e.getMessage());
        assertTrue(body.getTtl() > 0, "a finite validity window");
        assertTrue(body.getTtl() <= tip + 25, "the runtime's 20 s TTL, from about the tip");
        assertEquals(List.of(com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(attempts.get(0).tx())), e.attempts());
        assertTrue(Designation.isDesignated(e.designatedInput()), "reserved while the attempt may land");

        // Past its window, the settlement depth and the margin: released, and the attempt never landed.
        long until = time.slotToPosixMs(body.getTtl() + runtime.settlementSlots()) + 12_000;
        while (System.currentTimeMillis() < until) {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException ex) {
                throw new IllegalStateException(ex);
            }
        }
        assertFalse(Designation.isDesignated(e.designatedInput()), "released once the window has passed");
        assertEquals(BigInteger.ONE, reg.ballot(Keys.pkh(ann)).value().choice(), "the withheld attempt never landed");
    }
}
