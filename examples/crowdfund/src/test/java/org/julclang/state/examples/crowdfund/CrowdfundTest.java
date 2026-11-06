package org.julclang.state.examples.crowdfund;

import org.julclang.core.types.JulcMap;
import org.julclang.state.api.Ctx;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrowdfundTest {
    static final byte[] CREATOR = StateHarness.fill(28, 0xC1);
    static final byte[] ANN = StateHarness.fill(28, 0xF1);   // sorts after BEN: insertion order != canonical order
    static final byte[] BEN = StateHarness.fill(28, 0x21);
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final BigInteger GOAL = ADA.multiply(BigInteger.valueOf(100));
    static final BigInteger RUNNING = StateHarness.T0.add(BigInteger.valueOf(3_600_000));
    static final BigInteger ENDED = StateHarness.T0.subtract(BigInteger.ONE);

    final StateHarness<Crowdfund.State> h = StateHarness.of(CrowdfundClient.SPEC);

    Crowdfund.State campaign(BigInteger deadline) {
        JulcMap<byte[], BigInteger> none = JulcMap.empty();
        return new Crowdfund.State(CREATOR, GOAL, deadline, ADA.multiply(BigInteger.TWO), BigInteger.ZERO, none);
    }

    Crowdfund.State contribute(Crowdfund.State s, byte[] who, BigInteger amount, BigInteger balance) {
        return Crowdfund.contribute(s, h.ctx(who, amount, balance));
    }

    @Test
    void launch() {
        Ctx ctx = h.ctx(CREATOR, BigInteger.ZERO, BigInteger.ZERO);
        var tx = h.create(new CrowdfundValidator.Create(h.seed(), GOAL, RUNNING, ADA.multiply(BigInteger.TWO), ctx),
                Crowdfund.launch(GOAL, RUNNING, ADA.multiply(BigInteger.TWO), ctx), ctx);
        System.out.println("[launch] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void contributions_keepTheMapCanonical() {
        var s1 = contribute(campaign(RUNNING), ANN, ADA.multiply(BigInteger.TEN), BigInteger.ZERO);
        var live = h.live(s1, ADA.multiply(BigInteger.TEN));
        Ctx ctx = h.ctx(BEN, ADA.multiply(BigInteger.valueOf(5)), ADA.multiply(BigInteger.TEN));
        var s2 = Crowdfund.contribute(live.state(), ctx);
        var tx = h.act(live, new CrowdfundValidator.Contribute(ctx), s2, BEN, ctx);
        System.out.println("[contribute] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));

        // A repeat contribution updates the existing entry in place.
        var live2 = h.live(s2, ADA.multiply(BigInteger.valueOf(15)));
        Ctx again = h.ctx(ANN, ADA.multiply(BigInteger.valueOf(3)), ADA.multiply(BigInteger.valueOf(15)));
        var s3 = Crowdfund.contribute(live2.state(), again);
        h.assertAccepted(h.act(live2, new CrowdfundValidator.Contribute(again), s3, ANN, again));
        assertEquals(ADA.multiply(BigInteger.valueOf(13)), Crowdfund.contributionOf(s3, ANN));
    }

    @Test
    void refund_paysTheCaller() {
        var running = contribute(contribute(campaign(RUNNING), ANN, ADA.multiply(BigInteger.TEN), BigInteger.ZERO),
                BEN, ADA.multiply(BigInteger.valueOf(5)), ADA.multiply(BigInteger.TEN));
        var s = new Crowdfund.State(running.creator(), running.goal(), ENDED, running.minContribution(),
                running.raised(), running.backers());   // the deadline has since passed
        var live = h.live(s, ADA.multiply(BigInteger.valueOf(15)));
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, ADA.multiply(BigInteger.valueOf(15)));
        var r = Crowdfund.refund(live.state(), ctx);
        var tx = h.act(live, new CrowdfundValidator.Refund(ctx), r.state(), r.payouts(), ANN, ctx);
        System.out.println("[refund] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void claim_whenTheGoalIsMet() {
        var s = new Crowdfund.State(CREATOR, GOAL, ENDED, ADA.multiply(BigInteger.TWO), GOAL,
                JulcMap.<byte[], BigInteger>empty().insert(ANN, GOAL));
        var live = h.live(s, GOAL);
        Ctx ctx = h.ctx(CREATOR, BigInteger.ZERO, live.lovelace());
        var tx = h.destroy(live, new CrowdfundValidator.Claim(ctx), new CrowdfundValidator.Burn(),
                Crowdfund.claim(live.state(), ctx), CREATOR, ctx);
        System.out.println("[claim] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void capacity_shrinkingPathsStillWorkWhenFull() {
        // Fill the campaign until the next contribution no longer fits maxDatumBytes.
        var s = campaign(ENDED);
        BigInteger funds = BigInteger.ZERO;
        int n = 0;
        while (true) {
            var who = StateHarness.fill(28, 0x30 + n);
            var candidate = new Crowdfund.State(s.creator(), s.goal(), s.deadline(), s.minContribution(),
                    s.raised().add(ADA.multiply(BigInteger.TWO)),
                    org.julclang.state.api.SortedMaps.put(s.backers(), who, ADA.multiply(BigInteger.TWO)));
            if (sizeOf(candidate) > CrowdfundClient.SPEC.maxDatumBytes()) break;
            s = candidate;
            funds = funds.add(ADA.multiply(BigInteger.TWO));
            n++;
        }
        System.out.println("[capacity] " + n + " backers fit in " + CrowdfundClient.SPEC.maxDatumBytes() + " bytes");
        var live = h.live(s, funds);
        var who = StateHarness.fill(28, 0x30);
        Ctx ctx = h.ctx(who, BigInteger.ZERO, funds);
        var r = Crowdfund.refund(live.state(), ctx);
        System.out.println("[refund at capacity] " + h.assertAccepted(
                h.act(live, new CrowdfundValidator.Refund(ctx), r.state(), r.payouts(), who, ctx)));
    }

    static int sizeOf(Object state) {
        try {
            return org.julclang.clientlib.PlutusDataAdapter.convert(state).serializeToBytes().length;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
