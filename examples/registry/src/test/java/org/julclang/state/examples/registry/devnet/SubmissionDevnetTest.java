package org.julclang.state.examples.registry.devnet;

import com.bloxbean.cardano.client.account.Account;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.registry.m2.StampsClient;
import org.julclang.state.runtime.KeyedList;
import org.julclang.state.runtime.PendingTransactionException;
import org.julclang.state.runtime.Relay;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0016 D7's submission rules on DevKit, with relays that reject, time out or lie: the outcome is transaction-wide
 * (an acceptance or an unknown delivery dominates rejections); an attempt whose fate is unknown is never rebuilt; the
 * same signed bytes may be rebroadcast; and since every attempt spends the designated input, at most one ever lands.
 */
@Tag("devnet")
class SubmissionDevnetTest {

    static String instanceId;
    static Account ann;

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
        ann = M2Kit.wallet(6, 200);
        instanceId = StampsClient.deploy(M2Kit.rig().runtime(), ann).instanceId();
    }

    static List<String> keys(StampsClient s) {
        return s.stampKeys().stream().map(KeyedList.Entry::keyHex).toList();
    }

    static Relay reject() {
        return cbor -> Relay.Outcome.rejected("test: refused");
    }

    @Test
    void anAcceptanceDominatesARejection() {
        var rig = M2Kit.rig(b -> List.of(Relay.of(b), reject()));
        var s = StampsClient.at(rig.runtime(), instanceId);
        s.stamp(ann, new byte[]{0x01});
        assertEquals(1, rig.built("Stamps.stamp").size(), "accepted: not rebuilt");
        assertTrue(keys(s).contains("01"));
    }

    @Test
    void aTimeoutDominatesARejection_neverRebuilt() {
        var rig = M2Kit.rig(b -> {
            Relay node = Relay.of(b);
            return List.of(cbor -> {
                node.submit(cbor);   // delivered after all, but the answer is lost
                return Relay.Outcome.unknown("test: timed out");
            }, reject());
        });
        var s = StampsClient.at(rig.runtime(), instanceId);
        assertThrows(PendingTransactionException.class, () -> s.stamp(ann, new byte[]{0x02}));
        assertEquals(1, rig.built("Stamps.stamp").size(), "an unknown outcome is never rebuilt");
        M2Kit.sleep(8_000);
        assertTrue(M2Kit.onChain(rig.backend(), rig.built("Stamps.stamp").get(0).hash()), "the delivered attempt landed");
        assertTrue(keys(s).contains("02"));
    }

    @Test
    void expiryAfterAnAmbiguousDelivery_neverRebuilt() {
        var rig = M2Kit.rig(b -> List.of(cbor -> Relay.Outcome.unknown("test: timed out, never delivered")));
        var s = StampsClient.at(rig.runtime(), instanceId);
        assertThrows(PendingTransactionException.class, () -> s.stamp(ann, new byte[]{0x03}));
        var only = rig.built("Stamps.stamp");
        assertEquals(1, only.size());
        // Past its validity window: it can no longer land, and the runtime never built another.
        M2Kit.sleep(25_000);
        assertFalse(M2Kit.onChain(rig.backend(), only.get(0).hash()));
        assertFalse(keys(s).contains("03"));
        assertEquals(1, rig.built("Stamps.stamp").size());
    }

    @Test
    void identicalBytesMayBeRebroadcast() {
        var rig = M2Kit.rig(b -> List.of(Relay.of(b), Relay.of(b), Relay.of(b)));
        var s = StampsClient.at(rig.runtime(), instanceId);
        s.stamp(ann, new byte[]{0x04});
        assertEquals(1, rig.built("Stamps.stamp").size());
        assertTrue(keys(s).contains("04"));
    }

    /**
     * The first attempt is rejected, and meanwhile the wallet spends the designated input in another transaction: the
     * runtime cannot tell that spend from its own attempt landing, so it does not rebuild.
     */
    @Test
    void theDesignatedInputSpentElsewhere_pendingNotRebuilt() {
        var bob = M2Kit.wallet(2, 100);
        var first = new AtomicInteger();
        var rig = M2Kit.rig(b -> List.of(cbor -> {
            if (first.getAndIncrement() == 0) {
                M2Kit.spendAll(b, bob);
                return Relay.Outcome.rejected("test: refused, while the wallet spent its UTxOs elsewhere");
            }
            return Relay.of(b).submit(cbor);
        }));
        var s = StampsClient.at(rig.runtime(), instanceId);
        var e = assertThrows(PendingTransactionException.class, () -> s.stamp(bob, new byte[]{0x06}));
        System.out.println("[submission] designated input spent elsewhere: " + e.getMessage());
        assertEquals(1, rig.built("Stamps.stamp").size(), "not rebuilt");
        assertFalse(keys(s).contains("06"));
    }

    /**
     * The relay answers "rejected" to the first two attempts while holding them back, and on the third delivers all
     * three in order: the first lands, and the second and third conflict with it (they spend the same designated input),
     * whatever was claimed. The runtime rebuilt twice, then finds the designated input spent and stops (pending).
     */
    @Test
    void relaysLyingRejectedTwiceWhileTheFirstLands_onlyOneOfThreeLands() {
        var held = new java.util.ArrayList<byte[]>();
        var rig = M2Kit.rig(b -> {
            Relay node = Relay.of(b);
            return List.of(cbor -> {
                if (held.size() < 2) {
                    held.add(cbor);
                    return Relay.Outcome.rejected("test: a lie");
                }
                var first = node.submit(held.get(0));
                System.out.println("  [relay] the first attempt, delivered late: " + first.answer());
                M2Kit.sleep(4_000);   // it lands
                System.out.println("  [relay] the second: " + node.submit(held.get(1)).answer());
                return node.submit(cbor);
            });
        });
        var s = StampsClient.at(rig.runtime(), instanceId);
        var e = assertThrows(PendingTransactionException.class, () -> s.stamp(ann, new byte[]{0x05}));
        M2Kit.sleep(4_000);
        var attempts = rig.built("Stamps.stamp");
        var landed = attempts.stream().filter(a -> M2Kit.onChain(rig.backend(), a.hash())).toList();
        System.out.println("[submission] lying relays: " + attempts.size() + " attempts, " + landed.size() + " landed ("
                + (landed.isEmpty() ? "none" : "attempt " + (attempts.indexOf(landed.get(0)) + 1)) + "); then " + e.getMessage());
        assertEquals(3, attempts.size(), "rebuilt after each lie");
        assertFalse(M2Kit.sharedInputs(attempts).isEmpty(), "every attempt spends the designated input");
        assertEquals(List.of(attempts.get(0).hash()), landed.stream().map(M2Kit.Built::hash).toList(), "only the first lands");
        assertTrue(keys(s).contains("05"));
    }
}
