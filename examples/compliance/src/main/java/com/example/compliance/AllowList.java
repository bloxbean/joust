package com.example.compliance;

import java.math.BigInteger;
import java.util.Optional;
import org.julclang.core.types.JulcList;
import org.julclang.state.api.Require;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ByteStringLib;
import org.julclang.stdlib.lib.CryptoLib;

/**
 * A Java (JuLC) on-chain library that {@code .jsc} contracts import with {@code java:} (ADR-0018 D9, M4b): an allow-list
 * of keys with a spending limit each. Its jar ships these sources under {@code META-INF/plutus-sources}; a build that
 * imports it compiles them on both sides (the JVM and JuLC) and certifies them first.
 */
@OnchainLibrary
public class AllowList {

    /** One allowed key and its limit. A record of mapped fields, without constructor logic: read-only in .jsc. */
    public record Entry(byte[] who, BigInteger limit) {}

    /** Whether {@code who} is on the list. */
    public static boolean isAllowed(JulcList<Entry> list, byte[] who) {
        return find(list, who).isPresent();
    }

    /** The entry of {@code who}, if any (a lookup by content: never JulcList.find). */
    public static Optional<Entry> find(JulcList<Entry> list, byte[] who) {
        if (list.isEmpty()) return Optional.empty();
        Entry e = list.head();
        return ByteStringLib.equals(e.who(), who) ? Optional.of(e) : find(list.tail(), who);
    }

    /** The limit of {@code who}, or zero. */
    public static BigInteger limitOf(JulcList<Entry> list, byte[] who) {
        Optional<Entry> e = find(list, who);
        return e.isPresent() ? e.get().limit() : BigInteger.ZERO;
    }

    /** A new entry; a negative limit fails with the library's message, on both sides. */
    public static Entry entry(byte[] who, BigInteger limit) {
        Require.that(limit.signum() >= 0, "a limit is never negative");
        return new Entry(who, limit);
    }

    /** The sum of every limit. */
    public static BigInteger total(JulcList<Entry> list) {
        return list.isEmpty() ? BigInteger.ZERO : list.head().limit().add(total(list.tail()));
    }

    /** {@code x} kept within [lo, hi]. */
    public static BigInteger clamp(BigInteger x, BigInteger lo, BigInteger hi) {
        return x.compareTo(lo) < 0 ? lo : x.compareTo(hi) > 0 ? hi : x;
    }

    /** A key's tag: the blake2b-224 of its bytes (a hash D8 binds). */
    public static byte[] tag(byte[] who) {
        return CryptoLib.blake2b_224(who);
    }
}
