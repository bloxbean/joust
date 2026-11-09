package org.julclang.state.examples.bank;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.TxInInfo;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payouts;
import org.julclang.state.api.RequireException;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Bank with owned accounts (ADR-0003): object creation, owned actions with and without @Shared, the escape hatch. */
class BankTest {
    static final byte[] ADMIN = StateHarness.fill(28, 0xAD);
    static final byte[] ANN = StateHarness.fill(28, 0xA1);
    static final byte[] EVE = StateHarness.fill(28, 0xEE);
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    final StateHarness<Bank.State> h = StateHarness.of(BankClient.SPEC);
    final Bank.State open = new Bank.State(ADMIN, false, ADA.multiply(BigInteger.valueOf(50)));
    final Bank.State paused = new Bank.State(ADMIN, true, ADA.multiply(BigInteger.valueOf(50)));

    BankValidator.Holding held(Bank.Account a) {
        return new BankValidator.Holding(h.instanceId(), a);
    }

    @Test
    void openAnAccount() {
        Ctx ctx = h.ctx(ANN, ADA.multiply(BigInteger.TEN), BigInteger.ZERO);
        var tx = h.createObject(new BankValidator.Open(h.seed(), h.instanceId(), ctx), held(Bank.open(open, ctx)), open, ctx);
        System.out.println("[open] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void openingWhilePaused_rejectedOnTheJvmAndOnChain() {
        Ctx ctx = h.ctx(ANN, ADA, BigInteger.ZERO);
        assertThrows(RequireException.class, () -> Bank.open(paused, ctx));
        h.assertRejected(h.createObject(new BankValidator.Open(h.seed(), h.instanceId(), ctx),
                held(new Bank.Account(ANN, ADA)), paused, ctx), "opening while paused");
    }

    @Test
    void openUnderAnObjectInsteadOfAnInstance_rejected() {
        Ctx ctx = h.ctx(ANN, ADA, BigInteger.ZERO);
        byte[] fake = h.objectId();
        var tx = h.createObject(new BankValidator.Open(h.seed(), fake, ctx),
                new BankValidator.Holding(fake, new Bank.Account(ANN, ADA)), open, ctx);
        h.assertRejected(tx, "an object id as the instance (ADR-0003 r2)");
    }

    @Test
    void deposit() {
        var obj = h.liveObject(held(new Bank.Account(ANN, ADA.multiply(BigInteger.TEN))), ADA.multiply(BigInteger.TEN));
        Ctx ctx = h.ctx(ANN, ADA.multiply(BigInteger.valueOf(5)), ADA.multiply(BigInteger.TEN));
        var next = Bank.deposit(new Bank.Account(ANN, ADA.multiply(BigInteger.TEN)), ctx);
        var tx = h.actObject(obj, new BankValidator.Deposit(ctx), held(next), JulcList.empty(), ANN, ctx, null);
        System.out.println("[deposit] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void withdraw_readsTheBankByReference() {
        var acct = new Bank.Account(ANN, ADA.multiply(BigInteger.TEN));
        var obj = h.liveObject(held(acct), ADA.multiply(BigInteger.TEN));
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, ADA.multiply(BigInteger.TEN));
        var r = Bank.withdraw(acct, open, ADA.multiply(BigInteger.valueOf(4)), ctx);
        var tx = h.actObject(obj, new BankValidator.Withdraw(ADA.multiply(BigInteger.valueOf(4)), ctx), held(r.state()),
                r.payouts(), ANN, ctx, open);
        System.out.println("[withdraw] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void withdraw_whilePaused_orWithoutTheReference_orOverTheLimit_rejected() {
        var acct = new Bank.Account(ANN, ADA.multiply(BigInteger.valueOf(100)));
        var obj = h.liveObject(held(acct), ADA.multiply(BigInteger.valueOf(100)));
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, ADA.multiply(BigInteger.valueOf(100)));
        BigInteger four = ADA.multiply(BigInteger.valueOf(4));
        var next = new Bank.Account(ANN, acct.balance().subtract(four));
        h.assertRejected(h.actObject(obj, new BankValidator.Withdraw(four, ctx), held(next), Payouts.of(ANN, four), ANN, ctx,
                paused), "the bank is paused");
        var noRef = h.actObject(obj, new BankValidator.Withdraw(four, ctx), held(next), Payouts.of(ANN, four), ANN, ctx, null);
        h.assertRejected(noRef, "no reference input for @Shared");
        BigInteger sixty = ADA.multiply(BigInteger.valueOf(60));
        h.assertRejected(h.actObject(obj, new BankValidator.Withdraw(sixty, ctx),
                held(new Bank.Account(ANN, acct.balance().subtract(sixty))), Payouts.of(ANN, sixty), ANN, ctx, open),
                "over the limit");
    }

    @Test
    void withdraw_byAStranger_rejected() {
        var acct = new Bank.Account(ANN, ADA.multiply(BigInteger.TEN));
        var obj = h.liveObject(held(acct), ADA.multiply(BigInteger.TEN));
        Ctx ctx = h.ctx(EVE, BigInteger.ZERO, ADA.multiply(BigInteger.TEN));
        h.assertRejected(h.actObject(obj, new BankValidator.Withdraw(ADA, ctx),
                held(new Bank.Account(ANN, acct.balance().subtract(ADA))), Payouts.of(EVE, ADA), EVE, ctx, open),
                "@SignedBy(owner)");
    }

    @Test
    void sharedReadOfAnotherInstance_rejected() {
        var acct = new Bank.Account(ANN, ADA.multiply(BigInteger.TEN));
        var obj = h.liveObject(new BankValidator.Holding(StateHarness.fill(32, 0x99), acct), ADA.multiply(BigInteger.TEN));
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, ADA.multiply(BigInteger.TEN));
        // the object belongs to instance 0x99..; the reference input is the harness instance
        h.assertRejected(h.actObject(obj, new BankValidator.Withdraw(ADA, ctx),
                new BankValidator.Holding(StateHarness.fill(32, 0x99), new Bank.Account(ANN, acct.balance().subtract(ADA))),
                Payouts.of(ANN, ADA), ANN, ctx, open), "@Shared reference of a different instance");
    }

    @Test
    void anObjectSpentWithASharedRedeemer_rejected() {
        var obj = h.liveObject(held(new Bank.Account(ANN, ADA)), ADA);
        // SetPaused is a shared (instance) redeemer; the object token is 29 bytes (I21)
        var tx = h.actObject(obj, new BankValidator.SetPaused(true), held(new Bank.Account(ANN, ADA)), JulcList.empty(),
                ADMIN, null, null);
        h.assertRejected(tx, "shared redeemer on an object");
    }

    @Test
    void close_escapeHatchWorksWhilePaused() {
        var acct = new Bank.Account(ANN, ADA.multiply(BigInteger.TEN));
        var obj = h.liveObject(held(acct), ADA.multiply(BigInteger.TEN));
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, obj.lovelace());
        var tx = h.destroyObject(obj, new BankValidator.Close(ctx), new BankValidator.BurnInstance(), Bank.close(acct, ctx),
                ANN, ctx);
        System.out.println("[close] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        assertTrue(tx.referenceInputs.isEmpty(), "close needs no bank reference: it works even if the bank is paused or gone");
    }

    @Test
    void sharedActionOnTheInstanceStillWorks() {
        var live = h.live(open, BigInteger.ZERO);
        var tx = h.act(live, new BankValidator.SetPaused(true), Bank.setPaused(open, true), ADMIN, null);
        System.out.println("[setPaused] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        assertFalse(Arrays.equals(h.objectId(), h.instanceId()));
    }
}
