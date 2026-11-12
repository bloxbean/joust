package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.Interpreter.BigMapV;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.MapV;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.julclang.state.toolchain.FourWay.*;
import static org.julclang.state.toolchain.JscV2M1aFourWayTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M1c (J3, J11): the five example ports (vault, escrow, crowdfund, token, pool) on the four legs (reference
 * interpreter, JVM, transition program, validator), over vectors that cover every method's branches and the edges of
 * its checks, plus mutation rejection: for accepted calls of every method, each next state one step away from the
 * interpreter's (a component changed; with BigMaps, one extra write) and each payout list one step away (an amount
 * ±1, another recipient, one dropped, one more) is rejected by the validator, and the accepted transaction passes the
 * attack suite. Batched actions run through a batch (an intent applied by the batcher), and through a direct call too
 * unless they are {@code batched only}.
 * <p>
 * The interpreter leaves {@code only}, {@code after} and {@code before} modifiers to the validator (they are checks on
 * the transaction, not the transition; ADR-0018 D15 has M1b model them). So every modifier site also gets vectors that
 * break it with their outcome stated ({@code FourWay.modifierRefused}, review S2/F2): the interpreter, the JVM and the
 * transition program accept and agree, and the validator refuses. The attack suite adds the same per method, read from
 * the description (a stranger who signs as the caller, windows on the wrong side of the bound).
 */
class JscV2M1cFourWayTest {

    static final Map<String, FourWay> PORTS = new LinkedHashMap<>();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final BigInteger PAST = T0.subtract(n(60_000));
    static final BigInteger LATER = T0.add(n(3_600_000));
    static final byte[] BOB = fill(0xB0), SELLER = fill(0x5E), ARBITER = fill(0xAB), STRANGER = fill(0x77);

    static BigInteger ada(long v) {
        return ADA.multiply(BigInteger.valueOf(v));
    }

    @BeforeAll
    static void build() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!PORTS.isEmpty()) return;
        Map<String, String> files = new LinkedHashMap<>();
        for (String c : List.of("Vault", "Escrow", "Crowdfund", "Token", "Pool")) {
            files.put("org/julclang/state/examples/dsl/" + c + ".jsc", Files.readString(DSL.resolve(c + ".jsc")));
        }
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        for (var c : r.contracts()) {
            PORTS.put(c.name(), new FourWay(c.name(), files.get("org/julclang/state/examples/dsl/" + c.name() + ".jsc"), c.transitions()));
        }
    }

    static void report(FourWay f) {
        System.out.println("[M1c four-way] " + f.name + ": " + f.calls + " calls, interpreter = JVM = transition program = validator ("
                + f.refusals + " refusals, " + f.validatorRuns + " validator runs" + (f.batchRuns > 0 ? ", " + f.batchRuns + " through a batch" : "")
                + (f.hasMaps() ? ", at most " + f.rounds + " read rounds" : "") + "); mutants rejected: " + f.mutantsRejected
                + ", attacks rejected: " + f.attacksRejected + ", modifier refusals: " + f.modifierRefusals);
        System.out.println("[M1c four-way] modifier attacks skipped so far, by reason (review F13): " + org.julclang.state.testkit.Attacks.modifierSkips());
        assertTrue(f.refusals > 0 && f.refusals < f.calls, "accepted and refused calls");
        assertTrue(f.mutantsRejected > 0 && f.attacksRejected > 0, "mutation and attack coverage");
        assertEquals(f.name.equals("Escrow") ? 0 : 1, Math.min(1, f.modifierRefusals), "every modifier site has a refused vector");
    }

    /**
     * Review F8: every vector also ran on the Java example's JVM method; the outcomes differ exactly where the test says
     * (D5's key checks, which the Java examples do not have: bloxbean/joust#69), and nowhere else.
     */
    static void javaDiffers(FourWay f, String... expected) {
        f.javaDiffs.forEach(d -> System.out.println("[M1c vs Java] " + d));
        assertEquals(expected.length, f.javaDiffs.size(), f.name + ": differences from the Java example " + f.javaDiffs);
        for (int i = 0; i < expected.length; i++) assertTrue(f.javaDiffs.get(i).contains(expected[i]), f.javaDiffs.get(i) + " / " + expected[i]);
    }

    static String ok(String outcome) {
        assertTrue(outcome.startsWith("ok:"), outcome);
        return outcome;
    }

    // ------------------------------------------------------------------ Vault: payable, only, after

    static Rec vault(BigInteger unlockAt, BigInteger total) {
        return rec("State", b(OWNER), unlockAt, total);
    }

    @Test
    void vault() throws Exception {
        var f = PORTS.get("Vault").reference("org.julclang.state.examples.vault", "open");
        long minAda = f.harness.minAda();
        // init: before(unlockAt) holds for the whole window [T0, T0 + 2 min); the first deposit is the total.
        for (BigInteger unlockAt : List.of(T0.subtract(n(1)), T0, T0.add(WINDOW).subtract(n(1)), T0.add(WINDOW), T0.add(WINDOW).add(n(1)), LATER)) {
            for (long received : List.of(0L, 10_000_000L)) {
                f.check("init", null, List.of(unlockAt), at(OWNER, received, 0), OWNER, vault(unlockAt, n(received)));
            }
        }
        assertEquals("refused", f.check("init", null, List.of(T0), at(OWNER, 0, 0), OWNER, vault(T0, BigInteger.ZERO)));
        ok(f.check("init", null, List.of(LATER), at(OWNER, 0, 0), OWNER));
        // deposit: anyone, positive.
        for (byte[] who : List.of(OWNER, BOB)) {
            for (long received : List.of(0L, 1L, 5_000_000L)) f.check("deposit", vault(LATER, ada(10)), List.of(), at(who, received, 10_000_000), who);
        }
        // withdraw: positive, at most the total (only owner, after unlockAt: the validator's).
        for (BigInteger unlockAt : List.of(PAST, T0.subtract(n(1)))) {
            for (BigInteger amount : List.of(n(-1), BigInteger.ZERO, BigInteger.ONE, ada(3), ada(10), ada(10).add(BigInteger.ONE))) {
                f.check("withdraw", vault(unlockAt, ada(10)), List.of(amount), at(OWNER, 0, 10_000_000), OWNER);
            }
        }
        assertEquals("refused", f.check("withdraw", vault(PAST, ada(10)), List.of(ada(11)), at(OWNER, 0, 10_000_000), OWNER));
        f.check("close", vault(PAST, ada(10)), List.of(), at(OWNER, 0, 10_000_000 + minAda), OWNER);
        f.check("close", vault(PAST, BigInteger.ZERO), List.of(), at(OWNER, 0, minAda), OWNER);
        assertEquals("ok:" + TransitionParityTest.data(ada(10)).serializeToHex(), f.check("total", vault(PAST, ada(10)), List.of(), null, null));
        f.check("unlockAt", vault(PAST, ada(10)), List.of(), null, null);

        // Modifiers (review S2/F2), outcomes stated: the transition accepts, the validator refuses.
        for (String m : List.of("withdraw", "close")) {
            List<Object> args = m.equals("withdraw") ? List.of(ada(3)) : List.of();
            long bal = m.equals("withdraw") ? 10_000_000 : 10_000_000 + minAda;
            f.modifierRefused(m, vault(PAST, ada(10)), args, at(STRANGER, 0, bal), STRANGER);          // only owner: a stranger signs
            f.modifierRefused(m, vault(LATER, ada(10)), args, at(OWNER, 0, bal), OWNER);               // after unlockAt: still locked
            f.modifierRefused(m, vault(T0.add(n(1)), ada(10)), args, at(OWNER, 0, bal), OWNER);        // after: the window straddles it
        }
        f.mutants("init", null, List.of(LATER), at(OWNER, 10_000_000, 0), OWNER);
        f.mutants("deposit", vault(LATER, ada(10)), List.of(), at(BOB, 5_000_000, 10_000_000), BOB);
        f.mutants("withdraw", vault(PAST, ada(10)), List.of(ada(3)), at(OWNER, 0, 10_000_000), OWNER);
        f.mutants("close", vault(PAST, ada(10)), List.of(), at(OWNER, 0, 10_000_000 + minAda), OWNER);
        javaDiffers(f);
        report(f);
    }

    // ------------------------------------------------------------------ Escrow: after under ||

    static Rec escrow(BigInteger deadline) {
        return rec("State", b(OWNER), b(SELLER), b(ARBITER), ada(25), deadline);
    }

    @Test
    void escrow() throws Exception {
        var f = PORTS.get("Escrow").reference("org.julclang.state.examples.escrow", "open");
        long held = 25_000_000 + f.harness.minAda();
        for (BigInteger deadline : List.of(T0, T0.add(WINDOW).subtract(n(1)), T0.add(WINDOW), LATER)) {
            for (long received : List.of(0L, 1L, 25_000_000L)) {
                f.check("init", null, List.of(b(SELLER), b(ARBITER), deadline), at(OWNER, received, 0), OWNER,
                        rec("State", b(OWNER), b(SELLER), b(ARBITER), n(received), deadline));
            }
        }
        // A 27-byte seller: a checked input, refused on every leg.
        assertEquals("refused", f.check("init", null, List.of(Bytes.of(new byte[27]), b(ARBITER), LATER), at(OWNER, 25_000_000, 0), OWNER,
                rec("State", b(OWNER), Bytes.of(new byte[27]), b(ARBITER), ada(25), LATER)));
        // Around the deadline: after(t) holds when the whole window [T0, T0 + 2 min) is at or after t.
        var deadlines = List.of(T0.subtract(n(2)), T0.subtract(n(1)), T0, T0.add(n(1)), T0.add(WINDOW), LATER);
        for (BigInteger deadline : deadlines) {
            for (byte[] who : List.of(OWNER, SELLER, ARBITER, STRANGER)) {
                f.check("release", escrow(deadline), List.of(), at(who, 0, held), who);
                f.check("refund", escrow(deadline), List.of(), at(who, 0, held), who);
            }
        }
        // Anyone after the deadline; only the seller or arbiter before it; never the buyer before it.
        // after(t) is validFrom >= t (Clock.after): a deadline at the window's start has passed, one a millisecond later has not.
        ok(f.check("refund", escrow(T0), List.of(), at(STRANGER, 0, held), STRANGER));
        assertEquals("refused", f.check("refund", escrow(T0.add(n(1))), List.of(), at(STRANGER, 0, held), STRANGER));
        assertEquals("refused", f.check("refund", escrow(LATER), List.of(), at(OWNER, 0, held), OWNER));
        assertEquals("refused", f.check("release", escrow(LATER), List.of(), at(SELLER, 0, held), SELLER));
        f.check("amount", escrow(LATER), List.of(), null, null);

        f.mutants("init", null, List.of(b(SELLER), b(ARBITER), LATER), at(OWNER, 25_000_000, 0), OWNER);
        f.mutants("release", escrow(LATER), List.of(), at(ARBITER, 0, held), ARBITER);
        f.mutants("refund", escrow(LATER), List.of(), at(SELLER, 0, held), SELLER);
        f.mutants("refund", escrow(PAST), List.of(), at(STRANGER, 0, held), STRANGER);
        javaDiffers(f, "Escrow.init [#" + "00".repeat(27) + ",");
        report(f);
    }

    // ------------------------------------------------------------------ Crowdfund: an inline Map<Key, Int>

    static final byte[] ANN_CF = fill(0xF1), BEN_CF = fill(0x21);   // BEN sorts first: insertion order is not canonical order

    static Rec campaign(BigInteger deadline, long goal, long raised, MapV backers) {
        return rec("State", b(OWNER), n(goal), deadline, ada(2), n(raised), backers);
    }

    @Test
    void crowdfund() throws Exception {
        var f = PORTS.get("Crowdfund").reference("org.julclang.state.examples.crowdfund", "launch");
        long minAda = f.harness.minAda();
        for (long goal : List.of(0L, 1L, 100_000_000L)) {
            for (BigInteger deadline : List.of(T0, T0.add(WINDOW), LATER)) {
                for (long min : List.of(1_999_999L, 2_000_000L)) {
                    f.check("init", null, List.of(n(goal), deadline, n(min)), at(OWNER, 0, 0), OWNER,
                            rec("State", b(OWNER), n(goal), deadline, n(min), BigInteger.ZERO, MapV.EMPTY));
                }
            }
        }
        MapV one = map(b(ANN_CF), ada(10));
        MapV two = map(b(ANN_CF), ada(10), b(BEN_CF), ada(5));
        assertEquals(List.of(b(BEN_CF), b(ANN_CF)), two.keys(), "canonical: BEN (0x21…) before ANN (0xF1…)");
        for (MapV backers : List.of(MapV.EMPTY, one, two)) {
            long raised = backers.values().stream().mapToLong(v -> ((BigInteger) v).longValue()).sum();
            for (BigInteger deadline : List.of(LATER, T0.add(WINDOW), PAST)) {
                for (byte[] who : List.of(ANN_CF, BEN_CF, CAT)) {
                    for (long received : List.of(1_999_999L, 2_000_000L, 3_000_000L)) {
                        f.check("contribute", campaign(deadline, 100_000_000, raised, backers), List.of(), at(who, received, raised), who);
                    }
                }
            }
            for (long goal : List.of(raised, raised + 1)) {
                for (BigInteger deadline : List.of(PAST, T0, LATER)) {
                    for (byte[] who : List.of(ANN_CF, BEN_CF, CAT)) {
                        f.check("refund", campaign(deadline, goal, raised, backers), List.of(), at(who, 0, raised), who);
                    }
                    f.check("claim", campaign(deadline, goal, raised, backers), List.of(), at(OWNER, 0, raised + minAda), OWNER);
                    f.check("close", campaign(deadline, goal, raised, backers), List.of(), at(OWNER, 0, raised + minAda), OWNER);
                }
            }
            f.check("raised", campaign(LATER, 1, raised, backers), List.of(), null, null);
            for (byte[] who : List.of(ANN_CF, CAT)) f.check("contributionOf", campaign(LATER, 1, raised, backers), List.of(b(who)), null, null);
        }
        // By hand: a refund pays the caller their contribution and removes them; the goal met, no refunds.
        ok(f.check("refund", campaign(PAST, 100_000_000, 15_000_000, two), List.of(), at(ANN_CF, 0, 15_000_000), ANN_CF));
        assertEquals("refused", f.check("refund", campaign(PAST, 15_000_000, 15_000_000, two), List.of(), at(ANN_CF, 0, 15_000_000), ANN_CF));
        assertEquals("refused", f.check("close", campaign(PAST, 100_000_000, 15_000_000, two), List.of(), at(OWNER, 0, 15_000_000 + minAda), OWNER));

        // Modifiers (S2/F2): claim and close are 'only creator'; a stranger who signs is refused by the validator only.
        f.modifierRefused("claim", campaign(PAST, 15_000_000, 15_000_000, two), List.of(), at(STRANGER, 0, 15_000_000 + minAda), STRANGER);
        f.modifierRefused("close", campaign(PAST, 15_000_000, 0, MapV.EMPTY), List.of(), at(STRANGER, 0, minAda), STRANGER);
        f.mutants("init", null, List.of(ada(100), LATER, ada(2)), at(OWNER, 0, 0), OWNER);
        f.mutants("contribute", campaign(LATER, 100_000_000, 15_000_000, two), List.of(), at(CAT, 3_000_000, 15_000_000), CAT);
        f.mutants("contribute", campaign(LATER, 100_000_000, 15_000_000, two), List.of(), at(ANN_CF, 3_000_000, 15_000_000), ANN_CF);
        f.mutants("refund", campaign(PAST, 100_000_000, 15_000_000, two), List.of(), at(BEN_CF, 0, 15_000_000), BEN_CF);
        f.mutants("claim", campaign(PAST, 15_000_000, 15_000_000, two), List.of(), at(OWNER, 0, 15_000_000 + minAda), OWNER);
        f.mutants("close", campaign(PAST, 15_000_000, 0, MapV.EMPTY), List.of(), at(OWNER, 0, minAda), OWNER);
        javaDiffers(f);
        report(f);
    }

    // ------------------------------------------------------------------ Token: a BigMap<Key, Int>

    static Rec token(long supply, Map<Bytes, Object> balances) {
        return rec("State", b(OWNER), n(supply), BigMapV.of(balances));
    }

    @Test
    void token() throws Exception {
        var f = PORTS.get("Token").reference("org.julclang.state.examples.token", "create");
        long minAda = f.harness.minAda();
        ok(f.check("init", null, List.of(), at(OWNER, 0, 0), OWNER));
        Map<Bytes, Object> held = new LinkedHashMap<>();
        held.put(b(ANN), n(100));
        held.put(b(BEN), n(50));
        held.put(b(CAT), n(25));
        for (Map<Bytes, Object> bal : List.<Map<Bytes, Object>>of(Map.of(), held)) {
            long supply = bal.values().stream().mapToLong(v -> ((BigInteger) v).longValue()).sum();
            Rec s = token(supply, bal);
            for (byte[] to : List.of(ANN, CAT, BOB)) {
                for (long amount : List.of(-1L, 0L, 1L, 60L)) f.check("mint", s, List.of(b(to), n(amount)), null, OWNER);
            }
            for (byte[] from : List.of(ANN, CAT, BOB)) {
                for (byte[] to : List.of(BEN, from, BOB)) {
                    for (long amount : List.of(0L, 1L, 25L, 100L, 101L)) f.check("transfer", s, List.of(b(to), n(amount)), at(from, 0, 0), from);
                }
                for (long amount : List.of(0L, 1L, 25L, 100L, 101L)) f.check("burn", s, List.of(n(amount)), at(from, 0, 0), from);
            }
            f.check("close", s, List.of(), at(OWNER, 0, minAda), OWNER);
            for (byte[] who : List.of(ANN, BOB)) f.check("balanceOf", s, List.of(b(who)), null, null);
            f.check("supply", s, List.of(), null, null);
        }
        // By hand: everything transferred removes the key; a self-transfer keeps the balance; a burn of all empties it.
        Rec s = token(175, held);
        ok(f.check("transfer", s, List.of(b(ANN), n(25)), at(CAT, 0, 0), CAT));
        Map<Bytes, Object> after = new LinkedHashMap<>();
        after.put(b(ANN), n(125));
        after.put(b(BEN), n(50));
        assertEquals(after, ((BigMapV) f.next("transfer", s, List.of(b(ANN), n(25)), at(CAT, 0, 0)).fields().get(2)).now(), "CAT's key removed");
        Rec self = f.next("transfer", s, List.of(b(ANN), n(40)), at(ANN, 0, 0));
        assertEquals(held, ((BigMapV) self.fields().get(2)).now(), "a self-transfer keeps every balance");
        assertEquals("refused", f.check("close", token(0, held), List.of(), at(OWNER, 0, minAda), OWNER), "balances remain");
        // A key argument is checked (D5): a 27-byte recipient is refused on every leg (the Java example would store it).
        assertEquals("refused", f.check("mint", s, List.of(Bytes.of(new byte[27]), n(1)), null, OWNER));
        assertEquals("refused", f.check("transfer", s, List.of(Bytes.of(new byte[29]), n(1)), at(ANN, 0, 0), ANN));
        ok(f.check("close", token(0, Map.of()), List.of(), at(OWNER, 0, minAda), OWNER));

        // Modifiers (S2/F2): mint (no Ctx) and close are 'only admin'.
        f.modifierRefused("mint", s, List.of(b(BOB), n(10)), null, STRANGER);
        f.modifierRefused("close", token(0, Map.of()), List.of(), at(STRANGER, 0, minAda), STRANGER);
        f.mutants("init", null, List.of(), at(OWNER, 0, 0), OWNER);
        f.mutants("mint", s, List.of(b(BOB), n(10)), null, OWNER);
        f.mutants("transfer", s, List.of(b(BEN), n(30)), at(ANN, 0, 0), ANN);
        f.mutants("transfer", s, List.of(b(ANN), n(25)), at(CAT, 0, 0), CAT);
        f.mutants("burn", s, List.of(n(10)), at(BEN, 0, 0), BEN);
        f.mutants("close", token(0, Map.of()), List.of(), at(OWNER, 0, minAda), OWNER);
        javaDiffers(f, "Token.mint [#" + "00".repeat(27) + ",", "Token.transfer [#" + "00".repeat(29) + ",");
        report(f);
    }

    // ------------------------------------------------------------------ Pool: batched, batched only

    static Rec pool(long donated, long sold, long price, long step) {
        return rec("State", b(OWNER), n(donated), n(sold), n(price), n(step));
    }

    @Test
    void pool() throws Exception {
        var f = PORTS.get("Pool").reference("org.julclang.state.examples.pool", "init");
        long minAda = f.harness.minAda();
        for (long price : List.of(0L, 1L, 10_000_000L)) {
            for (long step : List.of(-1L, 0L, 1_000_000L)) {
                f.check("init", null, List.of(n(price), n(step)), at(OWNER, 0, 0), OWNER, pool(0, 0, price, step));
            }
        }
        Rec p = pool(5_000_000, 2, 10_000_000, 1_000_000);
        for (byte[] who : List.of(ANN, BOB)) {
            // donate: a direct call and an intent, both checked against the interpreter.
            for (long received : List.of(999_999L, 1_000_000L, 5_000_000L)) f.check("donate", p, List.of(), at(who, received, 3_000_000), who);
            // buy: only as an intent; the change is paid back (none for an exact payment).
            for (long received : List.of(9_000_000L, 10_000_000L, 12_000_000L)) {
                for (long maxPrice : List.of(9_999_999L, 10_000_000L, 11_000_000L)) {
                    f.check("buy", p, List.of(n(maxPrice)), at(who, received, 3_000_000), who);
                }
            }
        }
        for (long amount : List.of(0L, 1L, 3_000_000L, 3_000_001L)) f.check("withdraw", p, List.of(n(amount)), at(OWNER, 0, 3_000_000), OWNER);
        f.check("close", p, List.of(), at(OWNER, 0, 3_000_000 + minAda), OWNER);
        f.check("nextPrice", p, List.of(), null, null);
        ok(f.check("buy", p, List.of(n(10_000_000)), at(ANN, 12_000_000, 3_000_000), ANN));
        assertEquals("refused", f.check("buy", p, List.of(n(9_999_999)), at(ANN, 12_000_000, 3_000_000), ANN), "price above the bound");

        // Modifiers (S2/F2): withdraw and close are 'only admin'.
        f.modifierRefused("withdraw", p, List.of(n(1_000_000)), at(STRANGER, 0, 3_000_000), STRANGER);
        f.modifierRefused("close", p, List.of(), at(STRANGER, 0, 3_000_000 + minAda), STRANGER);
        f.mutants("init", null, List.of(n(10_000_000), n(1_000_000)), at(OWNER, 0, 0), OWNER);
        f.mutants("donate", p, List.of(), at(ANN, 5_000_000, 3_000_000), ANN);
        f.mutants("buy", p, List.of(n(11_000_000)), at(BOB, 12_000_000, 3_000_000), BOB);
        f.mutants("buy", p, List.of(n(10_000_000)), at(BOB, 10_000_000, 3_000_000), BOB);
        f.mutants("withdraw", p, List.of(n(1_000_000)), at(OWNER, 0, 3_000_000), OWNER);
        f.mutants("close", p, List.of(), at(OWNER, 0, 3_000_000 + minAda), OWNER);
        javaDiffers(f);
        report(f);
    }
}
