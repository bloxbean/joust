package org.julclang.state.runtime;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ClaimStore#jvm()}: the runtime's claims as they always were (ADR-0016 D7), static for the whole JVM, released
 * at machine-clock times ({@link ClaimStore.Expiry#untilMs()}). The structures and their rules are the ones
 * {@link Designation} kept before ADR-0022 made the store a seam; it avoids nothing beyond them.
 */
final class JvmClaimStore implements ClaimStore {

    static final JvmClaimStore INSTANCE = new JvmClaimStore();

    private JvmClaimStore() {}

    /** Designated inputs ({@code txHash#index}) of this JVM's keyed operations. */
    private static final Set<String> DESIGNATED = ConcurrentHashMap.newKeySet();
    /** Designated inputs kept after their operation ended pending, until this wall-clock time (ms). */
    private static final Map<String, Long> LINGERING = new ConcurrentHashMap<>();
    /** Claims of transactions built (or inputs picked) and not yet resolved; each claim is its own identity. */
    private static final Set<Claim> CLAIMS = ConcurrentHashMap.newKeySet();

    /** Drops lingering designations and claims whose time has passed. */
    void purge() {
        long now = System.currentTimeMillis();
        if (!LINGERING.isEmpty()) {
            LINGERING.forEach((ref, until) -> {
                if (until <= now && LINGERING.remove(ref, until)) DESIGNATED.remove(ref);
            });
        }
        CLAIMS.removeIf(c -> c.until <= now);
    }

    /** Nothing designated, and (when {@code withClaims}) nothing claimed: selection then keeps the provider's list. */
    boolean quiet(boolean withClaims) {
        return DESIGNATED.isEmpty() && (!withClaims || CLAIMS.isEmpty());
    }

    /** The claims as they are, without purging (the selection filter's view, as before). */
    Collection<Claim> raw() {
        return CLAIMS;
    }

    @Override
    public boolean designate(String ref) {
        return DESIGNATED.add(ref);
    }

    @Override
    public boolean isDesignated(String ref) {
        purge();
        return DESIGNATED.contains(ref);
    }

    /** Whether {@code ref} is designated, without purging (the selection filter's view, as before). */
    boolean designatedRaw(String ref) {
        return DESIGNATED.contains(ref);
    }

    @Override
    public void undesignate(String ref, Expiry when) {
        if (when == null) {
            DESIGNATED.remove(ref);
        } else if (when.untilMs() <= System.currentTimeMillis()) {
            DESIGNATED.remove(ref);
        } else {
            LINGERING.put(ref, when.untilMs());
        }
    }

    @Override
    public void add(Claim claim, Expiry when) {
        if (when != null) claim.until = when.untilMs();
        CLAIMS.add(claim);
    }

    @Override
    public void remove(Claim claim) {
        CLAIMS.remove(claim);
    }

    @Override
    public void expire(Claim claim, Expiry when) {
        if (when.untilMs() <= System.currentTimeMillis()) CLAIMS.remove(claim);
        else claim.until = when.untilMs();
    }

    @Override
    public Collection<Claim> claims() {
        purge();
        return Collections.unmodifiableSet(CLAIMS);
    }

    /** For tests: nothing designated or claimed. */
    boolean idle() {
        purge();
        return DESIGNATED.isEmpty() && CLAIMS.isEmpty();
    }
}
