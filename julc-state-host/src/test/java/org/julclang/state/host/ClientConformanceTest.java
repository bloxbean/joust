package org.julclang.state.host;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.julclang.state.host.conformance.ReferenceChecker;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.julclang.state.host.ClientVectors.*;
import static org.julclang.state.host.Wire.*;
import static org.julclang.state.host.Client.*;

class ClientConformanceTest {
    @TestFactory List<DynamicTest> everyPositiveMatchesTheLiveHost() {
        return CASES.stream().map(name -> DynamicTest.dynamicTest(name,() -> {
            var actual = positive(name);
            var shipped = JSON.readTree(DIRECTORY.resolve(name+".json").toFile());
            assertEquals(shipped,JSON.readTree(JSON.writeValueAsBytes(actual)));
            assertTrue(shipped.required("violations").isEmpty());
            assertEquals(List.of(),ReferenceChecker.check(shipped));
            if (name.startsWith("dapp-")) {
                assertEquals(name.endsWith("0003") ? 0 : 1,shipped.at("/result/prepared/plan/redeemers/0/index").intValue());
                assertEquals(JSON.readTree(ReplayTest.FIXTURES.resolve("dapp/"+name.substring(5)+"-composed_transaction.json")
                        .toFile()).get("unsignedTx"),shipped.at("/result/prepared/cbor"));
            }
        })).toList();
    }

    @Test void noUnexercisedVectors() throws Exception {
        try (var files = Files.list(DIRECTORY)) {
            var names = files.map(p -> p.getFileName().toString()).filter(n -> !n.startsWith("reject-")).toList();
            assertEquals(new TreeSet<>(CASES.stream().map(n -> n+".json").toList()),new TreeSet<>(names));
        }
    }

    @Test void contextSeesTheClientClockOneDayAheadOfTheMachine() throws Exception {
        var v = recording("Bank-init");
        var clock = v.withObject("/clock");
        long now = v.at("/params/now").asLong();
        long length = clock.required("slotLengthMs").asLong();
        long clientTime = System.currentTimeMillis() + java.time.Duration.ofDays(1).toMillis();
        clock.put("zeroTimeMs",clientTime - (now - clock.required("zeroSlot").asLong()) * length);
        var built = success(HostVectorsTest.run(v,RecordedSessionsTest.answers(v))).required("prepared");
        var schema = JSON.readTree(DIRECTORY.resolve("Bank-init.json").toFile()).at("/anchors/description");
        var codec = new org.julclang.state.runtime.described.SchemaCodec(schema);
        var mint = schema.required("validators").findParents("redeemer").stream()
                .filter(val -> val.path("title").asText().endsWith(".mint")).findFirst().orElseThrow();
        var data = org.julclang.core.cbor.PlutusDataCborDecoder.decode(HexFormat.of().parseHex(
                built.at("/plan/redeemers/0/data").textValue()));
        var ctx = codec.decode(mint.at("/redeemer/schema"),data).required("ctx");
        assertEquals(clientTime,ctx.required("validFrom").asLong());
        assertEquals(clientTime + length * v.at("/params/ttlSlots").asLong(),ctx.required("validTo").asLong());
    }

    @Test void unsolicitedReverseIdCannotAnswerAnOutstandingPrepareRead() throws Exception {
        var r = IntegrationTest.record();
        try (var c = session()) {
            success(c.rpc("initialize",init()));
            int id = c.request("prepare",r.params());
            var frame = c.next();
            error("bad","busy",c.rpc("prepare",r.params()));
            c.send(obj().put("id","999.999").set("result",obj().put("failed",true)));
            while (frame.has("method")) {
                c.send(obj().put("id",frame.required("id").textValue()).set("result",r.answer(frame.required("params"))));
                frame = c.next();
            }
            assertEquals(id,frame.get("id").intValue());
            assertEquals(r.fixture().get("txHash"),success(frame).at("/prepared/txHash"));
        }
    }
}
