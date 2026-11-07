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
 * A shared JOUST withdraw-zero verifier on a network: the map verifier (ADR-0002 D9) or the output verifier (ADR-0007
 * D5). Its reference script is kept at the verifier's own enterprise address (unspendable: the script only validates
 * withdrawals), and its stake credential is registered. Transactions withdraw 0 from it with their claims as the
 * redeemer.
 */
public final class VerifierHandle {

    private final StateRuntime runtime;
    private final PlutusV3Script script;
    private final String hash;
    private final String holder;
    private final String rewardAddress;
    private volatile Utxo reference;

    private final String label;

    VerifierHandle(StateRuntime runtime, Class<?> verifier, String label) {
        this.runtime = runtime;
        this.label = label;
        this.script = JulcScriptLoader.load(verifier);
        try {
            this.hash = HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new JoustException("cannot hash the " + label, e);
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
        String txHash = new TxRunner(runtime).submitPlain(tx, payer, "publish and register the JOUST " + label);
        for (int i = 0; i < 60 && find() == null; i++) TxRunner.sleep(500);
        if (find() == null) throw new PendingTransactionException(txHash, label + " not visible yet");
        runtime.log("published and registered the " + label + " in " + txHash);
        return find();
    }

    /** Adds the zero withdrawal carrying the map {@code claims} and the verifier's reference input to {@code tx}. */
    void attach(ScriptTx tx, List<MapClaim> claims, Account payer) {
        var cap = TxRunner.capture();
        if (cap != null) {
            // In a composition every call's claims go into one withdrawal, attached when it is submitted.
            ensure(payer);
            cap.mapClaims.addAll(claims);
            return;
        }
        JulcList<MapClaim> list = JulcList.empty();
        for (int i = claims.size() - 1; i >= 0; i--) list = list.prepend(claims.get(i));
        attachRedeemer(tx, new MapClaims(list), payer);
    }

    /** Adds the zero withdrawal carrying {@code redeemer} and the verifier's reference input to {@code tx}. */
    void attachRedeemer(ScriptTx tx, Object redeemer, Account payer) {
        Utxo ref = ensure(payer);
        tx.readFrom(ref).withdraw(rewardAddress, BigInteger.ZERO, PlutusDataAdapter.convert(redeemer));
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
            throw new JoustException("cannot look up the " + label, e);
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
            throw new JoustException("cannot compute the " + label + " deposit", e);
        }
    }
}
