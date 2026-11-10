package org.julclang.state.examples.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HexFormat;
import java.util.TreeMap;

import static org.julclang.state.examples.registry.RegistryWorld.PARTIES;
import static org.julclang.state.examples.registry.RegistryWorld.ada;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-0016 D2 (M3 exit criterion): the shared ordering and keyId vectors ({@code fixtures/keyed/order-vectors.json},
 * also checked by the JS client's keyed.test.js) agree with the Java runtime (its key ids, and the order of its
 * listing, a TreeMap of hex keys) and on chain: the registry validator accepts an insert after a key exactly when the
 * vector says that key is smaller (lessThanByteString), on the harness's ledger model.
 */
class KeyedVectorsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HexFormat HEX = HexFormat.of();

    static JsonNode vectors() throws Exception {
        return JSON.readTree(Path.of("../../fixtures/keyed/order-vectors.json").toFile());
    }

    @Test
    void keyIdsAgreeWithTheJavaRuntime() throws Exception {
        for (JsonNode k : vectors().get("keyIds")) {
            var t = k.get("type").asText().equals("Entry") ? RegistryClient.ENTRY_TYPE : RegistryClient.BALLOT_TYPE;
            byte[] id = t.keyId(HEX.parseHex(k.get("instance").asText()), HEX.parseHex(k.get("key").asText()));
            assertEquals(k.get("keyId").asText(), HEX.formatHex(id), k.toString());
        }
    }

    @Test
    void theOrderAgreesWithTheJavaRuntime() throws Exception {
        for (JsonNode o : vectors().get("order")) {
            String a = o.get(0).asText(), b = o.get(1).asText();
            int want = o.get(2).asInt();
            // The runtime's listing is a TreeMap of lowercase hex keys (KeyedList.listing / predecessor).
            var listing = new TreeMap<String, Boolean>();
            listing.put(a, true);
            listing.put(b, true);
            int got = a.equals(b) ? 0 : listing.firstKey().equals(a) ? -1 : 1;
            assertEquals(want, got, a + " vs " + b + " (the runtime's listing)");
            assertEquals(want, Integer.signum(RegistryWorld.KEY_ORDER.compare(a, b)), a + " vs " + b + " (unsigned bytes)");
        }
    }

    @Test
    void theOrderAgreesOnChain() throws Exception {
        byte[] alice = PARTIES.get(0);
        int checked = 0;
        for (JsonNode o : vectors().get("order")) {
            String lo = o.get(0).asText(), hi = o.get(1).asText();
            if (o.get(2).asInt() != -1) continue;
            if (lo.isEmpty()) {
                // The head's key "" is below every key: the first insert anchors at the head.
                var w = new RegistryWorld();
                byte[] inst = w.createInstance();
                w.accept(w.claim(inst, alice, HEX.parseHex(hi), ada(2)));
                checked++;
                continue;
            }
            // lo < hi: an insert of hi after lo is accepted ...
            var w = new RegistryWorld();
            byte[] inst = w.createInstance();
            w.accept(w.claim(inst, alice, HEX.parseHex(lo), ada(2)));
            w.anchoredAt(inst, RegistryWorld.Kind.ENTRY, HEX.parseHex(lo));
            w.accept(w.claim(inst, alice, HEX.parseHex(hi), ada(2)));
            // ... and an insert of lo after hi is refused by the policy (key(A) < key fails).
            var v = new RegistryWorld();
            byte[] inst2 = v.createInstance();
            v.accept(v.claim(inst2, alice, HEX.parseHex(hi), ada(2)));
            v.anchoredAt(inst2, RegistryWorld.Kind.ENTRY, HEX.parseHex(hi));
            var b = v.claim(inst2, alice, HEX.parseHex(lo), ada(2));
            v.rejectBy(b, b.tx, lo + " after " + hi, v.mintLabel());
            checked++;
        }
        System.out.println("[vectors] " + checked + " ordered pairs agree on chain (the registry validator)");
        assertEquals(10, checked);
    }

    /** R-G6: the other comparison of D4a.2, {@code key < A.next}, on the same pairs (the head as the anchor). */
    @Test
    void theNextComparisonAgreesOnChain() throws Exception {
        byte[] alice = PARTIES.get(0);
        int checked = 0;
        for (JsonNode o : vectors().get("order")) {
            String lo = o.get(0).asText(), hi = o.get(1).asText();
            if (o.get(2).asInt() != -1 || lo.isEmpty()) continue;
            // head -> hi: lo goes after the head (lo < head.next = hi), accepted.
            var w = new RegistryWorld();
            byte[] inst = w.createInstance();
            w.accept(w.claim(inst, alice, HEX.parseHex(hi), ada(2)));
            w.anchoredAt(inst, RegistryWorld.Kind.ENTRY, new byte[0]);
            w.accept(w.claim(inst, alice, HEX.parseHex(lo), ada(2)));
            // head -> lo: hi cannot go after the head (hi < head.next = lo fails), refused by the policy.
            var v = new RegistryWorld();
            byte[] inst2 = v.createInstance();
            v.accept(v.claim(inst2, alice, HEX.parseHex(lo), ada(2)));
            v.anchoredAt(inst2, RegistryWorld.Kind.ENTRY, new byte[0]);
            var b = v.claim(inst2, alice, HEX.parseHex(hi), ada(2));
            v.rejectBy(b, b.tx, hi + " after the head, before " + lo, v.mintLabel());
            checked++;
        }
        assertEquals(9, checked);
    }
}
