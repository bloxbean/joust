package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.MapV;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
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
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3a1 (J3, J11): owned objects (ADR-0003) on all five legs: both reference interpreters (semantics §2.8), the
 * generated Java on the JVM, the transition program and the validator. An owned creation mints its object from a seed
 * with the instance as a reference input; an owned action spends and continues its object, reading the instance by
 * reference when it says {@code shared}; an owned destroy burns its token. {@code Bank} is the port of examples/bank:
 * every vector also runs on the Java example's methods.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JscV2M3a1FourWayTest {

    static final Map<String, FourWay> LEGS = new LinkedHashMap<>();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] ADMIN = fill(0xAD), ANN = fill(0xA1), EVE = fill(0xEE);

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
        for (String c : List.of("Bank", "Members")) files.put("org/julclang/state/examples/dsl/" + c + ".jsc", Files.readString(DSL.resolve(c + ".jsc")));
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        for (var c : r.contracts()) {
            System.out.println("[M3a1] " + c.name() + ": " + c.scriptSizeBytes() + " bytes");
            LEGS.put(c.name(), new FourWay(c.name(), files.get("org/julclang/state/examples/dsl/" + c.name() + ".jsc"), c.transitions(),
                    c.name().equals("Members") ? rec("Config", ada(2)) : null));
        }
    }

    static void report(FourWay f) {
        System.out.println("[M3a1 five-way] " + f.name + ": " + f.calls + " calls, interpreter = JVM = transition program = validator (" + f.refusals
                + " refusals, " + f.validatorRuns + " validator runs); mutants rejected: " + f.mutantsRejected + ", attacks rejected: "
                + f.attacksRejected + ", modifier refusals: " + f.modifierRefusals
                + (f.javaDiffs.isEmpty() ? "" : "; differs from the Java example on: " + f.javaDiffs));
        assertTrue(f.refusals > 0 && f.refusals < f.calls, "accepted and refused calls");
        assertTrue(f.mutantsRejected > 0 && f.attacksRejected > 0, "mutation and attack coverage");
    }

    static Rec bank(boolean paused, long limitAda) {
        return rec("State", Bytes.of(ADMIN), paused, ada(limitAda));
    }

    static Rec account(byte[] owner, long savedAda) {
        return rec("Account", Bytes.of(owner), ada(savedAda));
    }

    static Tx shared(Tx t, Rec state) {
        return t.withShared(state);
    }

    @Test
    @Order(1)
    void bank() throws Exception {
        var f = LEGS.get("Bank").reference("org.julclang.state.examples.bank", "init");
        Rec open = bank(false, 50), paused = bank(true, 50);
        // The instance's own methods.
        ok(f.check("init", null, List.of(ada(50)), at(ADMIN, 0, 0), ADMIN));
        ok(f.check("setPaused", open, List.of(true), null, ADMIN));
        f.modifierRefused("setPaused", open, List.of(true), null, EVE);
        ok(f.check("setLimit", open, List.of(ada(20)), null, ADMIN));
        assertEquals("refused", f.check("setLimit", open, List.of(BigInteger.ZERO), null, ADMIN));
        // Opening an account: an owned init, the bank read by reference (shared).
        String opened = ok(f.check("open", null, List.of(), shared(at(ANN, ada(10).longValue(), 0), open), ANN));
        assertTrue(opened.startsWith("ok:"));
        assertEquals("refused", f.check("open", null, List.of(), shared(at(ANN, ada(10).longValue(), 0), paused), ANN, account(ANN, 10)));
        assertTrue(f.lastRefusal.startsWith("require:") && f.lastRefusal.endsWith(":the bank is paused"), f.lastRefusal);
        // Depositing: an owned action on the object; only its owner.
        Rec acct = account(ANN, 10);
        ok(f.check("deposit", acct, List.of(), at(ANN, ada(5).longValue(), ada(10).longValue()), ANN));
        assertEquals("refused", f.check("deposit", acct, List.of(), at(ANN, 0, ada(10).longValue()), ANN));
        f.modifierRefused("deposit", acct, List.of(), at(EVE, ada(5).longValue(), ada(10).longValue()), EVE);
        // Withdrawing: within the bank's limit, unless it is paused; the object's own savings.
        ok(f.check("withdraw", acct, List.of(ada(4)), shared(at(ANN, 0, ada(10).longValue()), open), ANN));
        ok(f.check("withdraw", acct, List.of(ada(10)), shared(at(ANN, 0, ada(10).longValue()), open), ANN));
        assertEquals("refused", f.check("withdraw", acct, List.of(ada(4)), shared(at(ANN, 0, ada(10).longValue()), paused), ANN));
        assertEquals("refused", f.check("withdraw", account(ANN, 100), List.of(ada(60)), shared(at(ANN, 0, ada(100).longValue()), open), ANN));
        assertEquals("refused", f.check("withdraw", acct, List.of(ada(11)), shared(at(ANN, 0, ada(10).longValue()), open), ANN));
        assertEquals("refused", f.check("withdraw", acct, List.of(BigInteger.ZERO), shared(at(ANN, 0, ada(10).longValue()), open), ANN));
        f.modifierRefused("withdraw", acct, List.of(ada(4)), shared(at(EVE, 0, ada(10).longValue()), open), EVE);
        // Closing: the escape hatch, which reads no bank (it works while the bank is paused).
        long lovelace = ada(10).longValue() + 2_300_000;
        ok(f.check("close", acct, List.of(), at(ANN, 0, lovelace), ANN));
        f.modifierRefused("close", acct, List.of(), at(EVE, 0, lovelace), EVE);
        ok(f.check("paused", open, List.of(), null, null));
        // Mutation rejection: the next object, its payouts, one step away; the attack suite on each accepted call.
        f.defaultShared = open;
        f.mutants("open", null, List.of(), shared(at(ANN, ada(10).longValue(), 0), open), ANN);
        f.mutants("deposit", acct, List.of(), at(ANN, ada(5).longValue(), ada(10).longValue()), ANN);
        f.mutants("withdraw", acct, List.of(ada(4)), shared(at(ANN, 0, ada(10).longValue()), open), ANN);
        f.mutants("close", acct, List.of(), at(ANN, 0, lovelace), ANN);
        f.mutants("setPaused", open, List.of(true), null, ADMIN);
        assertEquals(List.of(), f.javaDiffs, "the port and examples/bank agree on every vector");
        JsLeg.check(f);
        report(f);
    }

    // ------------------------------------------------------------------ coverage: Members

    static final byte[] K1 = fill(0x11), K2 = fill(0x22), K3 = fill(0x33);

    static List<Object> keys(byte[]... ks) {
        var out = new java.util.ArrayList<Object>();
        for (byte[] k : ks) out.add(Bytes.of(k));
        return out;
    }

    static Rec member(List<Object> guardians, long needed, MapV notes, Rec tier, Opt best, BigInteger until) {
        return rec("Member", Bytes.of(ANN), guardians, n(needed), notes, tier, best, until);
    }

    @Test
    @Order(2)
    void members() throws Exception {
        var f = LEGS.get("Members");
        Rec club = rec("State", Bytes.of(ADMIN), true, n(0)), closed = rec("State", Bytes.of(ADMIN), false, n(0));
        BigInteger later = T0.add(WINDOW).add(n(60_000)), earlier = T0.subtract(n(1));
        ok(f.check("init", null, List.of(), at(ADMIN, 0, 0), ADMIN));
        ok(f.check("setOpen", club, List.of(false), null, ADMIN));
        f.defaultShared = club;
        // Joining: the params' fee, the club read by reference, checked inputs, the quorum's shape at creation (per type).
        Rec fresh = member(keys(K1, K2, K3), 2, MapV.EMPTY, rec("Basic"), Opt.NONE, later);
        ok(f.check("join", null, List.of(keys(K1, K2, K3), n(2), later), shared(at(ANN, ada(5).longValue(), 0), club), ANN));
        assertEquals("refused", f.check("join", null, List.of(keys(K1, K2, K3), n(2), later), shared(at(ANN, ada(5).longValue(), 0), closed), ANN, fresh));
        assertEquals("refused", f.check("join", null, List.of(keys(K1, K2, K3), n(2), later), shared(at(ANN, ada(1).longValue(), 0), club), ANN, fresh));
        assertEquals("refused", f.check("join", null, List.of(keys(new byte[27], K2, K3), n(2), later), shared(at(ANN, ada(5).longValue(), 0), club), ANN,
                member(keys(new byte[27], K2, K3), 2, MapV.EMPTY, rec("Basic"), Opt.NONE, later)));
        assertEquals("input:guardians", f.lastRefusal);
        f.invariantRefused("invariant:quorum guardians needed", "join", null, List.of(keys(K2, K1), n(1), later),
                shared(at(ANN, ada(5).longValue(), 0), club), ANN);
        f.invariantRefused("invariant:quorum guardians needed", "join", null, List.of(keys(K1, K2), n(3), later),
                shared(at(ANN, ada(5).longValue(), 0), club), ANN);
        // Scoring before the deadline, with an event; the best note kept in an Option of a record.
        ok(f.check("score", fresh, List.of(Bytes.of(K1), n(3)), at(ANN, 0, 0), ANN));
        assertEquals("refused", f.check("score", fresh, List.of(Bytes.of(K1), n(0)), at(ANN, 0, 0), ANN));
        f.modifierRefused("score", member(keys(K1, K2, K3), 2, MapV.EMPTY, rec("Basic"), Opt.NONE, T0), List.of(Bytes.of(K1), n(3)), at(ANN, 0, 0), ANN);
        f.modifierRefused("score", fresh, List.of(Bytes.of(K1), n(3)), at(EVE, 0, 0), EVE);
        assertEquals("refused", f.check("score", fresh, List.of(Bytes.of(new byte[27]), n(3)), at(ANN, 0, 0), ANN));
        assertEquals("input:by", f.lastRefusal);
        // Promoting by the guardians' quorum over the object's own fields.
        ok(f.as(List.of(K1, K3), null).check("promote", fresh, List.of(n(7)), null, K1));
        f.as(List.of(K1), null).modifierRefused("promote", fresh, List.of(n(7)), null, K1);
        // Extending after the deadline, by tier (a match on the object's enum).
        Rec gold = member(keys(K1, K2, K3), 2, MapV.EMPTY, rec("Gold", n(7)), Opt.NONE, earlier);
        ok(f.check("extend", gold, List.of(n(10)), at(ANN, 0, 0), ANN));
        ok(f.check("extend", member(keys(K1, K2, K3), 2, MapV.EMPTY, rec("Basic"), Opt.NONE, earlier), List.of(n(10)), at(ANN, 0, 0), ANN));
        f.modifierRefused("extend", fresh, List.of(n(10)), at(ANN, 0, 0), ANN);   // its deadline is later than the window
        // Leaving, with an event naming how many notes the object held.
        long lovelace = ada(5).longValue() + 2_000_000;
        Rec noted = member(keys(K1, K2, K3), 2, MapV.of(Map.of(Bytes.of(K1), n(3))), rec("Basic"), Opt.some(rec("Note", Bytes.of(K1), Bytes.hex("6e6f746564"))), later);
        ok(f.check("leave", noted, List.of(), at(ANN, 0, lovelace), ANN));
        f.modifierRefused("leave", noted, List.of(), at(EVE, 0, lovelace), EVE);
        ok(f.check("isOpen", club, List.of(), null, null));
        f.mutants("join", null, List.of(keys(K1, K2, K3), n(2), later), shared(at(ANN, ada(5).longValue(), 0), club), ANN);
        f.mutants("score", fresh, List.of(Bytes.of(K1), n(3)), at(ANN, 0, 0), ANN);
        f.as(List.of(K1, K3), null).mutants("promote", fresh, List.of(n(7)), null, K1);
        f.mutants("extend", gold, List.of(n(10)), at(ANN, 0, 0), ANN);
        f.mutants("leave", noted, List.of(), at(ANN, 0, lovelace), ANN);
        JsLeg.check(f);
        report(f);
    }
}
