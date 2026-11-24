package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.state.examples.counter.CounterClient;
import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.julclang.state.host.Wire.*;
import static org.junit.jupiter.api.Assertions.*;

/** In-process peer speaking actual frames in both directions. */
class Client implements AutoCloseable {
    final BlockingQueue<byte[]> output = new LinkedBlockingQueue<>();
    final BlockingQueue<String> exits = new LinkedBlockingQueue<>();
    final PipedOutputStream stdin = new PipedOutputStream();
    final Frames.Writer writer = new Frames.Writer(stdin);
    final Thread host;
    int seq;
    Client(Transport.Handler handler) throws IOException {
        var transport = new Transport(new PipedInputStream(stdin, 65536), new OutputStream() {
            public void write(int b) { throw new AssertionError(); }
            public void write(byte[] b) { output.add(b); }
        }, s -> exits.add(s == null ? "exit" : s), handler);
        host = Thread.ofPlatform().daemon().start(transport::run);
    }
    static Path counter() throws Exception { return Path.of(CounterClient.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
    static Client session() throws Exception { return new Client(new Handshake(List.of(counter()))); }
    static ObjectNode init() throws Exception {
        return init(counter());
    }
    static ObjectNode init(Path entry) throws Exception {
        Path root = entry.getParent();
        var p = obj().put("restoring", false);
        p.putArray("protocol").add(1);
        p.putObject("build").put("snapshot", root.toString()).put("digest", Launch.scan(root, List.of(entry)).fingerprint());
        var genesis = JSON.readTree(ReplayTest.FIXTURES.resolve("devkit.json").toFile());
        p.putObject("clock").put("zeroTimeMs", genesis.required("startTimeMs").longValue())
                .put("zeroSlot", genesis.required("zeroSlot").longValue())
                .put("slotLengthMs", genesis.required("slotLengthMs").longValue());
        p.putObject("network").put("networkId", 0).put("protocolMagic", 42).put("mainnet", false);
        p.putObject("time").put("settlementSlots", 2).put("maxTtlSlots", 900).put("maxNowAheadSlots", 900);
        p.putObject("limits").put("readsPerCall", 256).put("readBytesPerCall", 33554432).put("callDeadlineMs", 60000);
        return p;
    }
    void send(ObjectNode msg) throws Exception {
        assertTrue(writer.write(JSON.writeValueAsBytes(msg.put("jsonrpc", "2.0"))));
    }
    int request(String method, JsonNode p) throws Exception {
        var req = obj().put("id", ++seq).put("method", method);
        send(req.set("params", p));
        return seq;
    }
    JsonNode next() throws Exception {
        byte[] frame = output.poll(10, TimeUnit.SECONDS);
        assertNotNull(frame, () -> "missing frame; exit " + exits.peek());
        return JSON.readTree(new Frames.Reader(new ByteArrayInputStream(frame)).next());
    }
    JsonNode rpc(String method, JsonNode params, Function<JsonNode, JsonNode> reads) throws Exception {
        int id = request(method, params);
        JsonNode frame;
        while ((frame = next()).has("method")) {
            assertEquals("chain/read", frame.get("method").textValue());
            var reply = obj().put("id", frame.get("id").textValue());
            send(reply.set("result", reads.apply(frame.get("params"))));
        }
        assertEquals(id, frame.path("id").intValue());
        return frame;
    }
    JsonNode rpc(String method, JsonNode params) throws Exception {
        return rpc(method, params, req -> fail("unexpected read " + req));
    }
    static JsonNode success(JsonNode reply) {
        assertFalse(reply.has("error"), reply::toString);
        return reply.required("result");
    }
    static void error(String kind, String code, JsonNode reply) {
        assertEquals(kind, reply.at("/error/data/kind").asText(), reply::toString);
        assertEquals(code, reply.at("/error/data/code").asText(), reply::toString);
    }
    public void close() throws IOException { stdin.close(); }
}
