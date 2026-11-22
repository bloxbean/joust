package org.julclang.state.host;

import com.bloxbean.cardano.client.transaction.spec.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.julclang.state.host.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

/** #156 review K1, K2: recovering a keyed operation after a rewind, and after an unsigned attempt expired. */
class KeyedRecoveryTest {
    static final String D = "aa".repeat(32) + "#0";
    static final String I = "cd".repeat(32);

    static String cbor(long fee, long from, long to) throws Exception {
        var body = TransactionBody.builder()
                .inputs(new ArrayList<>(List.of(new TransactionInput("aa".repeat(32), 0), new TransactionInput("bb".repeat(32), (int) fee))))
                .outputs(new ArrayList<>()).fee(BigInteger.valueOf(fee)).ttl(to).validityStartInterval(from).build();
        return Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).build().serializeToHex();
    }
    static ObjectNode op(Map<String, Long> attempts) {
        var o = obj().put("designated", D).put("contract", "Registry").put("method", "release").put("instance", I).put("key", "6f74");
        var a = o.putArray("attempts");
        attempts.forEach((h, t) -> a.addObject().put("txHash", h).put("validTo", t));
        return o;
    }

    /** K1: after a rewind revives an expired attempt, the client restores both attempts with its one record. */
    @Test void aRewindAcrossTwoAttemptsRestoresWithThePersistedRecord() throws Exception {
        String c1 = cbor(1, 40, 100), c2 = cbor(2, 103, 200);
        var b1 = Reservations.Body.decode(c1, 0);
        var b2 = Reservations.Body.decode(c2, 0);
        var table = new Reservations(2, false, 0);
        var op0 = KeyedOperation.parse(op(Map.of()));
        table.checkOperation(op0, true, 50, null);
        table.commit(table.prepare(b1, 50, op0));
        var op1 = KeyedOperation.parse(op(Map.of(b1.hash(), 100L)));
        table.observe(103);                                     // A1 expires unlanded
        table.checkOperation(op1, true, 103, null);
        table.commit(table.prepare(b2, 103, op1));
        var record = op(new LinkedHashMap<>(Map.of(b1.hash(), 100L, b2.hash(), 200L)));
        table.rewind(90);                                       // A1 is prepared again
        var restore = obj().put("complete", true);
        var txs = restore.putArray("transactions");
        txs.addObject().put("state", "prepared").put("cbor", c1).set("operation", record);
        txs.addObject().put("state", "prepared").put("cbor", c2).set("operation", record);
        var reply = table.restore(restore);
        reply.commit().run();
        assertEquals(2, reply.value().required("reservations").size());
        table.allowBuild();                                     // reconciled
        assertTrue(table.avoids(D), "both attempts still hold the designation");
        // A record that drops a known attempt is still refused.
        var short1 = obj().put("complete", true);
        short1.putArray("transactions").addObject().put("state", "prepared").put("cbor", c2).set("operation", op(Map.of(b2.hash(), 200L)));
        table.rewind(90);
        assertEquals("operation", assertThrows(HostError.class, () -> table.restore(short1).commit().run()).code);
    }

    /** K2: an attempt that expired before it was handed out (the user declined late) no longer wedges the operation. */
    @Test void anUnsignedAttemptThatExpiredDoesNotHoldTheOperation() throws Exception {
        var b1 = Reservations.Body.decode(cbor(1, 40, 100), 0);
        var table = new Reservations(2, false, 0);
        var op0 = KeyedOperation.parse(op(Map.of()));
        table.commit(table.prepare(b1, 50, op0));
        table.observe(500);                                     // expired while awaiting approval
        table.checkOperation(op0, true, 500, null);             // a new attempt with the unchanged record is allowed
        // One the client did hand out must still be listed.
        var table2 = new Reservations(2, false, 0);
        var listed = KeyedOperation.parse(op(Map.of(b1.hash(), 100L)));
        table2.commit(table2.prepare(b1, 50, op0));
        // Acknowledges the handoff, then answers pending while the attempt is live.
        assertEquals("pending", assertThrows(HostError.class, () -> table2.checkOperation(listed, true, 50, null)).code);
        table2.observe(500);
        assertEquals("operation", assertThrows(HostError.class, () -> table2.checkOperation(op0, true, 500, null)).code);
    }

    /** F-K3: a repeat prepare while an unsigned, unlisted attempt is live answers pending, not a record error. */
    @Test void aRepeatWhileAnUnsignedAttemptIsLiveIsPending() throws Exception {
        var b1 = Reservations.Body.decode(cbor(1, 40, 100), 0);
        var table = new Reservations(2, false, 0);
        var op0 = KeyedOperation.parse(op(Map.of()));
        table.commit(table.prepare(b1, 50, op0));
        assertEquals("pending", assertThrows(HostError.class, () -> table.checkOperation(op0, true, 60, null)).code);
        table.observe(103);                                     // expired unsigned (K2): the retry goes through
        table.checkOperation(op0, true, 103, null);
    }

    /**
     * F-K1: attempt A expires unsigned, the retry B is prepared with the unchanged record, and the devnet is rewound.
     * Restoring B with the client's record ([] while unsigned, [B] once handed out) succeeds whether A stays expired
     * (tip at or above A's validTo) or is revived (below it); a revived A stays reserved until discarded or expired.
     */
    @Test void restoreSkipsAnUnacknowledgedAttemptAcrossBothRewinds() throws Exception {
        String c1 = cbor(1, 40, 100), c2 = cbor(2, 103, 200);
        var b1 = Reservations.Body.decode(c1, 0);
        var b2 = Reservations.Body.decode(c2, 0);
        for (long rewound : new long[] {150, 90}) {
            for (boolean handedOut : new boolean[] {false, true}) {
                var table = new Reservations(2, false, 0);
                var op0 = KeyedOperation.parse(op(Map.of()));
                table.checkOperation(op0, true, 50, null);
                table.commit(table.prepare(b1, 50, op0));
                table.observe(103);                             // A expires before it was handed out
                table.checkOperation(op0, true, 103, null);
                table.commit(table.prepare(b2, 103, op0));
                table.rewind(rewound);
                var record = op(handedOut ? Map.of(b2.hash(), 200L) : Map.of());
                var restore = obj().put("complete", true);
                restore.putArray("transactions").addObject().put("state", "prepared").put("cbor", c2).set("operation", record);
                var reply = table.restore(restore);
                reply.commit().run();
                table.allowBuild();                             // reconciled: the session is not wedged
                assertTrue(table.avoids(D), rewound+" "+handedOut);
                var next = KeyedOperation.parse(record);
                // B is live either way, so a further retry waits.
                assertEquals("pending", assertThrows(HostError.class, () -> table.checkOperation(next, true, rewound, null)).code);
            }
        }
    }

    static ObjectNode discard(Reservations.Entry e, long tip) {
        return obj().put("reservation", e.id()).put("txHash", e.body().hash()).put("status", "discarded").put("tip", tip);
    }

    /**
     * #156 Codex review: the retry's wallet read can be past the first attempt's validTo + settlementSlots, which expires
     * it first; the handoff the retry acknowledges still counts, so a rewind that revives the attempt cannot let a
     * discard release inputs the signed body spends.
     */
    @Test void aRetryAcknowledgesAnAttemptThatExpiredBeforeIt() throws Exception {
        var b1 = Reservations.Body.decode(cbor(1, 40, 100), 0);
        var table = new Reservations(2, false, 0);
        var e1 = table.prepare(b1, 50, KeyedOperation.parse(op(Map.of())));
        table.commit(e1);
        table.observe(500);                                     // the retry's read: A1 expires before the check
        table.checkOperation(KeyedOperation.parse(op(Map.of(b1.hash(), 100L))), true, 500, null);
        table.rewind(90);                                       // A1 is prepared again
        assertEquals("conflict", assertThrows(HostError.class, () -> table.settled(discard(e1, 90), Runnable::run)).code);
    }

    /** #156 Codex review: the acknowledgement is taken before any chain read, so a failed read cannot lose it. */
    @Test void theAcknowledgementNeedsNoChainRead() throws Exception {
        var b1 = Reservations.Body.decode(cbor(1, 40, 100), 0);
        var table = new Reservations(2, false, 0);
        var e1 = table.prepare(b1, 50, KeyedOperation.parse(op(Map.of())));
        table.commit(e1);
        table.acknowledge(KeyedOperation.parse(op(Map.of(b1.hash(), 100L))));   // the retry's wallet read then fails
        assertEquals("conflict", assertThrows(HostError.class, () -> table.settled(discard(e1, 60), Runnable::run)).code);
        // Only a matching deadline acknowledges.
        var table2 = new Reservations(2, false, 0);
        var e2 = table2.prepare(b1, 50, KeyedOperation.parse(op(Map.of())));
        table2.commit(e2);
        table2.acknowledge(KeyedOperation.parse(op(Map.of(b1.hash(), 99L))));
        table2.settled(discard(e2, 60), Runnable::run).commit().run();
    }

    /**
     * #156 Codex review: one restore batch whose retry B lists A as handed out while A's own record does not is refused
     * in either order (the protocol journals one record per operation); across batches the fuller record is kept on
     * the earlier entry, so A cannot be discarded.
     */
    @Test void contradictoryRecordsAreRefusedInEitherOrder() throws Exception {
        String c1 = cbor(1, 40, 100), c2 = cbor(2, 103, 200);
        var b1 = Reservations.Body.decode(c1, 0);
        var fuller = op(Map.of(b1.hash(), 100L));
        var empty = op(Map.of());
        for (boolean bFirst : new boolean[] {true, false}) {
            var table = new Reservations(2, true, 0);
            var restore = obj().put("complete", true);
            var txs = restore.putArray("transactions");
            if (bFirst) txs.addObject().put("state", "prepared").put("cbor", c2).set("operation", fuller);
            txs.addObject().put("state", "prepared").put("cbor", c1).set("operation", empty);
            if (!bFirst) txs.addObject().put("state", "prepared").put("cbor", c2).set("operation", fuller);
            assertEquals("operation", assertThrows(HostError.class, () -> table.restore(restore)).code, "B first: " + bFirst);
        }
        // Across batches, A (empty record) then B (A handed out): A takes B's record.
        var table = new Reservations(2, true, 0);
        var first = obj().put("complete", false);
        first.putArray("transactions").addObject().put("state", "prepared").put("cbor", c1).set("operation", empty);
        var r1 = table.restore(first);
        r1.commit().run();
        var second = obj().put("complete", true);
        second.putArray("transactions").addObject().put("state", "prepared").put("cbor", c2).set("operation", fuller);
        table.restore(second).commit().run();
        String a = r1.value().get("reservations").get(0).asText();
        var discardA = obj().put("reservation", a).put("txHash", b1.hash()).put("status", "discarded").put("tip", 60);
        assertEquals("conflict", assertThrows(HostError.class, () -> table.settled(discardA, Runnable::run)).code);
        // The other order across batches is refused.
        var table2 = new Reservations(2, true, 0);
        var firstB = obj().put("complete", false);
        firstB.putArray("transactions").addObject().put("state", "prepared").put("cbor", c2).set("operation", fuller);
        table2.restore(firstB).commit().run();
        var secondA = obj().put("complete", true);
        secondA.putArray("transactions").addObject().put("state", "prepared").put("cbor", c1).set("operation", empty);
        assertEquals("operation", assertThrows(HostError.class, () -> table2.restore(secondA)).code);
    }

    /**
     * #156 Codex review: an insert whose key a live reservation mints may still be rolled back, so the runtime asks the
     * store before reporting the key taken for good; it is held until the reservation settles.
     */
    @Test void aLiveInsertHoldsItsKeyedToken() throws Exception {
        String policy = "ab".repeat(28), name = "4b" + "cd".repeat(28);
        var mint = new ArrayList<MultiAsset>(List.of(MultiAsset.builder().policyId(policy)
                .assets(new ArrayList<>(List.of(new Asset("0x" + name, BigInteger.ONE)))).build()));
        var body = TransactionBody.builder()
                .inputs(new ArrayList<>(List.of(new TransactionInput("aa".repeat(32), 0))))
                .outputs(new ArrayList<>()).fee(BigInteger.ONE).ttl(100).validityStartInterval(40).mint(mint).build();
        String c = Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).build().serializeToHex();
        var b = Reservations.Body.decode(c, 0);
        var table = new Reservations(2, false, 0);
        var e = table.prepare(b, 50, KeyedOperation.parse(op(Map.of())));
        table.commit(e);
        assertTrue(table.reservesToken(policy + name));
        assertFalse(table.reservesToken(policy + "4b" + "ef".repeat(28)));
        table.settled(discard(e, 60), Runnable::run).commit().run();
        assertFalse(table.reservesToken(policy + name), "released with its reservation");
    }

    /**
     * #156 Codex review, round 2: A expires unsigned, the retry B is handed out, and a rewind revives A. The client's
     * record lists only B; restoring A first or B first both reconcile, and neither attempt can then be discarded.
     */
    @Test void aRevivedUnsignedAttemptRestoresInEitherOrder() throws Exception {
        String c1 = cbor(1, 40, 100), c2 = cbor(2, 103, 200);
        var b1 = Reservations.Body.decode(c1, 0);
        var b2 = Reservations.Body.decode(c2, 0);
        for (boolean aFirst : new boolean[] {true, false}) {
            var table = new Reservations(2, false, 0);
            var op0 = KeyedOperation.parse(op(Map.of()));
            var e1 = table.prepare(b1, 50, op0);
            table.commit(e1);
            table.observe(103);                                 // A expires before it was handed out
            table.checkOperation(op0, true, 103, null);
            var e2 = table.prepare(b2, 103, op0);
            table.commit(e2);                                   // B is handed out (the host learns it on restore)
            table.rewind(90);                                   // A is live again
            var record = op(Map.of(b2.hash(), 200L));
            var restore = obj().put("complete", true);
            var txs = restore.putArray("transactions");
            if (aFirst) txs.addObject().put("state", "prepared").put("cbor", c1).set("operation", record);
            txs.addObject().put("state", "prepared").put("cbor", c2).set("operation", record);
            if (!aFirst) txs.addObject().put("state", "prepared").put("cbor", c1).set("operation", record);
            table.restore(restore).commit().run();
            table.allowBuild();
            assertEquals("conflict", assertThrows(HostError.class, () -> table.settled(discard(e2, 95), Runnable::run)).code,
                    "A first: " + aFirst);
        }
    }
}
