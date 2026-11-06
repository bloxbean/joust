package org.julclang.state.runtime;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.clientlib.JulcScriptLoader;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxOutRef;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.RequireException;

import java.math.BigInteger;
import java.util.List;

/**
 * One contract type on one network: its script, policy id and the (enterprise) address every instance lives at.
 * Creates instances ({@link #deploy}) and reconnects to existing ones ({@link #at}).
 */
public final class StateContractHandle<S> {

    final StateRuntime runtime;
    final ContractSpec<S> spec;
    final PlutusV3Script script;
    final String policyId;
    final String address;

    public StateContractHandle(StateRuntime runtime, ContractSpec<S> spec) {
        this.runtime = runtime;
        this.spec = spec;
        this.script = JulcScriptLoader.load(spec.validatorClass());
        try {
            this.policyId = HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new JoustException("cannot hash the " + spec.name() + " script", e);
        }
        this.address = AddressProvider.getEntAddress(script, runtime.network()).toBech32();
    }

    public String policyId() { return policyId; }
    public String address() { return address; }
    public PlutusV3Script script() { return script; }
    public ContractSpec<S> spec() { return spec; }
    public StateRuntime runtime() { return runtime; }

    /** Reconnects to an existing instance by its id (hex thread-token name). */
    public StateInstance<S> at(String instanceId) {
        return new StateInstance<>(this, instanceId);
    }

    /** The instance id a seed UTxO produces: {@code blake2b_256(txId ++ I2OSP(index, 2))}, hex. */
    public static String instanceIdOf(String seedTxHash, int seedIndex) {
        if (seedIndex < 0 || seedIndex > 0xFFFF) throw new IllegalArgumentException("seed index out of range");
        byte[] tx = HexUtil.decodeHexString(seedTxHash);
        byte[] buf = new byte[tx.length + 2];
        System.arraycopy(tx, 0, buf, 0, tx.length);
        buf[tx.length] = (byte) (seedIndex >> 8);
        buf[tx.length + 1] = (byte) seedIndex;
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(buf));
    }

    /** Creates a new instance: consumes a seed UTxO of the caller, mints the thread token, locks the initial state. */
    public DeployResult<S> deploy(Deploy<S> d) {
        Account caller = d.caller();
        byte[] callerPkh = Keys.pkh(caller);
        Utxo seed = pickSeed(caller);
        String instanceId = instanceIdOf(seed.getTxHash(), seed.getOutputIndex());
        var instance = new StateInstance<>(this, instanceId);

        Window window = d.usesCtx() ? Window.open(runtime, spec, List.of(), List.of(), null, "deploy") : null;
        Ctx ctx = window == null
                ? new Ctx(callerPkh, d.received(), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO)
                : new Ctx(callerPkh, d.received(), BigInteger.ZERO, window.fromMs(), window.toMs());
        S initial = d.run().apply(ctx);                      // the developer's @Init, on the JVM
        PlutusData datum = PlutusDataAdapter.convert(initial);
        Datums.checkSize(spec, datum, "deploy");
        Datums.checkCanonical(spec, datum, "deploy");

        var seedRef = new TxOutRef(new TxId(HexUtil.decodeHexString(seed.getTxHash())),
                BigInteger.valueOf(seed.getOutputIndex()));
        PlutusData redeemer = PlutusDataAdapter.convert(d.redeemer().apply(seedRef, ctx));
        BigInteger lovelace = BigInteger.valueOf(spec.minAda()).add(d.received());

        var tx = new ScriptTx()
                .collectFrom(seed)
                .mintAsset(script, new Asset("0x" + instanceId, BigInteger.ONE), redeemer)
                .payToContract(address, List.of(Amount.lovelace(lovelace), new Amount(instance.unit(), BigInteger.ONE)), datum);

        var expected = new TxRunner.ExpectedState(instance.unit(), lovelace, datum);
        String txHash = new TxRunner(runtime).submit(tx, caller, window, d.usesCtx() ? new byte[][]{callerPkh} : new byte[0][],
                expected, List.of(), "deploy " + spec.name());
        instance.awaitState(txHash);
        return new DeployResult<>(instance, txHash, initial);
    }

    /** Result of {@link #deploy}. */
    public record DeployResult<S>(StateInstance<S> instance, String txHash, S state) {}

    private Utxo pickSeed(Account caller) {
        try {
            var r = runtime.backend().getUtxoService().getUtxos(caller.baseAddress(), 50, 1);
            if (!r.isSuccessful() || r.getValue() == null || r.getValue().isEmpty()) {
                throw new JoustException("deploy: the caller's address has no UTxO to use as seed");
            }
            // Prefer a pure-ADA UTxO; any UTxO works.
            return r.getValue().stream()
                    .filter(u -> u.getAmount().size() == 1)
                    .findFirst()
                    .orElse(r.getValue().get(0));
        } catch (JoustException e) {
            throw e;
        } catch (Exception e) {
            throw new JoustException("deploy: cannot read the caller's UTxOs", e);
        }
    }

    /**
     * The address a payout to {@code pkh} goes to. Never the fee payer's address: QuickTx treats an output there as
     * change and takes the fee out of it, which would under-pay the payout. So a payout to the caller goes to the
     * caller's enterprise address (same payment key); others go to their address-book address, else enterprise.
     */
    String payoutAddress(byte[] pkh, Account caller) {
        if (java.util.Arrays.equals(pkh, Keys.pkh(caller))) return caller.enterpriseAddress();
        String known = runtime.addressOf(pkh);
        if (known != null && !known.equals(caller.baseAddress())) return known;
        return AddressProvider.getEntAddress(Credential.fromKey(pkh), runtime.network()).toBech32();
    }

    /** Ledger minimum for an output of {@code lovelace} at {@code addr} with an inline datum. */
    BigInteger minUtxo(String addr, PlutusData datum, BigInteger lovelace) {
        try {
            ProtocolParams pp = runtime.backend().getEpochService().getProtocolParameters().getValue();
            var out = TransactionOutput.builder()
                    .address(addr)
                    .value(Value.builder().coin(lovelace).build())
                    .inlineDatum(datum)
                    .build();
            return new MinAdaCalculator(pp).calculateMinAda(out);
        } catch (Exception e) {
            throw new JoustException("cannot compute the minimum UTxO for a payout", e);
        }
    }

    static void require(boolean ok, String message) {
        if (!ok) throw new RequireException(message);
    }
}
