package org.julclang.state.examples.guild;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.MerkleMaps;
import org.julclang.state.api.RequireException;
import org.julclang.state.runtime.InMemoryNodeStore;
import org.julclang.state.runtime.MerkleSupport;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Armory (ADR-0012 D2): MerkleMaps inside owned objects, witnessed by the map verifier and keyed by the object's input. */
class ArmoryTest {
    static final byte[] KEEPER = StateHarness.fill(28, 0xAD);
    static final byte[] ANN = StateHarness.fill(28, 0xA1);

    final StateHarness<Armory.State> h = StateHarness.of(ArmoryClient.SPEC);
    final Armory.State armory = new Armory.State(KEEPER, true);
    final InMemoryNodeStore store = new InMemoryNodeStore();

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    ArmoryValidator.Holding vault(Armory.Vault v) {
        return new ArmoryValidator.Holding(h.instanceId(), v);
    }

    static final byte[] SWORD = "sword".getBytes();
    static final byte[] GEM = "gem".getBytes();

    /** A vault with 3 swords and 5 gems, built through real calls so the mirror holds its trie. */
    Armory.Vault filled() {
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var ms = new MerkleSupport(store);
        var made = ms.run(() -> Armory.openVault(armory, ctx));
        Armory.Vault v = ms.prepare(Armory.Vault.class, made, made, h.seed()).stored();
        v = step(v, x -> Armory.stash(x, SWORD, BigInteger.valueOf(3))).stored();
        return step(v, x -> Armory.stash(x, GEM, BigInteger.valueOf(5))).stored();
    }

    MerkleSupport.Prepared<Armory.Vault> step(Armory.Vault v, java.util.function.Function<Armory.Vault, Armory.Vault> f) {
        var ms = new MerkleSupport(store);
        var next = ms.run(() -> f.apply(v));
        return ms.prepare(Armory.Vault.class, v, next, StateHarness.OBJECT_REF);
    }

    @Test
    void vault_openWithAnEmptyMap() {
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var ms = new MerkleSupport(store);
        var made = ms.run(() -> Armory.openVault(armory, ctx));
        var p = ms.prepare(Armory.Vault.class, made, made, h.seed());
        var tx = h.withClaims(h.createObject(new ArmoryValidator.OpenVault(h.seed(), h.instanceId(), ctx), vault(p.stored()),
                armory, ctx), p.claims());
        System.out.println("[openVault] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void vault_stashAndTake_witnessedByTheMapVerifier() {
        var v = filled();
        assertEquals(BigInteger.TWO, v.kinds());
        var obj = h.liveObject(vault(v), BigInteger.ZERO);
        var p = step(v, x -> Armory.take(x, SWORD, BigInteger.valueOf(3)));
        var tx = h.withClaims(h.actObject(obj, new ArmoryValidator.Take(SWORD, BigInteger.valueOf(3)), vault(p.stored()),
                JulcList.empty(), ANN, null, null), p.claims());
        System.out.println("[take] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        assertEquals(BigInteger.ONE, p.stored().kinds());
        // The datum holds the root only, and the vault's claim is owned by its own input.
        assertTrue(p.stored().inventory().reads().isEmpty());
        assertEquals(StateHarness.OBJECT_REF, p.claims().get(0).owner());
    }

    @Test
    void vault_forgedInventory_rejected() {
        var v = filled();
        var obj = h.liveObject(vault(v), BigInteger.ZERO);
        var p = step(v, x -> Armory.stash(x, GEM, BigInteger.ONE));
        // the output claims a different map root than the verified writes
        var forged = new Armory.Vault(ANN, v.kinds(), MerkleMaps.empty());
        h.assertRejected(h.withClaims(h.actObject(obj, new ArmoryValidator.Stash(GEM, BigInteger.ONE), vault(forged),
                JulcList.empty(), ANN, null, null), p.claims()), "a root the verifier did not compute");
        h.assertRejected(h.actObject(obj, new ArmoryValidator.Stash(GEM, BigInteger.ONE), vault(p.stored()), JulcList.empty(),
                ANN, null, null), "no map verifier claims");
        // a claim owned by another input
        var other = p.claims().stream().map(c -> new org.julclang.state.api.mpf.MapClaim(StateHarness.LIVE_REF, c.field(),
                c.oldRoot(), c.reads(), c.writes(), c.newRoot())).toList();
        h.assertRejected(h.withClaims(h.actObject(obj, new ArmoryValidator.Stash(GEM, BigInteger.ONE), vault(p.stored()),
                JulcList.empty(), ANN, null, null), other), "a claim keyed to another input");
    }

    @Test
    void vault_closeOnlyWhenEmpty() {
        var v = filled();
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.valueOf(h.minAda()));
        assertThrows(RequireException.class, () -> Armory.closeVault(v, ctx));
        var empty = step(step(v, x -> Armory.take(x, SWORD, BigInteger.valueOf(3))).stored(),
                x -> Armory.take(x, GEM, BigInteger.valueOf(5))).stored();
        var obj = h.liveObject(vault(empty), BigInteger.ZERO);
        var ms = new MerkleSupport(store);
        var pay = ms.run(() -> Armory.closeVault(empty, ctx));
        var p = ms.prepare(Armory.Vault.class, empty, null, StateHarness.OBJECT_REF);
        var tx = h.withClaims(h.destroyObject(obj, new ArmoryValidator.CloseVault(ctx), new ArmoryValidator.BurnInstance(), pay,
                ANN, ctx), p.claims());
        System.out.println("[closeVault] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }
}
