package org.julclang.state.examples.fees;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.DepositClaim;
import org.julclang.state.api.ForeignInstance;
import org.julclang.state.api.RequireException;
import org.julclang.state.runtime.Transition;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.julclang.stdlib.Builtins;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deposits into another contract's instance (ADR-0011 D3, I40): a stall sells and deposits its fee into a pot, which
 * takes it in with {@code @Receives}, in one transaction. Both validators, and the output verifier, are evaluated.
 */
class FeesTest {
    static final byte[] OWNER = StateHarness.fill(28, 0x0A);
    static final byte[] BUYER = StateHarness.fill(28, 0xB1);
    static final BigInteger PRICE = BigInteger.valueOf(10_000_000);
    static final BigInteger FEE = BigInteger.valueOf(500_000);     // 5%
    static final TxOutRef POT_REF = new TxOutRef(new TxId(StateHarness.fill(32, 0x0D)), BigInteger.ONE);

    final StateHarness<Pot.State> pot = StateHarness.of(PotClient.SPEC);
    final Stall.Config config = new Stall.Config(new ForeignInstance(pot.policy(), pot.instanceId()), BigInteger.valueOf(500));
    final StateHarness<Stall.State> stall = StateHarness.of(StallClient.SPEC, config);
    final Stall.State stallState = new Stall.State(OWNER, BigInteger.ZERO);
    final Pot.State potState = new Pot.State(OWNER, BigInteger.ZERO, BigInteger.ZERO);

    StateHarness.Held potHeld() {
        Value v = Assets.add(Assets.lovelace(BigInteger.valueOf(pot.minAda())), Assets.of(pot.policy(), pot.instanceId(), BigInteger.ONE));
        return new StateHarness.Held(POT_REF, potState, v, pot.instanceId());
    }

    /** The sale and the pot's collect in one transaction; {@code claimed} is the deposit claim's value. */
    TxModel sale(Value claimed, boolean withPot, boolean withClaim) {
        Ctx ctx = stall.ctx(BUYER, PRICE, BigInteger.ZERO);
        var sale = Stall.sell(stallState, config, PRICE, ctx);
        assertEquals(FEE, Assets.lovelaceOf(sale.deposits().head().value()));
        var tx = stall.stepWith(stall.held(stallState, BigInteger.ZERO, Assets.none()), new StallValidator.Sell(PRICE, ctx), null,
                Transition.full(sale.state(), null, null, null, null).withDeposits(sale.deposits()), sale.state(), BUYER, ctx, null);
        if (!withPot) return tx;
        int potOut = tx.outputs.size();
        var claim = new DepositClaim(BigInteger.valueOf(potOut), StateHarness.LIVE_REF, claimed, Builtins.bData(BUYER));
        Ctx potCtx = pot.ctx(BUYER, BigInteger.ZERO, BigInteger.ZERO);
        var claims = JulcList.<DepositClaim>empty().prepend(claim);
        Pot.State next;
        try {
            next = Pot.collect(potState, potCtx, pot.funds(potHeld(), Assets.none(), claims));
        } catch (RequireException refusedOnTheJvm) {   // build the state anyway: the validators must refuse it too
            next = new Pot.State(OWNER, Assets.lovelaceOf(claimed), BigInteger.ONE);
        }
        var potTx = pot.stepWith(potHeld(), new PotValidator.Collect(potCtx), null, Transition.full(next, null, null, null, null),
                next, BUYER, potCtx, null);
        // the pot's output also holds the deposit (its value equation adds the claimed deposits)
        TxOut o = potTx.outputs.get(potTx.stateOutput);
        potTx.outputs.set(potTx.stateOutput, new TxOut(o.address(), Assets.add(o.value(), claimed), o.datum(), o.referenceScript()));
        var merged = merge(tx, potTx);
        if (withClaim) merged.depositClaims = new ArrayList<>(List.of(claim));
        return merged;
    }

    /** {@code a} then {@code b}'s inputs, outputs, redeemers and signers (the shared wallet input once). */
    static TxModel merge(TxModel a, TxModel b) {
        var m = a.copy();
        for (TxInInfo in : b.inputs) if (m.inputs.stream().noneMatch(x -> x.outRef().equals(in.outRef()))) m.inputs.add(in);
        m.outputs.addAll(b.outputs);
        m.spendRedeemers.putAll(b.spendRedeemers);
        for (byte[] sg : b.signers) if (m.signers.stream().noneMatch(x -> java.util.Arrays.equals(x, sg))) m.signers.add(sg);
        return m;
    }

    @Test
    void saleDepositsTheFeeIntoThePot() {
        var tx = sale(Assets.lovelace(FEE), true, true);
        System.out.println("[stall] " + stall.assertAccepted(tx));
        System.out.println("[pot] " + pot.assertAccepted(tx));
        System.out.println("  stall attacks rejected: " + Attacks.assertAllRejected(stall, tx));
    }

    @Test
    void depositWithoutItsReceiver_orWithoutItsClaim_rejected() {
        stall.assertRejected(sale(Assets.lovelace(FEE), false, false), "the pot is not spent");
        var unclaimed = sale(Assets.lovelace(FEE), true, false);
        stall.assertRejected(unclaimed, "no deposit claim");
    }

    @Test
    void wrongClaimValue_rejected() {
        // a claim of less than the fee: the stall refuses it (it deposits exactly its fee)
        stall.assertRejected(sale(Assets.lovelace(FEE.subtract(BigInteger.ONE)), true, true), "claim below the deposit");
        // a negative or token-carrying claim: the output verifier refuses it
        stall.assertRejected(sale(Assets.lovelace(FEE.negate()), true, true), "a negative deposit");
        var withThreadToken = Assets.add(Assets.lovelace(FEE), Assets.of(StateHarness.fill(28, 0x99), StateHarness.fill(32, 1), BigInteger.ONE));
        pot.assertRejected(sale(withThreadToken, true, true), "a reserved-kind token in a deposit");
    }

    @Test
    void anExtraClaimByTheDepositor_rejected() {
        var tx = sale(Assets.lovelace(FEE), true, true);
        tx.depositClaims.add(new DepositClaim(BigInteger.valueOf(tx.outputs.size() - 1), StateHarness.LIVE_REF, Assets.lovelace(BigInteger.ONE),
                Builtins.bData(BUYER)));
        stall.assertRejected(tx, "a second claim naming the stall's input");
    }

    @Test
    void thePotCountsEveryClaim_andRefusesNone() {
        Ctx potCtx = pot.ctx(BUYER, BigInteger.ZERO, BigInteger.ZERO);
        assertThrows(RequireException.class, () -> Pot.collect(potState, potCtx, pot.funds(potHeld(), Assets.none(), JulcList.empty())));
        // the pot output without the deposit's value: the pot's value equation refuses it
        var tx = sale(Assets.lovelace(FEE), true, true);
        var o = tx.outputs.get(tx.outputs.size() - 1);
        tx.outputs.set(tx.outputs.size() - 1, new TxOut(o.address(), Assets.subtract(o.value(), Assets.lovelace(FEE)), o.datum(),
                o.referenceScript()));
        pot.assertRejected(tx, "the deposit is not in the pot's output");
    }

    @Test
    void depositIntoANonReceivingAction_rejectedByTheReceiver() {
        // review r3 C1: the pot spent with handOver (not @Receives) while a claim names its output; the fee would go to
        // the builder's change. The pot's validator refuses the claim.
        Ctx ctx = stall.ctx(BUYER, PRICE, BigInteger.ZERO);
        var sale = Stall.sell(stallState, config, PRICE, ctx);
        var tx = stall.stepWith(stall.held(stallState, BigInteger.ZERO, Assets.none()), new StallValidator.Sell(PRICE, ctx), null,
                Transition.full(sale.state(), null, null, null, null).withDeposits(sale.deposits()), sale.state(), BUYER, ctx, null);
        int potOut = tx.outputs.size();
        var next = Pot.handOver(potState, BUYER);
        var potTx = pot.stepWith(potHeld(), new PotValidator.HandOver(BUYER), null, Transition.full(next, null, null, null, null),
                next, OWNER, null, null);
        var merged = merge(tx, potTx);
        merged.depositClaims = new ArrayList<>(List.of(new DepositClaim(BigInteger.valueOf(potOut), StateHarness.LIVE_REF,
                Assets.lovelace(FEE), Builtins.bData(BUYER))));
        stall.assertAccepted(merged);   // the stall cannot tell how the pot is spent
        pot.assertRejected(merged, "a deposit claimed on a non-@Receives output");
    }
}
