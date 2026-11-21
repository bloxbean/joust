package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.julclang.state.host.Client.*;
import static org.julclang.state.host.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

/** #160 review Q2: a map history rebuild does not depend on the order of transactions within a block. */
class MapHistoryOrderTest {
    @Test void transactionsOfOneBlockInAnyOrderRebuildTheSameTrie() throws Exception {
        var v = MapRecording.record("token", "004-transfer");
        var original = RecordedSessionsTest.answers(v);
        var hashes = new ArrayList<String>();
        for (boolean reversed : List.of(false, true)) {
            var reply = HostVectorsTest.run(v, q -> {
                ObjectNode a = original.apply(q).deepCopy();
                if (str(q, "kind").equals("assetHistory")) {
                    // The whole history in one block, the creation first and the later transactions swapped.
                    ArrayNode txs = a.withArray("value");
                    var point = txs.get(txs.size() - 1).get("point").deepCopy();
                    var list = new ArrayList<JsonNode>();
                    for (var t : txs) { ((ObjectNode) t).set("point", point.deepCopy()); list.add(t); }
                    var tail = new ArrayList<>(list.subList(1, list.size()));
                    if (reversed) Collections.reverse(tail);
                    txs.removeAll();
                    txs.add(list.get(0));
                    tail.forEach(txs::add);
                }
                return a;
            });
            hashes.add(success(reply).at("/prepared/txHash").asText());
        }
        assertEquals(v.at("/expected/txHash").asText(), hashes.get(0));
        assertEquals(hashes.get(0), hashes.get(1), "the same body whatever the order within the block");
    }
}
