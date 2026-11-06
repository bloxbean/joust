package org.julclang.state.runtime;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxOutRef;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.api.RequireException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One live instance of a contract, identified by its thread-token name. Reads its state and runs calls on it.
 */
public final class StateInstance<S> {

    private final StateContractHandle<S> contract;
    private final String instanceId;

    StateInstance(StateContractHandle<S> contract, String instanceId) {
        this.contract = contract;
        this.instanceId = instanceId.toLowerCase();
    }

    public String instanceId() { return instanceId; }
    /** Asset unit of the thread token: policy id + instance id. */
    public String unit() { return contract.policyId + instanceId; }
    public StateContractHandle<S> contract() { return contract; }

    /** The current state UTxO. */
    public Utxo utxo() {
        try {
            var r = contract.runtime.backend().getUtxoService().getUtxos(contract.address, unit(), 10, 1);
            if (r.isSuccessful() && r.getValue() != null) {
                for (Utxo u : r.getValue()) {
                    if (u.getAmount().stream().anyMatch(a -> a.getUnit().equalsIgnoreCase(unit()))) return u;
                }
            }
        } catch (Exception e) {
            throw new JoustException("cannot query the " + contract.spec.name() + " state UTxO", e);
        }
        throw new InstanceNotFoundException(contract.spec.name() + " instance " + instanceId + " not found at "
                + contract.address);
    }

    /** The current state, decoded from the inline datum. */
    public S state() {
        return decode(utxo());
    }

    /** Lovelace the state UTxO holds (deposit included). */
    public BigInteger lovelace() {
        return lovelaceOf(utxo());
    }

    /** Runs an action or destroy: on the JVM first, then on chain. Retries only if the node rejected it because the state moved. */
    public CallResult<S> call(Call<S> call) {
        SubmitException last = null;
        for (int attempt = 0; attempt <= contract.runtime.maxRetries(); attempt++) {
            Utxo stateUtxo = utxo();
            try {
                return attempt(call, stateUtxo);
            } catch (SubmitException e) {
                last = e;
                Utxo now;
                try {
                    now = utxo();
                } catch (InstanceNotFoundException gone) {
                    throw e;
                }
                boolean moved = !(now.getTxHash().equals(stateUtxo.getTxHash())
                        && now.getOutputIndex() == stateUtxo.getOutputIndex());
                if (!moved) throw e;            // a real failure, not contention
                contract.runtime.log(call.name + ": state moved (attempt " + (attempt + 1) + "), re-running");
            }
        }
        throw new ContentionException(call.name + ": the state kept moving", last);
    }

    private CallResult<S> attempt(Call<S> call, Utxo stateUtxo) {
        var spec = contract.spec;
        String what = spec.name() + "." + call.name;
        S current = decode(stateUtxo);
        byte[] callerPkh = Keys.pkh(call.caller);
        BigInteger inLovelace = lovelaceOf(stateUtxo);
        BigInteger balance = call.kind == Call.Kind.DESTROY ? inLovelace : inLovelace.subtract(BigInteger.valueOf(spec.minAda()));

        // Policy annotations, checked before anything runs (the validator checks them again).
        for (var rule : call.signers) {
            if (!Arrays.equals(rule.key().apply(current), callerPkh)) {
                throw new RequireException(what + ": must be called by the key in '" + rule.field() + "'");
            }
        }
        Window window = call.usesTime()
                ? Window.open(contract.runtime, spec, call.notBefore, call.notAfter, current, what) : null;
        Ctx ctx = new Ctx(callerPkh, call.received, balance,
                window == null ? BigInteger.ZERO : window.fromMs(), window == null ? BigInteger.ZERO : window.toMs());

        Transition<S> t = call.run.apply(current, ctx);     // the developer's method, on the JVM
        Map<String, BigInteger> owed = new LinkedHashMap<>();
        List<Payout> payouts = new ArrayList<>();
        BigInteger total = BigInteger.ZERO;
        for (Payout p : t.payouts()) {
            if (p.lovelace().signum() <= 0) throw new RequireException(what + ": payouts must be positive");
            if (p.to().length != 28) throw new RequireException(what + ": payout recipient must be a 28-byte key hash");
            owed.merge(HexUtil.encodeHexString(p.to()), p.lovelace(), BigInteger::add);
            payouts.add(p);
            total = total.add(p.lovelace());
        }

        PlutusData redeemer = PlutusDataAdapter.convert(call.redeemer.apply(ctx));
        var tx = new ScriptTx().collectFrom(stateUtxo, redeemer);
        TxRunner.ExpectedState expected = null;
        if (call.kind == Call.Kind.ACTION) {
            BigInteger next = inLovelace.add(call.received).subtract(total);
            if (next.compareTo(BigInteger.valueOf(spec.minAda())) < 0) {
                throw new RequireException(what + ": payouts exceed the contract's funds");
            }
            PlutusData datum = PlutusDataAdapter.convert(t.next());
            Datums.checkSize(spec, datum, what);
            tx.payToContract(contract.address, List.of(Amount.lovelace(next), new Amount(unit(), BigInteger.ONE)), datum);
            expected = new TxRunner.ExpectedState(unit(), next, datum);
        } else {
            if (total.compareTo(inLovelace) != 0) {
                throw new RequireException(what + ": a destroy must pay out everything the instance holds ("
                        + inLovelace + " lovelace, ctx.balance()), but pays " + total);
            }
            tx.mintAsset(contract.script, new Asset("0x" + instanceId, BigInteger.ONE.negate()),
                    PlutusDataAdapter.convert(call.burnRedeemer));
        }

        // Payouts: one output per recipient, tagged with this instance's input (I7), topped up to the ledger minimum.
        PlutusData tag = PlutusDataAdapter.convert(new TxOutRef(new TxId(HexUtil.decodeHexString(stateUtxo.getTxHash())),
                BigInteger.valueOf(stateUtxo.getOutputIndex())));
        List<TxRunner.ExpectedPayout> expectedPayouts = new ArrayList<>();
        for (var e : owed.entrySet()) {
            String addr = contract.payoutAddress(HexUtil.decodeHexString(e.getKey()), call.caller);
            BigInteger amount = e.getValue().max(contract.minUtxo(addr, tag, e.getValue()));
            tx.payToContract(addr, List.of(Amount.lovelace(amount)), tag);
            expectedPayouts.add(new TxRunner.ExpectedPayout(addr, e.getValue(), tag));
        }
        tx.attachSpendingValidator(contract.script);

        List<byte[]> signers = new ArrayList<>();
        if (call.usesCtx || !call.signers.isEmpty()) signers.add(callerPkh);
        String txHash = new TxRunner(contract.runtime).submit(tx, call.caller, window, signers.toArray(new byte[0][]),
                expected, expectedPayouts, what);
        if (call.kind == Call.Kind.ACTION) {
            awaitState(txHash);
            return new CallResult<>(txHash, t.next(), payouts);
        }
        return new CallResult<>(txHash, null, payouts);
    }

    /** Waits until the indexer shows the state UTxO created by {@code txHash}. */
    void awaitState(String txHash) {
        for (int i = 0; i < 60; i++) {
            try {
                if (utxo().getTxHash().equals(txHash)) return;
            } catch (InstanceNotFoundException ignored) {
                // not indexed yet
            }
            TxRunner.sleep(500);
        }
        throw new PendingTransactionException(txHash, "included, but the new state is not visible yet");
    }

    private S decode(Utxo u) {
        try {
            if (u.getInlineDatum() == null) throw new JoustException("state UTxO has no inline datum");
            var data = PlutusData.deserialize(HexUtil.decodeHexString(u.getInlineDatum()));
            return PlutusDataAdapter.convert(data, contract.spec.stateClass());
        } catch (JoustException e) {
            throw e;
        } catch (Exception e) {
            throw new JoustException("cannot decode " + contract.spec.name() + " state", e);
        }
    }

    private static BigInteger lovelaceOf(Utxo u) {
        return u.getAmount().stream().filter(a -> a.getUnit().equals("lovelace"))
                .map(Amount::getQuantity).findFirst().orElse(BigInteger.ZERO);
    }
}
