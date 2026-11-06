package org.julclang.state.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a JOUST state contract: a state record plus pure {@link Init}, {@link Action},
 * {@link Destroy} and {@link View} methods. The JOUST processor generates {@code <Name>Validator} (a JuLC
 * {@code @MultiValidator}) and {@code <Name>Client} in the same package.
 * <p>
 * Until JuLC accepts annotations meta-annotated with {@code @OnchainLibrary}, the class must also carry
 * {@code @OnchainLibrary}. See ADR-0001.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface StateContract {
    /**
     * Lovelace locked in the state UTxO as its min-ADA deposit, on top of the contract's funds. {@code 0} (the
     * default) derives it from {@link #maxDatumBytes()}: {@code 4310 * (160 + 200 + maxDatumBytes)}, rounded up to
     * 0.1 ADA. An explicit value below that is a compile error.
     */
    long minAda() default 0L;

    /**
     * Largest serialised state the validator accepts. A transition that would grow the datum past it is rejected,
     * so no call can push the state UTxO beyond what {@link #minAda()} covers and brick the instance.
     */
    int maxDatumBytes() default 512;

    /**
     * Widest validity interval ({@code validTo - validFrom}, POSIX ms) a call that reads {@link Ctx} may use. Bounds
     * how stale or how far ahead {@code ctx.validFrom()} / {@code ctx.validTo()} can be.
     */
    long maxValidityMs() default 3_600_000L;
}
