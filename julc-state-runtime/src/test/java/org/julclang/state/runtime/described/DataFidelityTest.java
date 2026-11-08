package org.julclang.state.runtime.described;

import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-0015 I9: julc-core's encoding is the reference; CCL's matches it except for duplicate map keys, which are refused. */
class DataFidelityTest {

    static String julc(PlutusData d) {
        return HexFormat.of().formatHex(PlutusDataCborEncoder.encode(d));
    }

    static String ccl(PlutusData d) {
        return PlutusDataAdapter.toClientLib(d).serializeToHex();
    }

    @Test
    void positiveFixturesEncodeIdentically() {
        byte[] long100 = new byte[100];
        for (int i = 0; i < long100.length; i++) long100[i] = (byte) i;
        List<PlutusData> fixtures = List.of(
                PlutusData.constr(0),
                PlutusData.list(),
                PlutusData.map(),
                PlutusData.bytes(new byte[0]),
                PlutusData.bytes(long100),                                          // chunked over 64 bytes
                PlutusData.integer(BigInteger.TWO.pow(64)),                         // bignum
                PlutusData.integer(BigInteger.TWO.pow(600).negate()),
                PlutusData.constr(200, PlutusData.integer(1)),                      // a large tag
                PlutusData.constr(7, PlutusData.integer(-1)),
                PlutusData.map(new PlutusData.Pair(PlutusData.bytes(new byte[]{1}), PlutusData.list(PlutusData.constr(1))),
                        new PlutusData.Pair(PlutusData.bytes(new byte[]{2}), PlutusData.integer(1)),
                        new PlutusData.Pair(PlutusData.bytes(new byte[]{0, 0}), PlutusData.integer(2))));   // canonical: length first
        for (PlutusData d : fixtures) {
            assertEquals(julc(d), ccl(d), "encoding of " + d);
            assertSame(d, DataFidelity.check(d, "fixture"));
        }
    }

    @Test
    void duplicateMapKeysAreRefused() {
        var dup = PlutusData.map(new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(2)),
                new PlutusData.Pair(PlutusData.integer(1), PlutusData.integer(3)));
        assertEquals("a201020103", julc(dup));
        assertNotEquals(julc(dup), ccl(dup), "CCL collapses the duplicate");
        var e = assertThrows(IllegalArgumentException.class, () -> DataFidelity.check(PlutusData.constr(0, PlutusData.list(dup)), "the datum"));
        assertTrue(e.getMessage().contains("duplicate key"), e.getMessage());
    }

    @Test
    void mapsOutOfCanonicalOrderAreRefused() {
        var unsorted = PlutusData.map(new PlutusData.Pair(PlutusData.bytes(new byte[]{2}), PlutusData.integer(1)),
                new PlutusData.Pair(PlutusData.bytes(new byte[]{1}), PlutusData.integer(2)));
        assertNotEquals(julc(unsorted), ccl(unsorted), "CCL sorts the keys");
        var e = assertThrows(IllegalArgumentException.class, () -> DataFidelity.check(unsorted, "the datum"));
        assertTrue(e.getMessage().contains("canonical order"), e.getMessage());
    }
}
