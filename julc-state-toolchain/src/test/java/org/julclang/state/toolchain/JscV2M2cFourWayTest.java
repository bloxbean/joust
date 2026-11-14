package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.Interpreter.AddrV;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.CredV;
import org.julclang.state.dsl.v2.Interpreter.StakeHash;
import org.julclang.state.dsl.v2.Interpreter.DepositV;
import org.julclang.state.dsl.v2.Interpreter.InstV;
import org.julclang.state.dsl.v2.Interpreter.MapV;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.Interpreter.ValueV;
import org.julclang.state.dsl.v2.JscModules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.julclang.state.toolchain.FourWay.*;
import static org.julclang.state.toolchain.JscV2M1aFourWayTest.*;
import static org.julclang.state.toolchain.JscV2M2FourWayTest.vv;
import static org.julclang.state.toolchain.JscV2M2FourWayTest.wallet;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M2c (J3, J11): composition on all five legs: both reference interpreters (semantics §5.7, §5.8, §4's read
 * states and memos), the generated Java on the JVM, the transition program and the validator. Each contract is checked
 * by its own harness (CLAUDE.md: the harness evaluates only its own spends), in a transaction composed as the runtime
 * composes it: a read state is a reference input holding its instance's token at its script's address; a depositor's
 * transaction spends and continues the target instance, the deposit claimed in the registry by its input; a receiver's
 * continuing output holds the deposits claimed on it, each from a depositor's input. The registry is written as
 * cardano-client-lib writes it, so values of token names of unequal length cross in the wire's order (#85).
 * <p>
 * {@code Oracle}, {@code Kiosk}, {@code Pot} and {@code Stall} are the ports of examples/oracle (Oracle, Shop) and
 * examples/fees (Pot, Stall): every vector also runs on the Java example's JVM method. {@code Tips} and {@code Tipper}
 * cover what the ports do not: typed memos (receives Note), stored values (the state, a record, an Option of a record),
 * native assets deposited, a read state holding a map and values, send datums (a record, an enum). {@code Inbox} (review
 * r1, S1, F5, C1, C2) receives memos of every shape a decoder decides: a record holding a Bool, an Option, an enum, a List,
 * a Map, a Value and an Address; a Key and a Bytes (one Java representation, two decoders); an unread Value.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JscV2M2cFourWayTest {

    static final Map<String, FourWay> LEGS = new LinkedHashMap<>();
    static final Map<String, Map<String, String>> TRANSITIONS = new LinkedHashMap<>();
    static JscModules MODULES;
    static Map<String, String> SOURCES;
    static final byte[] STASH_POLICY = fill(0x0B);
    static final byte[] STASH_ID = JscV2M2FourWayTest.bytes(32, 0x3D);

    static Rec stashParams(ValueV v, byte[] h) {
        return rec("Config", v, Bytes.of(h));
    }
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] PUB = fill(0x9B), BUYER = fill(0xB0), STRANGER = fill(0x77), A = fill(0x11), B = fill(0x22);
    static final byte[] ORACLE_POLICY = fill(0x0C), POT_POLICY = fill(0x0E), JAR_POLICY = fill(0x0F), TOKEN_POLICY = fill(0x70);
    static final byte[] ORACLE_ID = JscV2M2FourWayTest.bytes(32, 0x0D), POT_ID = JscV2M2FourWayTest.bytes(32, 0x1D),
            JAR_ID = JscV2M2FourWayTest.bytes(32, 0x2D);
    static final Bytes N00FF = Bytes.hex("00ff"), N01 = Bytes.hex("01");

    static BigInteger ada(long v) {
        return ADA.multiply(BigInteger.valueOf(v));
    }

    static InstV inst(byte[] policy, byte[] id) {
        return new InstV(Bytes.of(policy), Bytes.of(id));
    }

    static String ok(String outcome) {
        assertTrue(outcome.startsWith("ok:"), outcome);
        return outcome;
    }

    static Map<String, String> sources() throws Exception {
        var out = new LinkedHashMap<String, String>();
        for (String c : List.of("Oracle", "Kiosk", "Pot", "Stall", "Tips", "Tipper", "Inbox", "Stash", "StashReader")) out.put(c + ".jsc", Files.readString(DSL.resolve(c + ".jsc")));
        return out;
    }

    @BeforeAll
    static void build() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!LEGS.isEmpty()) return;
        var srcs = sources();
        Map<String, String> files = new LinkedHashMap<>();
        srcs.forEach((n, t) -> files.put("org/julclang/state/examples/dsl/" + n, t));
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        var modules = JscModules.of(srcs);
        var params = Map.of(
                "Kiosk", rec("Config", inst(ORACLE_POLICY, ORACLE_ID), n(600_000)),
                "Stall", rec("Config", inst(POT_POLICY, POT_ID), n(500)),
                "Tipper", rec("Config", inst(JAR_POLICY, JAR_ID), ada(50)),
                "Stash", stashParams(vv(TOKEN_POLICY, N01, n(1)), fill(0x71)),
                "StashReader", rec("Config", inst(STASH_POLICY, STASH_ID)));
        MODULES = modules;
        SOURCES = srcs;
        for (var c : r.contracts()) {
            TRANSITIONS.put(c.name(), c.transitions());
            System.out.println("[M2c] " + c.name() + ": " + c.scriptSizeBytes() + " bytes");
            LEGS.put(c.name(), new FourWay(c.name(), srcs.get(c.name() + ".jsc"), c.transitions(), params.get(c.name()), modules));
        }
    }

    static void report(FourWay f) {
        System.out.println("[M2c five-way] " + f.name + ": " + f.calls + " calls, interpreter = JVM = transition program = validator (" + f.refusals
                + " refusals, " + f.validatorRuns + " validator runs, " + f.transactionLevelRefusals + " transaction-level); mutants rejected: "
                + f.mutantsRejected + ", attacks rejected: " + f.attacksRejected + ", modifier refusals: " + f.modifierRefusals
                + (f.javaDiffs.isEmpty() ? "" : "; differs from the Java example on: " + f.javaDiffs));
        assertTrue(f.refusals > 0 && f.refusals < f.calls, "accepted and refused calls");
        assertTrue(f.mutantsRejected > 0 && f.attacksRejected > 0, "mutation and attack coverage");
    }

    /** {@code t} with the native assets the call sees under this harness's policy and instance. */
    static Tx funds(FourWay f, Tx t, ValueV received, ValueV held) {
        return f.funds(t, received, held);
    }

    static Tx deposits(FourWay f, Tx t, DepositV... ds) {
        Tx x = t.funds() == null ? f.funds(t, ValueV.NONE, ValueV.NONE) : t;
        return x.withFunds(x.funds().withDeposits(List.of(ds)));
    }

    // ------------------------------------------------------------------ the oracle port

    static Rec oracle(byte[] publisher, long price, BigInteger at) {
        return rec("State", Bytes.of(publisher), n(price), at);
    }

    @Test
    @Order(1)
    void oracle() throws Exception {
        var f = LEGS.get("Oracle").reference("org.julclang.state.examples.oracle", "open");
        Rec s = oracle(PUB, 3_000_000, T0.subtract(n(60_000)));
        ok(f.check("init", null, List.of(n(3_000_000)), at(PUB, 0, 0), PUB));
        assertEquals("refused", f.check("init", null, List.of(n(0)), at(PUB, 0, 0), PUB, oracle(PUB, 0, T0)));
        ok(f.check("post", s, List.of(n(4_000_000)), at(PUB, 0, 2_000_000), PUB));
        assertEquals("refused", f.check("post", s, List.of(n(-1)), at(PUB, 0, 2_000_000), PUB));
        f.modifierRefused("post", s, List.of(n(4_000_000)), at(STRANGER, 0, 2_000_000), STRANGER);
        ok(f.check("close", s, List.of(), at(PUB, 0, 4_000_000), PUB));
        f.modifierRefused("close", s, List.of(), at(STRANGER, 0, 4_000_000), STRANGER);
        f.mutants("post", s, List.of(n(4_000_000)), at(PUB, 0, 2_000_000), PUB);
        f.mutants("close", s, List.of(), at(PUB, 0, 4_000_000), PUB);
        assertEquals(List.of(), f.javaDiffs, "the port and examples/oracle's Oracle agree on every vector");
        JsLeg.check(f);
        report(f);
    }

    // ------------------------------------------------------------------ the shop port (Kiosk)

    static Tx reading(Tx t, Rec oracleState) {
        return t.withReads(Map.of("oracle", oracleState));
    }

    @Test
    @Order(2)
    void kiosk() throws Exception {
        var f = LEGS.get("Kiosk").referenceNamed("org.julclang.state.examples.oracle", "Shop", "open");
        long price = 3_000_000;
        Rec shop = rec("State", Bytes.of(fill(0x0A)), n(0));
        Rec fresh = rec("Oracle.State", Bytes.of(PUB), n(price), T0.subtract(n(60_000)));
        Rec stale = rec("Oracle.State", Bytes.of(PUB), n(price), T0.subtract(n(700_000)));
        // Fresh by the port's rule: the whole window ends within maxAgeMs of the post (before(updatedAt + maxAge)).
        Rec edge = rec("Oracle.State", Bytes.of(PUB), n(price), T0.add(WINDOW).subtract(n(600_000)));
        Rec late = rec("Oracle.State", Bytes.of(PUB), n(price), T0.add(WINDOW).subtract(n(600_001)));
        ok(f.check("init", null, List.of(), at(fill(0x0A), 0, 0), fill(0x0A)));
        String bought = ok(f.check("buy", shop, List.of(n(2)), reading(at(BUYER, 2 * price, 0), fresh), BUYER));
        ok(f.check("buy", shop, List.of(n(1)), reading(at(BUYER, price, 0), edge), BUYER));
        assertEquals("refused", f.check("buy", shop, List.of(n(1)), reading(at(BUYER, price, 0), late), BUYER));
        assertEquals("refused", f.check("buy", shop, List.of(n(2)), reading(at(BUYER, 2 * price, 0), stale), BUYER));
        assertEquals("refused", f.check("buy", shop, List.of(n(2)), reading(at(BUYER, 2 * price - 1, 0), fresh), BUYER));
        assertEquals("refused", f.check("buy", shop, List.of(n(0)), reading(at(BUYER, 0, 0), fresh), BUYER));
        // A read state holding a 27-byte key: a checked input (D5), refused where the call starts (#read:).
        assertEquals("refused", f.check("buy", shop, List.of(n(2)), reading(at(BUYER, 2 * price, 0),
                rec("Oracle.State", Bytes.of(new byte[27]), n(price), T0.subtract(n(60_000)))), BUYER));
        assertEquals("input:price", f.lastRefusal);
        ok(f.check("close", shop, List.of(), at(fill(0x0A), 0, 10_000_000), fill(0x0A)));
        f.modifierRefused("close", shop, List.of(), at(STRANGER, 0, 10_000_000), STRANGER);
        assertTrue(bought.startsWith("ok:"));
        f.mutants("buy", shop, List.of(n(2)), reading(at(BUYER, 2 * price, 0), fresh), BUYER);
        f.mutants("close", shop, List.of(), at(fill(0x0A), 0, 10_000_000), fill(0x0A));
        // Against the Java example: its stale check reads validFrom (validFrom - updatedAt <= maxAge), the port's the whole
        // window (before(updatedAt + maxAge), D11), so a post at most a window older than maxAge is fresh for the Java shop
        // and stale for the port; and the Java shop does not check the read state's keys.
        assertEquals(2, f.javaDiffs.size(), f.javaDiffs.toString());
        assertTrue(f.javaDiffs.get(0).contains("the Java example accepts, the port refuses"), f.javaDiffs.toString());
        JsLeg.check(f);
        report(f);
    }

    // ------------------------------------------------------------------ the fees ports

    @Test
    @Order(3)
    void pot() throws Exception {
        var f = LEGS.get("Pot").reference("org.julclang.state.examples.fees", "init");
        Rec pot = rec("State", Bytes.of(fill(0x0A)), n(0), n(0));
        ok(f.check("init", null, List.of(), at(fill(0x0A), 0, 0), fill(0x0A)));
        Tx one = deposits(f, at(BUYER, 0, 0), new DepositV(vv(new byte[0], new byte[0], n(500_000)), null));
        ok(f.check("collect", pot, List.of(), one, BUYER));
        // #85: deposits holding token names of unequal length (00ff, 01), as the registry carries them (the wire's order).
        Tx two = deposits(f, at(BUYER, 0, 0), new DepositV(vv(new byte[0], new byte[0], n(500_000), TOKEN_POLICY, N00FF, n(1), TOKEN_POLICY, N01, n(2)), null),
                new DepositV(vv(new byte[0], new byte[0], n(700_000)), null));
        ok(f.check("collect", pot, List.of(), two, BUYER));
        assertEquals("refused", f.check("collect", pot, List.of(), deposits(f, at(BUYER, 0, 0)), BUYER));
        ok(f.check("handOver", pot, List.of(Bytes.of(BUYER)), null, fill(0x0A)));
        f.modifierRefused("handOver", pot, List.of(Bytes.of(BUYER)), null, STRANGER);
        ok(f.check("close", pot, List.of(), at(fill(0x0A), 0, 10_000_000), fill(0x0A)));
        f.mutants("collect", pot, List.of(), two, BUYER);
        f.mutants("handOver", pot, List.of(Bytes.of(BUYER)), null, fill(0x0A));
        assertEquals(List.of(), f.javaDiffs, "the port and examples/fees' Pot agree on every vector");
        JsLeg.check(f);
        report(f);
    }

    @Test
    @Order(4)
    void stall() throws Exception {
        var f = LEGS.get("Stall").reference("org.julclang.state.examples.fees", "init");
        Rec stall = rec("State", Bytes.of(fill(0x0A)), n(0));
        ok(f.check("init", null, List.of(), at(fill(0x0A), 0, 0), fill(0x0A)));
        String sold = ok(f.check("sell", stall, List.of(ada(10)), at(BUYER, 10_000_000, 0), BUYER));
        assertTrue(sold.contains(java.util.HexFormat.of().formatHex(POT_ID)), "the deposit names the params' pot");
        ok(f.check("sell", stall, List.of(n(2_000_000)), at(BUYER, 2_000_000, 0), BUYER));
        assertEquals("refused", f.check("sell", stall, List.of(ada(10)), at(BUYER, 9_999_999, 0), BUYER));
        assertEquals("refused", f.check("sell", stall, List.of(n(1_999_999)), at(BUYER, 1_999_999, 0), BUYER));
        ok(f.check("close", stall, List.of(), at(fill(0x0A), 0, 10_000_000), fill(0x0A)));
        f.modifierRefused("close", stall, List.of(), at(STRANGER, 0, 10_000_000), STRANGER);
        f.mutants("sell", stall, List.of(ada(10)), at(BUYER, 10_000_000, 0), BUYER);
        assertEquals(List.of(), f.javaDiffs, "the port and examples/fees' Stall agree on every vector");
        JsLeg.check(f);
        report(f);
    }

    // ------------------------------------------------------------------ Tips and Tipper: what the ports leave out

    static Rec note(byte[] to, String tag) {
        return rec("Note", Bytes.of(to), Bytes.hex(tag));
    }

    static Rec tips(MapV credits, ValueV taken, Opt last) {
        return rec("State", Bytes.of(fill(0x0A)), credits, taken, last);
    }

    @Test
    @Order(5)
    void tips() throws Exception {
        var f = LEGS.get("Tips");
        Rec empty = tips(MapV.EMPTY, ValueV.NONE, Opt.NONE);
        ValueV taken = vv(TOKEN_POLICY, N00FF, n(1), TOKEN_POLICY, N01, n(2));
        ok(f.check("init", null, List.of(), at(fill(0x0A), 0, 0), fill(0x0A)));
        Tx three = deposits(f, at(BUYER, 0, 0),
                new DepositV(vv(new byte[0], new byte[0], ada(3), TOKEN_POLICY, N00FF, n(1), TOKEN_POLICY, N01, n(2)), note(A, "01")),
                new DepositV(vv(new byte[0], new byte[0], ada(2)), note(B, "02")),
                new DepositV(vv(new byte[0], new byte[0], ada(1)), note(A, "03")));
        ok(f.check("credit", empty, List.of(), three, BUYER));
        assertEquals("refused", f.check("credit", empty, List.of(), deposits(f, at(BUYER, 0, 0)), BUYER));
        // A memo is a checked input of the action's memo type: a 27-byte key refuses the call (#memo:).
        assertEquals("refused", f.check("credit", empty, List.of(), deposits(f, at(BUYER, 0, 0),
                new DepositV(vv(new byte[0], new byte[0], ada(1)), rec("Note", Bytes.of(new byte[27]), Bytes.hex("01")))), BUYER));
        assertEquals("input:deposits", f.lastRefusal);
        Rec credited = tips(map(Bytes.of(A), ada(4), Bytes.of(B), ada(2)), taken, Opt.some(rec("Last", Bytes.of(BUYER),
                vv(new byte[0], new byte[0], ada(6), TOKEN_POLICY, N00FF, n(1), TOKEN_POLICY, N01, n(2)))));
        ok(f.check("credit", credited, List.of(), deposits(f, at(BUYER, 0, 0), new DepositV(vv(new byte[0], new byte[0], ada(1)), note(B, "04"))), BUYER));
        ok(f.check("claim", credited, List.of(), funds(f, at(A, 0, 6_000_000), ValueV.NONE, taken), A));
        assertEquals("refused", f.check("claim", credited, List.of(), funds(f, at(STRANGER, 0, 6_000_000), ValueV.NONE, taken), STRANGER));
        // close: the native assets taken go to the owner with a datum holding a value (a record's Value field, D5).
        ok(f.check("close", credited, List.of(), funds(f, at(fill(0x0A), 0, 8_000_000), ValueV.NONE, taken), fill(0x0A)));
        ok(f.check("close", empty, List.of(), funds(f, at(fill(0x0A), 0, 2_000_000), ValueV.NONE, ValueV.NONE), fill(0x0A)));
        f.modifierRefused("close", credited, List.of(), funds(f, at(STRANGER, 0, 8_000_000), ValueV.NONE, taken), STRANGER);
        ok(f.check("lastValue", credited, List.of(), null, null));
        f.mutants("credit", empty, List.of(), three, BUYER);
        f.mutants("claim", credited, List.of(), funds(f, at(A, 0, 6_000_000), ValueV.NONE, taken), A);
        f.mutants("close", credited, List.of(), funds(f, at(fill(0x0A), 0, 8_000_000), ValueV.NONE, taken), fill(0x0A));
        JsLeg.check(f);
        report(f);
    }

    static Tx jar(Tx t, Rec tipsState) {
        return t.withReads(Map.of("jar", tipsState));
    }

    @Test
    @Order(6)
    void tipper() throws Exception {
        var f = LEGS.get("Tipper");
        Rec s = rec("State", Bytes.of(fill(0x0A)), ada(5), Bytes.hex("00"));
        Rec jarState = rec("Tips.State", Bytes.of(fill(0x0A)), map(Bytes.of(A), ada(1)), vv(TOKEN_POLICY, N01, n(1)),
                Opt.some(rec("Tips.Last", Bytes.of(B), vv(new byte[0], new byte[0], ada(1)))));
        ValueV offered = vv(TOKEN_POLICY, N00FF, n(1), TOKEN_POLICY, N01, n(2));
        ok(f.check("init", null, List.of(), at(fill(0x0A), 0, 0), fill(0x0A)));
        // A tip of lovelace and native assets (two names of unequal length), deposited into the jar with a Note memo.
        String tipped = ok(f.check("tip", s, List.of(Bytes.of(A), Bytes.hex("0102"), ada(3)),
                jar(funds(f, at(BUYER, 3_000_000, 5_000_000), offered, ValueV.NONE), jarState), BUYER));
        assertTrue(tipped.contains(java.util.HexFormat.of().formatHex(JAR_ID)), "the deposit names the params' jar");
        ok(f.check("tip", s, List.of(Bytes.of(B), Bytes.hex(""), ada(2)), jar(funds(f, at(BUYER, 2_000_000, 5_000_000), ValueV.NONE, ValueV.NONE),
                rec("Tips.State", Bytes.of(fill(0x0A)), MapV.EMPTY, ValueV.NONE, Opt.NONE)), BUYER));
        assertEquals("refused", f.check("tip", s, List.of(Bytes.of(A), Bytes.hex("01"), ada(51)),
                jar(funds(f, at(BUYER, 51_000_000, 5_000_000), ValueV.NONE, ValueV.NONE), jarState), BUYER));
        assertEquals("refused", f.check("tip", s, List.of(Bytes.of(A), Bytes.hex("01"), ada(3)),
                jar(funds(f, at(BUYER, 2_999_999, 5_000_000), ValueV.NONE, ValueV.NONE), jarState), BUYER));
        // A read state whose map is not canonical: a checked input (#read:), refused where the call starts.
        var unsorted = new MapV(List.of(Bytes.of(B), Bytes.of(A)), List.of(ada(1), ada(1)));
        assertEquals("refused", f.check("tip", s, List.of(Bytes.of(A), Bytes.hex("01"), ada(3)), jar(funds(f, at(BUYER, 3_000_000, 5_000_000),
                ValueV.NONE, ValueV.NONE), rec("Tips.State", Bytes.of(fill(0x0A)), unsorted, ValueV.NONE, Opt.NONE)), BUYER));
        assertEquals("input:jar", f.lastRefusal);
        // A full jar (20 credits): the require reads the other contract's map.
        var full = new LinkedHashMap<Bytes, Object>();
        for (int k = 0; k < 20; k++) full.put(Bytes.of(fill(0x30 + k)), ada(1));
        assertEquals("refused", f.check("tip", s, List.of(Bytes.of(A), Bytes.hex("01"), ada(3)), jar(funds(f, at(BUYER, 3_000_000, 5_000_000),
                ValueV.NONE, ValueV.NONE), rec("Tips.State", Bytes.of(fill(0x0A)), MapV.of(full), ValueV.NONE, Opt.NONE)), BUYER));
        // Sends with datums: a record, then an enum.
        ok(f.check("refund", s, List.of(Bytes.of(A), ada(2), false), funds(f, at(fill(0x0A), 0, 10_000_000), ValueV.NONE, ValueV.NONE), fill(0x0A)));
        ok(f.check("refund", s, List.of(Bytes.of(A), ada(2), true), funds(f, at(fill(0x0A), 0, 10_000_000), ValueV.NONE, ValueV.NONE), fill(0x0A)));
        assertEquals("refused", f.check("refund", s, List.of(Bytes.of(A), ada(6), false), funds(f, at(fill(0x0A), 0, 10_000_000), ValueV.NONE,
                ValueV.NONE), fill(0x0A)));
        f.modifierRefused("refund", s, List.of(Bytes.of(A), ada(2), false), funds(f, at(STRANGER, 0, 10_000_000), ValueV.NONE, ValueV.NONE), STRANGER);
        ok(f.check("close", s, List.of(), at(fill(0x0A), 0, 10_000_000), fill(0x0A)));
        f.mutants("tip", s, List.of(Bytes.of(A), Bytes.hex("0102"), ada(3)), jar(funds(f, at(BUYER, 3_000_000, 5_000_000), offered, ValueV.NONE), jarState),
                BUYER);
        f.mutants("refund", s, List.of(Bytes.of(A), ada(2), true), funds(f, at(fill(0x0A), 0, 10_000_000), ValueV.NONE, ValueV.NONE), fill(0x0A));
        JsLeg.check(f);
        report(f);
    }

    /**
     * Review r1, S1 and C1: a receiver of every memo type the checker accepts builds (JuLC and SubsetLint included), in one
     * contract (so helpers whose types share a Java representation, Key, Hash and Bytes, must not collide).
     */
    @Test
    void aReceiverOfEveryMemoTypeBuilds() throws Exception {
        String src = """
                jsc 2
                package probe

                enum E { A, B { x: Int } }
                type R = { k: Key, o: Option<Bool>, e: E }

                contract Every {
                  state { n: Int }
                  init() { return { n: 0 } }
                  action a0() receives Int { return { n: n + funds.deposits.length } }
                  action a1() receives Bool { return { n: n + funds.deposits.length } }
                  action a2() receives Bytes { return { n: n + funds.deposits.length } }
                  action a3() receives Key { return { n: n + funds.deposits.length } }
                  action a4() receives Hash { return { n: n + funds.deposits.length } }
                  action a5() receives Value { return { n: n + funds.deposits.length } }
                  action a6() receives Address { return { n: n + funds.deposits.length } }
                  action a7() receives Credential { return { n: n + funds.deposits.length } }
                  action a8() receives Option<Int> { return { n: n + funds.deposits.length } }
                  action a9() receives List<Key> { return { n: n + funds.deposits.length } }
                  action a10() receives Map<Bytes, Int> { return { n: n + funds.deposits.length } }
                  action a11() receives Map<Key, Option<Bool>> { return { n: n + funds.deposits.length } }
                  action a12() receives E { return { n: n + funds.deposits.length } }
                  action a13() receives R { return { n: n + funds.deposits.length } }
                  action a14() receives List<E> { return { n: n + funds.deposits.length } }
                  action a15() receives Option<R> { return { n: n + funds.deposits.length } }
                }
                """;
        var r = Toolchain.build(Map.of("probe/Every.jsc", src), Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        System.out.println("[M2c] a receiver of each of 16 memo types builds: " + r.contracts().get(0).scriptSizeBytes() + " bytes");
    }

    // ------------------------------------------------------------------ Inbox: every memo shape (review r1, S1, F5, C1, C2)

    static final Rec PLAIN = rec("Plain");
    static final AddrV STAKED = new AddrV(new CredV(true, Bytes.of(fill(0x5C))), Opt.some(new StakeHash(new CredV(false, Bytes.of(fill(0x5D))))));

    static Rec slip(boolean urgent, Opt ref, Rec kind, List<Object> tags, MapV shares, ValueV extra, AddrV reply) {
        return rec("Slip", urgent, ref, kind, new ArrayList<>(tags), shares, extra, reply);
    }

    static Rec inbox(long urgent, long refs, long bonus, long tags, long shares, long extra, Opt reply, long keys, long names) {
        return rec("State", Bytes.of(fill(0x0A)), n(urgent), n(refs), n(bonus), n(tags), n(shares), n(extra), reply, n(keys), n(names), false, Opt.NONE, PLAIN);
    }

    static final Rec INBOX = inbox(0, 0, 0, 0, 0, 0, Opt.NONE, 0, 0);

    static ValueV lovelace(long q) {
        return vv(new byte[0], new byte[0], n(q));
    }

    static Rec slipA() {
        return slip(true, Opt.some(n(5)), rec("Bonus", n(3)), List.of(Bytes.hex("01"), Bytes.hex("0203")),
                map(Bytes.of(A), n(2), Bytes.of(B), n(3)), vv(new byte[0], new byte[0], n(7), TOKEN_POLICY, N00FF, n(1), TOKEN_POLICY, N01, n(-2)),
                JscV2M2FourWayTest.wallet(A));
    }

    static Rec slipB() {
        return slip(false, Opt.NONE, PLAIN, List.of(), MapV.EMPTY, ValueV.NONE, STAKED);
    }

    @Test
    @Order(7)
    void inbox() throws Exception {
        var f = LEGS.get("Inbox");
        ok(f.check("init", null, List.of(), at(fill(0x0A), 0, 0), fill(0x0A)));
        // Honest memos of every shape: two slips (every field read), then each scalar memo type.
        Tx two = deposits(f, at(BUYER, 0, 0), new DepositV(lovelace(2_000_000), slipA()), new DepositV(lovelace(1_000_000), slipB()));
        ok(f.check("post", INBOX, List.of(), two, BUYER));
        ok(f.check("post", INBOX, List.of(), deposits(f, at(BUYER, 0, 0), new DepositV(lovelace(1_000_000), slipB())), BUYER));
        assertEquals("refused", f.check("post", INBOX, List.of(), deposits(f, at(BUYER, 0, 0)), BUYER));
        ok(f.check("keyed", INBOX, List.of(), deposits(f, at(BUYER, 0, 0), new DepositV(lovelace(1_000_000), Bytes.of(A))), BUYER));
        ok(f.check("named", INBOX, List.of(), deposits(f, at(BUYER, 0, 0), new DepositV(lovelace(1_000_000), Bytes.hex("0102")),
                new DepositV(lovelace(1_000_000), Bytes.hex(""))), BUYER));
        ok(f.check("valued", INBOX, List.of(), deposits(f, at(BUYER, 0, 0), new DepositV(lovelace(1_000_000), vv(TOKEN_POLICY, N01, n(1))),
                new DepositV(lovelace(1_000_000), ValueV.NONE)), BUYER));
        // Memos the interpreters can express that are not well formed: refused on every leg where the action starts.
        var bad = new LinkedHashMap<String, Object[]>();
        bad.put("a 27-byte Key memo", new Object[]{"keyed", Bytes.of(new byte[27])});
        bad.put("a 29-byte Key memo", new Object[]{"keyed", Bytes.of(new byte[29])});
        bad.put("a slip whose map key has 27 bytes", new Object[]{"post", slip(false, Opt.NONE, PLAIN, List.of(), map(Bytes.of(new byte[27]), n(1)),
                ValueV.NONE, STAKED)});
        bad.put("a slip whose address has a 27-byte hash", new Object[]{"post", slip(false, Opt.NONE, PLAIN, List.of(), MapV.EMPTY, ValueV.NONE,
                JscV2M2FourWayTest.wallet(new byte[27]))});
        BigInteger over = BigInteger.ONE.shiftLeft(127);
        bad.put("an unread Value memo of quantity 2^127", new Object[]{"valued", vv(TOKEN_POLICY, N01, over)});
        bad.put("an unread Value memo with a 33-byte name", new Object[]{"valued", vv(TOKEN_POLICY, Bytes.of(new byte[33]), n(1))});
        bad.put("an unread Value memo with a 33-byte policy", new Object[]{"valued", vv(new byte[33], N01, n(1))});
        // Review r2, C6: policies of other lengths than lovelace's and 28 bytes, within 32 (canonical CBOR would reorder them).
        bad.put("an unread Value memo of policies #0000 and #ff", new Object[]{"valued", vv(new byte[]{0, 0}, N01, n(1), new byte[]{(byte) 0xFF}, N01, n(1))});
        bad.put("an unread Value memo with a 29-byte policy", new Object[]{"valued", vv(JscV2M2FourWayTest.bytes(29, 0x70), N01, n(1))});
        bad.put("a slip whose Value has a 1-byte policy", new Object[]{"post", slip(false, Opt.NONE, PLAIN, List.of(), MapV.EMPTY,
                vv(new byte[]{1}, N01, n(1)), STAKED)});
        bad.put("a slip whose Value has quantity -2^127 - 1", new Object[]{"post", slip(false, Opt.NONE, PLAIN, List.of(), MapV.EMPTY,
                vv(TOKEN_POLICY, N01, over.negate().subtract(BigInteger.ONE)), STAKED)});
        bad.put("a slip whose Value has a 33-byte name", new Object[]{"post", slip(false, Opt.NONE, PLAIN, List.of(), MapV.EMPTY,
                vv(TOKEN_POLICY, Bytes.of(new byte[33]), n(1)), STAKED)});
        for (var e : bad.entrySet()) {
            String m = (String) e.getValue()[0];
            assertEquals("refused", f.check(m, INBOX, List.of(), deposits(f, at(BUYER, 0, 0), new DepositV(lovelace(1_000_000), e.getValue()[1])), BUYER),
                    e.getKey());
            assertEquals("input:deposits", f.lastRefusal, e.getKey());
        }
        ok(f.check("set", INBOX, List.of(true, Opt.some(n(4)), rec("Bonus", n(2))), null, fill(0x0A)));
        ok(f.check("set", INBOX, List.of(false, Opt.NONE, PLAIN), null, fill(0x0A)));
        f.modifierRefused("set", INBOX, List.of(true, Opt.NONE, PLAIN), null, STRANGER);
        ok(f.check("close", INBOX, List.of(), at(fill(0x0A), 0, 4_000_000), fill(0x0A)));
        f.modifierRefused("close", INBOX, List.of(), at(STRANGER, 0, 4_000_000), STRANGER);
        f.mutants("post", INBOX, List.of(), two, BUYER);
        f.mutants("set", INBOX, List.of(true, Opt.some(n(4)), rec("Bonus", n(2))), null, fill(0x0A));
        f.mutants("keyed", INBOX, List.of(), deposits(f, at(BUYER, 0, 0), new DepositV(lovelace(1_000_000), Bytes.of(A))), BUYER);
        JsLeg.check(f);
        report(f);
    }
    // ------------------------------------------------------------------ Stash: params checked at init (review r3, C7)

    /**
     * A writer whose state stores a Value made from its params, and a reader of that state: every state the writer can make
     * the reader can read. The params are a checked input of the init (a Value of lovelace's or 28-byte policies, a 28-byte
     * Hash), so params a reader would refuse are refused where the instance is made, on every leg.
     */
    @Test
    @Order(8)
    void stashParams_areCheckedAtInit_andEveryStateItMakesIsReadable() throws Exception {
        var f = LEGS.get("Stash");
        byte[] h = fill(0x71);
        ValueV held = vv(TOKEN_POLICY, N01, n(1), h, N01, n(1));
        Rec made = rec("State", n(7), held);
        ok(f.check("init", null, List.of(), at(fill(0x0A), 0, 0), fill(0x0A)));
        // stay and read are open and never name the caller: no signer.
        ok(f.check("stay", made, List.of(), at(fill(0x0A), 0, 0), null));
        f.mutants("stay", made, List.of(), at(fill(0x0A), 0, 0), null);
        JsLeg.check(f);
        // Params outside the domain: refused by the init on every leg (input:params), so no such instance exists.
        var bad = new LinkedHashMap<String, Rec>();
        bad.put("a Value of a 1-byte policy", stashParams(vv(new byte[]{1}, N01, n(1)), h));
        bad.put("a Value of policies #0000 and #ff", stashParams(vv(new byte[]{0, 0}, N01, n(1), new byte[]{(byte) 0xFF}, N01, n(1)), h));
        bad.put("a Value of a 29-byte policy", stashParams(vv(JscV2M2FourWayTest.bytes(29, 0x70), N01, n(1)), h));
        bad.put("a 27-byte Hash", stashParams(vv(TOKEN_POLICY, N01, n(1)), new byte[27]));
        // #90 review C9: the state each one's init would make without the check (params.v + Value.asset(params.h, #01, 1)),
        // the mint validator's candidate output: the validator leg runs, and refuses it.
        var unchecked = new LinkedHashMap<String, Rec>();
        unchecked.put("a Value of a 1-byte policy", rec("State", n(7), vv(new byte[]{1}, N01, n(1), h, N01, n(1))));
        unchecked.put("a Value of policies #0000 and #ff", rec("State", n(7), vv(new byte[]{0, 0}, N01, n(1), new byte[]{(byte) 0xFF}, N01, n(1), h, N01, n(1))));
        unchecked.put("a Value of a 29-byte policy", rec("State", n(7), vv(JscV2M2FourWayTest.bytes(29, 0x70), N01, n(1), h, N01, n(1))));
        unchecked.put("a 27-byte Hash", rec("State", n(7), vv(TOKEN_POLICY, N01, n(1), new byte[27], N01, n(1))));
        int refused = 0;
        for (var e : bad.entrySet()) {
            var w = new FourWay("Stash", SOURCES.get("Stash.jsc"), TRANSITIONS.get("Stash"), e.getValue(), MODULES);
            int runs = w.validatorRuns;
            assertEquals("refused", w.check("init", null, List.of(), at(fill(0x0A), 0, 0), fill(0x0A), unchecked.get(e.getKey())), e.getKey());
            assertEquals("input:params", w.lastRefusal, e.getKey());
            assertEquals(runs + 1, w.validatorRuns, e.getKey() + ": the mint validator ran on the unchecked writer's output");
            JsLeg.check(w);
            refused++;
        }
        // The reader reads what the writer made (its stored Value checked, though read is only n); a state holding a Value
        // the writer can no longer make is refused (input:got).
        var r = LEGS.get("StashReader");
        Rec reader = rec("State", n(0));
        ok(r.check("init", null, List.of(), at(fill(0x0A), 0, 0), fill(0x0A)));
        ok(r.check("peek", reader, List.of(), at(BUYER, 0, 0).withReads(Map.of("stash", rec("Stash.State", n(7), held))), null));
        assertEquals("refused", r.check("peek", reader, List.of(), at(BUYER, 0, 0).withReads(Map.of("stash",
                rec("Stash.State", n(7), vv(new byte[]{1}, N01, n(1))))), null));
        assertEquals("input:got", r.lastRefusal);
        r.mutants("peek", reader, List.of(), at(BUYER, 0, 0).withReads(Map.of("stash", rec("Stash.State", n(7), held))), null);
        JsLeg.check(r);
        System.out.println("[M2c five-way] Stash: " + refused + " params refused by the init on every leg (input:params; the mint validator"
                + " refuses each unchecked output, #90 C9); StashReader reads"
                + " every state Stash makes");
        report(r);
    }
}
