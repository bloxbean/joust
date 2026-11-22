package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.julclang.state.host.ReadsTest.*;
import static org.julclang.state.host.Wire.*;

class MapReadsTest {
    @Test void restoreRetryCannotReplaceMapClaimsUnderTheSameBodyHash() throws Exception {
        var honest = JSON.readTree(ClientVectors.DIRECTORY.resolve("token-m3-mint-2.json").toFile());
        var mutated = JSON.readTree(ClientVectors.DIRECTORY.resolve("reject-map-policy.json").toFile());
        String original = honest.at("/result/prepared/cbor").asText();
        var tx = Transaction.deserialize(HexFormat.of().parseHex(original));
        var other = Transaction.deserialize(HexFormat.of().parseHex(mutated.at("/result/prepared/cbor").asText()));
        tx.setWitnessSet(other.getWitnessSet());
        byte[] changed = tx.serialize();
        assertEquals(honest.at("/result/prepared/txHash").asText(), TransactionUtil.getTxHash(changed));
        var entry = M1bTest.entry(org.julclang.state.examples.token.TokenClient.class);
        try (var client = new Client(new Handshake(List.of(entry)))) {
            Client.success(client.rpc("initialize", Client.init(entry).put("restoring", true)));
            var batch = obj().put("complete", false);
            var item = batch.putArray("transactions").addObject().put("state", "prepared").put("cbor", original);
            var first = Client.success(client.rpc("restore", batch));
            // #160 Codex: an ordinary reset between batches keeps the journaled claims of the live reservation.
            Client.success(client.rpc("reset", obj().put("now", 0)));
            batch.put("complete", true);
            item.put("cbor", HexFormat.of().formatHex(changed));
            Client.error("bad", "conflict", client.rpc("restore", batch));
            item.put("cbor", original);
            assertEquals(first, Client.success(client.rpc("restore", batch)));
            Client.error("refusal", "mapsStale", client.rpc("prepare", honest.required("request")));
        }
    }

    @Test void badHistoryCannotSupplyAProofForTheSpentRoot() throws Exception {
        var recording = MapRecording.record("token", MapRecording.m3("token", "mint-2"));
        var original = RecordedSessionsTest.answers(recording);
        for (String fault : List.of("duplicate", "future", "omitted", "missingRedeemer", "missingDatum", "hash", "unavailable", "failed",
                "duplicateVerifier")) {
            var result = HostVectorsTest.run(recording, q -> {
                ObjectNode a = original.apply(q).deepCopy();
                switch (str(q,"kind")) {
                    case "assetHistory" -> {
                        var txs = a.withArray("value");
                        if (fault.equals("duplicate")) txs.add(txs.get(0));
                        if (fault.equals("omitted")) txs.removeAll();
                        if (fault.equals("future")) ((ObjectNode)txs.get(0).get("point")).put("slot", Long.MAX_VALUE);
                    }
                    case "redeemers" -> {
                        if (fault.equals("missingRedeemer")) { a.remove("value"); a.put("absent",true); }
                        if (fault.equals("unavailable")) { a.remove("value"); a.put("unsupported",true); }
                        if (fault.equals("duplicateVerifier")) {   // #160 Codex: a second verifier entry, the same datum
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

    @Test void shallowConfirmationPromotesTheActualPreparedTrieAndRollbackUnpublishesIt() throws Exception {
        var mint = MapRecording.record("token", MapRecording.m3("token", "mint"));
        var later = MapRecording.record("token", MapRecording.m3("token", "mint-2"));
        var entry = M1bTest.entry(org.julclang.state.examples.token.TokenClient.class);
        try (var client = new Client(new Handshake(List.of(entry)))) {
            Client.success(client.rpc("initialize",Client.init(entry)));
            var prepared = Client.success(client.rpc("prepare",mint.required("params"),RecordedSessionsTest.answers(mint))).required("prepared");
            long tip = later.at("/params/now").asLong();
            var view = obj().put("now",tip).put("view","balanceOf");
            view.set("instance",mint.at("/params/call/instance"));
            view.set("contract",mint.at("/params/call/contract"));
            view.putObject("args").set("holder",mint.at("/params/call/args/to"));
            var answers = RecordedSessionsTest.answers(later);
            java.util.function.Function<JsonNode, JsonNode> noHistory = q -> {
                var a = answers.apply(q);
                if (str(q,"kind").equals("assetHistory")) return obj().put("unsupported",true).set("tip",a.required("tip"));
                return a;
            };
            Client.error("unsupported","read:assetHistory",client.rpc("view",view,noHistory));
            var settled = obj().put("status","confirmed").put("slot",tip-1).put("tip",tip);
            settled.set("txHash",prepared.required("txHash"));
            settled.set("reservation",prepared.required("reservation"));
            assertEquals("shallow",Client.success(client.rpc("settled",settled)).required("state").asText());
            assertEquals("1000",Client.success(client.rpc("view",view,noHistory)).required("value").asText());
            settled.put("status","rolledBack").remove("slot");
            Client.success(client.rpc("settled",settled));
            Client.error("unsupported","read:assetHistory",client.rpc("view",view,noHistory));
            var reset = obj().put("now",tip);
            reset.putObject("rewound").put("tip",tip);
            Client.success(client.rpc("reset",reset));
            Client.error("refusal","mapsStale",client.rpc("view",view));
            Client.error("refusal","reconcile",client.rpc("prepare",later.required("params")));
            var restore = obj().put("complete",true);
            restore.putArray("transactions");
            Client.success(client.rpc("restore",restore));
            Client.error("refusal","mapsStale",client.rpc("view",view));
            settled.put("status","discarded");
            Client.success(client.rpc("settled",settled));
            Client.success(client.rpc("reset",obj().put("now",tip)));
            assertEquals("1000",Client.success(client.rpc("view",view,answers)).required("value").asText());
            assertEquals("1000",Client.success(client.rpc("view",view,noHistory)).required("value").asText());
        }
    }

    @Test void chunksShareOneTipAndBothCapsIncludeHistory() {
        for (var limits : List.of(new long[]{1,256},new long[]{2,255},new long[]{2,256})) {
            var reads = reads(q -> value(JSON.createArrayNode(), q.has("cursor") ? 11 : 10).put("more","2"),limits[0],limits[1]);
            boolean cap = limits[0]==1 || limits[1]==255;
            failure(cap ? "bad" : "internal", cap ? "readLimit" : "inconsistentRead", () -> new MapReads(reads).history("unit"));
        }
    }

    @Test void onlyConfirmedLayersAreVisibleAndARevertedParentCannotBreakItsChild() {
        var m = new MapMirrors();
        byte[] root = {1}, node = {2}, other = {3};
        var first = m.new Overlay();
        first.put(root,root);
        first.put(node,node);
        var body = new Reservations.Body(HASH, Set.of(), Set.of(), 0, 20);
        var a = new Reservations.Entry("1",body,Reservations.State.prepared,null,null);
        m.stage("1","policy",first);
        assertNull(m.new Overlay().get(root));
        m.transition(a.as(Reservations.State.shallow,10L));
        var second = m.new Overlay();
        assertArrayEquals(root,second.get(root));
        second.put(other,other);
        m.stage("2","policy",second);
        m.transition(a);
        assertNull(m.new Overlay().get(root));
        var b = new Reservations.Entry("2",body,Reservations.State.shallow,11L,null);
        m.transition(b);
        assertArrayEquals(node,m.new Overlay().get(node));
        m.transition(a.as(Reservations.State.expired,null));
        m.transition(b.as(Reservations.State.confirmed,11L));
        assertArrayEquals(other,m.new Overlay().get(other));
        m.reset(List.of());
        assertNull(m.new Overlay().get(node));
    }
}
