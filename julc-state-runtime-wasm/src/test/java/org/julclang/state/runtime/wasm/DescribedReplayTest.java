package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.examples.devkit.Fixtures;
import org.julclang.state.toolchain.Toolchain;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0015 M1a/I3 on the JVM, with no contract classes: each prepare the dApp's DevKit run recorded, run again from its
 * call request and snapshot through {@link Prepare} (the engine's path: described contracts, call recipes, the methods
 * as transition programs), gives the recorded unsigned transaction byte for byte. Descriptions,
 * scripts and programs come from the toolchain, as an npm build makes them.
 */
class DescribedReplayTest {
    static final Path FIXTURES = Path.of("../fixtures/m0");
    static final ObjectMapper JSON = new ObjectMapper();

    static Prepare.Contract contract(String example) throws Exception {
        Path root = Path.of("../examples", example, "src/main/java");
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p);
                if (text.contains("@StateContract")) files.put(root.relativize(p).toString(), text);
            }
        }
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), example + ": " + r.diagnostics());
        var c = r.contracts().get(0);
        return new Prepare.Contract(c.descriptionJson(), c.scriptCborHex(), c.transitions());
    }

    /** The contract {@code simple} of {@code examples/<example>} (an example may hold several). */
    static Prepare.Contract contract(String example, String simple) throws Exception {
        Path root = Path.of("../examples", example, "src/main/java");
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p);
                if (text.contains("@StateContract")) files.put(root.relativize(p).toString(), text);
            }
        }
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), example + ": " + r.diagnostics());
        var c = r.contracts().stream().filter(x -> x.name().equals(simple)).findFirst().orElseThrow();
        return new Prepare.Contract(c.descriptionJson(), c.scriptCborHex(), c.transitions());
    }

    @Test
    void recordedPreparesReplayThroughDescriptionsAndPrograms() throws Exception {
        var contracts = List.of(contract("counter"), contract("bank"));
        JsonNode clock = JSON.readTree(FIXTURES.resolve("devkit.json").toFile());
        var vectors = JSON.createArrayNode();
        List<String> diffs = new ArrayList<>();
        List<Long> ms = new ArrayList<>();
        try (var files = Files.list(FIXTURES.resolve("dapp"))) {
            for (Path f : files.sorted().toList()) {
                JsonNode fixture = Fixtures.read(f);
                JsonNode call = fixture.get("call");
                if (call == null) continue;
                var req = new Prepare.Request(call.toString(), Fixtures.snapshot(fixture).toJson(), contracts,
                        clock.get("zeroSlot").asLong(), clock.get("startTimeMs").asLong(), clock.get("slotLengthMs").asLong(),
                        20_000, 0, java.util.Map.of());
                String name = f.getFileName() + " " + call.get("contract").asText().replaceAll(".*\\.", "") + "." + call.get("method").asText();
                long t = System.nanoTime();
                var r = Prepare.prepare(req);
                ms.add((System.nanoTime() - t) / 1_000_000L);
                if (!r.ok()) diffs.add(name + ": " + (r.needKind() != null ? "needs " + r.needKind() + " " + r.needKey() : r.error()));
                else if (!r.cbor().equals(fixture.get("unsignedTx").asText())) diffs.add(name + ": different bytes");
                var v = vectors.addObject();
                v.put("name", name);
                v.put("call", req.call());
                v.put("snapshot", req.snapshot());
                v.put("expected", fixture.get("unsignedTx").asText());
            }
        }
        assertEquals(List.of(), diffs);
        assertTrue(vectors.size() >= 30, "recorded prepares: " + vectors.size());
        var sorted = ms.stream().sorted().toList();
        System.out.println("[M1a] " + vectors.size() + " recorded prepares (Counter, Bank) byte-identical through descriptions and"
                + " transition programs; prepare: first " + ms.get(0) + " ms, median " + sorted.get(sorted.size() / 2) + " ms");
        var out = JSON.createObjectNode();
        out.set("contracts", JSON.valueToTree(contracts));
        out.set("clock", clock);
        out.set("vectors", vectors);
        Files.writeString(Path.of("build/prepare-vectors.json"), JSON.writeValueAsString(out));
    }

    @Test
    void refusalsAndNeeds() throws Exception {
        var contracts = List.of(contract("counter"));
        JsonNode fixture = null;
        try (var files = Files.list(FIXTURES.resolve("dapp"))) {
            for (Path f : files.sorted().toList()) {
                JsonNode x = Fixtures.read(f);
                if (x.has("call") && x.get("call").get("method").asText().equals("increment")) {
                    fixture = x;
                    break;
                }
            }
        }
        assertNotNull(fixture);
        var call = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.get("call").deepCopy();
        String snapshot = Fixtures.snapshot(fixture).toJson();
        // The contract's Require, from the transition program's trace.
        ((com.fasterxml.jackson.databind.node.ObjectNode) call.get("args")).put("by", "0");
        var refused = Prepare.prepare(new Prepare.Request(call.toString(), snapshot, contracts, 0, 1793975371000L, 1000, 20_000, 0, java.util.Map.of()));
        assertFalse(refused.ok());
        assertEquals("increment must be positive", refused.error());
        // A read the snapshot lacks: a need, not an error.
        var need = Prepare.prepare(new Prepare.Request(fixture.get("call").toString(), "{}", contracts, 0, 1793975371000L, 1000, 20_000, 0, java.util.Map.of()));
        assertFalse(need.ok());
        assertNotNull(need.needKind(), String.valueOf(need.error()));
        // An argument that does not fit its schema.
        ((com.fasterxml.jackson.databind.node.ObjectNode) call.get("args")).put("by", "five");
        var bad = Prepare.prepare(new Prepare.Request(call.toString(), snapshot, contracts, 0, 1793975371000L, 1000, 20_000, 0, java.util.Map.of()));
        assertFalse(bad.ok());
        assertTrue(bad.error().contains("not an integer"), bad.error());
    }
}
