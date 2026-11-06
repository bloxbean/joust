package org.julclang.state.api;

import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ByteStringLib;

import java.math.BigInteger;

/**
 * Maps with {@code byte[]} keys kept in canonical CBOR key order (shorter key first, then byte-wise), on the JVM and
 * on-chain alike. Use them for maps stored in the state.
 * <p>
 * Why: a {@code JulcMap} is an ordered association list and {@code insert} prepends, but cardano-client-lib writes
 * inline datums in canonical CBOR, which sorts map keys. A state map whose entries are not already in canonical
 * order would be written differently from what the validator recomputes, and the call would fail on-chain. The
 * runtime refuses to submit such a state (with a message pointing here).
 */
@OnchainLibrary
public class SortedMaps {

    /** True if {@code a} sorts before {@code b} in canonical CBOR order. */
    public static boolean before(byte[] a, byte[] b) {
        long la = ByteStringLib.length(a);
        long lb = ByteStringLib.length(b);
        return la < lb || (la == lb && ByteStringLib.lessThan(a, b));
    }

    /** {@code m} with {@code k} set to {@code v}, keeping canonical order. */
    public static JulcMap<byte[], BigInteger> put(JulcMap<byte[], BigInteger> m, byte[] k, BigInteger v) {
        JulcMap<byte[], BigInteger> rest = m.delete(k);
        JulcList<byte[]> keys = rest.keys().reverse();
        JulcMap<byte[], BigInteger> out = JulcMap.empty();
        boolean placed = false;
        for (var key : keys) {                       // largest first; prepending builds ascending order
            if (!placed && before(key, k)) {
                out = out.insert(k, v);
                placed = true;
            }
            out = out.insert(key, rest.get(key));
        }
        return placed ? out : out.insert(k, v);   // k is the smallest key
    }

    /** {@code m} without {@code k} (order is kept). */
    public static JulcMap<byte[], BigInteger> remove(JulcMap<byte[], BigInteger> m, byte[] k) {
        return m.delete(k);
    }

    /** The value for {@code k}, or {@code otherwise}. */
    public static BigInteger getOr(JulcMap<byte[], BigInteger> m, byte[] k, BigInteger otherwise) {
        return m.containsKey(k) ? m.get(k) : otherwise;
    }
}
