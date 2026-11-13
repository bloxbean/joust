package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.Interpreter.AddrV;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.CredV;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.StakeHash;
import org.julclang.state.dsl.v2.Interpreter.StakePtr;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.Interpreter.ValueV;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.julclang.state.toolchain.FourWay.*;
import static org.julclang.state.toolchain.JscV2M1aFourWayTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M2a (J3, J11): values, credentials and addresses, funds, and the effects on native assets (send, mint, burn,
 * accept) on all five legs: both reference interpreters, the generated Java on the JVM, the transition program and the
 * validator, which gets each transaction as the runtime builds it (the state output holds what came in, plus what was
 * received, accepted and minted, less the payouts and sends; each send an output claimed in the registry; a mint under
 * {@code ByAction}). {@code Coins} covers every construct of M2a; {@code Dex} is the port of examples/dex, whose every
 * vector also runs on the Java example (outcomes stated where they differ). Mutation rejection: for accepted calls, each
 * transaction proposing a next state, payouts, sends, mint or accepted value one step away is rejected, and the accepted
 * transaction passes the attack suite. {@code Wards} covers addresses and credentials (a contract of its own: one fixture
 * holding everything is over the 16 KB script limit).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JscV2M2FourWayTest {

    static final Map<String, FourWay> LEGS = new LinkedHashMap<>();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] TOKEN_POLICY = fill(0x70), OTHER_POLICY = fill(0x71), STRANGER = fill(0x77), TRADER = fill(0x7E), LP = fill(0x1A);
    static final Bytes TOKEN = Bytes.of("HOSKY".getBytes(StandardCharsets.UTF_8));
    static final BigInteger MAX_Q = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE), MIN_Q = BigInteger.ONE.shiftLeft(127).negate();

    static BigInteger ada(long v) {
        return ADA.multiply(BigInteger.valueOf(v));
    }

    @BeforeAll
    static void build() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!LEGS.isEmpty()) return;
        Map<String, String> files = new LinkedHashMap<>();
        for (String c : List.of("Coins", "Wards", "Dex")) files.put("org/julclang/state/examples/dsl/" + c + ".jsc", Files.readString(DSL.resolve(c + ".jsc")));
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        for (var c : r.contracts()) {
            System.out.println("[M2] " + c.name() + ": " + c.scriptSizeBytes() + " bytes");
            LEGS.put(c.name(), new FourWay(c.name(), files.get("org/julclang/state/examples/dsl/" + c.name() + ".jsc"), c.transitions()));
        }
    }

    // ------------------------------------------------------------------ values

    /** A value from (policy, name, quantity) triples; a policy or name is bytes or a Bytes. */
    static ValueV vv(Object... triples) {
        var m = new TreeMap<String, TreeMap<String, BigInteger>>();
        for (int i = 0; i < triples.length; i += 3) {
            String p = hex(triples[i]), n = hex(triples[i + 1]);
            m.computeIfAbsent(p, k -> new TreeMap<>()).merge(n, (BigInteger) triples[i + 2], BigInteger::add);
        }
        return new ValueV(m);
    }

    static String hex(Object o) {
        return HexFormat.of().formatHex(o instanceof Bytes b ? b.bytes() : (byte[]) o);
    }

    static ValueV tokens(long n) {
        return tokens(BigInteger.valueOf(n));
    }

    static ValueV tokens(BigInteger n) {
        return vv(TOKEN_POLICY, TOKEN, n);
    }

    /** {@code n} bytes of 0x33 starting with {@code first}: a 29-byte name of a reserved kind (0x4F, 0x49) or not (0x4B). */
    static byte[] prefixed(int n, int first) {
        byte[] x = bytes(n, 0x33);
        x[0] = (byte) first;
        return x;
    }

    static CredV keyCred(byte[] k) {
        return new CredV(false, Bytes.of(k));
    }

    static CredV scriptCred(byte[] h) {
        return new CredV(true, Bytes.of(h));
    }

    static AddrV wallet(byte[] k) {
        return new AddrV(keyCred(k), Opt.NONE);
    }

    static byte[] bytes(int n, int b) {
        byte[] x = new byte[n];
        java.util.Arrays.fill(x, (byte) b);
        return x;
    }

    static String ok(String outcome) {
        assertTrue(outcome.startsWith("ok:"), outcome);
        return outcome;
    }

    static void report(FourWay f) {
        System.out.println("[M2 five-way] " + f.name + ": " + f.calls + " calls, interpreter = JVM = transition program = validator (" + f.refusals
                + " refusals, " + f.validatorRuns + " validator runs, " + f.transactionLevelRefusals + " transaction-level); mutants rejected: "
                + f.mutantsRejected + ", attacks rejected: " + f.attacksRejected + ", modifier refusals: " + f.modifierRefusals);
        assertTrue(f.refusals > 0 && f.refusals < f.calls, "accepted and refused calls");
        assertTrue(f.mutantsRejected > 0 && f.attacksRejected > 0, "mutation and attack coverage");
    }

    // ------------------------------------------------------------------ Coins: every construct of M2a

    /** A Coins state: the owner, the beneficiary's address, the receipts outstanding, the label (named after the instance). */
    Rec coins(FourWay f, AddrV beneficiary, long minted) {
        return rec("State", b(OWNER), beneficiary, n(minted), label(f));
    }

    static Bytes label(FourWay f) {
        return Bytes.of(com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash224(f.harness.instanceId()));
    }

    /** A call with the native assets offered and held. */
    Tx with(FourWay f, byte[] caller, long received, long balance, ValueV offered, ValueV held) {
        return f.funds(at(caller, received, balance), offered, held);
    }

    @Test
    @Order(1)
    void coins() throws Exception {
        var f = LEGS.get("Coins");
        Bytes label = label(f);
        byte[] own = f.harness.policy();
        AddrV ben = wallet(BEN);
        Rec s0 = coins(f, ben, 0);
        ValueV none = ValueV.NONE;

        // init: funds.self is the instance's id; the beneficiary is a checked input.
        ok(f.check("init", null, List.of(ben), with(f, OWNER, 0, 0, none, none), OWNER));
        assertEquals("refused", f.check("init", null, List.of(new AddrV(keyCred(bytes(27, 0xB1)), Opt.NONE)), with(f, OWNER, 0, 0, none, none), OWNER, s0));
        assertEquals("input:beneficiary", f.lastRefusal);
        // Review r2 S6: a checked address may be the contract's own script; Coins' init refuses one with the guard idiom.
        assertEquals("refused", f.check("init", null, List.of(new AddrV(scriptCred(own), Opt.some(new StakeHash(keyCred(BEN))))),
                with(f, OWNER, 0, 0, none, none), OWNER, s0));
        assertTrue(f.lastRefusal.endsWith(":the beneficiary cannot be this contract's own script"), f.lastRefusal);
        ok(f.check("init", null, List.of(new AddrV(scriptCred(fill(0x5C)), Opt.NONE)), with(f, OWNER, 0, 0, none, none), OWNER));

        // lodge: accept in two parts (the sum must equal exactly what is offered), mint receipts, send them to the caller.
        for (long n : List.of(1L, 4L, 7L)) ok(f.check("lodge", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(n)), with(f, ANN, 0, 0, tokens(n), none), ANN));
        assertEquals("refused", f.check("lodge", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(4)), with(f, ANN, 0, 0, tokens(3), none), ANN));
        assertEquals("domain:accepted", f.lastRefusal);
        assertEquals("refused", f.check("lodge", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(4)), with(f, ANN, 0, 0, vv(TOKEN_POLICY, TOKEN, n(4), OTHER_POLICY, TOKEN, n(1)), none), ANN));
        assertEquals("refused", f.check("lodge", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(0)), with(f, ANN, 0, 0, none, none), ANN));
        // A reserved token kind of the own policy cannot be accepted (ADR-0007 D2b), nor a name over 32 bytes built.
        assertEquals("refused", f.check("lodge", s0, List.of(Bytes.of(own), Bytes.of(bytes(32, 3)), n(2)), with(f, ANN, 0, 0, vv(own, bytes(32, 3), n(2)), none), ANN));
        assertEquals("domain:accept", f.lastRefusal);
        assertEquals("refused", f.check("lodge", s0, List.of(Bytes.of(TOKEN_POLICY), Bytes.of(bytes(33, 3)), n(2)), with(f, ANN, 0, 0, none, none), ANN));
        assertEquals("domain:Value.asset", f.lastRefusal);
        ok(f.check("lodge", s0, List.of(Bytes.of(OTHER_POLICY), Bytes.of(new byte[0]), n(5)), with(f, ANN, 0, 0, vv(OTHER_POLICY, new byte[0], n(5)), none), ANN));

        // redeem: receipts back and burned (net), tokens sent from what is held.
        Rec s5 = coins(f, ben, 5);
        ValueV held = vv(TOKEN_POLICY, TOKEN, n(9), OTHER_POLICY, TOKEN, n(1));
        for (long n : List.of(1L, 5L)) {
            ok(f.check("redeem", s5, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(n)), with(f, ANN, 0, 3_000_000, vv(own, label, n(n)), held), ANN));
        }
        assertEquals("refused", f.check("redeem", s5, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(6)), with(f, ANN, 0, 3_000_000, vv(own, label, n(6)), held), ANN));
        assertEquals("refused", f.check("redeem", coins(f, ben, 20), List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(10)),
                with(f, ANN, 0, 3_000_000, vv(own, label, n(10)), held), ANN));
        assertTrue(f.lastRefusal.endsWith(":not enough held"), f.lastRefusal);
        assertEquals("refused", f.check("redeem", s5, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(2)), with(f, ANN, 0, 3_000_000, vv(own, label, n(1)), held), ANN));

        // probe: value arithmetic, comparison and std/value; accepts whatever is offered.
        ok(f.check("probe", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(5), n(3)), with(f, ANN, 0, 0, tokens(2), none), ANN));
        ok(f.check("probe", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(0), n(9)), with(f, ANN, 0, 0, none, none), ANN));
        ok(f.check("probe", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(4), n(0)), with(f, ANN, 0, 0, none, none), ANN));
        assertEquals("refused", f.check("probe", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(-2), n(3)), with(f, ANN, 0, 0, none, none), ANN));
        assertEquals("domain:covers", f.lastRefusal);
        assertEquals("refused", f.check("probe", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(4), BigInteger.TWO.pow(126)), with(f, ANN, 0, 0, none, none), ANN));
        assertEquals("domain:scale", f.lastRefusal);
        assertEquals("refused", f.check("probe", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, MAX_Q.add(BigInteger.ONE), n(1)), with(f, ANN, 0, 0, none, none), ANN));
        assertEquals("domain:Value.asset", f.lastRefusal);
        // n = 2^127 - 1: the asset and the lovelace are in range (two entries); k = 2 overflows scale.
        ok(f.check("probe", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, MAX_Q, n(1)), with(f, ANN, 0, 0, none, none), ANN));
        assertEquals("refused", f.check("probe", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, MAX_Q, n(2)), with(f, ANN, 0, 0, none, none), ANN));
        // What is offered must be native assets, positive, with no reserved name of the own policy (0x4B is not one here).
        assertEquals("refused", f.check("probe", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(1), n(1)), with(f, ANN, 0, 0, vv(new byte[0], new byte[0], n(5)), none), ANN));
        assertEquals("domain:accept", f.lastRefusal);
        assertEquals("refused", f.check("probe", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(1), n(1)), with(f, ANN, 0, 0, vv(own, bytes(29, 0x4F), n(1)), none), ANN));
        ok(f.check("probe", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(1), n(1)), with(f, ANN, 0, 0, vv(own, bytes(29, 0x4B), n(1)), none), ANN));

        // Subtraction at the bottom of the range (review F2): scale(1, v) - v negates v's -2^127 (2^127: out of range), and
        // scale(-1, v) - v of 2^126 + 1 reaches -2^127 - 2.
        BigInteger two126 = BigInteger.TWO.pow(126);
        assertEquals("refused", f.check("probe", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, MIN_Q, n(1)), with(f, ANN, 0, 0, none, none), ANN));
        assertEquals("domain:Value -", f.lastRefusal);
        assertEquals("refused", f.check("probe", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, two126.add(BigInteger.ONE), n(-1)), with(f, ANN, 0, 0, none, none), ANN));
        assertEquals("domain:Value -", f.lastRefusal);
        // The accepted sum overflows: two halves of 2^127 (review F2).
        assertEquals("refused", f.check("lodge", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, MAX_Q.add(BigInteger.ONE)),
                with(f, ANN, 0, 0, tokens(MAX_Q.add(BigInteger.ONE)), none), ANN));
        assertEquals("domain:accept", f.lastRefusal);

        // Review C1: names of unequal length (00ff before 01 in the ledger's order, after it in the wire's), offered through
        // the redeemer and the transition program's funds as the runtime encodes them, accepted as offered and as built.
        Bytes n00ff = Bytes.of(new byte[]{0, (byte) 0xFF}), n01 = Bytes.of(new byte[]{1});
        ValueV twoNames = vv(TOKEN_POLICY, n00ff, n(1), TOKEN_POLICY, n01, n(1));
        ok(f.check("probe", s0, List.of(Bytes.of(TOKEN_POLICY), n01, n(1), n(1)), with(f, ANN, 0, 0, twoNames, none), ANN));

        // Review F2: the generated mint check, refusing on every leg (forge has no guard of its own).
        Bytes coin = Bytes.of("coin".getBytes(StandardCharsets.UTF_8));
        ok(f.check("forge", s0, List.of(coin, n(5), n(2)), with(f, ANN, 0, 0, none, none), ANN));
        ok(f.check("forge", s0, List.of(coin, n(2), n(2)), with(f, ANN, 0, 0, none, none), ANN));
        ok(f.check("forge", s0, List.of(Bytes.of(prefixed(29, 0x4B)), n(2), n(1)), with(f, ANN, 0, 0, none, none), ANN));
        for (List<Object> bad : List.<List<Object>>of(List.of(coin, n(0), n(1)), List.of(coin, n(-1), n(1)), List.of(coin, n(2), n(0)),
                List.of(coin, n(2), n(-1)), List.of(Bytes.of(bytes(33, 3)), n(1), n(1)), List.of(Bytes.of(bytes(32, 3)), n(1), n(1)),
                List.of(Bytes.of(prefixed(29, 0x4F)), n(1), n(1)), List.of(Bytes.of(prefixed(29, 0x49)), n(1), n(1)),
                List.of(coin, MAX_Q.add(BigInteger.ONE), n(1)), List.of(coin, n(1), MAX_Q.add(n(3))))) {
            assertEquals("refused", f.check("forge", s0, bad, with(f, ANN, 0, 0, none, none), ANN), bad.toString());
            assertEquals("domain:mint", f.lastRefusal, bad.toString());
        }
        // The generated send check: a reserved name of the own policy (the instance's id; an owned object's), a negative.
        ValueV ownHeld = vv(own, coin, n(5));
        ok(f.check("give", s0, List.of(coin, n(3)), with(f, ANN, 0, 3_000_000, none, ownHeld), ANN));
        for (List<Object> bad : List.<List<Object>>of(List.of(Bytes.of(f.harness.instanceId()), n(1)), List.of(Bytes.of(prefixed(29, 0x4F)), n(1)),
                List.of(coin, n(-1)))) {
            assertEquals("refused", f.check("give", s0, bad, with(f, ANN, 0, 3_000_000, none, ownHeld), ANN), bad.toString());
            assertEquals("domain:send", f.lastRefusal, bad.toString());
        }
        // Transaction-level failures (semantics §2, the transaction-level rules; review F1, S2): every leg but the validator accepts, and the ledger
        // refuses the transaction: a burn of what the instance does not hold, a send of more than it holds, a net mint
        // outside Int64, an accepted quantity no output can hold.
        f.transactionLevel("forge", s0, List.of(coin, n(1), n(3)), with(f, ANN, 0, 0, none, none), ANN);
        f.transactionLevel("give", s0, List.of(coin, n(6)), with(f, ANN, 0, 3_000_000, none, ownHeld), ANN);
        f.transactionLevel("forge", s0, List.of(coin, BigInteger.TWO.pow(64), n(1)), with(f, ANN, 0, 0, none, none), ANN);
        f.transactionLevel("probe", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(1), n(1)), with(f, ANN, 0, 0, tokens(MAX_Q), tokens(10)), ANN);

        // close: every receipt burned; the held assets go to the beneficiary.
        long minAda = f.harness.minAda();
        ok(f.check("close", s0, List.of(), with(f, OWNER, 0, 4_000_000 + minAda, none, tokens(3)), OWNER));
        ok(f.check("close", s0, List.of(), with(f, OWNER, 0, minAda, none, none), OWNER));
        ok(f.check("close", s0, List.of(), with(f, OWNER, 0, minAda, none, twoNames), OWNER));   // C1: held in the wire's order too
        assertEquals("refused", f.check("close", s5, List.of(), with(f, OWNER, 0, minAda, none, tokens(3)), OWNER));
        f.modifierRefused("close", s0, List.of(), with(f, STRANGER, 0, minAda, none, none), STRANGER);

        // Mutation rejection on every method that moves value.
        f.mutants("init", null, List.of(ben), with(f, OWNER, 0, 0, none, none), OWNER);
        f.mutants("lodge", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(4)), with(f, ANN, 0, 0, tokens(4), none), ANN);
        f.mutants("redeem", s5, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(2)), with(f, ANN, 0, 3_000_000, vv(own, label, n(2)), held), ANN);
        f.mutants("probe", s0, List.of(Bytes.of(TOKEN_POLICY), TOKEN, n(5), n(3)), with(f, ANN, 0, 0, tokens(2), none), ANN);
        // forge reads no caller: its transaction needs no signer (the attack suite's "signer missing" is no attack on it).
        f.mutants("forge", s0, List.of(coin, n(5), n(2)), with(f, ANN, 0, 0, none, none), null);
        f.mutants("give", s0, List.of(coin, n(3)), with(f, ANN, 0, 3_000_000, none, ownHeld), ANN);
        f.mutants("close", s0, List.of(), with(f, OWNER, 0, 4_000_000 + minAda, none, tokens(3)), OWNER);
        report(f);
    }

    // ------------------------------------------------------------------ Wards: addresses and credentials

    Rec wards(AddrV beneficiary, CredV guard) {
        return rec("State", b(OWNER), beneficiary, guard);
    }

    @Test
    @Order(2)
    void wards() throws Exception {
        var f = LEGS.get("Wards");
        AddrV ben = wallet(BEN);
        CredV guard = keyCred(OWNER);
        Rec s0 = wards(ben, guard);
        ValueV none = ValueV.NONE;

        // init: checked inputs (D5, J15): a 28-byte payment credential, a 28-byte staking hash, and never a pointer (review
        // S1: whatever its parts, negative or over the ledger's widths included).
        ok(f.check("init", null, List.of(ben, guard), at(OWNER, 0, 0), OWNER));
        ok(f.check("init", null, List.of(new AddrV(scriptCred(fill(0x5C)), Opt.some(new StakeHash(keyCred(BEN)))), scriptCred(fill(0x5C))), at(OWNER, 0, 0), OWNER));
        BigInteger huge = BigInteger.TWO.pow(200);
        for (StakePtr ptr : List.of(new StakePtr(n(7), n(2), n(0)), new StakePtr(n(-1), n(-1), n(-1)), new StakePtr(huge, huge, huge),
                new StakePtr(n(0), n(-5), n(0)))) {
            assertEquals("refused", f.check("init", null, List.of(new AddrV(keyCred(BEN), Opt.some(ptr)), guard), at(OWNER, 0, 0), OWNER, s0), ptr.toString());
            assertEquals("input:beneficiary", f.lastRefusal);
        }
        assertEquals("refused", f.check("init", null, List.of(new AddrV(keyCred(bytes(27, 0xB1)), Opt.NONE), guard), at(OWNER, 0, 0), OWNER, s0));
        assertEquals("input:beneficiary", f.lastRefusal);
        assertEquals("refused", f.check("init", null, List.of(new AddrV(keyCred(BEN), Opt.some(new StakeHash(keyCred(bytes(29, 1))))), guard),
                at(OWNER, 0, 0), OWNER, s0));
        assertEquals("refused", f.check("init", null, List.of(ben, scriptCred(bytes(27, 0x5C))), at(OWNER, 0, 0), OWNER, s0));
        assertEquals("input:guard", f.lastRefusal);

        // tip: a match on the guard; a key is paid (0 skipped, negative refused), a script is sent lovelace.
        for (long amount : List.of(-1L, 0L, 5_000_000L)) f.check("tip", s0, List.of(n(amount)), f.funds(at(OWNER, 0, 10_000_000), none, none), OWNER);
        Rec scripted = wards(ben, scriptCred(fill(0x5C)));
        for (long amount : List.of(0L, 5_000_000L)) ok(f.check("tip", scripted, List.of(n(amount)), f.funds(at(OWNER, 0, 10_000_000), none, none), OWNER));
        assertEquals("refused", f.check("tip", scripted, List.of(n(-1)), f.funds(at(OWNER, 0, 10_000_000), none, none), OWNER));
        assertEquals("domain:send", f.lastRefusal);
        // A send to the contract's own script is refused in the method, as the validator refuses it (I30; review F1).
        Rec ownScript = wards(ben, scriptCred(f.harness.policy()));
        assertEquals("refused", f.check("tip", ownScript, List.of(n(5_000_000)), f.funds(at(OWNER, 0, 10_000_000), none, none), OWNER));
        assertEquals("domain:send", f.lastRefusal);
        f.modifierRefused("tip", s0, List.of(n(5_000_000)), f.funds(at(STRANGER, 0, 10_000_000), none, none), STRANGER);

        // spread: sends in a loop and its branches; a share to the beneficiary pays the owner too.
        Rec share1 = rec("Share", wallet(ANN), n(2)), share0 = rec("Share", wallet(CAT), n(0)), share2 = rec("Share", ben, n(3));
        ok(f.check("spread", s0, List.of(List.of(share1, share0, share2), Bytes.of(TOKEN_POLICY), TOKEN), f.funds(at(OWNER, 0, 10_000_000), none, tokens(9)), OWNER));
        ok(f.check("spread", s0, List.of(List.of(share1), Bytes.of(TOKEN_POLICY), TOKEN), f.funds(at(OWNER, 0, 10_000_000), none, tokens(9)), OWNER));
        ok(f.check("spread", s0, List.of(List.of(), Bytes.of(TOKEN_POLICY), TOKEN), f.funds(at(OWNER, 0, 10_000_000), none, tokens(9)), OWNER));
        // A negative weight is skipped by the branch, as 0 is.
        ok(f.check("spread", s0, List.of(List.of(rec("Share", wallet(ANN), n(-1))), Bytes.of(TOKEN_POLICY), TOKEN),
                f.funds(at(OWNER, 0, 10_000_000), none, tokens(9)), OWNER));
        assertEquals("refused", f.check("spread", s0, List.of(List.of(rec("Share", new AddrV(keyCred(bytes(27, 1)), Opt.NONE), n(1))), Bytes.of(TOKEN_POLICY), TOKEN),
                f.funds(at(OWNER, 0, 10_000_000), none, tokens(9)), OWNER));
        assertEquals("input:shares", f.lastRefusal);
        // Review F1: sending 5 of a token the instance holds 1 of: every leg but the ledger accepts (a negative output).
        f.transactionLevel("spread", s0, List.of(List.of(rec("Share", wallet(ANN), n(5))), Bytes.of(TOKEN_POLICY), TOKEN),
                f.funds(at(OWNER, 0, 10_000_000), none, tokens(1)), OWNER);

        // rotate: structural equality of addresses and credentials, std/address.
        assertEquals("refused", f.check("rotate", s0, List.of(ben, guard), null, OWNER));
        ok(f.check("rotate", s0, List.of(ben, keyCred(ANN)), null, OWNER));
        ok(f.check("rotate", s0, List.of(new AddrV(scriptCred(fill(0x5C)), Opt.NONE), scriptCred(fill(0x5C))), null, OWNER));
        assertEquals("refused", f.check("rotate", s0, List.of(new AddrV(scriptCred(fill(0x5C)), Opt.NONE), keyCred(fill(0x5C))), null, OWNER));
        assertEquals("refused", f.check("rotate", s0, List.of(new AddrV(keyCred(BEN), Opt.some(new StakePtr(n(1), n(2), n(3)))), guard), null, OWNER));
        assertEquals("input:to", f.lastRefusal);
        ok(f.check("rotate", s0, List.of(new AddrV(keyCred(BEN), Opt.some(new StakeHash(keyCred(CAT)))), guard), null, OWNER));
        ok(f.check("rotate", wards(new AddrV(keyCred(BEN), Opt.some(new StakeHash(keyCred(CAT)))), guard), List.of(ben, guard), null, OWNER));

        long minAda = f.harness.minAda();
        ok(f.check("close", s0, List.of(), f.funds(at(OWNER, 0, 4_000_000 + minAda), none, tokens(3)), OWNER));
        ok(f.check("close", s0, List.of(), f.funds(at(OWNER, 0, minAda), none, none), OWNER));

        // Coins' vectors on Wards (the size of a reference script): review C1, names of unequal length (00ff before 01 in
        // the ledger's order, after it in the wire's) offered through the redeemer and the transition program's funds as
        // the runtime encodes them, accepted as built natively.
        Bytes n00ff = Bytes.of(new byte[]{0, (byte) 0xFF}), n01 = Bytes.of(new byte[]{1});
        ValueV twoNames = vv(TOKEN_POLICY, n00ff, n(1), TOKEN_POLICY, n01, n(1));
        ok(f.check("pair", s0, List.of(Bytes.of(TOKEN_POLICY), n00ff, n01), f.funds(at(ANN, 0, 0), twoNames, none), ANN));
        ok(f.check("pair", s0, List.of(Bytes.of(TOKEN_POLICY), n01, n00ff), f.funds(at(ANN, 0, 0), twoNames, none), ANN));
        assertEquals("refused", f.check("pair", s0, List.of(Bytes.of(TOKEN_POLICY), n00ff, n01), f.funds(at(ANN, 0, 0), vv(TOKEN_POLICY, n01, n(1)), none), ANN));
        assertEquals("domain:accepted", f.lastRefusal);
        // Value + and - and Value.lovelace at the edges of the range (review F2).
        ok(f.check("edges", s0, List.of(true, MAX_Q.subtract(BigInteger.ONE), n(1)), null, ANN));
        ok(f.check("edges", s0, List.of(false, MIN_Q.add(BigInteger.ONE), n(1)), null, ANN));
        assertEquals("refused", f.check("edges", s0, List.of(true, MAX_Q, n(1)), null, ANN));
        assertEquals("domain:Value +", f.lastRefusal);
        assertEquals("refused", f.check("edges", s0, List.of(true, MIN_Q, n(-1)), null, ANN));
        assertEquals("domain:Value +", f.lastRefusal);
        assertEquals("refused", f.check("edges", s0, List.of(false, n(0), MIN_Q), null, ANN));
        assertEquals("domain:Value -", f.lastRefusal);
        assertEquals("refused", f.check("edges", s0, List.of(false, MIN_Q, n(1)), null, ANN));
        assertEquals("domain:Value -", f.lastRefusal);
        for (BigInteger bad : List.of(MAX_Q.add(BigInteger.ONE), MIN_Q.subtract(BigInteger.ONE))) {
            assertEquals("refused", f.check("edges", s0, List.of(true, bad, n(0)), null, ANN));
            assertEquals("domain:Value.lovelace", f.lastRefusal);
        }

        // A destroy that leaves native assets (review r2 F13): every leg accepts retire, the validator's destroy equation
        // refuses its transaction; with nothing held it is accepted everywhere.
        ok(f.check("retire", s0, List.of(), f.funds(at(OWNER, 0, minAda), none, none), OWNER));
        f.transactionLevel("retire", s0, List.of(), f.funds(at(OWNER, 0, 4_000_000 + minAda), none, tokens(3)), OWNER);

        // Views: std/address, a credential's hash, an address with a staking key.
        f.check("beneficiaryKey", s0, List.of(), null, null);
        f.check("beneficiaryKey", wards(new AddrV(scriptCred(fill(0x5C)), Opt.NONE), guard), List.of(), null, null);
        f.check("guardHash", scripted, List.of(), null, null);
        f.check("walletOf", s0, List.of(b(ANN)), null, null);

        f.mutants("init", null, List.of(ben, guard), at(OWNER, 0, 0), OWNER);
        f.mutants("tip", scripted, List.of(n(5_000_000)), f.funds(at(OWNER, 0, 10_000_000), none, none), OWNER);
        f.mutants("tip", s0, List.of(n(5_000_000)), f.funds(at(OWNER, 0, 10_000_000), none, none), OWNER);
        f.mutants("spread", s0, List.of(List.of(share1, share2), Bytes.of(TOKEN_POLICY), TOKEN), f.funds(at(OWNER, 0, 10_000_000), none, tokens(9)), OWNER);
        f.mutants("rotate", s0, List.of(ben, keyCred(ANN)), null, OWNER);
        f.mutants("pair", s0, List.of(Bytes.of(TOKEN_POLICY), n00ff, n01), f.funds(at(ANN, 0, 0), twoNames, none), ANN);
        f.mutants("close", s0, List.of(), f.funds(at(OWNER, 0, 4_000_000 + minAda), none, tokens(3)), OWNER);
        report(f);
    }

    // ------------------------------------------------------------------ Dex: the port of examples/dex

    Rec pool(FourWay f, BigInteger lpSupply) {
        return rec("State", b(OWNER), Bytes.of(TOKEN_POLICY), TOKEN, lpName(f), lpSupply);
    }

    static Bytes lpName(FourWay f) {
        return Bytes.of(com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash224(f.harness.instanceId()));
    }

    @Test
    @Order(3)
    void dex() throws Exception {
        var f = LEGS.get("Dex").reference("org.julclang.state.examples.dex", "open", "Pool");
        byte[] own = f.harness.policy();
        Bytes lp = lpName(f);
        ValueV none = ValueV.NONE;
        long minAda = f.harness.minAda();

        // init: the LP token is named after the pool's id; the token policy is a 28-byte hash (Java checks it too).
        assertEquals(ok(f.check("init", null, List.of(Bytes.of(TOKEN_POLICY), TOKEN), f.funds(at(OWNER, 0, 0), none, none), OWNER)),
                f.encode("init", f.interp.call("init", null, List.of(Bytes.of(TOKEN_POLICY), TOKEN), f.funds(at(OWNER, 0, 0), none, none))));
        assertEquals("refused", f.check("init", null, List.of(Bytes.of(bytes(27, 0x70)), TOKEN), f.funds(at(OWNER, 0, 0), none, none), OWNER, pool(f, n(0))));
        Rec empty = pool(f, n(0)), funded = pool(f, ada(100));

        // addLiquidity: the first provider sets the supply; a later one gets the smaller share; both ADA and tokens.
        ok(f.check("addLiquidity", empty, List.of(n(500)), f.funds(at(LP, 50_000_000, 0), tokens(500), none), LP));
        ok(f.check("addLiquidity", funded, List.of(n(100)), f.funds(at(LP, 10_000_000, 100_000_000), tokens(100), tokens(1000)), LP));
        ok(f.check("addLiquidity", funded, List.of(n(500)), f.funds(at(LP, 10_000_000, 100_000_000), tokens(500), tokens(1000)), LP));
        assertEquals("refused", f.check("addLiquidity", funded, List.of(n(0)), f.funds(at(LP, 10_000_000, 100_000_000), none, tokens(1000)), LP));
        assertEquals("refused", f.check("addLiquidity", empty, List.of(n(500)), f.funds(at(LP, 0, 0), tokens(500), none), LP));
        // The tokens offered must be exactly those accepted (the Java example's method does not check it; its validator does).
        assertEquals("refused", f.check("addLiquidity", empty, List.of(n(500)), f.funds(at(LP, 50_000_000, 0), tokens(499), none), LP));
        assertEquals("domain:accepted", f.lastRefusal);
        // A deposit too small to mint any LP (and a reserve of no tokens: a zero divisor).
        assertEquals("refused", f.check("addLiquidity", pool(f, ada(50)), List.of(n(1)), f.funds(at(LP, 1, 100_000_000), tokens(1), tokens(1000)), LP));
        assertEquals("refused", f.check("addLiquidity", funded, List.of(n(5)), f.funds(at(LP, 1_000_000, 100_000_000), tokens(5), none), LP));

        // removeLiquidity: LP tokens offered back and burned; the provider's share of both reserves paid and sent.
        ok(f.check("removeLiquidity", funded, List.of(ada(40)), f.funds(at(LP, 0, 100_000_000), vv(own, lp, ada(40)), tokens(1000)), LP));
        ok(f.check("removeLiquidity", funded, List.of(ada(100)), f.funds(at(LP, 0, 100_000_000), vv(own, lp, ada(100)), tokens(1000)), LP));
        for (BigInteger bad : List.of(n(0), ada(100).add(BigInteger.ONE))) {
            assertEquals("refused", f.check("removeLiquidity", funded, List.of(bad), f.funds(at(LP, 0, 100_000_000), vv(own, lp, bad), tokens(1000)), LP));
        }
        assertEquals("refused", f.check("removeLiquidity", funded, List.of(ada(40)), f.funds(at(LP, 0, 100_000_000), vv(own, lp, ada(39)), tokens(1000)), LP));

        // Swaps both ways, with their slippage bounds.
        ok(f.check("swapAdaForToken", funded, List.of(n(80)), f.funds(at(TRADER, 10_000_000, 100_000_000), none, tokens(1000)), TRADER));
        assertEquals("refused", f.check("swapAdaForToken", funded, List.of(n(91)), f.funds(at(TRADER, 10_000_000, 100_000_000), none, tokens(1000)), TRADER));
        assertEquals("refused", f.check("swapAdaForToken", funded, List.of(n(80)), f.funds(at(TRADER, 10_000_000, 100_000_000), tokens(1), tokens(1000)), TRADER));
        assertEquals("refused", f.check("swapAdaForToken", funded, List.of(n(0)), f.funds(at(TRADER, 0, 100_000_000), none, tokens(1000)), TRADER));
        ok(f.check("swapTokenForAda", funded, List.of(n(100), ada(8)), f.funds(at(TRADER, 0, 100_000_000), tokens(100), tokens(1000)), TRADER));
        assertEquals("refused", f.check("swapTokenForAda", funded, List.of(n(100), ada(10)), f.funds(at(TRADER, 0, 100_000_000), tokens(100), tokens(1000)), TRADER));
        assertEquals("refused", f.check("swapTokenForAda", funded, List.of(n(100), ada(8)), f.funds(at(TRADER, 1, 100_000_000), tokens(100), tokens(1000)), TRADER));
        // A negative tokenIn (review F2): a negative denominator makes the output positive, and the Java method accepts;
        // the port refuses it, accepting a negative quantity (domain:accept), as Java's validator refuses it (I31).
        assertEquals("refused", f.check("swapTokenForAda", funded, List.of(n(-2000), n(0)), f.funds(at(TRADER, 0, 100_000_000), none, tokens(1000)), TRADER));
        assertEquals("domain:accept", f.lastRefusal);

        // close: an empty pool; the dust goes to the admin with the deposit.
        ok(f.check("close", empty, List.of(), f.funds(at(OWNER, 0, minAda + 2_000_000), none, tokens(3)), OWNER));
        ok(f.check("close", empty, List.of(), f.funds(at(OWNER, 0, minAda), none, none), OWNER));
        assertEquals("refused", f.check("close", funded, List.of(), f.funds(at(OWNER, 0, minAda), none, none), OWNER));
        f.modifierRefused("close", empty, List.of(), f.funds(at(STRANGER, 0, minAda), none, none), STRANGER);
        f.check("lpSupply", funded, List.of(), null, null);

        f.mutants("init", null, List.of(Bytes.of(TOKEN_POLICY), TOKEN), f.funds(at(OWNER, 0, 0), none, none), OWNER);
        f.mutants("addLiquidity", funded, List.of(n(100)), f.funds(at(LP, 10_000_000, 100_000_000), tokens(100), tokens(1000)), LP);
        f.mutants("removeLiquidity", funded, List.of(ada(40)), f.funds(at(LP, 0, 100_000_000), vv(own, lp, ada(40)), tokens(1000)), LP);
        f.mutants("swapAdaForToken", funded, List.of(n(80)), f.funds(at(TRADER, 10_000_000, 100_000_000), none, tokens(1000)), TRADER);
        f.mutants("swapTokenForAda", funded, List.of(n(100), ada(8)), f.funds(at(TRADER, 0, 100_000_000), tokens(100), tokens(1000)), TRADER);
        f.mutants("close", empty, List.of(), f.funds(at(OWNER, 0, minAda + 2_000_000), none, tokens(3)), OWNER);

        // Every vector also ran on examples/dex's JVM method: the outcomes differ exactly where the port checks what the
        // Java method leaves to its validator (what is offered must be what is accepted; nothing negative is accepted):
        // the three offers of decision #3 and the negative tokenIn (review F2), a known difference from Java.
        f.javaDiffs.forEach(d -> System.out.println("[M2 vs Java] " + d));
        assertEquals(List.of(), f.javaDiffs.stream().filter(d -> !d.contains("the Java example accepts, the port refuses")).toList());
        assertEquals(4, f.javaDiffs.size(), f.javaDiffs.toString());
        assertEquals(1, f.javaDiffs.stream().filter(d -> d.contains("swapTokenForAda [-2000, 0]")).count(), f.javaDiffs.toString());
        report(f);
    }

    @Test
    @Order(4)
    void fiveWay_theJavaScriptInterpreterAgrees() throws Exception {
        int total = 0;
        for (FourWay f : LEGS.values()) {
            int n = JsLeg.check(f);
            System.out.println("[M2 five-way] " + f.name + ": " + n + " calls, JavaScript interpreter = Java interpreter = JVM = transition"
                    + " program = validator");
            total += n;
        }
        System.out.println("[M2 five-way] %d calls agree on all five legs".formatted(total));
        assertTrue(total > 0, "the tests above recorded calls");
    }
}
