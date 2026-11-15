package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.Interpreter.AddrV;
import org.julclang.state.dsl.v2.Interpreter.BigMapV;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.Interpreter.ValueV;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.julclang.state.toolchain.FourWay.rec;
import static org.julclang.state.toolchain.JscV2M1aFourWayTest.*;
import static org.julclang.state.toolchain.JscV2M2FourWayTest.keyCred;
import static org.julclang.state.toolchain.JscV2M2FourWayTest.vv;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3a3 (J3, J11) on all five legs: moves between instances (ADR-0012 D3) and invariants by type. Each half of a
 * move is checked with the other half in its transaction (FourWay's partner): a move continues its object under the
 * destination, which admits it with an admits action in the same transaction; an admits action sees the arriving object
 * as its input holds it. Guild (heroes and items, two moves, invariants by type), Armory (an owned BigMap without
 * assets) and Market (listings that move between markets, a payable owned destroy with a royalty send) are the ports of
 * examples/guild and examples/market.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JscV2M3a3FourWayTest {

    static final Map<String, FourWay> LEGS = new LinkedHashMap<>();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] MASTER = fill(0xAD), ANN = fill(0xA1), BEN = fill(0xB2), EVE = fill(0xEE);
    static final byte[] POLICY = fill(0x5E), NFT = "nft".getBytes();

    static BigInteger ada(long v) {
        return ADA.multiply(BigInteger.valueOf(v));
    }

    static String ok(String outcome) {
        assertTrue(outcome.startsWith("ok:"), outcome);
        return outcome;
    }

    @BeforeAll
    static void build() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!LEGS.isEmpty()) return;
        Map<String, String> files = new LinkedHashMap<>();
        for (String c : List.of("Guild", "Armory", "Market", "Ferry")) files.put("org/julclang/state/examples/dsl/" + c + ".jsc", Files.readString(DSL.resolve(c + ".jsc")));
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        for (var c : r.contracts()) {
            System.out.println("[M3a3] " + c.name() + ": " + c.scriptSizeBytes() + " bytes");
            LEGS.put(c.name(), new FourWay(c.name(), files.get("org/julclang/state/examples/dsl/" + c.name() + ".jsc"), c.transitions()));
        }
    }

    static void report(FourWay f) {
        System.out.println("[M3a3 five-way] " + f.name + ": " + f.calls + " calls, interpreter = JVM = transition program = validator (" + f.refusals
                + " refusals, " + f.validatorRuns + " validator runs); mutants rejected: " + f.mutantsRejected + ", attacks rejected: "
                + f.attacksRejected + ", modifier refusals: " + f.modifierRefusals);
        assertTrue(f.refusals > 0 && f.refusals < f.calls, "accepted and refused calls");
        assertTrue(f.mutantsRejected > 0 && f.attacksRejected > 0, "mutation and attack coverage");
    }

    // ------------------------------------------------------------------ Guild (port): two types, moves, invariants by type

    static Rec guild(boolean open, long arrivals, long minLevel) {
        return rec("State", Bytes.of(MASTER), open, n(arrivals), n(minLevel));
    }

    static Rec hero(long level) {
        return rec("Hero", Bytes.of(ANN), n(level));
    }

    static Rec item(long power) {
        return rec("Item", Bytes.of(ANN), n(power));
    }

    @Test
    @Order(1)
    void guild() throws Exception {
        var f = LEGS.get("Guild");
        Rec open = guild(true, 0, 1), closed = guild(false, 0, 1), elite = guild(true, 0, 5);
        f.defaultShared = open;
        ok(f.check("init", null, List.of(n(1)), at(MASTER, 0, 0), MASTER));
        assertEquals("refused", f.check("init", null, List.of(n(0)), at(MASTER, 0, 0), MASTER, guild(true, 0, 0)));
        ok(f.check("setOpen", open, List.of(false), null, MASTER));
        f.modifierRefused("setOpen", open, List.of(false), null, EVE);
        // Heroes: recruited while the guild is open; levelled up by their owner.
        ok(f.check("recruit", null, List.of(), at(ANN, 0, 0).withShared(open), ANN));
        assertEquals("refused", f.check("recruit", null, List.of(), at(ANN, 0, 0).withShared(closed), ANN, hero(1)));
        ok(f.check("levelUp", hero(1), List.of(), null, ANN));
        f.modifierRefused("levelUp", hero(1), List.of(), null, EVE);
        // A move: transfer from an open guild from level 2, admitted by the destination (both halves in one transaction).
        Tx moving = at(ANN, 0, 0).withShared(open).withInto(open);
        f.partner = new FourWay.Partner("admit", open, List.of(), null, null);
        ok(f.check("transfer", hero(2), List.of(), moving, ANN));
        ok(f.check("transfer", hero(7), List.of(), moving, ANN));
        assertEquals("refused", f.check("transfer", hero(1), List.of(), moving, ANN));
        assertEquals("refused", f.check("transfer", hero(2), List.of(), at(ANN, 0, 0).withShared(closed).withInto(open), ANN));
        f.modifierRefused("transfer", hero(2), List.of(), at(EVE, 0, 0).withShared(open).withInto(open), EVE);
        // A24 (ADR-0018 r25, narrowed after #107 review C1): each half is its own call with its own refusal class. The
        // move's half from a closed guild is refused with its own (the source's) class on the interpreters, the JVM and
        // the transition program, and the validator refuses the transaction (whose admit, into a closed guild, would
        // refuse too); the admit's half, checked from its side, reports the destination's class (admitItem below).
        f.partner = new FourWay.Partner("admit", closed, List.of(), null, null);
        assertEquals("refused", f.check("transfer", hero(2), List.of(), at(ANN, 0, 0).withShared(closed).withInto(closed), ANN));
        assertTrue(f.lastRefusal.endsWith(":the source guild is closed"), f.lastRefusal);
        f.partner = new FourWay.Partner("admit", open, List.of(), null, null);
        // The other half: the destination admits a hero of at least its level, while open.
        f.partner = new FourWay.Partner("transfer", hero(2), List.of(), open, open);
        ok(f.check("admit", open, List.of(Bytes.of(f.harness.objectId())), at(ANN, 0, 0).withArriving(hero(2)), ANN));
        f.partner = new FourWay.Partner("transfer", hero(2), List.of(), open, elite);
        assertEquals("refused", f.check("admit", elite, List.of(Bytes.of(f.harness.objectId())), at(ANN, 0, 0).withArriving(hero(2)), ANN));
        f.partner = new FourWay.Partner("transfer", hero(2), List.of(), open, closed);
        assertEquals("refused", f.check("admit", closed, List.of(Bytes.of(f.harness.objectId())), at(ANN, 0, 0).withArriving(hero(2)), ANN));
        ok(f.check("retire", hero(3), List.of(), at(ANN, 0, f.harness.minAda()), ANN));
        // Items: forged with power 1 to 10, enchanted up to the invariant (power at most 100), carried to another guild.
        ok(f.check("forge", null, List.of(n(10)), at(ANN, 0, 0).withShared(open), ANN));
        assertEquals("refused", f.check("forge", null, List.of(n(11)), at(ANN, 0, 0).withShared(open), ANN, item(11)));
        ok(f.check("enchant", item(10), List.of(n(90)), null, ANN));
        f.invariantRefused("invariant:itemPower", "enchant", item(10), List.of(n(91)), null, ANN);
        f.partner = new FourWay.Partner("admitItem", open, List.of(), null, null);
        ok(f.check("carry", item(5), List.of(), at(ANN, 0, 0).withInto(open), ANN));
        f.partner = new FourWay.Partner("carry", item(5), List.of(), null, open);
        ok(f.check("admitItem", open, List.of(Bytes.of(f.harness.objectId())), at(ANN, 0, 0).withArriving(item(5)), ANN));
        f.partner = new FourWay.Partner("carry", item(5), List.of(), null, closed);
        assertEquals("refused", f.check("admitItem", closed, List.of(Bytes.of(f.harness.objectId())), at(ANN, 0, 0).withArriving(item(5)), ANN));
        ok(f.check("scrap", item(5), List.of(), at(ANN, 0, f.harness.minAda()), ANN));
        ok(f.check("open", open, List.of(), null, null));
        // Mutants and the attack suite, each move with its other half.
        f.mutants("recruit", null, List.of(), at(ANN, 0, 0).withShared(open), ANN);
        f.mutants("levelUp", hero(1), List.of(), null, ANN);
        f.partner = new FourWay.Partner("admit", open, List.of(), null, null);
        f.mutants("transfer", hero(2), List.of(), moving, ANN);
        f.partner = new FourWay.Partner("transfer", hero(2), List.of(), open, open);
        f.mutants("admit", open, List.of(Bytes.of(f.harness.objectId())), at(ANN, 0, 0).withArriving(hero(2)), ANN);
        f.mutants("enchant", item(10), List.of(n(90)), null, ANN);
        f.partner = new FourWay.Partner("admitItem", open, List.of(), null, null);
        f.mutants("carry", item(5), List.of(), at(ANN, 0, 0).withInto(open), ANN);
        f.mutants("retire", hero(3), List.of(), at(ANN, 0, f.harness.minAda()), ANN);
        f.partner = null;
        JsLeg.check(f);
        report(f);
    }

    // ------------------------------------------------------------------ Armory (port): an owned BigMap without assets

    static BigMapV map(Object... kv) {
        var m = new LinkedHashMap<Bytes, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put(Bytes.of((byte[]) kv[i]), BigInteger.valueOf(((Number) kv[i + 1]).longValue()));
        return BigMapV.of(m);
    }

    static Rec vault(long kinds, BigMapV inventory) {
        return rec("Vault", Bytes.of(ANN), n(kinds), inventory);
    }

    @Test
    @Order(2)
    void armory() throws Exception {
        var f = LEGS.get("Armory");
        Rec open = rec("State", Bytes.of(MASTER), true), closed = rec("State", Bytes.of(MASTER), false);
        f.defaultShared = open;
        byte[] sword = "sword".getBytes(), shield = "shield".getBytes();
        ok(f.check("init", null, List.of(), at(MASTER, 0, 0), MASTER));
        ok(f.check("openVault", null, List.of(), at(ANN, 0, 0).withShared(open), ANN));
        assertEquals("refused", f.check("openVault", null, List.of(), at(ANN, 0, 0).withShared(closed), ANN, vault(0, map())));
        Rec some = vault(1, map(sword, 3));
        ok(f.check("stash", some, List.of(Bytes.of(sword), n(2)), null, ANN));
        ok(f.check("stash", some, List.of(Bytes.of(shield), n(1)), null, ANN));
        assertEquals("refused", f.check("stash", some, List.of(Bytes.of(sword), n(0)), null, ANN));
        f.modifierRefused("stash", some, List.of(Bytes.of(sword), n(2)), null, EVE);
        ok(f.check("take", some, List.of(Bytes.of(sword), n(3)), null, ANN));
        ok(f.check("take", some, List.of(Bytes.of(sword), n(1)), null, ANN));
        assertEquals("refused", f.check("take", some, List.of(Bytes.of(sword), n(4)), null, ANN));
        assertEquals("refused", f.check("take", some, List.of(Bytes.of(shield), n(1)), null, ANN));
        ok(f.check("closeVault", vault(0, map()), List.of(), at(ANN, 0, f.harness.minAda()), ANN));
        assertEquals("refused", f.check("closeVault", some, List.of(), at(ANN, 0, f.harness.minAda()), ANN));
        f.mutants("openVault", null, List.of(), at(ANN, 0, 0).withShared(open), ANN);
        f.mutants("stash", some, List.of(Bytes.of(shield), n(1)), null, ANN);
        f.mutants("take", some, List.of(Bytes.of(sword), n(1)), null, ANN);
        f.mutants("closeVault", vault(0, map()), List.of(), at(ANN, 0, f.harness.minAda()), ANN);
        JsLeg.check(f);
        report(f);
    }

    // ------------------------------------------------------------------ Market (port): moving listings, a payable destroy

    static Rec market(boolean paused, long feeBps, long royaltyBps) {
        return rec("State", Bytes.of(MASTER), n(feeBps), new AddrV(keyCred(fill(0x77)), Opt.NONE), n(royaltyBps), paused);
    }

    static Rec listing(long price) {
        return rec("Listing", Bytes.of(ANN), Bytes.of(POLICY), Bytes.of(NFT), n(price));
    }

    static ValueV nft() {
        return vv(POLICY, NFT, BigInteger.ONE);
    }

    @Test
    @Order(3)
    void market() throws Exception {
        var f = LEGS.get("Market");
        Rec open = market(false, 200, 300), paused = market(true, 200, 300), free = market(false, 0, 0);
        f.defaultShared = open;
        ok(f.check("init", null, List.of(n(200), new AddrV(keyCred(fill(0x77)), Opt.NONE), n(300)), at(MASTER, 0, 0), MASTER));
        assertEquals("refused", f.check("init", null, List.of(n(5_000), new AddrV(keyCred(fill(0x77)), Opt.NONE), n(5_000)), at(MASTER, 0, 0), MASTER,
                market(false, 5_000, 5_000)));
        ok(f.check("setPaused", open, List.of(true), null, MASTER));
        // Offering: the listing takes the NFT in (an owned init that accepts).
        ok(f.check("offer", null, List.of(Bytes.of(POLICY), Bytes.of(NFT), ada(5)), f.objectFunds(at(ANN, 0, 0), nft(), ValueV.NONE).withShared(open), ANN));
        assertEquals("refused", f.check("offer", null, List.of(Bytes.of(POLICY), Bytes.of(NFT), ada(5)),
                f.objectFunds(at(ANN, 0, 0), nft(), ValueV.NONE).withShared(paused), ANN, listing(ada(5).longValue())));
        assertEquals("refused", f.check("offer", null, List.of(Bytes.of(POLICY), Bytes.of(NFT), ada(1)),
                f.objectFunds(at(ANN, 0, 0), nft(), ValueV.NONE).withShared(open), ANN, listing(ada(1).longValue())));
        assertEquals("refused", f.check("offer", null, List.of(Bytes.of(POLICY), Bytes.of(NFT), ada(5)),
                f.objectFunds(at(ANN, 0, 0), vv(POLICY, NFT, BigInteger.ONE, fill(0x66), NFT, BigInteger.ONE), ValueV.NONE).withShared(open), ANN,
                listing(ada(5).longValue())));
        // Buying: pay exactly the price; the seller is paid less fee and royalty, the royalty sent with its datum.
        Rec l5 = listing(ada(5).longValue());
        long lovelace = f.harness.minAda();
        Tx buying = f.objectFunds(at(BEN, ada(5).longValue(), lovelace), ValueV.NONE, nft()).withShared(open);
        ok(f.check("buy", l5, List.of(), buying, BEN));
        ok(f.check("buy", l5, List.of(), f.objectFunds(at(BEN, ada(5).longValue(), lovelace), ValueV.NONE, nft()).withShared(free), BEN));
        assertEquals("refused", f.check("buy", l5, List.of(), f.objectFunds(at(BEN, ada(4).longValue(), lovelace), ValueV.NONE, nft()).withShared(open), BEN));
        assertEquals("refused", f.check("buy", l5, List.of(), f.objectFunds(at(ANN, ada(5).longValue(), lovelace), ValueV.NONE, nft()).withShared(open), ANN));
        assertEquals("refused", f.check("buy", l5, List.of(), f.objectFunds(at(BEN, ada(5).longValue(), lovelace), ValueV.NONE, nft()).withShared(paused), BEN));
        // Cancelling: the escape hatch, whatever the market's state.
        ok(f.check("cancel", l5, List.of(), f.objectFunds(at(ANN, 0, lovelace), ValueV.NONE, nft()), ANN));
        f.modifierRefused("cancel", l5, List.of(), f.objectFunds(at(EVE, 0, lovelace), ValueV.NONE, nft()), EVE);
        // Relisting: a move to another market at a new price, which that market admits unless paused.
        f.partner = new FourWay.Partner("enlist", open, List.of(), null, null);
        Tx relisting = f.objectFunds(at(ANN, 0, 0), ValueV.NONE, ValueV.NONE).withInto(open);
        ok(f.check("relist", l5, List.of(ada(7)), relisting, ANN));
        assertEquals("refused", f.check("relist", l5, List.of(ada(1)), relisting, ANN));
        f.modifierRefused("relist", l5, List.of(ada(7)), f.objectFunds(at(EVE, 0, 0), ValueV.NONE, ValueV.NONE).withInto(open), EVE);
        f.partner = new FourWay.Partner("relist", l5, List.of(ada(7)), null, open);
        ok(f.check("enlist", open, List.of(Bytes.of(f.harness.objectId())), f.funds(at(ANN, 0, 0), ValueV.NONE, ValueV.NONE).withArriving(l5), ANN));
        f.partner = new FourWay.Partner("relist", l5, List.of(ada(7)), null, paused);
        assertEquals("refused", f.check("enlist", paused, List.of(Bytes.of(f.harness.objectId())),
                f.funds(at(ANN, 0, 0), ValueV.NONE, ValueV.NONE).withArriving(l5), ANN));
        f.mutants("offer", null, List.of(Bytes.of(POLICY), Bytes.of(NFT), ada(5)), f.objectFunds(at(ANN, 0, 0), nft(), ValueV.NONE).withShared(open), ANN);
        f.mutants("buy", l5, List.of(), buying, BEN);
        f.mutants("cancel", l5, List.of(), f.objectFunds(at(ANN, 0, lovelace), ValueV.NONE, nft()), ANN);
        f.partner = new FourWay.Partner("enlist", open, List.of(), null, null);
        f.mutants("relist", l5, List.of(ada(7)), relisting, ANN);
        f.partner = null;
        JsLeg.check(f);
        report(f);
    }

    // ------------------------------------------------------------------ Ferry (coverage, #107 review C2, K22)

    static Rec pass(long level) {
        return rec("Pass", Bytes.of(ANN), n(level));
    }

    /** The two halves' verdicts in the last validator run of an admits action: the arriving object's spend, the destination's. */
    static boolean[] halves(FourWay f) {
        var r = f.lastVerdict.results();
        return new boolean[]{r.get("SPEND 2@0b0b0b0b").isSuccess(), r.get("SPEND 1@7a7a7a7a").isSuccess()};
    }

    /** The same for a move's run: the moving object's spend, the destination's (the partner instance, OTHER_REF). */
    static boolean[] moveHalves(FourWay f) {
        var r = f.lastVerdict.results();
        var object = r.entrySet().stream().filter(e -> e.getKey().startsWith("SPEND") && !e.getKey().contains("@0c0c0c0c")).toList();
        assertEquals(1, object.size(), "one object spend: " + r.keySet());
        return new boolean[]{object.get(0).getValue().isSuccess(), r.get("SPEND 0@0c0c0c0c").isSuccess()};
    }

    static Rec ferry(long arrivals) {
        return rec("State", Bytes.of(MASTER), n(3), n(arrivals));
    }

    /**
     * Each half of a move reads the other half's input, never its outcome, and the vectors are chosen so that input and
     * outcome give different verdicts, checked on five legs with each half's verdict asserted:
     * <ul>
     *   <li>K22 (#107 C2, round r2 C4): the admission reads the arriving pass as its input holds it. A level-1 pass lifted
     *   to 3 is refused at a level-3 ferry, and the refusal's transaction proposes the ferry's unguarded outcome (one more
     *   arrival), so only the admission's level check refuses it. A level-3 pass lowered to 1 is boarded.</li>
     *   <li>Design F1: the move reads the destination as its input holds it. lift into a ferry with 1 arrival is accepted
     *   although the ferry leaves with 2 (full), and lower onto an empty ferry is refused although it leaves with 1. The
     *   harness asserts that the legs' into and arriving are the states the validator spends.</li>
     *   <li>Design F5: an invariant by type broken through a move (lift from 4 to 6) and through an owned init (issueAt 6).</li>
     * </ul>
     * An admission that read the move's output would board the lifted pass (and refuse the lowered one); a move that read
     * the destination's output would refuse the lift into a ferry with 1 arrival and lower onto an empty one.
     * {@link #ferry_withoutItsAdmissionCheck_failsTheVector} runs the K22 refusal against the validator without its level
     * check.
     */
    @Test
    @Order(4)
    void ferry() throws Exception {
        var f = LEGS.get("Ferry");
        Rec empty = ferry(0), one = ferry(1);
        f.defaultShared = empty;
        ok(f.check("init", null, List.of(n(3)), at(MASTER, 0, 0), MASTER));
        ok(f.check("issue", null, List.of(), at(ANN, 0, 0).withShared(empty), ANN));
        // K22, low in, high out: refused at the destination, whose unguarded outcome (one more arrival) is proposed.
        f.partner = new FourWay.Partner("lift", pass(1), List.of(), null, one);
        f.unguarded(ferry(2), List.of());
        assertEquals("refused", f.check("board", one, List.of(Bytes.of(f.harness.objectId())), at(ANN, 0, 0).withArriving(pass(1)), ANN));
        assertTrue(f.lastRefusal.endsWith(":below the ferry's level"), f.lastRefusal);
        boolean[] lifted = halves(f);
        assertTrue(lifted[0] && !lifted[1], "the lift's spend accepted, the destination's refused: " + f.lastVerdict);
        // K22, high in, low out: boarded.
        f.partner = new FourWay.Partner("lower", pass(3), List.of(), null, one);
        ok(f.check("board", one, List.of(Bytes.of(f.harness.objectId())), at(ANN, 0, 0).withArriving(pass(3)), ANN));
        boolean[] lowered = halves(f);
        assertTrue(lowered[0] && lowered[1], "both halves accepted: " + f.lastVerdict);
        // F1, from the move's side: lift into a ferry with 1 arrival (the input has room; the ferry leaves full).
        f.partner = new FourWay.Partner("board", one, List.of(), null, null);
        ok(f.check("lift", pass(3), List.of(), at(ANN, 0, 0).withInto(one), ANN));
        boolean[] full = moveHalves(f);
        assertTrue(full[0] && full[1], "both halves accepted: " + f.lastVerdict);
        // A full ferry (2 arrivals): the lift is refused, its unguarded pass proposed.
        f.partner = new FourWay.Partner("board", ferry(2), List.of(), null, null);
        f.unguarded(pass(5), List.of());
        assertEquals("refused", f.check("lift", pass(3), List.of(), at(ANN, 0, 0).withInto(ferry(2)), ANN));
        assertTrue(f.lastRefusal.endsWith(":the ferry is full"), f.lastRefusal);
        // F1: lower onto an empty ferry (the input has boarded no one; the ferry leaves with 1), its unguarded pass proposed.
        f.partner = new FourWay.Partner("board", empty, List.of(), null, null);
        f.unguarded(pass(1), List.of());
        assertEquals("refused", f.check("lower", pass(3), List.of(), at(ANN, 0, 0).withInto(empty), ANN));
        assertTrue(f.lastRefusal.endsWith(":the ferry has not sailed yet"), f.lastRefusal);
        boolean[] notSailed = moveHalves(f);
        assertTrue(!notSailed[0] && notSailed[1], "the move's spend refused, the destination's accepted: " + f.lastVerdict);
        f.partner = new FourWay.Partner("board", one, List.of(), null, null);
        ok(f.check("lower", pass(3), List.of(), at(ANN, 0, 0).withInto(one), ANN));
        assertEquals("refused", f.check("lower", pass(2), List.of(), at(ANN, 0, 0).withInto(one), ANN));
        // F5: the type's invariant (level <= 5) broken through a move and through an owned init.
        f.invariantRefused("invariant:cap", "lift", pass(4), List.of(), at(ANN, 0, 0).withInto(one), ANN);
        ok(f.check("issueAt", null, List.of(n(5)), at(ANN, 0, 0).withShared(empty), ANN));
        f.invariantRefused("invariant:cap", "issueAt", null, List.of(n(6)), at(ANN, 0, 0).withShared(empty), ANN);
        f.partner = new FourWay.Partner("lower", pass(3), List.of(), null, one);
        f.mutants("board", one, List.of(Bytes.of(f.harness.objectId())), at(ANN, 0, 0).withArriving(pass(3)), ANN);
        f.partner = new FourWay.Partner("board", one, List.of(), null, null);
        f.mutants("lift", pass(3), List.of(), at(ANN, 0, 0).withInto(one), ANN);
        f.partner = null;
        ok(f.check("scrap", pass(3), List.of(), at(ANN, 0, f.harness.minAda()), ANN));
        JsLeg.check(f);
        report(f);
    }

    /**
     * #107 round-r2 review C4: the K22 refusal fails without the admission's level check. The validator is rebuilt from
     * Ferry's lowering with only that check removed and installed in a fresh leg (the other legs unchanged): the refused
     * board, whose transaction proposes the unguarded outcome, is then accepted by the validator alone, so the vector
     * reports "validator vs interpreter".
     */
    @Test
    @Order(5)
    void ferry_withoutItsAdmissionCheck_failsTheVector() throws Exception {
        String source = Files.readString(DSL.resolve("Ferry.jsc"));
        String java = org.julclang.state.dsl.v2.JscV2.lower(source, "Ferry.jsc").java();
        String guard = java.lines().filter(l -> l.contains("below the ferry's level")).findFirst().orElseThrow();
        assertTrue(guard.contains("Require.that("), guard);
        String mutated = java.replace(guard + "\n", "");
        var built = Toolchain.build(Map.of("org/julclang/state/examples/dsl/Ferry.java", mutated), Map.of());
        assertTrue(built.ok(), built.diagnostics().toString());
        var f = new FourWay("Ferry", source, LEGS.get("Ferry").transitions);
        f.useScript(built.contracts().get(0).scriptCborHex());
        f.defaultShared = ferry(0);
        f.partner = new FourWay.Partner("lift", pass(1), List.of(), null, ferry(1));
        f.unguarded(ferry(2), List.of());
        var e = assertThrows(org.opentest4j.AssertionFailedError.class,
                () -> f.check("board", ferry(1), List.of(Bytes.of(f.harness.objectId())), at(ANN, 0, 0).withArriving(pass(1)), ANN));
        assertTrue(e.getMessage().contains("validator vs interpreter"), e.getMessage());
        System.out.println("[M3a3 C4] the K22 refusal against the validator without its level check: " + f.lastVerdict);
    }
}
