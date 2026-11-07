package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
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
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * One owned object of an instance (ADR-0003): its own UTxO with a 29-byte object token and a {@code Holding(instance,
 * value)} datum. Calls on it spend only that UTxO (and read the instance through a reference input when the method
 * takes {@code @Shared}), so different objects never contend.
 *
 * @param <S> the instance state type
 * @param <R> the owned record type
 */
public final class OwnedObject<S, R> {

    /** How the generated validator's {@code Holding} wraps the owned record. */
    public record Codec<R>(Class<?> holdingClass, Function<Object, R> value, BiFunction<byte[], R, Object> wrap) {}

    /** How to create an object: filled in by a generated client. */
    public record Create<R>(String name, Account caller, BigInteger received, boolean usesCtx, boolean needsShared,
                            BiFunction<Object, Ctx, R> run, Redeemer redeemer, org.julclang.ledger.Value receivedAssets,
                            AssetCreate<R> assetRun) {

        public Create(String name, Account caller, BigInteger received, boolean usesCtx, boolean needsShared,
                      BiFunction<Object, Ctx, R> run, Redeemer redeemer) {
            this(name, caller, received, usesCtx, needsShared, run, redeemer, org.julclang.state.api.Assets.none(), null);
        }

        /** An {@code @OwnedInit} of an asset-aware contract (ADR-0007). */
        @FunctionalInterface
        public interface AssetCreate<R> {
            Transition<R> apply(Object shared, Ctx ctx, org.julclang.state.api.Funds funds);
        }
        @FunctionalInterface
        public interface Redeemer {
            Object make(TxOutRef seed, byte[] instance, Ctx ctx);
        }
    }

    private final StateInstance<S> parent;
    private final String objectId;
    private final Codec<R> codec;

    OwnedObject(StateInstance<S> parent, String objectId, Codec<R> codec) {
        this.parent = parent;
        this.objectId = objectId.toLowerCase();
        this.codec = codec;
    }

    /** An existing object of {@code parent}. */
    public static <S, R> OwnedObject<S, R> at(StateInstance<S> parent, String objectId, Codec<R> codec) {
        return new OwnedObject<>(parent, objectId, codec);
    }

    public String objectId() { return objectId; }
    public String unit() { return contract().policyId + objectId; }
    public StateInstance<S> instance() { return parent; }

    private StateContractHandle<S> contract() {
        return parent.contract();
    }

    /** The object id a seed UTxO produces: {@code 0x4F ++ blake2b_224(txId ++ I2OSP(index, 2))}, hex. */
    public static String objectIdOf(String seedTxHash, int seedIndex) {
        byte[] tx = HexUtil.decodeHexString(seedTxHash);
        byte[] buf = Arrays.copyOf(tx, tx.length + 2);
        buf[tx.length] = (byte) (seedIndex >> 8);
        buf[tx.length + 1] = (byte) seedIndex;
        return "4f" + HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(buf));
    }

    public Utxo utxo() {
        var c = contract();
        try {
            var r = c.runtime.backend().getUtxoService().getUtxos(c.address, unit(), 10, 1);
            if (r.isSuccessful() && r.getValue() != null) {
                for (Utxo u : r.getValue()) {
                    if (u.getAmount().stream().anyMatch(a -> a.getUnit().equalsIgnoreCase(unit()))) return u;
                }
            }
        } catch (Exception e) {
            throw new JoustException("cannot query the object UTxO", e);
        }
        throw new InstanceNotFoundException(c.spec.name() + " object " + objectId + " not found");
    }

    /** The owned record, decoded from the object's Holding datum. */
    public R value() {
        return decode(utxo());
    }

    public BigInteger lovelace() {
        return lovelaceOf(utxo());
    }

    // ------------------------------------------------------------------ create

    /** Creates an object under {@code parent} (the instance is read through a reference input). */
    public static <S, R> OwnedObject<S, R> create(StateInstance<S> parent, Create<R> spec, Codec<R> codec) {
        var contract = parent.contract();
        var rt = contract.runtime;
        String what = contract.spec.name() + "." + spec.name();
        Utxo ref = contract.ensureReferenceScript(spec.caller());
        Utxo instanceUtxo = parent.utxo();
        Object shared = parent.state();
        Utxo seed = contract.pickSeedFor(spec.caller());
        String objectId = objectIdOf(seed.getTxHash(), seed.getOutputIndex());
        var object = new OwnedObject<>(parent, objectId, codec);
        byte[] callerPkh = Keys.pkh(spec.caller());
        Window window = spec.usesCtx() ? Window.open(rt, contract.spec, List.of(), List.of(), null, what) : null;
        Ctx ctx = new Ctx(callerPkh, spec.received(), BigInteger.ZERO,
                window == null ? BigInteger.ZERO : window.fromMs(), window == null ? BigInteger.ZERO : window.toMs());
        if (spec.assetRun() != null) return createAssets(parent, spec, codec, ref, instanceUtxo, shared, seed, object, ctx, window, what);
        R value = spec.run().apply(shared, ctx);
        byte[] instance = HexUtil.decodeHexString(parent.instanceId());
        PlutusData datum = PlutusDataAdapter.convert(codec.wrap().apply(instance, value));
        Datums.checkSize(contract.spec, datum, what);
        var seedRef = new TxOutRef(new TxId(HexUtil.decodeHexString(seed.getTxHash())), BigInteger.valueOf(seed.getOutputIndex()));
        PlutusData redeemer = PlutusDataAdapter.convert(spec.redeemer().make(seedRef, instance, ctx));
        BigInteger lovelace = BigInteger.valueOf(contract.spec.minAda()).add(spec.received());
        var tx = new ScriptTx()
                .collectFrom(seed)
                .readFrom(ref)
                .readFrom(instanceUtxo)
                .mintAsset(contract.policyId, List.of(new Asset("0x" + objectId, BigInteger.ONE)), redeemer)
                .payToContract(contract.address, List.of(Amount.lovelace(lovelace), new Amount(object.unit(), BigInteger.ONE)), datum);
        String txHash = new TxRunner(rt).submit(tx, spec.caller(), window, spec.usesCtx() ? new byte[][]{callerPkh} : new byte[0][],
                new TxRunner.ExpectedState(object.unit(), lovelace, datum), List.of(), new PlutusScript[]{contract.script}, what);
        object.awaitUtxo(txHash);
        return object;
    }

    private static <S, R> OwnedObject<S, R> createAssets(StateInstance<S> parent, Create<R> spec, Codec<R> codec, Utxo ref,
                                                       Utxo instanceUtxo, Object shared, Utxo seed, OwnedObject<S, R> object,
                                                       Ctx ctx, Window window, String what) {
        var contract = parent.contract();
        org.julclang.state.api.Assets.check(spec.receivedAssets(), what + ": received assets");
        byte[] policy = HexUtil.decodeHexString(contract.policyId);
        byte[] id = HexUtil.decodeHexString(object.objectId);
        var funds = new org.julclang.state.api.Funds(policy, id, spec.receivedAssets(), org.julclang.state.api.Assets.none());
        Transition<R> t = spec.assetRun().apply(shared, ctx, funds);
        byte[] instance = HexUtil.decodeHexString(parent.instanceId());
        PlutusData datum = PlutusDataAdapter.convert(codec.wrap().apply(instance, t.next()));
        var seedRef = new TxOutRef(new TxId(HexUtil.decodeHexString(seed.getTxHash())), BigInteger.valueOf(seed.getOutputIndex()));
        Object redeemer = spec.redeemer().make(seedRef, instance, ctx);
        var tx = new ScriptTx().collectFrom(seed).readFrom(ref).readFrom(instanceUtxo);
        var inValue = org.julclang.state.api.Assets.add(org.julclang.state.api.Assets.lovelace(BigInteger.valueOf(contract.spec.minAda())),
                org.julclang.state.api.Assets.of(policy, id, BigInteger.ONE));
        var applied = AssetTx.apply(tx, contract, spec.caller(), what, null, inValue, spec.received(), spec.receivedAssets(), t,
                object.unit(), id, datum, redeemer, null, true, seed);
        byte[] callerPkh = Keys.pkh(spec.caller());
        String txHash = new TxRunner(contract.runtime).submit(tx, spec.caller(), window,
                spec.usesCtx() ? new byte[][]{callerPkh} : new byte[0][], applied.expected(), List.of(), List.of(),
                new PlutusScript[]{contract.script}, what);
        object.awaitUtxo(txHash);
        return object;
    }

    // ------------------------------------------------------------------ calls

    /** Runs an owned action or destroy; retries only if the node rejected it because the object or instance moved. */
    public CallResult<R> call(Call<R> call) {
        SubmitException last = null;
        for (int attempt = 0; attempt <= contract().runtime.maxRetries(); attempt++) {
            Utxo own = utxo();
            try {
                return attempt(call, own);
            } catch (SubmitException e) {
                last = e;
                Utxo now;
                try {
                    now = utxo();
                } catch (InstanceNotFoundException gone) {
                    throw e;
                }
                boolean moved = !(now.getTxHash().equals(own.getTxHash()) && now.getOutputIndex() == own.getOutputIndex());
                if (!moved && call.sharedRun == null) throw e;
                contract().runtime.log(call.name + ": the object or instance moved (attempt " + (attempt + 1) + "), re-running");
            }
        }
        throw new ContentionException(call.name + ": the state kept moving", last);
    }

    private CallResult<R> attempt(Call<R> call, Utxo own) {
        if (call.assetRun != null) return attemptAssets(call, own);
        var contract = contract();
        var spec = contract.spec;
        String what = spec.name() + "." + call.name;
        R current = decode(own);
        byte[] callerPkh = Keys.pkh(call.caller);
        BigInteger inLovelace = lovelaceOf(own);
        BigInteger balance = call.kind == Call.Kind.DESTROY ? inLovelace : inLovelace.subtract(BigInteger.valueOf(spec.minAda()));
        for (var rule : call.signers) {
            if (!Arrays.equals(rule.key().apply(current), callerPkh)) {
                throw new RequireException(what + ": must be called by the key in '" + rule.field() + "'");
            }
        }
        Window window = call.usesTime() ? Window.open(contract.runtime, spec, call.notBefore, call.notAfter, current, what) : null;
        Ctx ctx = new Ctx(callerPkh, call.received, balance,
                window == null ? BigInteger.ZERO : window.fromMs(), window == null ? BigInteger.ZERO : window.toMs());
        Utxo sharedUtxo = call.sharedRun != null ? parent.utxo() : null;
        Transition<R> t = call.sharedRun != null
                ? call.sharedRun.apply(current, parent.state(), ctx)
                : call.run.apply(current, ctx);

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
        var tx = new ScriptTx().collectFrom(own, redeemer).readFrom(ref);
        if (sharedUtxo != null) tx.readFrom(sharedUtxo);
        TxRunner.ExpectedState expected = null;
        if (call.kind == Call.Kind.ACTION) {
            BigInteger next = inLovelace.add(call.received).subtract(total);
            if (next.compareTo(BigInteger.valueOf(spec.minAda())) < 0) throw new RequireException(what + ": payouts exceed the object's funds");
            PlutusData datum = PlutusDataAdapter.convert(codec.wrap().apply(HexUtil.decodeHexString(parent.instanceId()), t.next()));
            Datums.checkSize(spec, datum, what);
            tx.payToContract(contract.address, List.of(Amount.lovelace(next), new Amount(unit(), BigInteger.ONE)), datum);
            expected = new TxRunner.ExpectedState(unit(), next, datum);
        } else {
            if (total.compareTo(inLovelace) != 0) {
                throw new RequireException(what + ": closing an object must pay out everything it holds (" + inLovelace
                        + " lovelace), but pays " + total);
            }
            tx.mintAsset(contract.policyId, List.of(new Asset("0x" + objectId, BigInteger.ONE.negate())),
                    PlutusDataAdapter.convert(call.burnRedeemer));
        }
        PlutusData tag = PlutusDataAdapter.convert(new TxOutRef(new TxId(HexUtil.decodeHexString(own.getTxHash())),
                BigInteger.valueOf(own.getOutputIndex())));
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
        String txHash = new TxRunner(contract.runtime, call.extraSigners()).submit(tx, call.caller, window, signers.toArray(new byte[0][]),
                expected, expectedPayouts, new PlutusScript[]{contract.script}, what);
        if (call.kind == Call.Kind.ACTION) {
            awaitUtxo(txHash);
            return new CallResult<>(txHash, t.next(), payouts, eventsOf(t));
        }
        return new CallResult<>(txHash, null, payouts, eventsOf(t));
    }

    /** An owned call of an asset-aware contract (ADR-0007). */
    private CallResult<R> attemptAssets(Call<R> call, Utxo own) {
        var contract = contract();
        var spec = contract.spec;
        String what = spec.name() + "." + call.name;
        R current = decode(own);
        byte[] callerPkh = Keys.pkh(call.caller);
        org.julclang.ledger.Value inValue = AssetTx.valueOf(own);
        BigInteger inLovelace = lovelaceOf(own);
        boolean destroy = call.kind == Call.Kind.DESTROY;
        BigInteger balance = destroy ? inLovelace : inLovelace.subtract(BigInteger.valueOf(spec.minAda()));
        for (var rule : call.signers) {
            if (!Arrays.equals(rule.key().apply(current), callerPkh)) {
                throw new RequireException(what + ": must be called by the key in '" + rule.field() + "'");
            }
        }
        org.julclang.state.api.Assets.check(call.receivedAssets, what + ": received assets");
        Window window = call.usesTime() ? Window.open(contract.runtime, spec, call.notBefore, call.notAfter, current, what) : null;
        Ctx ctx = new Ctx(callerPkh, call.received, balance,
                window == null ? BigInteger.ZERO : window.fromMs(), window == null ? BigInteger.ZERO : window.toMs());
        Utxo sharedUtxo = call.needsShared ? parent.utxo() : null;
        Object shared = call.needsShared ? parent.state() : null;
        byte[] policy = HexUtil.decodeHexString(contract.policyId);
        byte[] id = HexUtil.decodeHexString(objectId);
        var funds = new org.julclang.state.api.Funds(policy, id, call.receivedAssets, AssetTx.held(inValue, policy, id));
        Transition<R> t = call.assetRun.apply(current, shared, ctx, funds);
        PlutusData redeemer = PlutusDataAdapter.convert(call.redeemerFor(ctx, t));
        Utxo ref = contract.findReferenceScript();
        var tx = new ScriptTx().collectFrom(own, redeemer).readFrom(ref);
        if (sharedUtxo != null) tx.readFrom(sharedUtxo);
        TxOutRef ownRef = new TxOutRef(new TxId(HexUtil.decodeHexString(own.getTxHash())), BigInteger.valueOf(own.getOutputIndex()));
        PlutusData datum = destroy ? null
                : PlutusDataAdapter.convert(codec.wrap().apply(HexUtil.decodeHexString(parent.instanceId()), t.next()));
        var applied = AssetTx.apply(tx, contract, call.caller, what, ownRef, inValue, call.received, call.receivedAssets, t,
                unit(), id, datum, destroy ? call.burnRedeemer : null, call.byAction, false);
        List<byte[]> signers = new ArrayList<>();
        if (call.usesCtx || !call.signers.isEmpty()) signers.add(callerPkh);
        for (Account a : call.extraSigners()) signers.add(Keys.pkh(a));
        call.applyAuthorization(tx);
        var scripts = new ArrayList<PlutusScript>(List.of(contract.script));
        if (!applied.sends().isEmpty()) scripts.add(contract.runtime.outputVerifier().script());
        String txHash = new TxRunner(contract.runtime, call.extraSigners()).submit(tx, call.caller, window, signers.toArray(new byte[0][]),
                applied.expected(), applied.payouts(), applied.sends(), scripts.toArray(new PlutusScript[0]), what);
        var payouts = new ArrayList<Payout>();
        t.payouts().forEach(payouts::add);
        if (!destroy) {
            awaitUtxo(txHash);
            return new CallResult<>(txHash, t.next(), payouts, eventsOf(t));
        }
        return new CallResult<>(txHash, null, payouts, eventsOf(t));
    }

    /** The object UTxO's full value (ADR-0007). */
    public org.julclang.ledger.Value fullValue() {
        return AssetTx.valueOf(utxo());
    }

    private void awaitUtxo(String txHash) {
        for (int i = 0; i < 60; i++) {
            try {
                if (utxo().getTxHash().equals(txHash)) return;
            } catch (InstanceNotFoundException ignored) {
                // not indexed yet
            }
            TxRunner.sleep(500);
        }
        throw new PendingTransactionException(txHash, "included, but the object is not visible yet");
    }

    private R decode(Utxo u) {
        try {
            var data = PlutusData.deserialize(HexUtil.decodeHexString(u.getInlineDatum()));
            return codec.value().apply(PlutusDataAdapter.convert(data, codec.holdingClass()));
        } catch (Exception e) {
            throw new JoustException("cannot decode the owned object", e);
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
