package org.julclang.state.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HexFormat;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0016 D2, M3 review R-G6: the shared vectors ({@code fixtures/keyed/order-vectors.json}) through the runtime's own
 * code: {@link KeyedType#keyId} and the anchor choice of {@link KeyedList#predecessor} over its listing's TreeMap.
 */
class KeyedOrderVectorsTest {

    static final HexFormat HEX = HexFormat.of();

    @Test
    void theRuntimesKeyIdsAndPredecessorsAgreeWithTheVectors() throws Exception {
        JsonNode v = new ObjectMapper().readTree(Path.of("../fixtures/keyed/order-vectors.json").toFile());
        for (JsonNode k : v.get("keyIds")) {
            var t = new KeyedType<>(k.get("type").asText(), 0, 0, 64, false, 0, 120_000, Object.class, null, null);
            assertEquals(k.get("keyId").asText(), HEX.formatHex(t.keyId(HEX.parseHex(k.get("instance").asText()),
                    HEX.parseHex(k.get("key").asText()))), k.toString());
        }
        int checked = 0;
        for (JsonNode o : v.get("order")) {
            String a = o.get(0).asText(), b = o.get(1).asText();
            if (o.get(2).asInt() != -1 || a.isEmpty()) continue;
            // A listing with the head, a and b: an insert of b goes after a, an insert of a after the head.
            var all = new TreeMap<String, KeyedList.Listed<Object>>();
            var head = new KeyedList.Listed<Object>(null, null);
            var la = new KeyedList.Listed<Object>(null, null);
            var lb = new KeyedList.Listed<Object>(null, null);
            all.put("", head);
            all.put(a, la);
            all.put(b, lb);
            all.remove(b);
            assertSame(la, KeyedList.predecessor(all, b), b + " goes after " + a);
            all.put(b, lb);
            all.remove(a);
            assertSame(head, KeyedList.predecessor(all, a), a + " goes after the head, before " + b);
            checked++;
        }
        assertEquals(9, checked);
    }
}
