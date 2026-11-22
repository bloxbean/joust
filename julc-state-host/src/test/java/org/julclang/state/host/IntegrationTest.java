package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bloxbean.cardano.client.plutus.util.PlutusUtil;
import org.julclang.state.runtime.snapshot.Snapshot;
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
            success(c.rpc("settled", obj().put("reservation", tx.required("reservation").asText())
                    .put("txHash", tx.required("txHash").asText()).put("status", "discarded").put("tip", now)));
            var second = success(c.rpc("prepare", r.params().deepCopy().put("ttlSlots", 12), r::answer)).get("prepared");
            assertEquals(now + 12, second.required("validTo").longValue());
        }
    }
    @Test void timedEscrowUsesSessionGenesisAndSentNow() throws Exception {
        var file = ReplayTest.m3("escrow", "open");
        var vector = JSON.readTree(file.toFile());
        var snapshot = Snapshot.fromJson(vector.required("snapshot").toString());
        long tip = snapshot.tip.getSlot(), now = tip + 2;
        var point = obj().put("slot", tip).put("height", snapshot.tip.getHeight()).put("hash", snapshot.tip.getHash());
        var call = (ObjectNode) vector.required("call").deepCopy();
        // Synthetic address-set answer: the M3 vector pins a single holder by out-ref.
        var holder = snapshot.outputs.get(call.required("referenceScripts").get(0).textValue());
        snapshot.utxos.put(holder.getAddress(), List.of(holder));
        String hash = holder.getReferenceScriptHash();
        var script = PlutusUtil.getPlutusScript(hash, snapshot.scripts.get(hash).cbor()).orElseThrow();
        snapshot.scripts.put(hash, new Snapshot.Script("PlutusScriptV3", script.getCborHex()));
        call.remove(List.of("walletUtxos", "referenceScripts"));
        var params = obj().put("now", now).put("ttlSlots", 4);
        params.set("call", call);
        var reads = new ReplayTest.Recording(vector, snapshot, point, params);
        long deadline = Long.parseLong(call.at("/args/deadline").asText());
        long genesis = deadline - (now + 5) * 1000;
        Path entry = Path.of(org.julclang.state.examples.escrow.EscrowClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var c = new Client(new Handshake(List.of(entry)))) {
            var init = init(entry);
            var clock = init.withObject("/clock").put("zeroTimeMs", genesis).put("zeroSlot", 0).put("slotLengthMs", 1000);
            success(c.rpc("initialize", init));
            var tx = success(c.rpc("prepare", params, reads::answer)).required("prepared");
            assertEquals(now, tx.required("validFrom").longValue());
            assertEquals(now + 4, tx.required("validTo").longValue());
            success(c.rpc("settled", obj().put("reservation", tx.required("reservation").asText())
                    .put("txHash", tx.required("txHash").asText()).put("status", "discarded").put("tip", tip)));
            // Hold the transaction request and read answers fixed. The new genesis puts its end past the deadline.
            success(c.rpc("reset", obj().put("now", now).set("clock", clock.put("zeroTimeMs", genesis + 2000))));
            error("refusal", "require", c.rpc("prepare", params, reads::answer));
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
    @Test void mapContractCannotOpenAStoreOrRequestHistory() throws Exception {
        var entry = Path.of(org.julclang.state.examples.token.TokenClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var c = new Client(new Handshake(List.of(entry)))) {
            var contract = success(c.rpc("initialize", init(entry))).at("/contracts/0");
            assertEquals("maps", contract.required("unsupported").textValue());
            var params = record().params().deepCopy();
            params.withObject("/call").put("contract", contract.required("name").textValue());
            error("unsupported", "maps", c.rpc("prepare", params)); // the peer fails any reverse request
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
