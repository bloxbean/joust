package org.julclang.state.examples.token;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.MapEntry;
import org.julclang.state.api.MerkleMap;
import org.julclang.state.api.RequireException;
import org.julclang.state.api.mpf.MapClaim;
import org.julclang.state.api.mpf.ReadProof;
import org.julclang.state.api.mpf.WriteProof;
import org.julclang.state.testkit.TxModel;
import java.util.List;
import org.julclang.state.runtime.InMemoryNodeStore;
import org.julclang.state.runtime.MerkleSupport;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Token with MerkleMap balances (ADR-0002): JVM runs with reads served from a mirror, the validator evaluated against
 * runtime-shaped transactions (witnesses built exactly as the runtime builds them), the attack suite, and attacks on
 * the map witnesses themselves.
 */
class TokenTest {

    static final byte[] ADMIN = StateHarness.fill(28, 0xAD);
    static final byte[] ANN = StateHarness.fill(28, 0xA1);
    static final byte[] BEN = StateHarness.fill(28, 0xB1);
    static final byte[] CAT = StateHarness.fill(28, 0xC1);

    final StateHarness<Token.State> h = StateHarness.of(TokenClient.SPEC);
    final InMemoryNodeStore store = new InMemoryNodeStore();

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    /** Runs {@code method} on {@code current} as the runtime does: reads from the mirror, then witnesses. */
    MerkleSupport.Prepared<Token.State> call(Token.State current, Function<Token.State, Token.State> method) {
        var ms = new MerkleSupport(store);
        Token.State next = ms.run(() -> method.apply(current));
        return ms.prepare(h.policy(), Token.State.class, current, next, StateHarness.LIVE_REF);
    }

    /** An action transaction on the live instance, with the verifier's claims attached. */
    TxModel act(Token.State s, Object redeemer, MerkleSupport.Prepared<Token.State> p, Token.State next, byte[] signer, Ctx ctx) {
        return h.withClaims(h.act(h.live(s, BigInteger.ZERO), redeemer, next, signer, ctx), p.claims());
    }

    TxModel act(Token.State s, Object redeemer, MerkleSupport.Prepared<Token.State> p, byte[] signer, Ctx ctx) {
        return act(s, redeemer, p, p.stored(), signer, ctx);
    }

    static MapClaim withReads(MapClaim c, org.julclang.core.types.JulcList<ReadProof> reads) {
        return new MapClaim(c.policy(), c.owner(), c.field(), c.oldRoot(), reads, c.writes(), c.newRoot());
    }

    static MapClaim withWrites(MapClaim c, org.julclang.core.types.JulcList<WriteProof> writes) {
        return new MapClaim(c.policy(), c.owner(), c.field(), c.oldRoot(), c.reads(), writes, c.newRoot());
    }

    /** A token with ANN 100, BEN 50, CAT 25 minted (built through real calls, so the mirror holds them). */
    Token.State seeded() {
        var ms = new MerkleSupport(store);
        Ctx ctx = h.ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO);
        Token.State s = ms.prepareCreation(h.policy(), Token.State.class, Token.create(ctx), h.seed()).stored();
        s = call(s, x -> Token.mint(x, ANN, BigInteger.valueOf(100))).stored();
        s = call(s, x -> Token.mint(x, BEN, BigInteger.valueOf(50))).stored();
        return call(s, x -> Token.mint(x, CAT, BigInteger.valueOf(25))).stored();
    }

    @Test
    void create() {
        Ctx ctx = h.ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO);
        var ms = new MerkleSupport(store);
        Token.State made = ms.run(() -> Token.create(ctx));
        var p = ms.prepareCreation(h.policy(), Token.State.class, made, h.seed());
        var tx = h.withClaims(h.create(new TokenValidator.CreateInstance(h.seed(), ctx), p.stored(), ctx), p.claims());
        System.out.println("[create] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void mint() {
        Token.State s = seeded();
        var p = call(s, x -> Token.mint(x, ANN, BigInteger.TEN));
        var tx = act(s, new TokenValidator.Mint(ANN, BigInteger.TEN), p, ADMIN, null);
        System.out.println("[mint] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        assertEquals(BigInteger.valueOf(110), balance(p.stored(), ANN));
    }

    @Test
    void transfer() {
        Token.State s = seeded();
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var p = call(s, x -> Token.transfer(x, BEN, BigInteger.valueOf(30), ctx));
        var tx = act(s, new TokenValidator.Transfer(BEN, BigInteger.valueOf(30), ctx), p, ANN, ctx);
        System.out.println("[transfer] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        assertEquals(BigInteger.valueOf(70), balance(p.stored(), ANN));
        assertEquals(BigInteger.valueOf(80), balance(p.stored(), BEN));
    }

    @Test
    void transferEverything_deletesTheKey_andSelfTransferWorks() {
        Token.State s = seeded();
        Ctx catCtx = h.ctx(CAT, BigInteger.ZERO, BigInteger.ZERO);
        var all = call(s, x -> Token.transfer(x, ANN, BigInteger.valueOf(25), catCtx));
        h.assertAccepted(act(s, new TokenValidator.Transfer(ANN, BigInteger.valueOf(25), catCtx), all, CAT, catCtx));
        assertEquals(BigInteger.ZERO, balance(all.stored(), CAT));

        Ctx annCtx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var self = call(s, x -> Token.transfer(x, ANN, BigInteger.valueOf(40), annCtx));
        h.assertAccepted(act(s, new TokenValidator.Transfer(ANN, BigInteger.valueOf(40), annCtx), self, ANN, annCtx));
        assertEquals(BigInteger.valueOf(100), balance(self.stored(), ANN));
    }

    @Test
    void transfer_overdraft_rejectedOnTheJvm() {
        Token.State s = seeded();
        Ctx ctx = h.ctx(BEN, BigInteger.ZERO, BigInteger.ZERO);
        assertThrows(RequireException.class, () -> call(s, x -> Token.transfer(x, ANN, BigInteger.valueOf(51), ctx)));
    }

    // ------------------------------------------------------------------ attacks on the map witnesses

    @Test
    void forgedReadValue_rejected() {
        // BEN claims a balance of 1000 by forging the read entry (the proof is for the real value).
        Token.State s = seeded();
        Ctx ctx = h.ctx(BEN, BigInteger.ZERO, BigInteger.ZERO);
        var p = call(s, x -> Token.transfer(x, ANN, BigInteger.TEN, ctx));
        MapClaim w = p.claims().get(0);
        JulcList<ReadProof> forged = JulcList.empty();
        for (ReadProof r : w.reads()) {
            MapEntry e = r.entry();
            boolean ben = java.util.Arrays.equals(e.key(), BEN);
            forged = forged.prepend(ben ? new ReadProof(new MapEntry(BEN, true, intBytes(1000)), r.proof()) : r);
        }
        var tx = h.withClaims(h.act(h.live(s, BigInteger.ZERO), new TokenValidator.Transfer(ANN, BigInteger.TEN, ctx),
                p.stored(), BEN, ctx), List.of(withReads(w, forged.reverse())));
        h.assertRejected(tx, "forged read value");
    }

    @Test
    void missingWriteWitness_rejected() {
        Token.State s = seeded();
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var p = call(s, x -> Token.transfer(x, BEN, BigInteger.TEN, ctx));
        MapClaim w = p.claims().get(0);
        h.assertRejected(h.withClaims(h.act(h.live(s, BigInteger.ZERO), new TokenValidator.Transfer(BEN, BigInteger.TEN, ctx),
                p.stored(), ANN, ctx), List.of(withWrites(w, w.writes().tail()))), "a net write without its witness");
    }

    @Test
    void reorderedWriteWitnesses_rejected() {
        Token.State s = seeded();
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var p = call(s, x -> Token.transfer(x, BEN, BigInteger.TEN, ctx));
        MapClaim w = p.claims().get(0);
        h.assertRejected(h.withClaims(h.act(h.live(s, BigInteger.ZERO), new TokenValidator.Transfer(BEN, BigInteger.TEN, ctx),
                p.stored(), ANN, ctx), List.of(withWrites(w, w.writes().reverse()))), "write witnesses out of order");
    }

    @Test
    void writeWitnessForAnotherValue_rejected() {
        // The witness claims ANN ends with 1000 (the method computed 90).
        Token.State s = seeded();
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var p = call(s, x -> Token.transfer(x, BEN, BigInteger.TEN, ctx));
        MapClaim w = p.claims().get(0);
        JulcList<WriteProof> tampered = JulcList.empty();
        for (WriteProof wp : w.writes()) {
            boolean ann = java.util.Arrays.equals(wp.after().key(), ANN);
            tampered = tampered.prepend(ann ? new WriteProof(wp.before(), new MapEntry(ANN, true, intBytes(1000)), wp.proof()) : wp);
        }
        h.assertRejected(h.withClaims(h.act(h.live(s, BigInteger.ZERO), new TokenValidator.Transfer(BEN, BigInteger.TEN, ctx),
                p.stored(), ANN, ctx), List.of(withWrites(w, tampered.reverse()))), "write witness that differs from the method's write");
    }

    @Test
    void wrongRootInTheDatum_rejected() {
        Token.State s = seeded();
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var p = call(s, x -> Token.transfer(x, BEN, BigInteger.TEN, ctx));
        JulcList<MapEntry> none = JulcList.empty();
        Token.State wrong = new Token.State(ADMIN, p.stored().supply(), new MerkleMap(s.balances().root(), none, none));
        h.assertRejected(act(s, new TokenValidator.Transfer(BEN, BigInteger.TEN, ctx), p, wrong, ANN, ctx),
                "datum keeps the old root");
    }

    @Test
    void readsAndWritesInTheDatum_rejected() {
        Token.State s = seeded();
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var p = call(s, x -> Token.transfer(x, BEN, BigInteger.TEN, ctx));
        JulcList<MapEntry> junk = JulcList.<MapEntry>empty().prepend(new MapEntry(ANN, true, intBytes(1)));
        Token.State dirty = new Token.State(ADMIN, p.stored().supply(),
                new MerkleMap(p.stored().balances().root(), junk, JulcList.empty()));
        h.assertRejected(act(s, new TokenValidator.Transfer(BEN, BigInteger.TEN, ctx), p, dirty, ANN, ctx),
                "datum map with a non-empty read set");
    }

    @Test
    void manyHolders_datumStaysSmall() {
        Token.State s = seeded();
        for (int i = 0; i < 200; i++) {
            byte[] holder = StateHarness.fill(28, i);
            s = call(s, x -> Token.mint(x, holder, BigInteger.ONE)).stored();
        }
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        Token.State before = s;
        var p = call(before, x -> Token.transfer(x, BEN, BigInteger.ONE, ctx));
        var v = h.assertAccepted(act(before, new TokenValidator.Transfer(BEN, BigInteger.ONE, ctx), p, ANN, ctx));
        int datumBytes = StateHarness.data(p.stored()).toString().length();
        System.out.println("[transfer among 203 holders] " + v + "  datum ~" + datumBytes + " chars");
    }

    /** A view, as the client runs it: reads served from the mirror. */
    BigInteger balance(Token.State s, byte[] who) {
        return new MerkleSupport(store).run(() -> Token.balanceOf(s, who));
    }

    static byte[] intBytes(long v) {
        return org.julclang.stdlib.lib.ByteStringLib.integerToByteString(true, 0, BigInteger.valueOf(v));
    }
}
