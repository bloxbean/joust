package org.julclang.state.runtime;

import org.julclang.state.api.RequireException;

import java.math.BigInteger;
import java.util.List;

/**
 * A validity interval chosen in slots ({@code [fromSlot, toSlot)}), and the POSIX ms the contract sees for it. Slots
 * come first; milliseconds are derived, so the JVM run and the on-chain run see identical numbers (ADR-0001 D3).
 */
record Window(long fromSlot, long toSlot, BigInteger fromMs, BigInteger toMs) {

    /**
     * From the tip, for at most min(TTL, maxValidityMs), ending no later than every {@code notAfter} deadline and
     * starting no earlier than every {@code notBefore} time.
     */
    static <S> Window open(StateRuntime rt, ContractSpec<?> spec, List<Call.TimeRule<S>> notBefore,
                           List<Call.TimeRule<S>> notAfter, S state, String what) {
        ChainTime time = rt.chainTime();
        TxRunner.Capture cap = TxRunner.capture();
        if (cap != null && cap.forced != null) {
            // A composition's one window (ADR-0011 D1): every call's time rules must hold in it.
            Window w = cap.forced;
            for (var rule : notAfter) {
                if (BigInteger.valueOf(time.slotToPosixMs(w.toSlot())).compareTo(rule.time().apply(state)) > 0) {
                    throw new RequireException(what + ": the composed window ends after the deadline in '" + rule.field() + "'");
                }
            }
            for (var rule : notBefore) {
                if (w.fromMs().compareTo(rule.time().apply(state)) < 0) {
                    throw new RequireException(what + ": the composed window starts before '" + rule.field() + "'");
                }
            }
            return w;
        }
        long from = cap != null && !cap.windows.isEmpty() ? cap.windows.get(0).fromSlot() : time.tipSlot();
        long ttl = Math.min(rt.ttlMs(), spec.maxValidityMs());
        long to = from + Math.max(1, ttl / time.slotLengthMs());
        for (var rule : notAfter) {
            long deadline = rule.time().apply(state).longValueExact();
            long last = time.posixMsToSlotFloor(deadline);   // slotToPosix(last) <= deadline
            if (last < to) to = last;
            if (to <= from) {
                throw new RequireException(what + ": too late, the deadline in '" + rule.field() + "' has passed");
            }
        }
        long fromMs = time.slotToPosixMs(from);
        for (var rule : notBefore) {
            BigInteger t = rule.time().apply(state);
            if (BigInteger.valueOf(fromMs).compareTo(t) < 0) {
                throw new RequireException(what + ": too early, the time in '" + rule.field() + "' has not been reached");
            }
        }
        Window w = new Window(from, to, BigInteger.valueOf(fromMs), BigInteger.valueOf(time.slotToPosixMs(to)));
        if (cap != null) cap.windows.add(w);
        return w;
    }

    /** The intersection of windows that share their start; null if empty. */
    static Window intersect(StateRuntime rt, java.util.List<Window> ws) {
        long from = Long.MIN_VALUE, to = Long.MAX_VALUE;
        for (Window w : ws) {
            from = Math.max(from, w.fromSlot());
            to = Math.min(to, w.toSlot());
        }
        if (ws.isEmpty() || from >= to) return null;
        ChainTime time = rt.chainTime();
        return new Window(from, to, BigInteger.valueOf(time.slotToPosixMs(from)), BigInteger.valueOf(time.slotToPosixMs(to)));
    }
}
