package org.julclang.state.host;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static org.julclang.state.host.Wire.*;

final class Listings {
    private record Snapshot(String method, String contract, String instance, String tip, List<ObjectNode> items) {}
    // Insertion order is recency. Touch only once delivery commits (D10).
    private final LinkedHashMap<String, Snapshot> snapshots = new LinkedHashMap<>();
    private long sequence;

    void clear() { snapshots.clear(); }

    Transport.Reply start(String method, Contracts.Contract contract, String instance, List<Utxo> utxos, String tip, int limit) {
        var datums = new ContractDatums(contract);
        var items = utxos.stream().sorted(Comparator.comparing(Utxo::getTxHash).thenComparingInt(Utxo::getOutputIndex))
                .map(datums::recognize)
                .filter(i -> i.has("unrecognized") || (instance == null ? !i.has("object")
                        : i.has("object") && instance.equals(i.path("instance").asText()))).toList();
        return page(Long.toString(++sequence), new Snapshot(method, contract.name(), instance, tip, items), 0, limit);
    }

    static int limit(JsonNode p) {
        long limit = number(p, "limit", 1);
        if (limit > 500) throw bad("limit exceeds 500");
        return (int) limit;
    }

    Transport.Reply resume(String method, JsonNode p) {
        fields(p, "listing", "after", "limit");
        String id = str(p, "listing");
        var snapshot = snapshots.get(id);
        if (snapshot == null || !snapshot.method.equals(method)) throw HostError.bad("listingGone", "unknown listing");
        long after = number(p, "after", 1);
        if (after >= snapshot.items.size()) throw bad("invalid listing cursor");
        return page(id, snapshot, (int) after, limit(p));
    }

    private Transport.Reply page(String id, Snapshot snapshot, int start, int limit) {
        int end = start + Math.min(limit, snapshot.items.size() - start);
        var out = obj().put("listing", id);
        out.set("items", JSON.valueToTree(snapshot.items.subList(start, end)));
        if (end == snapshot.items.size()) out.putNull("next");
        else out.put("next", end);
        return new Transport.Reply(out, () -> {
            snapshots.putLast(id, snapshot);
            if (snapshots.size() > 8) snapshots.pollFirstEntry();
        });
    }
}
