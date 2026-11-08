package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
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
    public record Codec<R>(Class<?> holdingClass, Function<Object, R> value, BiFunction<byte[], R, Object> wrap,
                           Class<R> valueClass, java.util.List<Integer> mapFields) {
        public Codec(Class<?> holdingClass, Function<Object, R> value, BiFunction<byte[], R, Object> wrap) {
            this(holdingClass, value, wrap, null, null);
        }

        public Codec(Class<?> holdingClass, Function<Object, R> value, BiFunction<byte[], R, Object> wrap, Class<R> valueClass) {
            this(holdingClass, value, wrap, valueClass, null);
        }

        /** Where the owned value's MerkleMaps are (ADR-0012 D2): record components, or {@code mapFields} of its Data. */
        @SuppressWarnings("unchecked")
        MerkleSupport.Layout<R> layout() {
            if (mapFields != null) return (MerkleSupport.Layout<R>) MerkleSupport.dataLayout(mapFields);
            return valueClass != null ? MerkleSupport.layout(valueClass) : (MerkleSupport.Layout<R>) MerkleSupport.dataLayout(java.util.List.of());
        }

        /** The owned record has MerkleMap components (ADR-0012 D2). */
        boolean hasMaps() {
            return layout().hasMaps();
        }
    }

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
        // Publishing the map verifier spends the caller's UTxOs: do it before picking the seed.
        if (codec.hasMaps()) rt.mapVerifier().ensure(spec.caller());
        Utxo seed = contract.pickSeedFor(spec.caller());
        String objectId = objectIdOf(seed.getTxHash(), seed.getOutputIndex());
        var object = new OwnedObject<>(parent, objectId, codec);
        byte[] callerPkh = Keys.pkh(spec.caller());
        Window window = spec.usesCtx() ? Window.open(rt, contract.spec, List.of(), List.of(), null, what) : null;
        Ctx ctx = new Ctx(callerPkh, spec.received(), BigInteger.ZERO,
                window == null ? BigInteger.ZERO : window.fromMs(), window == null ? BigInteger.ZERO : window.toMs());
        if (spec.assetRun() != null) return createAssets(parent, spec, codec, ref, instanceUtxo, shared, seed, object, ctx, window, what);
        boolean maps = codec.hasMaps();
        MerkleSupport merkle = maps ? new MerkleSupport(rt.mapStore()) : null;
        R made = maps ? merkle.run(() -> spec.run().apply(shared, ctx)) : spec.run().apply(shared, ctx);
        var seedRef = new TxOutRef(new TxId(HexUtil.decodeHexString(seed.getTxHash())), BigInteger.valueOf(seed.getOutputIndex()));
        List<org.julclang.state.api.mpf.MapClaim> claims = List.of();
        R value = made;
        if (maps) {
            // ADR-0012 D2: the object's maps start empty; its writes are claimed by the seed, as for an instance.
            var prepared = merkle.prepareCreation(HexUtil.decodeHexString(contract.policyId), codec.layout(), made, seedRef);
            claims = prepared.claims();
            value = prepared.stored();
        }
        byte[] instance = HexUtil.decodeHexString(parent.instanceId());
        PlutusData datum = PlutusDataAdapter.convert(codec.wrap().apply(instance, value));
        Datums.checkSize(contract.spec, datum, what);
        PlutusData redeemer = PlutusDataAdapter.convert(spec.redeemer().make(seedRef, instance, ctx));
        BigInteger lovelace = BigInteger.valueOf(contract.spec.minAda()).add(spec.received());
        var tx = TxRunner.collect(TxRunner.newTx(), seed, null)
                .readFrom(ref)
                .readFrom(instanceUtxo);
        TxRunner.mint(tx, contract.policyId, List.of(new Asset("0x" + objectId, BigInteger.ONE)), redeemer);
        tx.payToContract(contract.address, List.of(Amount.lovelace(lovelace), new Amount(object.unit(), BigInteger.ONE)), datum);
        if (maps) rt.mapVerifier().attach(tx, claims, spec.caller());
        String txHash = new TxRunner(rt).submit(tx, spec.caller(), window, spec.usesCtx() ? new byte[][]{callerPkh} : new byte[0][],
                new TxRunner.ExpectedState(object.unit(), lovelace, datum), List.of(), scripts(contract, maps), what);
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
        var funds = new org.julclang.state.api.Funds(policy, id, spec.receivedAssets(), org.julclang.state.api.Assets.none(),
                org.julclang.core.types.JulcList.empty());
        boolean maps = codec.hasMaps();
        MerkleSupport merkle = maps ? new MerkleSupport(contract.runtime.mapStore()) : null;
        Transition<R> t = maps ? merkle.run(() -> spec.assetRun().apply(shared, ctx, funds)) : spec.assetRun().apply(shared, ctx, funds);
        byte[] instance = HexUtil.decodeHexString(parent.instanceId());
        var seedRef = new TxOutRef(new TxId(HexUtil.decodeHexString(seed.getTxHash())), BigInteger.valueOf(seed.getOutputIndex()));
        R value = t.next();
        List<org.julclang.state.api.mpf.MapClaim> claims = List.of();
        if (maps) {
            var prepared = merkle.prepareCreation(policy, codec.layout(), t.next(), seedRef);
            claims = prepared.claims();
            value = prepared.stored();
        }
        PlutusData datum = PlutusDataAdapter.convert(codec.wrap().apply(instance, value));
        Object redeemer = spec.redeemer().make(seedRef, instance, ctx);
        var tx = TxRunner.collect(TxRunner.newTx(), seed, null).readFrom(ref).readFrom(instanceUtxo);
        var inValue = org.julclang.state.api.Assets.add(org.julclang.state.api.Assets.lovelace(BigInteger.valueOf(contract.spec.minAda())),
                org.julclang.state.api.Assets.of(policy, id, BigInteger.ONE));
        var applied = AssetTx.apply(tx, contract, spec.caller(), what, null, inValue, spec.received(), spec.receivedAssets(), t,
                object.unit(), id, datum, redeemer, null, true, seed);
        if (maps) contract.runtime.mapVerifier().attach(tx, claims, spec.caller());
        byte[] callerPkh = Keys.pkh(spec.caller());
        String txHash = new TxRunner(contract.runtime).submit(tx, spec.caller(), window,
                spec.usesCtx() ? new byte[][]{callerPkh} : new byte[0][], applied.expected(), List.of(), List.of(),
                scripts(contract, maps), what);
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
                boolean stale = StateInstance.staleInput(e);
                if (!moved && call.sharedRun == null && !stale) throw e;
                if (stale) TxRunner.sleep(2000);
                contract().runtime.log(call.name + ": the object or instance moved (attempt " + (attempt + 1) + "), re-running");
            }
        }
        throw new ContentionException(call.name + ": the state kept moving", last);
    }

    /**
     * The instance the object belongs to now, from its Holding datum: after a move (ADR-0012 D3) it is no longer the
     * one this handle was looked up under.
     */
    private StateInstance<S> home(Utxo own) {
        try {
            var data = PlutusData.deserialize(HexUtil.decodeHexString(own.getInlineDatum()));
            Object holding = PlutusDataAdapter.convert(data, codec.holdingClass());
            // A described contract's Holding is Plutus data, Constr tag [instance, value] (ADR-0015 D4).
            byte[] inst = holding instanceof org.julclang.core.PlutusData.ConstrData c
                    ? ((org.julclang.core.PlutusData.BytesData) c.fields().get(0)).value()
                    : (byte[]) holding.getClass().getMethod("instance").invoke(holding);
            String hex = HexUtil.encodeHexString(inst);
            return hex.equalsIgnoreCase(parent.instanceId()) ? parent : new StateInstance<>(contract(), hex);
        } catch (Exception e) {
            throw new JoustException("cannot read the object's instance", e);
        }
    }

    private CallResult<R> attempt(Call<R> call, Utxo own) {
        StateInstance<S> home = home(own);
        if (call.into != null && TxRunner.capture() == null) {
            throw new JoustException(call.name + ": a move needs the destination's consent in the same transaction; compose it"
                    + " with the destination's @Admits call (Compose.of(...).add(move).add(admit), ADR-0012 D3)");
        }
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
        Utxo sharedUtxo = call.sharedRun != null ? home.utxo() : null;
        Object sharedState = call.sharedRun != null ? home.state() : null;
        java.util.function.Function<R, Transition<R>> method = o -> call.sharedRun != null
                ? call.sharedRun.apply(o, sharedState, ctx) : call.run.apply(o, ctx);
        boolean maps = codec.hasMaps();
        MerkleSupport merkle = maps ? new MerkleSupport(contract.runtime.mapStore()) : null;
        Transition<R> t = maps ? merkle.run(() -> method.apply(current)) : method.apply(current);
        TxOutRef ownRef = new TxOutRef(new TxId(HexUtil.decodeHexString(own.getTxHash())), BigInteger.valueOf(own.getOutputIndex()));
        List<org.julclang.state.api.mpf.MapClaim> claims = List.of();
        R stored = t.next();
        if (maps) {
            // ADR-0012 D2: the object's maps, claimed by its own input, as an instance's are.
            var prepared = merkle.prepare(HexUtil.decodeHexString(contract.policyId), codec.layout(), current, call.kind == Call.Kind.ACTION ? t.next() : null, ownRef);
            claims = prepared.claims();
            stored = prepared.stored();
            MerkleSupport.verifyReplay(codec.layout(), current, claims, o -> method.apply(o), t);
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
        var tx = TxRunner.collect(TxRunner.newTx(), own, redeemer).readFrom(ref);
        if (sharedUtxo != null) tx.readFrom(sharedUtxo);
        if (maps) contract.runtime.mapVerifier().attach(tx, claims, call.caller);
        TxRunner.ExpectedState expected = null;
        if (call.kind == Call.Kind.ACTION) {
            BigInteger next = inLovelace.add(call.received).subtract(total);
            if (next.compareTo(BigInteger.valueOf(spec.minAda())) < 0) throw new RequireException(what + ": payouts exceed the object's funds");
            PlutusData datum = PlutusDataAdapter.convert(codec.wrap().apply(HexUtil.decodeHexString(instanceAfter(call, own)), stored));
            Datums.checkSize(spec, datum, what);
            tx.payToContract(contract.address, List.of(Amount.lovelace(next), new Amount(unit(), BigInteger.ONE)), datum);
            expected = new TxRunner.ExpectedState(unit(), next, datum);
        } else {
            if (total.compareTo(inLovelace) != 0) {
                throw new RequireException(what + ": closing an object must pay out everything it holds (" + inLovelace
                        + " lovelace), but pays " + total);
            }
            TxRunner.mint(tx, contract.policyId, List.of(new Asset("0x" + objectId, BigInteger.ONE.negate())),
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
        for (Account a : call.extraSigners()) {
            byte[] k = Keys.pkh(a);
            if (signers.stream().noneMatch(s -> java.util.Arrays.equals(s, k))) signers.add(k);   // a set on the ledger
        }
        call.applyAuthorization(tx);
        call.applyReads(tx);
        String txHash = new TxRunner(contract.runtime, call.extraSigners()).submit(tx, call.caller, window, signers.toArray(new byte[0][]),
                expected, expectedPayouts, scripts(contract, maps), what);
        if (call.kind == Call.Kind.ACTION) {
            awaitUtxo(txHash);
            return new CallResult<>(txHash, stored, payouts, eventsOf(t));
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
        StateInstance<S> home = home(own);
        Utxo sharedUtxo = call.needsShared ? home.utxo() : null;
        Object shared = call.needsShared ? home.state() : null;
        byte[] policy = HexUtil.decodeHexString(contract.policyId);
        byte[] id = HexUtil.decodeHexString(objectId);
        var incoming = call.receives ? AssetTx.incoming(unit()) : org.julclang.core.types.JulcList.<org.julclang.state.api.DepositClaim>empty();
        var funds = new org.julclang.state.api.Funds(policy, id, call.receivedAssets, AssetTx.held(inValue, policy, id), incoming);
        boolean maps = codec.hasMaps();
        MerkleSupport merkle = maps ? new MerkleSupport(contract.runtime.mapStore()) : null;
        Transition<R> t = maps ? merkle.run(() -> call.assetRun.apply(current, shared, ctx, funds))
                : call.assetRun.apply(current, shared, ctx, funds);
        TxOutRef ownRef = new TxOutRef(new TxId(HexUtil.decodeHexString(own.getTxHash())), BigInteger.valueOf(own.getOutputIndex()));
        R stored = t.next();
        List<org.julclang.state.api.mpf.MapClaim> claims = List.of();
        if (maps) {
            var prepared = merkle.prepare(policy, codec.layout(), current, destroy ? null : t.next(), ownRef);
            claims = prepared.claims();
            stored = prepared.stored();
            MerkleSupport.verifyReplay(codec.layout(), current, claims, o -> call.assetRun.apply(o, shared, ctx, funds), t);
        }
        PlutusData redeemer = PlutusDataAdapter.convert(call.redeemerFor(ctx, t));
        Utxo ref = contract.findReferenceScript();
        var tx = TxRunner.collect(TxRunner.newTx(), own, redeemer).readFrom(ref);
        if (sharedUtxo != null) tx.readFrom(sharedUtxo);
        if (maps) contract.runtime.mapVerifier().attach(tx, claims, call.caller);
        PlutusData datum = destroy ? null
                : PlutusDataAdapter.convert(codec.wrap().apply(HexUtil.decodeHexString(instanceAfter(call, own)), stored));
        var applied = AssetTx.apply(tx, contract, call.caller, what, ownRef, inValue, call.received, call.receivedAssets, t,
                unit(), id, datum, destroy ? call.burnRedeemer : null, call.byAction, false, null, incoming);
        List<byte[]> signers = new ArrayList<>();
        if (call.usesCtx || !call.signers.isEmpty()) signers.add(callerPkh);
        for (Account a : call.extraSigners()) {
            byte[] k = Keys.pkh(a);
            if (signers.stream().noneMatch(s -> java.util.Arrays.equals(s, k))) signers.add(k);   // a set on the ledger
        }
        call.applyAuthorization(tx);
        call.applyReads(tx);
        var scripts = new ArrayList<PlutusScript>(List.of(contract.script));
        if (!applied.sends().isEmpty()) scripts.add(contract.runtime.outputVerifier().script());
        if (maps) scripts.add(contract.runtime.mapVerifier().script());
        String txHash = new TxRunner(contract.runtime, call.extraSigners()).submit(tx, call.caller, window, signers.toArray(new byte[0][]),
                applied.expected(), applied.payouts(), applied.sends(), scripts.toArray(new PlutusScript[0]), what);
        var payouts = new ArrayList<Payout>();
        t.payouts().forEach(payouts::add);
        if (!destroy) {
            awaitUtxo(txHash);
            return new CallResult<>(txHash, stored, payouts, eventsOf(t));
        }
        return new CallResult<>(txHash, null, payouts, eventsOf(t));
    }

    private static PlutusScript[] scripts(StateContractHandle<?> contract, boolean maps) {
        return maps ? new PlutusScript[]{contract.script, contract.runtime.mapVerifier().script()}
                : new PlutusScript[]{contract.script};
    }

    /** The instance the object belongs to after {@code call}: the destination of a move, else its current one. */
    private String instanceAfter(Call<R> call, Utxo own) {
        return call.into != null ? call.into : home(own).instanceId();
    }

    /** The object UTxO's full value (ADR-0007). */
    public org.julclang.ledger.Value fullValue() {
        return AssetTx.valueOf(utxo());
    }

    private void awaitUtxo(String txHash) {
        if (TxRunner.capture() != null) return;
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
