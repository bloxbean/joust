package org.julclang.state.api.mpf;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class MpfLiteralsTest {
    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.testkit.JvmCryptoProvider());
    }

    @Test
    void nullSubtreeLiteralsAreTheHashesTheyClaim() {
        assertArrayEquals(Mpf.combine(Mpf.emptyRoot(), Mpf.emptyRoot()), Mpf.NULL2);
        assertArrayEquals(Mpf.combine(Mpf.NULL2, Mpf.NULL2), Mpf.NULL4);
        assertArrayEquals(Mpf.combine(Mpf.NULL4, Mpf.NULL4), Mpf.NULL8);
    }
}
