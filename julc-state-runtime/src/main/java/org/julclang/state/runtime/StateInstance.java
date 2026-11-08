package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.Tx;
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

    /**
     * Runs a read-only function of the current state, with MerkleMap lookups served from the mirror (views).
     */
    public <T> T view(java.util.function.Function<S, T> f) {
        S s = state();
        if (!contract.spec.layout().hasMaps()) return f.apply(s);
        return new MerkleSupport(contract.runtime.mapStore()).run(() -> f.apply(s));
    }

    /** Lovelace the state UTxO holds (deposit included). */
    public BigInteger lovelace() {
        return lovelaceOf(utxo());
    }

    /**
     * The node rejected the transaction because one of its inputs was already spent, though the state was not: the
     * chain index had not caught up with a transaction of ours (e.g. a token UTxO the caller just spent). Rebuilding
     * after a moment queries the UTxOs again. Nothing was submitted, so retrying is safe.
     */
    static boolean staleInput(SubmitException e) {
        return e.getMessage() != null && e.getMessage().contains("BadInputsUTxO");
    }

    /**
     * Runs an action or destroy: on the JVM first, then on chain. Retries only if the node rejected it because the state
     * moved, or because an input was stale.
     */
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
                boolean stale = staleInput(e);
                if (!moved && !stale) throw e;            // a real failure, not contention
                contract.runtime.log(call.name + (stale ? ": an input was already spent (the index lagged)" : ": state moved")
                        + " (attempt " + (attempt + 1) + "), re-running");
                if (stale) TxRunner.sleep(2000);
            }
        }
        throw new ContentionException(call.name + ": the state kept moving", last);
    }

    private CallResult<S> attempt(Call<S> call, Utxo stateUtxo) {
        if (call.assetRun != null) return attemptAssets(call, stateUtxo);
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

        boolean maps = spec.layout().hasMaps();
        MerkleSupport merkle = maps ? new MerkleSupport(contract.runtime.mapStore()) : null;
        if (call.run == null) throw new JoustException(what + ": an instance call needs run(...)");
        Transition<S> t = maps ? merkle.run(() -> call.run.apply(current, ctx))   // the developer's method, on the JVM
                : call.run.apply(current, ctx);
        java.util.List<org.julclang.state.api.mpf.MapClaim> claims = java.util.List.of();
        S stored = t.next();
        TxOutRef owner = new TxOutRef(new TxId(HexUtil.decodeHexString(stateUtxo.getTxHash())),
                BigInteger.valueOf(stateUtxo.getOutputIndex()));
        if (maps) {
            var prepared = merkle.prepare(HexUtil.decodeHexString(contract.policyId), spec.layout(), current, t.next(), owner);
            claims = prepared.claims();
            stored = prepared.stored();
            MerkleSupport.verifyReplay(spec.layout(), current, claims, s -> call.run.apply(s, ctx), t);
        }
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

        PlutusData redeemer = PlutusDataAdapter.convert(call.redeemerFor(ctx, t));
        Utxo ref = contract.findReferenceScript();
        if (ref == null) throw new JoustException(what + ": the " + spec.name() + " reference script is not on chain");
        var tx = TxRunner.collect(TxRunner.newTx(), stateUtxo, redeemer).readFrom(ref);
        if (maps) contract.runtime.mapVerifier().attach(tx, claims, call.caller);
        TxRunner.ExpectedState expected = null;
        if (call.kind == Call.Kind.ACTION) {
            BigInteger next = inLovelace.add(call.received).subtract(total);
            if (next.compareTo(BigInteger.valueOf(spec.minAda())) < 0) {
                throw new RequireException(what + ": payouts exceed the contract's funds");
            }
            PlutusData datum = PlutusDataAdapter.convert(stored);
            Datums.checkSize(spec, datum, what);
            Datums.checkCanonical(spec, datum, what);
            tx.payToContract(contract.address, List.of(Amount.lovelace(next), new Amount(unit(), BigInteger.ONE)), datum);
            expected = new TxRunner.ExpectedState(unit(), next, datum);
        } else {
            if (!call.migrates && total.compareTo(inLovelace) != 0) {
                throw new RequireException(what + ": a destroy must pay out everything the instance holds ("
                        + inLovelace + " lovelace, ctx.balance()), but pays " + total);
            }
            TxRunner.mint(tx, contract.policyId, List.of(new Asset("0x" + instanceId, BigInteger.ONE.negate())),
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

        List<byte[]> signers = new ArrayList<>();
        if (call.usesCtx || !call.signers.isEmpty()) signers.add(callerPkh);
        for (Account a : call.extraSigners()) signers.add(Keys.pkh(a));
        call.applyAuthorization(tx);
        call.applyReads(tx);
        String txHash = new TxRunner(contract.runtime, call.extraSigners()).submit(tx, call.caller, window, signers.toArray(new byte[0][]),
                expected, expectedPayouts, maps ? new com.bloxbean.cardano.client.plutus.spec.PlutusScript[]{contract.script,
                        contract.runtime.mapVerifier().script()} : new com.bloxbean.cardano.client.plutus.spec.PlutusScript[]{contract.script}, what);
        if (call.kind == Call.Kind.ACTION) {
            awaitState(txHash);
            return new CallResult<>(txHash, stored, payouts, eventsOf(t));
        }
        awaitGone();
        return new CallResult<>(txHash, null, payouts, eventsOf(t));
    }

    /** A call of an asset-aware contract (ADR-0007): full values, sends, user mints. */
    private CallResult<S> attemptAssets(Call<S> call, Utxo stateUtxo) {
        var spec = contract.spec;
        String what = spec.name() + "." + call.name;
        S current = decode(stateUtxo);
        byte[] callerPkh = Keys.pkh(call.caller);
        org.julclang.ledger.Value inValue = AssetTx.valueOf(stateUtxo);
        BigInteger inLovelace = lovelaceOf(stateUtxo);
        boolean destroy = call.kind == Call.Kind.DESTROY;
        BigInteger balance = destroy ? inLovelace : inLovelace.subtract(BigInteger.valueOf(spec.minAda()));
        for (var rule : call.signers) {
            if (!Arrays.equals(rule.key().apply(current), callerPkh)) {
                throw new RequireException(what + ": must be called by the key in '" + rule.field() + "'");
            }
        }
        org.julclang.state.api.Assets.check(call.receivedAssets, what + ": received assets");
        Window window = call.usesTime()
                ? Window.open(contract.runtime, spec, call.notBefore, call.notAfter, current, what) : null;
        Ctx ctx = new Ctx(callerPkh, call.received, balance,
                window == null ? BigInteger.ZERO : window.fromMs(), window == null ? BigInteger.ZERO : window.toMs());
        byte[] policy = HexUtil.decodeHexString(contract.policyId);
        byte[] id = HexUtil.decodeHexString(instanceId);
        var incoming = call.receives ? AssetTx.incoming(unit()) : org.julclang.core.types.JulcList.<org.julclang.state.api.DepositClaim>empty();
        var funds = new org.julclang.state.api.Funds(policy, id, call.receivedAssets, AssetTx.held(inValue, policy, id), incoming);
        boolean maps = spec.layout().hasMaps();
        MerkleSupport merkle = maps ? new MerkleSupport(contract.runtime.mapStore()) : null;
        Transition<S> t = maps ? merkle.run(() -> call.assetRun.apply(current, null, ctx, funds))   // the developer's method
                : call.assetRun.apply(current, null, ctx, funds);
        TxOutRef ownRef = new TxOutRef(new TxId(HexUtil.decodeHexString(stateUtxo.getTxHash())),
                BigInteger.valueOf(stateUtxo.getOutputIndex()));
        S stored = t.next();
        java.util.List<org.julclang.state.api.mpf.MapClaim> claims = java.util.List.of();
        if (maps) {
            var prepared = merkle.prepare(policy, spec.layout(), current, destroy ? null : t.next(), ownRef);
            claims = prepared.claims();
            stored = prepared.stored();
            MerkleSupport.verifyReplay(spec.layout(), current, claims, s -> call.assetRun.apply(s, null, ctx, funds), t);
        }
        PlutusData redeemer = PlutusDataAdapter.convert(call.redeemerFor(ctx, t));
        Utxo ref = contract.findReferenceScript();
        if (ref == null) throw new JoustException(what + ": the " + spec.name() + " reference script is not on chain");
        var tx = TxRunner.collect(TxRunner.newTx(), stateUtxo, redeemer).readFrom(ref);
        TxOutRef own = new TxOutRef(new TxId(HexUtil.decodeHexString(stateUtxo.getTxHash())),
                BigInteger.valueOf(stateUtxo.getOutputIndex()));
        PlutusData datum = destroy ? null : PlutusDataAdapter.convert(stored);
        var applied = AssetTx.apply(tx, contract, call.caller, what, own, inValue, call.received, call.receivedAssets, t,
                unit(), id, datum, destroy ? call.burnRedeemer : null, call.byAction, false, null, incoming);
        if (maps) contract.runtime.mapVerifier().attach(tx, claims, call.caller);
        List<byte[]> signers = new ArrayList<>();
        if (call.usesCtx || !call.signers.isEmpty()) signers.add(callerPkh);
        for (Account a : call.extraSigners()) signers.add(Keys.pkh(a));
        call.applyAuthorization(tx);
        call.applyReads(tx);
        var scripts = new ArrayList<com.bloxbean.cardano.client.plutus.spec.PlutusScript>(List.of(contract.script));
        if (!applied.sends().isEmpty()) scripts.add(contract.runtime.outputVerifier().script());
        if (maps) scripts.add(contract.runtime.mapVerifier().script());
        String txHash = new TxRunner(contract.runtime, call.extraSigners()).submit(tx, call.caller, window, signers.toArray(new byte[0][]),
                applied.expected(), applied.payouts(), applied.sends(),
                scripts.toArray(new com.bloxbean.cardano.client.plutus.spec.PlutusScript[0]), what);
        var payouts = new ArrayList<Payout>();
        t.payouts().forEach(payouts::add);
        if (!destroy) {
            awaitState(txHash);
            return new CallResult<>(txHash, stored, payouts, eventsOf(t));
        }
        awaitGone();
        return new CallResult<>(txHash, null, payouts, eventsOf(t));
    }

    /** The current state UTxO's full value (ADR-0007). */
    public org.julclang.ledger.Value value() {
        return AssetTx.valueOf(utxo());
    }

    /** After a destroy: waits until the indexer no longer shows the state UTxO. */
    private void awaitGone() {
        if (TxRunner.capture() != null) return;
        for (int i = 0; i < 60; i++) {
            try {
                utxo();
            } catch (InstanceNotFoundException gone) {
                return;
            }
            TxRunner.sleep(500);
        }
    }

    /** Waits until the indexer shows the state UTxO created by {@code txHash}. */
    void awaitState(String txHash) {
        if (TxRunner.capture() != null) return;   // composed: Compose submits and confirms
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

    static java.util.List<Object> eventsOf(Transition<?> t) {
        var out = new java.util.ArrayList<Object>();
        if (t.events() != null) t.events().forEach(out::add);
        return out;
    }
}
