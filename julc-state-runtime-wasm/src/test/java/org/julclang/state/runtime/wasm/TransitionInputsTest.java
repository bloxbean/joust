package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborDecoder;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.state.examples.devkit.Fixtures;
import org.julclang.state.runtime.described.TransitionInputs;
import org.julclang.state.runtime.described.UplcTransitions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0020 S15 (§11.4, adopted): the engine checks every transition's inputs against the contract's declared types
 * before it evaluates the program. The spike's malformed targets (seed 20261112: a wrong constructor tag, a missing
 * field, swapped field types, an extra field) are all refused, though the raw program runs about half of them to a
 * step; malformed arguments are refused with the argument named; and the check is on the engine's path
 * ({@link Prepare}), so a malformed state on chain is refused before anything is evaluated.
 */
class TransitionInputsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final PlutusData NONE = PlutusData.constr(0);

    static PlutusData args(PlutusData... a) {
        return PlutusData.constr(0, a);
    }

    @Test
    void malformedTargetsAreRefusedThoughTheRawProgramAcceptsMany() throws Exception {
        var c = DescribedReplayTest.contract("counter");
        var raw = UplcTransitions.verified(c.manifest(), c.description(), c.script(), c.transitions());
        var checked = new TransitionInputs(JSON.readTree(c.description()), raw);
        var r = new Random(20261112L);
        int rawSteps = 0;
        int n = 300;
        for (int i = 0; i < n; i++) {
            byte[] owner = new byte[28];
            r.nextBytes(owner);
            PlutusData bad = switch (r.nextInt(4)) {
                case 0 -> PlutusData.constr(1 + r.nextInt(9), PlutusData.integer(r.nextInt(100)), PlutusData.bytes(owner));
                case 1 -> PlutusData.constr(0, PlutusData.integer(r.nextInt(100)));
                case 2 -> PlutusData.constr(0, PlutusData.bytes(owner), PlutusData.integer(r.nextInt(100)));
                default -> PlutusData.constr(0, PlutusData.integer(r.nextInt(100)), PlutusData.bytes(owner), PlutusData.integer(1));
            };
            if (raw.run("increment", bad, NONE, args(PlutusData.integer(1)), NONE).step() != null) rawSteps++;
            var e = assertThrows(TransitionInputs.Malformed.class,
                    () -> checked.run("increment", bad, NONE, args(PlutusData.integer(1)), NONE), "accepted: " + bad);
            assertTrue(e.getMessage().startsWith("Counter.increment: the target (Counter.State) does not fit its declared type"),
                    e.getMessage());
        }
        System.out.println("[S15] malformed Counter targets: " + n + "/" + n + " refused by the input check; the raw program"
                + " ran " + rawSteps + " of them to a step");
        assertTrue(rawSteps > 0, "the raw program should accept some malformed targets (the lenient decoding the check covers)");
    }

    @Test
    void malformedArgumentsAreRefusedByName() throws Exception {
        var c = DescribedReplayTest.contract("counter");
        var checked = new TransitionInputs(JSON.readTree(c.description()), UplcTransitions.verified(c.manifest(), c.description(), c.script(), c.transitions()));
        var state = PlutusData.constr(0, PlutusData.integer(7), PlutusData.bytes(new byte[28]));
        // Well-typed: runs (and the contract's own rule still refuses by = 0).
        assertNotNull(checked.run("increment", state, NONE, args(PlutusData.integer(2)), NONE).step());
        var e = assertThrows(TransitionInputs.Malformed.class,
                () -> checked.run("increment", state, NONE, args(PlutusData.bytes(new byte[]{1})), NONE));
        assertEquals("Counter.increment: the argument by does not fit its declared type (by: not an integer)", e.getMessage());
        e = assertThrows(TransitionInputs.Malformed.class,
                () -> checked.run("increment", state, NONE, args(PlutusData.integer(1), PlutusData.integer(2)), NONE));
        assertEquals("Counter.increment: 1 arguments declared, 2 given", e.getMessage());
        e = assertThrows(TransitionInputs.Malformed.class,
                () -> checked.run("increment", state, NONE, PlutusData.constr(1, PlutusData.integer(1)), NONE));
        assertEquals("Counter.increment: the arguments are not a Constr 0 record", e.getMessage());
        e = assertThrows(TransitionInputs.Malformed.class, () -> checked.run("nothing", state, NONE, args(), NONE));
        assertEquals("Counter.nothing: the description declares no such transition", e.getMessage());
        // A Ctx the call declares (Counter.close) is checked too.
        e = assertThrows(TransitionInputs.Malformed.class,
                () -> checked.run("close", state, NONE, args(), PlutusData.constr(0, PlutusData.integer(1))));
        assertTrue(e.getMessage().startsWith("Counter.close: the Ctx does not fit its declared type"), e.getMessage());
    }

    @Test
    void theEngineRefusesAMalformedStateOnChainBeforeEvaluating() throws Exception {
        var contracts = java.util.List.of(DescribedReplayTest.contract("counter"));
        Path fixtures = Path.of("../fixtures/m0");
        JsonNode clock = JSON.readTree(fixtures.resolve("devkit.json").toFile());
        JsonNode fixture = null;
        try (var files = Files.list(fixtures.resolve("dapp"))) {
            for (Path f : files.sorted().toList()) {
                JsonNode x = Fixtures.read(f);
                if (x.has("call") && x.get("call").path("method").asText().equals("increment")) {
                    fixture = x;
                    break;
                }
            }
        }
        assertNotNull(fixture);
        var snapshot = (ObjectNode) JSON.readTree(Fixtures.snapshot(fixture).toJson());
        int tampered = tamper(snapshot);
        assertTrue(tampered > 0, "the snapshot holds the counter's state");
        var r = Prepare.prepare(new Prepare.Request(fixture.get("call").toString(), snapshot.toString(), contracts,
                clock.get("zeroSlot").asLong(), clock.get("startTimeMs").asLong(), clock.get("slotLengthMs").asLong(), 20_000, 0,
                java.util.Map.of()));
        assertFalse(r.ok());
        assertTrue(r.error().startsWith("Counter.increment: the target (Counter.State) does not fit its declared type"), r.error());
    }

    @Test
    void aTransitionNamedByTwoRecipesKeepsItsCtxCheck() throws Exception {
        // PR #62 C2, F8: Pool.donate is an action (with a Ctx) and a batched intent call (none): a direct call's Ctx is
        // still checked, as withdraw's is.
        var c = Builds.contract("org.julclang.state.examples.pool.Pool");
        var checked = new TransitionInputs(JSON.readTree(c.descriptionJson()),
                UplcTransitions.verified(c.manifestJson(), c.descriptionJson(), c.scriptCborHex(), c.transitions()));
        var state = PlutusData.constr(0, PlutusData.bytes(new byte[28]), PlutusData.integer(0), PlutusData.integer(0),
                PlutusData.integer(1_000_000), PlutusData.integer(1));
        var badCtx = PlutusData.constr(99, PlutusData.bytes(new byte[28]), PlutusData.integer(1_000_000), PlutusData.integer(10_000_000),
                PlutusData.integer(0), PlutusData.integer(1000), PlutusData.integer(999));
        var e = assertThrows(TransitionInputs.Malformed.class, () -> checked.run("donate", state, NONE, args(), badCtx));
        assertTrue(e.getMessage().startsWith("Pool.donate: the Ctx does not fit its declared type"), e.getMessage());
        e = assertThrows(TransitionInputs.Malformed.class, () -> checked.run("withdraw", state, NONE, args(PlutusData.integer(1)), badCtx));
        assertTrue(e.getMessage().startsWith("Pool.withdraw: the Ctx does not fit its declared type"), e.getMessage());
    }

    @Test
    void whatCannotBeCheckedIsRefused() throws Exception {
        // PR #62 S6: no argument layout, an $admitted that names no owned type, an argument without a schema, and two
        // recipes that disagree are refused when run; $funds and a $read without a schema are the named exemptions.
        var counter = Builds.contract("org.julclang.state.examples.counter.Counter");
        var state = PlutusData.constr(0, PlutusData.integer(7), PlutusData.bytes(new byte[28]));
        var d = (ObjectNode) JSON.readTree(counter.descriptionJson());
        for (JsonNode a : d.path("x-joust").path("actions")) {
            if (a.path("name").asText().equals("increment")) ((ObjectNode) a.path("recipe")).remove("transitionArgs");
            if (a.path("name").asText().equals("reset")) ((ObjectNode) a.path("recipe")).putArray("transitionArgs").add("nameless");
        }
        var checked = new TransitionInputs(d, (m, t, sh, a, c) -> state);
        var e = assertThrows(TransitionInputs.Malformed.class, () -> checked.run("increment", state, NONE, args(PlutusData.integer(1)), NONE));
        assertEquals("Counter.increment: its inputs cannot be checked: the description gives no argument layout (transitionArgs)", e.getMessage());
        e = assertThrows(TransitionInputs.Malformed.class, () -> checked.run("reset", state, NONE, args(PlutusData.integer(1)), NONE));
        assertEquals("Counter.reset: its inputs cannot be checked: the argument nameless has no declared schema", e.getMessage());
        var guild = Builds.contract("org.julclang.state.examples.guild.Guild");
        var g = (ObjectNode) JSON.readTree(guild.descriptionJson());
        for (JsonNode a : g.path("x-joust").path("actions")) {
            if (a.path("name").asText().equals("admit")) ((ObjectNode) a.path("recipe")).put("admits", "Guild.Nobody");
        }
        var gc = new TransitionInputs(g, (m, t, sh, a, c) -> state);
        var gs = PlutusData.constr(0);
        e = assertThrows(TransitionInputs.Malformed.class, () -> gc.run("admit", gs, NONE, args(gs, PlutusData.bytes(new byte[29])), NONE));
        assertTrue(e.getMessage().contains("$admitted names no owned type of the description (Guild.Nobody)"), e.getMessage());
        // Two recipes of one transition that disagree.
        var pool = (ObjectNode) JSON.readTree(Builds.contract("org.julclang.state.examples.pool.Pool").descriptionJson());
        for (JsonNode i : pool.path("x-joust").path("intents").path("calls")) {
            if (i.path("name").asText().equals("donate")) ((ObjectNode) i.path("recipe")).putArray("transitionArgs").add("$funds");
        }
        var pc = new TransitionInputs(pool, (m, t, sh, a, c) -> state);
        e = assertThrows(TransitionInputs.Malformed.class, () -> pc.run("donate", state, NONE, args(), NONE));
        assertTrue(e.getMessage().contains("recipes for this transition disagree"), e.getMessage());
    }

    @Test
    void aStoredArgumentKeepsItsShapeAndTheCheckRefusesIt() throws Exception {
        // The security review's unconfirmed case: a record argument stored without reading its fields. The program (the
        // validator's own code, by the same compiler) keeps a malformed argument verbatim in the next state, so a caller
        // with their own transaction can leave such a state. The engine refuses to make one (the argument is checked)
        // and refuses to act on one (the target is checked), as the page verifier and DescribedContract.state() refuse
        // to decode it: every off-chain path fails closed on that instance (ADR-0020 §12.1, §12.4).
        String path = "org/julclang/state/probe/StoreProbe.java";
        var r = org.julclang.state.toolchain.Toolchain.build(Map.of(path, Files.readString(Path.of("src/test/resources/probe").resolve(path))), Map.of());
        assertTrue(r.ok(), String.valueOf(r.diagnostics()));
        var c = r.contracts().get(0);
        var raw = UplcTransitions.verified(c.manifestJson(), c.descriptionJson(), c.scriptCborHex(), c.transitions());
        var checked = new TransitionInputs(JSON.readTree(c.descriptionJson()), raw);
        var pair = PlutusData.constr(0, PlutusData.integer(1), PlutusData.integer(2));
        var state = PlutusData.constr(0, PlutusData.integer(0), pair, PlutusData.bytes(new byte[28]));
        var malformed = PlutusData.constr(0, PlutusData.integer(1), PlutusData.integer(2), PlutusData.integer(3));
        var step = raw.run("put", state, NONE, args(malformed), NONE).step();
        assertNotNull(step, "the program stores it");
        var next = ((PlutusData.ConstrData) ((PlutusData.ConstrData) ((PlutusData.ConstrData) step).fields().get(0)).fields().get(0));
        assertEquals(malformed, next.fields().get(1), "verbatim, extra field and all");
        var e = assertThrows(TransitionInputs.Malformed.class, () -> checked.run("put", state, NONE, args(malformed), NONE));
        assertTrue(e.getMessage().startsWith("StoreProbe.put: the argument p does not fit its declared type"), e.getMessage());
        var stored = PlutusData.constr(0, PlutusData.integer(1), malformed, PlutusData.bytes(new byte[28]));
        e = assertThrows(TransitionInputs.Malformed.class, () -> checked.run("put", stored, NONE, args(pair), NONE));
        assertTrue(e.getMessage().startsWith("StoreProbe.put: the target (StoreProbe.State) does not fit its declared type"), e.getMessage());
        var codec = new org.julclang.state.runtime.described.SchemaCodec(JSON.readTree(c.descriptionJson()));
        assertThrows(org.julclang.state.runtime.described.SchemaCodec.DataError.class,
                () -> codec.decode(JSON.readTree(c.descriptionJson()).path("x-joust").path("state"), stored),
                "DescribedContract.state() decodes the state with the same strict codec");
    }

    /** Gives every Counter state datum in the snapshot an extra field (a shape the program itself would accept). */
    static int tamper(JsonNode n) {
        int count = 0;
        if (n instanceof ObjectNode o) {
            var names = new java.util.ArrayList<String>();
            o.fieldNames().forEachRemaining(names::add);
            for (String k : names) {
                JsonNode v = o.get(k);
                if (k.equals("inline_datum") && v.isTextual()) {
                    PlutusData d = PlutusDataCborDecoder.decode(HexFormat.of().parseHex(v.asText()));
                    if (d instanceof PlutusData.ConstrData c && c.tag() == 0 && c.fields().size() == 2
                            && c.fields().get(0) instanceof PlutusData.IntData && c.fields().get(1) instanceof PlutusData.BytesData) {
                        var fs = new java.util.ArrayList<>(c.fields());
                        fs.add(PlutusData.integer(BigInteger.ONE));
                        o.put(k, HexFormat.of().formatHex(PlutusDataCborEncoder.encode(new PlutusData.ConstrData(0, fs))));
                        count++;
                    }
                } else {
                    count += tamper(v);
                }
            }
        } else if (n != null && n.isArray()) {
            for (JsonNode v : n) count += tamper(v);
        }
        return count;
    }
}
