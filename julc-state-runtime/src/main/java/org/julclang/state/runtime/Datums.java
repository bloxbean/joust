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

    /**
     * cardano-client-lib writes inline datums in canonical CBOR, which sorts map keys; the validator recomputes the
     * state with JuLC's ordered maps. If the state's own order is not canonical, what is written differs from what is
     * checked and the call would fail on-chain. Refuse it here with a useful message.
     */
    static void checkCanonical(ContractSpec<?> spec, PlutusData datum, String what) {
        try {
            var item = datum.serialize();
            byte[] canonical = com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(item, true);
            byte[] asBuilt = com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(item, false);
            if (!java.util.Arrays.equals(canonical, asBuilt)) {
                throw new JoustException(what + ": the new " + spec.name() + " state contains a map whose keys are not in"
                        + " canonical order, so the datum written on chain would differ from the one the validator"
                        + " recomputes. Keep state maps ordered with org.julclang.state.api.SortedMaps");
            }
        } catch (JoustException e) {
            throw e;
        } catch (Exception e) {
            throw new JoustException("cannot serialise the state", e);
        }
    }

    /** A deposit's memo must be canonical too: the registry carries its serialised form (ADR-0011 D3, review r3). */
    static void checkCanonicalMemo(PlutusData memo, String what) {
        try {
            var item = memo.serialize();
            byte[] canonical = com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(item, true);
            byte[] asBuilt = com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(item, false);
            if (!java.util.Arrays.equals(canonical, asBuilt)) {
                throw new JoustException(what + ": a deposit memo contains a map whose keys are not in canonical order;"
                        + " the validator would compare it with the reordered copy in the registry. Build it with SortedMaps");
            }
        } catch (JoustException e) {
            throw e;
        } catch (Exception e) {
            throw new JoustException("cannot serialise a deposit memo", e);
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
