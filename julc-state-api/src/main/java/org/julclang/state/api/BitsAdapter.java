package org.julclang.state.api;

import org.julclang.state.api.Require;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.BitwiseLib;
import org.julclang.stdlib.lib.ByteStringLib;

import java.math.BigInteger;

/**
 * Audited adapters for {@code .jsc}'s {@code std/bits} (ADR-0018 D8): each checks its domain with a {@code require} on
 * both sides, then narrows (inside the domain the JVM's {@code longValue()} and JuLC's agree) and calls the stdlib.
 */
@OnchainLibrary
public class BitsAdapter {

    /** Bit {@code index} of {@code bs} (bit 0 is the last byte's lowest); {@code index} in {@code [0, 8 * length)}. */
    public static boolean readBit(byte[] bs, BigInteger index) {
        Require.that(index.compareTo(BigInteger.ZERO) >= 0 && index.compareTo(BigInteger.valueOf(2147483648L)) < 0,
                "readBit: index must be in [0, 2^31)");
        Require.that(index.compareTo(BigInteger.valueOf(ByteStringLib.length(bs)).multiply(BigInteger.valueOf(8))) < 0,
                "readBit: index past the end");
        return BitwiseLib.readBit(bs, index.longValue());
    }

    /** {@code bs} shifted by {@code n} bits (positive: left), zero bits filling; {@code n} in {@code (-2^31, 2^31)}. */
    public static byte[] shift(byte[] bs, BigInteger n) {
        Require.that(n.compareTo(BigInteger.valueOf(-2147483648L)) > 0 && n.compareTo(BigInteger.valueOf(2147483648L)) < 0,
                "shift: n must be in (-2^31, 2^31)");
        return BitwiseLib.shiftByteString(bs, n.longValue());
    }

    /** {@code bs} rotated by {@code n} bits (positive: left); {@code n} in {@code (-2^31, 2^31)}. */
    public static byte[] rotate(byte[] bs, BigInteger n) {
        Require.that(n.compareTo(BigInteger.valueOf(-2147483648L)) > 0 && n.compareTo(BigInteger.valueOf(2147483648L)) < 0,
                "rotate: n must be in (-2^31, 2^31)");
        return BitwiseLib.rotateByteString(bs, n.longValue());
    }
}
