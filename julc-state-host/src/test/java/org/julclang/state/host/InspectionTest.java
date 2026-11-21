package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.julclang.state.examples.bank.BankClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.julclang.state.host.Wire.*;
import static org.julclang.state.host.Client.*;
import static org.junit.jupiter.api.Assertions.*;

class InspectionTest {
    @Test void stateAndNamedViewAndDecodeUseTheRecordedDatum() throws Exception {
        var r = IntegrationTest.record();
        try (var c = session()) {
            success(c.rpc("initialize",init()));
            var q = obj().put("now",r.point().required("slot").asLong()).put("contract",r.params().at("/call/contract").asText())
                    .put("instance",r.params().at("/call/instance").asText()).putNull("view");
            q.putObject("args");
            var state = success(c.rpc("view",q,r::answer)).required("value");
            assertEquals(state.required("count"), success(c.rpc("view",q.put("view","count"),r::answer)).required("value"));
            var id = q.get("instance").textValue();
            var utxo = r.snapshot().utxos.values().stream().flatMap(List::stream)
                    .filter(u -> u.getAmount().stream().anyMatch(a -> a.getUnit().endsWith(id))).findFirst().orElseThrow();
            var p = obj().put("contract",q.get("contract").asText()).put("datum",utxo.getInlineDatum());
            var decoded = success(c.rpc("decode",p));
            assertEquals("State", str(decoded,"type"));
            assertEquals(state, decoded.required("value"));
            error("bad","request",c.rpc("decode",p.put("datum","ff")));
        }
    }

    @Test void decodeReportsAllMatchingSchemas() throws Exception {
        var entry = M1bTest.entry(BankClient.class);
        var c = Contracts.load(Launch.scan(entry.getParent(),List.of(entry)),getClass().getClassLoader())
                .get(BankClient.class.getName().replaceFirst("Client$",""));
        var desc = (ObjectNode)c.description().deepCopy();
        // Two datum types with identical constructor and fields. The token distinguishes them only on a listing.
        var state = desc.at("/x-joust/state/$ref").textValue().substring("#/definitions/".length());
        var holding = desc.at("/x-joust/owned/0/holding/$ref").textValue().substring("#/definitions/".length());
        var copy = (ObjectNode)desc.required("definitions").required(holding).deepCopy();
        desc.withObject("/definitions").set(state,copy.put("title","State"));
        var datums = new ContractDatums(new Contracts.Contract(c.name(),desc,c.keyed(),c.stakeSelf(),c.messages(),c.adapter(),c.hash(),null,c.views()));
        var r = ownedRecording();
        var id = r.params().at("/call/object").asText();
        var u = r.snapshot().utxos.values().stream().flatMap(List::stream)
                .filter(item -> item.getAmount().stream().anyMatch(a -> a.getUnit().endsWith(id))).findFirst().orElseThrow();
        assertEquals(List.of("State","Holding"),JSON.convertValue(datums.decode(u.getInlineDatum(),null).required("ambiguous"),List.class));
    }

    private static ReplayTest.Recording ownedRecording() throws Exception {
        try (var files = java.nio.file.Files.list(ReplayTest.FIXTURES.resolve("dapp"))) {
            for (var file : files.sorted().toList())
                if (JSON.readTree(file.toFile()).at("/call/kind").asText().equals("ownedAction"))
                    return ReplayTest.Recording.read(file);
        }
        throw new AssertionError("missing owned recording");
    }

    @Test void objectsFilterByDatumOwner() throws Exception {
        var r = ownedRecording();
        var entry = M1bTest.entry(BankClient.class);
        try (var c = new Client(new Handshake(List.of(entry)))) {
            success(c.rpc("initialize",init(entry)));
            var p = obj().put("contract",r.params().at("/call/contract").asText()).put("instance",r.params().at("/call/instance").asText())
                    .put("now",r.params().required("now").asLong()).put("limit",500);
            var items = success(c.rpc("objects",p,r::answer)).required("items");
            assertTrue(items.findValues("object").contains(r.params().at("/call/object")));
            var foreign = success(c.rpc("objects",p.put("instance","00".repeat(32)),r::answer)).required("items");
            assertTrue(foreign.findValues("object").isEmpty());
            assertFalse(foreign.findValues("unrecognized").isEmpty());
        }
    }

    @Test void interleavedForkSnapshotsRespectSortOrderResetAndLru() throws Exception {
        var r = IntegrationTest.record();
        try (var c = session()) {
            success(c.rpc("initialize",init()));
            var p = obj().put("now",r.params().required("now").asLong()).put("contract",r.params().at("/call/contract").asText())
                    .put("limit",1);
            var ids = new ArrayList<String>();
            for (int fork = 0; fork < 9; fork++) {
                final String hash = "%064x".formatted(fork + 16);
                var reads = new ArrayList<JsonNode>();
                var first = success(c.rpc("instances",p,q -> {
                    reads.add(q);
                    var a = r.answer(q);
                    var u = (ObjectNode)a.required("value").get(0).deepCopy();
                    u.remove(List.of("data_hash","inline_datum","reference_script_hash"));
                    u.putArray("amount").addObject().put("unit","lovelace").put("quantity","5000000");
                    var items = a.putArray("value");
                    for (int index : List.of(10,2,1)) items.add(u.deepCopy().put("tx_hash",hash).put("output_index",index));
                    a.set("tip",r.point().deepCopy().put("hash",hash));
                    return a;
                }));
                ids.add(str(first,"listing"));
                assertEquals(1,reads.size());
                assertEquals(hash+"#1",first.at("/items/0/unrecognized").asText());
                if (fork == 7) assertEquals("%064x#2".formatted(16),resume(c,ids.getFirst()).at("/items/0/unrecognized").asText());
            }
            // Refreshing the oldest between 8 and 9 evicted the second.
            assertEquals("%064x#2".formatted(16),resume(c,ids.getFirst()).at("/items/0/unrecognized").asText());
            error("bad","listingGone",c.rpc("instances",cursor(ids.get(1))));
            assertEquals("%064x#2".formatted(24),resume(c,ids.getLast()).at("/items/0/unrecognized").asText());
            error("bad","listingGone",c.rpc("objects",cursor(ids.getLast())));
            error("bad","request",c.rpc("instances",p.put("limit",501)));
            success(c.rpc("reset",obj().put("now",r.point().required("slot").asLong())));
            error("bad","listingGone",c.rpc("instances",cursor(ids.getLast())));
        }
    }

    private static ObjectNode cursor(String id) { return obj().put("listing",id).put("after",1).put("limit",1); }
    private static JsonNode resume(Client c, String id) throws Exception { return success(c.rpc("instances",cursor(id))); }
}
