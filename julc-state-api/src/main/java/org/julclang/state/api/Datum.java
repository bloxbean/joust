package org.julclang.state.api;

import org.julclang.core.PlutusData;
import org.julclang.ledger.OutputDatum;
import org.julclang.stdlib.annotation.OnchainLibrary;

/**
 * Output datums for {@link Send}s (ADR-0007), the same on the JVM and on-chain.
 * <p>
 * A record cannot be passed as data in shared code: {@code (PlutusData) (Object) record} works on-chain but throws on
 * the JVM. Build the datum with {@code Builtins.constrData}, {@code bData}, {@code iData} and {@code mkCons}, which run
 * on both: a record {@code R(a, b)} is {@code constrData(0, mkCons(a, mkCons(b, mkNilData())))}.
 */
@OnchainLibrary
public class Datum {

    public static OutputDatum inline(PlutusData d) {
        return new OutputDatum.OutputDatumInline(d);
    }

    public static OutputDatum none() {
        return new OutputDatum.NoOutputDatum();
    }
}
