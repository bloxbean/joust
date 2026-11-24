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
            long now = System.nanoTime();   // before the samples, as the host's watchdog
            long started = reader.startedAt();
            if (started != 0 && now-started >= Frames.STALL_NANOS) {
                System.err.println("stalled frame");
                System.exit(2);
            }
            // A client that never reads a host frame blocks its write: end with a diagnostic, as the host does.
            long writing = writer.writingSince();
            if (writing != 0 && now-writing >= Frames.STALL_NANOS) {
                System.err.println("client did not read a host frame");
                System.exit(4);
            }
        },50,50,TimeUnit.MILLISECONDS);
    }
    public JsonNode read() throws Exception {
        var bytes = reader.next();
        if (bytes == null) throw new EOFException("expected client frame");
        var frame = json.readTree(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        // An empty or whitespace-only body parses to Jackson's MissingNode: a frame without a JSON-RPC object, never
        // the end of input (#153 Codex review).
        if (frame == null || !frame.isObject()) throw new IOException("client frame is not a JSON object");
        return frame;
    }
    public void write(JsonNode frame) throws IOException {
        if (!writer.write(json.writeValueAsBytes(frame))) throw new IOException("failed to deliver host frame");
    }
    /** Unframed bytes on the protocol descriptor: a scripted transport violation. */
    public void writeRaw(byte[] bytes) {
        System.out.write(bytes, 0, bytes.length);
        System.out.flush();
    }
    /** End of the host's output, as if it had exited mid-call. */
    public void closeOutput() { System.out.close(); }
    /** The next client frame within the bound: empty on timeout, null at end of input. */
    public java.util.Optional<JsonNode> readWithin(long millis) throws Exception {
        var next = CompletableFuture.supplyAsync(() -> {
            try { return read(); }
            catch (EOFException e) { return null; }
            catch (Exception e) { throw new CompletionException(e); }
        }, Executors.newVirtualThreadPerTaskExecutor());
        try { return java.util.Optional.ofNullable(next.get(millis, TimeUnit.MILLISECONDS)).or(() -> java.util.Optional.of(EOF)); }
        catch (TimeoutException e) { return java.util.Optional.empty(); }
        catch (ExecutionException e) { throw e.getCause() instanceof Exception x ? x : e; }
    }
    /** Marks end of input in {@link #readWithin}: an instance of its own, compared by identity, never a parsed frame. */
    public static final JsonNode EOF = new com.fasterxml.jackson.databind.node.ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
    public void close() { timer.shutdownNow(); }
}
