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

    /** The byte at {@code index} (0–255); {@code index} in {@code [0, 2^31)}, and it fails past the end on both sides. */
    public static BigInteger at(byte[] bs, BigInteger index) {
        Require.that(index.compareTo(BigInteger.ZERO) >= 0 && index.compareTo(BigInteger.valueOf(2147483648L)) < 0,
                "at: index must be in [0, 2^31)");
        return BigInteger.valueOf(ByteStringLib.at(bs, index.longValue()));
    }
}
