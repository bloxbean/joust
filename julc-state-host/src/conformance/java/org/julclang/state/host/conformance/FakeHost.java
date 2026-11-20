package org.julclang.state.host.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.state.host.TranscriptFrames;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Scripted, bidirectional stdio peer. Requests may choose their own forward JSON-RPC IDs. */
public final class FakeHost {
    public static void main(String[] args) {
        try {
            if (args.length<1 || args.length>2 || !args[0].equals("--stdio"))
                throw new IllegalArgumentException("usage: FakeHost --stdio [session.json]");
            try (var in = args.length==2 ? Files.newInputStream(Path.of(args[1])) :
                    FakeHost.class.getResourceAsStream("/sessions/bank.json");
                 var frames = new TranscriptFrames()) {
                var transcript = Ledger.JSON.readTree(Objects.requireNonNull(in,"missing recorded session"));
                replay(transcript,frames);
            }
        } catch (Exception e) {
            System.err.println(String.valueOf(e.getMessage()).lines().findFirst().orElse("transcript mismatch"));
            System.exit(2);
        }
    }

    private static void replay(JsonNode transcript, TranscriptFrames io) throws Exception {
        var outstanding = new HashMap<JsonNode,JsonNode>();
        int step = 0;
        for (var exchange : transcript.required("frames")) {
            step++;
            if (exchange.has("host")) {
                ObjectNode response = exchange.required("host").deepCopy();
                if (!response.has("method")) response.set("id",Objects.requireNonNull(outstanding.remove(response.get("id"))));
                io.write(response);
                continue;
            }
            ObjectNode expected = exchange.required("client").deepCopy();
            var actual = io.read();
            if (expected.has("method") && expected.has("id")) {
                var id = actual.path("id");
                if (!(id.isTextual() || id.isIntegralNumber()) || outstanding.containsValue(id))
                    throw new IOException("invalid or outstanding forward ID at frame "+step);
                outstanding.put(expected.get("id"),id);
                expected.set("id",id);
            } else if ("$/cancelRequest".equals(expected.path("method").textValue())) {
                expected.withObject("/params").set("id",Objects.requireNonNull(outstanding.get(expected.at("/params/id"))));
            }
            if (!expected.equals(actual)) throw new IOException("client frame "+step+" differs from recording ("+
                    expected.path("method").asText("reverse answer")+")");
        }
        if (!outstanding.isEmpty()) throw new IOException("incomplete transcript");
    }
}
