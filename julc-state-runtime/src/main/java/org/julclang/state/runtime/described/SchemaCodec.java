package org.julclang.state.runtime.described;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * JSON values and Plutus data, by the CIP-57 schemas of a JOUST contract description (ADR-0013 D3, ADR-0015 D4). The
 * same conventions as the JavaScript client's codec ({@code js/state-client/src/data.js}), from the description's
 * {@code x-joust.encoding.json}:
 * <ul>
 *   <li>integer: a decimal string or a JSON integer; bytes: lowercase hex;</li>
 *   <li>a constructor: an object by field title, a sum type adding {@code "$variant"} (its constructor's title);</li>
 *   <li>Bool: true or false; Optional: null for None, else the value (both recognised by shape);</li>
 *   <li>a list: an array; a map: an array of [key, value] pairs (encoded in canonical key order);</li>
 *   <li>Data: hex CBOR.</li>
 * </ul>
 * No reflection: it runs in the Wasm runtime.
 */
public final class SchemaCodec {

    /** A value that does not fit its schema; the message names the path. */
    public static final class DataError extends IllegalArgumentException {
        public DataError(String message) {
            super(message);
        }
    }

    static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    private final JsonNode definitions;

    public SchemaCodec(JsonNode description) {
        this.definitions = description.path("definitions");
    }

    public JsonNode resolve(JsonNode s) {
        if (s == null) return JSON.createObjectNode();
        JsonNode ref = s.get("$ref");
        if (ref == null) return s;
        String r = ref.asText();
        if (!r.startsWith("#/definitions/")) throw new DataError("unsupported $ref " + r);
        String key = r.substring("#/definitions/".length()).replace("~1", "/").replace("~0", "~");
        JsonNode d = definitions.get(key);
        if (d == null) throw new DataError("undefined $ref " + r);
        return d;
    }

    /** A definition by key (a type's qualified name). */
    public JsonNode definition(String key) {
        JsonNode d = definitions.get(key);
        if (d == null) throw new DataError("undefined type " + key);
        return d;
    }

    static boolean isBool(JsonNode s) {
        JsonNode a = s.get("anyOf");
        return a != null && a.size() == 2 && title(a.get(0)).equals("False") && a.get(0).path("index").asInt() == 0
                && title(a.get(1)).equals("True") && a.get(1).path("index").asInt() == 1
                && a.get(0).path("fields").isEmpty() && a.get(1).path("fields").isEmpty();
    }

    static boolean isOptional(JsonNode s) {
        JsonNode a = s.get("anyOf");
        return a != null && a.size() == 2 && title(a.get(0)).equals("Some") && a.get(0).path("index").asInt() == 0
                && a.get(0).path("fields").size() == 1 && title(a.get(1)).equals("None") && a.get(1).path("index").asInt() == 1
                && a.get(1).path("fields").isEmpty();
    }

    private static String title(JsonNode n) {
        return n.path("title").asText("");
    }

    String kindOf(JsonNode schema) {
        JsonNode s = resolve(schema);
        if (s.has("dataType")) return s.get("dataType").asText();
        if (s.has("anyOf")) return isBool(s) ? "bool" : isOptional(s) ? "optional" : "sum";
        return "data";
    }

    // ------------------------------------------------------------------ JSON → Data

    /** {@code v} (JSON) as Plutus data of {@code schema}. */
    public PlutusData encode(JsonNode schema, JsonNode v) {
        return encode(schema, v, "$");
    }

    public PlutusData encode(JsonNode schema, JsonNode v, String path) {
        JsonNode s = resolve(schema);
        switch (kindOf(s)) {
            case "integer":
                return PlutusData.integer(integer(v, path));
            case "bytes":
                if (v == null || !v.isTextual() || !v.asText().matches("([0-9a-f]{2})*")) throw new DataError(path + ": not lowercase hex");
                return PlutusData.bytes(HEX.parseHex(v.asText()));
            case "bool":
                if (v == null || !v.isBoolean()) throw new DataError(path + ": not a boolean");
                return PlutusData.constr(v.asBoolean() ? 1 : 0);
            case "optional":
                return v == null || v.isNull() || v.isMissingNode() ? PlutusData.constr(1)
                        : PlutusData.constr(0, encode(s.get("anyOf").get(0).get("fields").get(0), v, path));
            case "list": {
                if (v == null || !v.isArray()) throw new DataError(path + ": not an array");
                var items = new ArrayList<PlutusData>();
                for (int i = 0; i < v.size(); i++) items.add(encode(s.get("items"), v.get(i), path + "[" + i + "]"));
                return new PlutusData.ListData(items);
            }
            case "map": {
                if (v == null || !v.isArray()) throw new DataError(path + ": a map is an array of [key, value] pairs");
                var entries = new ArrayList<PlutusData.Pair>();
                for (int i = 0; i < v.size(); i++) {
                    JsonNode e = v.get(i);
                    if (!e.isArray() || e.size() != 2) throw new DataError(path + "[" + i + "]: not a [key, value] pair");
                    entries.add(new PlutusData.Pair(encode(s.get("keys"), e.get(0), path + "[" + i + "][0]"),
                            encode(s.get("values"), e.get(1), path + "[" + i + "][1]")));
                }
                // Canonical order (RFC 8949 length-first, as CCL writes inline datums), no duplicates.
                entries.sort((a, b) -> compare(cbor(a.key()), cbor(b.key())));
                for (int i = 1; i < entries.size(); i++) {
                    if (compare(cbor(entries.get(i - 1).key()), cbor(entries.get(i).key())) == 0) throw new DataError(path + ": duplicate key");
                }
                return new PlutusData.MapData(entries);
            }
            case "sum": {
                JsonNode alts = s.get("anyOf");
                if (alts.size() == 1) return encodeFields(alts.get(0), v, path);
                String name = v != null && v.isObject() ? v.path("$variant").asText(null) : null;
                for (JsonNode c : alts) if (title(c).equals(name)) return encodeFields(c, v, path);
                var names = new ArrayList<String>();
                alts.forEach(c -> names.add(title(c)));
                throw new DataError(path + ": $variant must be one of " + String.join(", ", names));
            }
            case "constructor":
                return encodeFields(s, v, path);
            default:
                if (v == null || !v.isTextual()) throw new DataError(path + ": Data is hex CBOR");
                try {
                    return PlutusDataCborDecoder.decode(HEX.parseHex(v.asText()));
                } catch (Exception e) {
                    throw new DataError(path + ": not CBOR Plutus data");
                }
        }
    }

    private PlutusData encodeFields(JsonNode c, JsonNode v, String path) {
        if (v == null || !v.isObject()) throw new DataError(path + ": expected an object for " + title(c));
        var known = new ArrayList<String>(List.of("$variant"));
        c.path("fields").forEach(f -> known.add(title(f)));
        v.fieldNames().forEachRemaining(k -> {
            if (!known.contains(k)) throw new DataError(path + ": unknown field " + k + " of " + title(c));
        });
        var fields = new ArrayList<PlutusData>();
        for (JsonNode f : c.path("fields")) {
            if (!v.has(title(f))) throw new DataError(path + ": missing field " + title(f) + " of " + title(c));
            fields.add(encode(f, v.get(title(f)), path + "." + title(f)));
        }
        return new PlutusData.ConstrData(c.path("index").asInt(), fields);
    }

    private static BigInteger integer(JsonNode v, String path) {
        if (v != null && v.isIntegralNumber()) return v.bigIntegerValue();
        if (v != null && v.isTextual() && v.asText().matches("-?[0-9]+")) return new BigInteger(v.asText());
        throw new DataError(path + ": not an integer: " + v);
    }

    // ------------------------------------------------------------------ Data → JSON

    /** Plutus data of {@code schema} as JSON (integers as decimal strings); throws on any mismatch. */
    public JsonNode decode(JsonNode schema, PlutusData d) {
        return decode(schema, d, "$");
    }

    public JsonNode decode(JsonNode schema, PlutusData d, String path) {
        JsonNode s = resolve(schema);
        switch (kindOf(s)) {
            case "integer":
                if (!(d instanceof PlutusData.IntData i)) throw new DataError(path + ": not an integer");
                return JSON.getNodeFactory().textNode(i.value().toString());
            case "bytes":
                if (!(d instanceof PlutusData.BytesData b)) throw new DataError(path + ": not bytes");
                return JSON.getNodeFactory().textNode(HEX.formatHex(b.value()));
            case "bool": {
                var c = constr(d, path);
                if (!c.fields().isEmpty() || c.tag() > 1) throw new DataError(path + ": not a Bool");
                return JSON.getNodeFactory().booleanNode(c.tag() == 1);
            }
            case "optional": {
                var c = constr(d, path);
                if (c.tag() == 1 && c.fields().isEmpty()) return JSON.getNodeFactory().nullNode();
                if (c.tag() == 0 && c.fields().size() == 1) return decode(s.get("anyOf").get(0).get("fields").get(0), c.fields().get(0), path);
                throw new DataError(path + ": not an Optional");
            }
            case "list": {
                if (!(d instanceof PlutusData.ListData l)) throw new DataError(path + ": not a list");
                ArrayNode out = JSON.createArrayNode();
                for (int i = 0; i < l.items().size(); i++) out.add(decode(s.get("items"), l.items().get(i), path + "[" + i + "]"));
                return out;
            }
            case "map": {
                if (!(d instanceof PlutusData.MapData m)) throw new DataError(path + ": not a map");
                ArrayNode out = JSON.createArrayNode();
                for (int i = 0; i < m.entries().size(); i++) {
                    var e = m.entries().get(i);
                    out.addArray().add(decode(s.get("keys"), e.key(), path + "[" + i + "][0]"))
                            .add(decode(s.get("values"), e.value(), path + "[" + i + "][1]"));
                }
                return out;
            }
            case "sum": {
                var c = constr(d, path);
                JsonNode alts = s.get("anyOf");
                for (JsonNode a : alts) {
                    if (a.path("index").asInt() == c.tag()) return decodeFields(a, c, path, alts.size() > 1);
                }
                throw new DataError(path + ": constructor " + c.tag() + " is not a " + title(s));
            }
            case "constructor": {
                var c = constr(d, path);
                if (c.tag() != s.path("index").asInt()) throw new DataError(path + ": constructor " + c.tag() + ", expected " + s.path("index").asInt());
                return decodeFields(s, c, path, false);
            }
            default:
                return JSON.getNodeFactory().textNode(HEX.formatHex(cbor(d)));
        }
    }

    private JsonNode decodeFields(JsonNode alt, PlutusData.ConstrData c, String path, boolean variant) {
        JsonNode fs = alt.path("fields");
        if (c.fields().size() != fs.size()) throw new DataError(path + ": " + title(alt) + " has " + fs.size() + " fields, got " + c.fields().size());
        ObjectNode out = JSON.createObjectNode();
        if (variant) out.put("$variant", title(alt));
        for (int i = 0; i < fs.size(); i++) out.set(title(fs.get(i)), decode(fs.get(i), c.fields().get(i), path + "." + title(fs.get(i))));
        return out;
    }

    static PlutusData.ConstrData constr(PlutusData d, String path) {
        if (!(d instanceof PlutusData.ConstrData c)) throw new DataError(path + ": not a constructor");
        return c;
    }

    static byte[] cbor(PlutusData d) {
        return PlutusDataCborEncoder.encode(d);
    }

    static int compare(byte[] a, byte[] b) {
        if (a.length != b.length) return a.length - b.length;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return (a[i] & 0xff) - (b[i] & 0xff);
        }
        return 0;
    }
}
