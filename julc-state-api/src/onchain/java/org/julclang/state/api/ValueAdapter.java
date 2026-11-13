package org.julclang.state.api;

import org.julclang.state.api.Assets;
import org.julclang.state.api.Funds;
import org.julclang.state.api.DepositClaim;
import org.julclang.core.types.JulcList;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcValue;
import org.julclang.ledger.Value;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ByteStringLib;

import java.math.BigInteger;

/**
 * On-chain variant of {@code ValueAdapter} (see src/main/java): the PV11 native Value builtins, which fail by themselves
 * where the JVM body checks (a quantity out of the 128-bit range, a token name over 32 bytes, a negative quantity in
 * {@code valueContains}). Every argument is canonical (the JVM body's precondition), so equality is Data equality;
 * {@link #fromWire} makes a value from the wire canonical.
 */
@OnchainLibrary
public class ValueAdapter {

    /** See the JVM body: a canonical value from a wire-ordered one, folded entry by entry (any token order). */
    public static Value fromWire(Value v) {
        JulcValue empty = Builtins.unValueData(Builtins.mapData(Builtins.mkNilPairData()));
        return (Value) (Object) Builtins.valueData(wirePolicies(Builtins.unMapData((PlutusData) (Object) v), Builtins.emptyByteString(),
                true, empty));
    }

    /** Each policy in strictly ascending order, its token map not empty, folded in (recursive: a native value is no loop accumulator). */
    static JulcValue wirePolicies(PlutusData ps, byte[] prev, boolean first, JulcValue acc) {
        if (Builtins.nullList(ps)) {
            return acc;
        } else {
            PlutusData pair = Builtins.headList(ps);
            byte[] p = Builtins.unBData(Builtins.fstPair(pair));
            PlutusData ts = Builtins.unMapData(Builtins.sndPair(pair));
            if ((first || Builtins.lessThanByteString(prev, p)) && !Builtins.nullList(ts)) {
                return wirePolicies(Builtins.tailList(ps), p, false, wireTokens(ts, p, acc));
            } else {
                Builtins.error();   // no message, as the builtins fail (a message would need a place in every message map)
                return acc;
            }
        }
    }

    /** Each token once, with a non-zero quantity; insertCoin refuses a key over 32 bytes and a quantity out of range. */
    static JulcValue wireTokens(PlutusData ts, byte[] p, JulcValue acc) {
        if (Builtins.nullList(ts)) {
            return acc;
        } else {
            PlutusData pair = Builtins.headList(ts);
            byte[] n = Builtins.unBData(Builtins.fstPair(pair));
            BigInteger q = Builtins.unIData(Builtins.sndPair(pair));
            if (q.compareTo(BigInteger.ZERO) != 0 && Builtins.lookupCoin(p, n, acc).compareTo(BigInteger.ZERO) == 0) {
                return wireTokens(Builtins.tailList(ts), p, Builtins.insertCoin(p, n, q, acc));
            } else {
                Builtins.error();
                return acc;
            }
        }
    }

    public static Funds fundsFromWire(Funds f) {
        return new Funds(f.policy(), f.self(), fromWire(f.received()), fromWire(f.held()), claimsFromWire(f.deposits()));
    }

    /** See the JVM body: each deposit claim's value from the wire (M2c, #85). */
    public static JulcList<DepositClaim> claimsFromWire(JulcList<DepositClaim> ds) {
        if (ds.isEmpty()) {
            return ds;
        } else {
            DepositClaim d = ds.head();
            JulcList<DepositClaim> rest = claimsFromWire(ds.tail());
            return rest.prepend(new DepositClaim(d.output(), d.by(), fromWire(d.value()), d.memo()));
        }
    }

    /** See the JVM body: each policy's token names sorted into the wire's order (a shorter name first, then bytewise). */
    public static Value toWire(Value v) {
        return (Value) (Object) Builtins.mapData(wireSorted(Builtins.unMapData((PlutusData) (Object) v)));
    }

    static PlutusData wireSorted(PlutusData ps) {
        if (Builtins.nullList(ps)) {
            return ps;
        }
        PlutusData pair = Builtins.headList(ps);
        PlutusData.MapData tokens = Builtins.mapData(sortTokens(Builtins.unMapData(Builtins.sndPair(pair))));
        return Builtins.mkCons(Builtins.mkPairData(Builtins.fstPair(pair), tokens), wireSorted(Builtins.tailList(ps)));
    }

    /** Insertion sort (a value's token map is short): the sorted rest, with the head inserted (the empty list is its own). */
    static PlutusData sortTokens(PlutusData ts) {
        if (Builtins.nullList(ts)) {
            return ts;
        }
        return insertToken(Builtins.headList(ts), sortTokens(Builtins.tailList(ts)));
    }

    static PlutusData insertToken(PlutusData t, PlutusData sorted) {
        if (Builtins.nullList(sorted)) {
            return Builtins.mkCons(t, sorted);
        }
        PlutusData h = Builtins.headList(sorted);
        if (wireBefore(Builtins.unBData(Builtins.fstPair(t)), Builtins.unBData(Builtins.fstPair(h)))) {
            return Builtins.mkCons(t, sorted);
        }
        return Builtins.mkCons(h, insertToken(t, Builtins.tailList(sorted)));
    }

    /** The wire's order of two token names: a shorter one first, then bytewise. */
    static boolean wireBefore(byte[] a, byte[] b) {
        long la = ByteStringLib.length(a);
        long lb = ByteStringLib.length(b);
        return la < lb || (la == lb && Builtins.lessThanByteString(a, b));
    }

    public static PlutusData data(Value v) {
        return (PlutusData) (Object) v;
    }

    public static Value fromData(PlutusData d) {
        return fromWire((Value) (Object) d);
    }

    public static boolean depositable(Value v) {
        PlutusData ps = Builtins.unMapData((PlutusData) (Object) v);
        return !Builtins.nullList(ps) && depositPolicies(ps);
    }

    static boolean depositPolicies(PlutusData ps) {
        if (Builtins.nullList(ps)) {
            return true;
        } else {
            PlutusData pair = Builtins.headList(ps);
            boolean ada = ByteStringLib.length(Builtins.unBData(Builtins.fstPair(pair))) == 0;
            return depositTokens(Builtins.unMapData(Builtins.sndPair(pair)), ada) && depositPolicies(Builtins.tailList(ps));
        }
    }

    static boolean depositTokens(PlutusData ts, boolean ada) {
        if (Builtins.nullList(ts)) {
            return true;
        } else {
            PlutusData pair = Builtins.headList(ts);
            byte[] n = Builtins.unBData(Builtins.fstPair(pair));
            BigInteger q = Builtins.unIData(Builtins.sndPair(pair));
            return q.compareTo(BigInteger.ZERO) > 0 && (ada || !reserved(n)) && depositTokens(Builtins.tailList(ts), ada);
        }
    }

    public static Value none() {
        return Assets.none();
    }

    public static Value lovelace(BigInteger q) {
        return Assets.lovelace(q);
    }

    public static Value asset(byte[] policy, byte[] name, BigInteger q) {
        return Assets.of(policy, name, q);
    }

    public static Value add(Value a, Value b) {
        return Assets.add(a, b);
    }

    public static Value subtract(Value a, Value b) {
        return Assets.subtract(a, b);
    }

    public static Value scale(BigInteger k, Value v) {
        return (Value) (Object) Builtins.valueData(Builtins.scaleValue(k, Builtins.unValueData((PlutusData) (Object) v)));
    }

    public static BigInteger quantity(Value v, byte[] policy, byte[] name) {
        return Assets.quantity(v, policy, name);
    }

    public static BigInteger lovelaceOf(Value v) {
        return Assets.lovelaceOf(v);
    }

    public static boolean covers(Value a, Value b) {
        return Assets.covers(a, b);
    }

    public static boolean same(Value a, Value b) {
        return Builtins.equalsData((PlutusData) (Object) a, (PlutusData) (Object) b);
    }

    public static boolean isEmpty(Value v) {
        return Assets.isEmpty(v);
    }

    public static Value withoutLovelace(Value v) {
        return Assets.withoutLovelace(v);
    }

    public static boolean sendable(Value v, byte[] policy) {
        return policiesOk(Builtins.unMapData((PlutusData) (Object) v), policy, false);
    }

    public static boolean acceptable(Value v, byte[] policy) {
        return policiesOk(Builtins.unMapData((PlutusData) (Object) v), policy, true);
    }

    /** Each policy's tokens: positive (or, for a send, not negative), no lovelace when accepting, no own reserved name. */
    static boolean policiesOk(PlutusData ps, byte[] policy, boolean accepting) {
        if (Builtins.nullList(ps)) {
            return true;
        } else {
            PlutusData pair = Builtins.headList(ps);
            byte[] p = Builtins.unBData(Builtins.fstPair(pair));
            boolean own = ByteStringLib.equals(p, policy);
            boolean ada = ByteStringLib.length(p) == 0;
            return !(accepting && ada) && tokensOk(Builtins.unMapData(Builtins.sndPair(pair)), own)
                    && policiesOk(Builtins.tailList(ps), policy, accepting);
        }
    }

    static boolean tokensOk(PlutusData ts, boolean own) {
        if (Builtins.nullList(ts)) {
            return true;
        } else {
            PlutusData pair = Builtins.headList(ts);
            byte[] n = Builtins.unBData(Builtins.fstPair(pair));
            BigInteger q = Builtins.unIData(Builtins.sndPair(pair));
            return q.compareTo(BigInteger.ZERO) > 0 && !(own && reserved(n)) && tokensOk(Builtins.tailList(ts), own);
        }
    }

    public static boolean mintable(byte[] name) {
        return ByteStringLib.length(name) <= 32 && !reserved(name);
    }

    public static Value mintAdd(Value acc, byte[] policy, byte[] name, BigInteger q) {
        JulcValue a = Builtins.unValueData((PlutusData) (Object) acc);
        BigInteger total = Builtins.lookupCoin(policy, name, a).add(q);
        return (Value) (Object) Builtins.valueData(Builtins.insertCoin(policy, name, total, a));
    }

    static boolean reserved(byte[] n) {
        long len = ByteStringLib.length(n);
        return len == 32 || (len == 29 && (ByteStringLib.at(n, 0L) == 79L || ByteStringLib.at(n, 0L) == 73L));
    }
}
