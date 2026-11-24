package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.state.runtime.wasm.Prepare;
import org.julclang.state.toolchain.Toolchain;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #160 Codex re-review: npm map read discovery evaluates the transition several times without a chain read in between
 * (the final path's reconstruction). A cancellation that lands there stops the call before its next evaluation, and the
 * session serves the next call.
 */
class EngineCancelTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path REPO = Path.of("..");

    static Toolchain.Contract token() throws Exception {
        Path root = REPO.resolve("examples/token/src/main/java");
        Map<String, String> files = new LinkedHashMap<>();
        try (var s = Files.walk(root)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String text = Files.readString(p);
                if (text.contains("@StateContract")) files.put(root.relativize(p).toString(), text);
            }
        }
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        return r.contracts().stream().filter(c -> c.name().equals("Token")).findFirst().orElseThrow();
    }

    /**
     * Answers the vector's reads by request (a retry after a cancellation reads no history: the nodes the cancelled call
     * replayed stay in the session's store); {@code read} is set after each answer, before the session's liveness check.
     */
    static Function<String, String> reverse(JsonNode vector, boolean[] read) {
        return params -> {
            JsonNode request;
            try { request = JSON.readTree(params); } catch (Exception e) { throw new AssertionError(e); }
            for (var pair : vector.get("orderedReads")) if (pair.get("request").equals(request)) {
                read[0] = true;
                return JSON.createObjectNode().set("result", pair.get("answer")).toString();
            }
            throw new AssertionError("unrecorded read " + params);
        };
    }

    @Test
    void aCancellationBetweenDiscoveryEvaluationsStopsTheCallAndKeepsTheSession() throws Exception {
        var c = token();
        var contracts = JSON.createArrayNode().add(JSON.valueToTree(
                new Prepare.Contract(c.descriptionJson(), c.scriptCborHex(), c.transitions(), c.manifestJson())));
        var setup = JSON.createObjectNode().put("fingerprint", "00".repeat(32)).set("contracts", contracts);
        ((ObjectNode) setup).putObject("versions").putNull("build");
        var vector = JSON.readTree(REPO.resolve("fixtures/host/verifier/token-m3-transfer.json").toFile());
        var init = (ObjectNode) JSON.readTree(REPO.resolve("fixtures/host/sessions/bank.json").toFile())
                .at("/frames/2/client/params").deepCopy();
        init.set("clock", vector.get("clock"));
        var session = new EngineSession();
        Function<JsonNode, String> frame = p -> p.toString();
        BooleanSupplier yes = () -> true;
        var hello = JSON.readTree(session.execute(frame.apply(JSON.createObjectNode().put("method", "initialize").set("params", init)),
                setup.toString(), p -> { throw new AssertionError("no reads"); }, yes, yes, () -> {}));
        assertTrue(hello.has("result"), hello.toString());
        session.commit();
        var prepare = frame.apply(JSON.createObjectNode().put("method", "prepare").set("params", vector.get("request")));

        // A dry run counts the evaluations' liveness checks: every check not right after a read is an evaluation's.
        boolean[] read = {false};
        int[] evaluations = {0};
        var dry = new EngineSession();
        dry.execute(frame.apply(JSON.createObjectNode().put("method", "initialize").set("params", init)), setup.toString(),
                p -> { throw new AssertionError("no reads"); }, yes, yes, () -> {});
        dry.commit();
        var done = JSON.readTree(dry.execute(prepare, "", reverse(vector, read), () -> {
            if (read[0]) { read[0] = false; return true; }
            evaluations[0]++;
            return true;
        }, yes, () -> {}));
        assertEquals(vector.at("/result/prepared/txHash"), done.at("/result/prepared/txHash"), done.toString());
        // The first discovery walks the path (from, to, then the final run); later evaluations replay the step with its
        // claims while the transaction is built.
        assertTrue(evaluations[0] >= 4, "evaluations: " + evaluations[0]);

        // Cancelled at the fourth evaluation: latched, as the transport latches it.
        boolean[] cancelled = {false};
        int[] seen = {0};
        read[0] = false;
        var stopped = JSON.readTree(session.execute(prepare, "", reverse(vector, read), () -> {
            if (cancelled[0]) return false;
            if (read[0]) { read[0] = false; return true; }
            if (++seen[0] == 4) cancelled[0] = true;
            return !cancelled[0];
        }, yes, () -> {}));
        assertEquals("cancelled", stopped.at("/error/data/code").asText(), stopped.toString());
        assertEquals(4, seen[0], "no evaluation after the cancellation");

        read[0] = false;
        var next = JSON.readTree(session.execute(prepare, "", reverse(vector, read), yes, yes, () -> {}));
        assertEquals(vector.at("/result/prepared/txHash"), next.at("/result/prepared/txHash"), next.toString());
    }
}
