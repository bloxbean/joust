package org.julclang.state.host.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.state.host.TranscriptFrames;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Scripted, bidirectional stdio peer. Requests may choose their own forward JSON-RPC IDs. A session may end with one
 * {@code violation} entry (a transport fault of the host's); the client must then stop: close its end without another
 * frame (exit 0), apart from the frames the entry tolerates. Another frame exits 3, no end of input within
 * {@link #STOP_MILLIS} exits 4. After the last recorded frame of an honest session the client may close its end or stay
 * idle, and a further frame within {@link #IDLE_MILLIS} exits 3. Any other mismatch exits 2.
 */
public final class FakeHost {
    static final long STOP_MILLIS = 5_000, IDLE_MILLIS = 1_000;

    public static void main(String[] args) {
        int status;
        try {
            if (args.length<1 || args.length>2 || !args[0].equals("--stdio"))
                throw new IllegalArgumentException("usage: FakeHost --stdio [session.json]");
            try (var in = args.length==2 ? Files.newInputStream(Path.of(args[1])) :
                    FakeHost.class.getResourceAsStream("/sessions/bank.json");
                 var frames = new TranscriptFrames()) {
                var transcript = Ledger.JSON.readTree(Objects.requireNonNull(in,"missing recorded session"));
                status = replay(transcript,frames);
            }
        } catch (Exception e) {
            System.err.println(String.valueOf(e.getMessage()).lines().findFirst().orElse("transcript mismatch"));
            status = 2;
        }
        System.exit(status);
    }

    private static int replay(JsonNode transcript, TranscriptFrames io) throws Exception {
        var outstanding = new HashMap<JsonNode,JsonNode>();
        var answered = new HashMap<JsonNode,JsonNode>();
        int step = 0;
        for (var exchange : transcript.required("frames")) {
            step++;
            if (exchange.has("violation")) return violate(exchange,io,answered,step);
            if (exchange.has("host")) {
                ObjectNode response = exchange.required("host").deepCopy();
                if (!response.has("method")) {
                    var recorded = response.get("id");
                    response.set("id",Objects.requireNonNull(outstanding.remove(recorded)));
                    answered.put(recorded,response.get("id"));
                }
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
        var extra = io.readWithin(IDLE_MILLIS);
        if (extra.isPresent() && extra.get()!=TranscriptFrames.EOF) {
            System.err.println("client frame after the end of the recording ("+extra.get().path("method").asText("response")+")");
            return 3;
        }
        return 0;
    }

    /** Commit the scripted fault, then require the client to stop. */
    private static int violate(JsonNode entry, TranscriptFrames io, Map<JsonNode,JsonNode> answered, int step) throws Exception {
        String kind = entry.required("violation").asText();
        if (entry.has("frame")) {
            ObjectNode frame = entry.required("frame").deepCopy();
            // A second response reuses the ID the client chose for the recorded first one.
            if (entry.path("again").asBoolean()) frame.set("id",Objects.requireNonNull(answered.get(frame.get("id")),"no first response"));
            io.write(frame);
        } else if (entry.has("raw")) io.writeRaw(entry.required("raw").asText().getBytes(StandardCharsets.UTF_8));
        else if (entry.path("eof").asBoolean()) io.closeOutput();
        else throw new IOException("unknown violation entry at frame "+step);
        var tolerated = new ArrayList<JsonNode>();
        entry.path("tolerate").forEach(tolerated::add);
        while (true) {
            var next = io.readWithin(STOP_MILLIS);
            if (next.isEmpty()) {
                System.err.println("client did not stop after "+kind);
                return 4;
            }
            if (next.get()==TranscriptFrames.EOF) {
                System.err.println("client stopped after "+kind);
                return 0;
            }
            if (!tolerated.remove(next.get())) {
                System.err.println("client continued after "+kind+" ("+next.get().path("method").asText("response")+")");
                return 3;
            }
        }
    }
}
