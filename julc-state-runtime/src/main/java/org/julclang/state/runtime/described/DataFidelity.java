package org.julclang.state.runtime.described;

import org.julclang.core.PlutusData;
import org.julclang.core.cbor.PlutusDataCborEncoder;

import java.util.HexFormat;

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

    private static void walk(PlutusData d, String what) {
        switch (d) {
            case PlutusData.ConstrData c -> c.fields().forEach(f -> walk(f, what));
            case PlutusData.ListData l -> l.items().forEach(i -> walk(i, what));
            case PlutusData.MapData m -> {
                byte[] previous = null;
                for (var e : m.entries()) {
                    byte[] k = PlutusDataCborEncoder.encode(e.key());
                    if (previous != null && SchemaCodec.compare(previous, k) >= 0) {
                        throw new IllegalArgumentException(what + ": a map whose keys are not in canonical order"
                                + (SchemaCodec.compare(previous, k) == 0 ? " (a duplicate key)" : "") + " cannot be encoded"
                                + " exactly (" + HexFormat.of().formatHex(k) + "; ADR-0015 I9)");
                    }
                    previous = k;
                    walk(e.key(), what);
                    walk(e.value(), what);
                }
            }
            default -> { }
        }
    }
}
