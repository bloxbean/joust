package org.julclang.state.host;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import org.julclang.state.runtime.MapHistory;
import java.util.*;
import static org.julclang.state.host.Wire.obj;

/**
 * Protocol-1 map history, read inside the current call's read budget. The provider is trusted (bloxbean/joust#169):
 * the anchor is the input datum's root, which the replay must reach, and every claim's proofs are verified.
 */
record MapReads(Reads reads) {
    /** A redeemer datum's upper bound: four times today's 16 KiB maximum transaction size. */
    static final int MAX_REDEEMER_BYTES = 64 * 1024;

    /** The unit's history transactions, one {@code more}/cursor page after another. */
    List<String> history(String unit) {
        var txs = new LinkedHashSet<String>();
        var cursors = new HashSet<String>();   // pages are cached per call: a repeated cursor would loop
        String cursor = null;
        do {
            var request = obj().put("unit", unit);
            if (cursor != null) request.put("cursor", cursor);
            var page = reads.mapAnswer("assetHistory", request);
            if (page.has("absent")) return List.copyOf(txs);
            var items = page.path("value");
            if (!items.isArray()) throw inconsistent("expected an asset history");
            for (var item : items) {
                String tx = text(item.path("txHash"));
                if (!tx.matches("[0-9a-f]{64}")) throw inconsistent("invalid history entry");
                txs.add(tx);
            }
            cursor = page.path("more").textValue();
            if (cursor != null && !cursors.add(cursor)) throw inconsistent("history cursor repeats");
        } while (cursor != null);
        return List.copyOf(txs);
    }

    /**
     * Replays the unit's history into {@code store}; {@code reached}: whether the replay reached the root the call
     * needs. The store is content-addressed, so replayed nodes are kept even if the call fails later.
     */
    void rebuild(String unit, String verifier, NodeStore store, java.util.function.BooleanSupplier reached) {
        var txs = history(unit);
        // About two reads per history transaction not yet answered in this call (its redeemers and the map verifier's
        // datum); a call rebuilding two units may share transactions (#160 review F5).
        long needed = 2L * txs.stream().filter(tx -> !reads.hasMapAnswer("redeemers", obj().put("txHash", tx))).count();
        if (needed > reads.remainingReads())
            throw HostError.bad("readLimit", "rebuilding the map history of " + unit + " (" + txs.size()
                    + " transactions) needs up to " + (reads.readLimit() - reads.remainingReads() + needed)
                    + " reads in this call; readsPerCall is " + reads.readLimit());
        var withdrawals = new ArrayList<byte[]>();
        for (String tx : txs) {
            var rs = reads.mapAnswer("redeemers", obj().put("txHash", tx)).path("value");
            if (!rs.isArray()) throw inconsistent("missing redeemers for history transaction");
            boolean verified = false;
            for (var r : rs) {
                if (!"reward".equals(r.path("purpose").asText()) || !verifier.equals(text(r.path("scriptHash")))) continue;
                // One reward account, one withdrawal per transaction: a second would replay one datum twice.
                if (verified) throw inconsistent("two map verifier withdrawals in one history transaction");
                verified = true;
                String hash = text(r.path("dataHash"));
                if (!hash.matches("[0-9a-f]{64}")) throw inconsistent("invalid map redeemer data hash");
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
        if (!reached.getAsBoolean()) throw inconsistent("history does not reach the input datum root");
    }

    // A JSON string's text, else "" (which no hash pattern matches): a number's digits must not pass as hex.
    private static String text(com.fasterxml.jackson.databind.JsonNode n) { return n.isTextual() ? n.textValue() : ""; }

    static HostError inconsistent(String message) { return HostError.internal("inconsistentRead", message); }
}
