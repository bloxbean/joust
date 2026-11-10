package org.julclang.state.runtime;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0016 M3 review (F94, R-F11): a relink is written canonically, whatever encoding the node's writer chose; the Data
 * is unchanged apart from next and linkedAt.
 */
class KeyedRelinkTest {

    @Test
    void aRelinkOfADefiniteListNodeIsCanonical() throws Exception {
        // A value record whose list another writer encoded with a definite length.
        var definite = new ListPlutusData(new java.util.ArrayList<>(List.of(BytesPlutusData.of(new byte[]{1}))), false);
        var value = KeyedType.constr(0, List.of(BytesPlutusData.of(new byte[]{0x61}), definite));
        var node = (ConstrPlutusData) PlutusData.deserialize(KeyedType.constr(4, List.of(BytesPlutusData.of(new byte[32]),
                BytesPlutusData.of(new byte[0]), BigIntPlutusData.of(0), value)).serializeToBytes());
        assertTrue(HexUtil.encodeHexString(node.serializeToBytes()).contains("81"), "the input keeps its definite list");
        var relinked = KeyedType.relinked(node, new byte[]{0x62}, BigInteger.valueOf(1_800_000_000_000L));
        String hex = HexUtil.encodeHexString(relinked.serializeToBytes());
        // Canonical: the list indefinite (9f ... ff), as for freshly built data.
        var fresh = KeyedType.constr(4, List.of(BytesPlutusData.of(new byte[32]), BytesPlutusData.of(new byte[]{0x62}),
                BigIntPlutusData.of(1_800_000_000_000L), KeyedType.constr(0, List.of(BytesPlutusData.of(new byte[]{0x61}),
                        ListPlutusData.of(BytesPlutusData.of(new byte[]{1}))))));
        assertEquals(HexUtil.encodeHexString(fresh.serializeToBytes()), hex);
    }

    /** R-F17: a node whose datum repeats a map key is refused before a relink could change its Data. */
    @Test
    void aNodeWithARepeatedMapKeyIsNotRelinked() {
        // Keyed_T(instance, next, linkedAt, Value{ map { 1: 2, 1: 3 } }) as another writer may encode it.
        String repeated = "d87a9f5820" + "00".repeat(32) + "4000d8799fa2010201" + "03ffff";
        var e = assertThrows(org.julclang.state.api.RequireException.class, () -> KeyedType.requireRelinkable(repeated, "insert"));
        assertTrue(e.getMessage().contains("repeated key"), e.getMessage());
        assertDoesNotThrow(() -> KeyedType.requireRelinkable("d87a9f5820" + "00".repeat(32) + "4000d8799fa2010203" + "03ffff", "insert"));
    }

    /** F99: unique map keys out of canonical order would be reordered by the relink: refused; canonical order relinks. */
    @Test
    void aNodeWithAMapOutOfCanonicalOrderIsNotRelinked() {
        String pre = "d87a9f5820" + "00".repeat(32) + "4000d8799f";
        // Refused: {3: 4, 1: 2} (reversed); {h'0101': 1, h'02': 2} (canonical order is shorter key first); nested in a list.
        for (String m : java.util.List.of("a203040102", "a2420101014102" + "02", "9fa203040102ff")) {
            var e = assertThrows(org.julclang.state.api.RequireException.class, () -> KeyedType.requireRelinkable(pre + m + "ffff", "remove"), m);
            assertTrue(e.getMessage().contains("not in canonical order"), e.getMessage());
        }
        // Accepted: sorted unique keys, length first; an empty map; a definite list around a sorted map.
        for (String m : java.util.List.of("a201020304", "a2410202420101" + "01", "a0", "81a201020304")) {
            assertDoesNotThrow(() -> KeyedType.requireRelinkable(pre + m + "ffff", "remove"), m);
        }
    }
}
