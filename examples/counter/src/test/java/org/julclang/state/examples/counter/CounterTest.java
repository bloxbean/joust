package org.julclang.state.examples.counter;

import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payouts;
import org.julclang.state.api.RequireException;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The same Counter source, tested two ways:
 * 1. plain JVM unit tests of the developer's methods (no chain, no compiler);
 * 2. the generated CounterValidator, as compiled by the build, evaluated against runtime-shaped transactions, plus the
 *    attack suite on each of them.
 */
class CounterTest {

    static final byte[] ALICE = StateHarness.fill(28, 0x11);
    static final byte[] BOB = StateHarness.fill(28, 0x22);

    final StateHarness<Counter.State> h = StateHarness.of(CounterClient.SPEC);

    @Nested
    class PlainJava {
        @Test
        void incrementResetClose() {
            var s0 = Counter.init(h.ctx(ALICE, BigInteger.ZERO, BigInteger.ZERO));
            var s1 = Counter.increment(s0, BigInteger.valueOf(5));
            var s2 = Counter.increment(s1, BigInteger.TWO);
            assertEquals(BigInteger.valueOf(7), Counter.count(s2));
            assertEquals(BigInteger.ZERO, Counter.reset(s2).count());
        }

        @Test
        void rulesFailOnTheJvmWithTheirMessage() {
            var s = Counter.init(h.ctx(ALICE, BigInteger.ZERO, BigInteger.ZERO));
            var e = assertThrows(RequireException.class, () -> Counter.increment(s, BigInteger.ZERO));
            assertEquals("increment must be positive", e.getMessage());
        }
    }

    @Nested
    class OnChain {

        Ctx aliceCtx(BigInteger balance) {
            return h.ctx(ALICE, BigInteger.ZERO, balance);
        }

        @Test
        void create() {
            Ctx ctx = aliceCtx(BigInteger.ZERO);
            var tx = h.create(new CounterValidator.CreateInstance(h.seed(), ctx), Counter.init(ctx), ctx);
            System.out.println("[create] " + h.assertAccepted(tx));
            System.out.println("  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        }

        @Test
        void create_wrongInitialState_rejected() {
            Ctx ctx = aliceCtx(BigInteger.ZERO);
            var bogus = new Counter.State(BigInteger.valueOf(1000), ALICE);
            h.assertRejected(h.create(new CounterValidator.CreateInstance(h.seed(), ctx), bogus, ctx), "initial state != init()");
        }

        @Test
        void create_ownerIsSomeoneElse_rejected() {
            Ctx ctx = aliceCtx(BigInteger.ZERO);
            var stolen = new Counter.State(BigInteger.ZERO, BOB);
            h.assertRejected(h.create(new CounterValidator.CreateInstance(h.seed(), ctx), stolen, ctx), "owner != caller");
        }

        @Test
        void increment_byAnyone() {
            var live = h.live(new Counter.State(BigInteger.valueOf(3), ALICE), BigInteger.ZERO);
            var next = Counter.increment(live.state(), BigInteger.TEN);          // the client computes this on the JVM
            var tx = h.act(live, new CounterValidator.Increment(BigInteger.TEN), next, null, null);
            System.out.println("[increment] " + h.assertAccepted(tx));
            System.out.println("  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        }

        @Test
        void increment_wrongResult_rejected() {
            var live = h.live(new Counter.State(BigInteger.valueOf(3), ALICE), BigInteger.ZERO);
            var cheat = new Counter.State(BigInteger.valueOf(999), ALICE);
            h.assertRejected(h.act(live, new CounterValidator.Increment(BigInteger.TEN), cheat, null, null), "wrong next state");
        }

        @Test
        void increment_nonPositive_rejectedOnChainToo() {
            var live = h.live(new Counter.State(BigInteger.valueOf(3), ALICE), BigInteger.ZERO);
            h.assertRejected(h.act(live, new CounterValidator.Increment(BigInteger.ZERO), live.state(), null, null),
                    "Require.that(by > 0)");
        }

        @Test
        void increment_hugeNumberCannotOutgrowTheDatum() {
            var live = h.live(new Counter.State(BigInteger.valueOf(3), ALICE), BigInteger.ZERO);
            var by = BigInteger.TEN.pow(400);    // ~170 bytes, over maxDatumBytes = 128
            var next = Counter.increment(live.state(), by);
            h.assertRejected(h.act(live, new CounterValidator.Increment(by), next, null, null), "datum over maxDatumBytes");
        }

        @Test
        void reset_byOwner() {
            var live = h.live(new Counter.State(BigInteger.valueOf(42), ALICE), BigInteger.ZERO);
            var tx = h.act(live, new CounterValidator.Reset(), Counter.reset(live.state()), ALICE, null);
            System.out.println("[reset] " + h.assertAccepted(tx));
            System.out.println("  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        }

        @Test
        void reset_byNonOwner_rejected() {
            var live = h.live(new Counter.State(BigInteger.valueOf(42), ALICE), BigInteger.ZERO);
            h.assertRejected(h.act(live, new CounterValidator.Reset(), Counter.reset(live.state()), BOB, null),
                    "@OnlyOwner");
        }

        @Test
        void close_paysEverythingBackToTheOwner() {
            var live = h.live(new Counter.State(BigInteger.valueOf(42), ALICE), BigInteger.ZERO);
            Ctx ctx = aliceCtx(live.lovelace());          // in @Destroy, balance is everything, deposit included
            var payouts = Counter.close(live.state(), ctx);
            var tx = h.destroy(live, new CounterValidator.Close(ctx), new CounterValidator.BurnInstance(), payouts, ALICE, ctx);
            System.out.println("[close] " + h.assertAccepted(tx));
            System.out.println("  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        }

        @Test
        void close_keepingPartOfTheDeposit_rejected() {
            var live = h.live(new Counter.State(BigInteger.valueOf(42), ALICE), BigInteger.ZERO);
            Ctx ctx = aliceCtx(live.lovelace());
            var short1 = Payouts.of(ALICE, live.lovelace().subtract(BigInteger.ONE));
            h.assertRejected(h.destroy(live, new CounterValidator.Close(ctx), new CounterValidator.BurnInstance(), short1, ALICE, ctx),
                    "destroy must allocate everything");
        }

        @Test
        void close_doubleSatisfaction_rejected() {
            // Two counters of Alice closed in one tx, with only one instance's payout output present.
            var a = h.live(new Counter.State(BigInteger.ONE, ALICE), BigInteger.ZERO);
            var b = new StateHarness.Live<>(new org.julclang.ledger.TxOutRef(
                    new org.julclang.ledger.TxId(StateHarness.fill(32, 0x7B)), BigInteger.ZERO),
                    new Counter.State(BigInteger.TWO, ALICE), a.lovelace());
            Ctx ctx = aliceCtx(a.lovelace());
            var txA = h.destroy(a, new CounterValidator.Close(ctx), new CounterValidator.BurnInstance(),
                    Counter.close(a.state(), ctx), ALICE, ctx);
            var txB = h.destroy(b, new CounterValidator.Close(ctx), new CounterValidator.BurnInstance(),
                    Counter.close(b.state(), ctx), ALICE, ctx);
            h.assertRejected(Attacks.sharedPayouts(txA, txB), "one payout output satisfying two instances");
        }
    }
}
