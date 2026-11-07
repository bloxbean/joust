package org.julclang.state.examples.dsl;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payouts;
import org.julclang.state.api.RequireException;
import org.julclang.state.runtime.InMemoryNodeStore;
import org.julclang.state.runtime.MerkleSupport;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contracts written in .jsc (ADR-0005): the generated Java checked against outcomes stated here independently of the
 * lowering, and the generated validators evaluated on-chain with the attack suite.
 */
class DslTest {
    static final byte[] OWNER = StateHarness.fill(28, 0x0A);
    static final byte[] ANN = StateHarness.fill(28, 0xA1);
    static final byte[] BEN = StateHarness.fill(28, 0xB1);
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    static BigInteger ada(long n) {
        return ADA.multiply(BigInteger.valueOf(n));
    }

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    // ------------------------------------------------------------------ Jar (A + D)

    final StateHarness<Jar.State> jar = StateHarness.of(JarClient.SPEC);

    @Test
    void jar_onTheJvm() {
        var s = Jar.init(ada(100), jar.ctx(OWNER, ada(5), BigInteger.ZERO));
        assertArrayEquals(OWNER, s.owner());
        assertEquals(ada(5), s.total());
        s = Jar.tip(s, jar.ctx(ANN, ada(3), ada(5)));
        assertEquals(new Jar.State(OWNER, ada(100), ada(8), BigInteger.ONE).total(), s.total());
        assertEquals(BigInteger.ONE, s.tips());
        Jar.State tipped = s;
        assertThrows(RequireException.class, () -> Jar.tip(tipped, jar.ctx(ANN, BigInteger.valueOf(999_999), ada(8))));
        // Before the goal: at most half of the balance.
        assertEquals(ada(4), Jar.withdraw(tipped, ada(4), jar.ctx(OWNER, BigInteger.ZERO, ada(8))).payouts().get(0).lovelace());
        assertThrows(RequireException.class, () -> Jar.withdraw(tipped, ada(5), jar.ctx(OWNER, BigInteger.ZERO, ada(8))));
        // After the goal: anything up to the balance.
        var rich = new Jar.State(OWNER, ada(10), ada(20), BigInteger.TWO);
        assertEquals(ada(20), Jar.withdraw(rich, ada(20), jar.ctx(OWNER, BigInteger.ZERO, ada(20))).payouts().get(0).lovelace());
        assertTrue(Jar.reached(rich));
        assertFalse(Jar.reached(tipped));
    }

    @Test
    void jar_onChain() {
        Ctx c0 = jar.ctx(OWNER, ada(5), BigInteger.ZERO);
        var create = jar.create(new JarValidator.CreateInstance(jar.seed(), ada(100), c0), Jar.init(ada(100), c0), c0);
        System.out.println("[jar init] " + jar.assertAccepted(create) + "  attacks rejected: " + Attacks.assertAllRejected(jar, create));

        var s = new Jar.State(OWNER, ada(100), ada(8), BigInteger.ONE);
        var live = jar.live(s, ada(8));
        Ctx tip = jar.ctx(ANN, ada(2), ada(8));
        var tipTx = jar.act(live, new JarValidator.Tip(tip), Jar.tip(s, tip), ANN, tip);
        System.out.println("[jar tip] " + jar.assertAccepted(tipTx) + "  attacks rejected: " + Attacks.assertAllRejected(jar, tipTx));

        Ctx w = jar.ctx(OWNER, BigInteger.ZERO, ada(8));
        var r = Jar.withdraw(s, ada(4), w);
        var wTx = jar.act(live, new JarValidator.Withdraw(ada(4), w), r.state(), r.payouts(), OWNER, w);
        System.out.println("[jar withdraw] " + jar.assertAccepted(wTx) + "  attacks rejected: " + Attacks.assertAllRejected(jar, wTx));
        // More than half before the goal: the arm's require runs on-chain too.
        jar.assertRejected(jar.act(live, new JarValidator.Withdraw(ada(5), w), s, Payouts.of(OWNER, ada(5)), OWNER, w),
                "more than half before the goal");
        jar.assertRejected(jar.act(live, new JarValidator.Withdraw(ada(1), jar.ctx(ANN, BigInteger.ZERO, ada(8))), s,
                Payouts.of(ANN, ada(1)), ANN, jar.ctx(ANN, BigInteger.ZERO, ada(8))), "only owner");

        Ctx cl = jar.ctx(OWNER, BigInteger.ZERO, live.lovelace());
        var close = jar.destroy(live, new JarValidator.Close(cl), new JarValidator.BurnInstance(), Jar.close(s, cl), OWNER, cl);
        System.out.println("[jar close] " + jar.assertAccepted(close) + "  attacks rejected: " + Attacks.assertAllRejected(jar, close));
    }

    // ------------------------------------------------------------------ Guards: laziness of arms and ||

    final StateHarness<Guards.State> guards = StateHarness.of(GuardsClient.SPEC);

    @Test
    void untakenArmsAndShortCircuits_doNotRun_onTheJvmOrOnChain() {
        var s = new Guards.State(OWNER, BigInteger.TEN);
        assertEquals(BigInteger.ZERO, Guards.set(s, BigInteger.ZERO).n());
        assertEquals(BigInteger.valueOf(25), Guards.set(s, BigInteger.valueOf(4)).n());
        assertEquals(BigInteger.ZERO, Guards.check(s, BigInteger.ZERO).n());
        assertThrows(RequireException.class, () -> Guards.check(s, BigInteger.valueOf(200)));

        var live = guards.live(s, BigInteger.ZERO);
        guards.assertAccepted(guards.act(live, new GuardsValidator.Set(BigInteger.ZERO), Guards.set(s, BigInteger.ZERO), null, null));
        guards.assertAccepted(guards.act(live, new GuardsValidator.Set(BigInteger.valueOf(4)),
                new Guards.State(OWNER, BigInteger.valueOf(25)), null, null));
        guards.assertAccepted(guards.act(live, new GuardsValidator.Check(BigInteger.ZERO), Guards.check(s, BigInteger.ZERO), null, null));
        guards.assertRejected(guards.act(live, new GuardsValidator.Check(BigInteger.valueOf(200)),
                new Guards.State(OWNER, BigInteger.valueOf(200)), null, null), "100 / 200 > 1 is false");
        guards.assertRejected(guards.act(live, new GuardsValidator.Set(BigInteger.valueOf(4)),
                new Guards.State(OWNER, BigInteger.ZERO), null, null), "the else arm's value, not the then arm's");
    }

    // ------------------------------------------------------------------ Points (B)

    final StateHarness<Points.State> pts = StateHarness.of(PointsClient.SPEC);
    final InMemoryNodeStore store = new InMemoryNodeStore();

    MerkleSupport.Prepared<Points.State> call(Points.State current, Function<Points.State, Points.State> method) {
        var ms = new MerkleSupport(store);
        Points.State next = ms.run(() -> method.apply(current));
        return ms.prepare(pts.policy(), Points.State.class, current, next, StateHarness.LIVE_REF);
    }

    TxModel act(Points.State s, Object redeemer, MerkleSupport.Prepared<Points.State> p, byte[] signer, Ctx ctx) {
        return pts.withClaims(pts.act(pts.live(s, BigInteger.ZERO), redeemer, p.stored(), signer, ctx), p.claims());
    }

    Points.State seeded() {
        var ms = new MerkleSupport(store);
        Ctx ctx = pts.ctx(OWNER, BigInteger.ZERO, BigInteger.ZERO);
        Points.State s = ms.prepareCreation(pts.policy(), Points.State.class, Points.init(ctx), pts.seed()).stored();
        s = call(s, x -> Points.award(x, ANN, BigInteger.valueOf(30))).stored();
        return call(s, x -> Points.award(x, BEN, BigInteger.valueOf(10))).stored();
    }

    BigInteger pointsOf(Points.State s, byte[] who) {
        return new MerkleSupport(store).run(() -> Points.pointsOf(s, who));
    }

    @Test
    void points_awardGiveSpend() {
        var s = seeded();
        assertEquals(BigInteger.valueOf(30), pointsOf(s, ANN));
        assertEquals(BigInteger.valueOf(40), s.supply());

        Ctx annCtx = pts.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var give = call(s, x -> Points.give(x, BEN, BigInteger.valueOf(5), annCtx));
        var giveTx = act(s, new PointsValidator.Give(BEN, BigInteger.valueOf(5), annCtx), give, ANN, annCtx);
        System.out.println("[points give] " + pts.assertAccepted(giveTx) + "  attacks rejected: " + Attacks.assertAllRejected(pts, giveTx));
        var s2 = give.stored();
        assertEquals(BigInteger.valueOf(25), pointsOf(s2, ANN));
        assertEquals(BigInteger.valueOf(15), pointsOf(s2, BEN));

        // A self-transfer reads its own debit: the balance is unchanged.
        var self = call(s2, x -> Points.give(x, ANN, BigInteger.valueOf(5), annCtx));
        pts.assertAccepted(act(s2, new PointsValidator.Give(ANN, BigInteger.valueOf(5), annCtx), self, ANN, annCtx));
        assertEquals(BigInteger.valueOf(25), pointsOf(self.stored(), ANN));

        // Spending everything removes the holder (the then arm, with a delete); partly (the else arm).
        Ctx benCtx = pts.ctx(BEN, BigInteger.ZERO, BigInteger.ZERO);
        var all = call(s2, x -> Points.spend(x, BigInteger.valueOf(15), benCtx));
        var allTx = act(s2, new PointsValidator.Spend(BigInteger.valueOf(15), benCtx), all, BEN, benCtx);
        System.out.println("[points spend all] " + pts.assertAccepted(allTx) + "  attacks rejected: " + Attacks.assertAllRejected(pts, allTx));
        assertEquals(BigInteger.ZERO, pointsOf(all.stored(), BEN));
        var part = call(s2, x -> Points.spend(x, BigInteger.valueOf(5), annCtx));
        pts.assertAccepted(act(s2, new PointsValidator.Spend(BigInteger.valueOf(5), annCtx), part, ANN, annCtx));
        assertEquals(BigInteger.valueOf(20), pointsOf(part.stored(), ANN));

        assertThrows(RequireException.class, () -> new MerkleSupport(store).run(() -> Points.spend(s2, BigInteger.valueOf(26), annCtx)));
        assertThrows(RequireException.class, () -> new MerkleSupport(store).run(() ->
                Points.give(s2, new byte[3], BigInteger.ONE, annCtx)), "a Key argument must be 28 bytes");
    }

    @Test
    void points_closeOnlyWhenEverythingIsSpent() {
        var s = seeded();
        Ctx ctx = pts.ctx(OWNER, BigInteger.ZERO, BigInteger.valueOf(PointsClient.SPEC.minAda()));
        assertThrows(RequireException.class, () -> new MerkleSupport(store).run(() -> Points.close(s, ctx)));
        var empty = new Points.State(OWNER, BigInteger.ZERO, org.julclang.state.api.MerkleMaps.empty());
        JulcList<org.julclang.state.api.Payout> out = Points.close(empty, ctx);
        assertEquals(BigInteger.valueOf(PointsClient.SPEC.minAda()), out.get(0).lovelace());
    }
}
