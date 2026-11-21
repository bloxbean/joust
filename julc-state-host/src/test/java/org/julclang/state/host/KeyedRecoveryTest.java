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
}
