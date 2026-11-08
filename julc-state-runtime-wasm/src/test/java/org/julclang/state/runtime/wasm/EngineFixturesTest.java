package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0015 I3 across the examples: every call the Wasm engine prepared in the npm path's DevKit flows
 * (js/joust-runtime/test/examples.test.js with JOUST_RECORD=fixtures/m3) is prepared again on the JVM from the same
 * request, snapshot, map nodes and contracts, and gives the same unsigned transaction, byte for byte.
 */
class EngineFixturesTest {
    static final Path FIXTURES = Path.of("../fixtures/m3");
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void engineFixturesReplayOnTheJvm() throws Exception {
        assumeTrue(Files.isDirectory(FIXTURES), "no fixtures/m3 (record with JOUST_RECORD)");
        var contracts = new LinkedHashMap<String, Prepare.Contract>();
        try (Stream<Path> s = Files.list(FIXTURES.resolve("contracts"))) {
            for (Path p : s.toList()) {
                JsonNode c = JSON.readTree(p.toFile());
                var transitions = new LinkedHashMap<String, String>();
                c.path("transitions").fields().forEachRemaining(e -> transitions.put(e.getKey(), e.getValue().asText()));
                contracts.put(p.getFileName().toString().replace(".json", ""),
                        new Prepare.Contract(c.get("description").asText(), c.get("script").asText(), transitions));
            }
        }
        List<String> diffs = new ArrayList<>();
        var groups = new LinkedHashMap<String, Integer>();
        try (Stream<Path> s = Files.walk(FIXTURES)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json") && !p.getParent().getFileName().toString().equals("contracts")).sorted().toList()) {
                JsonNode x = JSON.readTree(f.toFile());
                var cs = new ArrayList<Prepare.Contract>();
                for (JsonNode sha : x.get("contracts")) cs.add(contracts.get(sha.asText()));
                Map<String, String> nodes = new LinkedHashMap<>();
                x.path("mapNodes").fields().forEachRemaining(e -> nodes.put(e.getKey(), e.getValue().asText()));
                JsonNode clock = x.get("clock");
                var r = Prepare.prepare(new Prepare.Request(x.get("call").toString(), x.get("snapshot").toString(), cs,
                        clock.get("zeroSlot").asLong(), clock.get("zeroTimeMs").asLong(), clock.get("slotLengthMs").asLong(),
                        x.get("ttlMs").asLong(), x.get("networkId").asInt(), nodes));
                String name = FIXTURES.relativize(f).toString();
                if (!r.ok()) diffs.add(name + ": " + (r.needKind() != null ? "needs " + r.needKind() + " " + r.needKey() : r.error()));
                else if (!r.cbor().equals(x.get("cbor").asText())) {
                    diffs.add(name + ": different bytes");
                    if (System.getenv("JOUST_DUMP") != null) Files.writeString(Path.of(System.getenv("JOUST_DUMP"), name.replace("/", "_") + ".jvm"), r.cbor());
                }
                groups.merge(f.getParent().getFileName().toString(), 1, Integer::sum);
            }
        }
        assertEquals(List.of(), diffs);
        assertFalse(groups.isEmpty());
        System.out.println("[I3] engine fixtures byte-identical on the JVM: " + groups);
    }
}
