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

    /** A contract's manifest as contracts.json holds it (an object), as the engine takes it (text); null when absent. */
    static String manifest(JsonNode c) {
        JsonNode m = c.get("manifest");
        return m == null || m.isNull() ? null : m.isTextual() ? m.asText() : m.toString();
    }

    /** The recorded contracts (fixtures/m3/contracts), by content hash. */
    static Map<String, Prepare.Contract> contracts() throws Exception {
        var contracts = new LinkedHashMap<String, Prepare.Contract>();
        try (Stream<Path> s = Files.list(FIXTURES.resolve("contracts"))) {
            for (Path p : s.toList()) {
                JsonNode c = JSON.readTree(p.toFile());
                var transitions = new LinkedHashMap<String, String>();
                c.path("transitions").fields().forEachRemaining(e -> transitions.put(e.getKey(), e.getValue().asText()));
                contracts.put(p.getFileName().toString().replace(".json", ""),
                        new Prepare.Contract(c.get("description").asText(), c.get("script").asText(), transitions, manifest(c)));
            }
        }
        return contracts;
    }

    /** A recorded request, prepared again from {@code snapshot}. */
    static Prepare.Result prepare(JsonNode x, JsonNode snapshot, Map<String, Prepare.Contract> contracts) {
        var cs = new ArrayList<Prepare.Contract>();
        for (JsonNode sha : x.get("contracts")) cs.add(contracts.get(sha.asText()));
        JsonNode clock = x.get("clock");
        return Prepare.prepare(new Prepare.Request(x.get("call").toString(), snapshot.toString(), cs,
                clock.get("zeroSlot").asLong(), clock.get("zeroTimeMs").asLong(), clock.get("slotLengthMs").asLong(),
                x.get("ttlMs").asLong(), x.get("networkId").asInt(), Map.of(), Map.of()));
    }

    /**
     * #165 reviewer3 R3-4: a governor's registration is checked per unit like every prepared body. The snapshot answers
     * each output alike to QuickTx and to the check, so the recorded registration is accepted and the same body with its
     * fee raised by 1 ADA (the outputs unchanged) is refused.
     */
    @Test
    void anUnbalancedRegistrationIsRefused() throws Exception {
        Path f = FIXTURES.resolve("treasury-governor/001-register.json");
        assumeTrue(Files.isRegularFile(f), "no fixtures/m3 (record with JOUST_RECORD)");
        JsonNode x = JSON.readTree(f.toFile());
        var honest = prepare(x, x.get("snapshot"), contracts());
        assertTrue(honest.ok(), honest::error);
        assertEquals(x.get("cbor").asText(), honest.cbor());
        var backend = new org.julclang.state.runtime.snapshot.SnapshotBackend(
                org.julclang.state.runtime.snapshot.Snapshot.fromJson(x.get("snapshot").toString()));
        var tx = com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(
                com.bloxbean.cardano.client.util.HexUtil.decodeHexString(honest.cbor()));
        Prepare.checkRegistration(tx, backend);
        tx.getBody().setFee(tx.getBody().getFee().add(java.math.BigInteger.valueOf(1_000_000)));
        var e = assertThrows(org.julclang.state.runtime.BodyBalance.UnbalancedException.class, () -> Prepare.checkRegistration(tx, backend));
        assertTrue(e.getMessage().contains("register the stake credential"), e::getMessage);
    }

    @Test
    void engineFixturesReplayOnTheJvm() throws Exception {
        assumeTrue(Files.isDirectory(FIXTURES), "no fixtures/m3 (record with JOUST_RECORD)");
        var contracts = contracts();
        List<String> diffs = new ArrayList<>();
        var groups = new LinkedHashMap<String, Integer>();
        try (Stream<Path> s = Files.walk(FIXTURES)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json") && !p.getParent().getFileName().toString().equals("contracts")).sorted().toList()) {
                JsonNode x = JSON.readTree(f.toFile());
                var cs = new ArrayList<Prepare.Contract>();
                for (JsonNode sha : x.get("contracts")) cs.add(contracts.get(sha.asText()));
                Map<String, String> nodes = new LinkedHashMap<>();
                x.path("mapNodes").fields().forEachRemaining(e -> nodes.put(e.getKey(), e.getValue().asText()));
                // Map proofs the page's mirror supplied (ADR-0015 §13.12 M1); earlier fixtures carry nodes instead.
                Map<String, String> proofs = new LinkedHashMap<>();
                x.path("mapProofs").fields().forEachRemaining(e -> proofs.put(e.getKey(), e.getValue().asText()));
                JsonNode clock = x.get("clock");
                var r = Prepare.prepare(new Prepare.Request(x.get("call").toString(), x.get("snapshot").toString(), cs,
                        clock.get("zeroSlot").asLong(), clock.get("zeroTimeMs").asLong(), clock.get("slotLengthMs").asLong(),
                        x.get("ttlMs").asLong(), x.get("networkId").asInt(), nodes, proofs));
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
