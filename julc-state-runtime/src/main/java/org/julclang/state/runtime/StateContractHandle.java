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

    final Object params;
    final String rewardAddress;

    private static byte[] hashOf(PlutusV3Script s) {
        try {
            return s.getScriptHash();
        } catch (Exception e) {
            throw new JoustException("cannot hash a script", e);
        }
    }

    /** The reward address of this script's stake credential (ADR-0010). */
    public String rewardAddress() { return rewardAddress; }

    /** Registers this script's stake credential (legacy registration: no script witness; anyone may). */
    public void registerStake(Account payer) {
        var tx = new com.bloxbean.cardano.client.quicktx.Tx().registerStakeAddress(rewardAddress).from(payer.baseAddress());
        new TxRunner(runtime).submitPlain(tx, payer, "register the " + spec.name() + " stake credential");
    }

    /**
     * Delegates this script's stake to {@code poolId} (bech32 or hex): runs the contract's {@code @Certify} handler,
     * signed by {@code caller} and {@code cosigners} ({@code @Quorum}; ADR-0010).
     */
    public String delegateTo(String poolId, Account caller, Account... cosigners) {
        Utxo ref = ensureReferenceScript(caller);
        var tx = new ScriptTx().readFrom(ref)
                .delegateTo(new com.bloxbean.cardano.client.address.Address(rewardAddress), poolId,
                        com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0));
        return submitPurpose(tx, caller, cosigners, "delegate the " + spec.name() + " stake");
    }

    /** The rewards withdrawable from this script's stake credential. */
    public BigInteger rewardBalance() {
        try {
            var r = runtime.backend().getAccountService().getAccountInformation(rewardAddress);
            if (!r.isSuccessful() || r.getValue() == null || r.getValue().getWithdrawableAmount() == null) return BigInteger.ZERO;
            return new BigInteger(r.getValue().getWithdrawableAmount());
        } catch (Exception e) {
            return BigInteger.ZERO;
        }
    }

    /**
     * Withdraws {@code amount} rewards with the {@code @Withdraw} redeemer, sending them as the handler returned:
     * each send claimed in the output registry by RewardRef (ADR-0010 r2).
     */
    public String withdrawRewards(Object rewardsRedeemer, BigInteger amount, org.julclang.core.types.JulcList<org.julclang.state.api.Send> sends,
                                  Account caller, Account... cosigners) {
        Utxo ref = ensureReferenceScript(caller);
        var tx = new ScriptTx().readFrom(ref).withdraw(rewardAddress, amount, PlutusDataAdapter.convert(rewardsRedeemer));
        byte[] h = hashOf(script);
        var by = new TxOutRef(new TxId(com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(h)), BigInteger.valueOf(-1));
        var claims = new java.util.ArrayList<org.julclang.state.api.OutputClaim>();
        var expected = new java.util.ArrayList<TxRunner.ExpectedSend>();
        int index = 0;
        for (var snd : sends) {
            String addr = LedgerAddresses.toBech32(snd.to(), runtime.network());
            var amounts = AssetTx.amounts(snd.value());
            tx.payToAddress(addr, amounts);
            claims.add(new org.julclang.state.api.OutputClaim(BigInteger.valueOf(index), by));
            expected.add(new TxRunner.ExpectedSend(index, addr, null, null, snd.value()));
            index++;
        }
        if (!claims.isEmpty()) {
            org.julclang.core.types.JulcList<org.julclang.state.api.OutputClaim> list = org.julclang.core.types.JulcList.empty();
            for (int i = claims.size() - 1; i >= 0; i--) list = list.prepend(claims.get(i));
            runtime.outputVerifier().attachRedeemer(tx, new org.julclang.state.api.OutputClaims(list, org.julclang.core.types.JulcList.empty()), caller);
        }
        var signers = new java.util.ArrayList<byte[]>();
        signers.add(Keys.pkh(caller));
        for (Account a : cosigners) signers.add(Keys.pkh(a));
        var scripts = new java.util.ArrayList<com.bloxbean.cardano.client.plutus.spec.PlutusScript>(List.of(script));
        if (!claims.isEmpty()) scripts.add(runtime.outputVerifier().script());
        return new TxRunner(runtime, List.of(cosigners)).submit(tx, caller, null, signers.toArray(new byte[0][]), null,
                List.of(), expected, scripts.toArray(new com.bloxbean.cardano.client.plutus.spec.PlutusScript[0]),
                "withdraw " + spec.name() + " rewards");
    }

    private String submitPurpose(ScriptTx tx, Account caller, Account[] cosigners, String what) {
        var signers = new java.util.ArrayList<byte[]>();
        signers.add(Keys.pkh(caller));
        for (Account a : cosigners) signers.add(Keys.pkh(a));
        return new TxRunner(runtime, List.of(cosigners)).submit(tx, caller, null, signers.toArray(new byte[0][]), null,
                List.of(), List.of(), new com.bloxbean.cardano.client.plutus.spec.PlutusScript[]{script}, what);
    }

    public StateContractHandle(StateRuntime runtime, ContractSpec<S> spec) {
        this(runtime, spec, null);
    }

    /**
     * A contract with deploy-time parameters (ADR-0009 D1): the script is the validator with {@code params} applied, so
     * its policy id and address belong to this parameter set.
     */
    public StateContractHandle(StateRuntime runtime, ContractSpec<S> spec, Object params) {
        this.runtime = runtime;
        this.spec = spec;
        this.params = params;
        this.script = scriptFor(spec, params);
        try {
            this.policyId = HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new JoustException("cannot hash the " + spec.name() + " script", e);
        }
        this.address = spec.stakeSelf()
                ? AddressProvider.getBaseAddress(Credential.fromScript(hashOf(script)), Credential.fromScript(hashOf(script)),
                        runtime.network()).toBech32()
                : AddressProvider.getEntAddress(script, runtime.network()).toBech32();
        this.rewardAddress = AddressProvider.getRewardAddress(script, runtime.network()).toBech32();
    }

    /** The applied script of {@code spec} for {@code params} (null: none); refuses one too large to publish by reference. */
    public static PlutusV3Script scriptFor(ContractSpec<?> spec, Object params) {
        PlutusV3Script s = params == null ? JulcScriptLoader.load(spec.validatorClass())
                : JulcScriptLoader.load(spec.validatorClass(), PlutusDataAdapter.convert(params));
        int size = HexUtil.decodeHexString(s.getCborHex()).length;
        if (size > 15_000) {
            throw new JoustException(spec.name() + ": the applied script is " + size + " bytes; a reference script must fit a"
                    + " 16 KB transaction (ADR-0009 r2)");
        }
        return s;
    }

    /** The policy id (script hash) of {@code spec} with {@code params} applied: pin it to tell deployments apart. */
    public static String policyIdFor(ContractSpec<?> spec, Object params) {
        try {
            return HexUtil.encodeHexString(scriptFor(spec, params).getScriptHash());
        } catch (JoustException e) {
            throw e;
        } catch (Exception e) {
            throw new JoustException("cannot hash the " + spec.name() + " script", e);
        }
    }

    /** The deploy-time parameters, or null. */
    public Object params() { return params; }

    /** The reference-script UTxO the contract's transactions read the script from (null until found or created). */
    private volatile Utxo referenceScript;

    public String policyId() { return policyId; }
    public String address() { return address; }
    public PlutusV3Script script() { return script; }
    public ContractSpec<S> spec() { return spec; }
    public StateRuntime runtime() { return runtime; }

    /**
     * The UTxO holding this contract's script as a reference script (CIP-33), at the contract's own address without a
     * thread token: the validator never lets it be spent (I10), so it stays available. Found on chain, or null.
     */
    public Utxo findReferenceScript() {
        if (referenceScript != null) return referenceScript;
        try {
            for (int page = 1; page < 20; page++) {
                var r = runtime.backend().getUtxoService().getUtxos(address, 100, page);
                if (!r.isSuccessful() || r.getValue() == null || r.getValue().isEmpty()) break;
                for (Utxo u : r.getValue()) {
                    if (policyId.equalsIgnoreCase(u.getReferenceScriptHash())) {
                        referenceScript = u;
                        return u;
                    }
                }
            }
        } catch (Exception e) {
            throw new JoustException("cannot look up the " + spec.name() + " reference script", e);
        }
        return null;
    }

    /**
     * Publishes the script as a reference script, paid by {@code payer}, unless it is already on chain. Its min-ADA
     * deposit (about 4.3 lovelace per script byte) stays locked with it.
     */
    public Utxo ensureReferenceScript(Account payer) {
        Utxo existing = findReferenceScript();
        if (existing != null) return existing;
        var tx = new com.bloxbean.cardano.client.quicktx.Tx()
                .payToAddress(address, List.of(Amount.lovelace(referenceScriptDeposit())), script)
                .from(payer.baseAddress());
        String txHash = new TxRunner(runtime).submitPlain(tx, payer, "publish the " + spec.name() + " reference script");
        for (int i = 0; i < 60 && findReferenceScript() == null; i++) TxRunner.sleep(500);
        Utxo published = findReferenceScript();
        if (published == null) throw new PendingTransactionException(txHash, "reference script not visible yet");
        runtime.log("published the " + spec.name() + " reference script in " + txHash);
        return published;
    }

    private BigInteger referenceScriptDeposit() {
        try {
            ProtocolParams pp = runtime.backend().getEpochService().getProtocolParameters().getValue();
            var out = TransactionOutput.builder()
                    .address(address)
                    .value(Value.builder().coin(BigInteger.valueOf(2_000_000)).build())
                    .scriptRef(script)
                    .build();
            return new MinAdaCalculator(pp).calculateMinAda(out).add(BigInteger.valueOf(1_000_000));
        } catch (Exception e) {
            throw new JoustException("cannot compute the reference script deposit", e);
        }
    }

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
        // Publish reference scripts first: doing so spends the caller's UTxOs, so the seed is picked afterwards.
        Utxo ref = ensureReferenceScript(caller);
        boolean maps = MerkleSupport.hasMaps(spec.stateClass());
        if (maps) runtime.mapVerifier().ensure(caller);
        Utxo seed = pickSeed(caller);
        String instanceId = instanceIdOf(seed.getTxHash(), seed.getOutputIndex());
        var instance = new StateInstance<>(this, instanceId);

        Window window = d.usesCtx() ? Window.open(runtime, spec, List.of(), List.of(), null, "deploy") : null;
        Ctx ctx = window == null
                ? new Ctx(callerPkh, d.received(), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO)
                : new Ctx(callerPkh, d.received(), BigInteger.ZERO, window.fromMs(), window.toMs());
        if (d.assetRun() != null) return deployAssets(d, ref, seed, instance, ctx, window, callerPkh);
        MerkleSupport merkle = maps ? new MerkleSupport(runtime.mapStore()) : null;
        S made = maps ? merkle.run(() -> d.run().apply(ctx)) : d.run().apply(ctx);   // the developer's @Init, on the JVM
        var seedOwner = new TxOutRef(new TxId(HexUtil.decodeHexString(seed.getTxHash())),
                BigInteger.valueOf(seed.getOutputIndex()));
        java.util.List<org.julclang.state.api.mpf.MapClaim> claims = java.util.List.of();
        S initial = made;
        if (maps) {
            var prepared = merkle.prepare(spec.stateClass(), made, made, seedOwner);
            claims = prepared.claims();
            initial = prepared.stored();
        }
        PlutusData datum = PlutusDataAdapter.convert(initial);
        Datums.checkSize(spec, datum, "deploy");
        Datums.checkCanonical(spec, datum, "deploy");

        var seedRef = new TxOutRef(new TxId(HexUtil.decodeHexString(seed.getTxHash())),
                BigInteger.valueOf(seed.getOutputIndex()));
        PlutusData redeemer = PlutusDataAdapter.convert(d.redeemer().make(seedRef, ctx));
        BigInteger lovelace = BigInteger.valueOf(spec.minAda()).add(d.received());

        var tx = TxRunner.collect(TxRunner.scriptTx(), seed, null)
                .readFrom(ref);
        TxRunner.mint(tx, policyId, List.of(new Asset("0x" + instanceId, BigInteger.ONE)), redeemer);
        tx.payToContract(address, List.of(Amount.lovelace(lovelace), new Amount(instance.unit(), BigInteger.ONE)), datum);
        if (maps) runtime.mapVerifier().attach(tx, claims, caller);

        var expected = new TxRunner.ExpectedState(instance.unit(), lovelace, datum);
        String txHash = new TxRunner(runtime).submit(tx, caller, window, d.usesCtx() ? new byte[][]{callerPkh} : new byte[0][],
                expected, List.of(), maps ? new com.bloxbean.cardano.client.plutus.spec.PlutusScript[]{script,
                        runtime.mapVerifier().script()} : new com.bloxbean.cardano.client.plutus.spec.PlutusScript[]{script},
                "deploy " + spec.name());
        instance.awaitState(txHash);
        return new DeployResult<>(instance, txHash, initial);
    }

    /** Deploys an asset-aware contract (ADR-0007): the init may take native assets in. */
    private DeployResult<S> deployAssets(Deploy<S> d, Utxo ref, Utxo seed, StateInstance<S> instance, Ctx ctx, Window window,
                                         byte[] callerPkh) {
        String what = "deploy " + spec.name();
        org.julclang.state.api.Assets.check(d.receivedAssets(), what + ": received assets");
        byte[] policy = HexUtil.decodeHexString(policyId);
        byte[] id = HexUtil.decodeHexString(instance.instanceId());
        var funds = new org.julclang.state.api.Funds(policy, id, d.receivedAssets(), org.julclang.state.api.Assets.none(),
                org.julclang.core.types.JulcList.empty());
        Transition<S> t = d.assetRun().apply(ctx, funds);
        PlutusData datum = PlutusDataAdapter.convert(t.next());
        var seedRef = new TxOutRef(new TxId(HexUtil.decodeHexString(seed.getTxHash())), BigInteger.valueOf(seed.getOutputIndex()));
        Object redeemer = d.redeemer().make(seedRef, ctx);
        var tx = TxRunner.collect(TxRunner.scriptTx(), seed, null).readFrom(ref);
        var inValue = org.julclang.state.api.Assets.add(org.julclang.state.api.Assets.lovelace(BigInteger.valueOf(spec.minAda())),
                org.julclang.state.api.Assets.of(policy, id, BigInteger.ONE));
        var applied = AssetTx.apply(tx, this, d.caller(), what, null, inValue, d.received(), d.receivedAssets(), t,
                instance.unit(), id, datum, redeemer, null, true, seed);
        String txHash = new TxRunner(runtime).submit(tx, d.caller(), window, d.usesCtx() ? new byte[][]{callerPkh} : new byte[0][],
                applied.expected(), List.of(), List.of(), new com.bloxbean.cardano.client.plutus.spec.PlutusScript[]{script}, what);
        instance.awaitState(txHash);
        return new DeployResult<>(instance, txHash, t.next());
    }

    /**
     * Adopts {@code prior}, an instance of the previous version, as an instance of this contract (ADR-0011 D4). Runs
     * only inside a {@link Compose} that also runs the old instance's {@code @MigrateTo} destroy: the old state UTxO is
     * the seed (already spent there), its token burns, and the new instance holds exactly its lovelace.
     */
    public DeployResult<S> adopt(Deploy<S> d, Utxo prior) {
        if (TxRunner.capture() == null) {
            throw new JoustException("adopt runs inside a Compose, together with the old instance's migrating destroy");
        }
        Account caller = d.caller();
        byte[] callerPkh = Keys.pkh(caller);
        Utxo ref = ensureReferenceScript(caller);
        String instanceId = instanceIdOf(prior.getTxHash(), prior.getOutputIndex());
        var instance = new StateInstance<>(this, instanceId);
        Window window = d.usesCtx() ? Window.open(runtime, spec, List.of(), List.of(), null, "adopt") : null;
        Ctx ctx = window == null
                ? new Ctx(callerPkh, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO)
                : new Ctx(callerPkh, BigInteger.ZERO, BigInteger.ZERO, window.fromMs(), window.toMs());
        S initial = d.run().apply(ctx);
        PlutusData datum = PlutusDataAdapter.convert(initial);
        Datums.checkSize(spec, datum, "adopt");
        var seedRef = new TxOutRef(new TxId(HexUtil.decodeHexString(prior.getTxHash())), BigInteger.valueOf(prior.getOutputIndex()));
        PlutusData redeemer = PlutusDataAdapter.convert(d.redeemer().make(seedRef, ctx));
        BigInteger lovelace = prior.getAmount().stream().filter(a -> a.getUnit().equals("lovelace"))
                .map(Amount::getQuantity).findFirst().orElse(BigInteger.ZERO);
        var tx = TxRunner.scriptTx().readFrom(ref);
        TxRunner.mint(tx, policyId, List.of(new Asset("0x" + instanceId, BigInteger.ONE)), redeemer);
        tx.payToContract(address, List.of(Amount.lovelace(lovelace), new Amount(instance.unit(), BigInteger.ONE)), datum);
        String txHash = new TxRunner(runtime).submit(tx, caller, window, d.usesCtx() ? new byte[][]{callerPkh} : new byte[0][],
                new TxRunner.ExpectedState(instance.unit(), lovelace, datum), List.of(),
                new com.bloxbean.cardano.client.plutus.spec.PlutusScript[]{script}, "adopt " + spec.name());
        return new DeployResult<>(instance, txHash, initial);
    }

    /** Result of {@link #deploy}. */
    public record DeployResult<S>(StateInstance<S> instance, String txHash, S state) {}

    Utxo pickSeedFor(Account caller) {
        return pickSeed(caller);
    }

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

    /** Ledger minimum for an output of {@code amounts} at {@code addr}, with an inline datum if not null. */
    BigInteger minUtxoFor(String addr, PlutusData datum, List<Amount> amounts) {
        try {
            ProtocolParams pp = runtime.backend().getEpochService().getProtocolParameters().getValue();
            var multi = new java.util.ArrayList<com.bloxbean.cardano.client.transaction.spec.MultiAsset>();
            BigInteger coin = BigInteger.ZERO;
            for (Amount a : amounts) {
                if (a.getUnit().equals("lovelace")) {
                    coin = a.getQuantity();
                } else {
                    String unit = a.getUnit();
                    var ma = new com.bloxbean.cardano.client.transaction.spec.MultiAsset(unit.substring(0, 56),
                            new java.util.ArrayList<>(List.of(new Asset("0x" + unit.substring(56), a.getQuantity()))));
                    multi.add(ma);
                }
            }
            var b = TransactionOutput.builder().address(addr)
                    .value(Value.builder().coin(coin.max(BigInteger.ONE)).multiAssets(multi).build());
            if (datum != null) b.inlineDatum(datum);
            return new MinAdaCalculator(pp).calculateMinAda(b.build());
        } catch (Exception e) {
            throw new JoustException("cannot compute the minimum UTxO for an output", e);
        }
    }

    static void require(boolean ok, String message) {
        if (!ok) throw new RequireException(message);
    }
}
