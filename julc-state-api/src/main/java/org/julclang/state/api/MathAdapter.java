package org.julclang.state.api;

import org.julclang.state.api.Require;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.MathLib;

import java.math.BigInteger;

/**
 * Audited adapters for {@code .jsc}'s {@code std/math} (ADR-0018 D8): each checks its domain with a {@code require}
 * that fails the same way on the JVM and on-chain (the JVM's {@code BigInteger} would otherwise throw its own exception,
 * or loop for a long time), then calls the stdlib.
 */
@OnchainLibrary
public class MathAdapter {

    /** {@code a / b} rounded toward negative infinity; {@code b != 0}. */
    public static BigInteger div(BigInteger a, BigInteger b) {
        Require.that(b.compareTo(BigInteger.ZERO) != 0, "div: division by zero");
        return MathLib.floorDiv(a, b);
    }

    /** {@code a mod b}, with the sign of {@code b}; {@code b != 0}. */
    public static BigInteger mod(BigInteger a, BigInteger b) {
        Require.that(b.compareTo(BigInteger.ZERO) != 0, "mod: division by zero");
        return MathLib.floorMod(a, b);
    }

    /** {@code base} to the power {@code exp}; {@code exp} in {@code [0, 1024]} (the cost grows linearly with it). */
    public static BigInteger pow(BigInteger base, BigInteger exp) {
        Require.that(exp.compareTo(BigInteger.ZERO) >= 0 && exp.compareTo(BigInteger.valueOf(1024)) <= 0,
                "pow: the exponent must be in [0, 1024]");
        return MathLib.pow(base, exp);
    }

    /** {@code base^exp mod m}, in {@code [0, m)}; {@code exp >= 0} and {@code m > 0}. */
    public static BigInteger expMod(BigInteger base, BigInteger exp, BigInteger m) {
        Require.that(exp.compareTo(BigInteger.ZERO) >= 0, "expMod: the exponent must not be negative");
        Require.that(m.compareTo(BigInteger.ZERO) > 0, "expMod: the modulus must be positive");
        return MathLib.expMod(base, exp, m);
    }
}
