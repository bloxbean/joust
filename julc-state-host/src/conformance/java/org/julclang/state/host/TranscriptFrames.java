package org.julclang.state.host;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Classifier-only adapter to the exact protocol-1 framing implementation. */
public final class TranscriptFrames implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final Frames.Reader reader = new Frames.Reader(System.in);
    private final Frames.Writer writer = new Frames.Writer(System.out);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());

    public TranscriptFrames() {
        timer.scheduleAtFixedRate(() -> {
            long started = reader.startedAt();
            if (started != 0 && System.nanoTime()-started >= Frames.STALL_NANOS) {
                System.err.println("stalled frame");
                System.exit(2);
            }
        },50,50,TimeUnit.MILLISECONDS);
    }
    public JsonNode read() throws Exception {
        var bytes = reader.next();
        if (bytes == null) throw new EOFException("expected client frame");
        return json.readTree(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
    }
    public void write(JsonNode frame) throws IOException {
        if (!writer.write(json.writeValueAsBytes(frame))) throw new IOException("failed to deliver host frame");
    }
    public void close() { timer.shutdownNow(); }
}
