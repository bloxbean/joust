package org.julclang.state.examples.dex;

import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.Ctx;
import org.julclang.state.runtime.Transition;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The AMM (ADR-0007): pool creation, liquidity with LP mint/burn, swaps both ways, close; each against the attack suite. */
class DexTest {
    static final byte[] ADMIN = StateHarness.fill(28, 0xAD);
    static final byte[] LP = StateHarness.fill(28, 0x1A);
    static final byte[] TRADER = StateHarness.fill(28, 0x7E);
    static final byte[] TOKEN_POLICY = StateHarness.fill(28, 0x70);
    static final byte[] TOKEN = "HOSKY".getBytes();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    final StateHarness<Dex.Pool> h = StateHarness.of(DexClient.SPEC);

    static BigInteger ada(long n) {
        return ADA.multiply(BigInteger.valueOf(n));
    }

    Value tokens(long n) {
        return Assets.of(TOKEN_POLICY, TOKEN, BigInteger.valueOf(n));
    }

    Dex.Pool fresh() {
        return Dex.open(TOKEN_POLICY, TOKEN, h.ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO), h.createFunds(false, Assets.none()));
    }

    /** A pool holding 100 ADA and 1000 tokens, with 100 ADA of LP supply. */
    StateHarness.Held funded() {
        Dex.Pool p = fresh();
        var withLp = new Dex.Pool(p.admin(), p.tokenPolicy(), p.tokenName(), p.lpName(), ada(100));
        return h.held(withLp, ada(100), tokens(1000));
    }

    static <S> Transition<S> of(Dex.Moved m) {
        @SuppressWarnings("unchecked")
        Transition<S> t = (Transition<S>) Transition.full(m.state(), m.payouts(), m.sends(), m.mint(), m.accepted());
        return t;
    }

    @Test
    void open() {
        Ctx ctx = h.ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO);
        Dex.Pool p = Dex.open(TOKEN_POLICY, TOKEN, ctx, h.createFunds(false, Assets.none()));
        var tx = h.createWith(new DexValidator.CreateInstance(h.seed(), TOKEN_POLICY, TOKEN, ctx), p, ctx, Assets.none(), false, null);
        System.out.println("[open] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void firstLiquidity_mintsLpToTheProvider() {
        Dex.Pool p = fresh();
        var live = h.held(p, BigInteger.ZERO, Assets.none());
        Ctx ctx = h.ctx(LP, ada(50), BigInteger.ZERO);
        var m = Dex.addLiquidity(p, BigInteger.valueOf(500), ctx, h.funds(live, tokens(500)));
        assertEquals(ada(50), m.state().lpSupply());
        var tx = h.stepWith(live, new DexValidator.AddLiquidity(BigInteger.valueOf(500), ctx, tokens(500).toPlutusData()),
                new DexValidator.ByAction(live.ref()), of(m), m.state(), LP, ctx, null);
        System.out.println("[add] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void swapAdaForToken() {
        var live = funded();
        Dex.Pool p = (Dex.Pool) live.datum();
        Ctx ctx = h.ctx(TRADER, ada(10), ada(100));
        var m = Dex.swapAdaForToken(p, BigInteger.valueOf(80), ctx, h.funds(live, Assets.none()));
        assertEquals(BigInteger.valueOf(90), Assets.quantity(m.sends().head().value(), TOKEN_POLICY, TOKEN));
        var tx = h.stepWith(live, new DexValidator.SwapAdaForToken(BigInteger.valueOf(80), ctx, Assets.none().toPlutusData()), null, of(m),
                m.state(), TRADER, ctx, null);
        System.out.println("[swap ada->token] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void swapTokenForAda() {
        var live = funded();
        Dex.Pool p = (Dex.Pool) live.datum();
        Ctx ctx = h.ctx(TRADER, BigInteger.ZERO, ada(100));
        var m = Dex.swapTokenForAda(p, BigInteger.valueOf(100), ada(8), ctx, h.funds(live, tokens(100)));
        var tx = h.stepWith(live, new DexValidator.SwapTokenForAda(BigInteger.valueOf(100), ada(8), ctx, tokens(100).toPlutusData()), null,
                of(m), m.state(), TRADER, ctx, null);
        System.out.println("[swap token->ada] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void removeLiquidity_burnsLp() {
        var live = funded();
        Dex.Pool p = (Dex.Pool) live.datum();
        Value lp = Assets.of(h.policy(), p.lpName(), ada(40));
        Ctx ctx = h.ctx(LP, BigInteger.ZERO, ada(100));
        var m = Dex.removeLiquidity(p, ada(40), ctx, h.funds(live, lp));
        var tx = h.stepWith(live, new DexValidator.RemoveLiquidity(ada(40), ctx, lp.toPlutusData()), new DexValidator.ByAction(live.ref()),
                of(m), m.state(), LP, ctx, null);
        System.out.println("[remove] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }
}
