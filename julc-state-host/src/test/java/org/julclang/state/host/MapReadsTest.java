package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.julclang.state.host.ReadsTest.*;
import static org.julclang.state.host.Wire.*;

class MapReadsTest {
    /** A view of the recorded Token instance at the later recording's tip, holding the first mint's recipient. */
    static ObjectNode balanceView(JsonNode mint, JsonNode later) {
        var view = obj().put("now", later.at("/params/now").asLong()).put("view", "balanceOf");
        view.set("instance", mint.at("/params/call/instance"));
        view.set("contract", mint.at("/params/call/contract"));
        view.putObject("args").set("holder", mint.at("/params/call/args/to"));
        return view;
    }

    @Test void historyAnswersAdvanceNoReservationAndMustReachTheRoot() {
        var tips = new ArrayList<Long>();
        var reads = new Reads(req -> new Call.Answer(obj().set("result", obj().put("absent", true).set("tip", point(30))), 128),
                10, 20, 10, 8192, 0, tips::add);
        var store = new MapNodes();
        var m = new MapReads(reads);
        m.rebuild("unit", "ab".repeat(28), store, () -> true);
        failure("internal", "inconsistentRead", () -> m.rebuild("unit", "ab".repeat(28), store, () -> false));
        assertEquals(List.of(), tips, "map history reads do not advance reservations");
    }

    @Test void badHistoryCannotSupplyAProofForTheSpentRoot() throws Exception {
        var recording = MapRecording.record("token", MapRecording.m3("token", "mint-2"));
        var original = RecordedSessionsTest.answers(recording);
        for (String fault : List.of("omitted", "missingRedeemer", "missingDatum", "hash", "unavailable", "failed", "duplicateVerifier")) {
            var result = HostVectorsTest.run(recording, q -> {
                ObjectNode a = original.apply(q).deepCopy();
                switch (str(q,"kind")) {
                    case "assetHistory" -> {
                        if (fault.equals("omitted")) a.withArray("value").removeAll();
                    }
                    case "redeemers" -> {
                        if (fault.equals("missingRedeemer")) { a.remove("value"); a.put("absent",true); }
                        if (fault.equals("unavailable")) { a.remove("value"); a.put("unsupported",true); }
                        if (fault.equals("duplicateVerifier")) {   // a second verifier entry, the same datum
                            var rs = a.withArray("value");
                            for (int i = 0; i < rs.size(); i++) if (rs.get(i).path("purpose").asText().equals("reward")) {
                                var copy = ((ObjectNode) rs.get(i)).deepCopy();
                                copy.put("index", rs.get(i).path("index").intValue() + 7);
                                rs.add(copy);
                                break;
                            }
                        }
                    }
                    case "datum" -> {
                        if (fault.equals("hash")) a.put("value","00");
                        if (fault.equals("missingDatum")) { a.remove("value"); a.put("absent",true); }
                        if (fault.equals("failed")) { a.remove("value"); a.putObject("failed").put("message","offline"); }
                    }
                }
                return a;
            });
            Client.error(fault.equals("unavailable") ? "unsupported" : "internal", switch (fault) {
                case "unavailable" -> "read:redeemers";
                case "failed" -> "readFailed";
                default -> "inconsistentRead";
            }, result);
        }
    }

    /** One content-addressed store per session: a prepare's nodes serve a later root without history; reset drops them. */
    @Test void aPreparesNodesServeItsRootUntilReset() throws Exception {
        var mint = MapRecording.record("token", MapRecording.m3("token", "mint"));
        var later = MapRecording.record("token", MapRecording.m3("token", "mint-2"));
        var entry = M1bTest.entry(org.julclang.state.examples.token.TokenClient.class);
        try (var client = new Client(new Handshake(List.of(entry)))) {
            Client.success(client.rpc("initialize",Client.init(entry)));
            var view = balanceView(mint, later);
            var answers = RecordedSessionsTest.answers(later);
            java.util.function.Function<JsonNode, JsonNode> noHistory = q -> {
                var a = answers.apply(q);
                if (str(q,"kind").equals("assetHistory")) return obj().put("unsupported",true).set("tip",a.required("tip"));
                return a;
            };
            Client.success(client.rpc("prepare",mint.required("params"),RecordedSessionsTest.answers(mint)));
            assertEquals("1000",Client.success(client.rpc("view",view,noHistory)).required("value").asText());
            Client.success(client.rpc("reset",obj().put("now",later.at("/params/now").asLong())));
            Client.error("unsupported","read:assetHistory",client.rpc("view",view,noHistory));
            assertEquals("1000",Client.success(client.rpc("view",view,answers)).required("value").asText());
            assertEquals("1000",Client.success(client.rpc("view",view,noHistory)).required("value").asText());
        }
    }

    @Test void anEndlessHistoryMeetsTheReadCaps() {
        for (var limits : List.of(new long[]{1,256},new long[]{2,255},new long[]{3,8192})) {
            var reads = reads(q -> value(JSON.createArrayNode(), 10)
                    .put("more", Integer.toString(Integer.parseInt(q.path("cursor").asText("1")) + 1)),limits[0],limits[1]);
            failure("bad", "readLimit", () -> new MapReads(reads).history("unit"));
        }
    }

    @Test void theReadLimitNamesTheBudgetARebuildNeeds() {
        var items = JSON.createArrayNode();
        for (int i = 0; i < 4; i++) items.addObject().put("txHash", "%064x".formatted(i + 1));
        var reads = reads(q -> value(items, 10), 5, 1 << 20);
        var e = assertThrows(HostError.class, () -> new MapReads(reads).rebuild("unit", "ab".repeat(28), new MapNodes(), () -> true));
        assertEquals("readLimit", e.code);
        assertTrue(e.getMessage().contains("needs up to 9 reads") && e.getMessage().contains("readsPerCall is 5"), e.getMessage());
    }
}
