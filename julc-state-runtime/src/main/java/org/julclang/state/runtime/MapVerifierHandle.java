package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.clientlib.JulcScriptLoader;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.types.JulcList;
import org.julclang.state.api.mpf.MapClaim;
import org.julclang.state.api.mpf.MapClaims;
import org.julclang.state.verifier.MapVerifier;

import java.math.BigInteger;
import java.util.List;

/**
 * The JOUST map verifier on a network (ADR-0002 D9): its reference script, kept at the verifier's own enterprise
 * address (unspendable: the script only validates withdrawals), and its registered stake credential. Transactions that
 * touch MerkleMaps withdraw 0 from it with every claim as the redeemer.
 */
public final class MapVerifierHandle {

    private final StateRuntime runtime;
    private final PlutusV3Script script;
    private final String hash;
    private final String holder;
    private final String rewardAddress;
    private volatile Utxo reference;

    MapVerifierHandle(StateRuntime runtime) {
        this.runtime = runtime;
        this.script = JulcScriptLoader.load(MapVerifier.class);
        try {
            this.hash = HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new JoustException("cannot hash the map verifier", e);
        }
        this.holder = AddressProvider.getEntAddress(script, runtime.network()).toBech32();
        this.rewardAddress = AddressProvider.getRewardAddress(script, runtime.network()).toBech32();
    }

    public PlutusV3Script script() { return script; }
    public String rewardAddress() { return rewardAddress; }

    /** The verifier's reference script, publishing it (and registering its stake credential) first if needed. */
    public Utxo ensure(Account payer) {
        Utxo found = find();
        if (found != null) return found;
        var tx = new Tx()
                .payToAddress(holder, List.of(Amount.lovelace(deposit())), script)
                .registerStakeAddress(rewardAddress)
                .from(payer.baseAddress());
        String txHash = new TxRunner(runtime).submitPlain(tx, payer, "publish and register the JOUST map verifier");
        for (int i = 0; i < 60 && find() == null; i++) TxRunner.sleep(500);
        if (find() == null) throw new PendingTransactionException(txHash, "map verifier not visible yet");
        runtime.log("published and registered the map verifier in " + txHash);
        return find();
    }

    /** Adds the zero withdrawal carrying {@code claims} and the verifier's reference input to {@code tx}. */
    void attach(ScriptTx tx, List<MapClaim> claims, Account payer) {
        Utxo ref = ensure(payer);
        JulcList<MapClaim> list = JulcList.empty();
        for (int i = claims.size() - 1; i >= 0; i--) list = list.prepend(claims.get(i));
        tx.readFrom(ref).withdraw(rewardAddress, BigInteger.ZERO, PlutusDataAdapter.convert(new MapClaims(list)));
    }

    private Utxo find() {
        if (reference != null) return reference;
        try {
            var r = runtime.backend().getUtxoService().getUtxos(holder, 20, 1);
            if (r.isSuccessful() && r.getValue() != null) {
                for (Utxo u : r.getValue()) {
                    if (hash.equalsIgnoreCase(u.getReferenceScriptHash())) {
                        reference = u;
                        return u;
                    }
                }
            }
        } catch (Exception e) {
            throw new JoustException("cannot look up the map verifier", e);
        }
        return null;
    }

    private BigInteger deposit() {
        try {
            var pp = runtime.backend().getEpochService().getProtocolParameters().getValue();
            var out = TransactionOutput.builder().address(holder)
                    .value(Value.builder().coin(BigInteger.valueOf(2_000_000)).build()).scriptRef(script).build();
            return new MinAdaCalculator(pp).calculateMinAda(out).add(BigInteger.valueOf(1_000_000));
        } catch (Exception e) {
            throw new JoustException("cannot compute the map verifier deposit", e);
        }
    }
}
