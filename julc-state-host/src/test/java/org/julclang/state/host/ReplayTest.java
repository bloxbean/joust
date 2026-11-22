package org.julclang.state.host;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
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
    /** The plain calls the dApp recording must replay (ADR-0022 §9): Counter's init, action and destroy; Bank's init and action. */
    static final Set<String> PLAIN = Set.of("Counter init", "Counter action", "Counter destroy", "Bank init", "Bank action");
    /** With Bank's owned calls (M1b): its owned init, action and destroy. */
    static final Set<String> PLAIN_AND_OWNED = Set.of("Counter init", "Counter action", "Counter destroy", "Bank init",
            "Bank action", "Bank ownedInit", "Bank ownedAction", "Bank ownedDestroy");
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
    /**
     * ADR-0022 §9 replay gates check coverage, not a count (re-recording changes how many calls a flow makes): each
     * {@code "Contract kind"} in {@code wanted} must be among those replayed. The message lists what is missing.
     */
    static void assertCovers(Set<String> replayed, Set<String> wanted, String gate) {
        var missing = new TreeSet<>(wanted);
        missing.removeAll(replayed);
        assertTrue(missing.isEmpty(), gate + ": no recording replays " + missing + " (replayed: " + new TreeSet<>(replayed) + ")");
    }

    /** {@code "Counter action"} for a recording's call: the contract's simple name and the call kind. */
    static String covered(JsonNode f) {
        String contract = f.at("/call/contract").asText();
        return contract.substring(contract.lastIndexOf('.') + 1) + " " + f.at("/call/kind").asText();
    }
    @TestFactory Stream<DynamicTest> plainAndOwnedPrepares() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        var replayed = new HashSet<String>();
        try (var paths = Files.list(FIXTURES.resolve("dapp"))) {
            for (Path path : paths.sorted().toList()) {
                var f = JSON.readTree(path.toFile());
                if (!f.hasNonNull("call")) continue;
                replayed.add(covered(f));
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
                        var effects = result.required("predicted");
                        String kind = recording.params.at("/call/kind").asText();
                        if (kind.startsWith("owned")) {
                            var effect = effects.required("objects").required(0);
                            assertEquals(switch (kind) {
                                case "ownedInit" -> "created";
                                case "ownedDestroy" -> "removed";
                                default -> "updated";
                            }, effect.required("effect").asText());
                            assertEquals(kind.equals("ownedInit") ? result.at("/prepared/object") : recording.params.at("/call/object"),
                                    effect.required("object"));
                        } else if (kind.equals("destroy")) assertTrue(effects.required("state").isNull());
                        else assertTrue(effects.at("/state/predicted").asBoolean());
                        for (var payout : effects.required("payouts")) {
                            assertEquals(result.at("/prepared/plan/outputs/" + payout.required("index") + "/value"), payout.required("value"));
                            assertTrue(payout.required("owed").has("lovelace"));
                        }
                        var tx = result.required("prepared");
                        assertEquals(f.get("unsignedTx"), tx.get("cbor"));
                        assertEquals(f.get("txHash"), tx.get("txHash"));
                        // I9: compare the returned fields with the final CBOR and the client's own window.
                        var body = Transaction.deserialize(HexFormat.of().parseHex(tx.required("cbor").asText())).getBody();
                        long ceiling = recording.params.required("now").longValue() + recording.params.required("ttlSlots").longValue();
                        assertTrue(body.getTtl() > 0 && body.getTtl() <= ceiling);
                        assertEquals(body.getTtl(), tx.required("validTo").longValue());
                        if (body.getValidityStartInterval() == 0) assertTrue(tx.required("validFrom").isNull());
                        else assertEquals(body.getValidityStartInterval(), tx.required("validFrom").longValue());
                        assertEquals(tx.at("/plan/validFrom"), tx.required("validFrom"));
                        assertEquals(tx.at("/plan/validTo"), tx.required("validTo"));
                    }
                }));
            }
        }
        // ADR-0022 §9: every plain and owned recording replays, and together they cover each plain and owned kind of the
        // dApp's contracts.
        assertCovers(replayed, PLAIN_AND_OWNED, "ADR-0022 §9 plain and owned recordings");
        return tests.stream();
    }
}
