package org.julclang.state.runtime;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a runtime keeps the inputs its transactions hold (ADR-0016 D7) and how they are released (ADR-0022 D8, F15):
 * the <em>designated inputs</em> of keyed operations, and the <em>claims</em> of built transactions, explicit picks and
 * handed-out attempts.
 * <ul>
 *   <li><b>Reservations go through {@link #avoids}.</b> An input the store avoids is never selected (coins, collateral,
 *       seeds, funding picks, native-asset inputs), never designated, and never spent or used as collateral by any
 *       transaction this runtime builds: every final input, script and state inputs included, is checked under the
 *       payer's build lock, and a hit refuses the build ({@link ReservedInputException}). The one exception is an
 *       operation's own designated input, when {@link #reusable} says the reservation is that operation's.</li>
 *   <li><b>Claims</b> ({@link #claims()}) keep inputs out of <em>keyed attempts'</em> selection and designation only;
 *       plain transactions select around designations and {@link #avoids} alone (as before ADR-0016 M2). A store must
 *       not rely on a claim to protect a reservation.</li>
 * </ul>
 * <p>
 * The default is the store the runtime always had: static (shared by every runtime of the JVM that uses it), with
 * claims released at a machine-clock time. It is the runtime's own: no public method hands it out, so code outside the
 * runtime cannot end another runtime's claims or designations through it (#145 Codex review). A dev host gives its session runtime its own store through
 * {@link StateRuntime.Builder#claimStore}, driven by its reservation table: it releases on chain evidence (each
 * {@link Expiry} carries the slot after which what it releases can no longer land), and names inputs no build may
 * select ({@link #avoids}). A runtime never discovers a store by itself.
 * <p>
 * Implementations must be thread-safe: a runtime may build for several payers at once. Calls for one payer address are
 * serialised by the runtime (a lock per address), but queries come from any thread.
 */
public interface ClaimStore {

    /**
     * The key every method here names an input by: the lowercase hex of the transaction id's bytes, {@code #}, the output
     * index. A provider's spelling (upper case, a leading {@code 0x}) is the same input under this key; a host that keeps
     * its own table of reservations keys it this way, or a reservation it holds never matches the runtime's query.
     */
    static String ref(String txHash, int index) {
        return Designation.txKey(txHash) + "#" + index;
    }

    // ------------------------------------------------------------------ designated inputs (ADR-0016 D7)

    /** Designates {@code ref} (a {@link #ref} key) for a keyed operation; false if it is designated already. */
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
     * Inputs no transaction of this runtime selects, picks, designates, spends or uses as collateral (a host's reserved
     * inputs and collateral, ADR-0022 D8). Plain transactions skip reservations and designations. Only keyed attempts
     * also exclude other owners' claims. None by default.
     */
    default boolean avoids(String ref) {
        return false;
    }

    /**
     * Whether a reserved transaction of this store, not yet final, creates, spends or burns the token {@code unit}
     * (policy ++ name, lowercase hex): a key it inserts may still be rolled back, so a second insert of that key waits
     * instead of failing for good (ADR-0022 D11, #156 Codex review). False by default.
     */
    default boolean reservesToken(String unit) {
        return false;
    }

    /**
     * Whether {@code ref}, an input this store {@linkplain #avoids avoids}, is reserved for the operation the current
     * call retries (ADR-0022 D11). A host matches the persisted operation record for this call, including its instance
     * and key, before granting reuse. The input reference alone does not identify the operation. False by default: an
     * avoided input is then never designated or spent.
     */
    default boolean reusable(String ref) {
        return false;
    }

    /**
     * When a claim or designation may end. {@code untilMs} is the machine-clock time the runtime has always used
     * (the default store releases by it; it includes the runtime's own margins). {@code untilSlot} is the chain's view,
     * always without any settlement margin: the exclusive validity upper bound of the transaction it guards (a
     * prepared transaction's {@code ttl}, an attempt's window end), or of the last attempt of a keyed operation; a
     * store applies its own settlement margin once (ADR-0022 D8: releasable once a tip passes
     * {@code untilSlot + settlementSlots}). {@link Long#MAX_VALUE} when no validity bound limits it (an unbounded
     * publication: the store's own policy decides), {@link Long#MIN_VALUE} when nothing was handed out that could land.
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
        /** The store the claim was made in, which its release goes to (fixed; a store keeps its own release times). */
        private final ClaimStore store;

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
