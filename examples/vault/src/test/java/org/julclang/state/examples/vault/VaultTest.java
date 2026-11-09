package org.julclang.state.examples.vault;

import org.julclang.state.api.Ctx;
import org.julclang.state.api.RequireException;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultTest {
    static final byte[] OWNER = StateHarness.fill(28, 0x01);
    static final byte[] BOB = StateHarness.fill(28, 0x02);
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final BigInteger UNLOCK = StateHarness.T0.subtract(BigInteger.valueOf(60_000));   // already unlocked
    static final BigInteger LATER = StateHarness.T0.add(BigInteger.valueOf(3_600_000));      // still locked

    final StateHarness<Vault.State> h = StateHarness.of(VaultClient.SPEC);

    @Test
    void open_withFirstDeposit() {
        Ctx ctx = h.ctx(OWNER, ADA.multiply(BigInteger.TEN), BigInteger.ZERO);
        var tx = h.create(new VaultValidator.CreateInstance(h.seed(), LATER, ctx), Vault.open(LATER, ctx), ctx);
        System.out.println("[open] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void open_unlockInThePast_rejectedOnTheJvm() {
        Ctx ctx = h.ctx(OWNER, ADA, BigInteger.ZERO);
        assertThrows(RequireException.class, () -> Vault.open(UNLOCK, ctx));
    }

    @Test
    void deposit_byAnyone() {
        var live = h.live(new Vault.State(OWNER, LATER, ADA.multiply(BigInteger.TEN)), ADA.multiply(BigInteger.TEN));
        Ctx ctx = h.ctx(BOB, ADA.multiply(BigInteger.valueOf(5)), ADA.multiply(BigInteger.TEN));
        var tx = h.act(live, new VaultValidator.Deposit(ctx), Vault.deposit(live.state(), ctx), BOB, ctx);
        System.out.println("[deposit] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void deposit_claimingMoreThanSent_rejected() {
        var live = h.live(new Vault.State(OWNER, LATER, ADA), ADA);
        Ctx claimed = h.ctx(BOB, ADA.multiply(BigInteger.TEN), ADA);       // says 10 ADA, the output only gets 1
        Ctx actual = h.ctx(BOB, ADA, ADA);
        var tx = h.act(live, new VaultValidator.Deposit(claimed), Vault.deposit(live.state(), claimed), BOB, actual);
        h.assertRejected(tx, "ctx.received must match the value added");
    }

    @Test
    void withdraw_afterUnlock() {
        var live = h.live(new Vault.State(OWNER, UNLOCK, ADA.multiply(BigInteger.TEN)), ADA.multiply(BigInteger.TEN));
        Ctx ctx = h.ctx(OWNER, BigInteger.ZERO, ADA.multiply(BigInteger.TEN));
        var r = Vault.withdraw(live.state(), ADA.multiply(BigInteger.valueOf(3)), ctx);
        var tx = h.act(live, new VaultValidator.Withdraw(ADA.multiply(BigInteger.valueOf(3)), ctx), r.state(), r.payouts(), OWNER, ctx);
        System.out.println("[withdraw] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void withdraw_beforeUnlock_rejectedOnChain() {
        var live = h.live(new Vault.State(OWNER, LATER, ADA.multiply(BigInteger.TEN)), ADA.multiply(BigInteger.TEN));
        Ctx ctx = h.ctx(OWNER, BigInteger.ZERO, ADA.multiply(BigInteger.TEN));
        var r = Vault.withdraw(live.state(), ADA, ctx);
        h.assertRejected(h.act(live, new VaultValidator.Withdraw(ADA, ctx), r.state(), r.payouts(), OWNER, ctx), "@ValidAfter");
    }

    @Test
    void withdraw_byStranger_rejected() {
        var live = h.live(new Vault.State(OWNER, UNLOCK, ADA.multiply(BigInteger.TEN)), ADA.multiply(BigInteger.TEN));
        Ctx ctx = h.ctx(BOB, BigInteger.ZERO, ADA.multiply(BigInteger.TEN));
        var r = Vault.withdraw(live.state(), ADA, ctx);
        h.assertRejected(h.act(live, new VaultValidator.Withdraw(ADA, ctx), r.state(), r.payouts(), BOB, ctx), "@OnlyOwner");
    }

    @Test
    void close_afterUnlock() {
        var live = h.live(new Vault.State(OWNER, UNLOCK, ADA.multiply(BigInteger.TEN)), ADA.multiply(BigInteger.TEN));
        Ctx ctx = h.ctx(OWNER, BigInteger.ZERO, live.lovelace());
        var tx = h.destroy(live, new VaultValidator.Close(ctx), new VaultValidator.BurnInstance(), Vault.close(live.state(), ctx), OWNER, ctx);
        System.out.println("[close] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }
}
