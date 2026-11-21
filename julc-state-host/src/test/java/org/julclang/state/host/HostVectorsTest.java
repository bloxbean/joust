package org.julclang.state.host;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.julclang.state.examples.devkit.Fixtures;
import org.julclang.state.examples.dex.DexClient;
import org.julclang.state.runtime.snapshot.Snapshot;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.model.Amount;
import static org.julclang.state.host.Client.*;
import static org.julclang.state.host.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

class HostVectorsTest {
    static final Path DIRECTORY = Path.of(System.getProperty("joust.host.vectors", "../fixtures/host"));

    /** One portable forward request and the complete ordered exchanges that it observed. */
    static ObjectNode record(String method, ObjectNode params, ObjectNode init, JsonNode assertions,
                             Function<JsonNode, JsonNode> answers, String feature) throws Exception {
        var vector = obj().put("method", method);
        vector.set("params", params);
        for (String key : List.of("network", "clock", "time", "limits")) vector.set(key, init.required(key));
        vector.putObject("initialSession").set("registered", assertions);
        var reads = vector.putArray("reads");
        JsonNode response = run(vector, q -> {
            var a = answers.apply(q);
            var pair = reads.addObject();
            pair.set("request", q);
            pair.set("answer", a);
            return a;
        });
        if ("cooldown".equals(feature)) {
            error("refusal","cooldown",response);
            vector.putObject("expected").put("kind","refusal").put("code","cooldown")
                    .set("retryAtSlot",response.required("error").required("data").required("retryAtSlot"));
        } else if (feature != null) {
            error("unsupported", feature, response);
            vector.put("feature", feature);
            vector.putObject("expected").put("kind", "unsupported").put("code", feature);
        } else {
            var prepared = success(response).required("prepared");
            var expected = vector.putObject("expected");
            for (String key : List.of("cbor", "txHash", "validFrom", "validTo")) expected.set(key, prepared.required(key));
            expected.set("requiredSigners", prepared.at("/plan/requiredSigners"));
            for (String key : List.of("instance", "object"))
                if (prepared.has(key)) expected.set(key, prepared.get(key));
        }
        return vector;
    }

    static JsonNode run(JsonNode vector, Function<JsonNode, JsonNode> answers) throws Exception {
        var params = vector.required("params");
        String name = params.has("call") ? str(params.get("call"), "contract") : str(params, "contract");
        var entry = M1bTest.entry(Class.forName(name + "Client"));
        try (var c = new Client(new Handshake(List.of(entry)))) {
            var init = init(entry);
            for (String key : List.of("network", "clock", "time", "limits")) init.set(key, vector.required(key));
            success(c.rpc("initialize", init));
            for (var assertion : vector.required("initialSession").required("registered"))
                success(c.rpc("registered", assertion));
            return c.rpc(str(vector, "method"), params, answers);
        }
    }

    static void replay(JsonNode v) throws Exception {
        var reads = v.required("reads").elements();
        var response = run(v, q -> {
            assertTrue(reads.hasNext(), q::toString);
            var pair = reads.next();
            assertEquals(pair.required("request"), q);
            return pair.required("answer");
        });
        assertFalse(reads.hasNext(), "unused recorded read");
        checkOutcome(v, response);
    }

    /** The vector's recorded outcome against a host's response (here, and in the traced forked session). */
    static void checkOutcome(JsonNode v, JsonNode response) throws Exception {
        var expected = v.required("expected");
        if (expected.has("kind")) {
            if (!v.has("feature")) {
                error(str(expected,"kind"),str(expected,"code"),response);
                if (expected.has("retryAtSlot")) assertEquals(expected.get("retryAtSlot"),response.at("/error/data/retryAtSlot"));
                return;
            }
            if ("keyed".equals(v.path("feature").textValue())) {
                // ADR-0022 D11 supersedes the M1b unsupported reply. Preserve its original wire fixture.
                var current = JSON.readTree(DIRECTORY.resolve("keyed-needs-designated.json").toFile());
                assertEquals(current.required("params"),v.required("params"));
                error(str(current.required("expected"),"kind"),str(current.required("expected"),"code"),response);
                return;
            }
            assertEquals(v.get("feature"), expected.required("code"));
            error("unsupported", str(expected,"code"), response);
        } else {
            var built = success(response).required("prepared");
            for (String key : List.of("cbor", "txHash", "validFrom", "validTo"))
                assertEquals(expected.required(key), built.required(key), key);
            byte[] bytes = HexFormat.of().parseHex(str(built, "cbor"));
            assertEquals(str(expected, "txHash"), TransactionUtil.getTxHash(bytes));
            var body = Transaction.deserialize(bytes).getBody();
            assertEquals(expected.required("validTo").asLong(), body.getTtl());
            var keys = body.getRequiredSigners() == null ? List.of() :
                    body.getRequiredSigners().stream().map(HexFormat.of()::formatHex).toList();
            assertEquals(expected.required("requiredSigners"), JSON.valueToTree(keys));
        }
    }

    @TestFactory List<DynamicTest> recordedVectors() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        if (Files.isDirectory(DIRECTORY)) try (var files = Files.list(DIRECTORY)) {
            for (var f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList())
                tests.add(DynamicTest.dynamicTest(f.getFileName().toString(), () -> replay(JSON.readTree(f.toFile()))));
        }
        Assumptions.assumeFalse(tests.isEmpty(), "no DevKit host vectors recorded");
        return tests;
    }

    static final List<String> REGISTRY = List.of("Registry-publish-1","Registry-init","Registry-grant-62","Registry-grant-61",
            "Registry-cooldown","Registry-remove","Registry-retire");

    @Test void freshDevKitRegistryWorkflow() throws Exception {
        Assumptions.assumeTrue(Files.exists(DIRECTORY.resolve("Registry-init.json")), "record the keyed workflow on a fresh DevKit");
        for (var name : REGISTRY) replay(JSON.readTree(DIRECTORY.resolve(name+".json").toFile()));
    }

    /** Historical bounded counterpart of M0 Dex 0003 (§9). Yaci Store cannot supply a live negative registration read. */
    static ObjectNode combinedPublication() throws Exception {
        var dir = ReplayTest.FIXTURES.resolve("dex");
        var f = Fixtures.read(dir.resolve("0003-publish_and_register_the_JOUST_output_verifier.json"));
        var s = Fixtures.snapshot(f);
        var original = Transaction.deserialize(HexFormat.of().parseHex(str(f,"unsignedTx")));
        var originalOutputs = original.getBody().getOutputs();
        String payer = originalOutputs.stream().filter(o -> o.getScriptRef() == null).findFirst().orElseThrow().getAddress();
        var first = Fixtures.read(dir.resolve("0001-publish_the_Dex_reference_script.json"));
        var publication = Transaction.deserialize(HexFormat.of().parseHex(str(first,"unsignedTx")));
        var rt = org.julclang.state.examples.devkit.DevKit.snapshotRuntime(s, ReplayTest.FIXTURES.resolve("devkit.json"));
        var handle = DexClient.contract(rt);
        var holder = publication.getBody().getOutputs().stream().filter(o -> handle.address().equals(o.getAddress())).findFirst().orElseThrow();
        int index = publication.getBody().getOutputs().indexOf(holder);
        s.utxos.put(handle.address(),List.of(Utxo.builder().txHash(str(first,"txHash")).outputIndex(index).address(handle.address())
                .referenceScriptHash(handle.policyId()).amount(List.of(Amount.lovelace(holder.getValue().getCoin()))).build()));
        var tip = obj().put("slot",s.tip.getSlot()).put("height",s.tip.getHeight()).put("hash",s.tip.getHash());
        var p = obj().put("now",s.tip.getSlot()).put("ttlSlots",20).put("contract",DexClient.class.getPackageName()+".Dex")
                .put("address",payer).put("prerequisite","verifier:"+rt.outputVerifier().hash());
        var r = new ReplayTest.Recording(f,s,tip,p);
        var vector = record("publish",p,init(M1bTest.entry(DexClient.class)),JSON.createArrayNode(),q -> {
            if (!q.path("kind").asText().equals("stakeRegistration")) return r.answer(q);
            // Successful registration in 0003 establishes pre-transaction absence. This is reconstructed evidence.
            var a = obj();
            a.set("tip",tip);
            a.putObject("value").put("registered",false);
            return a;
        },null);
        return vector.put("provenance","bounded reconstruction of fixtures/m0/dex/0003 using the 0001 holder and pre-registration status");
    }

    @Test void boundedCounterpartOfHistoricalCombinedPublication() throws Exception {
        var v = combinedPublication();
        replay(v);
        var historical = Fixtures.read(ReplayTest.FIXTURES.resolve("dex/0003-publish_and_register_the_JOUST_output_verifier.json"));
        var old = Transaction.deserialize(HexFormat.of().parseHex(str(historical,"unsignedTx"))).getBody();
        var bounded = Transaction.deserialize(HexFormat.of().parseHex(v.at("/expected/cbor").asText())).getBody();
        assertEquals(old.getCerts().getFirst().serialize(com.bloxbean.cardano.client.spec.Era.Conway),
                bounded.getCerts().getFirst().serialize(com.bloxbean.cardano.client.spec.Era.Conway));
        assertEquals(old.getOutputs().getFirst(),bounded.getOutputs().getFirst());
        assertEquals(v.at("/params/now").asLong()+20,bounded.getTtl());
    }

    static Map<String, ObjectNode> unsupportedVectors(ObjectNode init, long now, long ttl, String address) throws Exception {
        record Example(String feature, Class<?> client, String kind, String method) {}
        var vectors = new LinkedHashMap<String, ObjectNode>();
        for (var example : List.of(
                new Example("params", org.julclang.state.examples.treasury.TreasuryClient.class,"action","pay"),
                new Example("maps", org.julclang.state.examples.token.TokenClient.class,"init","create"),
                new Example("authorizedBy", org.julclang.state.host.fixture.HostCouncilClient.class,"action","veto"),
                new Example("unverifiable", org.julclang.state.examples.pool.PoolClient.class,"action","donate"))) {
            var p = obj().put("now",now).put("ttlSlots",ttl);
            var call = p.putObject("call").put("contract",example.client.getName().replaceFirst("Client$",""))
                    .put("kind",example.kind).put("method",example.method).put("address",address);
            var args = call.putObject("args");
            if (example.kind.equals("action")) call.put("instance", "11".repeat(32));
            if (example.feature.equals("params")) args.put("to", "22".repeat(28)).put("amount", "1");
            if (example.feature.equals("unverifiable")) call.putObject("pay").put("lovelace", "3000000");
            var v = record("prepare",p,init,JSON.createArrayNode(),q -> fail("unsupported request read " + q),example.feature);
            replay(v);
            vectors.put("unsupported-" + example.feature,v);
        }
        return vectors;
    }

    @Test void everyUnsupportedFeatureReplaysWithItsTypedCodeBeforeARead() throws Exception {
        var r = IntegrationTest.record();
        assertEquals(4, unsupportedVectors(init(),r.params().required("now").asLong(),20,
                r.params().at("/call/address").asText()).size());
    }

    @Test void recorderRoundTripAndReadOrder() throws Exception {
        var r = IntegrationTest.record();
        var vector = record("prepare", r.params(), init(), JSON.createArrayNode(), r::answer, null);
        assertEquals(r.fixture().required("unsignedTx"), vector.at("/expected/cbor"));
        replay(vector);
        ((ObjectNode)vector.at("/reads/0/request")).put("kind", "tip");
        assertThrows(AssertionError.class, () -> replay(vector));
    }
}
