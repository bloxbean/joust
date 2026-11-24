package org.julclang.state.host;

import com.bloxbean.cardano.client.transaction.spec.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.julclang.state.host.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

/** ADR-0022 D11's keyed retry rules on the reservation table: the designated input alone makes a retry safe. */
class KeyedRecoveryTest {
    static final String D = "aa".repeat(32) + "#0";
    static final String I = "cd".repeat(32);

    static String cbor(long fee, long from, long to) throws Exception {
        var body = TransactionBody.builder()
                .inputs(new ArrayList<>(List.of(new TransactionInput("aa".repeat(32), 0), new TransactionInput("bb".repeat(32), (int) fee))))
                .outputs(new ArrayList<>()).fee(BigInteger.valueOf(fee)).ttl(to).validityStartInterval(from).build();
        return Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).build().serializeToHex();
    }
    static KeyedOperation op(Map<String, Long> attempts) {
        var o = obj().put("designated", D).put("contract", "Registry").put("method", "release").put("instance", I).put("key", "6f74");
        var a = o.putArray("attempts");
        attempts.forEach((h, t) -> a.addObject().put("txHash", h).put("validTo", t));
        return KeyedOperation.parse(o);
    }
    static ObjectNode discard(Reservations.Entry e, long tip) {
        return obj().put("reservation", e.id()).put("txHash", e.body().hash()).put("status", "discarded").put("tip", tip);
    }
    static String code(Runnable r) { return assertThrows(HostError.class, r::run).code; }

    /** Rule 2: every attempt spends the designation. */
    @Test void aBodyMustSpendTheDesignation() throws Exception {
        var b = Reservations.Body.decode(cbor(1, 40, 100), 0);
        var table = new Reservations(2, false, 0);
        assertEquals("operation", code(() -> table.prepare(b, 50, "ee".repeat(32) + "#0")));
        table.commit(table.prepare(b, 50, D));
    }

    /** Rule 3: pending while a live entry spends the designation, or while a listed attempt may still settle. */
    @Test void aRetryWaitsForLiveAndListedAttempts() throws Exception {
        var b1 = Reservations.Body.decode(cbor(1, 40, 100), 0);
        var table = new Reservations(2, false, 0);
        table.checkOperation(op(Map.of()), true, 50, null);
        var e1 = table.prepare(b1, 50, D);
        table.commit(e1);
        assertEquals("pending", code(() -> table.checkOperation(op(Map.of()), true, 60, null)));   // awaiting approval
        var listed = op(Map.of(b1.hash(), 100L));
        table.observe(102);                                     // validTo + settlementSlots: still within the margin
        assertEquals("pending", code(() -> table.checkOperation(listed, true, 102, null)));
        table.observe(103);                                     // expired: the retry runs
        table.checkOperation(listed, true, 103, null);
        assertFalse(table.avoids(D));
    }

    /** Rule 4: a spent designation means an attempt may have landed. */
    @Test void aSpentDesignationMayHaveLanded() {
        var table = new Reservations(2, false, 0);
        assertEquals("mayHaveLanded", code(() -> table.checkOperation(op(Map.of()), false, 50, null)));
    }

    /** ADR-0016's contested gap: the node rejected the attempt; discarded and left out of the record, it is rebuilt at once. */
    @Test void aRejectedAttemptIsRebuiltAtOnce() throws Exception {
        var b1 = Reservations.Body.decode(cbor(1, 40, 100), 0);
        var b2 = Reservations.Body.decode(cbor(2, 40, 100), 0);
        var table = new Reservations(2, false, 0);
        var e1 = table.prepare(b1, 50, D);
        table.commit(e1);
        table.settled(discard(e1, 51), Runnable::run).commit().run();
        table.checkOperation(op(Map.of()), true, 51, null);
        table.commit(table.prepare(b2, 51, D));
        assertTrue(table.avoids(D));
    }

    /** A restart: the restored body holds its designation again; no record is needed. */
    @Test void restoreReservesTheDesignation() throws Exception {
        var table = new Reservations(2, true, 0);
        var restore = obj().put("complete", true);
        restore.putArray("transactions").addObject().put("state", "prepared").put("cbor", cbor(1, 40, 100));
        table.restore(restore).commit().run();
        table.allowBuild();
        assertEquals("pending", code(() -> table.checkOperation(op(Map.of()), true, 50, null)));
    }

    /**
     * An insert whose key a live reservation mints may still be rolled back, so the runtime asks the store before
     * reporting the key taken for good; it is held until the reservation settles. The same token keeps another keyed
     * operation pending.
     */
    @Test void aLiveInsertHoldsItsKeyedToken() throws Exception {
        String policy = "ab".repeat(28), name = "4b" + "cd".repeat(28);
        var mint = new ArrayList<MultiAsset>(List.of(MultiAsset.builder().policyId(policy)
                .assets(new ArrayList<>(List.of(new Asset("0x" + name, BigInteger.ONE)))).build()));
        var body = TransactionBody.builder()
                .inputs(new ArrayList<>(List.of(new TransactionInput("aa".repeat(32), 0))))
                .outputs(new ArrayList<>()).fee(BigInteger.ONE).ttl(100).validityStartInterval(40).mint(mint).build();
        var b = Reservations.Body.decode(Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).build().serializeToHex(), 0);
        var table = new Reservations(2, false, 0);
        var e = table.prepare(b, 50, D);
        table.commit(e);
        assertTrue(table.reservesToken(policy + name));
        assertFalse(table.reservesToken(policy + "4b" + "ef".repeat(28)));
        var other = KeyedOperation.parse(obj().put("designated", "ee".repeat(32) + "#1").put("contract", "Registry")
                .put("method", "release").put("instance", I).put("key", "6f74").set("attempts", JSON.createArrayNode()));
        assertEquals("pending", code(() -> table.checkOperation(other, true, 60, policy + name)));
        table.settled(discard(e, 60), Runnable::run).commit().run();
        assertFalse(table.reservesToken(policy + name), "released with its reservation");
    }
}
