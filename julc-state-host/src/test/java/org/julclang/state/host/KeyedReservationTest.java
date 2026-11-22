package org.julclang.state.host;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.julclang.state.host.Wire.*;
import static org.julclang.state.host.ReservationsTest.code;
import static org.junit.jupiter.api.Assertions.*;

class KeyedReservationTest {
    @Test void restoreAndSettleAcrossEveryKeyedTransition() throws Exception {
        var v = ClientVectors.recording("registry-m3-release");
        var p = v.required("params").deepCopy();
        var built = v.required("expected");
        String hash = str(built,"txHash"), cbor = str(built,"cbor");
        long start = number(built,"validFrom",0), end = number(built,"validTo",0);
        ObjectNode record = p.at("/call/operation").deepCopy();
        record.withArray("attempts").addObject().put("txHash",hash).put("validTo",end);
        var op = KeyedOperation.parse(record);
        var restore = obj().put("complete",true);
        restore.putArray("transactions").addObject().put("state","prepared").put("cbor",cbor).set("operation",record);
        var table = new Reservations(2,true,0);
        var restored = table.restore(restore);
        code("reconcile",table::allowBuild);
        restored.commit().run();
        table.allowBuild();
        var ref = op.identity().designated();
        var event = obj().put("reservation",restored.value().at("/reservations/0").asText()).put("txHash",hash);
        assertTrue(table.avoids(ref));
        code("pending",() -> table.checkOperation(op,true,start,null));
        code("mayHaveLanded",() -> table.checkOperation(op,false,start,null));
        code("operation",() -> table.checkOperation(KeyedOperation.parse(record.deepCopy().put("instance","aa".repeat(32))),true,start,null));
        code("conflict",() -> table.settled(event.put("status","discarded").put("tip",start),Runnable::run));
        assertEquals("shallow",settle(table,event.put("status","confirmed").put("slot",start).put("tip",start+2)));
        assertEquals("shallow",settle(table,event));
        event.remove("slot");
        assertEquals("prepared",settle(table,event.put("status","rolledBack")));
        code("notExpired",() -> settle(table,event.put("status","expired").put("tip",end+2)));
        assertEquals("expired",settle(table,event.put("tip",end+3)));
        assertFalse(table.avoids(ref));
        table.checkOperation(op,true,end+3,null);
        code("mayHaveLanded",() -> table.checkOperation(op,false,end+3,null));
        table.rewind(start-1);
        table.restore(restore).commit().run();
        assertTrue(table.avoids(ref));
        assertEquals("confirmed",settle(table,event.put("status","confirmed").put("slot",start).put("tip",start+3)));
        assertFalse(table.avoids(ref));
        code("mayHaveLanded",() -> table.checkOperation(op,true,end+3,null));
        event.remove("slot");
        code("deepRollback",() -> settle(table,event.put("status","rolledBack")));
        code("deepRollback",table::allowBuild);
    }

    @Test void unsignedDiscardDoesNotBecomePartOfTheHandedOutHistory() throws Exception {
        var v = ClientVectors.recording("registry-m3-release");
        var op = KeyedOperation.parse(v.at("/params/call/operation"));
        var b = Reservations.Body.decode(v.at("/expected/cbor").asText(),0);
        var table = new Reservations(2,false,0);
        var first = table.prepare(b,b.from(),op);
        assertFalse(table.avoids(op.identity().designated()));
        table.commit(first);
        var event = obj().put("reservation",first.id()).put("txHash",b.hash()).put("status","discarded").put("tip",b.from());
        assertEquals("discarded",settle(table,event));
        table.checkOperation(op,true,b.from(),null);
        code("operation",() -> table.checkOperation(op.handedOut(b.hash(),b.to()),true,b.from(),null));
        var again = table.prepare(b,b.from(),op);
        table.commit(again);
        assertNotEquals(first.id(),again.id());
        assertEquals("discarded",settle(table,event));
        assertTrue(table.avoids(op.identity().designated()));
    }

    @Test void clientPageLimitsNarrowTheWindowOrYieldAnActionableCooldown() throws Exception {
        var v = ClientVectors.recording("registry-m3-release");
        var call = v.withObject("/params/call");
        var answers = RecordedSessionsTest.answers(v);
        long now = v.at("/params/now").asLong();
        long start = v.at("/expected/validFrom").asLong();
        call.remove("pageLimits");
        var defaults = Client.success(HostVectorsTest.run(v,answers)).required("prepared");
        assertEquals(v.at("/expected/cbor"),defaults.required("cbor"));
        call.putArray("pageLimits").add(60).add(start-now+1);
        var bounded = Client.success(HostVectorsTest.run(v,answers)).required("prepared");
        assertEquals(start,bounded.required("validFrom").asLong());
        assertEquals(start+1,bounded.required("validTo").asLong());
        call.putArray("pageLimits").add(60).add(start-now);
        var refused = HostVectorsTest.run(v,answers);
        Client.error("refusal","cooldown",refused);
        assertEquals(now+1,refused.at("/error/data/retryAtSlot").asLong());
    }

    private static String settle(Reservations table, ObjectNode e) {
        var reply = table.settled(e,Runnable::run);
        reply.commit().run();
        return str(reply.value(),"state");
    }
}
