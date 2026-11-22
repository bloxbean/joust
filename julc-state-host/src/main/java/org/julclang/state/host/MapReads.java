package org.julclang.state.host;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.state.runtime.MapHistory;
import java.util.*;
import static org.julclang.state.host.Wire.obj;

/** Protocol-1 map history, acquired inside the current call's read generation and budget. */
record MapReads(Reads reads) {
    /** A redeemer datum's upper bound: four times today's 16 KiB maximum transaction size. */
    static final int MAX_REDEEMER_BYTES = 64 * 1024;
    List<String> history(String unit) {
        var txs = new LinkedHashSet<String>();
        var cursors = new HashSet<String>();
        JsonNode point = null, previous = null;
        String cursor = null;
        do {
            var request = obj().put("unit", unit);
            if (cursor != null) request.put("cursor", cursor);
            var page = reads.mapAnswer("assetHistory", request);
            if (point != null && !point.equals(page.get("tip"))) throw inconsistent("history chunks have different tips");
            point = page.get("tip");
            if (page.has("absent") && cursor == null) { reads.accepted(); return List.of(); }
            var items = page.path("value");
            if (!items.isArray()) throw inconsistent("expected ordered asset history");
            for (var item : items) {
                String tx = item.path("txHash").asText("");
                var block = item.path("point");
                if (!tx.matches("[0-9a-f]{64}") || !txs.add(tx) || !chainPoint(block)
                        || !atOrBefore(block, point) || previous != null && !atOrBefore(previous, block))
                    throw inconsistent("duplicate, unordered or invalid history entry");
                previous = block;
            }
            cursor = page.path("more").textValue();
            if (page.has("more") && (cursor == null || cursor.isEmpty() || !cursors.add(cursor)))
                throw inconsistent("invalid history cursor");
        } while (cursor != null);
        reads.accepted();   // every page at one chain point, ordered (#149 Codex: only accepted reads advance reservations)
        return List.copyOf(txs);
    }

    private static boolean chainPoint(JsonNode p) {
        for (String f : List.of("slot", "height"))
            if (!p.path(f).isIntegralNumber() || !p.path(f).canConvertToLong() || p.get(f).longValue() < 0) return false;
        return p.path("hash").asText("").matches("[0-9a-f]{64}");
    }

    private static boolean atOrBefore(JsonNode a, JsonNode b) {
        return a.equals(b) || a.get("slot").longValue() < b.get("slot").longValue()
                && a.get("height").longValue() < b.get("height").longValue() && !a.get("hash").equals(b.get("hash"));
    }

    void rebuild(String unit, String verifier, NodeStore store) {
        var withdrawals = new ArrayList<byte[]>();
        for (String tx : history(unit)) {
            var rs = reads.mapAnswer("redeemers", obj().put("txHash", tx)).path("value");
            if (!rs.isArray()) throw inconsistent("missing redeemers for history transaction");
            var pointers = new HashSet<String>();
            for (var r : rs) {
                String purpose = r.path("purpose").asText("");
                var index = r.path("index");
                String script = r.path("scriptHash").asText(""), hash = r.path("dataHash").asText("");
                if (!Set.of("spend","mint","cert","reward","voting","proposing").contains(purpose)
                        || !index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0
                        || !pointers.add(purpose + index) || !hash.matches("[0-9a-f]{64}") || !script.matches("[0-9a-f]{56}"))
                    throw inconsistent("invalid transaction redeemer");
                if (!purpose.equals("reward") || !script.equals(verifier)) continue;
                var datum = reads.mapAnswer("datum", obj().put("hash", hash)).path("value");
                if (!datum.isTextual() || datum.textValue().isEmpty()) throw inconsistent("missing map redeemer datum");
                // A redeemer lives inside a transaction, so it is never larger than one (16 KiB today); bound it well
                // above that so one answer cannot make the claim replay quadratic (#160 final review F-M1).
                if (datum.textValue().length() > 2 * MAX_REDEEMER_BYTES) throw inconsistent("map redeemer datum larger than a transaction");
                try {
                    byte[] bytes = HexFormat.of().parseHex(datum.textValue());
                    if (!hash.equals(HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(bytes))))
                        throw inconsistent("map datum hash mismatch");
                    withdrawals.add(bytes);
                } catch (RuntimeException e) { throw inconsistent("malformed or inconsistent map history claim"); }
            }
        }
        try { MapHistory.replay(store, withdrawals); }
        catch (RuntimeException e) { throw inconsistent("malformed or inconsistent map history claim"); }
        reads.accepted();   // the redeemers and datums replayed
    }

    static HostError inconsistent(String message) { return HostError.internal("inconsistentRead", message); }
}
