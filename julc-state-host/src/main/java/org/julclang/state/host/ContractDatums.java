package org.julclang.state.host;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.state.runtime.described.SchemaCodec;
import java.math.BigInteger;
import java.util.*;
import static org.julclang.state.host.Wire.*;

/** Only datums declared for this contract. A token narrows the possible schemas on a listing. */
final class ContractDatums {
    private record Type(String kind, String name, JsonNode schema) {}
    private final List<Type> types = new ArrayList<>();
    private final SchemaCodec codec;
    private final String policy;
    private final boolean keyed;

    ContractDatums(Contracts.Contract contract) {
        policy = contract.hash();
        keyed = contract.keyed();
        codec = new SchemaCodec(contract.description());
        var x = contract.description().path("x-joust");
        add("instance", x.path("state"));
        if (x.has("intents")) add("intent", x.at("/intents/datum"));
        for (var owned : x.path("owned")) add("owned", owned.path("holding"));
        for (var keyed : x.path("keyed"))
            for (String field : List.of("head", "node", "retired")) add("keyed", keyed.path(field));
    }

    private void add(String kind, JsonNode ref) {
        types.add(new Type(kind, codec.resolve(ref).required("title").asText(), ref));
    }

    static PlutusData parse(String hex) {
        if (hex == null || hex.isEmpty()) throw new IllegalArgumentException("missing datum");
        try {
            byte[] cbor = HexFormat.of().parseHex(hex);
            SchemaCodec.checkNesting(cbor, "datum");   // a recursive decoder: the npm host would trap past its stack
            return PlutusDataCborDecoder.decode(cbor);
        }
        catch (RuntimeException malformed) { throw new IllegalArgumentException("invalid Plutus data", malformed); }
    }

    ObjectNode decode(String hex, String kind) {
        PlutusData data = parse(hex);
        var matches = JSON.createArrayNode();
        var decoded = obj();
        for (Type t : types) {
            if (kind != null && !t.kind.equals(kind)) continue;
            try {
                var value = codec.decode(t.schema, data);
                matches.add(t.name);
                decoded.put("type", t.name).set("value", value);
            } catch (SchemaCodec.DataError mismatch) { /* Try the next declared schema. */ }
        }
        if (matches.isEmpty()) throw new IllegalArgumentException("no matching datum type");
        if (matches.size() > 1) return obj().set("ambiguous", matches);
        return decoded;
    }

    /** A listing item, or null for an intent (a recognized kind that listings omit). */
    ObjectNode recognize(Utxo u) {
        String ref = u.getTxHash() + "#" + u.getOutputIndex();
        var unknown = obj().put("unrecognized", ref);
        String id = null, kind = null;
        for (var amount : u.getAmount()) {
            if (!amount.getUnit().startsWith(policy)) continue;
            String token = amount.getUnit().substring(policy.length());
            if (!Reads.reservedName(token, keyed)) continue;
            if (id != null || !BigInteger.ONE.equals(amount.getQuantity())) return unknown;
            id = token;
            kind = token.length() == 64 ? "instance" : token.startsWith("4f") ? "owned" : token.startsWith("49") ? "intent" : "keyed";
        }
        if (id == null) return unknown;
        if (kind.equals("intent")) return null;
        try {
            var item = decode(u.getInlineDatum(), kind);
            if (item.has("ambiguous")) return unknown;
            item.put("ref", ref);
            if (kind.equals("instance")) return item.put("instance", id);
            String owner = item.at("/value/instance").asText("");
            if (!owner.matches("[0-9a-f]{64}")) return unknown;
            return item.put("object", id).put("instance", owner);
        } catch (RuntimeException | StackOverflowError malformed) { return unknown; }
    }
}
