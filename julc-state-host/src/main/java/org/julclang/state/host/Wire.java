package org.julclang.state.host;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** Protocol v1 values. Request integers never undergo Jackson's truncating/coercing conversions (D4/D7). */
final class Wire {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    static ObjectNode obj() { return JSON.createObjectNode(); }

    static ObjectNode fields(JsonNode n, String... names) {
        if (!(n instanceof ObjectNode o)) throw bad("expected an object");
        var allowed = Set.of(names);
        o.fieldNames().forEachRemaining(k -> { if (!allowed.contains(k)) throw bad("unknown field " + k); });
        return o;
    }
    static long number(JsonNode n, String key, long min) {
        var v = n.path(key);
        if (!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < min) throw bad("invalid " + key);
        return v.longValue();
    }
    static boolean bool(JsonNode n, String key) {
        if (!n.path(key).isBoolean()) throw bad("invalid " + key);
        return n.get(key).booleanValue();
    }
    static String str(JsonNode n, String key) {
        if (!n.path(key).isTextual() || n.get(key).textValue().isEmpty()) throw bad("invalid " + key);
        return n.get(key).textValue();
    }
    static HostError bad(String message) { return HostError.bad("request", message); }
}
