package org.julclang.state.toolchain;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.state.api.Ctx;
import org.julclang.state.examples.dsl.Guild;
import org.julclang.state.examples.dsl.GuildClient;
import org.julclang.state.examples.dsl.GuildValidator;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

/**
 * ADR-0018 M3a3: the security review's attacks on moves (scratchpad m3-attacks.md: A20, A23, A25; Codex K21, K22, K24)
 * that the five-way vectors, the attack suite and the copied examples/guild and examples/market tests do not already
 * carry, each a raw transaction against the Guild port's validator, beside an accepted control. The source guild is the
 * harness's instance; the destination is another instance of the same policy, spent in the same transaction.
 */
class JscV2M3a3AttackTest {

    static final byte[] MASTER = StateHarness.fill(28, 0xAD), ANN = StateHarness.fill(28, 0xA1);
    static final byte[] B = StateHarness.fill(32, 0xB2), C = StateHarness.fill(32, 0xC3);
    static final TxOutRef B_REF = new TxOutRef(new TxId(StateHarness.fill(32, 0x0C)), BigInteger.ZERO);
    static final TxOutRef SECOND = new TxOutRef(new TxId(StateHarness.fill(32, 0x0E)), BigInteger.ZERO);
    static final BigInteger TWO = BigInteger.TWO;

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    final StateHarness<Guild.State> h = StateHarness.of(GuildClient.SPEC);
    final Guild.State open = new Guild.State(MASTER, true, BigInteger.ZERO, BigInteger.ONE);
    final Guild.State admitted = new Guild.State(MASTER, true, BigInteger.ONE, BigInteger.ONE);
    final Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);

    GuildValidator.Holding_Hero hero(byte[] instance, BigInteger level) {
        return new GuildValidator.Holding_Hero(instance, new Guild.Hero(ANN, level));
    }

    /** A level-2 hero of the harness guild moving into {@code into}, B admitting {@code admittedId} and ending as {@code destinationNext}. */
    TxModel move(byte[] into, byte[] admittedId, Guild.State destinationNext) {
        var obj = h.liveObject(hero(h.instanceId(), TWO), BigInteger.ZERO);
        var tx = h.actObject(obj, new GuildValidator.Transfer(into, ctx), hero(into, TWO), JulcList.empty(), ANN, ctx, open);
        return h.withInstance(tx, B, B_REF, open, new GuildValidator.Admit(admittedId, ctx), destinationNext, BigInteger.valueOf(h.minAda()));
    }

    /** A20: a move whose into names a third instance not in the transaction (B admits nothing of it). */
    @Test
    void aMoveIntoAnInstanceNotInTheTransaction_isRefused() {
        h.assertAccepted(move(B, h.objectId(), admitted));
        var obj = h.liveObject(hero(h.instanceId(), TWO), BigInteger.ZERO);
        var tx = h.actObject(obj, new GuildValidator.Transfer(C, ctx), hero(C, TWO), JulcList.empty(), ANN, ctx, open);
        h.assertRejected(h.withInstance(tx, B, B_REF, open, new GuildValidator.Admit(h.objectId(), ctx), admitted, BigInteger.valueOf(h.minAda())),
                "a move into C while B admits");
    }

    /**
     * A20: two heroes moving into one destination with one admit (naming the first): the second has no admit naming it,
     * so its move is refused (each admit consents to one object).
     */
    @Test
    void twoMovesIntoOneDestinationWithOneAdmit_isRefused() {
        TxModel one = move(B, h.objectId(), admitted);
        h.assertAccepted(one);
        byte[] second = new byte[29];
        second[0] = 0x4F;
        System.arraycopy(StateHarness.fill(28, 0x22), 0, second, 1, 28);
        Value v = Value.lovelace(BigInteger.valueOf(h.minAda())).merge(Value.singleton(new PolicyId(h.policy()), new TokenName(second), BigInteger.ONE));
        TxModel two = one.copy();
        two.inputs.add(new TxInInfo(SECOND, new TxOut(h.stateAddress(), v, new OutputDatum.OutputDatumInline(StateHarness.data(hero(h.instanceId(), TWO))),
                Optional.empty())));
        two.spendRedeemers.put(SECOND, StateHarness.data(new GuildValidator.Transfer(B, ctx)));
        two.outputs.add(new TxOut(h.stateAddress(), v, new OutputDatum.OutputDatumInline(StateHarness.data(hero(B, TWO))), Optional.empty()));
        h.assertRejected(two, "a second hero riding on the first one's admit");
    }

    /**
     * A23: the object's value travels with it. The destination's continuing output gaining the hero's deposit (and the
     * hero's output losing it) is refused; so is the source keeping a lovelace of it.
     */
    @Test
    void theObjectsValueTravelsWithIt() {
        TxModel honest = move(B, h.objectId(), admitted);
        h.assertAccepted(honest);
        // The destination's output (the last one, added by withInstance) gains one lovelace the hero's output loses.
        TxModel shifted = honest.copy();
        int dest = shifted.outputs.size() - 1;
        int obj = shifted.stateOutput;
        TxOut d = shifted.outputs.get(dest), o = shifted.outputs.get(obj);
        shifted.outputs.set(dest, new TxOut(d.address(), d.value().merge(Value.lovelace(BigInteger.ONE)), d.datum(), d.referenceScript()));
        shifted.outputs.set(obj, new TxOut(o.address(), o.value().merge(Value.lovelace(BigInteger.ONE.negate())), o.datum(), o.referenceScript()));
        h.assertRejected(shifted, "a lovelace of the hero's moved into the destination instance");
    }

    /**
     * A25, K22: admission reads the arriving object as its input holds it. The destination requires level 3; a level-2
     * hero whose continuing datum claims level 3 is refused (by the move, whose output must be its own result, and by the
     * admit, which reads level 2 from the input). The honest level-3 hero is admitted.
     */
    @Test
    void admissionReadsTheArrivingInput_notTheMoveResult() {
        var elite = new Guild.State(MASTER, true, BigInteger.ZERO, BigInteger.valueOf(3));
        var eliteNext = new Guild.State(MASTER, true, BigInteger.ONE, BigInteger.valueOf(3));
        var three = BigInteger.valueOf(3);
        var obj3 = h.liveObject(hero(h.instanceId(), three), BigInteger.ZERO);
        var ok = h.actObject(obj3, new GuildValidator.Transfer(B, ctx), hero(B, three), JulcList.empty(), ANN, ctx, open);
        h.assertAccepted(h.withInstance(ok, B, B_REF, elite, new GuildValidator.Admit(h.objectId(), ctx), eliteNext, BigInteger.valueOf(h.minAda())));
        var obj2 = h.liveObject(hero(h.instanceId(), TWO), BigInteger.ZERO);
        var forged = h.actObject(obj2, new GuildValidator.Transfer(B, ctx), hero(B, three), JulcList.empty(), ANN, ctx, open);
        h.assertRejected(h.withInstance(forged, B, B_REF, elite, new GuildValidator.Admit(h.objectId(), ctx), eliteNext, BigInteger.valueOf(h.minAda())),
                "a level-2 hero arriving as level 3");
    }

    /**
     * A25 (the data case): a hero whose owner is a 27-byte key. An arriving object comes only from an instance of this
     * policy (the admit finds its token under the own policy at the own script's address), so its datum is this
     * validator's own output, as an object's input always is, and no call of this contract writes a 27-byte owner
     * (recruit stores the signing caller). The arriving object is therefore not a checked input (as an object's own datum
     * is not, M3a1), and the validator accepts this hand-made one: pinned here, so a change of that rule shows.
     */
    @Test
    void anArrivingHeroWithA27ByteOwner_isNotACheckedInput() {
        byte[] short27 = StateHarness.fill(27, 0xA1);
        var bad = new GuildValidator.Holding_Hero(h.instanceId(), new Guild.Hero(short27, TWO));
        var obj = h.liveObject(bad, BigInteger.ZERO);
        Ctx c = h.ctx(short27, BigInteger.ZERO, BigInteger.ZERO);
        var tx = h.actObject(obj, new GuildValidator.Transfer(B, c), new GuildValidator.Holding_Hero(B, new Guild.Hero(short27, TWO)),
                JulcList.empty(), short27, c, open);
        var v = h.evaluate(h.withInstance(tx, B, B_REF, open, new GuildValidator.Admit(h.objectId(), c), admitted, BigInteger.valueOf(h.minAda())));
        System.out.println("[M3a3 A25] an arriving hero with a 27-byte owner: " + v);
        org.junit.jupiter.api.Assertions.assertTrue(v.accepted(), "the arriving object is this validator's own output, not a checked input: " + v);
    }

    /** K24: the move keeps the object's identity: its token on the continuing output replaced by another object id is refused. */
    @Test
    void aMoveKeepsTheObjectsToken() {
        TxModel honest = move(B, h.objectId(), admitted);
        var swapped = honest.copy();
        int obj = swapped.stateOutput;
        TxOut o = swapped.outputs.get(obj);
        byte[] other = new byte[29];
        other[0] = 0x4F;
        System.arraycopy(StateHarness.fill(28, 0x33), 0, other, 1, 28);
        Value v = Value.lovelace(BigInteger.valueOf(h.minAda())).merge(Value.singleton(new PolicyId(h.policy()), new TokenName(other), BigInteger.ONE));
        swapped.outputs.set(obj, new TxOut(o.address(), v, o.datum(), o.referenceScript()));
        h.assertRejected(swapped, "the moved hero continuing with another object's token");
    }
}
