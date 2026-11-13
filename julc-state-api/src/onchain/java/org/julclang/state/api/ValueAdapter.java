package org.julclang.state.api;

import org.julclang.state.api.Assets;
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
 * {@code valueContains}). Every value is canonical, so equality is Data equality.
 */
@OnchainLibrary
public class ValueAdapter {

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
