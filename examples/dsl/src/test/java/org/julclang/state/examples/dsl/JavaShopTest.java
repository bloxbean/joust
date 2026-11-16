package org.julclang.state.examples.dsl;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.api.RequireException;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D9 (M4): a Java contract calling a {@code .jsc} library. {@link JavaShop} uses {@code fees.jsc}'s generated
 * {@link Fees} class: its record and enum in the state and a redeemer, its fns in a require, the payouts and a view. The
 * same library code runs on the JVM and, compiled by JuLC into the validator, on-chain. Outcomes are stated by hand; the
 * attack suite runs on each accepted transaction.
 */
class JavaShopTest {
    static final byte[] OWNER = StateHarness.fill(28, 0x0A);
    static final byte[] ANN = StateHarness.fill(28, 0xA1);
    static final byte[] BEN = StateHarness.fill(28, 0xB1);

    static BigInteger n(long v) {
        return BigInteger.valueOf(v);
    }

    final StateHarness<JavaShop.State> h = StateHarness.of(JavaShopClient.SPEC);

    static JulcList<Fees.Line> lines() {
        return JulcList.of(new Fees.Line(ANN, n(6_000)), new Fees.Line(BEN, n(3_000)));
    }

    @Test
    void theLibrary_onTheJvm() {
        // By hand: 1% for Basic, 0.5% for Gold before ten sales, 0.25% after.
        assertEquals(n(100), Fees.rate(new Fees.Basic()));
        assertEquals(n(50), Fees.rate(new Fees.Gold(n(9))));
        assertEquals(n(25), Fees.rate(new Fees.Gold(n(10))));
        assertEquals(n(-3), Fees.fee(n(-7_000), n(5)), "division truncates toward zero");
        assertEquals(n(9_000), Fees.total(lines()));
        assertEquals(n(4), Fees.digits(n(1234)));
        assertThrows(RequireException.class, () -> Fees.nonNegative(n(-1)));
    }

    @Test
    void javaCallsTheJscLibrary_onTheJvmAndOnChain() {
        Ctx c0 = h.ctx(OWNER, BigInteger.ZERO, BigInteger.ZERO);
        var s0 = JavaShop.init(n(10_000_000), lines(), c0);
        assertEquals(n(0), s0.sold());
        assertInstanceOf(Fees.Basic.class, s0.tier());
        var create = h.create(new JavaShopValidator.CreateInstance(h.seed(), n(10_000_000), lines(), c0), s0, c0);
        System.out.println("[javashop init] " + h.assertAccepted(create) + "  attacks rejected: " + Attacks.assertAllRejected(h, create));
        assertThrows(RequireException.class, () -> JavaShop.init(n(-1), lines(), c0), "the library's own require");
        h.assertRejected(h.create(new JavaShopValidator.CreateInstance(h.seed(), n(-1), lines(), c0),
                new JavaShop.State(OWNER, n(-1), lines(), new Fees.Basic(), n(0)), c0), "a negative price, on-chain");

        var live = h.live(s0, BigInteger.ZERO);
        // A Basic sale needs the price plus 1%: 10,100,000.
        Ctx short_ = h.ctx(ANN, n(10_099_999), BigInteger.ZERO);
        assertThrows(RequireException.class, () -> JavaShop.buy(s0, short_));
        Ctx paid = h.ctx(ANN, n(10_100_000), BigInteger.ZERO);
        var s1 = JavaShop.buy(s0, paid);
        assertEquals(n(1), s1.sold());
        var buy = h.act(live, new JavaShopValidator.Buy(paid), s1, ANN, paid);
        System.out.println("[javashop buy] " + h.assertAccepted(buy) + "  attacks rejected: " + Attacks.assertAllRejected(h, buy));
        h.assertRejected(h.act(live, new JavaShopValidator.Buy(short_), s1, ANN, short_), "a sale short of the library's fee");

        // A library enum in the redeemer.
        var gold = new JavaShop.State(OWNER, s0.price(), s0.lines(), new Fees.Gold(n(10)), s0.sold());
        h.assertAccepted(h.act(live, new JavaShopValidator.SetTier(new Fees.Gold(n(10))), gold, OWNER, null));
        assertEquals(n(25_000), JavaShop.feeOn(gold, n(10_000_000)));

        // Close: 60% and 30% of the balance to the lines (the library's cuts), the remainder to the owner.
        var live2 = h.live(s0, n(25_000_003));
        Ctx cl2 = h.ctx(OWNER, BigInteger.ZERO, live2.lovelace());
        JulcList<Payout> out = JavaShop.close(s0, cl2);
        BigInteger b = live2.lovelace();
        assertEquals(3, out.size());
        assertEquals(b.multiply(n(6_000)).divide(n(10_000)), out.get(0).lovelace());
        assertEquals(b.multiply(n(3_000)).divide(n(10_000)), out.get(1).lovelace());
        assertEquals(b.subtract(out.get(0).lovelace()).subtract(out.get(1).lovelace()), out.get(2).lovelace());
        var close = h.destroy(live2, new JavaShopValidator.Close(cl2), new JavaShopValidator.BurnInstance(), out, OWNER, cl2);
        System.out.println("[javashop close] " + h.assertAccepted(close) + "  attacks rejected: " + Attacks.assertAllRejected(h, close));
    }
}
