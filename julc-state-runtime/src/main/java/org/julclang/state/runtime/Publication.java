package org.julclang.state.runtime;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.quicktx.Tx;
import java.util.List;

/** Bounded publication, shared by the D14 builders. */
final class Publication {
    static boolean present(List<Utxo> outputs, String address, String hash) {
        return outputs.stream().anyMatch(u -> address.equals(u.getAddress()) && hash.equalsIgnoreCase(u.getReferenceScriptHash()));
    }

    static PreparedTx build(StateRuntime runtime, String address, long validTo, Tx tx) {
        if (validTo <= 0) throw new IllegalArgumentException("publication needs a positive upper bound");
        var wallet = WalletAccount.of(address, runtime.network());
        var ctx = Designation.builder(runtime, null).compose(tx.from(address))
                .feePayer(address).additionalSignersCount(1).validTo(validTo);
        Designation.Claimed claimed;
        try {
            claimed = Designation.build(runtime.claimStore(), address, null, ctx::build);
        } catch (ReservedInputException e) {
            throw e;
        } catch (RuntimeException e) {
            if (e instanceof CallAbort) throw e;
            // A wallet that cannot pay is the runtime's refusal, as in a prepare (#151 review DB3); the cause is kept,
            // so a read failure or a control event below it still decides the host's answer.
            throw new SubmitException("publish prerequisite: could not build the transaction: " + e.getMessage(), e);
        }
        var built = TxRunner.exportPrepared(runtime, "publish prerequisite", claimed, validTo);
        return new PreparedTx(runtime, built, List.of(wallet.paymentKeyHash()), null, "publish prerequisite");
    }
}
