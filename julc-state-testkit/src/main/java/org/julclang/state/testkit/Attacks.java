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
            // An intent may carry more than its payment plus deposit: the surplus is refunded (ADR-0004 D2).
            boolean intent = tx.token != null && tx.token.length == 29 && tx.token[0] == 0x49;
            if (!intent) {
                out.add(new Attack("one lovelace added to the state output", "I3", t -> mapState(t, o ->
                        new TxOut(o.address(), o.value().merge(Value.lovelace(BigInteger.ONE)), o.datum(),
                                o.referenceScript()))));
            }
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
            // An object's continuing Holding keeps its instance (I19); only a move names another, admitted one (I43).
            boolean object = tx.token != null && tx.token.length == 29 && tx.token[0] == 0x4F;
            if (object && !create && tx.outputs.get(tx.stateOutput).datum() instanceof OutputDatum.OutputDatumInline held
                    && held.datum() instanceof PlutusData.ConstrData cd && !cd.fields().isEmpty()
                    && cd.fields().get(0) instanceof PlutusData.BytesData) {
                out.add(new Attack("object's instance changed", "I19/I43", t -> mapState(t, o -> {
                    var d = (PlutusData.ConstrData) ((OutputDatum.OutputDatumInline) o.datum()).datum();
                    var fs = new ArrayList<>(d.fields());
                    // another instance id: never the one already there (a move's own destination, say)
                    byte[] now = ((PlutusData.BytesData) fs.get(0)).value();
                    byte[] other = StateHarness.fill(32, now.length > 0 && (now[0] & 0xFF) == 0x77 ? 0x78 : 0x77);
                    fs.set(0, PlutusData.bytes(other));
                    return new TxOut(o.address(), o.value(), new OutputDatum.OutputDatumInline(new PlutusData.ConstrData(d.tag(), fs)),
                            o.referenceScript());
                })));
            }
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
                // Issue #73: a key that is none of the transaction's parties (derived, never a fixed key a contract may
                // be owned by: then the attack would sign as an authorised key and be accepted).
                c.signers.add(outsider(t));
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

        // ---- native assets, sends and user mints (ADR-0007)
        if (tx.stateOutput >= 0 && hasNativeAssets(tx.outputs.get(tx.stateOutput).value(), h.policy(), tx)) {
            out.add(new Attack("one native token skimmed from the state output", "I26", t -> mapState(t, o ->
                    new TxOut(o.address(), lessOneToken(o.value(), h.policy(), t), o.datum(), o.referenceScript()))));
        }
        if (tx.outputClaims != null) {
            for (int k = 0; k < tx.sendOutputs.size(); k++) {
                final int idx = tx.sendOutputs.get(k);
                final int claim = k;
                out.add(new Attack("send #" + k + " under-filled by one unit", "I29", t -> mapOutput(t, idx, o ->
                        new TxOut(o.address(), lessOne(o.value()), o.datum(), o.referenceScript()))));
                out.add(new Attack("send #" + k + " delivered to another address", "I29", t -> mapOutput(t, idx, o ->
                        new TxOut(StateHarness.pkhAddress(StateHarness.fill(28, 0xBC)), o.value(), o.datum(), o.referenceScript()))));
                out.add(new Attack("send #" + k + " datum changed", "I29", t -> mapOutput(t, idx, o ->
                        new TxOut(o.address(), o.value(), new OutputDatum.OutputDatumInline(PlutusData.integer(7)),
                                o.referenceScript()))));
                out.add(new Attack("send #" + k + " claimed by another input", "I29", t -> {
                    var c = t.copy();
                    var cl = c.outputClaims.get(claim);
                    c.outputClaims.set(claim, new org.julclang.state.api.OutputClaim(cl.output(),
                            new TxOutRef(new TxId(StateHarness.fill(32, 0x98)), BigInteger.ZERO)));
                    return c;
                }));
                out.add(new Attack("send #" + k + " claim dropped", "I29", t -> {
                    var c = t.copy();
                    c.outputClaims.remove(claim);
                    return c;
                }));
                out.add(new Attack("send #" + k + " claim pointed at the state output or another output", "I29/I30", t -> {
                    var c = t.copy();
                    var cl = c.outputClaims.get(claim);
                    c.outputClaims.set(claim, new org.julclang.state.api.OutputClaim(BigInteger.ZERO, cl.by()));
                    c.outputClaims.sort(java.util.Comparator.comparing(org.julclang.state.api.OutputClaim::output));
                    return c;
                }));
                out.add(new Attack("send #" + k + " claimed twice (duplicate index)", "I29", t -> {
                    var c = t.copy();
                    c.outputClaims.add(claim, c.outputClaims.get(claim));
                    return c;
                }));
                out.add(new Attack("send #" + k + " claim with a negative index", "I29", t -> {
                    var c = t.copy();
                    var cl = c.outputClaims.get(claim);
                    c.outputClaims.add(0, new org.julclang.state.api.OutputClaim(BigInteger.valueOf(-1), cl.by()));
                    return c;
                }));
                out.add(new Attack("send #" + k + " carries this input's payout tag", "I30", t -> mapOutput(t, idx, o ->
                        new TxOut(o.address(), o.value(), new OutputDatum.OutputDatumInline(t.inputs.get(0).outRef().toPlutusData()),
                                o.referenceScript()))));
            }
            out.add(new Attack("output registry absent", "I29", t -> {
                var c = t.copy();
                c.outputClaims = null;
                return c;
            }));
        }
        if (userMint(tx, h.policy()) != null) {
            out.add(new Attack("one user token too many minted (to the attacker)", "I28", t -> {
                var c = t.copy();
                var e = userMint(c, h.policy());
                Value extra = org.julclang.state.api.Assets.of(h.policy(), e, BigInteger.ONE);
                c.mint = org.julclang.state.api.Assets.add(c.mint, extra);
                c.outputs.add(new TxOut(StateHarness.pkhAddress(StateHarness.fill(28, 0xBA)),
                        org.julclang.state.api.Assets.add(Value.lovelace(BigInteger.valueOf(2_000_000)), extra),
                        new OutputDatum.NoOutputDatum(), Optional.empty()));
                return c;
            }));
            out.add(new Attack("one user token too few minted", "I28", t -> {
                var c = t.copy();
                var e = userMint(c, h.policy());
                c.mint = org.julclang.state.api.Assets.subtract(c.mint, org.julclang.state.api.Assets.of(h.policy(), e,
                        BigInteger.ONE.multiply(BigInteger.valueOf(org.julclang.state.api.Assets.quantity(c.mint, h.policy(), e).signum()))));
                return c;
            }));
            out.add(new Attack("ByAction names another input", "I28", t -> {
                var c = t.copy();
                if (c.mintRedeemer instanceof PlutusData.ConstrData cd) {
                    c.mintRedeemer = new PlutusData.ConstrData(cd.tag(), List.of(
                            new TxOutRef(new TxId(StateHarness.fill(32, 0x33)), BigInteger.ZERO).toPlutusData()));
                }
                return c;
            }));
        }

        if (destroy) {
            out.add(new Attack("destroy without burning the token", "I8", t -> {
                var c = t.copy();
                c.mint = Value.zero();
                c.mintRedeemer = null;
                c.outputs.add(new TxOut(StateHarness.pkhAddress(StateHarness.fill(28, 0xBA)), h.token(t, BigInteger.ONE),
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
                c.mint = h.token(t, BigInteger.TWO);
                c.outputs.set(c.stateOutput, new TxOut(c.outputs.get(c.stateOutput).address(),
                        c.outputs.get(c.stateOutput).value().merge(h.token(t, BigInteger.ONE)),
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
                        i.resolved().value().merge(h.token(t, BigInteger.ONE.negate())), i.resolved().datum(), Optional.empty()))
                        : i);
                return c;
            }));
        }
        modifierAttacks(h, tx, out);
        return out;
    }

    /**
     * Why modifier attacks were not generated for a transaction, with how often (review F13): the counts are visible
     * ({@link #modifierSkips()}), and a description that does not describe the spent method fails instead of skipping.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> MODIFIER_SKIPS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Modifier attacks not generated, by reason (creations, batches, objects, …), since the JVM started. */
    public static java.util.Map<String, Integer> modifierSkips() {
        return java.util.Map.copyOf(MODIFIER_SKIPS);
    }

    private static void skip(String why) {
        MODIFIER_SKIPS.merge(why, 1, Integer::sum);
    }

    /**
     * The attacker of the modifier attacks (review C6): a key that is none of the transaction's parties, so the attack
     * cannot be a legitimate call by an authorised key. Derived from a fixed seed and checked, by content, against every
     * signer, the caller in the redeemer, and every byte string the spent state holds (its owners and signers).
     */
    static byte[] attacker(TxModel tx, PlutusData.ConstrData datum, PlutusData.ConstrData redeemer) {
        var taken = new ArrayList<byte[]>(tx.signers);
        collectBytes(datum, taken);
        collectBytes(redeemer, taken);
        return fresh(0xE5, taken);
    }

    /**
     * The signer of "required signer missing" (issue #73): a key that is none of the transaction's parties, derived as
     * {@link #attacker} is and checked, by content, against every signer, every byte string in the inputs' inline datums,
     * the redeemers (spend, mint, withdrawal) and the outputs' inline datums. A fixed key could be a contract's owner,
     * and the attack would then sign as that owner and be accepted: a false failure.
     */
    static byte[] outsider(TxModel tx) {
        var taken = new ArrayList<byte[]>(tx.signers);
        for (var in : tx.inputs) datumBytes(in.resolved(), taken);
        for (var in : tx.referenceInputs) datumBytes(in.resolved(), taken);
        for (var o : tx.outputs) datumBytes(o, taken);
        tx.spendRedeemers.values().forEach(r -> collectBytes(r, taken));
        tx.withdrawals.values().forEach(r -> collectBytes(r, taken));
        if (tx.mintRedeemer != null) collectBytes(tx.mintRedeemer, taken);
        return fresh(0xEE, taken);
    }

    private static void datumBytes(TxOut o, List<byte[]> into) {
        if (o.datum() instanceof OutputDatum.OutputDatumInline d) collectBytes(d.datum(), into);
    }

    /** The first of {@code fill(28, seed)}, then that key with its last two bytes varied, that is none of {@code taken}. */
    private static byte[] fresh(int seed, List<byte[]> taken) {
        for (int i = 0; ; i++) {
            byte[] k = StateHarness.fill(28, seed);
            k[26] ^= (byte) (i >> 8);
            k[27] ^= (byte) i;
            if (taken.stream().noneMatch(t -> java.util.Arrays.equals(t, k))) return k;
        }
    }

    private static void collectBytes(PlutusData d, List<byte[]> into) {
        switch (d) {
            case PlutusData.BytesData b -> into.add(b.value());
            case PlutusData.ConstrData c -> c.fields().forEach(f -> collectBytes(f, into));
            case PlutusData.ListData l -> l.items().forEach(f -> collectBytes(f, into));
            case PlutusData.MapData m -> m.entries().forEach(e -> {
                collectBytes(e.key(), into);
                collectBytes(e.value(), into);
            });
            default -> {
            }
        }
    }

    /**
     * Attacks only the method's own modifiers can refuse (ADR-0018 M1c review S2/F2), read from the contract's description
     * ({@code x-joust.actions[].policies}): for {@code @SignedBy(f)} with a Ctx, a key that is none of the transaction's
     * parties signs and is {@code ctx.caller} (so the generated caller-signed check holds, and only {@code @SignedBy} can
     * refuse; "required signer missing" leaves the caller as it was, so the caller check refuses it whatever the modifier
     * lowered to); for {@code @ValidAfter(f)} a window that ends before the state's {@code f}, and one that straddles it;
     * for {@code @ValidBefore(f)} a window that starts at {@code f}, and one that straddles it. The redeemer's Ctx and the
     * transaction agree in each, so only the modifier can refuse. Single-instance actions and destroys only: other
     * transactions are counted as skipped ({@link #modifierSkips()}), and a description that does not describe the
     * spent method fails.
     */
    private static void modifierAttacks(StateHarness<?> h, TxModel tx, List<Attack> out) {
        if (tx.kind == TxModel.Kind.CREATE) {
            skip("a creation (no state to spend)");
            return;
        }
        if (tx.spendRedeemers.size() != 1) {
            skip("not a single spend (a batch, a composition, or a purpose without a spend)");
            return;
        }
        if (tx.token != null && tx.token.length == 29) {
            skip("an object, a keyed object or an intent (29-byte token)");
            return;
        }
        var entry = tx.spendRedeemers.entrySet().iterator().next();
        Class<?> contract = h.spec().stateClass() == null ? null : h.spec().stateClass().getEnclosingClass();
        if (contract == null) {
            skip("a contract known only by its description (no state class)");
            return;
        }
        TxInInfo input = tx.inputs.stream().filter(i -> i.outRef().equals(entry.getKey())).findFirst().orElse(null);
        if (input == null || !(input.resolved().datum() instanceof OutputDatum.OutputDatumInline inline)
                || !(inline.datum() instanceof PlutusData.ConstrData datum) || !(entry.getValue() instanceof PlutusData.ConstrData redeemer)) {
            fail("modifier attacks: the spent input of " + contract.getSimpleName() + " has no inline state datum or no constructor redeemer");
            return;
        }
        com.fasterxml.jackson.databind.JsonNode d = Descriptions.of(contract);   // fails when it is missing
        com.fasterxml.jackson.databind.JsonNode method = null;
        for (var a : d.path("x-joust").path("actions")) {
            var r = a.path("redeemer");
            if (r.path("purpose").asText().equals("spend") && r.path("index").asInt(-1) == redeemer.tag()) method = a;
        }
        if (method == null || !method.has("policies") || !method.has("fields")) {
            fail("modifier attacks: the description of " + contract.getSimpleName() + " does not describe the spend redeemer "
                    + redeemer.tag() + " with its fields and policies (x-joust.actions[].policies)");
            return;
        }
        var fields = new ArrayList<String>();
        method.path("fields").forEach(f -> fields.add(f.asText()));
        int ctxAt = fields.indexOf("ctx");
        if (ctxAt >= 0 && (ctxAt >= redeemer.fields().size() || !(redeemer.fields().get(ctxAt) instanceof PlutusData.ConstrData ctx)
                || ctx.fields().size() != 5)) {
            fail("modifier attacks: " + contract.getSimpleName() + "." + method.path("name").asText() + "'s redeemer has no Ctx at field "
                    + ctxAt + " as its description says");
            return;
        }
        byte[] stranger = attacker(tx, datum, redeemer);
        String name = method.path("name").asText();
        var policies = method.path("policies");
        if (ctxAt >= 0) {
            for (var f : policies.path("signedBy")) {
                out.add(new Attack(name + ": a stranger signs as the caller (only " + f.asText() + ")", "I5", t -> {
                    var c = t.copy();
                    c.spendRedeemers.put(entry.getKey(), withCtx(redeemer, ctxAt, 0, PlutusData.bytes(stranger)));
                    c.signers.clear();
                    c.signers.add(stranger);
                    return c;
                }));
            }
        }
        // The state's field values, by name (the description's state schema).
        var stateFields = new ArrayList<String>();
        var stateRef = d.path("x-joust").path("state").path("$ref").asText().replace("#/definitions/", "");
        d.path("definitions").path(stateRef).path("anyOf").path(0).path("fields").forEach(f -> stateFields.add(f.path("title").asText()));
        for (String kind : List.of("validAfter", "validBefore")) {
            for (var f : policies.path(kind)) {
                int at = stateFields.indexOf(f.asText());
                if (at < 0 || at >= datum.fields().size() || !(datum.fields().get(at) instanceof PlutusData.IntData bound)) continue;
                BigInteger b = bound.value();
                boolean after = kind.equals("validAfter");
                // after(t) needs validFrom >= t; before(t) needs validTo <= t (Clock).
                BigInteger[][] windows = after
                        ? new BigInteger[][]{{b.subtract(BigInteger.valueOf(60_001)), b.subtract(BigInteger.ONE)}, {b.subtract(BigInteger.ONE), b.add(BigInteger.valueOf(59_999))}}
                        : new BigInteger[][]{{b, b.add(BigInteger.valueOf(60_000))}, {b.subtract(BigInteger.valueOf(59_999)), b.add(BigInteger.ONE)}};
                String[] what = after ? new String[]{"a window ending before " + f.asText(), "a window straddling " + f.asText()}
                        : new String[]{"a window starting at " + f.asText(), "a window straddling " + f.asText()};
                for (int k = 0; k < 2; k++) {
                    BigInteger from = windows[k][0], to = windows[k][1];
                    out.add(new Attack(name + ": " + what[k] + " (" + (after ? "after " : "before ") + f.asText() + ")", "I6", t -> {
                        var c = t.copy();
                        if (ctxAt >= 0) {
                            var r = withCtx(redeemer, ctxAt, 3, PlutusData.integer(from));
                            c.spendRedeemers.put(entry.getKey(), withCtx((PlutusData.ConstrData) r, ctxAt, 4, PlutusData.integer(to)));
                        }
                        c.range = StateHarness.window(from, to);
                        return c;
                    }));
                }
            }
        }
    }

    /** {@code redeemer} with field {@code i} of its Ctx (field {@code at}) replaced. */
    private static PlutusData withCtx(PlutusData.ConstrData redeemer, int at, int i, PlutusData v) {
        var ctx = (PlutusData.ConstrData) redeemer.fields().get(at);
        var cf = new ArrayList<>(ctx.fields());
        cf.set(i, v);
        var rf = new ArrayList<>(redeemer.fields());
        rf.set(at, new PlutusData.ConstrData(ctx.tag(), cf));
        return new PlutusData.ConstrData(redeemer.tag(), rf);
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

    /** The first user-token name the transaction mints or burns under the policy, or null (ADR-0007). */
    private static byte[] userMint(TxModel t, byte[] policy) {
        var ts = org.julclang.state.api.Assets.read(t.mint).get(java.util.HexFormat.of().formatHex(policy));
        if (ts == null) return null;
        for (String n : ts.keySet()) {
            byte[] name = java.util.HexFormat.of().parseHex(n);
            if (!java.util.Arrays.equals(name, t.token)) return name;
        }
        return null;
    }

    private static boolean hasNativeAssets(Value v, byte[] policy, TxModel t) {
        return firstToken(v, policy, t) != null;
    }

    /** The first native entry of {@code v} other than the transaction's own lifecycle token: {policy, name}. */
    private static String[] firstToken(Value v, byte[] policy, TxModel t) {
        String own = java.util.HexFormat.of().formatHex(policy);
        String tok = t.token == null ? "" : java.util.HexFormat.of().formatHex(t.token);
        for (var p : org.julclang.state.api.Assets.read(v).entrySet()) {
            if (p.getKey().isEmpty()) continue;
            for (String n : p.getValue().keySet()) {
                if (!(p.getKey().equals(own) && n.equals(tok))) return new String[]{p.getKey(), n};
            }
        }
        return null;
    }

    private static Value lessOneToken(Value v, byte[] policy, TxModel t) {
        String[] e = firstToken(v, policy, t);
        return org.julclang.state.api.Assets.subtract(v, org.julclang.state.api.Assets.of(
                java.util.HexFormat.of().parseHex(e[0]), java.util.HexFormat.of().parseHex(e[1]), BigInteger.ONE));
    }

    /** One unit less of the first native token, or one lovelace less if there is none. */
    private static Value lessOne(Value v) {
        for (var p : org.julclang.state.api.Assets.read(v).entrySet()) {
            if (p.getKey().isEmpty()) continue;
            String n = p.getValue().firstKey();
            return org.julclang.state.api.Assets.subtract(v, org.julclang.state.api.Assets.of(
                    java.util.HexFormat.of().parseHex(p.getKey()), java.util.HexFormat.of().parseHex(n), BigInteger.ONE));
        }
        return org.julclang.state.api.Assets.subtract(v, org.julclang.state.api.Assets.lovelace(BigInteger.ONE));
    }

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
