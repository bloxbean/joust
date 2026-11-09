package org.julclang.state.examples.guild;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxOutRef;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.RequireException;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guild (ADR-0012): two owned types told apart by their datum's constructor (I42), per-type invariants, and a hero
 * moving between guilds with the destination's consent (I43).
 */
class GuildTest {
    static final byte[] MASTER = StateHarness.fill(28, 0xAD);
    static final byte[] ANN = StateHarness.fill(28, 0xA1);
    static final byte[] EVE = StateHarness.fill(28, 0xEE);
    static final BigInteger TWO = BigInteger.TWO;

    final StateHarness<Guild.State> h = StateHarness.of(GuildClient.SPEC);
    final Guild.State open = new Guild.State(MASTER, true, BigInteger.ZERO, BigInteger.ONE);
    final Guild.State closed = new Guild.State(MASTER, false, BigInteger.ZERO, BigInteger.ONE);

    /** The destination guild of a move: another instance at its own UTxO. */
    static final byte[] B = StateHarness.fill(32, 0xB2);
    static final TxOutRef B_REF = new TxOutRef(new TxId(StateHarness.fill(32, 0x0C)), BigInteger.ZERO);

    GuildValidator.Holding_Hero hero(byte[] instance, Guild.Hero v) {
        return new GuildValidator.Holding_Hero(instance, v);
    }

    GuildValidator.Holding_Item item(byte[] instance, Guild.Item v) {
        return new GuildValidator.Holding_Item(instance, v);
    }

    @Test
    void recruitAndForge() {
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var tx = h.createObject(new GuildValidator.Recruit(h.seed(), h.instanceId(), ctx), hero(h.instanceId(),
                Guild.recruit(open, ctx)), open, ctx);
        System.out.println("[recruit] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        var forge = h.createObject(new GuildValidator.Forge(h.seed(), h.instanceId(), BigInteger.valueOf(7), ctx),
                item(h.instanceId(), Guild.forge(open, BigInteger.valueOf(7), ctx)), open, ctx);
        System.out.println("[forge] " + h.assertAccepted(forge) + "  attacks rejected: " + Attacks.assertAllRejected(h, forge));
    }

    @Test
    void creatingTheWrongType_rejected() {
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        // Forge (an Item creation) whose output is a Hero
        h.assertRejected(h.createObject(new GuildValidator.Forge(h.seed(), h.instanceId(), BigInteger.valueOf(7), ctx),
                hero(h.instanceId(), new Guild.Hero(ANN, BigInteger.valueOf(7))), open, ctx), "Forge creating a Hero");
    }

    @Test
    void levelUpAndEnchant() {
        var hv = new Guild.Hero(ANN, BigInteger.ONE);
        var obj = h.liveObject(hero(h.instanceId(), hv), BigInteger.ZERO);
        var tx = h.actObject(obj, new GuildValidator.LevelUp(), hero(h.instanceId(), Guild.levelUp(hv)), JulcList.empty(),
                ANN, null, null);
        System.out.println("[levelUp] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        var iv = new Guild.Item(ANN, BigInteger.valueOf(7));
        var it = h.liveObject(item(h.instanceId(), iv), BigInteger.ZERO);
        var en = h.actObject(it, new GuildValidator.Enchant(BigInteger.TEN), item(h.instanceId(), Guild.enchant(iv, BigInteger.TEN)),
                JulcList.empty(), ANN, null, null);
        System.out.println("[enchant] " + h.assertAccepted(en) + "  attacks rejected: " + Attacks.assertAllRejected(h, en));
    }

    @Test
    void typeConfusion_rejected() {
        // I42: a hero's method on an item, and an item's method on a hero, even with outputs crafted to match
        var iv = new Guild.Item(ANN, BigInteger.valueOf(7));
        var it = h.liveObject(item(h.instanceId(), iv), BigInteger.ZERO);
        h.assertRejected(h.actObject(it, new GuildValidator.LevelUp(), hero(h.instanceId(), new Guild.Hero(ANN, BigInteger.valueOf(8))),
                JulcList.empty(), ANN, null, null), "LevelUp on an Item");
        h.assertRejected(h.actObject(it, new GuildValidator.LevelUp(), item(h.instanceId(), new Guild.Item(ANN, BigInteger.valueOf(8))),
                JulcList.empty(), ANN, null, null), "LevelUp on an Item, item output");
        var hv = new Guild.Hero(ANN, BigInteger.valueOf(3));
        var ho = h.liveObject(hero(h.instanceId(), hv), BigInteger.ZERO);
        h.assertRejected(h.actObject(ho, new GuildValidator.Enchant(BigInteger.TEN), item(h.instanceId(), new Guild.Item(ANN,
                BigInteger.valueOf(13))), JulcList.empty(), ANN, null, null), "Enchant on a Hero");
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, it.lovelace());
        h.assertRejected(h.destroyObject(it, new GuildValidator.Retire(ctx), new GuildValidator.BurnInstance(),
                Guild.scrap(iv, ctx), ANN, ctx), "Retire (a hero's destroy) on an Item");
    }

    @Test
    void invariantsAreChecked_perType() {
        var iv = new Guild.Item(ANN, BigInteger.valueOf(95));
        var it = h.liveObject(item(h.instanceId(), iv), BigInteger.ZERO);
        h.assertRejected(h.actObject(it, new GuildValidator.Enchant(BigInteger.TEN), item(h.instanceId(), Guild.enchant(iv,
                BigInteger.TEN)), JulcList.empty(), ANN, null, null), "item power over 100 (itemPower)");
    }

    // ------------------------------------------------------------------ moves (I43)

    /** A level-2 hero of the harness guild moving to guild B, which admits it. */
    TxModel move(Guild.State source, Guild.State destination, byte[] into, byte[] admitted, byte[] continuesUnder) {
        var hv = new Guild.Hero(ANN, TWO);
        var obj = h.liveObject(hero(h.instanceId(), hv), BigInteger.ZERO);
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var tx = h.actObject(obj, new GuildValidator.Transfer(into, ctx), hero(continuesUnder, hv), JulcList.empty(), ANN, ctx,
                source);
        var admittedState = new Guild.State(destination.master(), destination.open(), destination.arrivals().add(BigInteger.ONE),
                destination.minLevel());
        return h.withInstance(tx, B, B_REF, destination, new GuildValidator.Admit(admitted, ctx), admittedState,
                BigInteger.valueOf(h.minAda()));
    }

    @Test
    void move_admittedByTheDestination() {
        var tx = move(open, open, B, h.objectId(), B);
        System.out.println("[move] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        // the JVM runs both sides too
        assertEquals(TWO, Guild.transfer(new Guild.Hero(ANN, TWO), open, open, h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO)).level());
    }

    @Test
    void move_withoutTheDestination_orWithAnotherAction_rejected() {
        var hv = new Guild.Hero(ANN, TWO);
        var obj = h.liveObject(hero(h.instanceId(), hv), BigInteger.ZERO);
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var alone = h.actObject(obj, new GuildValidator.Transfer(B, ctx), hero(B, hv), JulcList.empty(), ANN, ctx, open);
        h.assertRejected(alone, "the destination is not spent");
        var other = h.withInstance(alone, B, B_REF, open, new GuildValidator.SetOpen(true), open, BigInteger.valueOf(h.minAda()));
        h.assertRejected(other, "the destination is spent with SetOpen, not @Admits");
    }

    @Test
    void move_bindingBroken_rejected() {
        h.assertRejected(move(open, open, B, StateHarness.fill(29, 0x4F), B), "the destination admits another object");
        h.assertRejected(move(open, open, B, h.objectId(), h.instanceId()), "the hero stays under the source");
        h.assertRejected(move(open, open, h.instanceId(), h.objectId(), h.instanceId()), "a move into its own instance");
        h.assertRejected(move(open, open, StateHarness.fill(29, 0x4F), h.objectId(), StateHarness.fill(29, 0x4F)),
                "into an object id");
    }

    @Test
    void move_refusedByEitherSide() {
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        assertThrows(RequireException.class, () -> Guild.transfer(new Guild.Hero(ANN, TWO), closed, open, ctx));
        h.assertRejected(move(closed, open, B, h.objectId(), B), "the source is closed");
        var level2 = new Guild.Hero(ANN, TWO);
        assertThrows(RequireException.class, () -> Guild.admit(closed, h.objectId(), level2, ctx));
        h.assertRejected(move(open, closed, B, h.objectId(), B), "the destination is closed");
        // The destination sees the arriving hero (from its input) and requires level 5: a level-2 hero is refused.
        var elite = new Guild.State(MASTER, true, BigInteger.ZERO, BigInteger.valueOf(5));
        assertThrows(RequireException.class, () -> Guild.admit(elite, h.objectId(), level2, ctx));
        h.assertRejected(move(open, elite, B, h.objectId(), B), "the hero's level is below the destination's minimum");
    }

    @Test
    void admit_withoutAMove_rejected() {
        // the hero is spent with LevelUp, and guild B "admits" it
        var hv = new Guild.Hero(ANN, TWO);
        var obj = h.liveObject(hero(h.instanceId(), hv), BigInteger.ZERO);
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var tx = h.actObject(obj, new GuildValidator.LevelUp(), hero(h.instanceId(), Guild.levelUp(hv)), JulcList.empty(), ANN,
                ctx, null);
        var admitted = new Guild.State(MASTER, true, BigInteger.ONE, BigInteger.ONE);
        h.assertRejected(h.withInstance(tx, B, B_REF, open, new GuildValidator.Admit(h.objectId(), ctx), admitted,
                BigInteger.valueOf(h.minAda())), "an admit with no move");
    }

    @Test
    void admitsAreBoundToTheirType() {
        // M1: a hero cannot arrive through the item admit (which skips the cap), nor an item through the hero admit
        var hv = new Guild.Hero(ANN, TWO);
        var obj = h.liveObject(hero(h.instanceId(), hv), BigInteger.ZERO);
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        var tx = h.actObject(obj, new GuildValidator.Transfer(B, ctx), hero(B, hv), JulcList.empty(), ANN, ctx, open);
        // admitItem checks no level: a level-2 hero must not enter a level-5 guild through it
        var elite = new Guild.State(MASTER, true, BigInteger.ZERO, BigInteger.valueOf(5));
        h.assertRejected(h.withInstance(tx, B, B_REF, elite, new GuildValidator.AdmitItem(h.objectId(), ctx), elite,
                BigInteger.valueOf(h.minAda())), "a hero through admitItem");
        var iv = new Guild.Item(ANN, BigInteger.valueOf(7));
        var it = h.liveObject(item(h.instanceId(), iv), BigInteger.ZERO);
        var carry = h.actObject(it, new GuildValidator.Carry(B, ctx), item(B, iv), JulcList.empty(), ANN, ctx, null);
        var admitted = new Guild.State(MASTER, true, BigInteger.ONE, BigInteger.ONE);
        h.assertRejected(h.withInstance(carry, B, B_REF, open, new GuildValidator.Admit(h.objectId(), ctx), admitted,
                BigInteger.valueOf(h.minAda())), "an item through the hero admit");
        var ok = h.withInstance(carry, B, B_REF, open, new GuildValidator.AdmitItem(h.objectId(), ctx), open,
                BigInteger.valueOf(h.minAda()));
        System.out.println("[carry] " + h.assertAccepted(ok) + "  attacks rejected: " + Attacks.assertAllRejected(h, ok));
    }
}
