package org.julclang.state.api;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcValue;
import org.julclang.ledger.Value;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.OnchainLibrary;

import java.math.BigInteger;

/**
 * On-chain variant of {@code Assets} (see src/main/java): the PV11 native Value builtins (CIP-153). Every argument must
 * be canonical, as ledger values and the results of these functions are; anything else fails the script.
 */
@OnchainLibrary
public class Assets {

    public static Value none() {
        return (Value) (Object) Builtins.mapData(Builtins.mkNilPairData());
    }

    public static Value of(byte[] policy, byte[] name, BigInteger quantity) {
        JulcValue empty = Builtins.unValueData(Builtins.mapData(Builtins.mkNilPairData()));
        return (Value) (Object) Builtins.valueData(Builtins.insertCoin(policy, name, quantity, empty));
    }

    public static Value lovelace(BigInteger quantity) {
        JulcValue empty = Builtins.unValueData(Builtins.mapData(Builtins.mkNilPairData()));
        byte[] ada = Builtins.emptyByteString();
        return (Value) (Object) Builtins.valueData(Builtins.insertCoin(ada, ada, quantity, empty));
    }

    public static Value add(Value a, Value b) {
        JulcValue sum = Builtins.unionValue(Builtins.unValueData((PlutusData) (Object) a),
                Builtins.unValueData((PlutusData) (Object) b));
        return (Value) (Object) Builtins.valueData(sum);
    }

    public static Value subtract(Value a, Value b) {
        JulcValue minusB = Builtins.scaleValue(BigInteger.valueOf(-1), Builtins.unValueData((PlutusData) (Object) b));
        JulcValue diff = Builtins.unionValue(Builtins.unValueData((PlutusData) (Object) a), minusB);
        return (Value) (Object) Builtins.valueData(diff);
    }

    public static BigInteger quantity(Value v, byte[] policy, byte[] name) {
        return Builtins.lookupCoin(policy, name, Builtins.unValueData((PlutusData) (Object) v));
    }

    public static BigInteger lovelaceOf(Value v) {
        byte[] ada = Builtins.emptyByteString();
        return Builtins.lookupCoin(ada, ada, Builtins.unValueData((PlutusData) (Object) v));
    }

    public static Value withoutLovelace(Value v) {
        byte[] ada = Builtins.emptyByteString();
        JulcValue rest = Builtins.insertCoin(ada, ada, BigInteger.ZERO, Builtins.unValueData((PlutusData) (Object) v));
        return (Value) (Object) Builtins.valueData(rest);
    }

    public static boolean isEmpty(Value v) {
        return Builtins.nullList(Builtins.unMapData((PlutusData) (Object) v));
    }

    public static boolean covers(Value a, Value b) {
        return Builtins.valueContains(Builtins.unValueData((PlutusData) (Object) a),
                Builtins.unValueData((PlutusData) (Object) b));
    }
}
