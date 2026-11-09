package org.julclang.state.examples.treasury;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.Credential;
import org.julclang.ledger.ScriptHash;
import org.julclang.state.api.Approval;
import org.julclang.state.api.Approvals;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payouts;
import org.julclang.state.api.RequireException;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-0009: deploy params, a quorum council, a guardian script, an invariant and authenticated events. */
class TreasuryTest {
    static final byte[] A = StateHarness.fill(28, 0x11);
    static final byte[] B = StateHarness.fill(28, 0x22);
    static final byte[] C = StateHarness.fill(28, 0x33);
    static final byte[] BOB = StateHarness.fill(28, 0xB0);
    static final byte[] GUARDIAN = StateHarness.fill(28, 0x6A);
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final Credential GUARDIAN_CRED = new Credential.ScriptCredential(new ScriptHash(GUARDIAN));

    final Treasury.Config cfg = new Treasury.Config(GUARDIAN_CRED, ADA.multiply(BigInteger.valueOf(100)));
    final StateHarness<Treasury.State> h = StateHarness.of(TreasuryClient.SPEC, cfg);

    static JulcList<byte[]> keys(byte[]... ks) {
        JulcList<byte[]> l = JulcList.empty();
        for (int i = ks.length - 1; i >= 0; i--) l = l.prepend(ks[i]);
        return l;
    }

    final Treasury.State council = new Treasury.State(keys(A, B, C), BigInteger.TWO, false);

    static TxModel signedBy(TxModel tx, byte[]... signers) {
        tx.signers.clear();
        for (byte[] s : signers) tx.signers.add(s);
        return tx;
    }

    @Test
    void open_withAWellFormedCouncil() {
        Ctx ctx = h.ctx(A, ADA.multiply(BigInteger.valueOf(500)), BigInteger.ZERO);
        var s = Treasury.open(cfg, keys(A, B, C), BigInteger.TWO, ctx);
        var tx = h.create(new TreasuryValidator.CreateInstance(h.seed(), keys(A, B, C), BigInteger.TWO, ctx), s, ctx);
        System.out.println("[open] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        h.assertRejected(h.create(new TreasuryValidator.CreateInstance(h.seed(), keys(A, A, B), BigInteger.TWO, ctx),
                Treasury.open(cfg, keys(A, A, B), BigInteger.TWO, ctx), ctx), "duplicate council keys");
        h.assertRejected(h.create(new TreasuryValidator.CreateInstance(h.seed(), keys(A, B), BigInteger.valueOf(3), ctx),
                Treasury.open(cfg, keys(A, B), BigInteger.valueOf(3), ctx), ctx), "quorum above the council");
    }

    @Test
    void pay_twoOfThree() {
        var live = h.live(council, ADA.multiply(BigInteger.valueOf(500)));
        Ctx ctx = h.ctx(A, BigInteger.ZERO, ADA.multiply(BigInteger.valueOf(500)));
        var r = Treasury.pay(council, cfg, BOB, ADA.multiply(BigInteger.TEN), ctx);
        var tx = signedBy(h.act(live, new TreasuryValidator.Pay(BOB, ADA.multiply(BigInteger.TEN), ctx, r.events()), r.state(),
                r.payouts(), A, ctx), A, C);
        System.out.println("[pay 2-of-3] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        h.assertRejected(signedBy(tx.copy(), A), "one of three");
        h.assertRejected(signedBy(tx.copy(), A, BOB), "a stranger does not count");
    }

    @Test
    void pay_withForgedEvents_rejected() {
        var live = h.live(council, ADA.multiply(BigInteger.valueOf(500)));
        Ctx ctx = h.ctx(A, BigInteger.ZERO, ADA.multiply(BigInteger.valueOf(500)));
        var r = Treasury.pay(council, cfg, BOB, ADA.multiply(BigInteger.TEN), ctx);
        JulcList<Treasury.Event> forged = JulcList.<Treasury.Event>empty().prepend(new Treasury.Paid(BOB, ADA));
        h.assertRejected(signedBy(h.act(live, new TreasuryValidator.Pay(BOB, ADA.multiply(BigInteger.TEN), ctx, forged), r.state(),
                r.payouts(), A, ctx), A, B), "redeemer events differ from the method's (I35)");
    }

    @Test
    void pay_overTheParamLimit_rejected() {
        Ctx ctx = h.ctx(A, BigInteger.ZERO, ADA.multiply(BigInteger.valueOf(500)));
        assertThrows(RequireException.class, () -> Treasury.pay(council, cfg, BOB, ADA.multiply(BigInteger.valueOf(101)), ctx));
    }

    @Test
    void rotate_toAnUnreachableOrMalformedCouncil_rejected() {
        var live = h.live(council, ADA.multiply(BigInteger.valueOf(500)));
        var ok = Treasury.rotate(council, keys(A, B), BigInteger.TWO);
        var tx = signedBy(h.act(live, new TreasuryValidator.Rotate(keys(A, B), BigInteger.TWO, ok.events()), ok.state(), A, null), A, B);
        System.out.println("[rotate] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        var dup = Treasury.rotate(council, keys(A, A, B), BigInteger.valueOf(3));
        h.assertRejected(signedBy(h.act(live, new TreasuryValidator.Rotate(keys(A, A, B), BigInteger.valueOf(3), dup.events()),
                dup.state(), A, null), A, B), "duplicates would make quorum 3 unreachable");
        var unsorted = Treasury.rotate(council, keys(B, A), BigInteger.ONE);
        h.assertRejected(signedBy(h.act(live, new TreasuryValidator.Rotate(keys(B, A), BigInteger.ONE, unsorted.events()),
                unsorted.state(), A, null), A, B), "keys must be strictly ascending");
        var tooHigh = new Treasury.State(keys(A), BigInteger.TWO, false);
        assertFalse(Treasury.reachable(tooHigh));
    }

    @Test
    void freeze_byTheGuardianScript() {
        var live = h.live(council, ADA.multiply(BigInteger.valueOf(500)));
        var r = Treasury.freeze(council, true);
        var tx = h.act(live, new TreasuryValidator.Freeze(true, r.events()), r.state(), null, null);
        h.assertRejected(tx, "no guardian withdrawal");
        tx.withdrawals.put(GUARDIAN_CRED, approval(h.instanceId(), 3));
        System.out.println("[freeze] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));

        var other = tx.copy();
        other.withdrawals.put(GUARDIAN_CRED, approval(StateHarness.fill(32, 0x99), 3));
        h.assertRejected(other, "approval for another instance");
        var wrongAction = tx.copy();
        wrongAction.withdrawals.put(GUARDIAN_CRED, approval(h.instanceId(), 1));
        h.assertRejected(wrongAction, "approval for another action");
        var noRedeemerButOtherScript = tx.copy();
        noRedeemerButOtherScript.withdrawals.clear();
        noRedeemerButOtherScript.withdrawals.put(new Credential.ScriptCredential(new ScriptHash(StateHarness.fill(28, 0x5D))), null);
        h.assertRejected(noRedeemerButOtherScript, "a different script's withdrawal");
    }

    @Test
    void close_byQuorum() {
        var live = h.live(council, ADA.multiply(BigInteger.valueOf(500)));
        Ctx ctx = h.ctx(B, BigInteger.ZERO, live.lovelace());
        var payouts = Treasury.close(council, BOB, ctx);
        var tx = signedBy(h.destroy(live, new TreasuryValidator.Close(BOB, ctx), new TreasuryValidator.BurnInstance(), payouts,
                B, ctx), B, C);
        System.out.println("[close] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        h.assertRejected(signedBy(tx.copy(), B), "one signature");
        assertEquals(1, Payouts.total(payouts).compareTo(BigInteger.ZERO));
    }

    org.julclang.core.PlutusData approval(byte[] target, long action) {
        JulcList<Approval> list = JulcList.<Approval>empty().prepend(new Approval(h.policy(), target, BigInteger.valueOf(action)));
        return StateHarness.data(new Approvals(list));
    }
}
