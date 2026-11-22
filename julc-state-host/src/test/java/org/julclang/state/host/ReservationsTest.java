package org.julclang.state.host;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.julclang.state.host.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

class ReservationsTest {
    final Reservations table = new Reservations(3, false, 0);
    final Reservations.Body body = new Reservations.Body("hash", Set.of("spend"), Set.of("collateral"), 10, 30);
    Reservations.Entry entry = table.prepare(body, 9);

    ObjectNode event(String status, long tip, Long slot) {
        var e = obj().put("reservation", entry.id()).put("txHash", body.hash()).put("status",status).put("tip",tip);
        if (slot != null) e.put("slot",slot.longValue());
        return e;
    }
    String settle(String status, long tip, Long slot) {
        var reply = table.settled(event(status,tip,slot), Runnable::run);
        reply.commit().run();
        return reply.value().required("state").asText();
    }
    void held(boolean expected) {
        assertEquals(expected, table.avoids("spend"));
        assertEquals(expected, table.avoids("collateral"));
    }
    static void code(String code, Runnable op) {
        assertEquals(code,assertThrows(HostError.class,op::run).code);
    }
    @Test void inclusionAndRollbackApplyAllThresholdsWithStrictMargin() {
        held(false);
        table.commit(entry);
        held(true);
        assertEquals("shallow", settle("confirmed",21,20L));
        assertEquals("shallow", settle("confirmed",23,20L));
        code("conflict",() -> settle("confirmed",22,21L));
        held(true);
        assertEquals("prepared",settle("rolledBack",29,null));
        assertEquals("prepared",settle("rolledBack",33,null));
        code("notExpired",() -> settle("expired",33,null));
        assertEquals("expired",settle("rolledBack",34,null)); // crosses expiry during the rollback event
        held(false);
        assertEquals("expired",settle("expired",34,null));
        code("conflict",() -> settle("discarded",34,null));
        code("conflict",() -> settle("confirmed",34,19L));
    }
    @Test void confirmedRewindAndDeepRollback() {
        table.commit(entry);
        assertEquals("confirmed",settle("confirmed",24,20L));
        held(false);
        table.rewind(23);  // retained inclusion, within its margin
        held(true);
        code("reconcile",table::allowBuild);
        table.observe(100);
        held(true);
        finish();
        assertEquals("shallow",settle("confirmed",23,20L));
        table.observe(24);
        held(false);
        table.rewind(19); // removed inclusion, may land again
        finish();
        assertEquals("prepared",settle("rolledBack",19,null));
        assertEquals("confirmed",settle("confirmed",28,23L));
        code("deepRollback",() -> settle("rolledBack",29,null));
        table.rewind(15);
        finish();
        code("deepRollback",table::allowBuild);
    }
    @Test void rewindRearmsOnlyRevivableTombstones() {
        table.commit(entry);
        assertEquals("expired",settle("expired",34,null));
        table.rewind(30);
        held(false);
        table.rewind(29);
        held(true);
        finish();
        assertEquals("discarded",settle("discarded",29,null));
        assertEquals("discarded",settle("discarded",29,null));
        table.rewind(10);
        held(false);
        finish();
        var repeat = table.prepare(body,20);
        assertNotEquals(entry.id(),repeat.id());
        table.commit(repeat);
        settle("discarded",25,null); // late event for original id
        held(true);
    }
    @Test void invalidOrExpiredBodyAndInvalidEvidence() {
        code("expiredOnArrival",() -> table.prepare(body,30));
        table.commit(entry);
        code("conflict",() -> settle("confirmed",12,13L));
        code("conflict",() -> settle("confirmed",30,30L));
        code("conflict",() -> settle("confirmed",8,8L));
        code("unknownReservation",() -> table.settled(event("confirmed",11,11L).put("reservation","?"),Runnable::run));
        code("conflict",() -> table.settled(event("confirmed",11,11L).put("txHash","?"),Runnable::run));
        code("restoreClosed",this::finish);
        held(true);
    }
    void finish() {
        var p = obj().put("complete",true);
        p.putArray("transactions");
        table.restore(p).commit().run();
    }
    @Test void restartRestoresSignedAndUnsignedCopiesUnderOneReservation() throws Exception {
        var recording = IntegrationTest.record();
        String unsigned = recording.fixture().required("unsignedTx").asText();
        var expected = Reservations.Body.decode(unsigned, 0);
        var tx = com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(HexFormat.of().parseHex(unsigned));
        // Simulate the client's handoff. The host identifies the exact body independently of witnesses.
        var keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var sig = java.security.Signature.getInstance("Ed25519");
        sig.initSign(keys.getPrivate());
        sig.update(HexFormat.of().parseHex(expected.hash()));
        byte[] encoded = keys.getPublic().getEncoded();
        tx.getWitnessSet().setVkeyWitnesses(List.of(new com.bloxbean.cardano.client.transaction.spec.VkeyWitness(
                Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length), sig.sign())));
        String signed = HexFormat.of().formatHex(tx.serialize());
        assertNotEquals(unsigned, signed);
        assertEquals(expected, Reservations.Body.decode(signed, 0));
        var fresh = new Reservations(2, true, 0);
        var p = obj().put("complete", true);
        var entries = p.putArray("transactions");
        for (String cbor : List.of(unsigned, signed)) entries.addObject().put("state", "prepared").put("cbor", cbor);
        var reply = fresh.restore(p);
        var ids = reply.value().required("reservations");
        assertEquals(2, ids.size());
        assertEquals(ids.get(0), ids.get(1));
        reply.commit().run();
        expected.inputs().forEach(ref -> assertTrue(fresh.avoids(ref)));
        expected.collateral().forEach(ref -> assertTrue(fresh.avoids(ref)));
    }
    @Test void restartRestoresShallowEvenPastValidityAndBatchesAreAtomic() throws Exception {
        var r = IntegrationTest.record();
        String cbor = r.fixture().get("unsignedTx").asText();
        var decoded = Reservations.Body.decode(cbor, 0);
        assertFalse(decoded.inputs().isEmpty());
        assertFalse(decoded.collateral().isEmpty());
        var fresh = new Reservations(10, true, 0);
        code("reconcile",fresh::allowBuild);
        var p = obj().put("complete",false);
        var txs = p.putArray("transactions");
        txs.addObject().put("state","shallow").put("slot",decoded.to()-1).put("cbor",cbor);
        txs.addObject().put("state","prepared").put("cbor","bad");
        code("request",() -> fresh.restore(p));
        decoded.collateral().forEach(ref -> assertFalse(fresh.avoids(ref)));
        txs.remove(1);
        var mainnet = new Reservations(10, true, 1);
        code("network", () -> mainnet.restore(p)); // a journal from a different chain
        var first = fresh.restore(p);
        first.commit().run();
        var repeat = fresh.restore(p.put("complete",true));
        repeat.commit().run();
        assertEquals(first.value(),repeat.value());
        fresh.allowBuild();
        code("restoreClosed",() -> fresh.restore(p));
        fresh.observe(decoded.to()+9); // validTo passed but retained inclusion is still shallow
        decoded.collateral().forEach(ref -> assertTrue(fresh.avoids(ref)));
        fresh.observe(decoded.to()+10);
        decoded.collateral().forEach(ref -> assertFalse(fresh.avoids(ref)));
        var tx = com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(HexFormat.of().parseHex(cbor));
        tx.getBody().setTtl(0);
        String unbounded = HexFormat.of().formatHex(tx.serialize());
        code("unbounded",() -> Reservations.Body.decode(unbounded, 0));
    }
    @Test void restoreFindsALiveBodyFromAnEarlierBatchOrTheSameOne() throws Exception {
        String cbor = IntegrationTest.record().fixture().get("unsignedTx").asText();
        var decoded = Reservations.Body.decode(cbor, 0);
        var fresh = new Reservations(10, true, 0);
        var p = obj().put("complete", false);
        var txs = p.putArray("transactions");
        txs.addObject().put("state", "shallow").put("slot", decoded.to() - 1).put("cbor", cbor);
        txs.addObject().put("state", "shallow").put("slot", decoded.to() - 1).put("cbor", cbor);
        var first = fresh.restore(p);
        assertEquals(first.value().at("/reservations/0"), first.value().at("/reservations/1"));
        first.commit().run();
        // A later batch finds the committed entry; a different state for it, in a batch, is a conflict.
        var later = obj().put("complete", false);
        later.putArray("transactions").addObject().put("state", "prepared").put("cbor", cbor);
        code("conflict", () -> fresh.restore(later));
        txs.remove(1);
        var repeat = fresh.restore(p);
        assertEquals(first.value().at("/reservations/0"), repeat.value().at("/reservations/0"));
        // Nothing is staged by a refused or an uncommitted batch.
        decoded.inputs().forEach(ref -> assertTrue(fresh.avoids(ref)));
    }
    @Test void aRearmedTombstoneNeverHidesTheLiveReservationOfItsBody() throws Exception {
        String cbor = IntegrationTest.record().fixture().get("unsignedTx").asText();
        var b = Reservations.Body.decode(cbor, 0);
        long to = b.to();
        var t = new Reservations(3, false, 0);
        var first = t.prepare(b, to - 10);
        t.commit(first);
        t.observe(to + 4);                       // expired: 4 > margin 3
        t.rewind(to + 1);                        // still past its upper bound: stays expired
        var p = obj().put("complete", true);
        p.putArray("transactions").addObject().put("state", "shallow").put("slot", to - 1).put("cbor", cbor);
        var restored = t.restore(p);
        restored.commit().run();
        String second = restored.value().at("/reservations/0").asText();
        assertNotEquals(first.id(), second);
        t.rewind(to - 1);                        // the tombstone could land again, but the body is held by the shallow entry
        var again = t.restore(p);
        assertEquals(second, again.value().at("/reservations/0").asText());
        var prepared = obj().put("complete", true);
        prepared.putArray("transactions").addObject().put("state", "prepared").put("cbor", cbor);
        code("conflict", () -> t.restore(prepared));
        // Late settlement of the old reservation is still told apart.
        var late = obj().put("reservation", first.id()).put("txHash", b.hash()).put("status", "discarded").put("tip", to - 1);
        code("conflict", () -> t.settled(late, Runnable::run));
    }
}
