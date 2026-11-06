package org.julclang.state.api;

import org.julclang.stdlib.annotation.OnchainLibrary;

import java.math.BigInteger;

/**
 * Deadline checks against the transaction's validity interval {@code [ctx.validFrom(), ctx.validTo())}.
 * They are the safe way to use time: they hold for every moment the transaction could be valid.
 */
@OnchainLibrary
public class Clock {

    /** The call happens at or after {@code t} (POSIX ms). */
    public static boolean after(Ctx ctx, BigInteger t) {
        return ctx.validFrom().compareTo(t) >= 0;
    }

    /** The call happens strictly before {@code t} (POSIX ms). */
    public static boolean before(Ctx ctx, BigInteger t) {
        return ctx.validTo().compareTo(t) <= 0;
    }
}
