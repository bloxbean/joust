package org.julclang.state.runtime;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.state.api.ForeignInstance;

/**
 * Reads another JOUST contract's instance for {@code @Reads} (ADR-0011 D2): the UTxO holding its token, used as a
 * reference input, and its state decoded as {@code type}.
 */
public final class ForeignReads {

    /** One read: the reference input and the decoded state. */
    public record Read<T>(Utxo utxo, T state) {}

    private ForeignReads() {}

    public static <T> Read<T> read(StateRuntime runtime, ForeignInstance fi, Class<T> type) {
        String unit = HexUtil.encodeHexString(fi.policy()) + HexUtil.encodeHexString(fi.instance());
        // A JOUST instance sits at its script's enterprise address, or at the base address staked to the script.
        var cred = com.bloxbean.cardano.client.address.Credential.fromScript(fi.policy());
        var candidates = java.util.List.of(
                com.bloxbean.cardano.client.address.AddressProvider.getEntAddress(cred, runtime.network()).toBech32(),
                com.bloxbean.cardano.client.address.AddressProvider.getBaseAddress(cred, cred, runtime.network()).toBech32());
        try {
            for (String address : candidates) {
                var r = runtime.backend().getUtxoService().getUtxos(address, unit, 10, 1);
                if (!r.isSuccessful() || r.getValue() == null) continue;
                for (Utxo u : r.getValue()) {
                    if (u.getInlineDatum() == null) continue;
                    var data = PlutusData.deserialize(HexUtil.decodeHexString(u.getInlineDatum()));
                    return new Read<>(u, PlutusDataAdapter.convert(data, type));
                }
            }
        } catch (Exception e) {
            throw new JoustException("cannot read the foreign instance " + unit, e);
        }
        throw new InstanceNotFoundException("foreign instance " + unit + " not found");
    }
}
