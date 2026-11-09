package org.julclang.state.examples.rollup;

import com.bloxbean.cardano.client.account.Account;
import org.julclang.core.types.JulcList;
import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The optimistic-rollup spike on a live Yaci DevKit (ADR-0006): an honest checkpoint is finalised after its window; a
 * forged one is caught by a watcher re-executing the committed steps, and the operator's bond is slashed.
 */
@Tag("devnet")
class RollupDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] DOMAIN = "joust-rollup-devnet-test-domain!".getBytes();

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void honestCheckpointFinalises_forgedOneIsSlashed() throws Exception {
        var runtime = DevKit.runtime();
        Account operator = DevKit.fundedAccount(300);
        Account watcher = DevKit.fundedAccount(50);
        var ann = Sequencer.Signer.generate();
        var ben = Sequencer.Signer.generate();

        JulcList<Ledger.Acct> accts = JulcList.empty();
        accts = accts.prepend(new Ledger.Acct(ben.publicKey(), BigInteger.valueOf(10), BigInteger.ZERO));
        accts = accts.prepend(new Ledger.Acct(ann.publicKey(), BigInteger.valueOf(100), BigInteger.ZERO));
        Ledger.Book genesis = new Ledger.Book(DOMAIN, accts);
        BigInteger window = BigInteger.valueOf(60_000);
        var rollup = RollupClient.deploy(runtime, operator, Ledger.hash(genesis), window, ADA.multiply(BigInteger.valueOf(40)));

        // An honest checkpoint of three orders.
        var seq = new Sequencer(genesis);
        for (int i = 0; i < 3; i++) {
            var o = new Ledger.Order(ann.publicKey(), ben.publicKey(), BigInteger.valueOf(5), Ledger.nonceOf(seq.book(), ann.publicKey()));
            seq.submit(o, ann.sign(DOMAIN, o));
        }
        var cp1 = seq.checkpoint();
        rollup.commit(operator, cp1.steps());
        assertTrue(Sequencer.audit(genesis, cp1.steps()).isEmpty(), "the watcher finds nothing to dispute");
        assertThrows(RequireException.class, () -> rollup.finalize(watcher), "the window is still open");
        finalizeWhenOpen(rollup, watcher);
        assertArrayEquals(cp1.newHash(), rollup.state().finalHash());
        assertEquals(BigInteger.ONE, rollup.state().height());
        Ledger.Book final1 = Sequencer.replay(genesis, cp1.steps());
        assertEquals(BigInteger.valueOf(25), Ledger.balanceOf(final1, ben.publicKey()));

        // A forged checkpoint: the operator credits itself 1000 in the second step.
        var o1 = new Ledger.Order(ben.publicKey(), ann.publicKey(), BigInteger.valueOf(5), Ledger.nonceOf(seq.book(), ben.publicKey()));
        seq.submit(o1, ben.sign(DOMAIN, o1));
        var o2 = new Ledger.Order(ann.publicKey(), ben.publicKey(), BigInteger.ONE, Ledger.nonceOf(seq.book(), ann.publicKey()));
        var honest = Ledger.step(seq.book(), o2, ann.sign(DOMAIN, o2));
        seq.submitForged(o2, ann.sign(DOMAIN, o2), new Ledger.Book(DOMAIN, Ledger.update(honest.accounts(),
                ann.publicKey(), BigInteger.valueOf(1000), BigInteger.ZERO)));
        var cp2 = seq.checkpoint();
        rollup.commit(operator, cp2.steps());

        // The watcher re-executes the steps it reads from the commit and proves step 1 wrong.
        var proof = Sequencer.audit(final1, cp2.steps()).orElseThrow();
        assertEquals(BigInteger.ONE, proof.index());
        var slashed = rollup.challenge(watcher, cp2.steps(), proof.index(), proof.pre());
        System.out.println("[rollup slash] " + slashed.txHash() + " payouts " + slashed.payouts());
        assertEquals(2L, slashed.payouts().size());
        assertArrayEquals(cp1.newHash(), rollup.state().finalHash(), "reverted to the last final state");
        assertEquals(BigInteger.ZERO, rollup.state().pendingCount());
        boolean paidWatcher = false;
        for (var p : slashed.payouts()) paidWatcher |= java.util.Arrays.equals(p.to(), Keys.pkh(watcher));
        assertTrue(paidWatcher);

        // The operator must rebond before committing again.
        seq.revertTo(final1);
        var o3 = new Ledger.Order(ben.publicKey(), ann.publicKey(), BigInteger.valueOf(5), Ledger.nonceOf(seq.book(), ben.publicKey()));
        seq.submit(o3, ben.sign(DOMAIN, o3));
        var cp3 = seq.checkpoint();
        assertThrows(RequireException.class, () -> rollup.commit(operator, cp3.steps()), "bond below the minimum");
        rollup.rebond(operator, ADA.multiply(BigInteger.valueOf(40)));
        rollup.commit(operator, cp3.steps());
        assertEquals(BigInteger.ONE, rollup.state().pendingCount());
    }

    private static void finalizeWhenOpen(RollupClient rollup, Account who) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            try {
                rollup.finalize(who);
                return;
            } catch (RequireException stillOpen) {
                Thread.sleep(5_000);
            }
        }
        fail("the challenge window never closed");
    }
}
