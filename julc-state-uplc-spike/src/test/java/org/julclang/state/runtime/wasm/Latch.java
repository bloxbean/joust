package org.julclang.state.runtime.wasm;

import java.util.ArrayList;
import java.util.List;

/**
 * ADR-0020 D3/D11: the per-call failure latch and the cumulative off-chain work limit. A call's state lives on the
 * calling thread from {@link #begin} to {@link #end}; a shadow mismatch sets it, and {@link #end} fails the call even
 * when nothing was built (an all-mismatching batch: {@code Batcher} skips every intent and builds nothing).
 */
final class Latch {
    private Latch() {}

    /** The spike's cumulative cap per call: four evaluations' worth (ADR-0015's round limit) of the per-run cap. */
    static final long MAX_CPU = 4 * UplcRunner.CAP.cpuSteps();
    static final long MAX_MEM = 4 * UplcRunner.CAP.memoryUnits();

    static final class State {
        final List<String> mismatches = new ArrayList<>();
        long cpu;
        long mem;
        int evaluations;
        int rounds;
    }

    /** A call over its cumulative limit (D11). */
    static final class OverLimit extends RuntimeException {
        OverLimit(String m) { super(m); }
    }

    /** A call the latch failed (D3): the short error remote callers would see; details stay in the spike's report. */
    static final class Latched extends RuntimeException {
        Latched(String m) { super(m); }
    }

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    static void begin() {
        CURRENT.set(new State());
    }

    static State current() {
        return CURRENT.get();
    }

    static void charge(long cpu, long mem) {
        State s = CURRENT.get();
        if (s == null) return;
        s.cpu += cpu;
        s.mem += mem;
        s.evaluations++;
        if (s.cpu > MAX_CPU || s.mem > MAX_MEM) throw new OverLimit("over the per-call off-chain work limit (ADR-0020 D11)");
    }

    static void mismatch(String detail) {
        State s = CURRENT.get();
        if (s != null) s.mismatches.add(detail);
    }

    /** Ends the call: returns its state and throws {@link Latched} if a mismatch was recorded. */
    static State end() {
        State s = CURRENT.get();
        CURRENT.remove();
        if (s != null && !s.mismatches.isEmpty()) {
            Report.mismatches(s.mismatches);
            throw new Latched("shadow mismatch (" + s.mismatches.size() + "); details in the spike report");
        }
        return s;
    }

    /** Ends the call without throwing (for tests that inspect the latch). */
    static State endQuietly() {
        State s = CURRENT.get();
        CURRENT.remove();
        return s;
    }
}
