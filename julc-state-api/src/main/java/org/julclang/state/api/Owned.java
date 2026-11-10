package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the contract's owned record (ADR-0003): each owned object is its own UTxO, touched only by owned methods,
 * so different objects never contend. A contract may declare several (ADR-0012 D1).
 * <p>
 * Naming a {@code byte[]} component as {@link #key()} makes the type <em>keyed</em> (ADR-0016): at most one object per
 * key and instance, kept in a sorted on-chain list. A keyed type is created by inserting next to its predecessor, needs
 * an {@code @OwnedDestroy(retire = true)} exit, and every {@code @OwnedAction} on it must be authorized.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Owned {
    /** The {@code byte[]} component that keys the object (ADR-0016 D1); empty (the default) for a plain owned type. */
    String key() default "";

    /** Longest key, in bytes: 1 to 64 (ADR-0016 D1). */
    int maxKeyBytes() default 32;

    /** The key is a 28-byte key hash that must sign the insert (ADR-0016 I53). */
    boolean signedKey() default false;

    /**
     * Least time between two relinks of a node, in POSIX ms (ADR-0016 D9): 0 (written explicitly; no churn protection)
     * or 60,000 to 3,600,000.
     */
    long linkCooldownMs() default 60_000L;

    /**
     * Widest validity window of a transaction that relinks a node, in POSIX ms (ADR-0016 D9): 20,000 to
     * {@code min(900,000, maxValidityMs)}. {@code -1} (the default) means {@code min(120,000, maxValidityMs)}.
     */
    long linkWindowMs() default -1L;
}
