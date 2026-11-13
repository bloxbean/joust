package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.CredV;
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

import static org.julclang.state.toolchain.FourWay.*;
import static org.julclang.state.toolchain.JscV2M1aFourWayTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M2b (J3, J11): params, invariants, events, quorum and authorized on all five legs: both reference interpreters
 * (semantics §2.6, §2.7, §5.6), the generated Java on the JVM, the transition program and the validator, which gets each
 * transaction signed by the keys a quorum counts and with each approving script's zero withdrawal (an Approvals redeemer
 * naming the contract, instance and action, as examples/treasury's Guardian approves). A broken quorum or authorized is
 * refused by both interpreters with its modifier's class and by the validator, while the JVM method and the transition
 * program compute the transition (the modifiers are the validator's); so is a next state that breaks an invariant or a
 * quorum's shape (class {@code invariant:…}). Mutation rejection: for accepted calls, each transaction proposing a next
 * state, payouts or events one step away is rejected, and the accepted one passes the attack suite. {@code Treasury} is
 * the port of examples/treasury, whose every vector also runs on the Java example; {@code Council} covers what the port
 * does not (a quorum of the params, authorized by a state credential and by a params key, events in loops and arms and
 * from a destroy, an invariant over the params).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JscV2M2bFourWayTest {

    static final Map<String, FourWay> LEGS = new LinkedHashMap<>();
    /** Council deployed with a malformed board quorum (#84 review F3), by what is wrong with it. */
    static final Map<String, FourWay> BAD_PARAMS = new LinkedHashMap<>();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] A = fill(0x11), B = fill(0x22), C = fill(0x33), BOB = fill(0xB0), GUARDIAN = fill(0x6A), STRANGER = fill(0x77),
            K1 = fill(0x41), K2 = fill(0x42), K3 = fill(0x43), AUDITOR = fill(0xAD), KEEPER = fill(0x4E), KEEPER_SCRIPT = fill(0x5C);

    static BigInteger ada(long v) {
        return ADA.multiply(BigInteger.valueOf(v));
    }

    static List<Object> keys(byte[]... ks) {
        return java.util.Arrays.stream(ks).map(k -> (Object) Bytes.of(k)).toList();
    }

    static CredV keyCred(byte[] k) {
        return new CredV(false, Bytes.of(k));
    }

    static CredV scriptCred(byte[] h) {
        return new CredV(true, Bytes.of(h));
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
        for (String c : List.of("Treasury", "Council", "Ratio")) files.put("org/julclang/state/examples/dsl/" + c + ".jsc", Files.readString(DSL.resolve(c + ".jsc")));
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        var params = Map.of(
                "Treasury", rec("Config", scriptCred(GUARDIAN), ada(100)),
                "Council", rec("Config", keys(K1, K2, K3), n(2), keyCred(AUDITOR), ada(50)));
        for (var c : r.contracts()) {
            System.out.println("[M2b] " + c.name() + ": " + c.scriptSizeBytes() + " bytes");
            LEGS.put(c.name(), new FourWay(c.name(), files.get("org/julclang/state/examples/dsl/" + c.name() + ".jsc"), c.transitions(),
                    params.get(c.name())));
        }
        // #84 review F3 (and the security review's hostile params): Council deployed with params whose board quorum is
        // not well formed, each its own script. Semantics §2.7 step 1: every creation is refused.
        String council = files.get("org/julclang/state/examples/dsl/Council.jsc");
        var transitions = LEGS.get("Council").transitions;
        var bad = new LinkedHashMap<String, List<Object>>();
        bad.put("unsorted", List.of(keys(K2, K1, K3), n(2)));
        bad.put("duplicates", List.of(keys(K1, K1, K3), n(2)));
        bad.put("threshold 0", List.of(keys(K1, K2, K3), n(0)));
        bad.put("threshold -1", List.of(keys(K1, K2, K3), n(-1)));
        bad.put("threshold 4 of 3", List.of(keys(K1, K2, K3), n(4)));
        bad.put("a 27-byte key", List.of(keys(new byte[27], K2, K3), n(2)));
        bad.put("no keys", List.of(List.of(), n(1)));
        for (var e : bad.entrySet()) {
            BAD_PARAMS.put(e.getKey(), new FourWay("Council", council, transitions,
                    rec("Config", e.getValue().get(0), e.getValue().get(1), keyCred(AUDITOR), ada(50))));
        }
    }

    static void report(FourWay f) {
        System.out.println("[M2b five-way] " + f.name + ": " + f.calls + " calls, interpreter = JVM = transition program = validator (" + f.refusals
                + " refusals, " + f.validatorRuns + " validator runs); mutants rejected: " + f.mutantsRejected + ", attacks rejected: "
                + f.attacksRejected + ", modifier refusals: " + f.modifierRefusals + ", invariant refusals: " + f.invariantRefusals);
        assertTrue(f.refusals > 0 && f.refusals < f.calls, "accepted and refused calls");
        assertTrue(f.mutantsRejected > 0 && f.attacksRejected > 0, "mutation and attack coverage");
        assertTrue(f.modifierRefusals > 0 && f.invariantRefusals > 0, "modifier and invariant refusals");
    }

    // ------------------------------------------------------------------ Treasury: the port of examples/treasury

    static Rec treasury(List<Object> council, long quorum, boolean frozen) {
        return rec("State", council, n(quorum), frozen);
    }

    @Test
    @Order(1)
    void treasury() throws Exception {
        var f = LEGS.get("Treasury").reference("org.julclang.state.examples.treasury", "open");
        Rec council = treasury(keys(A, B, C), 2, false), frozen = treasury(keys(A, B, C), 2, true);
        long minAda = f.harness.minAda();

        // init: a council whose keys are sorted, distinct and 28 bytes, with a reachable quorum (checked on the output).
        ok(f.check("init", null, List.of(keys(A, B, C), n(2)), at(A, 500_000_000, 0), A));
        f.invariantRefused("invariant:quorum council quorum", "init", null, List.of(keys(A, A, B), n(2)), at(A, 500_000_000, 0), A);
        f.invariantRefused("invariant:quorum council quorum", "init", null, List.of(keys(A, B), n(3)), at(A, 500_000_000, 0), A);
        f.invariantRefused("invariant:quorum council quorum", "init", null, List.of(keys(B, A), n(1)), at(A, 500_000_000, 0), A);
        assertEquals("refused", f.check("init", null, List.of(List.of(Bytes.of(new byte[27])), n(1)), at(A, 500_000_000, 0), A,
                treasury(List.of(Bytes.of(new byte[27])), 1, false)));

        // fund: anyone adds ADA.
        ok(f.check("fund", council, List.of(), at(BOB, 5_000_000, 500_000_000), BOB));
        assertEquals("refused", f.check("fund", council, List.of(), at(BOB, 0, 500_000_000), BOB));

        // pay: two of three sign; one, or one and a stranger, is no quorum; the params bound the amount.
        String paid = ok(f.as(List.of(A, C), null).check("pay", council, List.of(Bytes.of(BOB), ada(10)), at(A, 0, 500_000_000), A));
        assertEquals(paid, f.encode("pay", f.interp.call("pay", council, List.of(Bytes.of(BOB), ada(10)),
                at(A, 0, 500_000_000).signedBy(List.of(Bytes.of(A), Bytes.of(C))))));
        f.as(List.of(A), null).modifierRefused("pay", council, List.of(Bytes.of(BOB), ada(10)), at(A, 0, 500_000_000), A);
        assertEquals("modifier:quorum council quorum", f.lastRefusal);
        f.as(List.of(A, BOB), null).modifierRefused("pay", council, List.of(Bytes.of(BOB), ada(10)), at(A, 0, 500_000_000), A);
        assertEquals("refused", f.as(List.of(A, B), null).check("pay", council, List.of(Bytes.of(BOB), ada(101)), at(A, 0, 500_000_000), A));
        assertEquals("refused", f.as(List.of(A, B), null).check("pay", council, List.of(Bytes.of(BOB), ada(10)), at(A, 0, 5_000_000), A));
        assertEquals("refused", f.as(List.of(A, B), null).check("pay", frozen, List.of(Bytes.of(BOB), ada(10)), at(A, 0, 500_000_000), A));
        assertEquals("refused", f.as(List.of(A, B), null).check("pay", council, List.of(Bytes.of(BOB), n(0)), at(A, 0, 500_000_000), A));

        // rotate: a quorum of the current council; the new council keeps a well-formed, reachable quorum.
        ok(f.as(List.of(A, B), null).check("rotate", council, List.of(keys(A, B), n(2)), null, A));
        ok(f.as(List.of(B, C), null).check("rotate", council, List.of(keys(A), n(1)), null, B));
        f.as(List.of(A, B), null).invariantRefused("invariant:quorum council quorum", "rotate", council, List.of(keys(A, A, B), n(3)), null, A);
        f.as(List.of(A, B), null).invariantRefused("invariant:quorum council quorum", "rotate", council, List.of(keys(B, A), n(1)), null, A);
        f.as(List.of(A, B), null).invariantRefused("invariant:quorum council quorum", "rotate", council, List.of(keys(A, B), n(0)), null, A);
        f.as(List.of(C), null).modifierRefused("rotate", council, List.of(keys(C), n(1)), null, C);

        // freeze: the guardian script approves through a zero withdrawal; a key of the same hash signing does not.
        ok(f.as(List.of(A), List.of(GUARDIAN)).check("freeze", council, List.of(true), null, A));
        ok(f.as(List.of(A), List.of(GUARDIAN)).check("freeze", frozen, List.of(false), null, A));
        f.as(List.of(A), null).modifierRefused("freeze", council, List.of(true), null, A);
        assertEquals("modifier:authorized params.guardian", f.lastRefusal);
        f.as(List.of(GUARDIAN), null).modifierRefused("freeze", council, List.of(true), null, GUARDIAN);
        f.as(List.of(A), List.of(STRANGER)).modifierRefused("freeze", council, List.of(true), null, A);

        // close: a quorum pays everything out.
        ok(f.as(List.of(B, C), null).check("close", council, List.of(Bytes.of(BOB)), at(B, 0, minAda + 500_000_000), B));
        f.as(List.of(B), null).modifierRefused("close", council, List.of(Bytes.of(BOB)), at(B, 0, minAda + 500_000_000), B);

        f.mutants("init", null, List.of(keys(A, B, C), n(2)), at(A, 500_000_000, 0), A);
        f.mutants("fund", council, List.of(), at(BOB, 5_000_000, 500_000_000), BOB);
        f.as(List.of(A, C), null).mutants("pay", council, List.of(Bytes.of(BOB), ada(10)), at(A, 0, 500_000_000), A);
        f.as(List.of(A, B), null).mutants("rotate", council, List.of(keys(A, B), n(2)), null, A);
        // freeze reads no Ctx: no key needs to sign it (the attack suite's 'required signer missing' then has nothing to drop).
        f.as(List.of(), List.of(GUARDIAN)).mutants("freeze", council, List.of(true), null, A);
        f.as(List.of(B, C), null).mutants("close", council, List.of(Bytes.of(BOB)), at(B, 0, minAda + 500_000_000), B);

        // Every vector also ran on examples/treasury's JVM method: they differ only where the port checks its inputs (a
        // council key of 27 bytes), which the Java method leaves to its validator's quorum check.
        f.javaDiffs.forEach(d -> System.out.println("[M2b vs Java] " + d));
        assertEquals(List.of(), f.javaDiffs.stream().filter(d -> !d.contains("the Java example accepts, the port refuses")).toList());
        assertEquals(1, f.javaDiffs.size(), f.javaDiffs.toString());
        report(f);
    }

    // ------------------------------------------------------------------ Council: what the port does not cover

    static Rec council(Object keeper, List<Object> members, long need, BigInteger spent) {
        return rec("State", Bytes.of(OWNER), keeper, members, n(need), spent);
    }

    @Test
    @Order(2)
    void council() throws Exception {
        var f = LEGS.get("Council");
        Rec s = council(keyCred(KEEPER), keys(A, B), 1, n(0)), scripted = council(scriptCred(KEEPER_SCRIPT), keys(A, B), 1, n(0));
        long minAda = f.harness.minAda();

        // init: the params' board is checked at creation too; the members' quorum on the output.
        ok(f.check("init", null, List.of(keys(A, B), n(1), keyCred(KEEPER)), at(OWNER, 0, 0), OWNER));
        f.invariantRefused("invariant:quorum members need", "init", null, List.of(keys(A, B), n(3), keyCred(KEEPER)), at(OWNER, 0, 0), OWNER);
        assertEquals("refused", f.check("init", null, List.of(keys(A, B), n(1), keyCred(new byte[27])), at(OWNER, 0, 0), OWNER,
                council(keyCred(new byte[27]), keys(A, B), 1, n(0))));

        // spend: a quorum of the params' board; an event per payment, in the loop's order; the invariant over the params.
        String spent = ok(f.as(List.of(K1, K3), null).check("spend", s, List.of(keys(BOB, C), ada(10)), at(K1, 0, 100_000_000), K1));
        assertEquals(spent, f.encode("spend", f.interp.call("spend", s, List.of(keys(BOB, C), ada(10)),
                at(K1, 0, 100_000_000).signedBy(List.of(Bytes.of(K1), Bytes.of(K3))))));
        ok(f.as(List.of(K1, K2), null).check("spend", s, List.of(List.of(), ada(10)), at(K1, 0, 100_000_000), K1));
        f.as(List.of(K2), null).modifierRefused("spend", s, List.of(keys(BOB), ada(10)), at(K2, 0, 100_000_000), K2);
        assertEquals("modifier:quorum params.board params.boardQuorum", f.lastRefusal);
        f.as(List.of(K1, K2), null).invariantRefused("invariant:withinCap", "spend", s, List.of(keys(BOB, C), ada(30)), at(K1, 0, 100_000_000), K1);

        // setMembers: a quorum of the members; a Joined event for each new one (an emit in an if, in a loop).
        ok(f.as(List.of(A), null).check("setMembers", s, List.of(keys(A, B, C), n(2)), null, A));
        ok(f.as(List.of(B), null).check("setMembers", s, List.of(keys(A), n(1)), null, B));
        f.as(List.of(C), null).modifierRefused("setMembers", s, List.of(keys(A, B, C), n(2)), null, C);
        f.as(List.of(A), null).invariantRefused("invariant:quorum members need", "setMembers", s, List.of(keys(C, A), n(1)), null, A);

        // stamp: authorized by the state's credential: a key signs, or a script approves; an event of the window's start.
        ok(f.check("stamp", s, List.of(), at(KEEPER, 0, 0), KEEPER));
        f.modifierRefused("stamp", s, List.of(), at(STRANGER, 0, 0), STRANGER);
        assertEquals("modifier:authorized keeper", f.lastRefusal);
        ok(f.as(List.of(A), List.of(KEEPER_SCRIPT)).check("stamp", scripted, List.of(), at(A, 0, 0), A));
        f.as(List.of(KEEPER_SCRIPT), null).modifierRefused("stamp", scripted, List.of(), at(KEEPER_SCRIPT, 0, 0), KEEPER_SCRIPT);

        // audit: authorized by a params key credential.
        ok(f.check("audit", council(keyCred(KEEPER), keys(A, B), 1, ada(20)), List.of(), null, AUDITOR));
        f.modifierRefused("audit", s, List.of(), null, STRANGER);

        // close: only the owner; the destroy's result carries its event (Settled: payouts and events).
        ok(f.check("close", s, List.of(), at(OWNER, 0, minAda + 3_000_000), OWNER));
        f.modifierRefused("close", s, List.of(), at(STRANGER, 0, minAda + 3_000_000), STRANGER);

        f.mutants("init", null, List.of(keys(A, B), n(1), keyCred(KEEPER)), at(OWNER, 0, 0), OWNER);
        f.as(List.of(K1, K3), null).mutants("spend", s, List.of(keys(BOB, C), ada(10)), at(K1, 0, 100_000_000), K1);
        f.as(List.of(A), null).mutants("setMembers", s, List.of(keys(A, B, C), n(2)), null, A);
        f.mutants("stamp", s, List.of(), at(KEEPER, 0, 0), KEEPER);
        f.as(List.of(A), List.of(KEEPER_SCRIPT)).mutants("stamp", scripted, List.of(), at(A, 0, 0), A);
        f.mutants("audit", council(keyCred(KEEPER), keys(A, B), 1, ada(20)), List.of(), null, AUDITOR);
        f.mutants("close", s, List.of(), at(OWNER, 0, minAda + 3_000_000), OWNER);
        report(f);
    }

    /** #84 review F3: semantics §2.7 step 1, a params quorum checked at creation, on all five legs. */
    @Test
    @Order(3)
    void paramsQuorums_areCheckedAtCreation() throws Exception {
        for (var e : BAD_PARAMS.entrySet()) {
            var f = e.getValue();
            f.invariantRefused("invariant:quorum params.board params.boardQuorum", "init", null, List.of(keys(A, B), n(1), keyCred(KEEPER)),
                    at(OWNER, 0, 0), OWNER);
            System.out.println("[M2b five-way] Council deployed with a board quorum of " + e.getKey() + ": init refused on all legs ("
                    + f.validatorRuns + " validator run)");
        }
    }

    /** #84 review S2: an invariant that fails (here a division by zero) refuses the call as the invariant's. */
    @Test
    @Order(4)
    void anInvariantThatFails_isTheInvariantsRefusal() throws Exception {
        var f = LEGS.get("Ratio");
        Rec s = rec("State", n(0), n(1));
        ok(f.check("init", null, List.of(), null, OWNER));
        ok(f.check("set", s, List.of(n(5), n(2)), null, OWNER));
        f.invariantRefused("invariant:ratio", "set", s, List.of(n(5), n(0)), null, OWNER);
        f.as(List.of(), null).mutants("set", s, List.of(n(5), n(2)), null, OWNER);   // no key need sign: set reads no Ctx
        System.out.println("[M2b five-way] Ratio: " + f.calls + " calls (" + f.refusals + " refusals, " + f.validatorRuns + " validator runs), invariant"
                + " refusals: " + f.invariantRefusals + ", mutants rejected: " + f.mutantsRejected + ", attacks rejected: " + f.attacksRejected);
    }

    @Test
    @Order(5)
    void fiveWay_theJavaScriptInterpreterAgrees() throws Exception {
        int total = 0;
        var all = new java.util.ArrayList<>(LEGS.values());
        all.addAll(BAD_PARAMS.values());
        for (FourWay f : all) {
            int n = JsLeg.check(f);
            System.out.println("[M2b five-way] " + f.name + ": " + n + " calls, JavaScript interpreter = Java interpreter = JVM = transition"
                    + " program = validator");
            total += n;
        }
        System.out.println("[M2b five-way] %d calls agree on all five legs".formatted(total));
        assertTrue(total > 0, "the tests above recorded calls");
    }
}
