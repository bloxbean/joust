package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One forward call's execution (ADR-0022 D10): its generation (fresh per execution, used in its reverse-request ids and
 * stamped on its per-call writes), its one latched terminal cause (the first of result, failure, cancellation or
 * deadline wins), and its outstanding reverse requests.
 */
final class Call {

    /** The terminal causes; the first latched wins. */
    enum End { RESULT, CANCELLED, DEADLINE }

    final long generation;
    final JsonNode id;
    final String method;
    private final Transport transport;
    private final AtomicReference<End> end = new AtomicReference<>();
    private final AtomicInteger next = new AtomicInteger();
    private final Map<String, CompletableFuture<Answer>> pending = new ConcurrentHashMap<>();
    volatile Thread thread;

    /** A reverse request's answer: the response message and the size of its frame body (for {@code readBytesPerCall}). */
    record Answer(JsonNode message, int bytes) {}

    Call(long generation, JsonNode id, String method, Transport transport) {
        this.generation = generation;
        this.id = id;
        this.method = method;
        this.transport = transport;
    }

    /** D10: a session mutation is ordered with the terminal latch on the transport monitor. */
    void whileLive(Runnable mutation) {
        synchronized (transport) {
            checkLive();
            mutation.run();
        }
    }

    /** Latches {@code cause} as this call's end; false when another cause won already. */
    boolean latch(End cause) {
        return end.compareAndSet(null, cause);
    }

    End ended() {
        return end.get();
    }

    /**
     * Throws the control event that ended this call, if a cancellation or a deadline did (D10): its per-call writes
     * (claims, reads) are then rejected, and the execution unwinds with that code, never {@code readFailed}.
     */
    void checkLive() {
        End e = end.get();
        if (e == End.RESULT) throw HostError.internal("transport", "the call has ended");
        if (e == End.CANCELLED) throw HostError.internal("cancelled", "the call was cancelled");
        if (e == End.DEADLINE) throw HostError.internal("deadline", "the call's deadline passed");
        if (Thread.currentThread() == thread && Thread.currentThread().isInterrupted()) {
            throw HostError.internal("cancelled", "the call was interrupted");
        }
    }

    /**
     * Sends a reverse request ({@code <generation>.<n>}, D2) and waits for its answer. Only inside this forward call,
     * while it is live (D6 rules); a cancellation or deadline while waiting ends it with that control code.
     */
    Answer reverse(String method, ObjectNode params) {
        checkLive();
        String rid = generation + "." + next.incrementAndGet();
        var f = new CompletableFuture<Answer>();
        pending.put(rid, f);
        try {
            if (!transport.sendRequest(this, rid, method, params)) throw HostError.internal("transport", "cannot write a reverse request");
            checkLive();   // a cancellation between registering and sending abandoned f already
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            checkLive();
            throw HostError.internal("cancelled", "the call was interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HostError h) throw h;
            throw HostError.internal("internal", String.valueOf(e.getCause()));
        } finally {
            pending.remove(rid);
        }
    }

    /** An answer to one of this call's reverse requests; false when the id is not outstanding (dropped, D2). */
    boolean answer(String rid, JsonNode message, int bytes) {
        var f = pending.remove(rid);
        if (f == null) return false;
        f.complete(new Answer(message, bytes));
        return true;
    }

    /** Abandons every outstanding reverse request with the control event {@code cause} (late answers are dropped). */
    void abandon(HostError cause) {
        for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            it.remove();
            e.getValue().completeExceptionally(cause);
        }
        Thread t = thread;
        if (t != null) t.interrupt();
    }
}
