package org.julclang.state.api;

import org.julclang.state.api.Assets;
import org.julclang.state.api.Require;
import org.julclang.ledger.Value;
import org.julclang.stdlib.annotation.OnchainLibrary;

import java.math.BigInteger;
import java.util.HexFormat;

/**
 * Audited adapters for {@code .jsc}'s {@code Value} (ADR-0018 D5, D8; M2): every operation the language offers on a
 * value, failing exactly where the PV11 native Value builtins fail on-chain (CIP-153, as JuLC pins them in
 * {@code NativeValueSemantics}): a quantity outside the signed 128-bit range, a token name longer than 32 bytes,
 * {@code valueContains} on a negative quantity. {@link Assets} alone does not: its JVM body normalises and never checks
 * those limits, so a value the chain refuses would pass on the JVM. Generated {@code .jsc} code calls only this class
 * for values, and the on-chain variant ({@code src/onchain/java}) calls the builtins, which fail by themselves.
 * <p>
 * Every value here is canonical (ledger order, no zero quantities, no empty token maps), as the builtins keep them,
 * so equality is equality of the canonical encodings. The JVM failures throw {@link RequireException} with the messages
 * below; on-chain the builtin fails without a message.
 */
@OnchainLibrary
public class ValueAdapter {

    private static final HexFormat HEX = HexFormat.of();

    /** The largest quantity a value holds: 2^127 - 1 (CIP-153). */
    public static final BigInteger MAX_QUANTITY = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);
    /** The smallest: -2^127. */
    public static final BigInteger MIN_QUANTITY = BigInteger.ONE.shiftLeft(127).negate();

    private static void inRange(BigInteger q, String op) {
        Require.that(q.compareTo(MAX_QUANTITY) <= 0 && q.compareTo(MIN_QUANTITY) >= 0, op + ": a quantity out of the 128-bit range");
    }

    private static void allInRange(Value v, String op) {
        Assets.read(v).values().forEach(ts -> ts.values().forEach(q -> inRange(q, op)));
    }

    /** The empty value. */
    public static Value none() {
        return Assets.none();
    }

    /** {@code q} lovelace (insertCoin: a non-zero quantity must be in range). */
    public static Value lovelace(BigInteger q) {
        if (q.signum() != 0) inRange(q, "Value.lovelace");
        return Assets.lovelace(q);
    }

    /** {@code q} of {@code (policy, name)} (insertCoin: with a non-zero quantity, the name at most 32 bytes, in range). */
    public static Value asset(byte[] policy, byte[] name, BigInteger q) {
        if (q.signum() != 0) {
            Require.that(policy.length <= 32, "Value.asset: a policy id has at most 32 bytes");
            Require.that(name.length <= 32, "Value.asset: a token name has at most 32 bytes");
            inRange(q, "Value.asset");
        }
        return Assets.of(policy, name, q);
    }

    /** {@code a + b} (unionValue: every sum in range). */
    public static Value add(Value a, Value b) {
        Value r = Assets.add(a, b);
        allInRange(r, "Value +");
        return r;
    }

    /** {@code a - b}: unionValue(a, scaleValue(-1, b)): every negated quantity of b, and every difference, in range. */
    public static Value subtract(Value a, Value b) {
        Assets.read(b).values().forEach(ts -> ts.values().forEach(q -> inRange(q.negate(), "Value -")));
        Value r = Assets.subtract(a, b);
        allInRange(r, "Value -");
        return r;
    }

    /** {@code k * v} (scaleValue: 0 gives the empty value; every product in range). */
    public static Value scale(BigInteger k, Value v) {
        if (k.signum() == 0) return Assets.none();
        Assets.read(v).values().forEach(ts -> ts.values().forEach(q -> inRange(q.multiply(k), "scale")));
        var m = Assets.read(v);
        Value out = Assets.none();
        for (var p : m.entrySet()) {
            for (var t : p.getValue().entrySet()) {
                out = Assets.add(out, Assets.of(HEX.parseHex(p.getKey()), HEX.parseHex(t.getKey()), t.getValue().multiply(k)));
            }
        }
        return out;
    }

    /** The quantity of {@code (policy, name)}, zero when absent (lookupCoin: total). */
    public static BigInteger quantity(Value v, byte[] policy, byte[] name) {
        return Assets.quantity(v, policy, name);
    }

    /** The lovelace in {@code v}. */
    public static BigInteger lovelaceOf(Value v) {
        return Assets.lovelaceOf(v);
    }

    /** Whether {@code a} holds at least {@code b} entry by entry (valueContains: fails on a negative quantity in either). */
    public static boolean covers(Value a, Value b) {
        Require.that(nonNegative(a) && nonNegative(b), "covers: a negative quantity");
        return Assets.covers(a, b);
    }

    /** Equality of two canonical values. */
    public static boolean same(Value a, Value b) {
        return Assets.read(a).equals(Assets.read(b));
    }

    public static boolean isEmpty(Value v) {
        return Assets.isEmpty(v);
    }

    public static Value withoutLovelace(Value v) {
        return Assets.withoutLovelace(v);
    }

    /**
     * Whether {@code v} may be sent (ADR-0007 D2, D2b): no negative quantity, and no reserved token name under the
     * contract's own policy ({@code policy}): the validator refuses either.
     */
    public static boolean sendable(Value v, byte[] policy) {
        String own = HEX.formatHex(policy);
        for (var p : Assets.read(v).entrySet()) {
            for (var t : p.getValue().entrySet()) {
                if (t.getValue().signum() < 0) return false;
                if (p.getKey().equals(own) && reserved(HEX.parseHex(t.getKey()))) return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code v} may be accepted (ADR-0007 D1, D2b): native assets only (no lovelace), every quantity positive, and
     * no reserved token name under the own policy.
     */
    public static boolean acceptable(Value v, byte[] policy) {
        String own = HEX.formatHex(policy);
        for (var p : Assets.read(v).entrySet()) {
            if (p.getKey().isEmpty()) return false;
            for (var t : p.getValue().entrySet()) {
                if (t.getValue().signum() <= 0) return false;
                if (p.getKey().equals(own) && reserved(HEX.parseHex(t.getKey()))) return false;
            }
        }
        return true;
    }

    /** Whether the own policy may mint {@code name}: at most 32 bytes, and not a reserved kind (ADR-0007 D3). */
    public static boolean mintable(byte[] name) {
        return name.length <= 32 && !reserved(name);
    }

    /** {@code acc} with {@code q} more of {@code (policy, name)} (insertCoin of the new total: in range, zero removes it). */
    public static Value mintAdd(Value acc, byte[] policy, byte[] name, BigInteger q) {
        BigInteger total = Assets.quantity(acc, policy, name).add(q);
        if (total.signum() != 0) inRange(total, "mint");
        return Assets.add(acc, Assets.of(policy, name, q));
    }

    /** Reserved token kinds under a contract's policy: an instance (32 bytes), an owned object or an intent (ADR-0007 D3). */
    static boolean reserved(byte[] n) {
        return n.length == 32 || (n.length == 29 && (n[0] == 0x4F || n[0] == 0x49));
    }

    private static boolean nonNegative(Value v) {
        for (var ts : Assets.read(v).values()) for (var q : ts.values()) if (q.signum() < 0) return false;
        return true;
    }
}
