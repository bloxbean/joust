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
    void anAbsentOptionalIsMissingNotNone() throws Exception {
        String opt = "{\"anyOf\":[{\"title\":\"Some\",\"index\":0,\"fields\":[" + INT + "]},{\"title\":\"None\",\"index\":1,\"fields\":[]}]}";
        assertFalse(engine(STRICT, opt, null));                       // omitted: refused, as JsonValues refuses it
        assertTrue(engine(STRICT, opt, JSON.nullNode()));             // explicit null: None
        assertFalse(jvm(null, BigInteger.class));
        assertTrue(engine(LENIENT, opt, null));                       // the lenient codec, unchanged
    }

    record Id(byte[] v) {}
    @SuppressWarnings("unused") private static org.julclang.core.types.JulcMap<byte[], BigInteger> map;

    /** #154 review G3-3: records and maps read alike (a record as an object; a map in canonical order, no repeats). */
    @Test
    void recordsAndMapsAgree() throws Exception {
        String id = "{\"title\":\"Id\",\"anyOf\":[{\"title\":\"Id\",\"dataType\":\"constructor\",\"index\":0,\"fields\":[{\"title\":\"v\",\"dataType\":\"bytes\"}]}]}";
        String bytesToInt = "{\"dataType\":\"map\",\"keys\":" + BYTES + ",\"values\":" + INT + "}";
        Type mapType = WireParityTest.class.getDeclaredField("map").getGenericType();
        for (String v : new String[]{"{\"v\":\"abcd\"}", "\"abcd\""})
            assertEquals(engine(STRICT, id, json(v)), jvm(json(v), Id.class), v);
        assertFalse(jvm(json("\"abcd\""), Id.class));
        assertTrue(jvm(json("\"" + "ab".repeat(28) + "\""), org.julclang.ledger.PolicyId.class));   // a newtype is its bytes
        for (String v : new String[]{"[[\"01\",1],[\"02\",2]]", "[[\"02\",1],[\"01\",2]]", "[[\"01\",1],[\"01\",2]]", "[[\"0101\",1],[\"02\",2]]"})
            assertEquals(engine(STRICT, bytesToInt, json(v)), jvm(json(v), mapType), v);
        assertFalse(engine(STRICT, bytesToInt, json("[[\"02\",1],[\"01\",2]]")));
        assertTrue(engine(LENIENT, bytesToInt, json("[[\"02\",1],[\"01\",2]]")));   // pages may send any order
    }

    /** #154 review G3-1: pay.assets is checked once, before any read, for both hosts. */
    @Test
    void payAssetsAreBounded() throws Exception {
        String policy = "\"" + "ab".repeat(28) + "\"";
        Session.checkAssets(json("[[" + policy + ",[[\"\",\"1\"],[\"4d31\",2]]]]"));
        for (String bad : new String[]{"[1]", "[[" + policy + ",[[\"\",\"" + "9".repeat(201) + "\"]]]]",
                "[[\"" + "AB".repeat(28) + "\",[]]]", "[[" + policy + ",[[\"4d31\",1],[\"\",1]]]]", "[[" + policy + ",{}]]",
                "[[" + policy + ",[[\"" + "00".repeat(33) + "\",1]]]]", "[[" + policy + ",[]],[" + policy + ",[]]]"}) {
            var e = assertThrows(Throwable.class, () -> Session.checkAssets(json(bad)), bad);
            assertTrue(e instanceof HostError h && h.code.equals("request") || e instanceof JsonValues.BadValue, bad);
        }
    }

    /** #154 review G3-4: Plutus data nests at most 256 levels on both hosts (a trap ends the npm session). */
    @Test
    void dataNestingAgrees() throws Exception {
        String data = "{}";   // no dataType: Plutus data as hex CBOR
        for (int depth : new int[]{256, 257, 16_383}) {
            JsonNode v = JSON.getNodeFactory().textNode("81".repeat(depth) + "00");
            assertEquals(depth <= 256, jvm(v, org.julclang.core.PlutusData.class), "jvm " + depth);
            assertEquals(depth <= 256, engine(STRICT, data, v), "engine " + depth);
        }
        assertTrue(engine(STRICT, data, JSON.getNodeFactory().textNode("9f" + "80".repeat(300) + "ff")));   // wide, not deep
        assertTrue(engine(LENIENT, data, JSON.getNodeFactory().textNode("81".repeat(257) + "00")));
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
