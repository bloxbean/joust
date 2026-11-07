package org.julclang.state.testkit;

import org.julclang.clientlib.JulcScriptLoader;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.flat.UplcFlatDecoder;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.Interval;
import org.julclang.ledger.IntervalBound;
import org.julclang.ledger.IntervalBoundType;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.runtime.ContractSpec;
import org.julclang.state.runtime.StateContractHandle;
import org.julclang.testkit.ScriptContextTestBuilder;
import org.julclang.testkit.ValidatorTest;
import org.julclang.vm.EvalResult;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Evaluates a generated validator, as compiled by the build (META-INF/plutus), against transactions shaped like
 * the ones {@code julc-state-runtime} submits (ADR-0001 D15). A transaction is accepted when every script it
 * triggers accepts: SPEND for each state input, MINT if it mints or burns under the policy.
 *
 * <pre>{@code
 * var h = StateHarness.of(CounterClient.SPEC);
 * Ctx ctx = h.ctx(ALICE, BigInteger.ZERO, BigInteger.ZERO);
 * TxModel create = h.create(new CounterValidator.CreateInstance(h.seed(), ctx), Counter.init(ctx), ctx);
 * h.assertAccepted(create);
 * Attacks.assertAllRejected(h, create);
 * }</pre>
 */
public final class StateHarness<S> {

    /** Start of the validity window every harness Ctx uses (POSIX ms), and its length. */
    public static final BigInteger T0 = BigInteger.valueOf(1_800_000_000_000L);
    public static final BigInteger WINDOW_MS = BigInteger.valueOf(120_000L);

    private final ContractSpec<S> spec;
    private final Program program;
    private final byte[] policy;
    private final TxOutRef seed;
    private final Program verifier;
    private final byte[] verifierHash;
    private final Program outputVerifier;
    private final byte[] outputVerifierHash;

    /** Where {@link #live} instances sit: the owner of their map claims. */
    public static final TxOutRef LIVE_REF = new TxOutRef(new TxId(fill(32, 0x7A)), BigInteger.ONE);

    private StateHarness(ContractSpec<S> spec) {
        this(spec, null);
    }

    private StateHarness(ContractSpec<S> spec, Object params) {
        this.spec = spec;
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());   // contracts may hash
        var script = org.julclang.state.runtime.StateContractHandle.scriptFor(spec, params);
        this.program = UplcFlatDecoder.decodeProgram(unwrapCbor(HexFormat.of().parseHex(script.getCborHex())));
        try {
            this.policy = script.getScriptHash();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        this.seed = new TxOutRef(new TxId(fill(32, 0x5E)), BigInteger.ZERO);
        var ov = JulcScriptLoader.load(org.julclang.state.verifier.OutputVerifier.class);
        this.outputVerifier = UplcFlatDecoder.decodeProgram(unwrapCbor(HexFormat.of().parseHex(ov.getCborHex())));
        try {
            this.outputVerifierHash = ov.getScriptHash();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var v = JulcScriptLoader.load(org.julclang.state.verifier.MapVerifier.class);
        this.verifier = UplcFlatDecoder.decodeProgram(unwrapCbor(HexFormat.of().parseHex(v.getCborHex())));
        try {
            this.verifierHash = v.getScriptHash();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Attaches the map verifier's claims to {@code tx} (its zero withdrawal and redeemer), as the runtime does. */
    public TxModel withClaims(TxModel tx, java.util.List<org.julclang.state.api.mpf.MapClaim> claims) {
        tx.mapClaims = claims;
        return tx;
    }

    public static <S> StateHarness<S> of(ContractSpec<S> spec) {
        return new StateHarness<>(spec);
    }

    /** A harness for a contract with deploy-time parameters (ADR-0009 D1): the validator with {@code params} applied. */
    public static <S> StateHarness<S> of(ContractSpec<S> spec, Object params) {
        return new StateHarness<>(spec, params);
    }

    public ContractSpec<S> spec() { return spec; }
    public byte[] policy() { return policy.clone(); }
    public long minAda() { return spec.minAda(); }

    /** The seed UTxO every harness deploy consumes. */
    public TxOutRef seed() { return seed; }

    /** The instance id the harness seed produces. */
    public byte[] instanceId() {
        return HexFormat.of().parseHex(StateContractHandle.instanceIdOf(HexFormat.of().formatHex(seed.txId().hash()),
                seed.index().intValueExact()));
    }

    /** The enterprise script address every instance lives at. */
    public Address stateAddress() {
        Credential own = new Credential.ScriptCredential(new ScriptHash(policy));
        return spec.stakeSelf()
                ? new Address(own, Optional.of(new org.julclang.ledger.StakingCredential.StakingHash(own)))
                : new Address(own, Optional.empty());
    }

    // ------------------------------------------------------------------ other purposes (ADR-0010)

    /** This script's own credential (its stake credential, DRep or committee credential). */
    public Credential ownCredential() {
        return new Credential.ScriptCredential(new ScriptHash(policy));
    }

    /** The claimant of reward sends in the output registry: TxOutRef(blake2b_256(own), -1). */
    public TxOutRef rewardRef() {
        return new TxOutRef(new TxId(com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(policy)), BigInteger.valueOf(-1));
    }

    /**
     * A reward withdrawal of {@code amount} with the {@code Rewards} redeemer: {@code sends} become outputs claimed by
     * {@link #rewardRef()}. Returns the transaction; evaluate it with {@link #evaluateWithdraw}.
     */
    public TxModel withdrawTx(java.util.List<org.julclang.state.api.Send> sends, byte[]... signers) {
        var tx = new TxModel(TxModel.Kind.ACTION);
        tx.inputs.add(new TxInInfo(new TxOutRef(new TxId(fill(32, 0x33)), BigInteger.ZERO),
                walletOut(signers.length == 0 ? fill(28, 0xFE) : signers[0], BigInteger.valueOf(50_000_000))));
        for (byte[] sgn : signers) tx.signers.add(sgn);
        if (!sends.isEmpty()) {
            tx.outputClaims = new ArrayList<>();
            for (var snd : sends) {
                tx.outputs.add(new TxOut(snd.to(), snd.value(), snd.datum(), Optional.empty()));
                tx.sendOutputs.add(tx.outputs.size() - 1);
                tx.outputClaims.add(new org.julclang.state.api.OutputClaim(BigInteger.valueOf(tx.outputs.size() - 1), rewardRef()));
            }
        }
        return tx;
    }

    public Verdict evaluateWithdraw(TxModel tx, Object rewards, BigInteger amount) {
        var results = new LinkedHashMap<String, EvalResult>();
        PlutusData rd = data(rewards);
        var b = ScriptContextTestBuilder.rewarding(ownCredential()).redeemer(rd);
        b.withdrawal(ownCredential(), amount);
        b.redeemerEntry(new org.julclang.ledger.ScriptPurpose.Rewarding(ownCredential()), rd);
        results.put("WITHDRAW", ValidatorTest.evaluate(program, fill(b, tx).buildPlutusData()));
        if (tx.outputClaims != null || tx.depositClaims != null) {
            var v = ScriptContextTestBuilder.rewarding(outputVerifierCredential()).redeemer(outputClaimsData(tx));
            v.withdrawal(ownCredential(), amount);
            results.put("OUTPUT VERIFIER", ValidatorTest.evaluate(outputVerifier, fill(v, tx).buildPlutusData()));
        }
        return new Verdict(results);
    }

    public Verdict evaluateCertify(TxModel tx, org.julclang.ledger.TxCert cert) {
        var b = ScriptContextTestBuilder.certifying(BigInteger.ZERO, cert).redeemer(PlutusData.integer(0));
        b.certificate(cert);
        var results = new LinkedHashMap<String, EvalResult>();
        results.put("CERTIFY", ValidatorTest.evaluate(program, fill(b, tx).buildPlutusData()));
        return new Verdict(results);
    }

    /** Evaluates the VOTE purpose for {@code voter} casting {@code votes} (action id -> vote) in the transaction. */
    public Verdict evaluateVote(TxModel tx, org.julclang.ledger.Voter voter,
                                java.util.Map<org.julclang.ledger.GovernanceActionId, org.julclang.ledger.Vote> votes) {
        var b = ScriptContextTestBuilder.voting(voter).redeemer(PlutusData.integer(0));
        PlutusData ctx = fill(b, tx).buildPlutusData();
        // The builder has no votes: put them in TxInfo field 12 (votes: Map Voter (Map GovernanceActionId Vote)).
        var inner = new ArrayList<PlutusData.Pair>();
        votes.forEach((id, v) -> inner.add(new PlutusData.Pair(data(id), data(v))));
        var votesMap = new PlutusData.MapData(List.of(new PlutusData.Pair(data(voter), new PlutusData.MapData(inner))));
        var sc = (PlutusData.ConstrData) ctx;
        var info = (PlutusData.ConstrData) sc.fields().get(0);
        var fields = new ArrayList<>(info.fields());
        fields.set(12, votesMap);
        var newInfo = new PlutusData.ConstrData(info.tag(), fields);
        var newCtx = new PlutusData.ConstrData(sc.tag(), List.of(newInfo, sc.fields().get(1), sc.fields().get(2)));
        var results = new LinkedHashMap<String, EvalResult>();
        results.put("VOTE", ValidatorTest.evaluate(program, newCtx));
        return new Verdict(results);
    }

    public Verdict evaluatePropose(TxModel tx, org.julclang.ledger.ProposalProcedure procedure) {
        var b = ScriptContextTestBuilder.proposing(BigInteger.ZERO, procedure).redeemer(PlutusData.integer(0));
        var results = new LinkedHashMap<String, EvalResult>();
        results.put("PROPOSE", ValidatorTest.evaluate(program, fill(b, tx).buildPlutusData()));
        return new Verdict(results);
    }

    /** A Ctx over the harness window {@code [T0, T0 + WINDOW_MS)}. */
    public Ctx ctx(byte[] caller, BigInteger received, BigInteger balance) {
        return new Ctx(caller, received, balance, T0, T0.add(WINDOW_MS));
    }

    public static Interval window(BigInteger from, BigInteger to) {
        return new Interval(new IntervalBound(new IntervalBoundType.Finite(from), true),
                new IntervalBound(new IntervalBoundType.Finite(to), false));
    }

    // ------------------------------------------------------------------ live instances

    /** An instance as it sits on chain: its UTxO reference, state and lovelace (deposit included). */
    public record Live<S>(TxOutRef ref, S state, BigInteger lovelace) {}

    /** An instance holding {@code funds} lovelace on top of the deposit. */
    public Live<S> live(S state, BigInteger funds) {
        return new Live<>(LIVE_REF, state, BigInteger.valueOf(spec.minAda()).add(funds));
    }

    // ------------------------------------------------------------------ transactions shaped like the runtime's

    /** A deploy: consumes the seed, mints the thread token, locks the initial state (runtime: StateContractHandle.deploy). */
    public TxModel create(Object createRedeemer, S initial, Ctx ctx) {
        var tx = new TxModel(TxModel.Kind.CREATE);
        byte[] caller = ctx == null ? fill(28, 0xCA) : ctx.caller();
        tx.inputs.add(new TxInInfo(seed, walletOut(caller, BigInteger.valueOf(50_000_000))));
        BigInteger received = ctx == null ? BigInteger.ZERO : ctx.received();
        tx.outputs.add(stateOut(initial, BigInteger.valueOf(spec.minAda()).add(received)));
        tx.stateOutput = 0;
        tx.mint = token(BigInteger.ONE);
        tx.mintRedeemer = data(createRedeemer);
        if (ctx != null) {
            tx.signers.add(caller);
            tx.range = window(ctx.validFrom(), ctx.validTo());
        }
        return tx;
    }

    /**
     * An action: spends the state, continues with {@code next}, pays {@code payouts} (runtime: StateInstance.call).
     *
     * @param signer the caller's key when the call needs a signature (Ctx or {@code @SignedBy}), else null
     * @param ctx    the Ctx if the method declares one, else null
     */
    public TxModel act(Live<S> live, Object spendRedeemer, S next, JulcList<Payout> payouts, byte[] signer, Ctx ctx) {
        var tx = spend(TxModel.Kind.ACTION, live, spendRedeemer, signer, ctx);
        BigInteger received = ctx == null ? BigInteger.ZERO : ctx.received();
        tx.outputs.add(stateOut(next, live.lovelace().add(received).subtract(total(payouts))));
        tx.stateOutput = tx.outputs.size() - 1;
        addPayouts(tx, live, payouts);
        return tx;
    }

    /** Same as {@link #act} without payouts. */
    public TxModel act(Live<S> live, Object spendRedeemer, S next, byte[] signer, Ctx ctx) {
        return act(live, spendRedeemer, next, JulcList.empty(), signer, ctx);
    }

    /** A destroy: spends the state, burns the token, pays out everything (runtime: StateInstance.call). */
    public TxModel destroy(Live<S> live, Object spendRedeemer, Object burnRedeemer, JulcList<Payout> payouts,
                           byte[] signer, Ctx ctx) {
        var tx = spend(TxModel.Kind.DESTROY, live, spendRedeemer, signer, ctx);
        tx.mint = token(BigInteger.ONE.negate());
        tx.mintRedeemer = data(burnRedeemer);
        addPayouts(tx, live, payouts);
        return tx;
    }

    private TxModel spend(TxModel.Kind kind, Live<S> live, Object spendRedeemer, byte[] signer, Ctx ctx) {
        var tx = new TxModel(kind);
        tx.inputs.add(new TxInInfo(live.ref(), stateOut(live.state(), live.lovelace())));
        tx.inputs.add(new TxInInfo(new TxOutRef(new TxId(fill(32, 0x33)), BigInteger.ZERO),
                walletOut(signer == null ? fill(28, 0xFE) : signer, BigInteger.valueOf(50_000_000))));
        tx.spendRedeemers.put(live.ref(), data(spendRedeemer));
        if (signer != null) tx.signers.add(signer);
        if (ctx != null) tx.range = window(ctx.validFrom(), ctx.validTo());
        return tx;
    }

    private void addPayouts(TxModel tx, Live<S> live, JulcList<Payout> payouts) {
        Map<String, BigInteger> owed = new LinkedHashMap<>();
        for (Payout p : payouts) owed.merge(HexFormat.of().formatHex(p.to()), p.lovelace(), BigInteger::add);
        PlutusData tag = data(live.ref());
        for (var e : owed.entrySet()) {
            tx.outputs.add(new TxOut(pkhAddress(HexFormat.of().parseHex(e.getKey())), Value.lovelace(e.getValue()),
                    new OutputDatum.OutputDatumInline(tag), Optional.empty()));
            tx.payoutOutputs.add(tx.outputs.size() - 1);
        }
    }

    // ------------------------------------------------------------------ evaluation

    /** Per-script results of one transaction. */
    public record Verdict(Map<String, EvalResult> results) {
        public boolean accepted() {
            return !results.isEmpty() && results.values().stream().allMatch(EvalResult::isSuccess);
        }

        @Override
        public String toString() {
            var sb = new StringBuilder();
            results.forEach((k, r) -> sb.append(k).append(": ").append(r.isSuccess() ? "ok" : "REJECTED")
                    .append(" cpu=").append(r.budgetConsumed().cpuSteps())
                    .append(" mem=").append(r.budgetConsumed().memoryUnits())
                    .append(r.isSuccess() ? "" : " traces=" + r.traces())
                    .append(r instanceof org.julclang.vm.EvalResult.Failure f ? " error=" + f.error() + (Boolean.getBoolean("joust.failedTerm") && f.failedTerm() != null ? " term=" + String.valueOf(f.failedTerm()).substring(0, Math.min(1500, String.valueOf(f.failedTerm()).length())) : "") : "").append('\n'));
            return sb.toString();
        }
    }

    public Verdict evaluate(TxModel tx) {
        var results = new LinkedHashMap<String, EvalResult>();
        for (var e : tx.spendRedeemers.entrySet()) {
            TxInInfo in = tx.inputs.stream().filter(i -> i.outRef().equals(e.getKey())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("spend redeemer for a missing input"));
            // Another contract's input in the same transaction (ADR-0011 composition) is that contract's harness's job.
            if (in.resolved().address().credential() instanceof Credential.ScriptCredential sc
                    && !java.util.Arrays.equals(sc.hash().hash(), policy)) continue;
            PlutusData datum = in.resolved().datum() instanceof OutputDatum.OutputDatumInline d ? d.datum() : null;
            var b = ScriptContextTestBuilder.spending(e.getKey(), datum).redeemer(e.getValue());
            results.put("SPEND " + e.getKey().index() + "@" + shortHex(e.getKey().txId().hash()),
                    ValidatorTest.evaluate(program, fill(b, tx).buildPlutusData()));
        }
        if (tx.mintRedeemer != null) {
            var b = ScriptContextTestBuilder.minting(new PolicyId(policy)).redeemer(tx.mintRedeemer);
            results.put("MINT", ValidatorTest.evaluate(program, fill(b, tx).buildPlutusData()));
        }
        if (tx.mapClaims != null) {
            var b = ScriptContextTestBuilder.rewarding(verifierCredential()).redeemer(claimsData(tx));
            results.put("MAP VERIFIER", ValidatorTest.evaluate(verifier, fill(b, tx).buildPlutusData()));
        }
        if (tx.outputClaims != null || tx.depositClaims != null) {
            var b = ScriptContextTestBuilder.rewarding(outputVerifierCredential()).redeemer(outputClaimsData(tx));
            results.put("OUTPUT VERIFIER", ValidatorTest.evaluate(outputVerifier, fill(b, tx).buildPlutusData()));
        }
        return new Verdict(results);
    }

    public Verdict assertAccepted(TxModel tx) {
        Verdict v = evaluate(tx);
        if (!v.accepted()) fail("expected the transaction to be accepted:\n" + v);
        return v;
    }

    public void assertRejected(TxModel tx, String what) {
        Verdict v = evaluate(tx);
        if (v.accepted()) fail("expected rejection (" + what + ") but every script accepted:\n" + v);
    }

    private Credential verifierCredential() {
        return new Credential.ScriptCredential(new org.julclang.ledger.ScriptHash(verifierHash));
    }

    private static PlutusData claimsData(TxModel tx) {
        org.julclang.core.types.JulcList<org.julclang.state.api.mpf.MapClaim> list =
                org.julclang.core.types.JulcList.empty();
        for (int i = tx.mapClaims.size() - 1; i >= 0; i--) list = list.prepend(tx.mapClaims.get(i));
        return data(new org.julclang.state.api.mpf.MapClaims(list));
    }

    private Credential outputVerifierCredential() {
        return new Credential.ScriptCredential(new org.julclang.ledger.ScriptHash(outputVerifierHash));
    }

    private static PlutusData outputClaimsData(TxModel tx) {
        JulcList<org.julclang.state.api.OutputClaim> list = JulcList.empty();
        if (tx.outputClaims != null) {
            for (int i = tx.outputClaims.size() - 1; i >= 0; i--) list = list.prepend(tx.outputClaims.get(i));
        }
        JulcList<org.julclang.state.api.DepositClaim> deps = JulcList.empty();
        if (tx.depositClaims != null) {
            for (int i = tx.depositClaims.size() - 1; i >= 0; i--) deps = deps.prepend(tx.depositClaims.get(i));
        }
        return data(new org.julclang.state.api.OutputClaims(list, deps));
    }

    private ScriptContextTestBuilder fill(ScriptContextTestBuilder b, TxModel tx) {
        tx.withdrawals.forEach((cred, rd) -> {
            b.withdrawal(cred, BigInteger.ZERO);
            if (rd != null) b.redeemerEntry(new org.julclang.ledger.ScriptPurpose.Rewarding(cred), rd);
        });
        if (tx.outputClaims != null || tx.depositClaims != null) {
            b.withdrawal(outputVerifierCredential(), BigInteger.ZERO);
            b.redeemerEntry(new org.julclang.ledger.ScriptPurpose.Rewarding(outputVerifierCredential()), outputClaimsData(tx));
        }
        if (tx.mapClaims != null) {
            b.withdrawal(verifierCredential(), BigInteger.ZERO);
            b.redeemerEntry(new org.julclang.ledger.ScriptPurpose.Rewarding(verifierCredential()), claimsData(tx));
        }
        // The redeemer map, as the ledger builds it: scripts can look up each other's redeemers (ADR-0004 InBatch).
        tx.spendRedeemers.forEach((ref, r) -> b.redeemerEntry(new org.julclang.ledger.ScriptPurpose.Spending(ref), r));
        if (tx.mintRedeemer != null) {
            b.redeemerEntry(new org.julclang.ledger.ScriptPurpose.Minting(new PolicyId(policy)), tx.mintRedeemer);
        }
        tx.inputs.forEach(b::input);
        tx.referenceInputs.forEach(b::referenceInput);
        tx.outputs.forEach(b::output);
        tx.signers.forEach(s -> b.signer(new PubKeyHash(s)));
        b.mint(tx.mint);
        b.validRange(tx.range);
        return b;
    }

    // ------------------------------------------------------------------ building blocks

    public TxOut stateOut(Object state, BigInteger lovelace) {
        return new TxOut(stateAddress(), Value.lovelace(lovelace).merge(token(BigInteger.ONE)),
                new OutputDatum.OutputDatumInline(data(state)), Optional.empty());
    }

    /** A state output of another instance of this contract, {@code id} (ADR-0012 moves between instances). */
    public TxOut instanceOut(byte[] id, Object state, BigInteger lovelace) {
        return new TxOut(stateAddress(), Value.lovelace(lovelace).merge(Value.singleton(new PolicyId(policy), new TokenName(id),
                BigInteger.ONE)), new OutputDatum.OutputDatumInline(data(state)), Optional.empty());
    }

    /**
     * Adds another instance {@code id} of this contract to {@code tx}: spent at {@code ref} with {@code redeemer}, holding
     * {@code state}, continuing with {@code next} (same lovelace). How a move's destination admits the object (ADR-0012 D3).
     */
    public TxModel withInstance(TxModel tx, byte[] id, TxOutRef ref, Object state, Object redeemer, Object next,
                                BigInteger lovelace) {
        var c = tx.copy();
        c.inputs.add(new TxInInfo(ref, instanceOut(id, state, lovelace)));
        c.spendRedeemers.put(ref, data(redeemer));
        c.outputs.add(instanceOut(id, next, lovelace));
        return c;
    }

    public Value token(BigInteger qty) {
        return Value.singleton(new PolicyId(policy), new TokenName(instanceId()), qty);
    }

    /** {@code qty} of the token {@code tx} works on (its instance's, or its object's). */
    public Value token(TxModel tx, BigInteger qty) {
        byte[] name = tx.token != null ? tx.token : instanceId();
        return Value.singleton(new PolicyId(policy), new TokenName(name), qty);
    }

    // ------------------------------------------------------------------ owned objects (ADR-0003)

    /** The object id the harness seed produces: 0x4F ++ blake2b_224(seed ref). */
    public byte[] objectId() {
        return HexFormat.of().parseHex(org.julclang.state.runtime.OwnedObject.objectIdOf(
                HexFormat.of().formatHex(seed.txId().hash()), seed.index().intValueExact()));
    }

    /** Where {@link #liveObject} objects sit. */
    public static final TxOutRef OBJECT_REF = new TxOutRef(new TxId(fill(32, 0x0B)), BigInteger.TWO);

    /** An object at {@link #OBJECT_REF} holding {@code holding} (the generated Holding) and {@code funds} on top of the deposit. */
    public record LiveObject(TxOutRef ref, Object holding, BigInteger lovelace) {}

    public LiveObject liveObject(Object holding, BigInteger funds) {
        return new LiveObject(OBJECT_REF, holding, BigInteger.valueOf(spec.minAda()).add(funds));
    }

    /** The instance UTxO with {@code shared} as a reference input (how objects read @Shared state). */
    public TxInInfo instanceReference(Object shared) {
        return new TxInInfo(LIVE_REF, stateOut(shared, BigInteger.valueOf(spec.minAda())));
    }

    private TxOut objectOut(Object holding, BigInteger lovelace) {
        return new TxOut(stateAddress(), Value.lovelace(lovelace).merge(Value.singleton(new PolicyId(policy),
                new TokenName(objectId()), BigInteger.ONE)), new OutputDatum.OutputDatumInline(data(holding)), Optional.empty());
    }

    /** Creating an object: seed, the instance as a reference input, the object token minted, the object output. */
    public TxModel createObject(Object redeemer, Object holding, Object shared, Ctx ctx) {
        var tx = new TxModel(TxModel.Kind.CREATE);
        tx.token = objectId();
        byte[] caller = ctx == null ? fill(28, 0xCA) : ctx.caller();
        tx.inputs.add(new TxInInfo(seed, walletOut(caller, BigInteger.valueOf(50_000_000))));
        tx.referenceInputs.add(instanceReference(shared));
        BigInteger received = ctx == null ? BigInteger.ZERO : ctx.received();
        tx.outputs.add(objectOut(holding, BigInteger.valueOf(spec.minAda()).add(received)));
        tx.stateOutput = 0;
        tx.mint = Value.singleton(new PolicyId(policy), new TokenName(objectId()), BigInteger.ONE);
        tx.mintRedeemer = data(redeemer);
        if (ctx != null) {
            tx.signers.add(caller);
            tx.range = window(ctx.validFrom(), ctx.validTo());
        }
        return tx;
    }

    /** An owned action: spends the object only (plus the instance by reference when {@code shared} is given). */
    public TxModel actObject(LiveObject obj, Object redeemer, Object nextHolding, JulcList<Payout> payouts, byte[] signer,
                             Ctx ctx, Object shared) {
        var tx = spendObject(TxModel.Kind.ACTION, obj, redeemer, signer, ctx, shared);
        BigInteger received = ctx == null ? BigInteger.ZERO : ctx.received();
        tx.outputs.add(objectOut(nextHolding, obj.lovelace().add(received).subtract(total(payouts))));
        tx.stateOutput = tx.outputs.size() - 1;
        addPayoutsFor(tx, obj.ref(), payouts);
        return tx;
    }

    /** An owned destroy: spends the object, burns its token, pays out everything. */
    public TxModel destroyObject(LiveObject obj, Object redeemer, Object burnRedeemer, JulcList<Payout> payouts,
                                 byte[] signer, Ctx ctx) {
        var tx = spendObject(TxModel.Kind.DESTROY, obj, redeemer, signer, ctx, null);
        tx.mint = Value.singleton(new PolicyId(policy), new TokenName(objectId()), BigInteger.ONE.negate());
        tx.mintRedeemer = data(burnRedeemer);
        addPayoutsFor(tx, obj.ref(), payouts);
        return tx;
    }

    private TxModel spendObject(TxModel.Kind kind, LiveObject obj, Object redeemer, byte[] signer, Ctx ctx, Object shared) {
        var tx = new TxModel(kind);
        tx.token = objectId();
        tx.inputs.add(new TxInInfo(obj.ref(), objectOut(obj.holding(), obj.lovelace())));
        tx.inputs.add(new TxInInfo(new TxOutRef(new TxId(fill(32, 0x33)), BigInteger.ZERO),
                walletOut(signer == null ? fill(28, 0xFE) : signer, BigInteger.valueOf(50_000_000))));
        if (shared != null) tx.referenceInputs.add(instanceReference(shared));
        tx.spendRedeemers.put(obj.ref(), data(redeemer));
        if (signer != null) tx.signers.add(signer);
        if (ctx != null) tx.range = window(ctx.validFrom(), ctx.validTo());
        return tx;
    }

    private void addPayoutsFor(TxModel tx, TxOutRef ref, JulcList<Payout> payouts) {
        Map<String, BigInteger> owed = new LinkedHashMap<>();
        for (Payout p : payouts) owed.merge(HexFormat.of().formatHex(p.to()), p.lovelace(), BigInteger::add);
        PlutusData tag = data(ref);
        for (var e : owed.entrySet()) {
            tx.outputs.add(new TxOut(pkhAddress(HexFormat.of().parseHex(e.getKey())), Value.lovelace(e.getValue()),
                    new OutputDatum.OutputDatumInline(tag), Optional.empty()));
            tx.payoutOutputs.add(tx.outputs.size() - 1);
        }
    }

    // ------------------------------------------------------------------ native assets (ADR-0007)

    /**
     * A UTxO of an asset-aware contract as it sits on chain: its reference, its datum (the state, or the Holding of an
     * object), its full value (lovelace, its own token, native assets) and its token name.
     */
    public record Held(TxOutRef ref, Object datum, Value value, byte[] token) {
        public BigInteger lovelace() {
            return org.julclang.state.api.Assets.lovelaceOf(value);
        }
    }

    /** An instance at {@link #LIVE_REF} holding {@code funds} lovelace on top of the deposit and {@code assets}. */
    public Held held(S state, BigInteger funds, Value assets) {
        return new Held(LIVE_REF, state, ownedValue(instanceId(), funds, assets), instanceId());
    }

    /** An object at {@link #OBJECT_REF} with datum {@code holding}, {@code funds} lovelace and {@code assets}. */
    public Held heldObject(Object holding, BigInteger funds, Value assets) {
        return new Held(OBJECT_REF, holding, ownedValue(objectId(), funds, assets), objectId());
    }

    private Value ownedValue(byte[] token, BigInteger funds, Value assets) {
        return org.julclang.state.api.Assets.add(org.julclang.state.api.Assets.add(
                org.julclang.state.api.Assets.lovelace(BigInteger.valueOf(spec.minAda()).add(funds)),
                org.julclang.state.api.Assets.of(policy, token, BigInteger.ONE)), assets);
    }

    /** The Funds a method sees for {@code h}, with {@code received} offered by the caller. */
    public org.julclang.state.api.Funds funds(Held h, Value received) {
        Value v = org.julclang.state.api.Assets.withoutLovelace(h.value());
        Value held = org.julclang.state.api.Assets.subtract(v, org.julclang.state.api.Assets.of(policy, h.token(), BigInteger.ONE));
        return new org.julclang.state.api.Funds(policy, h.token(), received, held, JulcList.empty());
    }

    /** The Funds of a {@code @Receives} call: as {@link #funds}, plus the deposits claimed on its output (ADR-0011 D3). */
    public org.julclang.state.api.Funds funds(Held h, Value received, JulcList<org.julclang.state.api.DepositClaim> deposits) {
        var f = funds(h, received);
        return new org.julclang.state.api.Funds(f.policy(), f.self(), f.received(), f.held(), deposits);
    }

    /** The Funds of a creation (instance or object): nothing held yet. */
    public org.julclang.state.api.Funds createFunds(boolean object, Value received) {
        return new org.julclang.state.api.Funds(policy, object ? objectId() : instanceId(), received,
                org.julclang.state.api.Assets.none(), JulcList.empty());
    }

    /**
     * A creation in an asset-aware contract (runtime: StateContractHandle.deploy / OwnedObject.create): the output holds
     * the deposit, {@code ctx.received()}, the token and {@code accepted}.
     *
     * @param object create an owned object (the instance {@code shared} is then a reference input)
     */
    public TxModel createWith(Object createRedeemer, Object datum, Ctx ctx, Value accepted, boolean object, Object shared) {
        var tx = new TxModel(TxModel.Kind.CREATE);
        byte[] token = object ? objectId() : instanceId();
        tx.token = token;
        byte[] caller = ctx == null ? fill(28, 0xCA) : ctx.caller();
        tx.inputs.add(new TxInInfo(seed, walletOut(caller, BigInteger.valueOf(50_000_000))));
        if (object) tx.referenceInputs.add(instanceReference(shared));
        BigInteger received = ctx == null ? BigInteger.ZERO : ctx.received();
        tx.outputs.add(new TxOut(stateAddress(), ownedValue(token, received, accepted),
                new OutputDatum.OutputDatumInline(data(datum)), Optional.empty()));
        tx.stateOutput = 0;
        tx.mint = org.julclang.state.api.Assets.of(policy, token, BigInteger.ONE);
        tx.mintRedeemer = data(createRedeemer);
        if (ctx != null) {
            tx.signers.add(caller);
            tx.range = window(ctx.validFrom(), ctx.validTo());
        }
        return tx;
    }

    /**
     * A call in an asset-aware contract (runtime: StateInstance / OwnedObject asset paths): spends {@code h}, continues
     * with {@code nextDatum} (null for a destroy) holding {@code in + received + accepted + mint - payouts - sends}, pays
     * the payouts (tagged), adds each send as an output claimed for {@code h} in the output registry, and mints
     * {@code t.mint()} (plus the burn of the token for a destroy) under {@code mintRedeemer}.
     */
    public TxModel stepWith(Held h, Object spendRedeemer, Object mintRedeemer, org.julclang.state.runtime.Transition<?> t,
                            Object nextDatum, byte[] signer, Ctx ctx, Object shared) {
        boolean destroy = nextDatum == null;
        var tx = new TxModel(destroy ? TxModel.Kind.DESTROY : TxModel.Kind.ACTION);
        tx.token = h.token();
        tx.inputs.add(new TxInInfo(h.ref(), new TxOut(stateAddress(), h.value(),
                new OutputDatum.OutputDatumInline(data(h.datum())), Optional.empty())));
        tx.inputs.add(new TxInInfo(new TxOutRef(new TxId(fill(32, 0x33)), BigInteger.ZERO),
                walletOut(signer == null ? fill(28, 0xFE) : signer, BigInteger.valueOf(50_000_000))));
        if (shared != null) tx.referenceInputs.add(instanceReference(shared));
        tx.spendRedeemers.put(h.ref(), data(spendRedeemer));
        if (signer != null) tx.signers.add(signer);
        if (ctx != null) tx.range = window(ctx.validFrom(), ctx.validTo());

        BigInteger received = ctx == null ? BigInteger.ZERO : ctx.received();
        Value sent = org.julclang.state.api.Assets.none();
        for (var snd : t.sends()) sent = org.julclang.state.api.Assets.add(sent, snd.value());
        for (var dep : t.deposits()) sent = org.julclang.state.api.Assets.add(sent, dep.value());   // ADR-0011 D3
        Value next = org.julclang.state.api.Assets.add(org.julclang.state.api.Assets.add(h.value(),
                org.julclang.state.api.Assets.lovelace(received.subtract(total(t.payouts())))),
                org.julclang.state.api.Assets.add(t.accepted(), t.mint()));
        next = org.julclang.state.api.Assets.subtract(next, sent);
        if (!destroy) {
            tx.outputs.add(new TxOut(stateAddress(), next, new OutputDatum.OutputDatumInline(data(nextDatum)), Optional.empty()));
            tx.stateOutput = tx.outputs.size() - 1;
        }
        addPayoutsFor(tx, h.ref(), t.payouts());
        if (!t.sends().isEmpty()) {
            tx.outputClaims = new ArrayList<>();
            for (var snd : t.sends()) {
                tx.outputs.add(new TxOut(snd.to(), snd.value(), snd.datum(), Optional.empty()));
                tx.sendOutputs.add(tx.outputs.size() - 1);
                tx.outputClaims.add(new org.julclang.state.api.OutputClaim(BigInteger.valueOf(tx.outputs.size() - 1), h.ref()));
            }
        }
        Value mint = t.mint();
        if (destroy) mint = org.julclang.state.api.Assets.add(mint, org.julclang.state.api.Assets.of(policy, h.token(),
                BigInteger.ONE.negate()));
        if (!org.julclang.state.api.Assets.isEmpty(mint)) {
            tx.mint = mint;
            tx.mintRedeemer = data(mintRedeemer);
        }
        return tx;
    }

    // ------------------------------------------------------------------ intents and batches (ADR-0004)

    /** An intent as it sits on chain: its UTxO reference, generated Intent record, lovelace and token name. */
    public record LiveIntent(TxOutRef ref, Object intent, BigInteger lovelace, byte[] token) {}

    /** The intent id a seed produces: 0x49 ++ blake2b_224(seed ref). */
    public static byte[] intentId(TxOutRef seed) {
        return HexFormat.of().parseHex(org.julclang.state.runtime.IntentHandle.intentIdOf(
                HexFormat.of().formatHex(seed.txId().hash()), seed.index().intValueExact()));
    }

    /** The {@code n}-th test intent's UTxO reference; their transaction ids sort by {@code n} (ledger order). */
    public static TxOutRef intentRef(int n) {
        return new TxOutRef(new TxId(fill(32, 0x40 + n)), BigInteger.valueOf(n));
    }

    /** An intent at {@link #intentRef(int) intentRef(n)}, minted from seed {@code fill(32, 0x20 + n)#0}. */
    public LiveIntent liveIntent(int n, Object intent, BigInteger lovelace) {
        byte[] token = intentId(new TxOutRef(new TxId(fill(32, 0x20 + n)), BigInteger.ZERO));
        return new LiveIntent(intentRef(n), intent, lovelace, token);
    }

    public TxOut intentOut(Object intent, BigInteger lovelace, byte[] token) {
        return new TxOut(stateAddress(), Value.lovelace(lovelace).merge(Value.singleton(new PolicyId(policy),
                new TokenName(token), BigInteger.ONE)), new OutputDatum.OutputDatumInline(data(intent)), Optional.empty());
    }

    /** Creating an intent (runtime: IntentHandle.create): seed, token minted, intent output, owner signs. */
    public TxModel createIntent(org.julclang.state.runtime.IntentCodec<S> codec, Object intent, BigInteger lovelace) {
        var tx = new TxModel(TxModel.Kind.CREATE);
        tx.token = intentId(seed);
        byte[] owner = codec.read(intent).owner();
        tx.inputs.add(new TxInInfo(seed, walletOut(owner, BigInteger.valueOf(50_000_000))));
        tx.outputs.add(intentOut(intent, lovelace, tx.token));
        tx.stateOutput = 0;
        tx.mint = Value.singleton(new PolicyId(policy), new TokenName(tx.token), BigInteger.ONE);
        tx.mintRedeemer = data(codec.create(seed, intent));
        tx.signers.add(owner);
        return tx;
    }

    /** Cancelling an intent (runtime: IntentHandle.cancel): owner signs, token burned, funds back to the owner. */
    public TxModel cancelIntent(org.julclang.state.runtime.IntentCodec<S> codec, LiveIntent it, byte[] signer) {
        var tx = new TxModel(TxModel.Kind.DESTROY);
        tx.token = it.token();
        tx.inputs.add(new TxInInfo(it.ref(), intentOut(it.intent(), it.lovelace(), it.token())));
        tx.spendRedeemers.put(it.ref(), data(codec.cancel()));
        tx.mint = Value.singleton(new PolicyId(policy), new TokenName(it.token()), BigInteger.ONE.negate());
        tx.mintRedeemer = data(codec.burn());
        tx.outputs.add(walletOut(signer, it.lovelace()));
        tx.signers.add(signer);
        return tx;
    }

    /** A batch and the JVM fold that produced it. */
    public record Batch<S>(TxModel tx, org.julclang.state.runtime.Batcher.Plan<S> plan) {}

    /**
     * A batch (runtime: Batcher.run) over the harness window: folds {@code intents} on the JVM in ledger order, then
     * spends the instance with Batch and each applied intent with InBatch, burns their tokens, pays refunds
     * (tagged IntentRefund(intentRef)) and payouts (tagged with the intent's input), and continues the state.
     */
    public Batch<S> batch(Live<S> live, org.julclang.state.runtime.IntentCodec<S> codec, List<LiveIntent> intents) {
        var pending = new ArrayList<org.julclang.state.runtime.Batcher.Pending>();
        for (LiveIntent it : intents) {
            pending.add(new org.julclang.state.runtime.Batcher.Pending(it.ref(), codec.read(it.intent()), it.lovelace(),
                    HexFormat.of().formatHex(it.token()), null));
        }
        BigInteger to = T0.add(WINDOW_MS);
        var plan = org.julclang.state.runtime.Batcher.fold(codec, live.state(),
                live.lovelace().subtract(BigInteger.valueOf(spec.minAda())), pending, T0, to, Integer.MAX_VALUE);
        var tx = new TxModel(TxModel.Kind.ACTION);
        var inputs = new ArrayList<TxInInfo>();
        inputs.add(new TxInInfo(live.ref(), stateOut(live.state(), live.lovelace())));
        inputs.add(new TxInInfo(new TxOutRef(new TxId(fill(32, 0x33)), BigInteger.ZERO),
                walletOut(fill(28, 0xBA), BigInteger.valueOf(50_000_000))));
        tx.spendRedeemers.put(live.ref(), data(codec.batch(T0, to)));
        Value burns = Value.zero();
        for (int i = 0; i < plan.applied().size(); i++) {
            var p = plan.applied().get(i);
            LiveIntent it = intents.stream().filter(x -> x.ref().equals(p.ref())).findFirst().orElseThrow();
            inputs.add(new TxInInfo(it.ref(), intentOut(it.intent(), it.lovelace(), it.token())));
            tx.spendRedeemers.put(it.ref(), data(codec.inBatch()));
            burns = burns.merge(Value.singleton(new PolicyId(policy), new TokenName(it.token()), BigInteger.ONE.negate()));
            tx.outputs.add(new TxOut(p.data().refund(), Value.lovelace(it.lovelace().subtract(p.data().received())),
                    new OutputDatum.OutputDatumInline(data(codec.refundTag(it.ref()))), Optional.empty()));
            tx.payoutOutputs.add(tx.outputs.size() - 1);
            JulcList<Payout> owed = JulcList.empty();
            for (Payout po : plan.payouts().get(i)) owed = owed.prepend(po);
            addPayoutsFor(tx, it.ref(), owed);
        }
        inputs.sort(java.util.Comparator.comparing(TxInInfo::outRef, org.julclang.state.runtime.Batcher.LEDGER_ORDER));
        tx.inputs.addAll(inputs);
        tx.mint = burns;
        tx.mintRedeemer = data(codec.burn());
        tx.outputs.add(stateOut(plan.state(), plan.balance().add(BigInteger.valueOf(spec.minAda()))));
        tx.stateOutput = tx.outputs.size() - 1;
        tx.range = window(T0, to);
        return new Batch<>(tx, plan);
    }

    public static TxOut walletOut(byte[] pkh, BigInteger lovelace) {
        return new TxOut(pkhAddress(pkh), Value.lovelace(lovelace), new OutputDatum.NoOutputDatum(), Optional.empty());
    }

    public static Address pkhAddress(byte[] pkh) {
        return new Address(new Credential.PubKeyCredential(new PubKeyHash(pkh)), Optional.empty());
    }

    /**
     * Record (or ledger value) to on-chain data, the same path the runtime uses: PlutusDataAdapter, then the canonical
     * CBOR that cardano-client-lib writes (which sorts map keys), then back. So a state whose maps are not in
     * canonical order is seen here exactly as it would land on chain.
     */
    public static PlutusData data(Object o) {
        if (o instanceof PlutusData d) return d;
        if (o instanceof org.julclang.ledger.TxOutRef r) return r.toPlutusData();
        try {
            var ccl = PlutusDataAdapter.convert(o);
            byte[] onChain = com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(ccl.serialize(), true);
            return PlutusDataAdapter.fromClientLib(com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(onChain));
        } catch (Exception e) {
            throw new IllegalStateException("cannot encode " + o, e);
        }
    }

    public static BigInteger total(JulcList<Payout> payouts) {
        BigInteger t = BigInteger.ZERO;
        for (Payout p : payouts) t = t.add(p.lovelace());
        return t;
    }

    public static byte[] fill(int len, int b) {
        var a = new byte[len];
        Arrays.fill(a, (byte) b);
        return a;
    }

    private static String shortHex(byte[] b) {
        return HexFormat.of().formatHex(b).substring(0, 8);
    }

    /** Strips CBOR byte-string headers (scripts are CBOR-wrapped flat, sometimes twice). */
    static byte[] unwrapCbor(byte[] b) {
        byte[] cur = b;
        for (int i = 0; i < 3; i++) {
            int major = (cur[0] & 0xFF) >> 5;
            if (major != 2) return cur;
            int info = cur[0] & 0x1F;
            int header;
            long len;
            if (info < 24) { header = 1; len = info; }
            else if (info == 24) { header = 2; len = cur[1] & 0xFF; }
            else if (info == 25) { header = 3; len = ((cur[1] & 0xFF) << 8) | (cur[2] & 0xFF); }
            else if (info == 26) { header = 5; len = ((long) (cur[1] & 0xFF) << 24) | ((cur[2] & 0xFF) << 16) | ((cur[3] & 0xFF) << 8) | (cur[4] & 0xFF); }
            else return cur;
            if (header + len != cur.length) return cur;
            cur = Arrays.copyOfRange(cur, header, cur.length);
        }
        return cur;
    }

    @SuppressWarnings("unused")
    private static List<String> list(String... s) {
        return new ArrayList<>(List.of(s));
    }
}
