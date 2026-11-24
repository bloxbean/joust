package org.julclang.state.runtime.described;

import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;

import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.List;

/**
 * I9 (ADR-0015 D6): every datum and redeemer the runtime builds must have the bytes of the reference encoding
 * (julc-core's, which the validator and the transition programs see). cardano-client-lib's encoding is identical for
 * every Data julc-core can make except maps: CCL writes map keys in canonical order (RFC 7049 length-first) and
 * collapses a duplicate key, while julc-core keeps the entries as given. So a map whose keys are not strictly in
 * canonical order (which includes a duplicate) is refused before anything is built; JOUST's contracts keep their maps
 * canonical ({@code SortedMaps}).
 */
public final class DataFidelity {

    private DataFidelity() {}

    /** Refuses {@code d} if it holds a map with a duplicate key anywhere; {@code what} names it in the message. */
    public static PlutusData check(PlutusData d, String what) {
        walk(d, what);
        return d;
    }

    /**
     * Visits {@code d} depth first, in order, with an explicit stack (deep data must not overflow the stack: a Web Image
     * engine cannot recover from that). Each map's keys are checked in order as the walk reaches them, before each
     * entry's key and value are visited, so the first offending map is the one the recursive walk reported.
     */
    private static void walk(PlutusData d, String what) {
        var stack = new ArrayDeque<Object>();
        stack.push(d);
        while (!stack.isEmpty()) {
            Object top = stack.pop();
            switch (top) {
                case PlutusData.ConstrData c -> pushAll(stack, c.fields());
                case PlutusData.ListData l -> pushAll(stack, l.items());
                case PlutusData.MapData m -> {
                    if (!m.entries().isEmpty()) stack.push(new MapCursor(m.entries()));
                }
                case MapCursor cursor -> {
                    var e = cursor.entries.get(cursor.index++);
                    byte[] k = PlutusDataCborEncoder.encode(e.key());
                    byte[] previous = cursor.previous;
                    if (previous != null && SchemaCodec.compare(previous, k) >= 0) {
                        throw new IllegalArgumentException(what + ": a map whose keys are not in canonical order"
                                + (SchemaCodec.compare(previous, k) == 0 ? " (a duplicate key)" : "") + " cannot be encoded"
                                + " exactly (" + HexFormat.of().formatHex(k) + "; ADR-0015 I9)");
                    }
                    cursor.previous = k;
                    // The entry's key, then its value, then the map's next entry.
                    if (cursor.index < cursor.entries.size()) stack.push(cursor);
                    stack.push(e.value());
                    stack.push(e.key());
                }
                default -> { }
            }
        }
    }

    /** The next entry of a map being walked, and the encoding of the key before it. */
    private static final class MapCursor {
        final List<PlutusData.Pair> entries;
        int index;
        byte[] previous;

        MapCursor(List<PlutusData.Pair> entries) {
            this.entries = entries;
        }
    }

    private static void pushAll(ArrayDeque<Object> stack, List<PlutusData> items) {
        for (int i = items.size() - 1; i >= 0; i--) stack.push(items.get(i));
    }
}
