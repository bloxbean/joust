package org.julclang.state.api;

import org.julclang.core.PlutusData;
import org.julclang.ledger.Value;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Multi-asset values for contract code (ADR-0007): the same results on the JVM and on-chain.
 * <p>
 * Every value these functions return is canonical, as the ledger writes values: policies and token names in ascending
 * byte order, no zero quantities, no empty token maps. On-chain they use the PV11 native Value builtins, which reject
 * a non-canonical argument; values from the ledger and from this class always are canonical. The JVM body here
 * normalises instead, so a JVM test never passes on a value the chain would reject: {@link #check} is what the
 * runtime runs on every value a method returns.
 * <p>
 * Lovelace is the entry with the empty policy and the empty token name.
 */
public class Assets {

    private static final HexFormat HEX = HexFormat.of();

    /** The empty value. */
    public static Value none() {
        return Value.zero();
    }

    /** {@code quantity} of {@code (policy, name)}; empty if the quantity is zero. */
    public static Value of(byte[] policy, byte[] name, BigInteger quantity) {
        var m = new TreeMap<String, TreeMap<String, BigInteger>>();
        put(m, HEX.formatHex(policy), HEX.formatHex(name), quantity);
        return build(m);
    }

    /** {@code quantity} lovelace. */
    public static Value lovelace(BigInteger quantity) {
        return of(new byte[0], new byte[0], quantity);
    }

    /** Entry-wise sum; zero sums vanish. */
    public static Value add(Value a, Value b) {
        var m = read(a);
        read(b).forEach((p, ts) -> ts.forEach((t, q) -> put(m, p, t, q)));
        return build(m);
    }

    /** Entry-wise difference {@code a - b}; zero differences vanish (negative ones stay). */
    public static Value subtract(Value a, Value b) {
        var m = read(a);
        read(b).forEach((p, ts) -> ts.forEach((t, q) -> put(m, p, t, q.negate())));
        return build(m);
    }

    /** The quantity of {@code (policy, name)} in {@code v}, zero if absent. */
    public static BigInteger quantity(Value v, byte[] policy, byte[] name) {
        var ts = read(v).get(HEX.formatHex(policy));
        if (ts == null) return BigInteger.ZERO;
        return ts.getOrDefault(HEX.formatHex(name), BigInteger.ZERO);
    }

    /** The lovelace in {@code v}. */
    public static BigInteger lovelaceOf(Value v) {
        return quantity(v, new byte[0], new byte[0]);
    }

    /** {@code v} without its lovelace. */
    public static Value withoutLovelace(Value v) {
        var m = read(v);
        m.remove("");
        return build(m);
    }

    /** True if {@code v} holds nothing. */
    public static boolean isEmpty(Value v) {
        return read(v).isEmpty();
    }

    /**
     * True if {@code a} holds at least {@code b} of every entry. Both must be non-negative: on-chain a negative
     * quantity fails the script, so here it throws.
     */
    public static boolean covers(Value a, Value b) {
        var ma = read(a);
        var mb = read(b);
        nonNegative(ma);
        nonNegative(mb);
        for (var p : mb.entrySet()) {
            for (var t : p.getValue().entrySet()) {
                BigInteger have = ma.getOrDefault(p.getKey(), new TreeMap<>()).getOrDefault(t.getKey(), BigInteger.ZERO);
                if (have.compareTo(t.getValue()) < 0) return false;
            }
        }
        return true;
    }

    /**
     * JVM only: throws unless {@code v} is canonical (sorted, no zero quantities, no empty token maps, keys at most 32
     * bytes). The chain rejects any other value, so the runtime and the harness check every value a method returns.
     */
    public static void check(Value v, String what) {
        var d = v.toPlutusData();
        String prevPolicy = null;
        for (var e : d.entries()) {
            String p = HEX.formatHex(((PlutusData.BytesData) e.key()).value());
            if (p.length() > 64) throw new IllegalArgumentException(what + ": policy id longer than 32 bytes");
            if (prevPolicy != null && prevPolicy.compareTo(p) >= 0) {
                throw new IllegalArgumentException(what + ": value not canonical (policies out of order or repeated)");
            }
            prevPolicy = p;
            var tokens = ((PlutusData.MapData) e.value()).entries();
            if (tokens.isEmpty()) throw new IllegalArgumentException(what + ": value has an empty token map");
            String prevToken = null;
            for (var t : tokens) {
                String n = HEX.formatHex(((PlutusData.BytesData) t.key()).value());
                if (n.length() > 64) throw new IllegalArgumentException(what + ": token name longer than 32 bytes");
                if (prevToken != null && prevToken.compareTo(n) >= 0) {
                    throw new IllegalArgumentException(what + ": value not canonical (tokens out of order or repeated)");
                }
                prevToken = n;
                if (((PlutusData.IntData) t.value()).value().signum() == 0) {
                    throw new IllegalArgumentException(what + ": value has a zero quantity");
                }
            }
        }
    }

    // ------------------------------------------------------------------ JVM representation

    /** Lower-case hex orders like unsigned bytes, so a TreeMap keyed by hex is in ledger order. */
    /** JVM only: the entries of {@code v} by policy and token name, both as lower-case hex, in ledger order. */
    public static TreeMap<String, TreeMap<String, BigInteger>> read(Value v) {
        var m = new TreeMap<String, TreeMap<String, BigInteger>>();
        for (var e : v.toPlutusData().entries()) {
            String p = HEX.formatHex(((PlutusData.BytesData) e.key()).value());
            for (var t : ((PlutusData.MapData) e.value()).entries()) {
                put(m, p, HEX.formatHex(((PlutusData.BytesData) t.key()).value()), ((PlutusData.IntData) t.value()).value());
            }
        }
        return m;
    }

    private static void put(TreeMap<String, TreeMap<String, BigInteger>> m, String p, String t, BigInteger q) {
        var ts = m.computeIfAbsent(p, k -> new TreeMap<>());
        BigInteger sum = ts.getOrDefault(t, BigInteger.ZERO).add(q);
        if (sum.signum() == 0) ts.remove(t);
        else ts.put(t, sum);
        if (ts.isEmpty()) m.remove(p);
    }

    static Value build(TreeMap<String, TreeMap<String, BigInteger>> m) {
        List<PlutusData.Pair> outer = new ArrayList<>();
        for (Map.Entry<String, TreeMap<String, BigInteger>> p : m.entrySet()) {
            List<PlutusData.Pair> inner = new ArrayList<>();
            for (var t : p.getValue().entrySet()) {
                inner.add(new PlutusData.Pair(new PlutusData.BytesData(HEX.parseHex(t.getKey())),
                        new PlutusData.IntData(t.getValue())));
            }
            outer.add(new PlutusData.Pair(new PlutusData.BytesData(HEX.parseHex(p.getKey())),
                    new PlutusData.MapData(inner)));
        }
        return Value.fromPlutusData(new PlutusData.MapData(outer));
    }

    private static void nonNegative(TreeMap<String, TreeMap<String, BigInteger>> m) {
        m.values().forEach(ts -> ts.values().forEach(q -> {
            if (q.signum() < 0) throw new IllegalArgumentException("Assets.covers: negative quantity");
        }));
    }
}
