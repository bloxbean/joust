package org.julclang.state.examples.locker;

import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.RequireException;
import org.julclang.state.runtime.InMemoryNodeStore;
import org.julclang.state.runtime.MerkleSupport;
import org.julclang.state.runtime.Transition;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/** MerkleMaps with native assets: balances in a map, the tokens themselves in the instance's value. */
class LockerTest {
    static final byte[] ADMIN = StateHarness.fill(28, 0xAD);
    static final byte[] ANN = StateHarness.fill(28, 0xA1);
    static final byte[] POLICY = StateHarness.fill(28, 0x60);
    static final byte[] GOLD = "GOLD".getBytes();

    final StateHarness<Locker.State> h = StateHarness.of(LockerClient.SPEC);
    final InMemoryNodeStore store = new InMemoryNodeStore();

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    Value gold(long n) {
        return Assets.of(POLICY, GOLD, BigInteger.valueOf(n));
    }

    Locker.State created() {
        Ctx ctx = h.ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO);
        var ms = new MerkleSupport(store);
        var made = ms.run(() -> Locker.init(POLICY, GOLD, ctx));
        return ms.prepareCreation(h.policy(), Locker.State.class, made, h.seed()).stored();
    }

    /** {@code s} (holding {@code locked} gold) after ANN locks {@code qty}: the transaction and the stored state. */
    record Step(TxModel tx, Locker.State next) {}

    Step lock(Locker.State s, long locked, long qty) {
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var held = h.held(s, BigInteger.ZERO, locked == 0 ? Assets.none() : gold(locked));
        var funds = h.funds(held, gold(qty));
        var ms = new MerkleSupport(store);
        var r = ms.run(() -> Locker.lock(s, BigInteger.valueOf(qty), ctx, funds));
        var p = ms.prepare(h.policy(), Locker.State.class, s, r.state(), StateHarness.LIVE_REF);
        var tx = h.stepWith(held, new LockerValidator.Lock(BigInteger.valueOf(qty), ctx, gold(qty).toPlutusData()), null,
                Transition.full(p.stored(), null, null, null, r.accepted()), p.stored(), ANN, ctx, null);
        return new Step(h.withClaims(tx, p.claims()), p.stored());
    }

    @Test
    void create_withAnEmptyMap() {
        Ctx ctx = h.ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO);
        var ms = new MerkleSupport(store);
        var made = ms.run(() -> Locker.init(POLICY, GOLD, ctx));
        var p = ms.prepareCreation(h.policy(), Locker.State.class, made, h.seed());
        var tx = h.withClaims(h.createWith(new LockerValidator.CreateInstance(h.seed(), POLICY, GOLD, ctx), p.stored(), ctx,
                Assets.none(), false, null), p.claims());
        System.out.println("[create] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void lock_takesTheTokensAndWritesTheBalance() {
        var step = lock(created(), 0, 40);
        System.out.println("[lock] " + h.assertAccepted(step.tx()) + "  attacks rejected: " + Attacks.assertAllRejected(h, step.tx()));
        assertEquals(BigInteger.valueOf(40), step.next().total());
    }

    @Test
    void unlock_sendsTheTokensBackAndWritesTheBalance() {
        var s = lock(created(), 0, 40).next();
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var held = h.held(s, BigInteger.ZERO, gold(40));
        var ms = new MerkleSupport(store);
        var r = ms.run(() -> Locker.unlock(s, BigInteger.valueOf(15), ctx));
        var p = ms.prepare(h.policy(), Locker.State.class, s, r.state(), StateHarness.LIVE_REF);
        var tx = h.withClaims(h.stepWith(held, new LockerValidator.Unlock(BigInteger.valueOf(15), ctx), null,
                Transition.full(p.stored(), null, r.sends(), null, null), p.stored(), ANN, ctx, null), p.claims());
        System.out.println("[unlock] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        assertThrows(RequireException.class, () -> new MerkleSupport(store).run(() -> Locker.unlock(s, BigInteger.valueOf(41), ctx)));
    }

    @Test
    void lock_withoutTheTokens_orAForgedRoot_rejected() {
        var step = lock(created(), 0, 40);
        // the state output lacks the tokens the method accepted
        var tx = step.tx().copy();
        var o = tx.outputs.get(tx.stateOutput);
        tx.outputs.set(tx.stateOutput, new org.julclang.ledger.TxOut(o.address(), Assets.subtract(o.value(), gold(40)), o.datum(),
                o.referenceScript()));
        h.assertRejected(tx, "the accepted tokens are not in the state output");
        // a datum claiming a root the verifier did not compute
        var forged = new Locker.State(step.next().admin(), POLICY, GOLD, step.next().total(), org.julclang.state.api.MerkleMaps.empty());
        var tx2 = step.tx().copy();
        var o2 = tx2.outputs.get(tx2.stateOutput);
        tx2.outputs.set(tx2.stateOutput, new org.julclang.ledger.TxOut(o2.address(), o2.value(),
                new org.julclang.ledger.OutputDatum.OutputDatumInline(StateHarness.data(forged)), o2.referenceScript()));
        h.assertRejected(tx2, "a forged map root");
    }

    @Test
    void creationClaims_neverShareAKeyWithASpend() {
        // a creation's claims use fields -1, -2, ...; a spend's 0, 1, ...: a seed that is also a spent state input of
        // this contract cannot make them collide (ADR-0014 review)
        Ctx ctx = h.ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO);
        var ms = new MerkleSupport(store);
        var made = ms.run(() -> Locker.init(POLICY, GOLD, ctx));
        var created = ms.prepareCreation(h.policy(), Locker.State.class, made, StateHarness.LIVE_REF);
        assertEquals(BigInteger.valueOf(-1), created.claims().get(0).field());
        var step = lock(created.stored(), 0, 5);
        assertTrue(step.tx().mapClaims.stream().allMatch(c -> c.field().signum() >= 0));
    }

    @Test
    void replay_comparesAssetEffectsToo() {
        var s = created();
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var funds = h.funds(h.held(s, BigInteger.ZERO, Assets.none()), gold(10));
        var ms = new MerkleSupport(store);
        var r = ms.run(() -> Locker.lock(s, BigInteger.TEN, ctx, funds));
        var p = ms.prepare(h.policy(), Locker.State.class, s, r.state(), StateHarness.LIVE_REF);
        Transition<Locker.State> recorded = Transition.full(r.state(), null, null, null, r.accepted());
        // a replay that agrees on the state but accepts a different amount is refused
        assertThrows(org.julclang.state.runtime.JoustException.class, () -> MerkleSupport.verifyReplay(Locker.State.class, s,
                p.claims(), x -> Transition.full(Locker.lock(x, BigInteger.TEN, ctx, funds).state(), null, null, null, gold(11)), recorded));
        MerkleSupport.verifyReplay(Locker.State.class, s, p.claims(),
                x -> { var again = Locker.lock(x, BigInteger.TEN, ctx, funds); return Transition.full(again.state(), null, null, null, again.accepted()); },
                recorded);
    }
}
