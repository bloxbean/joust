package org.julclang.state.examples.escrow;

import org.julclang.state.api.Ctx;
import org.julclang.state.api.RequireException;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;

class EscrowTest {
    static final byte[] BUYER = StateHarness.fill(28, 0xB0);
    static final byte[] SELLER = StateHarness.fill(28, 0x5E);
    static final byte[] ARBITER = StateHarness.fill(28, 0xAB);
    static final byte[] STRANGER = StateHarness.fill(28, 0x77);
    static final BigInteger PRICE = BigInteger.valueOf(25_000_000);
    static final BigInteger FUTURE = StateHarness.T0.add(BigInteger.valueOf(3_600_000));
    static final BigInteger PAST = StateHarness.T0.subtract(BigInteger.ONE);

    final StateHarness<Escrow.State> h = StateHarness.of(EscrowClient.SPEC);

    StateHarness.Live<Escrow.State> funded(BigInteger deadline) {
        return h.live(new Escrow.State(BUYER, SELLER, ARBITER, PRICE, deadline), PRICE);
    }

    @Test
    void open() {
        Ctx ctx = h.ctx(BUYER, PRICE, BigInteger.ZERO);
        var tx = h.create(new EscrowValidator.CreateInstance(h.seed(), SELLER, ARBITER, FUTURE, ctx),
                Escrow.open(SELLER, ARBITER, FUTURE, ctx), ctx);
        System.out.println("[open] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void release_byBuyer_paysTheSeller() {
        var live = funded(FUTURE);
        Ctx ctx = h.ctx(BUYER, BigInteger.ZERO, live.lovelace());
        var tx = h.destroy(live, new EscrowValidator.Release(ctx), new EscrowValidator.BurnInstance(),
                Escrow.release(live.state(), ctx), BUYER, ctx);
        System.out.println("[release] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void release_bySeller_rejected() {
        var live = funded(FUTURE);
        Ctx ctx = h.ctx(SELLER, BigInteger.ZERO, live.lovelace());
        assertThrows(RequireException.class, () -> Escrow.release(live.state(), ctx));
        // ...and on-chain, even with the payouts a cheating client would build:
        var tx = h.destroy(live, new EscrowValidator.Release(ctx), new EscrowValidator.BurnInstance(),
                org.julclang.state.api.Payouts.of(SELLER, live.lovelace()), SELLER, ctx);
        h.assertRejected(tx, "only buyer or arbiter releases");
    }

    @Test
    void refund_bySeller_beforeDeadline() {
        var live = funded(FUTURE);
        Ctx ctx = h.ctx(SELLER, BigInteger.ZERO, live.lovelace());
        var tx = h.destroy(live, new EscrowValidator.Refund(ctx), new EscrowValidator.BurnInstance(),
                Escrow.refund(live.state(), ctx), SELLER, ctx);
        System.out.println("[refund] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void refund_byStranger_onlyAfterTheDeadline() {
        Ctx ctx = h.ctx(STRANGER, BigInteger.ZERO, funded(FUTURE).lovelace());
        var early = funded(FUTURE);
        h.assertRejected(h.destroy(early, new EscrowValidator.Refund(ctx), new EscrowValidator.BurnInstance(),
                org.julclang.state.api.Payouts.of(BUYER, early.lovelace()), STRANGER, ctx), "stranger before deadline");
        var late = funded(PAST);
        h.assertAccepted(h.destroy(late, new EscrowValidator.Refund(ctx), new EscrowValidator.BurnInstance(),
                Escrow.refund(late.state(), ctx), STRANGER, ctx));
    }
}
