package org.julclang.state.api;

import org.julclang.state.api.Assets;
import org.julclang.state.api.Require;
import org.julclang.core.PlutusData;
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
 * <b>Precondition: every argument is canonical</b> (ledger order, no zero quantities, no empty token maps, no repeated
 * key), as the builtins keep every value they return, so equality is equality of the canonical encodings. The arguments
 * generated code passes are: the results of this class, the instance's held value (the ledger's), and what the caller
 * offers, which reaches a method only through {@link #fromWire} (the validator and the transition program convert it).
 * On a non-canonical argument the two bodies differ: the JVM's normalises ({@link Assets#read}) where the builtins fail
 * or compare structurally; the differential test ({@code ValueAdapterDifferentialTest}) covers canonical inputs, and
 * {@link #fromWire}'s refusals of non-canonical ones. The JVM failures throw {@link RequireException} with the messages
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

    /**
     * A value as it arrives encoded in a redeemer or an argument ("the wire": the offered assets, a transition program's
     * funds) as a canonical value. cardano-client-lib writes a map's keys in canonical CBOR order (shorter encodings
     * first), so the token names of one policy may come in another order than the ledger's (unsigned bytes: {@code 00ff}
     * before {@code 01}); the native builtins refuse such a value. Token names may come in any order; refused (as the
     * on-chain fold refuses them): a repeated token name of a policy, a zero quantity, an empty token map, policies not in
     * strictly ascending order (both orders agree on policies: lovelace's is empty, every other has 28 bytes), a policy or
     * token name over 32 bytes, a quantity out of the 128-bit range (review r1, C1).
     */
    public static Value fromWire(Value v) {
        wire(v.toPlutusData(), "fromWire");
        return Assets.build(Assets.read(v));
    }

    /** {@link #fromWire}'s rules on the data's own entries, in their own order (nothing normalised before the check). */
    private static void wire(PlutusData d, String op) {
        Require.that(d instanceof PlutusData.MapData, op + ": not a map");
        String prevPolicy = null;
        for (var e : ((PlutusData.MapData) d).entries()) {
            Require.that(e.key() instanceof PlutusData.BytesData && e.value() instanceof PlutusData.MapData, op + ": not a value");
            String p = HEX.formatHex(((PlutusData.BytesData) e.key()).value());
            Require.that(prevPolicy == null || prevPolicy.compareTo(p) < 0, op + ": policies out of order or repeated");
            prevPolicy = p;
            var tokens = ((PlutusData.MapData) e.value()).entries();
            Require.that(!tokens.isEmpty(), op + ": an empty token map");
            var seen = new java.util.HashSet<String>();
            for (var t : tokens) {
                Require.that(t.key() instanceof PlutusData.BytesData && t.value() instanceof PlutusData.IntData, op + ": not a value");
                String n = HEX.formatHex(((PlutusData.BytesData) t.key()).value());
                BigInteger q = ((PlutusData.IntData) t.value()).value();
                Require.that(seen.add(n), op + ": a repeated token name");
                Require.that(q.signum() != 0, op + ": a zero quantity");
                Require.that(p.length() <= 64 && n.length() <= 64, op + ": a policy id or token name over 32 bytes");
                inRange(q, op);
            }
        }
    }

    /**
     * {@code f} with its offered and held values, and (M2c, #85) each deposit's value, {@link #fromWire from the wire}: what a
     * transition program hands a method, as the validator does.
     */
    public static Funds fundsFromWire(Funds f) {
        return new Funds(f.policy(), f.self(), fromWire(f.received()), fromWire(f.held()), claimsFromWire(f.deposits()));
    }

    /** Deposit claims with canonical values (M2c, #85): a claim's value is the registry's, in the wire's order. */
    public static org.julclang.core.types.JulcList<DepositClaim> claimsFromWire(org.julclang.core.types.JulcList<DepositClaim> ds) {
        if (ds.isEmpty()) return ds;
        DepositClaim d = ds.head();
        return claimsFromWire(ds.tail()).prepend(new DepositClaim(d.output(), d.by(), fromWire(d.value()), d.memo()));
    }

    // ------------------------------------------------------------------ stored values (M2c, ADR-0018 D5)

    /**
     * {@code v} in its wire form (M2c): the order cardano-client-lib's canonical CBOR writes a value as data, each policy's
     * token names by their encoding (a shorter name first, then bytewise; policies keep the ledger's order, which agrees).
     * A value stored in a datum (a field of the state, a record, a memo, a datum) is held so, so that the data a method
     * returns equals the data the runtime writes. Precondition: {@code v} canonical, every policy lovelace's or 28 bytes
     * (then the ledger's policy order is canonical CBOR's too: review r2, C6; {@link #fromData} refuses other policies).
     */
    public static Value toWire(Value v) {
        var ps = new java.util.ArrayList<PlutusData.Pair>();
        for (var p : Assets.read(v).entrySet()) {
            var names = new java.util.ArrayList<>(p.getValue().keySet());
            names.sort(java.util.Comparator.comparingInt(String::length).thenComparing(java.util.Comparator.naturalOrder()));
            var ts = new java.util.ArrayList<PlutusData.Pair>();
            for (String n : names) ts.add(new PlutusData.Pair(PlutusData.bytes(HEX.parseHex(n)), PlutusData.integer(p.getValue().get(n))));
            ps.add(new PlutusData.Pair(PlutusData.bytes(HEX.parseHex(p.getKey())), new PlutusData.MapData(ts)));
        }
        return Value.fromPlutusData(new PlutusData.MapData(ps));
    }

    /** A value as data (M2c): the generated encoders' Value (on-chain a value is its data). */
    public static PlutusData data(Value v) {
        return v.toPlutusData();
    }

    /**
     * A value decoded from data (a memo's or a read state's, M2c): {@link #fromWire}'s rules, so a malformed one fails. They
     * are checked on the data as it came, before a {@code Value} is built from it, since that build normalises (it puts
     * lovelace first, as the on-chain body does not: review r1, C3). And every policy is the ledger's: lovelace's (empty)
     * or 28 bytes (review r2, C6). Data that crosses is written as canonical CBOR, whose map order (a shorter key first)
     * agrees with the ledger's only for such policies; a value of policies of other lengths (both up to 32 bytes) could
     * not come back in the order {@link #fromWire} requires. No value .jsc builds has another policy (Value.asset takes a
     * Hash).
     */
    public static Value fromData(PlutusData d) {
        wire(d, "fromData");
        for (var e : ((PlutusData.MapData) d).entries()) {
            int n = ((PlutusData.BytesData) e.key()).value().length;
            Require.that(n == 0 || n == 28, "fromData: a policy id that is neither lovelace's nor 28 bytes");
        }
        return fromWire(Value.fromPlutusData(d));
    }

    /**
     * Whether {@code v} may be deposited (M2c, ADR-0011 D3, §5.7): not empty, every quantity strictly positive, and no token
     * name of a reserved kind (32 bytes; 29 starting 0x4F or 0x49) under any policy but lovelace's, as the output verifier
     * requires of every deposit claim.
     */
    public static boolean depositable(Value v) {
        var m = Assets.read(v);
        if (m.isEmpty()) return false;
        for (var p : m.entrySet()) {
            for (var t : p.getValue().entrySet()) {
                if (t.getValue().signum() <= 0) return false;
                if (!p.getKey().isEmpty() && reserved(HEX.parseHex(t.getKey()))) return false;
            }
        }
        return true;
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

    /**
     * {@code acc} with {@code q} more of {@code (policy, name)} (insertCoin of the new total: with a non-zero total, keys of
     * at most 32 bytes and the total in range; zero removes it).
     */
    public static Value mintAdd(Value acc, byte[] policy, byte[] name, BigInteger q) {
        BigInteger total = Assets.quantity(acc, policy, name).add(q);
        if (total.signum() != 0) {
            Require.that(policy.length <= 32 && name.length <= 32, "mint: a policy id or token name over 32 bytes");
            inRange(total, "mint");
        }
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
