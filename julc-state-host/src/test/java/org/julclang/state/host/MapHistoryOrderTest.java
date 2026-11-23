package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.julclang.state.host.Client.*;
import static org.julclang.state.host.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

/** #160 review Q2: a map history rebuild does not depend on the order of the history's transactions. */
class MapHistoryOrderTest {
    @Test void historyTransactionsInAnyOrderRebuildTheSameTrie() throws Exception {
        var v = MapRecording.record("token", MapRecording.m3("token", "transfer"));
        var original = RecordedSessionsTest.answers(v);
        var hashes = new ArrayList<String>();
        for (boolean reversed : List.of(false, true)) {
            var reply = HostVectorsTest.run(v, q -> {
                ObjectNode a = original.apply(q).deepCopy();
                if (str(q, "kind").equals("assetHistory")) {
                    // The creation first and the later transactions swapped.
                    ArrayNode txs = a.withArray("value");
                    var list = new ArrayList<JsonNode>();
                    for (var t : txs) list.add(t);
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
        assertEquals(hashes.get(0), hashes.get(1), "the same body whatever the order");
    }
}
