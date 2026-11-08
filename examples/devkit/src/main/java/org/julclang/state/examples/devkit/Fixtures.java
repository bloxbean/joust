package org.julclang.state.examples.devkit;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.model.Block;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.runtime.snapshot.Snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the fixtures {@link FixtureRecorder} writes (ADR-0015 M0) as {@link Snapshot}s: the chain as the recorded call
 * saw it. Reads are folded in order, a later answer replacing an earlier one (a fixture's first reads can be the
 * previous transaction's confirmation polling). Submissions and transaction lookups are not part of a snapshot.
 */
public final class Fixtures {

    static final ObjectMapper JSON = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Fixtures() {}

    /** A fixture's JSON, each {@code {"$ref": sha}} result replaced by its blob. */
    public static JsonNode read(Path fixture) throws IOException {
        JsonNode f = JSON.readTree(fixture.toFile());
        Path blobs = fixture.getParent().resolveSibling("blobs");
        for (JsonNode r : f.get("reads")) {
            JsonNode result = r.get("result");
            if (result.isObject() && result.has("$ref")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) r).set("result",
                        JSON.readTree(blobs.resolve(result.get("$ref").asText() + ".json").toFile()));
            }
        }
        return f;
    }

    /** The snapshot of a fixture read by {@link #read}. */
    public static Snapshot snapshot(JsonNode fixture) throws IOException {
        Snapshot s = new Snapshot();
        for (JsonNode r : fixture.get("reads")) {
            String m = r.get("method").asText();
            JsonNode args = r.get("args");
            JsonNode res = r.get("result");
            boolean ok = res.path("successful").asBoolean();
            JsonNode v = res.get("value");
            switch (m) {
                case "getLatestBlock" -> { if (ok) s.tip = JSON.treeToValue(v, Block.class); }
                case "getProtocolParameters" -> { if (ok) s.protocolParams = JSON.treeToValue(v, ProtocolParams.class); }
                case "getPlutusScript" -> {
                    if (ok) s.scripts.put(args.get(0).asText(), new Snapshot.Script(v.path("type").asText(), v.path("cborHex").asText()));
                }
                case "getTxOutput" -> {
                    String ref = Snapshot.ref(args.get(0).asText(), args.get(1).asInt());
                    if (ok && v != null && !v.isNull()) s.outputs.put(ref, JSON.treeToValue(v, Utxo.class));
                    else s.absent.add(ref);
                }
                case "getUtxos" -> {
                    boolean byUnit = args.size() >= 4 && args.get(1).isTextual();
                    String key = byUnit ? args.get(0).asText() + "|" + args.get(1).asText() : args.get(0).asText();
                    int page = args.get(byUnit ? 3 : 2).asInt();
                    List<Utxo> list = new ArrayList<>();
                    if (ok && v != null) for (JsonNode u : v) list.add(JSON.treeToValue(u, Utxo.class));
                    var into = byUnit ? s.assetUtxos : s.utxos;
                    if (page <= 1) into.put(key, list);
                    else into.computeIfAbsent(key, k -> new ArrayList<>()).addAll(list);
                }
                default -> { }   // submissions, transaction and account lookups: not part of a call's snapshot
            }
        }
        return s;
    }
}
