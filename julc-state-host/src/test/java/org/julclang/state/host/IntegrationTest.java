package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.julclang.state.host.Client.*;
import static org.julclang.state.host.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationTest {
    static ReplayTest.Recording record() throws Exception {
        return ReplayTest.Recording.read(ReplayTest.FIXTURES.resolve("dapp/0003-composed_transaction.json"));
    }
    @Test void pendingInstanceAndRewindFenceAcrossPrepares() throws Exception {
        var r = record();
        try (var c = session()) {
            success(c.rpc("initialize",init()));
            var tx = success(c.rpc("prepare",r.params(),r::answer)).get("prepared");
            error("refusal","pending",c.rpc("prepare",r.params(),r::answer));
            long now = r.params().get("now").asLong();
            success(c.rpc("reset",obj().put("now",now)));
            error("refusal","pending",c.rpc("prepare",r.params(),r::answer));
            var e = obj().put("reservation",tx.get("reservation").asText()).put("txHash",tx.get("txHash").asText())
                    .put("status","expired").put("tip",tx.get("validTo").asLong()+3);
            assertEquals("expired",success(c.rpc("settled",e)).get("state").asText());
            var reset = obj().put("now",now);
            reset.putObject("rewound").put("tip",now);
            success(c.rpc("reset",reset));
            error("refusal","reconcile",c.rpc("prepare",r.params()));
            var done = obj().put("complete",true);
            done.putArray("transactions");
            success(c.rpc("restore",done));
            error("refusal","pending",c.rpc("prepare",r.params(),r::answer));
            success(c.rpc("settled",e.put("status","discarded").put("tip",now)));
            var again = success(c.rpc("prepare",r.params(),r::answer)).get("prepared");
            assertEquals(tx.get("cbor"),again.get("cbor"));
            assertNotEquals(tx.get("reservation"),again.get("reservation"));
        }
    }
    @Test void clockLimitsAndReset() throws Exception {
        var r = record();
        long now = r.params().get("now").asLong();
        try (var c = session()) {
            success(c.rpc("initialize",init()));
            var p = r.params().deepCopy();
            p.remove("ttlSlots");
            error("bad","request",c.rpc("prepare",p));
            error("bad","request",c.rpc("prepare",p.put("ttlSlots",901)));
            error("bad","nowImplausible",c.rpc("prepare",p.put("ttlSlots",7).put("now",now+901),r::answer));
            error("bad","nowDecreased",c.rpc("prepare",r.params()));
            var reset = obj().put("now",now);
            reset.set("clock",init().get("clock"));
            success(c.rpc("reset",reset));
            var tx = success(c.rpc("prepare",r.params().deepCopy().put("ttlSlots",7),r::answer)).get("prepared");
            assertEquals(now+7,tx.get("validTo").asLong());
            assertTrue(tx.get("validFrom").isNull());
        }
    }
    @Test void expiredOnArrivalNeverReserves() throws Exception {
        var r = record();
        try (var c = session()) {
            success(c.rpc("initialize",init()));
            error("refusal","expiredOnArrival",c.rpc("prepare",r.params(),req -> {
                var a = r.answer(req);
                a.set("tip", r.point().deepCopy().put("slot",r.point().get("slot").asLong()+20));
                return a;
            }));
            assertEquals(r.fixture().get("txHash"),success(c.rpc("prepare",r.params(),r::answer)).at("/prepared/txHash"));
        }
    }
    @Test void missingReferenceAndProviderFailureStopBeforePublication() throws Exception {
        var r = record();
        try (var c = session()) {
            success(c.rpc("initialize", init()));
            error("refusal", "prerequisite", c.rpc("prepare", r.params(), req -> {
                assertEquals("utxos", req.required("kind").asText());
                return obj().put("absent", true).set("tip", r.point());
            }));
            var failed = c.rpc("prepare", r.params(), req -> {
                var answer = obj();
                answer.set("tip", r.point());
                answer.putObject("failed").put("message", "provider disconnected");
                return answer;
            });
            error("internal", "readFailed", failed);
            assertEquals("provider disconnected", failed.at("/error/data/message").asText());
            assertEquals(r.fixture().get("txHash"), success(c.rpc("prepare", r.params(), r::answer)).at("/prepared/txHash"));
        }
    }
    @Test void wrongNetworkAndCallerSuppliedInputs() throws Exception {
        var r = record();
        try (var c = session()) {
            var p = init();
            p.withObject("/network").put("networkId",1).put("mainnet",true);
            success(c.rpc("initialize",p));
            error("bad","network",c.rpc("prepare",r.params()));
            var req = r.params().deepCopy();
            req.withObject("/call").putArray("walletUtxos");
            error("bad","request",c.rpc("prepare",req));
        }
    }
    @Test void prepareHasNoFileOrSocketIo(@TempDir Path dir) throws Exception {
        var r = record();
        try (var c = session(); var recording = new Recording()) {
            success(c.rpc("initialize",init()));
            for (String event : List.of("jdk.FileWrite","jdk.SocketRead","jdk.SocketWrite","jdk.ProcessStart"))
                recording.enable(event).withThreshold(Duration.ZERO);
            recording.start();
            Files.writeString(dir.resolve("control"),"positive control");
            assertEquals(r.fixture().get("txHash"),success(c.rpc("prepare",r.params(),r::answer)).at("/prepared/txHash"));
            recording.stop();
            Path log = dir.resolve("events.jfr");
            recording.dump(log);
            var events = RecordingFile.readAllEvents(log);
            assertTrue(events.stream().anyMatch(e -> e.getEventType().getName().equals("jdk.FileWrite")
                    && e.getString("path").endsWith("control")));
            for (var event : events) {
                if (event.getThread() != null && event.getThread().getJavaName().startsWith("joust-call-"))
                    fail("host execution performed I/O: " + event);
            }
        }
    }
}
