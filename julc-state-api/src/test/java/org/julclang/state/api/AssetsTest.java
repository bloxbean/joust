package org.julclang.state.api;

import org.julclang.core.PlutusData;
import org.julclang.ledger.Value;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The JVM body of Assets: canonical results, as the on-chain native builtins produce (ADR-0007). */
class AssetsTest {
    static final byte[] P1 = new byte[28];
    static final byte[] P2 = filled(0xFF);

    static byte[] filled(int b) {
        var a = new byte[28];
        java.util.Arrays.fill(a, (byte) b);
        return a;
    }

    @Test
    void sumsAreCanonical_sortedAndWithoutZeros() {
        Value v = Assets.add(Assets.of(P2, new byte[]{2}, BigInteger.ONE), Assets.of(P1, new byte[]{9}, BigInteger.TWO));
        v = Assets.add(v, Assets.lovelace(BigInteger.TEN));
        v = Assets.add(v, Assets.of(P2, new byte[]{1}, BigInteger.ONE));
        Assets.check(v, "sum");
        var d = v.toPlutusData();
        assertEquals(0, ((PlutusData.BytesData) d.entries().get(0).key()).value().length, "lovelace first");
        assertEquals(3, d.entries().size());
        Value cancelled = Assets.subtract(v, Assets.of(P1, new byte[]{9}, BigInteger.TWO));
        Assets.check(cancelled, "difference");
        assertEquals(BigInteger.ZERO, Assets.quantity(cancelled, P1, new byte[]{9}));
        assertEquals(2, cancelled.toPlutusData().entries().size(), "a cancelled entry vanishes with its policy");
    }

    @Test
    void coversAndHelpers() {
        Value v = Assets.add(Assets.lovelace(BigInteger.valueOf(5)), Assets.of(P1, new byte[]{1}, BigInteger.valueOf(3)));
        assertTrue(Assets.covers(v, Assets.of(P1, new byte[]{1}, BigInteger.TWO)));
        assertFalse(Assets.covers(v, Assets.of(P1, new byte[]{1}, BigInteger.valueOf(4))));
        assertThrows(IllegalArgumentException.class, () -> Assets.covers(v, Assets.of(P1, new byte[]{1}, BigInteger.ONE.negate())));
        assertEquals(BigInteger.valueOf(5), Assets.lovelaceOf(v));
        assertEquals(BigInteger.ZERO, Assets.lovelaceOf(Assets.withoutLovelace(v)));
        assertTrue(Assets.isEmpty(Assets.subtract(v, v)));
        assertTrue(Assets.isEmpty(Assets.of(P1, new byte[]{1}, BigInteger.ZERO)));
    }

    @Test
    void checkRejectsNonCanonicalValues() {
        Value unsorted = Value.fromPlutusData(new PlutusData.MapData(java.util.List.of(
                new PlutusData.Pair(new PlutusData.BytesData(P2), new PlutusData.MapData(java.util.List.of(
                        new PlutusData.Pair(new PlutusData.BytesData(new byte[]{1}), new PlutusData.IntData(BigInteger.ONE))))),
                new PlutusData.Pair(new PlutusData.BytesData(P1), new PlutusData.MapData(java.util.List.of(
                        new PlutusData.Pair(new PlutusData.BytesData(new byte[]{1}), new PlutusData.IntData(BigInteger.ONE))))))));
        assertThrows(IllegalArgumentException.class, () -> Assets.check(unsorted, "x"));
    }
}
