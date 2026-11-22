package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.core.types.JulcList;
import org.julclang.state.runtime.described.SchemaCodec;
import org.julclang.state.serve.JsonValues;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Both hosts read a call's arguments with the same bounds (#154 Codex review): the JVM host through
 * {@code JsonValues.TYPED} (typed by the client's Java types), the npm host's engine through {@link SchemaCodec}'s
 * strict mode (typed by the description). Each value is accepted by both or refused by both.
 */
class WireParityTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final SchemaCodec STRICT = new SchemaCodec(JSON.createObjectNode(), true);
    static final SchemaCodec LENIENT = new SchemaCodec(JSON.createObjectNode());

    static JsonNode json(String s) throws Exception { return JSON.readTree(s); }

    static boolean jvm(JsonNode v, Type type) {
        try { JsonValues.fromJson(v, type, "$"); return true; } catch (JsonValues.BadValue e) { return false; }
    }

    static boolean engine(SchemaCodec codec, String schema, JsonNode v) throws Exception {
        try { codec.encode(json(schema), v); return true; } catch (SchemaCodec.DataError e) { return false; }
    }

    static final String INT = "{\"dataType\":\"integer\"}", BYTES = "{\"dataType\":\"bytes\"}",
            INTS = "{\"dataType\":\"list\",\"items\":" + INT + "}";

    static Type listOfBigInteger() throws Exception {
        return WireParityTest.class.getDeclaredField("sample").getGenericType();
    }
    @SuppressWarnings("unused") private static JulcList<BigInteger> sample;

    @Test
    void integersAgree() throws Exception {
        String digits200 = "9".repeat(200), digits201 = "9".repeat(201);
        for (String v : new String[]{"9007199254740991", "-9007199254740991", "\"9007199254740992\"", "9007199254740992",
                "-9007199254740992", "\"" + digits200 + "\"", "\"" + digits201 + "\"", "1.5", "\"0x10\""}) {
            JsonNode n = json(v);
            assertEquals(jvm(n, BigInteger.class), engine(STRICT, INT, n), v);
        }
        // The unsafe literal is refused, its quoted form accepted; the lenient codec (pages, the HTTP service) unchanged.
        assertFalse(engine(STRICT, INT, json("9007199254740992")));
        assertTrue(engine(STRICT, INT, json("\"9007199254740992\"")));
        assertTrue(engine(LENIENT, INT, json("9007199254740992")));
    }

    @Test
    void collectionsAndBytesAgree() throws Exception {
        var ok = JSON.createArrayNode();
        for (int i = 0; i < 1024; i++) ok.add(i);
        var over = ok.deepCopy().add(1024);
        Type list = listOfBigInteger();
        assertTrue(jvm(ok, list));
        assertTrue(engine(STRICT, INTS, ok));
        assertFalse(jvm(over, list));
        assertFalse(engine(STRICT, INTS, over));
        assertTrue(engine(LENIENT, INTS, over));

        var maxHex = JSON.getNodeFactory().textNode("ab".repeat(16384));
        var overHex = JSON.getNodeFactory().textNode("ab".repeat(16385));
        assertEquals(jvm(maxHex, byte[].class), engine(STRICT, BYTES, maxHex));
        assertEquals(jvm(overHex, byte[].class), engine(STRICT, BYTES, overHex));
        assertFalse(engine(STRICT, BYTES, overHex));
    }

    @Test
    void nestingAgrees() throws Exception {
        // 33 nested lists around an integer: one level more than either host reads.
        String schema = INT, value = "1";
        for (int i = 0; i < 33; i++) { schema = "{\"dataType\":\"list\",\"items\":" + schema + "}"; value = "[" + value + "]"; }
        assertFalse(engine(STRICT, schema, json(value)));
        assertTrue(engine(LENIENT, schema, json(value)));
    }
}
