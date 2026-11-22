package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.julclang.state.host.Wire.*;
import static org.julclang.state.host.Client.*;

class TransportTest {
    @ParameterizedTest @ValueSource(strings = {
        "Content-Length: 4194305\r\n\r\n", "Content-Length: -1\r\n\r\n",
        "Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}", "X: 4\r\n\r\n",
        "Content-Length: 5\r\n\r\n{}", "Content-Length: 0\n\n", "garbage"
    })
    void rejectsMalformedFrame(String wire) {
        assertThrows(Frames.Violation.class, () -> new Frames.Reader(new ByteArrayInputStream(wire.getBytes())).next());
    }
    @Test void cappedHeaderAndMultibyteBody() throws Exception {
        var longHeader = "Content-Type: " + "a".repeat(1000) + "\r\nContent-Length: 2\r\n\r\n{}";
        assertThrows(Frames.Violation.class, () -> new Frames.Reader(new ByteArrayInputStream(longHeader.getBytes())).next());
        var out = new ByteArrayOutputStream();
        var body = "\"λ\"".getBytes(StandardCharsets.UTF_8);
        assertTrue(new Frames.Writer(out).write(body));
        assertEquals("Content-Length: 4\r\n\r\n\"λ\"", out.toString(StandardCharsets.UTF_8));
        assertArrayEquals(body, new Frames.Reader(new ByteArrayInputStream(out.toByteArray())).next());
    }
    @ParameterizedTest @ValueSource(strings = {"{", "{} {}", "null", "[]", "{\"jsonrpc\":\"2.0\",\"id\":1}", "{\"jsonrpc\":\"2.0\",\"method\":1}"})
    void invalidMessageTerminates(String body) throws Exception {
        try (var c = new Client((call,p) -> fail())) {
            c.writer.write(body.getBytes(StandardCharsets.UTF_8));
            assertNotNull(c.exits.poll(2, TimeUnit.SECONDS));
            assertTrue(c.output.isEmpty());
        }
    }
    @Test void invalidUtf8Terminates() throws Exception {
        try (var c = new Client((call,p) -> fail())) {
            c.writer.write(new byte[]{'"', (byte)0xc0, '"'});
            assertEquals("invalid or incomplete UTF-8 JSON frame", c.exits.poll(2, TimeUnit.SECONDS));
        }
    }
    @Test void incompleteFrameTimesOutFromFirstByte() throws Exception {
        try (var c = new Client((call,p) -> fail())) {
            c.stdin.write('C');
            c.stdin.flush();
            assertNull(c.exits.poll(1, TimeUnit.SECONDS));
            assertEquals("stalled frame", c.exits.poll(12, TimeUnit.SECONDS));
        }
    }
    @Test void oversizedResultAndFailedWriteNeverCommit() throws Exception {
        var commits = new AtomicInteger();
        Transport.Handler handler = (call,p) -> new Transport.Reply(
                obj().put("value", "a".repeat(p.path("large").asBoolean() ? Frames.MAX_BODY : 1)), commits::incrementAndGet);
        try (var c = new Client(handler)) {
            error("bad", "tooLarge", c.rpc("get", obj().put("large", true)));
            assertEquals(0, commits.get());
            assertEquals("a", success(c.rpc("get", obj())).path("value").textValue());
        }
        var exit = new CountDownLatch(1);
        var input = new PipedOutputStream();
        var t = new Transport(new PipedInputStream(input), new OutputStream() {
            public void write(int b) throws IOException { throw new IOException(); }
        }, x -> exit.countDown(), handler);
        Thread.ofPlatform().daemon().start(t::run);
        new Frames.Writer(input).write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"get\"}".getBytes());
        assertTrue(exit.await(2, TimeUnit.SECONDS));
        input.close();
        assertEquals(1, commits.get());
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void controlWinsDuringReadAndExecutionDrains(boolean cancel) throws Exception {
        var draining = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Transport.Handler handler = new Transport.Handler() {
            public long deadlineMs() { return cancel ? 60000 : 300; }
            public Transport.Reply execute(Call call, JsonNode p) {
                try { call.reverse("chain/read", obj().put("kind", "tip")); }
                finally {
                    draining.countDown();
                    while (release.getCount() != 0) {
                        try { release.await(); } catch (InterruptedException ignored) {}
                    }
                }
                return new Transport.Reply(obj());
            }
        };
        try (var c = new Client(handler)) {
            int id = c.request("first", obj());
            String rid = c.next().required("id").textValue();
            assertEquals("1.1", rid);
            error("bad", "busy", c.rpc("second", obj()));
            if (cancel) c.send(obj().put("method", "$/cancelRequest").set("params", obj().put("id", id)));
            var end = c.next();
            error("internal", cancel ? "cancelled" : "deadline", end);
            assertEquals(id, end.path("id").intValue());
            assertTrue(draining.await(1, TimeUnit.SECONDS));
            c.send(obj().put("id", rid).set("result", obj().putObject("failed").put("message", "too late")));
            error("bad", "busy", c.rpc("second", obj()));
            release.countDown();
            JsonNode pending;
            do {
                c.send(obj().put("id", id).put("method", "second").set("params", obj()));
                pending = c.next();
                if (pending.has("error")) error("bad", "busy", pending);
            } while (pending.has("error"));
            assertEquals("2.1", pending.required("id").textValue());
            c.send(obj().put("id", rid).set("result", obj()));
            c.send(obj().put("id", "2.1").set("result", obj()));
            assertTrue(success(c.next()).isObject());
        } finally { release.countDown(); }
    }
    @Test void cancelledCpuResultCannotCommitOrReplyAgain() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var commits = new AtomicInteger();
        try (var c = new Client((call, p) -> {
            if (call.method.equals("prepare")) {
                started.countDown();
                while (release.getCount() > 0) Thread.onSpinWait();
                return new Transport.Reply(obj(), commits::incrementAndGet);
            }
            return new Transport.Reply(obj());
        })) {
            int id = c.request("prepare", obj());
            assertTrue(started.await(1, TimeUnit.SECONDS));
            c.send(obj().put("method", "$/cancelRequest").set("params", obj().put("id", id)));
            error("internal", "cancelled", c.next());
            error("bad", "busy", c.rpc("prepare", obj()));
            release.countDown();
            JsonNode reply;
            do { reply = c.rpc("describe", obj()); } while (reply.has("error"));
            assertTrue(success(reply).isObject());
            assertEquals(0, commits.get());
            assertTrue(c.output.isEmpty());
        } finally { release.countDown(); }
    }

    @Test void uncooperativeExecutionStopsHost() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var c = new Client((call,p) -> {
            started.countDown();
            while (release.getCount() != 0) try { release.await(); } catch (InterruptedException ignored) {}
            return new Transport.Reply(obj());
        })) {
            int id = c.request("loop", obj());
            assertTrue(started.await(1, TimeUnit.SECONDS));
            c.send(obj().put("method", "$/cancelRequest").set("params", obj().put("id", id)));
            error("internal", "cancelled", c.next());
            assertEquals("execution did not stop within two seconds", c.exits.poll(3, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }
    @Test void aBlockedOutputStillEndsTheSession() throws Exception {
        var blocked = new CountDownLatch(1);
        var writing = new CountDownLatch(1);
        var exits = new LinkedBlockingQueue<String>();
        var input = new PipedOutputStream();
        var t = new Transport(new PipedInputStream(input, 65536), new OutputStream() {
            public void write(int b) { write(new byte[]{(byte) b}, 0, 1); }
            public void write(byte[] b, int off, int len) {   // the client stopped reading: the pipe is full
                writing.countDown();
                while (blocked.getCount() != 0) try { blocked.await(); } catch (InterruptedException ignored) {}
            }
        }, x -> exits.add(x == null ? "exit" : x), new Transport.Handler() {
            public long deadlineMs() { return 300; }
            public Transport.Reply execute(Call call, JsonNode p) {
                call.reverse("chain/read", obj().put("kind", "tip"));
                return new Transport.Reply(obj());
            }
        });
        Thread.ofPlatform().daemon().start(t::run);
        var writer = new Frames.Writer(input);
        writer.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"get\"}".getBytes());
        assertTrue(writing.await(2, TimeUnit.SECONDS));
        writer.write("{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}".getBytes());
        // The reverse request's write holds the transport's monitor, so neither the deadline nor exit can act; the
        // watchdog ends the session once the write has stalled for the frame bound.
        assertNull(exits.poll(1, TimeUnit.SECONDS));
        assertEquals("stalled protocol write", exits.poll(12, TimeUnit.SECONDS));
        // The pipe drains: the queued exit and the call's unwinding run now, and the session still exits once.
        blocked.countDown();
        assertNull(exits.poll(1, TimeUnit.SECONDS));
    }
    @Test void aBlockedStderrDelaysTheHaltOnlyBriefly() throws Exception {
        var never = new CountDownLatch(1);
        var err = new PrintStream(new OutputStream() {
            public void write(int b) { while (true) try { never.await(); } catch (InterruptedException ignored) {} }
        }, true);
        var halts = new LinkedBlockingQueue<Integer>();
        var exit = HostMain.exit(err, halts::add);
        long start = System.nanoTime();
        Thread.ofPlatform().daemon().start(() -> exit.accept("stalled frame"));
        assertEquals(1, halts.poll(HostMain.REASON_MILLIS + 2000, TimeUnit.MILLISECONDS));
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(HostMain.REASON_MILLIS - 50));
        exit.accept(null);   // a clean exit writes nothing
        assertEquals(0, halts.poll(1, TimeUnit.SECONDS));
    }
    @Test void aStopOnAnInterruptedThreadStillWritesItsReason() throws Exception {
        var lines = new ByteArrayOutputStream();
        var err = new PrintStream(new OutputStream() {
            public void write(int b) {
                try { Thread.sleep(lines.size() == 0 ? 100 : 0); } catch (InterruptedException ignored) {}
                lines.write(b);
            }
        }, true);
        var written = new LinkedBlockingQueue<String>();
        var exit = HostMain.exit(err, code -> written.add(lines.toString(StandardCharsets.UTF_8)));
        var stopping = Thread.ofPlatform().daemon().unstarted(() -> {
            Thread.currentThread().interrupt();   // as a call's own thread after abandon()
            exit.accept("protocol write failed");
        });
        stopping.start();
        assertEquals("joust dev host: protocol write failed" + System.lineSeparator(), written.poll(3, TimeUnit.SECONDS));
    }
}
