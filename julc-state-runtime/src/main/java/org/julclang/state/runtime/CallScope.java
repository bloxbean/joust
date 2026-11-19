package org.julclang.state.runtime;

import java.util.OptionalLong;

/**
 * One call's own clock and limits (ADR-0022 D7), overriding a runtime's fixed settings while the call runs: see
 * {@link StateRuntime#inScope(CallScope, java.util.function.Supplier)}. Inside the scope the runtime's
 * {@link StateRuntime#chainTime()} answers {@link ChainTime#currentSlot()} and {@link ChainTime#tipSlot()} with
 * {@link #now()} (one clock per call: neither the machine clock nor the provider's tip), the untimed wallet path takes
 * its slot from it, and {@link StateRuntime#ttlMs()} and {@link StateRuntime#pageLimits()} are the scope's when it sets
 * them. Slot and time conversion stay the runtime's {@link ChainTime}'s. Immutable.
 * <p>
 * A scope is for building and reading (a prepare, a view). A submitting call that waits for the chain to move (a keyed
 * attempt's window start, its settlement) requires an advancing clock and throws {@link IllegalStateException} inside a scope.
 */
public final class CallScope {

    private final long now;
    private final long ttlSlots;          // 0: the runtime's TTL
    private final long[] pageLimits;      // null: the runtime's page limits

    private final boolean hosted;

    private CallScope(long now, long ttlSlots, long[] pageLimits, boolean hosted) {
        this.hosted = hosted;
        this.now = now;
        this.ttlSlots = ttlSlots;
        this.pageLimits = pageLimits;
    }

    /** A scope whose current slot and tip slot are {@code now}; the runtime's TTL and page limits otherwise. */
    public static CallScope at(long now) {
        if (now < 0) throw new IllegalArgumentException("now must be a slot (not negative)");
        return new CallScope(now, 0, null, false);
    }

    /**
     * A dev-host build (ADR-0022 D6–D9, D12). Its backend exposes complete address sets and
     * memoizes them for this call. Missing reference scripts and illegal windows are typed refusals.
     */
    public static CallScope hosted(long now) {
        if (now < 0) throw new IllegalArgumentException("now must be a slot (not negative)");
        return new CallScope(now, 0, null, true);
    }

    boolean hosted() { return hosted; }

    /** A refusal at a host-only build boundary (ADR-0022 D9). */
    public static final class Refusal extends org.julclang.state.api.RequireException {
        private final String code;
        Refusal(String code, String message) {
            super(message);
            this.code = code;
        }
        public String code() { return code; }
    }

    /** This scope with a TTL of {@code slots} slots (at least 1, subject to the host's TTL limit), in place of the runtime's. */
    public CallScope ttlSlots(long slots) {
        if (slots < 1) throw new IllegalArgumentException("ttlSlots must be at least 1");
        return new CallScope(now, slots, pageLimits, hosted);
    }

    /** This scope with keyed calls' page limits, in slots after {@link #now()} (ADR-0016 D7), in place of the runtime's. */
    public CallScope pageLimits(long maxStartSlots, long maxValiditySlots) {
        if (maxStartSlots < 0 || maxValiditySlots < 1) {
            throw new IllegalArgumentException("page limits: maxStartSlots >= 0 and maxValiditySlots >= 1");
        }
        return new CallScope(now, ttlSlots, new long[]{maxStartSlots, maxValiditySlots}, hosted);
    }

    /** The slot the caller considers current: the scope's {@code currentSlot()} and {@code tipSlot()}. */
    public long now() {
        return now;
    }

    /** The scope's TTL in slots, or empty for the runtime's. */
    public OptionalLong ttlSlots() {
        return ttlSlots == 0 ? OptionalLong.empty() : OptionalLong.of(ttlSlots);
    }

    /** The scope's page limits ({@code {maxStartSlots, maxValiditySlots}}), or null for the runtime's. */
    public long[] pageLimits() {
        return pageLimits == null ? null : pageLimits.clone();
    }

    @Override
    public String toString() {
        return "CallScope[now=" + now + (ttlSlots == 0 ? "" : ", ttlSlots=" + ttlSlots)
                + (pageLimits == null ? "" : ", pageLimits=" + pageLimits[0] + "/" + pageLimits[1]) + "]";
    }

    /** The scope's clock: the runtime's conversions, with {@code now} as both the current slot and the tip. */
    static final class Clock implements ChainTime {
        private final ChainTime base;
        private final long now;

        Clock(ChainTime base, long now) {
            this.base = base;
            this.now = now;
        }

        @Override
        public long slotToPosixMs(long slot) {
            return base.slotToPosixMs(slot);
        }

        @Override
        public long posixMsToSlotFloor(long posixMs) {
            return base.posixMsToSlotFloor(posixMs);
        }

        @Override
        public long slotLengthMs() {
            return base.slotLengthMs();
        }

        @Override
        public long tipSlot() {
            return now;
        }

        @Override
        public long currentSlot() {
            return now;
        }
    }
}
