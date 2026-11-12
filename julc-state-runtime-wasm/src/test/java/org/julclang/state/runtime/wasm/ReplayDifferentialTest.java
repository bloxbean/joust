package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.state.examples.devkit.Fixtures;
import org.junit.jupiter.api.AfterEach;
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
 * ADR-0020 §11.4, the comparison mode in CI (the spike's D4(a) replays): recorded transactions are prepared again on
 * the engine's path with each loaded contract's transitions run as its programs, as its JVM methods, and as both
 * compared step by step; the unsigned bytes must equal the recording in every mode, and no comparison may differ.
 * <ul>
 *   <li>{@code fixtures/m0/dapp}: the 34 call requests (Counter, Bank) recorded from the typed clients on DevKit
 *       (examples/dapp {@code ReplayTest} replays them through the typed clients);</li>
 *   <li>{@code fixtures/m3}: the engine's recordings (every kind the engine prepares, 15 groups).</li>
 * </ul>
 * A divergence between the JVM and the chain's meaning of a contract method (a JuLC change, a new library) fails here.
 */
class ReplayDifferentialTest {

    @AfterEach
    void restore() {
        Comparison.restore();
    }

    record Case(String name, Prepare.Request request, String expected) {}

    static List<Case> dapp() throws Exception {
        var contracts = List.of(Builds.prepareContract(Builds.contract("org.julclang.state.examples.counter.Counter")),
                Builds.prepareContract(Builds.contract("org.julclang.state.examples.bank.Bank")));
        Path dir = Builds.ROOT.resolve("fixtures/m0");
        JsonNode clock = Comparison.JSON.readTree(dir.resolve("devkit.json").toFile());
        var out = new ArrayList<Case>();
        try (var files = Files.list(dir.resolve("dapp"))) {
            for (Path f : files.sorted().toList()) {
                JsonNode x = Fixtures.read(f);
                JsonNode call = x.get("call");
                if (call == null) continue;
                out.add(new Case("m0/dapp/" + f.getFileName(), new Prepare.Request(call.toString(), Fixtures.snapshot(x).toJson(),
                        contracts, clock.get("zeroSlot").asLong(), clock.get("startTimeMs").asLong(),
                        clock.get("slotLengthMs").asLong(), 20_000, 0, Map.of()), x.get("unsignedTx").asText()));
            }
        }
        return out;
    }

    static List<Case> m3() throws Exception {
        Path dir = Builds.ROOT.resolve("fixtures/m3");
        var contracts = new LinkedHashMap<String, Prepare.Contract>();
        try (Stream<Path> s = Files.list(dir.resolve("contracts"))) {
            for (Path p : s.sorted().toList()) {
                JsonNode c = Comparison.JSON.readTree(p.toFile());
                var t = new LinkedHashMap<String, String>();
                c.path("transitions").fields().forEachRemaining(e -> t.put(e.getKey(), e.getValue().asText()));
                contracts.put(p.getFileName().toString().replace(".json", ""),
                        new Prepare.Contract(c.get("description").asText(), c.get("script").asText(), t, EngineFixturesTest.manifest(c)));
            }
        }
        var out = new ArrayList<Case>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json") && !p.getParent().getFileName().toString().equals("contracts")).sorted().toList()) {
                JsonNode x = Comparison.JSON.readTree(f.toFile());
                var cs = new ArrayList<Prepare.Contract>();
                for (JsonNode sha : x.get("contracts")) cs.add(contracts.get(sha.asText()));
                Map<String, String> nodes = new LinkedHashMap<>();
                x.path("mapNodes").fields().forEachRemaining(e -> nodes.put(e.getKey(), e.getValue().asText()));
                Map<String, String> proofs = new LinkedHashMap<>();
                x.path("mapProofs").fields().forEachRemaining(e -> proofs.put(e.getKey(), e.getValue().asText()));
                JsonNode clock = x.get("clock");
                out.add(new Case("m3/" + dir.relativize(f), new Prepare.Request(x.get("call").toString(), x.get("snapshot").toString(), cs,
                        clock.get("zeroSlot").asLong(), clock.get("zeroTimeMs").asLong(), clock.get("slotLengthMs").asLong(),
                        x.get("ttlMs").asLong(), x.get("networkId").asInt(), nodes, proofs), x.get("cbor").asText()));
            }
        }
        return out;
    }

    /** Replays {@code cases} in {@code mode}; returns each case that did not give the recorded bytes, with why. */
    static List<String> replay(List<Case> cases, Comparison.Mode mode) {
        Comparison.use(mode);
        var bad = new ArrayList<String>();
        for (Case c : cases) {
            var r = Prepare.prepare(c.request());
            if (!r.ok()) bad.add(c.name() + ": " + (r.needKind() != null ? "needs " + r.needKind() + " " + r.needKey() : "refused: " + r.error()));
            else if (!r.cbor().equals(c.expected())) bad.add(c.name() + ": different bytes");
        }
        return bad;
    }

    static void inEveryMode(List<Case> cases, String what) {
        for (Comparison.Mode mode : Comparison.Mode.values()) {
            ShadowTransitions.reset();
            assertEquals(List.of(), replay(cases, mode), mode + " replay of " + what);
            assertEquals(List.of(), ShadowTransitions.MISMATCHES, mode + " comparisons of " + what);
            if (mode == Comparison.Mode.SHADOW) {
                int n = ShadowTransitions.COMPARED.values().stream().mapToInt(Integer::intValue).sum();
                assertTrue(n > 0, "no comparison made");
                System.out.println("[comparison] " + what + ": " + cases.size() + " recordings byte-identical through programs,"
                        + " JVM methods and both; " + n + " comparisons over " + ShadowTransitions.COMPARED.size() + " methods, none differing");
            }
        }
    }

    @Test
    void dappCallRequestsReplayInEveryMode() throws Exception {
        var cases = dapp();
        assertEquals(34, cases.size(), "fixtures/m0/dapp call requests");
        inEveryMode(cases, "fixtures/m0/dapp");
    }

    @Test
    void engineRecordingsReplayInEveryMode() throws Exception {
        var cases = m3();
        assertTrue(cases.size() >= 200, "fixtures/m3 recordings: " + cases.size());
        inEveryMode(cases, "fixtures/m3");
    }
}
