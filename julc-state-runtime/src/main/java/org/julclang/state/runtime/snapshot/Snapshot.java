package org.julclang.state.runtime.snapshot;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.model.Block;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The chain as a call sees it (ADR-0015 D5): the tip, the protocol parameters, the complete UTxO set at some
 * addresses, outputs by reference, references known to be absent, and scripts by hash. {@link SnapshotBackend} answers
 * from it and throws {@link Need} for anything else.
 * <p>
 * JSON: <pre>{ "tip": Block, "protocolParams": ProtocolParams, "utxos": { address: [Utxo] },
 *   "assetUtxos": { "address|unit": [Utxo] }, "outputs": [Utxo], "absent": ["txHash#index"],
 *   "scripts": { hash: { "type": "PlutusScriptV3", "cborHex": "…" } } }</pre>
 * with CCL's own JSON for {@code Block}, {@code ProtocolParams} and {@code Utxo}.
 */
public final class Snapshot {

    static final ObjectMapper JSON = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * A script by hash: its CCL type name ({@code PlutusScriptV1..V3}) and its CBOR; or only {@code cbor}, as a
     * Blockfrost-style API serves it ({@code /scripts/{hash}/cbor}), the version found from the hash as CCL does.
     */
    public record Script(String type, String cborHex, String cbor) {
        public Script(String type, String cborHex) {
            this(type, cborHex, null);
        }
    }

    public Block tip;
    public ProtocolParams protocolParams;
    /** The complete set at each address, in the chain API's ascending order. */
    public final Map<String, List<Utxo>> utxos = new LinkedHashMap<>();
    /** At an address, holding a unit ({@code address|unit}): used when the address's complete set is not known. */
    public final Map<String, List<Utxo>> assetUtxos = new LinkedHashMap<>();
    public final Map<String, Utxo> outputs = new LinkedHashMap<>();
    public final Set<String> absent = new LinkedHashSet<>();
    public final Map<String, Script> scripts = new LinkedHashMap<>();

    public static String ref(String txHash, int index) {
        return txHash + "#" + index;
    }

    /** The output {@code txHash#index} from any set or lookup this snapshot holds (an output never changes), or null. */
    public Utxo output(String txHash, int index) {
        Utxo u = outputs.get(ref(txHash, index));
        if (u != null) return u;
        for (List<List<Utxo>> sets : List.of(List.copyOf(utxos.values()), List.copyOf(assetUtxos.values()))) {
            for (List<Utxo> set : sets) {
                for (Utxo x : set) if (x.getTxHash().equals(txHash) && x.getOutputIndex() == index) return x;
            }
        }
        return null;
    }

    public static Snapshot fromJson(String json) {
        try {
            JsonNode n = JSON.readTree(json);
            Snapshot s = new Snapshot();
            if (n.hasNonNull("tip")) s.tip = JSON.treeToValue(n.get("tip"), Block.class);
            if (n.hasNonNull("protocolParams")) s.protocolParams = JSON.treeToValue(n.get("protocolParams"), ProtocolParams.class);
            n.path("utxos").fields().forEachRemaining(e -> s.utxos.put(e.getKey(), utxoList(e.getValue())));
            n.path("assetUtxos").fields().forEachRemaining(e -> s.assetUtxos.put(e.getKey(), utxoList(e.getValue())));
            for (Utxo u : utxoList(n.path("outputs"))) s.outputs.put(ref(u.getTxHash(), u.getOutputIndex()), u);
            for (JsonNode a : n.path("absent")) s.absent.add(a.asText());
            n.path("scripts").fields().forEachRemaining(e -> s.scripts.put(e.getKey(), new Script(
                    e.getValue().path("type").asText(null), e.getValue().path("cborHex").asText(null), e.getValue().path("cbor").asText(null))));
            return s;
        } catch (Exception e) {
            throw new IllegalArgumentException("not a snapshot: " + e.getMessage(), e);
        }
    }

    public String toJson() {
        ObjectNode n = JSON.createObjectNode();
        if (tip != null) n.set("tip", JSON.valueToTree(tip));
        if (protocolParams != null) n.set("protocolParams", JSON.valueToTree(protocolParams));
        ObjectNode u = n.putObject("utxos");
        utxos.forEach((k, v) -> u.set(k, JSON.valueToTree(v)));
        ObjectNode a = n.putObject("assetUtxos");
        assetUtxos.forEach((k, v) -> a.set(k, JSON.valueToTree(v)));
        n.set("outputs", JSON.valueToTree(new ArrayList<>(outputs.values())));
        var abs = n.putArray("absent");
        absent.forEach(abs::add);
        ObjectNode sc = n.putObject("scripts");
        scripts.forEach((k, v) -> {
            var o = sc.putObject(k);
            if (v.type() != null) o.put("type", v.type());
            if (v.cborHex() != null) o.put("cborHex", v.cborHex());
            if (v.cbor() != null) o.put("cbor", v.cbor());
        });
        return n.toString();
    }

    private static List<Utxo> utxoList(JsonNode arr) {
        List<Utxo> out = new ArrayList<>();
        try {
            for (JsonNode x : arr) out.add(JSON.treeToValue(x, Utxo.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("bad UTxO: " + e.getMessage(), e);
        }
        return out;
    }
}
