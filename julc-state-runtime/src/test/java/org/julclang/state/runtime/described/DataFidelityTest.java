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

    /**
     * #173 review: a deep raw Data parameter goes through schema decoding and the fidelity check without recursion, on a
     * small stack; the per-map key-order check still holds at the bottom.
     */
    @Test
    void deepRawDataPassesSchemaDecodingAndTheCheckOnASmallStack() throws Exception {
        int n = 50_000;
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var codec = new SchemaCodec(json.readTree("{}"));
        var anyData = json.readTree("{}");
        java.util.function.Function<String, Throwable> run = hex -> {
            var thrown = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread t = new Thread(null, () -> {
                try {
                    DataFidelity.check(codec.encode(anyData, com.fasterxml.jackson.databind.node.TextNode.valueOf(hex), "params"),
                            "the params");
                } catch (Throwable e) {
                    thrown.set(e);
                }
            }, "small-stack", 256 * 1024);
            t.start();
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return thrown.get();
        };
        assertNull(run.apply("81".repeat(n) + "a2" + "0100" + "0200"), "a deep list over an ordered map");
        assertNull(run.apply("d87981".repeat(n) + "00"), "deep constructors");
        // Keys are encoded at every level they are checked, so data nested in key position costs depth squared (as it
        // did before the walk was iterative): fewer levels, still far past what a recursive walk takes on this stack.
        assertNull(run.apply("a1".repeat(5_000) + "00" + "00".repeat(5_000)), "maps nested in key position");
        Throwable unordered = run.apply("81".repeat(n) + "a2" + "0200" + "0100");
        assertInstanceOf(IllegalArgumentException.class, unordered);
        assertTrue(unordered.getMessage().contains("not in canonical order"), unordered.getMessage());
        Throwable repeated = run.apply("81".repeat(n) + "a2" + "0100" + "0100");
        assertTrue(repeated.getMessage().contains("duplicate key"), repeated.getMessage());
    }

    /** The walk reports the first offending map in depth-first order, as the recursive walk did. */
    @Test
    void theFirstOffendingMapInDepthFirstOrderIsReported() {
        var dupIn = PlutusData.map(new PlutusData.Pair(PlutusData.integer(5), PlutusData.integer(0)),
                new PlutusData.Pair(PlutusData.integer(5), PlutusData.integer(0)));            // inner: a duplicate (05)
        var outer = PlutusData.map(new PlutusData.Pair(PlutusData.integer(1), dupIn),
                new PlutusData.Pair(PlutusData.integer(0), PlutusData.integer(0)));            // outer: out of order (00)
        var e = assertThrows(IllegalArgumentException.class, () -> DataFidelity.check(outer, "d"));
        assertTrue(e.getMessage().contains("(a duplicate key)") && e.getMessage().contains("(05;"), e.getMessage());
    }
}
