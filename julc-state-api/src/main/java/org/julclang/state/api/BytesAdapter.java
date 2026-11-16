package org.julclang.state.api;

import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ByteStringLib;

import java.math.BigInteger;

/**
 * Audited adapters from {@code .jsc}'s unbounded {@code Int} to the stdlib's fixed-width byte-string parameters
 * (ADR-0018 D8). Each checks its domain with a {@code require} that fails the same way on the JVM and on-chain, and only
 * then narrows: inside {@code 0 <= n < 2^31} the JVM's {@code longValue()} and JuLC's (the identity) agree, so no
 * argument can wrap (F18). Generated code passes only {@code BigInteger}s and never narrows itself.
 */
@OnchainLibrary
public class BytesAdapter {

    /** {@code length} bytes of {@code bs} from {@code start}; both in {@code [0, 2^31)}. Clamps like the builtin. */
    public static byte[] slice(byte[] bs, BigInteger start, BigInteger length) {
        Require.that(start.compareTo(BigInteger.ZERO) >= 0 && start.compareTo(BigInteger.valueOf(2147483648L)) < 0,
                "slice: start must be in [0, 2^31)");
        Require.that(length.compareTo(BigInteger.ZERO) >= 0 && length.compareTo(BigInteger.valueOf(2147483648L)) < 0,
                "slice: length must be in [0, 2^31)");
        return ByteStringLib.slice(bs, start.longValue(), length.longValue());
    }

    /** The first {@code n} bytes of {@code bs} (all of it when shorter); {@code n} in {@code [0, 2^31)}. */
    public static byte[] take(byte[] bs, BigInteger n) {
        Require.that(n.compareTo(BigInteger.ZERO) >= 0 && n.compareTo(BigInteger.valueOf(2147483648L)) < 0,
                "take: n must be in [0, 2^31)");
        return ByteStringLib.take(bs, n.longValue());
    }

    /**
     * {@code bs} without its first {@code n} bytes (empty when shorter); {@code n} in {@code [0, 2^31)}. Inside that
     * domain the stdlib's {@code length - n} cannot wrap (F18).
     */
    public static byte[] drop(byte[] bs, BigInteger n) {
        Require.that(n.compareTo(BigInteger.ZERO) >= 0 && n.compareTo(BigInteger.valueOf(2147483648L)) < 0,
                "drop: n must be in [0, 2^31)");
        return ByteStringLib.drop(bs, n.longValue());
    }

    /**
     * The byte at {@code index} (0–255); {@code index} in {@code [0, 2^31)} and below the length. Past the end it fails
     * with this adapter's {@code require} on both sides (the JVM's stdlib would throw an index exception instead).
     */
    public static BigInteger at(byte[] bs, BigInteger index) {
        Require.that(index.compareTo(BigInteger.ZERO) >= 0 && index.compareTo(BigInteger.valueOf(2147483648L)) < 0,
                "at: index must be in [0, 2^31)");
        Require.that(index.compareTo(BigInteger.valueOf(ByteStringLib.length(bs))) < 0, "at: index past the end");
        return BigInteger.valueOf(ByteStringLib.at(bs, index.longValue()));
    }
}
