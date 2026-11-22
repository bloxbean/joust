package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static org.julclang.state.host.Wire.*;

/** D2/D10. The monitor orders terminal frames, reverse requests and the D8 commit point. */
final class Transport {
    interface Handler {
        Reply execute(Call call, JsonNode params) throws Exception;
        default long deadlineMs() { return 60_000; }
    }
    record Reply(JsonNode value, Runnable commit) {
        Reply(JsonNode value) { this(value, () -> {}); }
    }

    private final Frames.Reader reader;
    private final Frames.Writer writer;
    private final Consumer<String> exit;
    private final Handler handler;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
    // A frame write can block (the client stopped reading) while it holds the monitor that the timer's deadline and the
    // input thread's cancel and exit need. This watchdog takes no lock and ends the session on a stalled write (#149 Codex).
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
    private final AtomicBoolean exited = new AtomicBoolean();
    /** Open until the exit that {@link #exited} claimed has run: {@link #run} does not return before it (#149). */
    private final java.util.concurrent.CountDownLatch exitDone = new java.util.concurrent.CountDownLatch(1);
    private Call active;
    private long generation;
    private volatile boolean stopped;
    private ScheduledFuture<?> deadline;

    Transport(InputStream in, OutputStream out, Consumer<String> exit, Handler handler) {
        reader = new Frames.Reader(in);
        writer = new Frames.Writer(out);
        this.exit = exit;
        this.handler = handler;
    }

    void run() {
        timer.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();   // the clock first, as for writes: a finished frame never looks stalled
            long start = reader.startedAt();
            if (start != 0 && now - start >= Frames.STALL_NANOS) stop("stalled frame");
        }, 50, 50, TimeUnit.MILLISECONDS);
        watchdog.scheduleAtFixedRate(() -> {
            // The clock first: a write still in progress after this sample started before it, so a stale sample of
            // a write that has since finished can never look overdue (#149 Codex fix review).
            long now = System.nanoTime();
            long since = writer.writingSince();
            if (since != 0 && now - since >= Frames.STALL_NANOS) {
                stopped = true;
                exitOnce("stalled protocol write");
                watchdog.shutdown();
            }
        }, 50, 50, TimeUnit.MILLISECONDS);
        try {
            byte[] body;
            while (!stopped && (body = reader.next()) != null) {
                String utf8 = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
                accept(JSON.readTree(utf8), body.length);
            }
            stop(null);
        } catch (Frames.Violation e) {
            stop(e.getMessage());
        } catch (IOException | StackOverflowError e) {
            stop("invalid or incomplete UTF-8 JSON frame");
        } finally {
            timer.shutdownNow();
            watchdog.shutdownNow();
            // An exit in progress on another thread (the watchdog's halt after its reason line) finishes first: main
            // returning here could otherwise end the JVM with status 0 before that halt (#149 review P3-1).
            if (exited.get()) {
                boolean interrupted = false;
                while (true) {
                    try { exitDone.await(); break; }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private synchronized void stop(String why) {
        if (!stopped) {
            stopped = true;
            if (active != null) active.abandon(HostError.internal("transport", "session closed"));
            exitOnce(why);
        }
    }

    private void exitOnce(String why) {
        if (!exited.compareAndSet(false, true)) return;
        try {
            exit.accept(why);
        } finally {
            exitDone.countDown();
        }
    }

    synchronized void accept(JsonNode msg, int bytes) throws Frames.Violation {
        if (msg == null || !msg.isObject() || !"2.0".equals(msg.path("jsonrpc").textValue()))
            throw new Frames.Violation("expected a JSON-RPC 2.0 object");
        var id = msg.get("id");
        if (!msg.has("method")) {
            if (id == null || (!id.isTextual() && !id.isIntegralNumber() && !id.isNull())
                    || msg.has("result") == msg.has("error")) throw new Frames.Violation("malformed response");
            if (msg.has("error") && !(msg.path("error").path("code").isIntegralNumber()
                    && msg.path("error").path("message").isTextual())) throw new Frames.Violation("malformed error response");
            if (active != null && id.isTextual()) active.answer(id.textValue(), msg, bytes);
            return;
        }
        String method = msg.path("method").textValue();
        if (method == null || msg.has("result") || msg.has("error")) throw new Frames.Violation("malformed request");
        JsonNode params = msg.path("params");
        if (id == null) {
            if (method.equals("exit")) stop(null);
            else if (method.equals("$/cancelRequest") && active != null && active.id.equals(params.get("id")))
                control(active, Call.End.CANCELLED);
            return;
        }
        if (!id.isIntegralNumber() && !id.isTextual()) throw new Frames.Violation("invalid forward id");
        if (active != null) {
            error(id, HostError.bad("busy", "another call is running"));
            return;
        }
        Call call = new Call(++generation, id, method, this);
        active = call;
        deadline = timer.schedule(() -> control(call, Call.End.DEADLINE), handler.deadlineMs(), TimeUnit.MILLISECONDS);
        call.thread = Thread.ofPlatform().daemon().name("joust-call-" + generation).unstarted(() -> execute(call, params));
        call.thread.start();
    }

    private void execute(Call call, JsonNode params) {
        try {
            var reply = handler.execute(call, params);
            ObjectNode response = envelope(call.id);
            response.set("result", reply.value());
            byte[] frame = encode(response);
            synchronized (this) {
                if (!stopped && call.latch(Call.End.RESULT) && send(frame)) reply.commit().run();
                release(call);
            }
        } catch (Throwable t) {
            synchronized (this) {
                if (!stopped && call.latch(Call.End.RESULT)) error(call.id, t instanceof HostError h ? h : Errors.classify(t));
                release(call);
            }
        }
    }

    private void release(Call call) {
        if (active == call) {
            deadline.cancel(false);
            active = null;
        }
    }

    private synchronized void control(Call call, Call.End end) {
        if (stopped || active != call || !call.latch(end)) return;
        var cause = HostError.internal(end == Call.End.DEADLINE ? "deadline" : "cancelled", "call " + end.name().toLowerCase());
        call.abandon(cause);
        error(call.id, cause);
        timer.schedule(() -> {
            synchronized (this) {
                if (active == call) stop("execution did not stop within two seconds");
            }
        }, 2, TimeUnit.SECONDS);
    }

    synchronized boolean sendRequest(Call call, String rid, String method, ObjectNode params) {
        call.checkLive();
        if (stopped || active != call) throw HostError.internal("transport", "call has ended");
        ObjectNode req = obj().put("jsonrpc", "2.0").put("id", rid).put("method", method);
        req.set("params", params);
        return send(encode(req));
    }

    private static ObjectNode envelope(JsonNode id) { return obj().put("jsonrpc", "2.0").set("id", id); }
    private void error(JsonNode id, HostError failure) {
        var out = envelope(id);
        out.set("error", failure.rpcError());
        try { send(encode(out)); }
        catch (HostError large) {
            if (!large.code.equals("tooLarge") || failure.code.equals("tooLarge")) stop("response id exceeds frame budget");
            else error(id, large);
        }
    }

    // Stop serialization at the frame cap, including escaping and UTF-8 expansion.
    private static byte[] encode(JsonNode n) {
        var bytes = new ByteArrayOutputStream();
        try {
            JSON.writeValue(new OutputStream() {
                public void write(int b) { check(1); bytes.write(b); }
                public void write(byte[] b, int off, int len) { check(len); bytes.write(b, off, len); }
                private void check(int n) {
                    if (n > Frames.MAX_BODY - bytes.size()) throw HostError.bad("tooLarge", "result exceeds frame cap");
                }
            }, n);
        } catch (IOException e) { throw HostError.internal("internal", "cannot encode response"); }
        return bytes.toByteArray();
    }

    private boolean send(byte[] frame) {
        if (writer.write(frame)) return true;
        stop("protocol write failed");
        return false;
    }
}
