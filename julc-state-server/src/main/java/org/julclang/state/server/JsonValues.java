package org.julclang.state.server;

import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JSON values to Java values of a contract's types and back, by the description's JSON conventions (ADR-0013 D3,
 * {@code x-joust.encoding.json}): integers as decimal strings (or safe integers), bytes as hex, records as objects by
 * component name, sealed variants with {@code "$variant"}, Optional as null or the value, lists as arrays, maps as
 * arrays of [key, value] pairs (kept in the order given), Plutus data as hex CBOR. A one-component record (a ledger
 * newtype such as PolicyId) also accepts its component's JSON directly.
 */
public final class JsonValues {

    static final ObjectMapper JSON = new ObjectMapper();
    /** Bounds a request can ask for: nesting depth and list sizes. */
    static final int MAX_DEPTH = 32;
    static final int MAX_ITEMS = 1024;

    private JsonValues() {
    }

    public static final class BadValue extends RuntimeException {
        BadValue(String message) {
            super(message);
        }
    }

    /** {@code json} as a value of {@code type}. */
    public static Object fromJson(JsonNode json, Type type, String path) {
        return fromJson(json, type, path, 0);
    }

    private static Object fromJson(JsonNode json, Type type, String path, int depth) {
        if (depth > MAX_DEPTH) throw new BadValue(path + ": nested too deeply");
        if (json == null || json.isMissingNode()) throw new BadValue(path + ": missing");
        Class<?> raw = rawOf(type);
        if (raw == BigInteger.class) return integer(json, path);
        if (raw == long.class || raw == Long.class) return integer(json, path).longValueExact();
        if (raw == int.class || raw == Integer.class) return integer(json, path).intValueExact();
        if (raw == boolean.class || raw == Boolean.class) {
            if (!json.isBoolean()) throw new BadValue(path + ": not a boolean");
            return json.booleanValue();
        }
        if (raw == byte[].class) return hex(json, path);
        if (raw == String.class) return new String(hex(json, path), StandardCharsets.UTF_8);
        if (PlutusData.class.isAssignableFrom(raw)) {
            try {
                return PlutusDataCborDecoder.decode(hex(json, path));
            } catch (RuntimeException | StackOverflowError e) {   // the decoder recurses: deep nesting overflows
                throw new BadValue(path + ": not Plutus data CBOR");
            }
        }
        if (raw == Optional.class) return json.isNull() ? Optional.empty() : Optional.of(fromJson(json, typeArg(type, 0, path), path, depth + 1));
        if (raw == JulcList.class) {
            if (!json.isArray()) throw new BadValue(path + ": not an array");
            if (json.size() > MAX_ITEMS) throw new BadValue(path + ": too many items");
            Object[] items = new Object[json.size()];
            for (int i = 0; i < json.size(); i++) items[i] = fromJson(json.get(i), typeArg(type, 0, path), path + "[" + i + "]", depth + 1);
            return JulcList.of(items);
        }
        if (raw == JulcMap.class) {
            if (!json.isArray()) throw new BadValue(path + ": a map is an array of [key, value] pairs");
            if (json.size() > MAX_ITEMS) throw new BadValue(path + ": too many entries");
            JulcMap<Object, Object> m = JulcMap.empty();
            // insert prepends: insert from the last pair, so the map keeps the order given.
            for (int i = json.size() - 1; i >= 0; i--) {
                JsonNode e = json.get(i);
                if (!e.isArray() || e.size() != 2) throw new BadValue(path + "[" + i + "]: not a [key, value] pair");
                m = m.insert(fromJson(e.get(0), typeArg(type, 0, path), path + "[" + i + "][0]", depth + 1),
                        fromJson(e.get(1), typeArg(type, 1, path), path + "[" + i + "][1]", depth + 1));
            }
            return m;
        }
        if (raw.isSealed() && raw.isInterface()) {
            if (!json.isObject() || !json.has("$variant")) throw new BadValue(path + ": a " + raw.getSimpleName() + " needs \"$variant\"");
            String name = json.get("$variant").asText();
            for (Class<?> sub : raw.getPermittedSubclasses()) {
                if (sub.getSimpleName().equals(name)) return record(json, sub, path, depth, true);
            }
            throw new BadValue(path + ": no variant " + name + " of " + raw.getSimpleName());
        }
        if (raw.isRecord()) {
            RecordComponent[] cs = raw.getRecordComponents();
            if (cs.length == 1 && !json.isObject()) return construct(raw, fromJson(json, cs[0].getGenericType(), path, depth + 1));
            return record(json, raw, path, depth, false);
        }
        throw new BadValue(path + ": arguments of type " + raw.getSimpleName() + " are not supported");
    }

    private static Object record(JsonNode json, Class<?> raw, String path, int depth, boolean variant) {
        if (!json.isObject()) throw new BadValue(path + ": expected an object for " + raw.getSimpleName());
        RecordComponent[] cs = raw.getRecordComponents();
        var names = new ArrayList<String>();
        for (RecordComponent c : cs) names.add(c.getName());
        json.fieldNames().forEachRemaining(n -> {
            if (!names.contains(n) && !(variant && n.equals("$variant"))) throw new BadValue(path + ": unknown field " + n + " of " + raw.getSimpleName());
        });
        Object[] args = new Object[cs.length];
        for (int i = 0; i < cs.length; i++) args[i] = fromJson(json.get(cs[i].getName()), cs[i].getGenericType(), path + "." + cs[i].getName(), depth + 1);
        return construct(raw, args);
    }

    private static Object construct(Class<?> raw, Object... args) {
        try {
            Class<?>[] types = new Class<?>[raw.getRecordComponents().length];
            for (int i = 0; i < types.length; i++) types[i] = raw.getRecordComponents()[i].getType();
            Constructor<?> k = raw.getDeclaredConstructor(types);
            k.setAccessible(true);
            return k.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new BadValue("cannot build a " + raw.getSimpleName() + ": " + e.getMessage());
        }
    }

    private static BigInteger integer(JsonNode json, String path) {
        if (json.isTextual() && json.asText().matches("-?[0-9]{1,200}")) return new BigInteger(json.asText());
        if (json.isIntegralNumber() && json.canConvertToLong() && Math.abs(json.longValue()) <= (1L << 53)) return BigInteger.valueOf(json.longValue());
        throw new BadValue(path + ": not an integer");
    }

    private static byte[] hex(JsonNode json, String path) {
        if (!json.isTextual() || !json.asText().matches("([0-9a-f]{2}){0,16384}")) throw new BadValue(path + ": not lowercase hex");
        return HexUtil.decodeHexString(json.asText());
    }

    private static Class<?> rawOf(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        throw new BadValue("unsupported type " + t);
    }

    private static Type typeArg(Type t, int i, String path) {
        if (t instanceof ParameterizedType p && p.getActualTypeArguments().length > i) return p.getActualTypeArguments()[i];
        throw new BadValue(path + ": no element type");
    }

    // ------------------------------------------------------------------ Java to JSON

    /** {@code v} as JSON by the same conventions (views and states). */
    public static JsonNode toJson(Object v) {
        if (v == null) return JSON.nullNode();
        if (v instanceof BigInteger b) return JSON.getNodeFactory().textNode(b.toString());
        if (v instanceof Long || v instanceof Integer) return JSON.getNodeFactory().textNode(v.toString());
        if (v instanceof Boolean b) return JSON.getNodeFactory().booleanNode(b);
        if (v instanceof byte[] b) return JSON.getNodeFactory().textNode(HexUtil.encodeHexString(b));
        if (v instanceof String s) return JSON.getNodeFactory().textNode(HexUtil.encodeHexString(s.getBytes(StandardCharsets.UTF_8)));
        if (v instanceof PlutusData d) return JSON.getNodeFactory().textNode(HexUtil.encodeHexString(PlutusDataCborEncoder.encode(d)));
        if (v instanceof Optional<?> o) return o.isPresent() ? toJson(o.get()) : JSON.nullNode();
        if (v instanceof JulcList<?> l) {
            ArrayNode a = JSON.createArrayNode();
            for (Object x : l) a.add(toJson(x));
            return a;
        }
        if (v instanceof JulcMap<?, ?> m) {
            ArrayNode a = JSON.createArrayNode();
            @SuppressWarnings("unchecked")
            JulcMap<Object, Object> mm = (JulcMap<Object, Object>) m;
            JulcList<Object> keys = mm.keys();
            JulcList<Object> values = mm.values();
            for (long i = 0; i < keys.size(); i++) a.add(JSON.createArrayNode().add(toJson(keys.get(i))).add(toJson(values.get(i))));
            return a;
        }
        if (v instanceof List<?> l) {
            ArrayNode a = JSON.createArrayNode();
            for (Object x : l) a.add(toJson(x));
            return a;
        }
        if (v.getClass().isRecord()) {
            RecordComponent[] cs = v.getClass().getRecordComponents();
            boolean variant = false;
            for (Class<?> i : v.getClass().getInterfaces()) variant |= i.isSealed();
            if (cs.length == 1 && !variant && isNewType(v.getClass())) return toJson(get(cs[0], v));
            ObjectNode o = JSON.createObjectNode();
            if (variant) o.put("$variant", v.getClass().getSimpleName());
            for (RecordComponent c : cs) o.set(c.getName(), toJson(get(c, v)));
            return o;
        }
        throw new BadValue("cannot show a " + v.getClass().getSimpleName());
    }

    /** Ledger newtypes are shown as their component (as the description encodes them). */
    private static boolean isNewType(Class<?> c) {
        return c.getName().startsWith("org.julclang.ledger.") || c.isAnnotationPresent(org.julclang.stdlib.annotation.NewType.class);
    }

    private static Object get(RecordComponent c, Object v) {
        try {
            var m = c.getAccessor();
            m.setAccessible(true);
            return m.invoke(v);
        } catch (ReflectiveOperationException e) {
            throw new BadValue("cannot read " + c.getName());
        }
    }
}
