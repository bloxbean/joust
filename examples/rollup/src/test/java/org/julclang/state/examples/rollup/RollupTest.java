package org.julclang.state.examples.rollup;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.RequireException;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The optimistic-rollup spike (ADR-0006 r2): the L2 program on the JVM, and commit / finalize / fraud proofs on-chain. */
class RollupTest {
    static final byte[] OPERATOR = StateHarness.fill(28, 0x0B);
    static final byte[] WATCHER = StateHarness.fill(28, 0x3A);
    static final byte[] BURN = new byte[28];
    static final byte[] DOMAIN = StateHarness.fill(32, 0xD0);
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final BigInteger BOND = ADA.multiply(BigInteger.valueOf(100));
    static final BigInteger WINDOW = BigInteger.valueOf(600_000);

    final StateHarness<Rollup.State> h = StateHarness.of(RollupClient.SPEC);
    static Sequencer.Signer ann, ben, cat;

    @BeforeAll
    static void setup() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        ann = Sequencer.Signer.generate();
        ben = Sequencer.Signer.generate();
        cat = Sequencer.Signer.generate();
    }

    static Ledger.Book genesis() {
        JulcList<Ledger.Acct> accts = JulcList.empty();
        accts = accts.prepend(new Ledger.Acct(ben.publicKey(), BigInteger.valueOf(50), BigInteger.ZERO));
        accts = accts.prepend(new Ledger.Acct(ann.publicKey(), BigInteger.valueOf(100), BigInteger.ZERO));
        return new Ledger.Book(DOMAIN, accts);
    }

    static Ledger.Order order(Sequencer.Signer from, Sequencer.Signer to, long amount, Ledger.Book at) {
        return new Ledger.Order(from.publicKey(), to.publicKey(), BigInteger.valueOf(amount), Ledger.nonceOf(at, from.publicKey()));
    }

    static byte[] sign(Sequencer.Signer s, Ledger.Order o) {
        return s.sign(DOMAIN, o);
    }

    /** Four orders: three valid, one signed by the wrong key (an honest sequencer records it as a no-op). */
    static Sequencer honestRun() {
        var seq = new Sequencer(genesis());
        var o1 = order(ann, ben, 30, seq.book());
        seq.submit(o1, sign(ann, o1));
        var o2 = order(ben, cat, 70, seq.book());
        seq.submit(o2, sign(ben, o2));
        var bad = order(cat, ann, 70, seq.book());
        seq.submit(bad, sign(ann, bad));
        var o3 = order(cat, ann, 20, seq.book());
        seq.submit(o3, sign(cat, o3));
        return seq;
    }

    // ------------------------------------------------------------------ the L2 program on the JVM

    @Test
    void ledger_semantics() {
        var seq = honestRun();
        var b = seq.book();
        assertEquals(BigInteger.valueOf(90), Ledger.balanceOf(b, ann.publicKey()));
        assertEquals(BigInteger.valueOf(10), Ledger.balanceOf(b, ben.publicKey()));
        assertEquals(BigInteger.valueOf(50), Ledger.balanceOf(b, cat.publicKey()));
        assertEquals(BigInteger.ONE, Ledger.nonceOf(b, cat.publicKey()), "the bad-signature order used no nonce");
        // Replay of an included order: stale nonce, no change.
        var o1 = new Ledger.Order(ann.publicKey(), ben.publicKey(), BigInteger.valueOf(30), BigInteger.ZERO);
        assertSame(b, Ledger.step(b, o1, sign(ann, o1)));
        // Another rollup's domain: the signature doesn't verify here.
        var fresh = order(ann, ben, 1, b);
        byte[] otherDomain = ann.sign(StateHarness.fill(32, 0xEE), fresh);
        assertSame(b, Ledger.step(b, fresh, otherDomain));
        // from == to: allowed, no money created, nonce used.
        var self = order(ann, ann, 90, b);
        var after = Ledger.step(b, self, sign(ann, self));
        assertEquals(BigInteger.valueOf(90), Ledger.balanceOf(after, ann.publicKey()));
        assertEquals(BigInteger.TWO, Ledger.nonceOf(after, ann.publicKey()));
        // Total: malformed inputs never throw, and a malformed log can still be hashed.
        var junk = new Ledger.Order(new byte[3], new byte[0], BigInteger.valueOf(-5), BigInteger.valueOf(-1));
        assertSame(b, Ledger.step(b, junk, new byte[1]));
        var hugeAmount = new Ledger.Order(ann.publicKey(), ben.publicKey(), BigInteger.TWO.pow(300), BigInteger.ZERO);
        assertSame(b, Ledger.step(b, hugeAmount, new byte[64]));
        assertNotNull(Ledger.logHash(JulcList.<Ledger.Step>empty().prepend(new Ledger.Step(junk, new byte[0], new byte[0]))));
        assertTrue(Sequencer.audit(genesis(), seq.checkpoint().steps()).isEmpty(), "an honest checkpoint has no fraud");
    }

    @Test
    void ledger_boundsTheNumberOfAccounts() {
        var b = genesis();
        for (int i = 0; i < 40; i++) {
            var to = Sequencer.Signer.generate();
            var o = new Ledger.Order(ann.publicKey(), to.publicKey(), BigInteger.ONE, Ledger.nonceOf(b, ann.publicKey()));
            b = Ledger.step(b, o, sign(ann, o));
        }
        assertEquals(32L, b.accounts().size(), "new accounts stop at MAX_ACCOUNTS");
    }

    @Test
    void encodingsAreInjective() {
        // Same bytes, different field boundaries: different hashes.
        var a = new Ledger.Step(new Ledger.Order(new byte[]{1}, new byte[]{2, 3}, BigInteger.ONE, BigInteger.ZERO), new byte[0], new byte[0]);
        var b = new Ledger.Step(new Ledger.Order(new byte[]{1, 2}, new byte[]{3}, BigInteger.ONE, BigInteger.ZERO), new byte[0], new byte[0]);
        assertFalse(java.util.Arrays.equals(Ledger.logHash(JulcList.<Ledger.Step>empty().prepend(a)),
                Ledger.logHash(JulcList.<Ledger.Step>empty().prepend(b))));
        assertFalse(java.util.Arrays.equals(Ledger.intEnc(BigInteger.valueOf(5)), Ledger.intEnc(BigInteger.valueOf(-5))));
    }

    // ------------------------------------------------------------------ L1

    Rollup.State opened() {
        return new Rollup.State(OPERATOR, WINDOW, BOND, Ledger.hash(genesis()), BigInteger.ZERO, new byte[0], BigInteger.ZERO,
                new byte[0], BigInteger.ZERO);
    }

    Rollup.State pending(Sequencer.Checkpoint cp, BigInteger until) {
        return new Rollup.State(OPERATOR, WINDOW, BOND, Ledger.hash(genesis()), BigInteger.ZERO, cp.log(), cp.count(),
                cp.newHash(), until);
    }

    @Test
    void initAndCommit() {
        Ctx c0 = h.ctx(OPERATOR, BOND, BigInteger.ZERO);
        var init = h.create(new RollupValidator.CreateInstance(h.seed(), Ledger.hash(genesis()), WINDOW, c0),
                Rollup.init(Ledger.hash(genesis()), WINDOW, c0), c0);
        System.out.println("[rollup init] " + h.assertAccepted(init) + "  attacks rejected: " + Attacks.assertAllRejected(h, init));

        var cp = honestRun().checkpoint();
        var live = h.live(opened(), BOND);
        Ctx c = h.ctx(OPERATOR, BigInteger.ZERO, BOND);
        var next = Rollup.commit(opened(), cp.steps(), c);
        assertEquals(c.validTo().add(WINDOW), next.challengeUntil());
        assertArrayEquals(cp.log(), next.pendingLog(), "the log hash is derived from the committed steps");
        assertEquals(BigInteger.valueOf(4), next.pendingCount());
        var tx = h.act(live, new RollupValidator.Commit(cp.steps(), c), next, OPERATOR, c);
        System.out.println("[rollup commit x4] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        // A commit whose datum claims a different count or new state than its steps: rejected.
        var lie = new Rollup.State(OPERATOR, WINDOW, BOND, next.finalHash(), BigInteger.ZERO, next.pendingLog(), BigInteger.ONE,
                next.pendingHash(), next.challengeUntil());
        h.assertRejected(h.act(live, new RollupValidator.Commit(cp.steps(), c), lie, OPERATOR, c), "count not derived from steps");
        Ctx w = h.ctx(WATCHER, BigInteger.ZERO, BOND);
        h.assertRejected(h.act(live, new RollupValidator.Commit(cp.steps(), w), next, WATCHER, w), "only the operator commits");
        // No rebond while a checkpoint is pending (it would crowd out challenges).
        var pend = pending(cp, StateHarness.T0.add(WINDOW));
        assertThrows(RequireException.class, () -> Rollup.rebond(pend, h.ctx(OPERATOR, ADA, BOND)));
    }

    @Test
    void finalize_andChallenge_haveDisjointWindows() {
        var cp = honestRun().checkpoint();
        Ctx c = h.ctx(WATCHER, BigInteger.ZERO, BOND);           // [T0, T0 + 120 s)
        // challengeUntil == T0: finalize allowed (whole window after), challenge not.
        var atStart = pending(cp, StateHarness.T0);
        var next = Rollup.finalize(atStart, c);
        assertArrayEquals(cp.newHash(), next.finalHash());
        var tx = h.act(h.live(atStart, BOND), new RollupValidator.Finalize(c), next, WATCHER, c);
        System.out.println("[rollup finalize] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        // One ms later: neither (the window straddles challengeUntil).
        var straddle = pending(cp, StateHarness.T0.add(BigInteger.ONE));
        assertThrows(RequireException.class, () -> Rollup.finalize(straddle, c));
        h.assertRejected(h.act(h.live(straddle, BOND), new RollupValidator.Finalize(c), next, WATCHER, c), "window still open");
    }

    /** A sequencer that mints 1000 for cat in step 1 and continues from the forged state. */
    static Sequencer forgedRun() {
        var seq = new Sequencer(genesis());
        var o1 = order(ann, ben, 30, seq.book());
        seq.submit(o1, sign(ann, o1));
        var o2 = order(ben, ann, 10, seq.book());
        var honest = Ledger.step(seq.book(), o2, sign(ben, o2));
        var forged = new Ledger.Book(DOMAIN, Ledger.update(honest.accounts(), cat.publicKey(), BigInteger.valueOf(1000), BigInteger.ZERO));
        seq.submitForged(o2, sign(ben, o2), forged);
        var o3 = order(cat, ann, 500, seq.book());
        seq.submit(o3, sign(cat, o3));
        return seq;
    }

    @Test
    void challenge_ofAnHonestCheckpoint_failsAtEveryStep() {
        var cp = honestRun().checkpoint();
        var s = pending(cp, StateHarness.T0.add(WINDOW));
        Ledger.Book pre = genesis();
        int i = 0;
        for (Ledger.Step st : cp.steps()) {
            Ctx c = h.ctx(WATCHER, BigInteger.ZERO, BOND);
            final Ledger.Book p = pre;
            final BigInteger k = BigInteger.valueOf(i);
            assertThrows(RequireException.class, () -> Rollup.challenge(s, cp.steps(), k, p, c), "no fraud at step " + i);
            var reverted = Rollup.finalize(pending(cp, StateHarness.T0), c);   // any next state: the validator must reject
            h.assertRejected(h.act(h.live(s, BOND), new RollupValidator.Challenge(cp.steps(), k, p, c), reverted,
                    org.julclang.state.api.Payouts.of(WATCHER, BOND), WATCHER, c), "honest step " + i);
            pre = Ledger.step(pre, st.order(), st.sig());
            i++;
        }
    }

    @Test
    void challenge_ofAForgedStep_slashesHalfToTheChallengerHalfBurned() {
        var cp = forgedRun().checkpoint();
        var proof = Sequencer.audit(genesis(), cp.steps()).orElseThrow();
        assertEquals(BigInteger.ONE, proof.index(), "the watcher finds step 1");
        var s = pending(cp, StateHarness.T0.add(WINDOW));
        Ctx c = h.ctx(WATCHER, BigInteger.ZERO, BOND);
        var slash = Rollup.challenge(s, cp.steps(), proof.index(), proof.pre(), c);
        assertEquals(BigInteger.ZERO, slash.state().pendingCount(), "the checkpoint is discarded");
        assertArrayEquals(Ledger.hash(genesis()), slash.state().finalHash());
        for (var p : slash.payouts()) {
            assertEquals(BOND.divide(BigInteger.TWO), p.lovelace());
            assertTrue(java.util.Arrays.equals(BURN, p.to()) || java.util.Arrays.equals(WATCHER, p.to()));
        }
        assertEquals(2L, slash.payouts().size());
        var tx = h.act(h.live(s, BOND), new RollupValidator.Challenge(cp.steps(), proof.index(), proof.pre(), c),
                slash.state(), slash.payouts(), WATCHER, c);
        System.out.println("[rollup challenge] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));

        BigInteger two = BigInteger.TWO;
        h.assertRejected(h.act(h.live(s, BOND), new RollupValidator.Challenge(cp.steps(), two, proof.pre(), c),
                slash.state(), slash.payouts(), WATCHER, c), "pre-state of another step");
        h.assertRejected(h.act(h.live(s, BOND), new RollupValidator.Challenge(cp.steps(), proof.index(), genesis(), c),
                slash.state(), slash.payouts(), WATCHER, c), "wrong pre-state");
        h.assertRejected(h.act(h.live(s, BOND), new RollupValidator.Challenge(cp.steps().tail(), proof.index(), proof.pre(), c),
                slash.state(), slash.payouts(), WATCHER, c), "not the committed log");
        h.assertRejected(h.act(h.live(s, BOND), new RollupValidator.Challenge(cp.steps(), BigInteger.valueOf(3), proof.pre(), c),
                slash.state(), slash.payouts(), WATCHER, c), "index out of range");
        var closed = pending(cp, StateHarness.T0.add(WINDOW.negate()));
        h.assertRejected(h.act(h.live(closed, BOND), new RollupValidator.Challenge(cp.steps(), proof.index(), proof.pre(), c),
                slash.state(), slash.payouts(), WATCHER, c), "after the window");
        // Whole bond to the challenger: rejected (half must be burned).
        h.assertRejected(h.act(h.live(s, BOND), new RollupValidator.Challenge(cp.steps(), proof.index(), proof.pre(), c),
                slash.state(), org.julclang.state.api.Payouts.of(WATCHER, BOND), WATCHER, c), "no burn");
    }

    @Test
    void challenge_ofAnInvalidOrderAppliedAsValid_slashes() {
        // The operator applies a transfer signed by the wrong key (it should have been a no-op).
        var seq = new Sequencer(genesis());
        var bad = order(ann, cat, 100, seq.book());
        var applied = Ledger.step(seq.book(), bad, sign(ann, bad));      // what a valid signature would do
        seq.submitForged(bad, sign(ben, bad), applied);
        var cp = seq.checkpoint();
        var proof = Sequencer.audit(genesis(), cp.steps()).orElseThrow();
        assertEquals(BigInteger.ZERO, proof.index());
        var s = pending(cp, StateHarness.T0.add(WINDOW));
        Ctx c = h.ctx(WATCHER, BigInteger.ZERO, BOND);
        var slash = Rollup.challenge(s, cp.steps(), proof.index(), proof.pre(), c);
        h.assertAccepted(h.act(h.live(s, BOND), new RollupValidator.Challenge(cp.steps(), proof.index(), proof.pre(), c),
                slash.state(), slash.payouts(), WATCHER, c));
    }

    @Test
    void challenge_ofAMalformedOrderAppliedAsValid_slashes() {
        // A step whose order has a negative amount and short keys: the operator claims it changed the state.
        var seq = new Sequencer(genesis());
        var junk = new Ledger.Order(new byte[3], new byte[1], BigInteger.valueOf(-7), BigInteger.valueOf(-1));
        var claimed = new Ledger.Book(DOMAIN, Ledger.update(genesis().accounts(), cat.publicKey(), BigInteger.TEN, BigInteger.ZERO));
        seq.submitForged(junk, new byte[5], claimed);
        var cp = seq.checkpoint();
        var proof = Sequencer.audit(genesis(), cp.steps()).orElseThrow();
        var s = pending(cp, StateHarness.T0.add(WINDOW));
        Ctx c = h.ctx(WATCHER, BigInteger.ZERO, BOND);
        var slash = Rollup.challenge(s, cp.steps(), proof.index(), proof.pre(), c);
        h.assertAccepted(h.act(h.live(s, BOND), new RollupValidator.Challenge(cp.steps(), proof.index(), proof.pre(), c),
                slash.state(), slash.payouts(), WATCHER, c));
    }

    @Test
    void worstCaseChallengeCost() {
        // 32 accounts in the pre-state, 16 steps, fraud in the last step.
        var b = genesis();
        for (int i = 0; i < 30; i++) {
            var to = Sequencer.Signer.generate();
            var o = new Ledger.Order(ann.publicKey(), to.publicKey(), BigInteger.ONE, Ledger.nonceOf(b, ann.publicKey()));
            b = Ledger.step(b, o, sign(ann, o));
        }
        assertEquals(32L, b.accounts().size());
        for (int n : new int[]{1, 8, 16}) {
            var seq = new Sequencer(b);
            for (int i = 0; i < n; i++) {
                var o = order(ben, ann, 1, seq.book());
                if (i == n - 1) {
                    var forged = new Ledger.Book(DOMAIN, Ledger.update(seq.book().accounts(), ann.publicKey(), BigInteger.TEN, BigInteger.ZERO));
                    seq.submitForged(o, sign(ben, o), forged);
                } else {
                    seq.submit(o, sign(ben, o));
                }
            }
            var cp = seq.checkpoint();
            var proof = Sequencer.audit(b, cp.steps()).orElseThrow();
            var s = new Rollup.State(OPERATOR, WINDOW, BOND, Ledger.hash(b), BigInteger.ZERO, cp.log(), cp.count(), cp.newHash(),
                    StateHarness.T0.add(WINDOW));
            Ctx c = h.ctx(WATCHER, BigInteger.ZERO, BOND);
            var slash = Rollup.challenge(s, cp.steps(), proof.index(), proof.pre(), c);
            var v = h.assertAccepted(h.act(h.live(s, BOND), new RollupValidator.Challenge(cp.steps(), proof.index(), proof.pre(), c),
                    slash.state(), slash.payouts(), WATCHER, c));
            var r = v.results().values().iterator().next();
            System.out.printf("[challenge cost] 32 accounts, %d steps (fraud at %s): cpu=%d mem=%d%n", n, proof.index(),
                    r.budgetConsumed().cpuSteps(), r.budgetConsumed().memoryUnits());
            Ctx oc = h.ctx(OPERATOR, BigInteger.ZERO, BOND);
            var commit = h.assertAccepted(h.act(h.live(new Rollup.State(OPERATOR, WINDOW, BOND, Ledger.hash(b), BigInteger.ZERO,
                    new byte[0], BigInteger.ZERO, new byte[0], BigInteger.ZERO), BOND), new RollupValidator.Commit(cp.steps(), oc),
                    Rollup.commit(new Rollup.State(OPERATOR, WINDOW, BOND, Ledger.hash(b), BigInteger.ZERO, new byte[0], BigInteger.ZERO,
                            new byte[0], BigInteger.ZERO), cp.steps(), h.ctx(OPERATOR, BigInteger.ZERO, BOND)), OPERATOR,
                    h.ctx(OPERATOR, BigInteger.ZERO, BOND)));
            var rc = commit.results().values().iterator().next();
            System.out.printf("[commit cost] %d steps: cpu=%d mem=%d%n", n, rc.budgetConsumed().cpuSteps(), rc.budgetConsumed().memoryUnits());
        }
    }
}
