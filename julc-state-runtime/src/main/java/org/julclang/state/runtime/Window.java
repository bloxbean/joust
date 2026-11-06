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
        long from = time.tipSlot();
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
        return new Window(from, to, BigInteger.valueOf(fromMs), BigInteger.valueOf(time.slotToPosixMs(to)));
    }
}
