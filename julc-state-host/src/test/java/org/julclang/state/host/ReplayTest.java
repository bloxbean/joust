package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.state.examples.devkit.Fixtures;
import org.julclang.state.runtime.snapshot.Snapshot;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.julclang.state.host.Client.*;
import static org.julclang.state.host.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayTest {
    static final Path FIXTURES = Path.of(System.getProperty("joust.host.fixtures", "../fixtures/m0"));

    /** The first M3 recording of {@code contract}'s {@code method} ({@code NNN-method.json}): re-recording renumbers them. */
    static Path m3(String contract, String method) throws java.io.IOException {
        try (var s = Files.list(FIXTURES.resolveSibling("m3").resolve(contract))) {
            return s.filter(p -> p.getFileName().toString().matches("\\d+-" + method + "\\.json")).sorted().findFirst()
                    .orElseThrow(() -> new AssertionError("no recorded " + contract + "/" + method));
        }
    }
    // ADR-0022 §9: reconstruct testnet, 20 seconds in slots, recorded tip for now and every chain point.
    record Recording(JsonNode fixture, Snapshot snapshot, ObjectNode point, ObjectNode params) {
        static Recording read(Path file) throws Exception {
            var f = Fixtures.read(file);
            var s = Fixtures.snapshot(f);
            for (var read : f.path("reads")) {
                if (!"getPlutusScript".equals(read.path("method").asText())) continue;
                var v = read.at("/result/value");
                if (v.isObject()) {
                    String type = switch (v.path("language").asText()) {
                        case "PLUTUS_V3" -> "PlutusScriptV3";
                        default -> throw new AssertionError("unknown recorded language");
                    };
                    s.scripts.put(read.at("/args/0").asText(), new Snapshot.Script(type, v.required("cborHex").asText()));
                }
            }
            var tip = obj().put("hash", s.tip.getHash()).put("slot", s.tip.getSlot()).put("height", s.tip.getHeight());
            var call = (ObjectNode) f.get("call").deepCopy();
            call.remove("walletUtxos");
            var p = obj().put("now", s.tip.getSlot()).put("ttlSlots", 20000 / JSON.readTree(FIXTURES.resolve("devkit.json").toFile()).get("slotLengthMs").asLong());
            p.set("call", call);
            return new Recording(f, s, tip, p);
        }
        ObjectNode answer(JsonNode req) {
            ObjectNode a = obj().set("tip", point);
            switch (req.path("kind").asText()) {
                case "tip" -> a.set("value", point);
                case "protocolParams" -> a.set("value", JSON.valueToTree(snapshot.protocolParams));
                case "script" -> {
                    var script = snapshot.scripts.get(req.path("hash").asText());
                    if (script == null) a.put("absent", true);
                    else a.putObject("value").put("type", script.type()).put("cborHex", script.cborHex());
                }
                case "utxos" -> {
                    var list = snapshot.utxos.get(req.path("address").asText());
                    assertNotNull(list, req::toString);
                    a.set("value", JSON.valueToTree(list));
                }
                case "output" -> {
                    var ref = req.path("ref").asText();
                    String[] key = ref.split("#");
                    var u = snapshot.output(key[0], Integer.parseInt(key[1]));
                    if (u == null) {
                        assertTrue(snapshot.absent.contains(ref), ref);
                        a.put("absent", true);
                    } else a.set("value", JSON.valueToTree(u));
                }
                default -> fail(req::toString);
            }
            return a;
        }
    }
    @TestFactory Stream<DynamicTest> plainPrepares() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        try (var paths = Files.list(FIXTURES.resolve("dapp"))) {
            for (Path path : paths.sorted().toList()) {
                var f = JSON.readTree(path.toFile());
                if (!Set.of("init", "action", "destroy").contains(f.at("/call/kind").asText())) continue;
                boolean counter = f.at("/call/contract").asText().endsWith(".Counter");
                Path entry = counter ? counter() : Path.of(org.julclang.state.examples.bank.BankClient.class
                        .getProtectionDomain().getCodeSource().getLocation().toURI());
                tests.add(DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
                    var recording = Recording.read(path);
                    try (var client = new Client(new Handshake(List.of(entry)))) {
                        var hello = success(client.rpc("initialize", init(entry)));
                        if (counter) assertEquals("f46416f87d4622250ec7554fc583bd1f9d46bf2de04521ceddefa5cb",
                                hello.at("/contracts/0/scriptHashes/mint").asText());
                        var result = success(client.rpc("prepare", recording.params, recording::answer));
                        assertFalse(result.has("predicted"));
                        var tx = result.required("prepared");
                        assertEquals(f.get("unsignedTx"), tx.get("cbor"));
                        assertEquals(f.get("txHash"), tx.get("txHash"));
                        assertEquals(tx.at("/plan/validFrom"), tx.required("validFrom"));
                        assertEquals(tx.at("/plan/validTo"), tx.required("validTo"));
                    }
                }));
            }
        }
        // ADR-0022 §9: every plain recording. The JuLC 0.1.0-pre22 re-recording (#148) is a shorter flow: 18 plain calls
        // (Counter init, 13 increments, reset, close; Bank init, setPaused), where pre21's had 23; every kind is still covered.
        assertEquals(18, tests.size(), "ADR-0022 §9 plain recordings");
        return tests.stream();
    }
}
