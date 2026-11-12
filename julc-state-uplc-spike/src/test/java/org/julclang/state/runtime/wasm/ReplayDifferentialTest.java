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
 * ADR-0020 D4(a): recorded transactions replayed through the described core with each transition function; the
 * unsigned bytes must equal the recording.
 * <ul>
 *   <li>{@code fixtures/m0/dapp}: the 34 call requests (Counter, Bank), recorded from the typed clients on DevKit
 *       (examples/dapp {@code ReplayTest} replays them through the typed clients: the typed-path leg);</li>
 *   <li>{@code fixtures/m3}: the engine's recordings (made with programs), replayed through {@code JvmTransitions}
 *       and shadow (through programs alone they would compare with themselves).</li>
 * </ul>
 */
class ReplayDifferentialTest {

    @AfterEach
    void clear() throws Exception {
        Spike.uninstall();
    }

    record Case(String name, Prepare.Request request, String expected) {}

    static List<Case> dapp() throws Exception {
        var contracts = List.of(Spike.prepareContract(Spike.contract("org.julclang.state.examples.counter.Counter")),
                Spike.prepareContract(Spike.contract("org.julclang.state.examples.bank.Bank")));
        Path dir = Spike.ROOT.resolve("fixtures/m0");
        JsonNode clock = Spike.read(dir.resolve("devkit.json"));
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
        Path dir = Spike.ROOT.resolve("fixtures/m3");
        var contracts = new LinkedHashMap<String, Prepare.Contract>();
        try (Stream<Path> s = Files.list(dir.resolve("contracts"))) {
            for (Path p : s.sorted().toList()) {
                JsonNode c = Spike.read(p);
                var t = new LinkedHashMap<String, String>();
                c.path("transitions").fields().forEachRemaining(e -> t.put(e.getKey(), e.getValue().asText()));
                contracts.put(p.getFileName().toString().replace(".json", ""),
                        new Prepare.Contract(c.get("description").asText(), c.get("script").asText(), t));
            }
        }
        var out = new ArrayList<Case>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json") && !p.getParent().getFileName().toString().equals("contracts")).sorted().toList()) {
                JsonNode x = Spike.read(f);
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

    /** Replays {@code cases} in {@code mode}; returns name → "ok" or the difference. */
    static Map<String, String> replay(List<Case> cases, Spike.Mode mode, List<Long> nanos) throws Exception {
        var all = new ArrayList<Prepare.Contract>();
        for (Case c : cases) for (var pc : c.request().contracts()) if (pc != null && !all.contains(pc)) all.add(pc);
        Spike.install(all.stream().filter(pc -> !Spike.title(pc.description()).isEmpty()).toList(), pc -> Spike.fn(mode, pc));
        var results = new LinkedHashMap<String, String>();
        for (Case c : cases) {
            long t = System.nanoTime();
            var r = Spike.prepare(c.request());
            if (nanos != null) nanos.add(System.nanoTime() - t);
            if (!r.ok()) results.put(c.name(), r.needKind() != null ? "needs " + r.needKind() + " " + r.needKey() : "refused: " + r.error());
            else if (!r.cbor().equals(c.expected())) results.put(c.name(), "different bytes");
            else results.put(c.name(), "ok");
        }
        return results;
    }

    static Map<String, Long> tally(Map<String, String> r) {
        var t = new LinkedHashMap<String, Long>();
        r.values().forEach(v -> t.merge(v.equals("ok") ? "ok" : v.startsWith("different") ? "different bytes" : "failed", 1L, Long::sum));
        return t;
    }

    @Test
    void dappCallRequestsReplayInEveryMode() throws Exception {
        var cases = dapp();
        assertEquals(34, cases.size(), "fixtures/m0/dapp call requests");
        var body = Report.JSON.createObjectNode();
        for (Spike.Mode mode : Spike.Mode.values()) {
            var r = replay(cases, mode, null);
            body.set(mode.name(), Report.JSON.valueToTree(tally(r)));
            var bad = r.entrySet().stream().filter(e -> !e.getValue().equals("ok")).map(e -> e.getKey() + ": " + e.getValue()).toList();
            assertEquals(List.of(), bad, mode + " replay of fixtures/m0/dapp");
        }
        body.put("cases", cases.size());
        Report.write("replay-m0-dapp", body);
    }

    @Test
    void engineRecordingsReplayThroughTheJvmAndShadow() throws Exception {
        var cases = m3();
        var body = Report.JSON.createObjectNode();
        body.put("cases", cases.size());
        var failures = new LinkedHashMap<String, Object>();
        for (Spike.Mode mode : List.of(Spike.Mode.UPLC, Spike.Mode.JVM, Spike.Mode.SHADOW)) {
            var r = replay(cases, mode, null);
            body.set(mode.name(), Report.JSON.valueToTree(tally(r)));
            failures.put(mode.name(), r.entrySet().stream().filter(e -> !e.getValue().equals("ok")).map(e -> e.getKey() + ": " + e.getValue()).toList());
        }
        body.set("notOk", Report.JSON.valueToTree(failures));
        body.set("comparisons", Report.JSON.valueToTree(Report.COUNTS));
        Report.write("replay-m3", body);
        for (var e : failures.entrySet()) assertEquals(List.of(), e.getValue(), e.getKey() + " replay of fixtures/m3");
    }
}
