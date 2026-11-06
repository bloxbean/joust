package org.julclang.state.runtime;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.state.api.RequireException;

/** Client-side mirror of the validator's datum-size check (I13), so oversize states fail before submitting. */
final class Datums {
    private Datums() {}

    static void checkSize(ContractSpec<?> spec, PlutusData datum, String what) {
        int size;
        try {
            size = HexUtil.decodeHexString(datum.serializeToHex()).length;
        } catch (Exception e) {
            throw new JoustException("cannot serialise the state", e);
        }
        if (size > spec.maxDatumBytes()) {
            throw new RequireException(what + ": the new state is " + size + " bytes, over " + spec.name()
                    + "'s maxDatumBytes (" + spec.maxDatumBytes() + ")");
        }
    }

    static String hex(PlutusData d) {
        try {
            return d.serializeToHex();
        } catch (Exception e) {
            throw new JoustException("cannot serialise datum", e);
        }
    }
}
