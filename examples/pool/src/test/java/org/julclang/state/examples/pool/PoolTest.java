package org.julclang.state.examples.pool;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.StakingCredential;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.state.api.Ctx;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Batched intents (ADR-0004): creation, the batch fold and its attacks, cancel. */
class PoolTest {
    static final byte[] ADMIN = StateHarness.fill(28, 0xAD);
    static final byte[] ANN = StateHarness.fill(28, 0xA1);
    static final byte[] BOB = StateHarness.fill(28, 0xB0);
    static final byte[] CAT = StateHarness.fill(28, 0xCA);
    static final byte[] EVE = StateHarness.fill(28, 0xEE);
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final BigInteger DEPOSIT = ADA.multiply(BigInteger.TWO);
    static final BigInteger LATER = StateHarness.T0.add(BigInteger.valueOf(3_600_000));

    final StateHarness<Pool.State> h = StateHarness.of(PoolClient.SPEC);
    final Pool.State pool = new Pool.State(ADMIN, BigInteger.ZERO, BigInteger.ZERO, ADA.multiply(BigInteger.TEN), ADA);

    static BigInteger ada(long n) {
        return ADA.multiply(BigInteger.valueOf(n));
    }

    /** A base address: the owner's payment key plus a stake key (the batcher must not change it). */
    static Address base(byte[] pkh) {
        return new Address(new Credential.PubKeyCredential(new PubKeyHash(pkh)), Optional.of(new StakingCredential.StakingHash(
                new Credential.PubKeyCredential(new PubKeyHash(StateHarness.fill(28, pkh[0] ^ 0x55))))));
    }

    PoolValidator.Intent intent(byte[] owner, BigInteger received, PoolValidator.IntentCall call) {
        return new PoolValidator.Intent(h.instanceId(), owner, base(owner), LATER, received, call);
    }

    /** Three intents: ANN donates 5, BOB buys (pays 12, price 10, change 2), CAT buys (pays 11, price 11). */
    List<StateHarness.LiveIntent> three() {
        return List.of(
                h.liveIntent(1, intent(ANN, ada(5), new PoolValidator.DonateIntent()), ada(5).add(DEPOSIT)),
                h.liveIntent(2, intent(BOB, ada(12), new PoolValidator.BuyIntent(ada(10))), ada(12).add(DEPOSIT)),
                h.liveIntent(3, intent(CAT, ada(11), new PoolValidator.BuyIntent(ada(11))), ada(11).add(DEPOSIT)));
    }

    // ------------------------------------------------------------------ creating intents

    @Test
    void createIntent() {
        var tx = h.createIntent(PoolClient.INTENTS, intent(ANN, ada(5), new PoolValidator.DonateIntent()), ada(5).add(DEPOSIT));
        System.out.println("[createIntent] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void createIntent_withNegativeReceived_rejected() {
        // ADR-0004 r2 (security 1): a negative amount would drain the pool through the refund.
        var it = intent(ANN, ada(-100), new PoolValidator.DonateIntent());
        h.assertRejected(h.createIntent(PoolClient.INTENTS, it, DEPOSIT), "negative received");
    }

    @Test
    void createIntent_unsigned_orBelowDeposit_orUnderAnObject_rejected() {
        var it = intent(ANN, ada(5), new PoolValidator.DonateIntent());
        var unsigned = h.createIntent(PoolClient.INTENTS, it, ada(5).add(DEPOSIT));
        unsigned.signers.clear();
        h.assertRejected(unsigned, "owner did not sign");
        h.assertRejected(h.createIntent(PoolClient.INTENTS, it, ada(5).add(ADA)), "deposit below 2 ADA");
        var underObject = new PoolValidator.Intent(StateHarness.fill(29, 0x4F), ANN, base(ANN), LATER, ada(5),
                new PoolValidator.DonateIntent());
        h.assertRejected(h.createIntent(PoolClient.INTENTS, underObject, ada(5).add(DEPOSIT)), "instance id not 32 bytes");
    }

    // ------------------------------------------------------------------ batches

    @Test
    void batchOfThree_matchesTheJvmAndRejectsEveryAttack() {
        var b = h.batch(h.live(pool, ada(3)), PoolClient.INTENTS, three());
        assertEquals(3, b.plan().applied().size(), "skipped: " + b.plan().skipped());
        var s = b.plan().state();
        assertEquals(ada(5), s.donated());
        assertEquals(BigInteger.TWO, s.sold());
        assertEquals(ada(12), s.price());
        // 3 + 5 donated + 10 + 11 tickets; BOB's 2 ADA change is paid out
        assertEquals(ada(29), b.plan().balance());
        System.out.println("[batch x3] " + h.assertAccepted(b.tx()) + "  attacks rejected: "
                + Attacks.assertAllRejected(h, b.tx()));
    }

    @Test
    void batch_payingAStepsChangeToSomeoneElse_rejected() {
        var tx = h.batch(h.live(pool, ada(3)), PoolClient.INTENTS, three()).tx().copy();
        // BOB's change is tagged with BOB's intent input; send it to CAT instead.
        PlutusData bobTag = StateHarness.data(StateHarness.intentRef(2));
        int payout = tx.payoutOutputs.stream().filter(i -> tx.outputs.get(i).datum() instanceof OutputDatum.OutputDatumInline d
                && d.datum().equals(bobTag)).findFirst().orElseThrow();
        var o = tx.outputs.get(payout);
        tx.outputs.set(payout, new TxOut(StateHarness.pkhAddress(CAT), o.value(), o.datum(), Optional.empty()));
        h.assertRejected(tx, "a step's payout moved to another owner");
    }

    @Test
    void buyIsIntentOnly() {
        // @Batched(only = true): no direct-call redeemer exists for buy.
        assertThrows(ClassNotFoundException.class, () -> Class.forName(PoolValidator.class.getName() + "$Buy"));
    }

    @Test
    void batch_withADirectRedeemerOnTheInstance_rejected() {
        // ADR-0004 r2 (security 2): InBatch must see the instance spent with Batch, not with a direct action.
        var b = h.batch(h.live(pool, ada(3)), PoolClient.INTENTS, three());
        var tx = b.tx().copy();
        Ctx ctx = h.ctx(EVE, BigInteger.ZERO, ada(3));
        tx.spendRedeemers.put(StateHarness.LIVE_REF, StateHarness.data(new PoolValidator.Withdraw(ada(1), ctx)));
        tx.signers.add(EVE);
        h.assertRejected(tx, "instance spent with a direct action while intents are consumed");
    }

    @Test
    void batch_thatLeavesAnIntentOut_rejected() {
        // The intent is consumed (InBatch, burned) but the state does not include its step.
        var intents = three();
        var b = h.batch(h.live(pool, ada(3)), PoolClient.INTENTS, intents.subList(0, 2));
        var tx = b.tx().copy();
        var extra = intents.get(2);
        tx.inputs.add(new TxInInfo(extra.ref(), h.intentOut(extra.intent(), extra.lovelace(), extra.token())));
        tx.spendRedeemers.put(extra.ref(), StateHarness.data(PoolClient.INTENTS.inBatch()));
        tx.mint = tx.mint.merge(org.julclang.ledger.Value.singleton(new org.julclang.ledger.PolicyId(h.policy()),
                new org.julclang.ledger.TokenName(extra.token()), BigInteger.ONE.negate()));
        tx.outputs.add(StateHarness.walletOut(EVE, extra.lovelace()));   // the batcher keeps it
        h.assertRejected(tx, "an intent consumed without being applied");
    }

    @Test
    void batch_withAnIntentPastItsDeadline_rejected() {
        var b = h.batch(h.live(pool, ada(3)), PoolClient.INTENTS, three());
        var tx = b.tx().copy();
        var first = three().get(0);
        var late = new PoolValidator.Intent(h.instanceId(), ANN, base(ANN), StateHarness.T0, ada(5),
                new PoolValidator.DonateIntent());
        tx.inputs.replaceAll(i -> i.outRef().equals(first.ref())
                ? new TxInInfo(i.outRef(), h.intentOut(late, first.lovelace(), first.token())) : i);
        h.assertRejected(tx, "intent past its deadline");
    }

    @Test
    void batch_composedWithACancel_rejected() {
        // ADR-0004 r2 (Codex 7): every intent of the instance in the batch must be spent with InBatch.
        var b = h.batch(h.live(pool, ada(3)), PoolClient.INTENTS, three());
        var tx = b.tx().copy();
        tx.spendRedeemers.put(StateHarness.intentRef(1), StateHarness.data(PoolClient.INTENTS.cancel()));
        tx.signers.add(ANN);
        h.assertRejected(tx, "a cancelled intent inside its instance's batch");
    }

    @Test
    void batch_refundToAnotherStakeKey_rejected() {
        // ADR-0004 r2 (security 10): the refund goes to the exact address the owner chose.
        var b = h.batch(h.live(pool, ada(3)), PoolClient.INTENTS, three());
        var tx = b.tx().copy();
        int refund = 0;   // ANN's refund is the first output
        var o = tx.outputs.get(refund);
        var captured = new Address(o.address().credential(), Optional.of(new StakingCredential.StakingHash(
                new Credential.PubKeyCredential(new PubKeyHash(StateHarness.fill(28, 0xBA))))));
        tx.outputs.set(refund, new TxOut(captured, o.value(), o.datum(), Optional.empty()));
        h.assertRejected(tx, "refund delegated to the batcher's stake key");
    }

    @Test
    void anIntentOfAnotherInstance_cannotRideAlong() {
        var b = h.batch(h.live(pool, ada(3)), PoolClient.INTENTS, three());
        var tx = b.tx().copy();
        var foreign = h.liveIntent(9, new PoolValidator.Intent(StateHarness.fill(32, 0x99), EVE, base(EVE), LATER, ada(5),
                new PoolValidator.DonateIntent()), ada(5).add(DEPOSIT));
        tx.inputs.add(new TxInInfo(foreign.ref(), h.intentOut(foreign.intent(), foreign.lovelace(), foreign.token())));
        tx.spendRedeemers.put(foreign.ref(), StateHarness.data(PoolClient.INTENTS.inBatch()));
        tx.mint = tx.mint.merge(org.julclang.ledger.Value.singleton(new org.julclang.ledger.PolicyId(h.policy()),
                new org.julclang.ledger.TokenName(foreign.token()), BigInteger.ONE.negate()));
        tx.outputs.add(StateHarness.walletOut(EVE, foreign.lovelace()));
        h.assertRejected(tx, "an intent whose instance is not batched here");
    }

    @Test
    void wrongRedeemerKinds_rejected() {
        var it = three().get(0);
        // A direct (instance) redeemer on an intent UTxO, and Batch on an intent UTxO.
        var tx = h.cancelIntent(PoolClient.INTENTS, it, ANN);
        var direct = tx.copy();
        direct.spendRedeemers.put(it.ref(), StateHarness.data(new PoolValidator.Donate(h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO))));
        h.assertRejected(direct, "instance redeemer on an intent");
        var batch = tx.copy();
        batch.spendRedeemers.put(it.ref(), StateHarness.data(PoolClient.INTENTS.batch(StateHarness.T0, StateHarness.T0)));
        h.assertRejected(batch, "Batch on an intent");
    }

    @Test
    void emptyBatch_rejected() {
        var b = h.batch(h.live(pool, ada(3)), PoolClient.INTENTS, List.of());
        h.assertRejected(b.tx(), "a batch with no intents");
    }

    @Test
    void batchCost_growsWithTheNumberOfIntents() {
        for (int n : new int[]{1, 5, 10, 15}) {
            var intents = new java.util.ArrayList<StateHarness.LiveIntent>();
            for (int i = 1; i <= n; i++) {
                byte[] owner = StateHarness.fill(28, 0x10 + i);
                intents.add(h.liveIntent(i, intent(owner, ada(2), new PoolValidator.DonateIntent()), ada(2).add(DEPOSIT)));
            }
            var v = h.assertAccepted(h.batch(h.live(pool, ada(3)), PoolClient.INTENTS, intents).tx());
            long cpu = v.results().values().stream().mapToLong(r -> r.budgetConsumed().cpuSteps()).sum();
            long mem = v.results().values().stream().mapToLong(r -> r.budgetConsumed().memoryUnits()).sum();
            System.out.printf("[batch cost] n=%d cpu=%d mem=%d (limits 10e9 / 14e6)%n", n, cpu, mem);
        }
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void cancel_byTheOwner_only() {
        var it = three().get(1);
        var tx = h.cancelIntent(PoolClient.INTENTS, it, BOB);
        System.out.println("[cancel] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        h.assertRejected(h.cancelIntent(PoolClient.INTENTS, it, EVE), "cancel by a stranger");
    }

}
