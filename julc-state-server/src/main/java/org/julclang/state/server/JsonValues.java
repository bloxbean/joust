package org.julclang.state.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;

/**
 * JSON values to Java values of a contract's types and back, by the description's JSON conventions (ADR-0013 D3,
 * {@code x-joust.encoding.json}): integers as decimal strings (or safe integers), bytes as hex, records as objects by
 * component name, sealed variants with {@code "$variant"}, Optional as null or the value, lists as arrays, maps as
 * arrays of [key, value] pairs (kept in the order given), Plutus data as hex CBOR. A one-component record (a ledger
 * newtype such as PolicyId) also accepts its component's JSON directly.
 * <p>
 * The codec itself is {@link org.julclang.state.serve.JsonValues} (ADR-0022 D16: shared with the dev host); this class
 * keeps the prepare service's API, with its own {@link BadValue}.
 */
public final class JsonValues {

    static final ObjectMapper JSON = new ObjectMapper();

    private JsonValues() {
    }

    public static final class BadValue extends RuntimeException {
        BadValue(String message) {
            super(message);
        }
    }

    /** {@code json} as a value of {@code type}. */
    public static Object fromJson(JsonNode json, Type type, String path) {
        try {
            return org.julclang.state.serve.JsonValues.BAD_VALUES.decode(json, type, path);
        } catch (org.julclang.state.serve.JsonValues.BadValue e) {
            throw new BadValue(e.getMessage());
        }
    }

    /** {@code v} as JSON by the same conventions (views and states). */
    public static JsonNode toJson(Object v) {
        try {
            return org.julclang.state.serve.JsonValues.BAD_VALUES.encode(v);
        } catch (org.julclang.state.serve.JsonValues.BadValue e) {
            throw new BadValue(e.getMessage());
        }
    }
}
