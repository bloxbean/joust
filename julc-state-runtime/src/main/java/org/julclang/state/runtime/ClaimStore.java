package org.julclang.state.runtime;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a runtime keeps the inputs its transactions hold (ADR-0016 D7) and how they are released (ADR-0022 D8, F15):
 * the <em>designated inputs</em> of keyed operations, and the <em>claims</em> of built transactions, explicit picks and
 * handed-out attempts. Coin and collateral selection, seeds and funding picks and designations all consult it.
 * <p>
 * The default, {@link #jvm()}, is the store the runtime always had: static (shared by every runtime of the JVM that
 * uses it), with claims released at a machine-clock time. A dev host gives its session runtime its own store through
 * {@link StateRuntime.Builder#claimStore}, driven by its reservation table: it releases on chain evidence (each
 * {@link Expiry} carries the slot after which what it releases can no longer land), and names inputs no build may
 * select ({@link #avoids}). A runtime never discovers a store by itself.
 * <p>
 * Implementations must be thread-safe: a runtime may build for several payers at once. Calls for one payer address are
 * serialised by the runtime (a lock per address), but queries come from any thread.
 */
public interface ClaimStore {

    /** The default store: this JVM's static claims, released by the machine clock (ADR-0016 D7; unchanged). */
    static ClaimStore jvm() {
        return JvmClaimStore.INSTANCE;
    }

    // ------------------------------------------------------------------ designated inputs (ADR-0016 D7)

    /** Designates {@code ref} ({@code txHash#index}) for a keyed operation; false if it is designated already. */
    boolean designate(String ref);

    /** Whether {@code ref} is designated (and not yet released). */
    boolean isDesignated(String ref);

    /** Ends {@code ref}'s designation: now ({@code when} null), or at {@code when}. */
    void undesignate(String ref, Expiry when);

    // ------------------------------------------------------------------ claims

    /** Adds {@code claim} (possibly again: claims are identities), until released, or until {@code when} if not null. */
    void add(Claim claim, Expiry when);

    /** Ends {@code claim} now. */
    void remove(Claim claim);

    /** Ends {@code claim} at {@code when} (at once if that has passed). */
    void expire(Claim claim, Expiry when);

    /** The claims not yet released. */
    Collection<Claim> claims();

    // ------------------------------------------------------------------ selection

    /**
     * Inputs no transaction of this runtime selects, picks or designates (a host's reserved inputs and collateral,
     * ADR-0022 D8). An operation's own designated input stays selectable for its own attempts. None by default.
     */
    default boolean avoids(String ref) {
        return false;
    }

    /**
     * When a claim or designation may end. {@code untilMs} is the machine-clock time the runtime has always used
     * ({@link #jvm()} releases by it); {@code untilSlot} is the chain's view: the slot from which the transaction (or
     * every attempt) it guards can no longer land, before any settlement margin; {@link Long#MAX_VALUE} when no
     * validity bound limits it (an unbounded publication: the store's own policy decides), {@link Long#MIN_VALUE}
     * when nothing was handed out that could land.
     */
    record Expiry(long untilMs, long untilSlot) {
        /** A machine-clock end with no slot bound. */
        static Expiry atMs(long untilMs) {
            return new Expiry(untilMs, Long.MAX_VALUE);
        }
    }

    /**
     * The inputs ({@code txHash#index}) held by one transaction, one explicit pick, or every handed-out attempt of one
     * keyed operation. Claims are compared by identity: two claims of the same inputs are released separately (F66).
     */
    final class Claim {
        final Object owner;
        final Set<String> refs = ConcurrentHashMap.newKeySet();
        final ClaimStore store;
        /** {@link #jvm()}'s release time (machine ms); {@code Long.MAX_VALUE}: until released explicitly. */
        volatile long until = Long.MAX_VALUE;

        Claim(Object owner, ClaimStore store) {
            this.owner = owner == null ? this : owner;
            this.store = store;
        }

        /** The keyed operation whose attempts may reuse these inputs, or this claim itself (an identity, opaque). */
        public Object owner() {
            return owner;
        }

        /** Whether the owner is a keyed operation (its attempts' inputs, ADR-0016 D7), not this claim alone. */
        public boolean ofOperation() {
            return owner != this;
        }

        /** The inputs held, {@code txHash#index} (a live view). */
        public Set<String> refs() {
            return java.util.Collections.unmodifiableSet(refs);
        }

        /** Ends the claim now. */
        void release() {
            store.remove(this);
        }

        /** Ends the claim at {@code untilMs} (machine clock), with no slot bound. */
        void releaseAt(long untilMs) {
            store.expire(this, Expiry.atMs(untilMs));
        }

        /** Ends the claim at {@code when}. */
        void releaseAt(Expiry when) {
            store.expire(this, when);
        }
    }
}
