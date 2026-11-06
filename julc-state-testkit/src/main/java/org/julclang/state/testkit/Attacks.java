package org.julclang.state.testkit;

import org.julclang.core.PlutusData;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.DatumHash;
import org.julclang.ledger.Interval;
import org.julclang.ledger.IntervalBound;
import org.julclang.ledger.IntervalBoundType;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.StakingCredential;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The attack suite (ADR-0001 D16). Given a transaction the harness accepts, {@link #applicable} lists every mutation
 * the invariants forbid that applies to it, and {@link #assertAllRejected} checks each is rejected.
 * <p>
 * Applicability: signer removal only if the transaction has signers (the call needed a signature); range attacks only
 * if it has a validity range (the call read Ctx or a time annotation); payout attacks only if it pays out.
 */
public final class Attacks {

    /** A named mutation of an accepted transaction. */
    public record Attack(String name, String invariant, UnaryOperator<TxModel> mutate) {}

    private Attacks() {}

    public static List<Attack> applicable(StateHarness<?> h, TxModel tx) {
        var out = new ArrayList<Attack>();
        boolean create = tx.kind == TxModel.Kind.CREATE;
        boolean destroy = tx.kind == TxModel.Kind.DESTROY;

        if (tx.stateOutput >= 0) {
            out.add(new Attack("datum replaced", "I2/I4", t -> mapState(t, o ->
                    new TxOut(o.address(), o.value(), new OutputDatum.OutputDatumInline(PlutusData.integer(42)),
                            o.referenceScript()))));
            out.add(new Attack("token dust added to the state output", "I3", t -> mapState(t, o ->
                    new TxOut(o.address(), o.value().merge(Value.singleton(new PolicyId(StateHarness.fill(28, 0xD0)),
                            new TokenName(new byte[]{1}), BigInteger.ONE)), o.datum(), o.referenceScript()))));
            out.add(new Attack("one lovelace skimmed from the state output", "I3", t -> mapState(t, o ->
                    new TxOut(o.address(), o.value().merge(Value.lovelace(BigInteger.ONE.negate())), o.datum(),
                            o.referenceScript()))));
            out.add(new Attack("one lovelace added to the state output", "I3", t -> mapState(t, o ->
                    new TxOut(o.address(), o.value().merge(Value.lovelace(BigInteger.ONE)), o.datum(),
                            o.referenceScript()))));
            out.add(new Attack("thread token sent to a key address", "I4/I12", t -> mapState(t, o ->
                    new TxOut(StateHarness.pkhAddress(StateHarness.fill(28, 0xBA)), o.value(), o.datum(),
                            o.referenceScript()))));
            out.add(new Attack("stake credential on the state address", "I12", t -> mapState(t, o ->
                    new TxOut(new Address(o.address().credential(), Optional.of(new StakingCredential.StakingHash(
                            new Credential.PubKeyCredential(new PubKeyHash(StateHarness.fill(28, 0x5A)))))),
                            o.value(), o.datum(), o.referenceScript()))));
            out.add(new Attack("reference script on the state output", "I12", t -> mapState(t, o ->
                    new TxOut(o.address(), o.value(), o.datum(), Optional.of(new ScriptHash(StateHarness.fill(28, 0x5C)))))));
            out.add(new Attack("datum hash instead of inline datum", "I4", t -> mapState(t, o ->
                    new TxOut(o.address(), o.value(), new OutputDatum.OutputDatumHash(new DatumHash(StateHarness.fill(32, 0xDA))),
                            o.referenceScript()))));
            out.add(new Attack("state output dropped", "I4", t -> {
                var c = t.copy();
                c.outputs.remove(c.stateOutput);
                c.payoutOutputs.replaceAll(i -> i > c.stateOutput ? i - 1 : i);
                c.stateOutput = -1;
                return c;
            }));
        }

        if (!tx.signers.isEmpty()) {
            out.add(new Attack("required signer missing", "I5/I11", t -> {
                var c = t.copy();
                c.signers.clear();
                c.signers.add(StateHarness.fill(28, 0xEE));
                return c;
            }));
        }

        if (tx.hasRange()) {
            out.add(new Attack("upper bound unbounded", "I6/I11", t -> withRange(t, t.range.from(),
                    new IntervalBound(new IntervalBoundType.PosInf(), true))));
            out.add(new Attack("lower bound unbounded", "I6/I11", t -> withRange(t,
                    new IntervalBound(new IntervalBoundType.NegInf(), true), t.range.to())));
            out.add(new Attack("validity range shifted by 1 ms (Ctx no longer equal)", "I6", t -> withRange(t,
                    shift(t.range.from(), 1), shift(t.range.to(), 1))));
            out.add(new Attack("validity range widened past maxValidityMs", "I6", t -> withRange(t,
                    shift(t.range.from(), -h.spec().maxValidityMs()), t.range.to())));
        }

        for (int k = 0; k < tx.payoutOutputs.size(); k++) {
            final int idx = tx.payoutOutputs.get(k);
            out.add(new Attack("payout #" + k + " untagged", "I7", t -> mapOutput(t, idx, o ->
                    new TxOut(o.address(), o.value(), new OutputDatum.NoOutputDatum(), o.referenceScript()))));
            out.add(new Attack("payout #" + k + " tagged with another input", "I7", t -> mapOutput(t, idx, o ->
                    new TxOut(o.address(), o.value(), new OutputDatum.OutputDatumInline(
                            new TxOutRef(new TxId(StateHarness.fill(32, 0x99)), BigInteger.ZERO).toPlutusData()),
                            o.referenceScript()))));
            out.add(new Attack("payout #" + k + " under-paid by 1 lovelace", "I7", t -> mapOutput(t, idx, o ->
                    new TxOut(o.address(), o.value().merge(Value.lovelace(BigInteger.ONE.negate())), o.datum(),
                            o.referenceScript()))));
            out.add(new Attack("payout #" + k + " paid to someone else", "I7", t -> mapOutput(t, idx, o ->
                    new TxOut(StateHarness.pkhAddress(StateHarness.fill(28, 0xBB)), o.value(), o.datum(),
                            o.referenceScript()))));
            out.add(new Attack("payout #" + k + " paid to a script with the same hash", "I7", t -> mapOutput(t, idx, o ->
                    new TxOut(new Address(new Credential.ScriptCredential(new ScriptHash(
                            ((Credential.PubKeyCredential) o.address().credential()).hash().hash())), Optional.empty()),
                            o.value(), o.datum(), o.referenceScript()))));
        }

        if (destroy) {
            out.add(new Attack("destroy without burning the token", "I8", t -> {
                var c = t.copy();
                c.mint = Value.zero();
                c.mintRedeemer = null;
                c.outputs.add(new TxOut(StateHarness.pkhAddress(StateHarness.fill(28, 0xBA)), h.token(BigInteger.ONE),
                        new OutputDatum.NoOutputDatum(), Optional.empty()));
                return c;
            }));
        }

        if (create) {
            out.add(new Attack("seed not consumed", "I1", t -> {
                var c = t.copy();
                c.inputs.replaceAll(i -> i.outRef().equals(h.seed())
                        ? new TxInInfo(new TxOutRef(new TxId(StateHarness.fill(32, 0x11)), BigInteger.ZERO), i.resolved()) : i);
                return c;
            }));
            out.add(new Attack("two thread tokens minted", "I1", t -> {
                var c = t.copy();
                c.mint = h.token(BigInteger.TWO);
                c.outputs.set(c.stateOutput, new TxOut(c.outputs.get(c.stateOutput).address(),
                        c.outputs.get(c.stateOutput).value().merge(h.token(BigInteger.ONE)),
                        c.outputs.get(c.stateOutput).datum(), Optional.empty()));
                return c;
            }));
            out.add(new Attack("an extra token minted under the policy", "I1", t -> {
                var c = t.copy();
                c.mint = c.mint.merge(Value.singleton(new PolicyId(h.policy()), new TokenName(new byte[]{9}), BigInteger.ONE));
                return c;
            }));
        } else {
            out.add(new Attack("junk UTxO (no thread token) spent as the state", "I10", t -> {
                var c = t.copy();
                c.inputs.replaceAll(i -> c.spendRedeemers.containsKey(i.outRef())
                        ? new TxInInfo(i.outRef(), new TxOut(i.resolved().address(),
                        i.resolved().value().merge(h.token(BigInteger.ONE.negate())), i.resolved().datum(), Optional.empty()))
                        : i);
                return c;
            }));
        }
        return out;
    }

    /** Evaluates every applicable attack; fails listing the ones that were accepted. */
    public static int assertAllRejected(StateHarness<?> h, TxModel accepted) {
        h.assertAccepted(accepted);
        var attacks = applicable(h, accepted);
        var accepted2 = new ArrayList<String>();
        for (Attack a : attacks) {
            if (h.evaluate(a.mutate().apply(accepted)).accepted()) accepted2.add(a.name() + " (" + a.invariant() + ")");
        }
        if (!accepted2.isEmpty()) fail("attacks NOT rejected:\n  " + String.join("\n  ", accepted2));
        return attacks.size();
    }

    /**
     * Double satisfaction across instances: two destroys/actions of different instances in one transaction, where
     * {@code b}'s payouts are dropped so {@code a}'s payout outputs would have to satisfy both.
     */
    public static TxModel sharedPayouts(TxModel a, TxModel b) {
        var c = a.copy();
        b.inputs.stream().filter(i -> b.spendRedeemers.containsKey(i.outRef())).forEach(c.inputs::add);
        c.spendRedeemers.putAll(b.spendRedeemers);
        if (b.stateOutput >= 0) c.outputs.add(b.outputs.get(b.stateOutput));
        c.mint = a.mint.merge(b.mint);
        return c;
    }

    // ------------------------------------------------------------------ helpers

    private static TxModel mapState(TxModel t, UnaryOperator<TxOut> f) {
        return mapOutput(t, t.stateOutput, f);
    }

    private static TxModel mapOutput(TxModel t, int index, UnaryOperator<TxOut> f) {
        var c = t.copy();
        c.outputs.set(index, f.apply(c.outputs.get(index)));
        return c;
    }

    private static TxModel withRange(TxModel t, IntervalBound from, IntervalBound to) {
        var c = t.copy();
        c.range = new Interval(from, to);
        return c;
    }

    private static IntervalBound shift(IntervalBound b, long ms) {
        if (b.boundType() instanceof IntervalBoundType.Finite f) {
            return new IntervalBound(new IntervalBoundType.Finite(f.time().add(BigInteger.valueOf(ms))), b.isInclusive());
        }
        return b;
    }
}
