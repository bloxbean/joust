package org.julclang.state.runtime;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The JVM-wide default claim store. Designations and claims expire by machine time
 * ({@link ClaimStore.Expiry#untilMs()}). Each active designation carries its own release time so a previous
 * owner's scheduled release cannot remove a new designation of the same input.
 */
final class JvmClaimStore implements ClaimStore {

    static final JvmClaimStore INSTANCE = new JvmClaimStore();

    private JvmClaimStore() {}

    /** One entry per designation, held until its wall-clock expiry or explicit release. */
    private static final ConcurrentHashMap<String, Long> DESIGNATED = new ConcurrentHashMap<>();
    /** Claims of transactions built (or inputs picked) and not yet resolved; each claim is its own identity. */
    private static final Set<Claim> CLAIMS = ConcurrentHashMap.newKeySet();
    /**
     * Each claim's release time (machine ms); absent: {@code Long.MAX_VALUE}, until released explicitly. Kept here, not
     * on the claim (a store's own bookkeeping); weak, as the field it replaces lived and died with its claim.
     */
    private static final Map<Claim, Long> UNTIL = Collections.synchronizedMap(new WeakHashMap<>());

    private static long until(Claim c) {
        Long u = UNTIL.get(c);
        return u == null ? Long.MAX_VALUE : u;
    }

    /** Drops lingering designations and claims whose time has passed. */
    void purge() {
        long now = System.currentTimeMillis();
        DESIGNATED.entrySet().removeIf(entry -> entry.getValue() <= now);
        CLAIMS.removeIf(c -> until(c) <= now);
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
        while (true) {
            Long until = DESIGNATED.putIfAbsent(ref, Long.MAX_VALUE);
            if (until == null) return true;
            if (until > System.currentTimeMillis()) return false;
            if (DESIGNATED.replace(ref, until, Long.MAX_VALUE)) return true;
            // A concurrent purge or release changed the entry. Retry acquisition against its current state.
        }
    }

    @Override
    public boolean isDesignated(String ref) {
        purge();
        return DESIGNATED.containsKey(ref);
    }

    /** Whether {@code ref} is designated, without purging (the selection filter's view, as before). */
    boolean designatedRaw(String ref) {
        return DESIGNATED.containsKey(ref);
    }

    @Override
    public void undesignate(String ref, Expiry when) {
        if (when == null || when.untilMs() <= System.currentTimeMillis()) DESIGNATED.remove(ref);
        else DESIGNATED.replace(ref, when.untilMs());
    }

    @Override
    public void add(Claim claim, Expiry when) {
        if (when != null) UNTIL.put(claim, when.untilMs());
        CLAIMS.add(claim);
    }

    @Override
    public void remove(Claim claim) {
        CLAIMS.remove(claim);
    }

    @Override
    public void expire(Claim claim, Expiry when) {
        if (when.untilMs() <= System.currentTimeMillis()) CLAIMS.remove(claim);
        else UNTIL.put(claim, when.untilMs());
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
