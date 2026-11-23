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

    /** A view of the recorded Token instance at the later recording's tip, holding the first mint's recipient. */
    static ObjectNode balanceView(JsonNode mint, JsonNode later) {
        var view = obj().put("now", later.at("/params/now").asLong()).put("view", "balanceOf");
        view.set("instance", mint.at("/params/call/instance"));
        view.set("contract", mint.at("/params/call/contract"));
        view.putObject("args").set("holder", mint.at("/params/call/args/to"));
        return view;
    }

    @Test void restoringFencesMapViewsUntilTheRestoreCompletes() throws Exception {
        // #160 Codex re-review: a restarted host (restoring) must not read and cache a map before the batch naming its
        // pending transaction arrives, as a host that saw the rewind would not.
        var mint = MapRecording.record("token", MapRecording.m3("token", "mint"));
        var later = MapRecording.record("token", MapRecording.m3("token", "mint-2"));
        var entry = M1bTest.entry(org.julclang.state.examples.token.TokenClient.class);
        try (var client = new Client(new Handshake(List.of(entry)))) {
            Client.success(client.rpc("initialize", Client.init(entry).put("restoring", true)));
            var view = balanceView(mint, later);
            var answers = RecordedSessionsTest.answers(later);
            Client.error("refusal", "mapsStale", client.rpc("view", view, answers));
            var batch = obj().put("complete", false);
            batch.putArray("transactions");
            Client.success(client.rpc("restore", batch));
            Client.error("refusal", "mapsStale", client.rpc("view", view, answers));
            batch.put("complete", true);
            Client.success(client.rpc("restore", batch));
            assertEquals("1000", Client.success(client.rpc("view", view, answers)).required("value").asText());
        }
    }

    @Test void aRearmedTombstoneKeepsItsRestoredClaims() throws Exception {
        // #160 Codex re-review: expiry, then a rewind below validTo revives the reservation; its re-import is still
        // compared with the claims first restored under that body hash.
        var honest = JSON.readTree(ClientVectors.DIRECTORY.resolve("token-m3-mint-2.json").toFile());
        var mutated = JSON.readTree(ClientVectors.DIRECTORY.resolve("reject-map-policy.json").toFile());
        String original = honest.at("/result/prepared/cbor").asText();
        var tx = Transaction.deserialize(HexFormat.of().parseHex(original));
        long to = tx.getBody().getTtl();
        var other = Transaction.deserialize(HexFormat.of().parseHex(mutated.at("/result/prepared/cbor").asText()));
        tx.setWitnessSet(other.getWitnessSet());
        String changed = HexFormat.of().formatHex(tx.serialize());
        var entry = M1bTest.entry(org.julclang.state.examples.token.TokenClient.class);
        try (var client = new Client(new Handshake(List.of(entry)))) {
            Client.success(client.rpc("initialize", Client.init(entry).put("restoring", true)));
            var batch = obj().put("complete", true);
            var item = batch.putArray("transactions").addObject().put("state", "prepared").put("cbor", original);
            var restored = Client.success(client.rpc("restore", batch));
            String id = restored.required("reservations").get(0).asText();
            var expired = obj().put("reservation", id).put("status", "expired").put("tip", to + 1_000_000);
            expired.set("txHash", honest.at("/result/prepared/txHash"));
            assertEquals("expired", Client.success(client.rpc("settled", expired)).required("state").asText());
            var reset = obj().put("now", to + 1_000_000);
            reset.putObject("rewound").put("tip", to - 1);
            Client.success(client.rpc("reset", reset));
            item.put("cbor", changed);
            Client.error("bad", "conflict", client.rpc("restore", batch));
            item.put("cbor", original);
            assertEquals(restored, Client.success(client.rpc("restore", batch)));
        }
    }

    @Test void oneBlockHashNamesOnePointAcrossTheHistory() {
        // #160 Codex re-review: a hash seen again after an intervening block, at a later slot and height.
        var items = JSON.createArrayNode();
        items.addObject().put("txHash", "01".repeat(32)).set("point", point(5));
        items.addObject().put("txHash", "02".repeat(32)).set("point", point(6));
        items.addObject().put("txHash", "03".repeat(32)).set("point", point(7).put("hash", "%064x".formatted(5)));
        var reads = reads(q -> value(items, 10), 10, 8192);
        failure("internal", "inconsistentRead", () -> new MapReads(reads).history("unit"));
        // Several transactions at one identical point stay valid.
        var same = JSON.createArrayNode();
        same.addObject().put("txHash", "01".repeat(32)).set("point", point(5));
        same.addObject().put("txHash", "02".repeat(32)).set("point", point(5));
        assertEquals(2, new MapReads(reads(q -> value(same, 10), 10, 8192)).history("unit").size());
    }

    @Test void aHistoryBlockIsNotATipAtAnotherPoint() {
        // #160 Codex round 3: a history entry in block H at slot 5, then a later answer whose tip claims H at slot 12.
        var items = JSON.createArrayNode();
        items.addObject().put("txHash", "01".repeat(32)).set("point", point(5));
        var reads = reads(q -> q.path("kind").asText().equals("assetHistory") ? value(items, 10)
                : value(JSON.createArrayNode(), 12).set("tip", point(12).put("hash", "%064x".formatted(5))), 10, 8192);
        assertEquals(1, new MapReads(reads).history("unit").size());
        failure("internal", "chainMoved", () -> reads.answer("redeemers", obj().put("txHash", "01".repeat(32))));
    }

    @Test void aShallowReimportUnderANewIdKeepsTheRestoredClaims() throws Exception {
        // #160 Codex round 3: expiry, then a rewind to validTo (not rearmed), then the body restored as shallow at
        // validTo - 1: a new reservation id, compared with the claims first restored under that body hash.
        var honest = JSON.readTree(ClientVectors.DIRECTORY.resolve("token-m3-mint-2.json").toFile());
        var mutated = JSON.readTree(ClientVectors.DIRECTORY.resolve("reject-map-policy.json").toFile());
        String original = honest.at("/result/prepared/cbor").asText();
        var tx = Transaction.deserialize(HexFormat.of().parseHex(original));
        long to = tx.getBody().getTtl();
        var other = Transaction.deserialize(HexFormat.of().parseHex(mutated.at("/result/prepared/cbor").asText()));
        tx.setWitnessSet(other.getWitnessSet());
        String changed = HexFormat.of().formatHex(tx.serialize());
        var entry = M1bTest.entry(org.julclang.state.examples.token.TokenClient.class);
        try (var client = new Client(new Handshake(List.of(entry)))) {
            Client.success(client.rpc("initialize", Client.init(entry).put("restoring", true)));
            var batch = obj().put("complete", true);
            var item = batch.putArray("transactions").addObject().put("state", "prepared").put("cbor", original);
            String id = Client.success(client.rpc("restore", batch)).required("reservations").get(0).asText();
            var expired = obj().put("reservation", id).put("status", "expired").put("tip", to + 1_000_000);
            expired.set("txHash", honest.at("/result/prepared/txHash"));
            Client.success(client.rpc("settled", expired));
            var reset = obj().put("now", to + 1_000_000);
            reset.putObject("rewound").put("tip", to);
            Client.success(client.rpc("reset", reset));
            item.put("state", "shallow").put("slot", to - 1).put("cbor", changed);
            Client.error("bad", "conflict", client.rpc("restore", batch));
            item.put("cbor", original);
            Client.success(client.rpc("restore", batch));
        }
    }

    @Test void aPrepareRefusesAChainDatumWithPendingMapEntries() throws Exception {
        // #160 Codex round 3: the JVM method would read the pending entries, the engine's discovery would not; both
        // hosts refuse the datum before any history read.
        var v = MapRecording.record("token", MapRecording.m3("token", "transfer"));
        var original = RecordedSessionsTest.answers(v);
        byte[] sender = HexFormat.of().parseHex("00".repeat(28));
        for (boolean writes : List.of(false, true)) {
            var kinds = new ArrayList<String>();
            var result = HostVectorsTest.run(v, q -> {
                kinds.add(str(q, "kind"));
                return pending(original.apply(q), writes, sender);
            });
            Client.error("internal", "inconsistentRead", result);
            assertFalse(kinds.contains("assetHistory"), kinds.toString());
        }
    }

    @Test void aHistoryThatMissesALaterRootAdvancesNoReservation() {
        // #160 Codex re-review: a call that needs two cold roots. The replay reaches the first; until the call ends its
        // tip stays provisional, and a second root it does not reach is an inconsistent read that advanced nothing.
        var tips = new ArrayList<Long>();
        var reads = new Reads(req -> new Call.Answer(obj().set("result", obj().put("absent", true).set("tip", point(30))), 128),
                10, 20, 10, 8192, 0, tips::add);
        var store = new org.julclang.state.runtime.InMemoryNodeStore();
        var m = new MapReads(reads);
        m.rebuild("unit", "ab".repeat(28), store, () -> true);
        assertEquals(List.of(), tips, "the first root's replay is provisional");
        failure("internal", "inconsistentRead", () -> m.rebuild("unit", "ab".repeat(28), store, () -> false));
        assertEquals(List.of(), tips, "a history that misses the second root advances nothing");
        assertEquals(30, reads.historyTip(), "the tip a committed reply lets the table observe");
        assertEquals(List.of(), tips, "observed only when the call's reply is committed");
    }

    /** {@code answer} with every Token state datum's balances map given one pending entry in its reads or writes. */
    static JsonNode pending(JsonNode answer, boolean writes, byte[] key) {
        var copy = answer.deepCopy();
        copy.findParents("inline_datum").forEach(o -> {
            if (!o.path("inline_datum").isTextual()) return;
            try {
                var d = com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(HexFormat.of().parseHex(o.get("inline_datum").asText()));
                if (!(d instanceof com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData c) || c.getData().getPlutusDataList().size() != 3
                        || !(c.getData().getPlutusDataList().get(2) instanceof com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData map)) return;
                var entry = com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0,
                        com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(key),
                        com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(1),
                        com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(new byte[] {0x07}));
                map.getData().getPlutusDataList().set(writes ? 2 : 1, com.bloxbean.cardano.client.plutus.spec.ListPlutusData.of(entry));
                ((ObjectNode) o).put("inline_datum", c.serializeToHex());
                if (o.has("data_hash")) ((ObjectNode) o).put("data_hash", HexFormat.of().formatHex(
                        com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(HexFormat.of().parseHex(c.serializeToHex()))));
            } catch (Exception notState) { /* another datum */ }
        });
        return copy;
    }

    @Test void aViewRefusesAChainDatumWithPendingMapEntries() throws Exception {
        // #160 Codex re-review: the view reads a datum's pending reads or writes before the prover, so a datum on chain
        // (always root only) that carries either is an inconsistent read, not a balance the root does not prove.
        var mint = MapRecording.record("token", MapRecording.m3("token", "mint"));
        var later = MapRecording.record("token", MapRecording.m3("token", "mint-2"));
        var entry = M1bTest.entry(org.julclang.state.examples.token.TokenClient.class);
        var view = balanceView(mint, later);
        byte[] holder = HexFormat.of().parseHex(view.at("/args/holder").asText());
        var answers = RecordedSessionsTest.answers(later);
        for (boolean writes : List.of(false, true)) {
            try (var client = new Client(new Handshake(List.of(entry)))) {
                Client.success(client.rpc("initialize", Client.init(entry)));
                Client.error("internal", "inconsistentRead", client.rpc("view", view, q -> pending(answers.apply(q), writes, holder)));
                assertEquals("1000", Client.success(client.rpc("view", view, answers)).required("value").asText());
            }
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
