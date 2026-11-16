package org.julclang.state.api;

import org.julclang.state.api.MerkleMap;
import org.julclang.state.api.MapEntry;
import org.julclang.state.api.Require;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.mpf.Mpf;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ByteStringLib;

import java.math.BigInteger;

/**
 * Reading and changing a {@link MerkleMap} (ADR-0002 D2). A lookup sees this call's writes (latest first), then its
 * read set. Writes add to the map's pending writes; the root never changes inside a method.
 * <p>
 * On-chain variant (bundled for JuLC in place of the JVM source, which differs only below the marker line).
 */
@OnchainLibrary
public class MerkleMaps {

    public static MerkleMap empty() {
        JulcList<MapEntry> none = JulcList.empty();
        return new MerkleMap(Mpf.emptyRoot(), none, none);
    }

    /** True if the map was empty before this call and this call wrote nothing (use before destroying an instance). */
    public static boolean isEmpty(MerkleMap m) {
        return ByteStringLib.equals(m.root(), Mpf.emptyRoot()) && m.writes().isEmpty();
    }

    public static boolean contains(MerkleMap m, byte[] key) {
        MapEntry e = lookup(m, key);
        return e.present();
    }

    public static byte[] get(MerkleMap m, byte[] key, byte[] otherwise) {
        MapEntry e = lookup(m, key);
        return e.present() ? e.value() : otherwise;
    }

    public static BigInteger getInt(MerkleMap m, byte[] key, BigInteger otherwise) {
        MapEntry e = lookup(m, key);
        return e.present() ? Builtins.byteStringToInteger(true, e.value()) : otherwise;
    }

    public static MerkleMap put(MerkleMap m, byte[] key, byte[] value) {
        return new MerkleMap(m.root(), m.reads(), m.writes().prepend(new MapEntry(key, true, value)));
    }

    /** Stores a non-negative integer (as its minimal big-endian bytes; zero is the empty byte string). */
    public static MerkleMap putInt(MerkleMap m, byte[] key, BigInteger value) {
        Require.that(value.compareTo(BigInteger.ZERO) >= 0, "MerkleMaps.putInt: value must not be negative");
        return put(m, key, ByteStringLib.integerToByteString(true, 0, value));
    }

    /**
     * A {@code BigMap<K, Int>}'s {@code set} (ADR-0018 D7, M3a): a non-negative integer, where 0 removes the key, as
     * {@link #remove} does, and any other value is stored as {@link #putInt} stores it; one write, without a branch.
     */
    public static MerkleMap setInt(MerkleMap m, byte[] key, BigInteger value) {
        Require.that(value.compareTo(BigInteger.ZERO) >= 0, "BigMap.set: a value cannot be negative");
        return new MerkleMap(m.root(), m.reads(), m.writes().prepend(new MapEntry(key, value.compareTo(BigInteger.ZERO) > 0,
                ByteStringLib.integerToByteString(true, 0, value))));
    }

    public static MerkleMap remove(MerkleMap m, byte[] key) {
        return new MerkleMap(m.root(), m.reads(), m.writes().prepend(new MapEntry(key, false, ByteStringLib.empty())));
    }

    /** The entry for {@code key}: this call's latest write, else the read set. */
    public static MapEntry lookup(MerkleMap m, byte[] key) {
        boolean found = false;
        MapEntry hit = new MapEntry(key, false, ByteStringLib.empty());
        for (var w : m.writes()) {
            if (!found && ByteStringLib.equals(w.key(), key)) {
                found = true;
                hit = w;
            }
        }
        for (var r : m.reads()) {
            if (!found && ByteStringLib.equals(r.key(), key)) {
                found = true;
                hit = r;
            }
        }
        if (found) {
            return hit;
        } else {
            // The empty root holds no key: absent without a proof (lets @Init read the maps it creates).
            return ByteStringLib.equals(m.root(), Mpf.emptyRoot()) ? hit : missing(m, key);
        }
    }

    /** The latest write of each key, latest first (both sides apply net writes in this order). */
    public static JulcList<MapEntry> netWrites(MerkleMap m) {
        JulcList<MapEntry> firsts = JulcList.empty();
        for (var w : m.writes()) {
            boolean seen = false;
            for (var f : firsts) {
                if (ByteStringLib.equals(f.key(), w.key())) {
                    seen = true;
                }
            }
            if (!seen) {
                firsts = firsts.prepend(w);
            }
        }
        return firsts.reverse();
    }

    // ---- JVM-only below this line (the on-chain source differs here) ----

    /** On-chain: a key outside the read set (and not written in this call) fails the script (ADR-0002 D3). */
    static MapEntry missing(MerkleMap m, byte[] key) {
        Builtins.error();
        return new MapEntry(key, false, ByteStringLib.empty());
    }
}
