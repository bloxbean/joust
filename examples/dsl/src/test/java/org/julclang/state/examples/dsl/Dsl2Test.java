package org.julclang.state.examples.dsl;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Payouts;
import org.julclang.state.api.RequireException;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contracts written in {@code .jsc} 2 (ADR-0018, M0). Expected outcomes are stated here by hand, independently of the
 * lowering (hashes come from cardano-client-lib), and the generated validators are evaluated on-chain with the attack
 * suite: the JVM and the chain must agree on every case.
 */
class Dsl2Test {
    static final byte[] OWNER = StateHarness.fill(28, 0x0A);
    static final byte[] ANN = StateHarness.fill(28, 0xA1);
    static final byte[] BEN = StateHarness.fill(28, 0xB1);
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final BigInteger T0 = StateHarness.T0;

    static BigInteger ada(long n) {
        return ADA.multiply(BigInteger.valueOf(n));
    }

    static BigInteger n(long n) {
        return BigInteger.valueOf(n);
    }

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    // ------------------------------------------------------------------ Counter2: the template counter

    final StateHarness<Counter2.State> counter = StateHarness.of(Counter2Client.SPEC);

    @Test
    void counter_onTheJvmAndOnChain() {
        Ctx c0 = counter.ctx(OWNER, BigInteger.ZERO, BigInteger.ZERO);
        var s0 = Counter2.init(c0);
        assertEquals(BigInteger.ZERO, s0.count());
        assertArrayEquals(OWNER, s0.owner());
        var create = counter.create(new Counter2Validator.CreateInstance(counter.seed(), c0), s0, c0);
        System.out.println("[counter2 init] " + counter.assertAccepted(create) + "  attacks rejected: "
                + Attacks.assertAllRejected(counter, create));

        var s = new Counter2.State(n(4), OWNER);
        assertEquals(n(9), Counter2.increment(s, n(5)).count());
        assertThrows(RequireException.class, () -> Counter2.increment(s, BigInteger.ZERO));
        assertEquals(n(4), Counter2.current(s));

        var live = counter.live(s, BigInteger.ZERO);
        var inc = counter.act(live, new Counter2Validator.Increment(n(5)), new Counter2.State(n(9), OWNER), null, null);
        System.out.println("[counter2 increment] " + counter.assertAccepted(inc) + "  attacks rejected: "
                + Attacks.assertAllRejected(counter, inc));
        counter.assertRejected(counter.act(live, new Counter2Validator.Increment(n(5)), new Counter2.State(n(10), OWNER), null, null),
                "a next state other than 4 + 5");
        counter.assertRejected(counter.act(live, new Counter2Validator.Increment(BigInteger.ZERO), s, null, null), "by = 0");
        counter.assertAccepted(counter.act(live, new Counter2Validator.Reset(), new Counter2.State(BigInteger.ZERO, OWNER), OWNER, null));
        counter.assertRejected(counter.act(live, new Counter2Validator.Reset(), new Counter2.State(BigInteger.ZERO, OWNER), ANN, null),
                "reset by someone else");

        Ctx cl = counter.ctx(OWNER, BigInteger.ZERO, live.lovelace());
        JulcList<Payout> out = Counter2.close(s, cl);
        assertEquals(1, out.size());
        assertEquals(live.lovelace(), out.get(0).lovelace());
        var close = counter.destroy(live, new Counter2Validator.Close(cl), new Counter2Validator.BurnInstance(), out, OWNER, cl);
        System.out.println("[counter2 close] " + counter.assertAccepted(close) + "  attacks rejected: "
                + Attacks.assertAllRejected(counter, close));
    }

    /**
     * Issue #74 (design F9 on #71): a real example's refusal on the JVM names its {@code .jsc} site, from the message map
     * the build put beside the contract's classes (Counter2.jscmap.json), read by the contract's spec as the runtime
     * reads it. An author's require and a generated check (a negative payout's key) both.
     */
    @Test
    void counter_aRefusalNamesItsJscSite_fromTheMapTheBuildShips() {
        var spec = Counter2Client.SPEC;
        var s = new Counter2.State(n(4), OWNER);
        var byZero = assertThrows(RequireException.class, () -> Counter2.increment(s, BigInteger.ZERO));
        assertEquals("increment must be positive [Counter2.jsc:13:5 (a require in increment)]",
                org.julclang.state.runtime.JscMessages.explained(spec.validatorClass(), spec.name(), byZero).getMessage());
        // close pays the owner the balance: a negative one is refused by a generated check, whose key names the site.
        var negative = assertThrows(RequireException.class, () -> Counter2.close(s, counter.ctx(OWNER, BigInteger.ZERO, n(-1))));
        String explained = org.julclang.state.runtime.JscMessages.explained(spec.validatorClass(), spec.name(), negative).getMessage();
        assertTrue(explained.matches("#pay:spend\\d+\\.\\d+ \\[Counter2\\.jsc:22:5 \\(a payout in close is negative\\)\\]"), explained);
        assertTrue(explained.startsWith(negative.getMessage() + " ["), explained);
    }

    // ------------------------------------------------------------------ Pledges: records, lists, Option, std, time

    final StateHarness<Pledges.State> pl = StateHarness.of(PledgesClient.SPEC);

    static JulcList<Pledges.Share> shares() {
        return JulcList.of(new Pledges.Share(ANN, n(6_000)), new Pledges.Share(BEN, n(4_000)));
    }

    static byte[] tag(byte[] label) {
        return Arrays.copyOf(Blake2bUtil.blake2bHash224(label), 8);
    }

    Pledges.State state(BigInteger deadline, BigInteger raised) {
        return new Pledges.State(OWNER, ada(10), deadline, raised, shares(), tag(new byte[]{1, 2, 3}), Optional.empty());
    }

    @Test
    void pledges_init_checksInputs_andHashes() {
        Ctx c0 = pl.ctx(OWNER, BigInteger.ZERO, BigInteger.ZERO);
        byte[] label = {1, 2, 3};
        var s = Pledges.init(ada(10), T0, shares(), label, c0);
        assertArrayEquals(tag(label), s.tag(), "slice(blake2b_224(label), 0, 8), computed independently");
        assertEquals(Optional.empty(), s.lastBacker());
        var create = pl.create(new PledgesValidator.CreateInstance(pl.seed(), ada(10), T0, shares(), label, c0), s, c0);
        System.out.println("[pledges init] " + pl.assertAccepted(create) + "  attacks rejected: " + Attacks.assertAllRejected(pl, create));

        // Shares must total 100%, each positive; every nested key is 28 bytes (checked input, D5).
        var bad = JulcList.of(new Pledges.Share(ANN, n(6_000)), new Pledges.Share(BEN, n(3_999)));
        assertThrows(RequireException.class, () -> Pledges.init(ada(10), T0, bad, label, c0));
        var zero = JulcList.of(new Pledges.Share(ANN, n(10_000)), new Pledges.Share(BEN, BigInteger.ZERO));
        assertThrows(RequireException.class, () -> Pledges.init(ada(10), T0, zero, label, c0));
        var shortKey = JulcList.of(new Pledges.Share(new byte[27], n(10_000)));
        assertThrows(RequireException.class, () -> Pledges.init(ada(10), T0, shortKey, label, c0), "a nested 27-byte key");
        pl.assertRejected(pl.create(new PledgesValidator.CreateInstance(pl.seed(), ada(10), T0, shortKey, label, c0),
                new Pledges.State(OWNER, ada(10), T0, BigInteger.ZERO, shortKey, tag(label), Optional.empty()), c0), "a nested 27-byte key");
    }

    @Test
    void pledges_pledge_beforeTheDeadline_storesAnOption() {
        BigInteger deadline = T0.add(StateHarness.WINDOW_MS).add(n(1));
        var s = state(deadline, BigInteger.ZERO);
        Ctx c = pl.ctx(ANN, ada(3), BigInteger.ZERO);
        var next = Pledges.pledge(s, c);
        assertEquals(ada(3), next.raised());
        assertArrayEquals(ANN, next.lastBacker().orElseThrow());
        var live = pl.live(s, BigInteger.ZERO);
        var tx = pl.act(live, new PledgesValidator.Pledge(c), next, ANN, c);
        System.out.println("[pledges pledge] " + pl.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(pl, tx));
        pl.assertRejected(pl.act(live, new PledgesValidator.Pledge(c),
                new Pledges.State(OWNER, ada(10), deadline, ada(3), shares(), s.tag(), Optional.of(BEN)), ANN, c), "another backer recorded");
        pl.assertRejected(pl.act(live, new PledgesValidator.Pledge(c),
                new Pledges.State(OWNER, ada(10), deadline, ada(3), shares(), s.tag(), Optional.empty()), ANN, c), "no backer recorded");

        // Window [T0, T0 + 2 min) is not wholly before a deadline at T0 + 1 min: refused on both sides (before(t), D11).
        var late = state(T0.add(n(60_000)), BigInteger.ZERO);
        assertThrows(RequireException.class, () -> Pledges.pledge(late, c));
        pl.assertRejected(pl.act(pl.live(late, BigInteger.ZERO), new PledgesValidator.Pledge(c),
                new Pledges.State(OWNER, ada(10), late.deadline(), ada(3), shares(), late.tag(), Optional.of(ANN)), ANN, c), "after the deadline");
        assertThrows(RequireException.class, () -> Pledges.pledge(s, pl.ctx(ANN, n(999_999), BigInteger.ZERO)));
    }

    @Test
    void pledges_views() {
        var s = state(T0, n(12_345));
        assertEquals(n(6_000), Pledges.shareOf(s, ANN.clone()));
        assertEquals(BigInteger.ZERO, Pledges.shareOf(s, OWNER));
        assertTrue(Pledges.isShareholder(s, BEN.clone()), "byte equality is by content, not reference");
        assertFalse(Pledges.isShareholder(s, OWNER));
        assertEquals(n(5), Pledges.digitsRaised(s), "digits(12345): direct self-recursion");
    }

    @Test
    void pledges_reassign_comparesRecordsByValue_onBothSides() {
        var s = state(T0, BigInteger.ZERO);
        // A distinct allocation of an equal share: Java record equality would say false (ADR-0018 F16).
        var from = new Pledges.Share(ANN.clone(), n(6_000));
        byte[] cat = StateHarness.fill(28, 0xC1);
        var next = Pledges.reassign(s, from, cat);
        var expected = JulcList.of(new Pledges.Share(cat, n(6_000)), new Pledges.Share(BEN, n(4_000)));
        assertEquals(2, next.shares().size());
        assertArrayEquals(cat, next.shares().get(0).to());
        assertEquals(n(6_000), next.shares().get(0).bps());
        assertArrayEquals(BEN, next.shares().get(1).to());
        assertThrows(RequireException.class, () -> Pledges.reassign(s, new Pledges.Share(ANN, n(5_999)), cat));

        var live = pl.live(s, BigInteger.ZERO);
        var tx = pl.act(live, new PledgesValidator.Reassign(from, cat),
                new Pledges.State(OWNER, ada(10), T0, BigInteger.ZERO, expected, s.tag(), Optional.empty()), OWNER, null);
        System.out.println("[pledges reassign] " + pl.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(pl, tx));
        pl.assertRejected(pl.act(live, new PledgesValidator.Reassign(from, cat), s, OWNER, null), "shares unchanged");
        pl.assertRejected(pl.act(live, new PledgesValidator.Reassign(from, new byte[27]),
                new Pledges.State(OWNER, ada(10), T0, BigInteger.ZERO, JulcList.of(new Pledges.Share(new byte[27], n(6_000)),
                        new Pledges.Share(BEN, n(4_000))), s.tag(), Optional.empty()), OWNER, null), "a 27-byte key argument");
    }

    @Test
    void pledges_setBacker_takesAnOptionInTheRedeemer() {
        var s = state(T0, BigInteger.ZERO);
        assertArrayEquals(BEN, Pledges.setBacker(s, Optional.of(BEN)).lastBacker().orElseThrow());
        assertEquals(Optional.empty(), Pledges.setBacker(s, Optional.empty()).lastBacker());
        assertThrows(RequireException.class, () -> Pledges.setBacker(s, Optional.of(new byte[27])), "a present key must be 28 bytes");
        var live = pl.live(s, BigInteger.ZERO);
        for (Optional<byte[]> who : java.util.List.of(Optional.of(BEN), Optional.<byte[]>empty())) {
            // The expected next state is written here, not taken from the lowering (§9, review F12).
            var next = new Pledges.State(OWNER, ada(10), T0, BigInteger.ZERO, shares(), s.tag(), who);
            var tx = pl.act(live, new PledgesValidator.SetBacker(who), next, OWNER, null);
            System.out.println("[pledges setBacker " + (who.isPresent() ? "Some" : "None") + "] " + pl.assertAccepted(tx)
                    + "  attacks rejected: " + Attacks.assertAllRejected(pl, tx));
        }
        pl.assertRejected(pl.act(live, new PledgesValidator.SetBacker(Optional.of(BEN)),
                new Pledges.State(OWNER, ada(10), T0, BigInteger.ZERO, shares(), s.tag(), Optional.empty()), OWNER, null),
                "Some(BEN) asked, None stored");
        pl.assertRejected(pl.act(live, new PledgesValidator.SetBacker(Optional.of(new byte[27])),
                new Pledges.State(OWNER, ada(10), T0, BigInteger.ZERO, shares(), s.tag(), Optional.of(new byte[27])), OWNER, null),
                "a 27-byte key in the Option");
    }

    @Test
    void pledges_distribute_paysSharesInSourceOrder_andTheRemainder() {
        // After the deadline (T0 - 1), goal reached: 60% / 40% of the balance, the rounding remainder to the owner.
        var s = state(T0.subtract(BigInteger.ONE), ada(20));
        BigInteger balance = n(25_000_003);
        Ctx c = pl.ctx(OWNER, BigInteger.ZERO, balance);
        JulcList<Payout> out = Pledges.distribute(s, c);
        assertEquals(3, out.size());
        assertArrayEquals(ANN, out.get(0).to());
        assertEquals(n(15_000_001), out.get(0).lovelace());
        assertArrayEquals(BEN, out.get(1).to());
        assertEquals(n(10_000_001), out.get(1).lovelace());
        assertArrayEquals(OWNER, out.get(2).to());
        assertEquals(n(1), out.get(2).lovelace());

        var live = pl.live(s, balance.subtract(BigInteger.valueOf(pl.minAda())));
        Ctx dc = pl.ctx(OWNER, BigInteger.ZERO, live.lovelace());
        // The payouts the validator must accept, by hand (§9, review F12): 60% and 40% of 25,000,003, rounded down, and
        // the remainder to the owner.
        assertEquals(balance, live.lovelace());
        JulcList<Payout> stated = JulcList.of(new Payout(ANN, n(15_000_001)), new Payout(BEN, n(10_000_001)), new Payout(OWNER, n(1)));
        var tx = pl.destroy(live, new PledgesValidator.Distribute(dc), new PledgesValidator.BurnInstance(), stated, OWNER, dc);
        System.out.println("[pledges distribute] " + pl.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(pl, tx));
        pl.assertRejected(pl.destroy(live, new PledgesValidator.Distribute(dc), new PledgesValidator.BurnInstance(),
                Payouts.of(OWNER, live.lovelace()), OWNER, dc), "everything to the owner although the goal was reached");

        // Goal missed: everything to the owner.
        var missed = state(T0.subtract(BigInteger.ONE), ada(5));
        JulcList<Payout> back = Pledges.distribute(missed, c);
        assertEquals(1, back.size());
        assertEquals(balance, back.get(0).lovelace());

        // Before the deadline with pledges: too early.
        assertThrows(RequireException.class, () -> Pledges.distribute(state(T0.add(n(1)), ada(20)), c));
    }
}
