package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        var raw = new UplcTransitions(c.transitions());
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
        var checked = new TransitionInputs(JSON.readTree(c.description()), new UplcTransitions(c.transitions()));
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
