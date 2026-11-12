package org.julclang.state.variantprobe;

import org.julclang.stdlib.annotation.OnchainLibrary;

import java.math.BigInteger;

/** A project library of the test-only VariantProbe contract (ManifestsTest). */
@OnchainLibrary
public class Helper {

    public static BigInteger twice(BigInteger n) {
        return n.add(n);
    }
}
