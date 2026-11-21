package org.julclang.state.host;

import com.bloxbean.cardano.client.address.Address;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import org.julclang.state.verifier.OutputVerifier;
import static org.julclang.state.host.Client.*;
import static org.julclang.state.host.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

/** Rebuild the independent client inputs and the complete host reply from the ordered node recordings. */
final class ClientVectors {
    static final Path HOST = HostVectorsTest.DIRECTORY;
    static final Path DIRECTORY = HOST.resolve("verifier");
    static final List<String> CASES = List.of("Bank-init", "Bank-publish-1", "Dex-addLiquidity",
            "Dex-combined-reconstructed", "Dex-init", "Dex-publish-1", "Dex-publish-2", "Dex-publish-3",
            "HostCouncil-init", "HostCouncil-publish-1", "destroy-events", "owned-action", "owned-destroy",
            "owned-init", "quorum-caller", "quorum-two", "owned-quorum-caller", "owned-quorum-two", "owned-events-caller",
            "owned-destroy-events", "dapp-0003", "dapp-0034", "permuted");

    static ObjectNode recording(String name) throws Exception {
        if (name.startsWith("dapp-")) {
            var r = ReplayTest.Recording.read(ReplayTest.FIXTURES.resolve("dapp/" + name.substring(5) + "-composed_transaction.json"));
            return HostVectorsTest.record("prepare", r.params(), init(), JSON.createArrayNode(), r::answer, null);
        }
        if (!name.equals("permuted")) return (ObjectNode) JSON.readTree(HOST.resolve(name + ".json").toFile());
        var v = recording("Bank-publish-1");
        // Reverse the SAME provider set. Resolve subsequent output queries by identity if selection changes.
        var payer = v.at("/params/address").textValue();
        var byRef = new HashMap<String, JsonNode>();
        var answers = new HashMap<JsonNode, JsonNode>();
        boolean permuted = false;
        for (var exchange : v.required("reads")) {
            var q = exchange.required("request");
            var a = (ObjectNode) exchange.required("answer").deepCopy();
            if (q.path("kind").asText().equals("utxos") && payer.equals(q.path("address").textValue())) {
                var items = new ArrayList<JsonNode>();
                a.required("value").forEach(items::add);
                assertTrue(items.size() > 1);
                Collections.reverse(items);
                var array = a.putArray("value");
                items.forEach(u -> {
                    array.add(u);
                    byRef.put(ref(u), u);
                });
                permuted = true;
            }
            answers.put(q, a);
        }
        assertTrue(permuted);
        var hello = init();
        for (String k : List.of("network","clock","time","limits")) hello.set(k,v.required(k));
        return HostVectorsTest.record("publish", (ObjectNode)v.required("params"), hello, v.at("/initialSession/registered"), q -> {
            if (answers.containsKey(q)) return answers.get(q);
            assertEquals("output", q.required("kind").textValue());
            var a = obj();
            a.set("tip", v.at("/reads/0/answer/tip"));
            return a.set("value", Objects.requireNonNull(byRef.get(q.required("ref").textValue())));
        }, null);
    }

    static String ref(JsonNode u) { return u.required("tx_hash").textValue() + "#" + u.required("output_index").intValue(); }

    static ObjectNode positive(String name) throws Exception {
        var v = recording(name);
        var params = v.required("params");
        var call = params.has("call") ? params.required("call") : params;
        String contract = call.required("contract").textValue();
        var entry = M1bTest.entry(Class.forName(contract + "Client"));
        var description = resource(entry,"META-INF/joust/" + contract + ".json");
        var x = description.required("x-joust");
        var script = resource(entry,"META-INF/plutus/" + x.required("validator").asText() + ".plutus.json");
        var verifier = resource(M1bTest.entry(OutputVerifier.class),"META-INF/plutus/OutputVerifier.plutus.json");
        var vector = obj().put("source", name).put("method", v.required("method").asText());
        vector.set("request", params);
        vector.put("networkId", v.at("/network/networkId").intValue()).put("maxFee","3000000");
        var anchors = vector.putObject("anchors");
        anchors.set("description", description);
        String hash = scriptHash(script);
        anchors.put("language",script.required("type").asText()).put("policy", hash).put("address", scriptAddress(hash, x.path("stakeSelf").asBoolean()));
        var shared = anchors.putObject("outputVerifier").put("language",verifier.required("type").asText()).put("hash",scriptHash(verifier));
        shared.put("address", scriptAddress(shared.required("hash").asText(),false));
        var resolved = vector.putObject("resolved");
        var status = vector.putObject("node");
        var reads = v.required("reads").iterator();
        var result = success(HostVectorsTest.run(v, q -> {
            var pair = reads.next();
            assertEquals(pair.required("request"), q);
            var a = pair.required("answer");
            switch (q.required("kind").asText()) {
                case "protocolParams" -> vector.set("protocolParams", a.required("value"));
                case "output" -> { if (a.has("value")) resolved.set(ref(a.get("value")), a.get("value")); }
                case "utxos" -> {
                    var holders = status.withObject("/scriptsAt").withArray(q.required("address").asText());
                    for (var u : a.path("value")) {
                        resolved.set(ref(u), u);
                        if (u.hasNonNull("reference_script_hash")) holders.add(u.required("reference_script_hash"));
                    }
                }
                case "stakeRegistration" -> status.withObject("/registrations").set(q.required("credential").asText(),
                        a.has("unsupported") ? JSON.getNodeFactory().textNode("unknown") : a.at("/value/registered"));
            }
            return a;
        }));
        assertFalse(reads.hasNext());
        assertEquals(v.at("/expected/cbor"), result.at("/prepared/cbor"));
        if (name.equals("permuted") || name.equals("Bank-publish-1")) vector.set("orderedReads",v.required("reads"));
        vector.set("result", result);
        vector.putArray("violations");
        return vector;
    }

    private static JsonNode resource(Path entry,String name) throws Exception {
        if (Files.isDirectory(entry)) return JSON.readTree(entry.resolve(name).toFile());
        try (var jar = new JarFile(entry.toFile()); var in = jar.getInputStream(jar.getJarEntry(name))) {
            return JSON.readTree(in);
        }
    }

    private static String scriptHash(JsonNode script) throws Exception {
        assertEquals("PlutusScriptV3",script.required("type").asText());
        var bytes = PlutusV3Script.builder().cborHex(script.required("cborHex").asText()).build();
        var hash = HexFormat.of().formatHex(bytes.getScriptHash());
        assertEquals(script.required("hash").asText(),hash);
        return hash;
    }

    private static String scriptAddress(String hash, boolean stakeSelf) {
        return new Address(HexFormat.of().parseHex(stakeSelf ? "30" + hash + hash : "70" + hash)).toBech32();
    }

    static void write(Path file, JsonNode value) throws Exception {
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(DIRECTORY);
        for (String name : CASES) write(DIRECTORY.resolve(name + ".json"), positive(name));
        org.julclang.state.host.conformance.MutationVectors.main(args);
        RecordedSessionsTest.main(args);
    }
}
