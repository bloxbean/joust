package org.julclang.state.examples.guild;

import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.InstanceNotFoundException;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Heroes and items in two guilds on a live Yaci DevKit; a hero moves between guilds (ADR-0012). */
@Tag("devnet")
class GuildDevnetTest {

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void heroesItemsAndAMove() {
        var runtime = DevKit.runtime();
        var master = DevKit.fundedAccount(300);
        var other = DevKit.fundedAccount(300);
        var ann = DevKit.fundedAccount(300);

        var a = GuildClient.deploy(runtime, master, BigInteger.TEN);
        var b = GuildClient.deploy(runtime, other, BigInteger.ONE);

        var hero = a.recruit(ann);
        assertArrayEquals(Keys.pkh(ann), hero.value().owner());
        var sword = a.forge(ann, BigInteger.valueOf(7));
        hero.levelUp(ann);
        sword.enchant(ann, BigInteger.valueOf(20));
        assertEquals(BigInteger.TWO, hero.value().level());
        assertEquals(BigInteger.valueOf(27), sword.value().power());
        assertThrows(RequireException.class, () -> sword.enchant(ann, BigInteger.valueOf(80)), "the itemPower invariant");

        // A move needs the destination's consent in the same transaction.
        assertThrows(JoustException.class, () -> hero.transfer(ann, b));
        var moved = hero.transferAdmitted(ann, b);
        assertEquals(BigInteger.ONE, b.state().arrivals());
        assertEquals(BigInteger.TWO, moved.value().level());
        System.out.println("[guild] hero " + moved.objectId() + " moved to guild " + b.instanceId());
        // The handle from guild A follows the hero: its datum names guild B now.
        hero.levelUp(ann);
        assertEquals(BigInteger.valueOf(3), moved.value().level());

        // Nested: the move-and-admit joins an outer composition with another call, in one transaction.
        var c = GuildClient.deploy(runtime, other, BigInteger.TEN);
        Compose.of(runtime, ann).allowSharedSigners()
                .add(() -> moved.transferAdmitted(ann, c))
                .add(() -> sword.enchant(ann, BigInteger.ONE))
                .submit();
        assertEquals(BigInteger.ONE, c.state().arrivals());
        assertEquals(BigInteger.valueOf(28), sword.value().power());

        // Guild B is full now: its admit refuses the next hero.
        var second = a.recruit(ann);
        second.levelUp(ann);
        assertThrows(RequireException.class, () -> second.transferAdmitted(ann, b), "the destination is full");

        // Closing the source keeps heroes in, but the escape hatch still works.
        a.setOpen(master, false);
        assertThrows(RequireException.class, () -> second.transferAdmitted(ann, b), "the source is closed");
        second.retire(ann);
        moved.retire(ann);
        sword.scrap(ann);
        assertThrows(InstanceNotFoundException.class, moved::value);
        assertThrows(InstanceNotFoundException.class, sword::value);
    }

    @Test
    void vaultWithAMerkleMapInventory() {
        var runtime = DevKit.runtime();
        var keeper = DevKit.fundedAccount(300);
        var ann = DevKit.fundedAccount(300);
        var armory = ArmoryClient.deploy(runtime, keeper);
        var vault = armory.openVault(ann);
        byte[] sword = "sword".getBytes();
        byte[] gem = "gem".getBytes();
        vault.stash(ann, sword, BigInteger.valueOf(3));
        vault.stash(ann, gem, BigInteger.valueOf(5));
        vault.take(ann, sword, BigInteger.ONE);
        assertEquals(BigInteger.TWO, vault.value().kinds());
        assertThrows(RequireException.class, () -> vault.take(ann, gem, BigInteger.TEN), "not that many");
        assertThrows(RequireException.class, () -> vault.closeVault(ann), "not empty");
        // Two vaults' map claims in one composed transaction: one verifier withdrawal carries both.
        var spare = armory.openVault(ann);
        Compose.of(runtime, ann).allowSharedSigners()
                .add(() -> vault.stash(ann, gem, BigInteger.ONE))
                .add(() -> spare.stash(ann, sword, BigInteger.ONE))
                .submit();
        assertEquals(BigInteger.ONE, spare.value().kinds());
        spare.take(ann, sword, BigInteger.ONE);
        spare.closeVault(ann);
        vault.take(ann, gem, BigInteger.ONE);
        vault.take(ann, sword, BigInteger.TWO);
        vault.take(ann, gem, BigInteger.valueOf(5));
        assertEquals(BigInteger.ZERO, vault.value().kinds());
        vault.closeVault(ann);
        assertThrows(InstanceNotFoundException.class, vault::value);
    }
}
