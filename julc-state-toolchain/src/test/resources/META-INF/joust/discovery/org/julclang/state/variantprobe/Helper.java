package org.julclang.state.variantprobe;

import org.julclang.stdlib.annotation.OnchainLibrary;

import java.math.BigInteger;

/** A transition-program variant of a library that is not on the allowlist: the toolchain must refuse it (S5). */
@OnchainLibrary
public class Helper {

    public static BigInteger twice(BigInteger n) {
        return n.add(n).add(BigInteger.ONE);
    }
}
