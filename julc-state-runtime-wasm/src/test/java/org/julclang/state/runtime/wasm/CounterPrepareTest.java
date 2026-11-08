package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.julclang.state.examples.devkit.Fixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate S0 on the JVM: every Counter call of the dApp's recorded DevKit run, prepared by {@link CounterPrepare} from its
 * snapshot, gives the recorded unsigned transaction byte for byte. Writes {@code build/s0-vectors.json}, which
 * {@code src/test/js/s0.mjs} runs through the Wasm image.
 */
class CounterPrepareTest {
    static final Path FIXTURES = Path.of("../fixtures/m0");
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void counterCallsFromSnapshotsAreByteIdentical() throws Exception {
        JsonNode clock = JSON.readTree(FIXTURES.resolve("devkit.json").toFile());
        ArrayNode vectors = JSON.createArrayNode();
        List<String> diffs = new ArrayList<>();
        List<Long> ms = new ArrayList<>();
        try (var files = Files.list(FIXTURES.resolve("dapp"))) {
            for (Path f : files.sorted().toList()) {
                JsonNode fixture = Fixtures.read(f);
                JsonNode call = fixture.get("call");
                if (call == null || !call.path("contract").asText().endsWith(".Counter")) continue;
                var req = new CounterPrepare.Request(Fixtures.snapshot(fixture).toJson(),
                        clock.get("zeroSlot").asLong(), clock.get("startTimeMs").asLong(), clock.get("slotLengthMs").asLong(),
                        20_000, call.get("method").asText(), call.path("instance").asText(null),
                        call.path("args").path("by").asText(null), call.get("address").asText());
                long t = System.nanoTime();
                var r = CounterPrepare.prepare(req);
                ms.add((System.nanoTime() - t) / 1_000_000L);
                String name = f.getFileName() + " Counter." + req.method();
                if (!r.ok()) diffs.add(name + ": " + (r.needKind() != null ? "needs " + r.needKind() + " " + r.needKey() : r.error()));
                else if (!r.cbor().equals(fixture.get("unsignedTx").asText())) diffs.add(name + ": different bytes");
                var v = vectors.addObject();
                v.put("name", name);
                v.set("request", JSON.valueToTree(req));
                v.put("expected", fixture.get("unsignedTx").asText());
            }
        }
        assertTrue(vectors.size() >= 10, "Counter calls recorded: " + vectors.size());
        assertEquals(List.of(), diffs);
        Files.writeString(Path.of("build/s0-vectors.json"), JSON.writeValueAsString(vectors));
        var sorted = ms.stream().sorted().toList();
        System.out.println("[S0] " + vectors.size() + " Counter calls byte-identical on the JVM; prepare: first " + ms.get(0)
                + " ms, median " + sorted.get(sorted.size() / 2) + " ms");
    }

    @Test
    void aMissingReadIsReturnedAsANeed() throws Exception {
        var r = CounterPrepare.prepare(new CounterPrepare.Request("{}", 0, 0, 1000, 20_000, "increment",
                "00".repeat(32), "1", "addr_test1qrg4d2xx57yqa4etjhtynwehsaztvv2qk9vfkzyayauwqjarj7ys3h22758xddc44qep7d06s4cc8sv5s8qtpf6sat8svnegwf"));
        assertFalse(r.ok());
        assertNotNull(r.needKind(), r.error());
    }
}
