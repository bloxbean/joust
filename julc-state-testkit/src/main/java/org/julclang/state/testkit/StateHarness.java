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
 * TxModel create = h.create(new CounterValidator.Create(h.seed(), ctx), Counter.init(ctx), ctx);
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

    private StateHarness(ContractSpec<S> spec) {
        this.spec = spec;
        var script = JulcScriptLoader.load(spec.validatorClass());
        this.program = UplcFlatDecoder.decodeProgram(unwrapCbor(HexFormat.of().parseHex(script.getCborHex())));
        try {
            this.policy = script.getScriptHash();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        this.seed = new TxOutRef(new TxId(fill(32, 0x5E)), BigInteger.ZERO);
    }

    public static <S> StateHarness<S> of(ContractSpec<S> spec) {
        return new StateHarness<>(spec);
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
        return new Address(new Credential.ScriptCredential(new ScriptHash(policy)), Optional.empty());
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
        return new Live<>(new TxOutRef(new TxId(fill(32, 0x7A)), BigInteger.ONE), state,
                BigInteger.valueOf(spec.minAda()).add(funds));
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
                    .append(r.isSuccess() ? "" : " traces=" + r.traces()).append('\n'));
            return sb.toString();
        }
    }

    public Verdict evaluate(TxModel tx) {
        var results = new LinkedHashMap<String, EvalResult>();
        for (var e : tx.spendRedeemers.entrySet()) {
            TxInInfo in = tx.inputs.stream().filter(i -> i.outRef().equals(e.getKey())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("spend redeemer for a missing input"));
            PlutusData datum = in.resolved().datum() instanceof OutputDatum.OutputDatumInline d ? d.datum() : null;
            var b = ScriptContextTestBuilder.spending(e.getKey(), datum).redeemer(e.getValue());
            results.put("SPEND " + e.getKey().index() + "@" + shortHex(e.getKey().txId().hash()),
                    ValidatorTest.evaluate(program, fill(b, tx).buildPlutusData()));
        }
        if (tx.mintRedeemer != null) {
            var b = ScriptContextTestBuilder.minting(new PolicyId(policy)).redeemer(tx.mintRedeemer);
            results.put("MINT", ValidatorTest.evaluate(program, fill(b, tx).buildPlutusData()));
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

    private static ScriptContextTestBuilder fill(ScriptContextTestBuilder b, TxModel tx) {
        tx.inputs.forEach(b::input);
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

    public Value token(BigInteger qty) {
        return Value.singleton(new PolicyId(policy), new TokenName(instanceId()), qty);
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
