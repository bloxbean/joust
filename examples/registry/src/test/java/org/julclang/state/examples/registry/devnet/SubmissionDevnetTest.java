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
        var built = rig.built("Stamps.stamp");
        assertEquals(1, built.size(), "not rebuilt");
        assertEquals(List.of(built.get(0).hash()), e.attempts(), "the attempt that was handed out (R-D13.5, F58)");
        assertEquals(built.get(0).hash(), e.txHash());
        assertTrue(M2Kit.sharedInputs(built).contains(e.designatedInput()), "the attempt spends the input named");
        assertTrue(M2Kit.ordinaryInputOf(rig.backend(), e.designatedInput(), bob), "an ordinary UTxO of the payer");
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
        assertEquals(M2Kit.designatedInput(rig.backend(), attempts, ann), e.designatedInput(),
                "every attempt spends the designated input, an ordinary UTxO of the payer");
        assertEquals(attempts.stream().map(M2Kit.Built::hash).toList(), e.attempts());
        assertEquals(List.of(attempts.get(0).hash()), landed.stream().map(M2Kit.Built::hash).toList(), "only the first lands");
        assertTrue(keys(s).contains("05"));
    }

    /**
     * F61, R-D2: attempts that conflict only through the designated input. The key is the window's start
     * ({@code Stamps.now}), and a node {@code b} sits between the first attempt's key and the second's: the first is
     * anchored before {@code b}, the second at {@code b}, so they spend different list nodes, and the designated input
     * is the only input they share. The relay withholds the first (answering "rejected"), delivers it once the second
     * arrives and lets it land, then delivers the second: refused, for its designated input is spent. Without the
     * designated input both would land (two keys for one call).
     */
    @Test
    void attemptsInDifferentGapsShareOnlyTheDesignatedInput_atMostOneLands() {
        var bob = M2Kit.wallet(3, 100);
        var setup = M2Kit.rig();
        var stamps = StampsClient.deploy(setup.runtime(), bob);
        long bSlot = setup.time().tipSlot() + 12;
        byte[] b = M2Kit.be8(setup.time().slotToPosixMs(bSlot));
        stamps.stamp(bob, b);   // node b, keyed a few seconds ahead

        var held = new java.util.ArrayList<byte[]>();
        var rig = M2Kit.rig(backend -> {
            Relay node = Relay.of(backend);
            var time = DevKit.chainTime(backend);
            return List.of(cbor -> {
                if (held.isEmpty()) {
                    held.add(cbor);
                    while (time.tipSlot() <= bSlot) M2Kit.sleep(500);   // the rebuilt window starts after b's key
                    return Relay.Outcome.rejected("test: a lie (withheld)");
                }
                System.out.println("  [relay] the first attempt, delivered late: " + node.submit(held.get(0)).answer());
                for (int i = 0; i < 20 && !M2Kit.onChain(backend, com.bloxbean.cardano.client.transaction.util.TransactionUtil
                        .getTxHash(held.get(0))); i++) M2Kit.sleep(500);
                return node.submit(cbor);   // the second: its designated input is spent now
            });
        }, 3);
        var s = StampsClient.at(rig.runtime(), stamps.instanceId());
        var e = assertThrows(PendingTransactionException.class, () -> s.now(bob));
        var attempts = rig.built("Stamps.now");
        var landed = attempts.stream().filter(a -> M2Kit.onChain(rig.backend(), a.hash())).toList();
        var keys = keys(s);
        System.out.println("[submission] two gaps: " + attempts.size() + " attempts, keys " + keys + "; " + e.getMessage());
        assertEquals(2, attempts.size());
        String d = M2Kit.designatedInput(rig.backend(), attempts, bob);
        assertEquals(java.util.Set.of(d), M2Kit.sharedInputs(attempts), "they share the designated input and nothing else");
        assertEquals(d, e.designatedInput());
        assertEquals(List.of(attempts.get(0).hash()), landed.stream().map(M2Kit.Built::hash).toList(), "only the first lands");
        long first = rig.time().slotToPosixMs(attempts.get(0).validFrom());
        long second = rig.time().slotToPosixMs(attempts.get(1).validFrom());
        String hb = java.util.HexFormat.of().formatHex(b);
        String h1 = String.format("%016x", first);
        String h2 = String.format("%016x", second);
        assertTrue(h1.compareTo(hb) < 0 && hb.compareTo(h2) < 0, "the attempts' keys lie on both sides of b: different gaps");
        assertEquals(List.of(h1, hb), keys, "the first attempt's key, and never the second's");
    }
}
