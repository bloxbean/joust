package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.Interpreter;
import org.julclang.state.dsl.v2.Interpreter.BigMapV;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.CredV;
import org.julclang.state.dsl.v2.Interpreter.DepositV;
import org.julclang.state.dsl.v2.Interpreter.ValueV;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.JscModules;
import org.julclang.state.dsl.v2.JscV2;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.julclang.state.toolchain.FourWay.rec;
import static org.julclang.state.toolchain.JscV2M1aFourWayTest.*;
import static org.julclang.state.toolchain.PurposeLegs.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3 final pass (Codex M1, security MS3, design MD2): the combinations of M3's parts that the checker accepts and
 * no port covers (the matrix in the semantics, §2.12), each on a committed coverage contract on all five legs, with its
 * script size pinned (its recorded size, 100 bytes of slack, and the runtime's 15,000-byte limit):
 * <ul>
 *   <li>{@code Mixed}: a keyed type declared before a plain one (the plain holding's tag shifted), invariants by type, a
 *   move of the plain type and its admit, a read of another contract, and the certify, vote and propose handlers beside
 *   both kinds of object;</li>
 *   <li>{@code OwnedIntents}: plain objects that move beside a batched instance action and the three handlers;</li>
 *   <li>{@code Mix}: an instance, a plain owned type that moves, a keyed type with a retiring exit, a certify handler by a
 *   params quorum, invariants by type: a part of every M3 milestone in one contract;</li>
 *   <li>{@code Desk}: plain objects that move, with invariants, beside deposits and a read;</li>
 *   <li>{@code StakedDesk}: stakeSelf and all four handlers beside deposits, a read and native assets;</li>
 *   <li>{@code MapVotes}, {@code MapInbox}: BigMap state beside the certify, vote and propose handlers, and beside deposits
 *   and a read;</li>
 *   <li>{@code RelayV1}, {@code RelayV2}: migration on both sides beside a batched action, a read and the vote and propose
 *   handlers (RelayV2 adopts and migrates on);</li>
 *   <li>{@code StakedQueue}: a batched action beside stakeSelf and its handlers.</li>
 * </ul>
 * And the build-time size warning (JSC9015): {@code Mix} with a vote handler too ({@code MixFull}) builds over the runtime's
 * limit, and the toolchain says so.
 */
class JscV2M3MatrixTest {

    static final Map<String, Toolchain.Contract> BUILT = new LinkedHashMap<>();
    static final Map<String, String> SOURCES = new LinkedHashMap<>();
    /** The recorded sizes (M3 final pass); a change of more than SLACK bytes must be justified again (§8's J11 rule). */
    static final Map<String, Integer> RECORDED = Map.of("Mixed", 14_067, "OwnedIntents", 12_529, "Mix", 14_518, "Desk", 12_898, "StakedDesk", 14_597,
            "MapVotes", 13_017, "MapInbox", 14_798, "RelayV1", 10_876, "RelayV2", 10_796, "StakedQueue", 14_117);
    static final int SLACK = 100;
    static final byte[] MASTER = fill(0xAD), ANN = fill(0xA1), EVE = fill(0xEE), A = fill(0x0A), B = fill(0x0B), C = fill(0x0C);

    static String ok(String o) {
        assertTrue(o.startsWith("ok:"), o);
        return o;
    }

    static final List<String> CONTRACTS = List.of("Mixed", "OwnedIntents", "Mix", "Desk", "StakedDesk", "MapVotes", "MapInbox", "RelayV1", "RelayV2", "StakedQueue");
    static JscModules MODULES;

    @BeforeAll
    static void build() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!BUILT.isEmpty()) return;
        var files = new LinkedHashMap<String, String>();
        var byFile = new LinkedHashMap<String, String>();
        for (String c : CONTRACTS) SOURCES.put(c, Files.readString(DSL.resolve(c + ".jsc")));
        SOURCES.forEach((c, src) -> byFile.put(c + ".jsc", src));
        byFile.put("Oracle.jsc", Files.readString(DSL.resolve("Oracle.jsc")));
        byFile.forEach((f, src) -> files.put("org/julclang/state/examples/dsl/" + f, src));
        MODULES = JscModules.of(byFile);
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        for (var k : r.contracts()) if (SOURCES.containsKey(k.name())) BUILT.put(k.name(), k);
        assertEquals(CONTRACTS.size(), BUILT.size(), BUILT.keySet().toString());
    }

    static FourWay legs(String c, Rec params) throws Exception {
        return new FourWay(c, SOURCES.get(c), BUILT.get(c).transitions(), params, MODULES);
    }

    @Test
    void sizes_pinned_andUnderTheRuntimesLimit() {
        var over = new java.util.ArrayList<String>();
        BUILT.forEach((c, k) -> {
            System.out.printf("[M3 matrix] %-12s %,7d B (recorded %,d)%n", c, k.scriptSizeBytes(), RECORDED.get(c));
            if (k.scriptSizeBytes() > RECORDED.get(c) + SLACK) over.add(c + ": " + k.scriptSizeBytes() + " B, over its recorded size + " + SLACK);
            if (k.scriptSizeBytes() > JscV2.RUNTIME_SCRIPT_LIMIT) over.add(c + ": over the runtime's limit");
        });
        assertEquals(List.of(), over);
    }

    @Test
    void mixed_keyedBeforePlain_movesAndPurposes() throws Exception {
        byte[] a = fill(0x11), e = fill(0xee);
        var f = legs("Mixed", rec("Config", List.of(Bytes.of(a)), n(1), FEED));
        var s = rec("State", Bytes.of(a), n(0));
        var badge = rec("Badge", Bytes.of(a), n(1));
        var slot = rec("Slot", Bytes.of(a), n(0));
        f.defaultShared = s;
        ok(f.check("init", null, List.of(), at(a, 0, 0), a));
        // Design MD2: a read of another contract beside keyed and plain objects.
        ok(f.check("quote", s, List.of(), at(e, 0, 0).withReads(Map.of("feed", oracle(5))), null));
        f.mutants("quote", s, List.of(), at(e, 0, 0).withReads(Map.of("feed", oracle(5))), null);
        ok(f.check("award", null, List.of(), at(a, 0, 0), a));
        ok(f.check("drop", badge, List.of(), at(a, 0, f.harness.minAda()), a));
        f.modifierRefused("drop", badge, List.of(), at(e, 0, f.harness.minAda()), e);
        f.anchor = FourWay.Node.head("Slot", f.harness.instanceId(), Bytes.of(new byte[0]), T0.subtract(n(60000)));
        ok(f.check("claim", null, List.of(), at(a, 0, 0), a));
        ok(f.check("bump", slot, List.of(), null, a));
        f.modifierRefused("bump", slot, List.of(), null, e);
        ok(f.check("leave", slot, List.of(), at(a, 0, 0), a));
        f.partner = new FourWay.Partner("admit", s, List.of(), null, null);
        ok(f.check("go", badge, List.of(), at(a, 0, 0).withInto(s), a));
        f.partner = new FourWay.Partner("go", badge, List.of(), null, s);
        ok(f.check("admit", s, List.of(Bytes.of(f.harness.objectId())), at(a, 0, 0).withArriving(badge), a));
        f.partner = new FourWay.Partner("admit", s, List.of(), null, null);
        f.modifierRefused("go", badge, List.of(), at(e, 0, 0).withInto(s), e);
        var p = new PurposeLegs(f);
        CredV self = scriptCred(f.harness.policy());
        ok(p.as(List.of(a), List.of()).check("register", List.of(cert("RegStaking", self, Opt.NONE)), null));
        p.as(List.of(e), List.of()).refused("modifier:quorum params.council params.threshold", "register", List.of(cert("RegStaking", self, Opt.NONE)), null);
        ok(p.as(List.of(a), List.of()).check("choose", List.of(voter("DRepVoter", self), List.of(pair(actionId(fill(0x61), 0), vote("VoteYes")))), null));
        ok(p.as(List.of(), List.of()).check("guard", List.of(proposal(n(1), keyCred(a), action("InfoAction"))), null));
        f.mutants("bump", slot, List.of(), null, a);
        f.mutants("go", badge, List.of(), at(a, 0, 0).withInto(s), a);
        JsLeg.check(f);
        System.out.println("[M3 matrix] Mixed: " + f.calls + " calls, " + f.refusals + " refused, " + f.validatorRuns + " validator runs, "
                + f.mutantsRejected + " mutants rejected");
        assertTrue(f.mutantsRejected > 0 && f.refusals > 0);
    }

    @Test
    void ownedIntents_objectsBesideABatchedActionAndPurposes() throws Exception {
        byte[] a = fill(0x11);
        var f = legs("OwnedIntents", rec("Config", List.of(Bytes.of(a)), n(1)));
        var s = rec("State", Bytes.of(a), n(0));
        var badge = rec("Badge", Bytes.of(a), n(1));
        f.defaultShared = s;
        ok(f.check("init", null, List.of(), at(a, 0, 0), a));
        ok(f.check("award", null, List.of(), at(a, 0, 0), a));
        ok(f.check("drop", badge, List.of(), at(a, 0, f.harness.minAda()), a));
        f.modifierRefused("drop", badge, List.of(), at(fill(0xee), 0, f.harness.minAda()), fill(0xee));
        ok(f.check("tick", s, List.of(), at(a, 0, 0), a));
        // Design MD2: a move of a plain object beside the batched action.
        f.partner = new FourWay.Partner("admit", s, List.of(), null, null);
        ok(f.check("go", badge, List.of(), at(a, 0, 0).withInto(s), a));
        f.modifierRefused("go", badge, List.of(), at(fill(0xee), 0, 0).withInto(s), fill(0xee));
        f.mutants("go", badge, List.of(), at(a, 0, 0).withInto(s), a);
        f.partner = new FourWay.Partner("go", badge, List.of(), null, s);
        ok(f.check("admit", s, List.of(Bytes.of(f.harness.objectId())), at(a, 0, 0).withArriving(badge), a));
        f.mutants("admit", s, List.of(Bytes.of(f.harness.objectId())), at(a, 0, 0).withArriving(badge), a);
        f.partner = null;
        var p = new PurposeLegs(f);
        CredV self = scriptCred(f.harness.policy());
        ok(p.as(List.of(a), List.of()).check("register", List.of(cert("RegStaking", self, Opt.NONE)), null));
        ok(p.check("choose", List.of(voter("DRepVoter", self), List.of(pair(actionId(fill(0x61), 0), vote("VoteYes")))), null));
        ok(p.as(List.of(), List.of()).check("guard", List.of(proposal(n(1), keyCred(a), action("InfoAction"))), null));
        f.mutants("tick", s, List.of(), at(a, 0, 0), a);
        JsLeg.check(f);
        System.out.println("[M3 matrix] OwnedIntents: " + f.calls + " calls, " + f.validatorRuns + " validator runs, " + f.mutantsRejected
                + " mutants rejected");
        assertTrue(f.mutantsRejected > 0);
    }

    static BigInteger ada(long v) {
        return BigInteger.valueOf(1_000_000L * v);
    }

    static Tx w(byte[] c, long r, long b) {
        return FourWay.tx(c, r, b, T0, T0.add(BigInteger.valueOf(20_000)));
    }

    @Test
    void mix_aPartOfEveryMilestone() throws Exception {
        var params = rec("Config", List.of(Bytes.of(A), Bytes.of(B), Bytes.of(C)), n(2), Bytes.of(fill(0x91)));
        var f = legs("Mix", params);
        byte[] inst = f.harness.instanceId();
        Rec open = rec("State", Bytes.of(MASTER), true, n(0)), closed = rec("State", Bytes.of(MASTER), false, n(0));
        f.defaultShared = open;
        ok(f.check("init", null, List.of(), at(MASTER, 0, 0), MASTER));
        ok(f.check("setOpen", open, List.of(false), null, MASTER));
        f.modifierRefused("setOpen", open, List.of(false), null, EVE);
        Rec hero1 = rec("Hero", Bytes.of(ANN), n(1)), hero2 = rec("Hero", Bytes.of(ANN), n(2));
        ok(f.check("recruit", null, List.of(), at(ANN, 0, 0).withShared(open), ANN));
        assertEquals("refused", f.check("recruit", null, List.of(), at(ANN, 0, 0).withShared(closed), ANN, hero1));
        ok(f.check("levelUp", hero1, List.of(), null, ANN));
        f.modifierRefused("levelUp", hero1, List.of(), null, EVE);
        f.partner = new FourWay.Partner("admit", open, List.of(), null, null);
        ok(f.check("transfer", hero2, List.of(), at(ANN, 0, 0).withInto(open), ANN));
        f.modifierRefused("transfer", hero2, List.of(), at(EVE, 0, 0).withInto(open), EVE);
        f.partner = new FourWay.Partner("transfer", hero2, List.of(), null, open);
        ok(f.check("admit", open, List.of(Bytes.of(f.harness.objectId())), at(ANN, 0, 0).withArriving(hero2), ANN));
        f.partner = new FourWay.Partner("transfer", hero2, List.of(), null, closed);
        assertEquals("refused", f.check("admit", closed, List.of(Bytes.of(f.harness.objectId())), at(ANN, 0, 0).withArriving(hero2), ANN));
        f.partner = null;
        ok(f.check("retire", hero2, List.of(), at(ANN, 0, f.harness.minAda()), ANN));
        f.modifierRefused("retire", hero2, List.of(), at(EVE, 0, f.harness.minAda()), EVE);
        BigInteger cooled = T0.subtract(n(60_000));
        Bytes empty = Bytes.of(new byte[0]), code = Bytes.of(new byte[]{0x63, 0x31});
        f.anchor = FourWay.Node.head("Seat", inst, empty, cooled);
        ok(f.check("take", null, List.of(code), w(ANN, 0, 0).withShared(open), ANN));
        assertEquals("refused", f.check("take", null, List.of(code), w(ANN, 0, 0).withShared(closed), ANN, rec("Seat", Bytes.of(ANN), code, empty)));
        Rec seat = rec("Seat", Bytes.of(ANN), code, empty);
        ok(f.check("write", seat, List.of(Bytes.of(new byte[64])), null, ANN));
        f.invariantRefused("invariant:shortNote", "write", seat, List.of(Bytes.of(new byte[65])), null, ANN);
        f.modifierRefused("write", seat, List.of(Bytes.of(new byte[3])), null, EVE);
        ok(f.check("leave", seat, List.of(), w(ANN, 0, ada(2).longValue()), ANN));
        f.modifierRefused("leave", seat, List.of(), w(EVE, 0, ada(2).longValue()), EVE);
        f.anchor = FourWay.Node.head("Seat", inst, code, cooled);
        ok(f.check("free", seat, List.of(), w(ANN, 0, ada(2).longValue()), ANN));
        f.anchor = null;
        f.mutants("setOpen", open, List.of(false), null, MASTER);
        f.mutants("recruit", null, List.of(), at(ANN, 0, 0).withShared(open), ANN);
        f.mutants("levelUp", hero1, List.of(), null, ANN);
        f.partner = new FourWay.Partner("admit", open, List.of(), null, null);
        f.mutants("transfer", hero2, List.of(), at(ANN, 0, 0).withInto(open), ANN);
        f.partner = new FourWay.Partner("transfer", hero2, List.of(), null, open);
        f.mutants("admit", open, List.of(Bytes.of(f.harness.objectId())), at(ANN, 0, 0).withArriving(hero2), ANN);
        f.partner = null;
        f.mutants("retire", hero2, List.of(), at(ANN, 0, f.harness.minAda()), ANN);
        f.anchor = FourWay.Node.head("Seat", inst, empty, cooled);
        f.mutants("take", null, List.of(code), w(ANN, 0, 0).withShared(open), ANN);
        f.mutants("write", seat, List.of(Bytes.of(new byte[64])), null, ANN);
        f.mutants("leave", seat, List.of(), w(ANN, 0, ada(2).longValue()), ANN);
        f.anchor = FourWay.Node.head("Seat", inst, code, cooled);
        f.mutants("free", seat, List.of(), w(ANN, 0, ada(2).longValue()), ANN);
        var p = new PurposeLegs(f);
        CredV self = scriptCred(f.harness.policy());
        ok(p.as(List.of(A, B), List.of()).check("reg", List.of(cert("RegStaking", self, Opt.NONE)), null));
        p.as(List.of(A), List.of()).refused("modifier:quorum params.council params.quorum", "reg", List.of(cert("RegStaking", self, Opt.NONE)), null);
        p.as(List.of(A, B), List.of()).refused("purpose:certificate", "reg", List.of(cert("UnRegStaking", self, Opt.NONE)), null);
        JsLeg.check(f);
        System.out.println("[M3 matrix] Mix: " + f.calls + " calls, " + f.refusals + " refused, " + f.validatorRuns + " validator runs, "
                + f.mutantsRejected + " mutants rejected, " + f.attacksRejected + " attacks rejected");
        assertTrue(f.mutantsRejected > 0 && f.attacksRejected > 0);
    }

    // ------------------------------------------------------------------ design MD2: the cells no port covered

    static final Interpreter.InstV FEED = JscV2M2cFourWayTest.inst(JscV2M2cFourWayTest.ORACLE_POLICY, JscV2M2cFourWayTest.ORACLE_ID);
    static final byte[] PUB = JscV2M2cFourWayTest.PUB, POOL = JscV2M3cFourWayTest.POOL, PAYEE = JscV2M3cFourWayTest.PAYEE;
    static final Bytes NONE_B = Bytes.of(new byte[0]);

    static Rec oracle(long price) {
        return rec("Oracle.State", Bytes.of(PUB), n(price), T0.subtract(n(1000)));
    }

    static Tx deposits(FourWay f, Tx t, DepositV... ds) {
        return JscV2M2cFourWayTest.deposits(f, t, ds);
    }

    static DepositV deposit(long lovelace, Object memo) {
        return new DepositV(JscV2M2FourWayTest.vv(new byte[0], new byte[0], BigInteger.valueOf(lovelace)), memo);
    }

    static void done(String name, FourWay f) {
        System.out.println("[M3 matrix] " + name + ": " + f.calls + " calls, " + f.refusals + " refused, " + f.validatorRuns + " validator runs, "
                + f.mutantsRejected + " mutants rejected, " + f.attacksRejected + " attacks rejected");
        assertTrue(f.mutantsRejected > 0 && f.refusals > 0 && f.refusals < f.calls, name);
    }

    @Test
    void desk_movingObjectsWithInvariants_besideDepositsAndReads() throws Exception {
        var f = legs("Desk", rec("Config", FEED));
        Rec s = rec("State", Bytes.of(A), n(0), n(0), n(0));
        f.defaultShared = s;
        ok(f.check("init", null, List.of(), at(A, 0, 0), A));
        Tx two = deposits(f, at(B, 0, 0), deposit(2_000_000, Bytes.of(B)), deposit(1_000_000, Bytes.of(C)));
        ok(f.check("credit", s, List.of(), two, B));
        assertEquals("refused", f.check("credit", s, List.of(), deposits(f, at(B, 0, 0)), B));
        assertEquals("refused", f.check("credit", s, List.of(), deposits(f, at(B, 0, 0), deposit(1_000_000, Bytes.of(new byte[27]))), B));
        assertEquals("input:deposits", f.lastRefusal);
        ok(f.check("quote", s, List.of(), at(B, 0, 0).withReads(Map.of("feed", oracle(7))), null));
        Rec one = rec("Folder", Bytes.of(B), n(1)), two_ = rec("Folder", Bytes.of(B), n(2));
        ok(f.check("start", null, List.of(), at(B, 0, 0), B));
        ok(f.check("add", one, List.of(), at(B, 0, 0), B));
        f.modifierRefused("add", one, List.of(), at(EVE, 0, 0), EVE);
        ok(f.check("tear", two_, List.of(), at(B, 0, 0), B));
        f.invariantRefused("invariant:somePages", "tear", one, List.of(), at(B, 0, 0), B);
        // The move: the clerk's own folder (the admit is only clerk, so the clerk signs both halves).
        Rec mine = rec("Folder", Bytes.of(A), n(2));
        f.partner = new FourWay.Partner("shelve", s, List.of(), null, null);
        ok(f.check("pass", mine, List.of(), at(A, 0, 0).withInto(s), A));
        f.modifierRefused("pass", mine, List.of(), at(EVE, 0, 0).withInto(s), EVE);
        f.mutants("pass", mine, List.of(), at(A, 0, 0).withInto(s), A);
        f.partner = new FourWay.Partner("pass", mine, List.of(), null, s);
        ok(f.check("shelve", s, List.of(Bytes.of(f.harness.objectId())), at(A, 0, 0).withArriving(mine), A));
        f.modifierRefused("shelve", s, List.of(Bytes.of(f.harness.objectId())), at(EVE, 0, 0).withArriving(mine), EVE);
        f.mutants("shelve", s, List.of(Bytes.of(f.harness.objectId())), at(A, 0, 0).withArriving(mine), A);
        f.partner = null;
        ok(f.check("shred", one, List.of(), at(B, 0, f.harness.minAda()), B));
        f.modifierRefused("shred", one, List.of(), at(EVE, 0, f.harness.minAda()), EVE);
        f.mutants("credit", s, List.of(), two, B);
        f.mutants("quote", s, List.of(), at(B, 0, 0).withReads(Map.of("feed", oracle(7))), null);
        f.mutants("add", one, List.of(), at(B, 0, 0), B);
        JsLeg.check(f);
        done("Desk", f);
    }

    @Test
    void stakedDesk_thePurposesBesideDepositsReadsAndAssets() throws Exception {
        var f = legs("StakedDesk", rec("Config", FEED, JscV2M3cFourWayTest.keys(A, B, C), n(2), Bytes.of(POOL), Bytes.of(PAYEE)));
        assertTrue(f.interp.stakeSelf() && f.harness.stateAddress().stakingCredential().isPresent());
        Rec s = rec("State", Bytes.of(A), n(0), n(0));
        ok(f.check("init", null, List.of(), at(A, 0, 0), A));
        Tx two = deposits(f, at(B, 0, 0), deposit(2_000_000, Bytes.of(B)), deposit(1_000_000, Bytes.of(C)));
        ok(f.check("credit", s, List.of(), two, B));
        assertEquals("refused", f.check("credit", s, List.of(), deposits(f, at(B, 0, 0)), B));
        ok(f.check("quote", s, List.of(), at(B, 0, 0).withReads(Map.of("feed", oracle(7))), null));
        byte[] tp = JscV2M2FourWayTest.TOKEN_POLICY;
        ValueV three = JscV2M2FourWayTest.vv(tp, new byte[]{1}, n(3));
        ok(f.check("lodge", s, List.of(Bytes.of(tp), Bytes.of(new byte[]{1}), n(3)), f.funds(at(B, 0, 0), three, ValueV.NONE), B));
        assertEquals("refused", f.check("lodge", s, List.of(Bytes.of(tp), Bytes.of(new byte[]{1}), n(4)), f.funds(at(B, 0, 0), three, ValueV.NONE), B));
        f.mutants("credit", s, List.of(), two, B);
        f.mutants("quote", s, List.of(), at(B, 0, 0).withReads(Map.of("feed", oracle(7))), null);
        f.mutants("lodge", s, List.of(Bytes.of(tp), Bytes.of(new byte[]{1}), n(3)), f.funds(at(B, 0, 0), three, ValueV.NONE), B);
        var p = new PurposeLegs(f);
        CredV self = scriptCred(f.harness.policy());
        ok(p.as(List.of(A, B), List.of()).check("collect", List.of(ada(5)), null));
        p.as(List.of(A), List.of()).refused("modifier:quorum params.council params.quorum", "collect", List.of(ada(5)), null);
        p.as(List.of(A, B), List.of()).withdrawMutants("collect", List.of(ada(5)), null);
        ok(p.as(List.of(A, C), List.of()).check("certificate", List.of(cert("RegStaking", self, Opt.NONE)), null));
        p.as(List.of(A, C), List.of()).refused("purpose:certify", "certificate",
                List.of(cert("DelegStaking", self, gov("Delegatee", "Stake", Bytes.of(fill(0x91))))), null);
        ok(p.as(List.of(A, B), List.of()).check("decide", List.of(voter("DRepVoter", self), List.of(pair(actionId(fill(0x61), 0), vote("VoteYes")))), null));
        ok(p.as(List.of(), List.of()).check("guard", List.of(proposal(n(1), keyCred(A), action("InfoAction"))), null));
        JsLeg.check(f);
        done("StakedDesk", f);
    }

    @Test
    void mapVotes_bigMapStateBesideTheHandlers() throws Exception {
        var f = legs("MapVotes", rec("Config", JscV2M3cFourWayTest.keys(A, B, C), n(2)));
        Rec s = rec("State", Bytes.of(A), BigMapV.of(Map.of()));
        ok(f.check("init", null, List.of(), at(A, 0, 0), A));
        ok(f.check("note", s, List.of(n(4)), at(B, 0, 0), B));
        Rec noted = rec("State", Bytes.of(A), BigMapV.of(Map.of(Bytes.of(B), n(4))));
        ok(f.check("note", noted, List.of(n(9)), at(B, 0, 0), B));
        assertEquals("refused", f.check("note", noted, List.of(n(0)), at(B, 0, 0), B));
        f.mutants("note", noted, List.of(n(9)), at(B, 0, 0), B);
        var p = new PurposeLegs(f);
        CredV self = scriptCred(f.harness.policy());
        ok(p.as(List.of(A, B), List.of()).check("register", List.of(cert("RegStaking", self, Opt.NONE)), null));
        p.as(List.of(A), List.of()).refused("modifier:quorum params.council params.quorum", "register", List.of(cert("RegStaking", self, Opt.NONE)), null);
        ok(p.as(List.of(A, B), List.of()).check("decide", List.of(voter("DRepVoter", self), List.of(pair(actionId(fill(0x61), 0), vote("VoteNo")))), null));
        ok(p.as(List.of(), List.of()).check("guard", List.of(proposal(n(1), keyCred(A), action("InfoAction"))), null));
        JsLeg.check(f);
        done("MapVotes", f);
    }

    @Test
    void mapInbox_bigMapStateBesideDepositsAndReads() throws Exception {
        var f = legs("MapInbox", rec("Config", FEED));
        Rec s = rec("State", Bytes.of(A), n(0), BigMapV.of(Map.of()));
        ok(f.check("init", null, List.of(), at(A, 0, 0), A));
        Tx two = deposits(f, at(B, 0, 0), deposit(2_000_000, Bytes.of(B)), deposit(1_000_000, Bytes.of(C)));
        ok(f.check("credit", s, List.of(), two, B));
        Rec credited = rec("State", Bytes.of(A), n(0), BigMapV.of(Map.of(Bytes.of(B), n(2))));
        ok(f.check("credit", credited, List.of(), two, B));
        assertEquals("refused", f.check("credit", credited, List.of(), deposits(f, at(B, 0, 0)), B));
        ok(f.check("quote", credited, List.of(), at(B, 0, 0).withReads(Map.of("feed", oracle(7))), null));
        f.mutants("credit", credited, List.of(), two, B);
        f.mutants("quote", credited, List.of(), at(B, 0, 0).withReads(Map.of("feed", oracle(7))), null);
        JsLeg.check(f);
        done("MapInbox", f);
    }

    @Test
    void relay_migrationBesideBatchedReadsAndTheHandlers() throws Exception {
        var keys = JscV2M3cFourWayTest.keys(A, B, C);
        var v1 = legs("RelayV1", rec("Config", FEED, keys, n(2)));
        byte[] p1 = v1.harness.policy();
        var v2 = legs("RelayV2", rec("Config", Bytes.of(p1), FEED));
        byte[] p2 = v2.harness.policy();
        // A third version adopting RelayV2's instances (the successor RelayV2's own migrating destroy moves into).
        var v3 = legs("RelayV2", rec("Config", Bytes.of(p2), FEED));
        byte[] p3 = v3.harness.policy();
        BigInteger funds = ada(10);
        // ---- version 1: a batched action, a read, the handlers, the successor set once, the migration
        Rec fresh = rec("State", Bytes.of(MASTER), NONE_B, n(0));
        ok(v1.check("init", null, List.of(), at(MASTER, 0, 0), MASTER));
        ok(v1.check("tick", fresh, List.of(), at(B, 0, 0), B));
        ok(v1.check("quote", fresh, List.of(), at(B, 0, 0).withReads(Map.of("feed", oracle(41))), null));
        ok(v1.check("setSuccessor", fresh, List.of(Bytes.of(p2)), null, MASTER));
        v1.modifierRefused("setSuccessor", fresh, List.of(Bytes.of(p2)), null, EVE);
        v1.mutants("tick", fresh, List.of(), at(B, 0, 0), B);
        v1.mutants("quote", fresh, List.of(), at(B, 0, 0).withReads(Map.of("feed", oracle(41))), null);
        Rec named = rec("State", Bytes.of(MASTER), Bytes.of(p2), n(41));
        Rec adopted = rec("State", Bytes.of(MASTER), NONE_B, n(41));
        BigInteger lovelace = BigInteger.valueOf(v1.harness.minAda()).add(funds);
        Tx up = at(MASTER, 0, lovelace.longValue()).withSuccessor(Bytes.of(p2));
        v1.successor = JscV2M3cFourWayTest.successor(p2, v2.harness.stateAddress(), v2.java(adopted), 0);
        ok(v1.check("upgrade", named, List.of(), up, MASTER));
        v1.modifierRefused("upgrade", named, List.of(), at(EVE, 0, lovelace.longValue()).withSuccessor(Bytes.of(p2)), EVE);
        JscV2M3cFourWayTest.oldSideMutants(v1, named, lovelace, p2, v2.harness.stateAddress(), v2.java(adopted));
        var p = new PurposeLegs(v1);
        CredV self1 = scriptCred(p1);
        ok(p.as(List.of(A, B), List.of()).check("decide", List.of(voter("DRepVoter", self1), List.of(pair(actionId(fill(0x61), 0), vote("VoteYes")))), null));
        p.as(List.of(A), List.of()).refused("modifier:quorum params.council params.quorum", "decide",
                List.of(voter("DRepVoter", self1), List.of(pair(actionId(fill(0x61), 0), vote("VoteYes")))), null);
        ok(p.as(List.of(), List.of()).check("guard", List.of(proposal(n(1), keyCred(A), action("InfoAction"))), null));
        // ---- version 2 adopts it, runs its own parts, and migrates on into version 3
        Object oldJ = v1.java(named);
        v2.adoption = JscV2M3cFourWayTest.adoption(v1, "upgrade", oldJ, funds, v2, 0, MASTER);
        Tx adopt = new Tx(Bytes.of(MASTER), BigInteger.ZERO, BigInteger.ZERO, T0, T0.add(WINDOW))
                .withPrior(new Interpreter.PriorV(Bytes.of(p1), JscV2M3cFourWayTest.as("RelayV1.State", named)));
        ok(v2.check("migrate", null, List.of(), adopt, MASTER));
        v2.adoption = JscV2M3cFourWayTest.adoption(v1, "upgrade", oldJ, funds, v2, 1, MASTER);
        v2.transactionLevel("migrate", null, List.of(), adopt, MASTER);
        v2.adoption = JscV2M3cFourWayTest.adoption(v1, "upgrade", oldJ, funds, v2, 0, MASTER);
        JscV2M3cFourWayTest.newSideMutants(v2, v1, oldJ, adopted, funds);
        ok(v2.check("tick", adopted, List.of(), at(B, 0, 0), B));
        ok(v2.check("peek", adopted, List.of(), at(B, 0, 0).withReads(Map.of("feed", oracle(42))), null));
        ok(v2.check("setSuccessor", adopted, List.of(Bytes.of(p3)), null, MASTER));
        v2.mutants("tick", adopted, List.of(), at(B, 0, 0), B);
        Rec named2 = rec("State", Bytes.of(MASTER), Bytes.of(p3), n(41));
        v2.successor = JscV2M3cFourWayTest.successor(p3, v3.harness.stateAddress(), v3.java(adopted), 0);
        Tx up2 = at(MASTER, 0, lovelace.longValue()).withSuccessor(Bytes.of(p3));
        ok(v2.check("upgrade", named2, List.of(), up2, MASTER));
        v2.modifierRefused("upgrade", named2, List.of(), at(EVE, 0, lovelace.longValue()).withSuccessor(Bytes.of(p3)), EVE);
        JsLeg.check(v1);
        JsLeg.check(v2);
        done("RelayV1", v1);
        done("RelayV2", v2);
    }

    @Test
    void stakedQueue_aBatchedActionBesideStakeSelf() throws Exception {
        var f = legs("StakedQueue", rec("Config", JscV2M3cFourWayTest.keys(A, B, C), n(2), Bytes.of(POOL), Bytes.of(PAYEE)));
        assertTrue(f.interp.stakeSelf() && f.harness.stateAddress().stakingCredential().isPresent());
        Rec s = rec("State", Bytes.of(A), n(0));
        ok(f.check("init", null, List.of(), at(A, 0, 0), A));
        ok(f.check("tick", s, List.of(), at(B, 0, 0), B));
        f.mutants("tick", s, List.of(), at(B, 0, 0), B);
        var p = new PurposeLegs(f);
        CredV self = scriptCred(f.harness.policy());
        ok(p.as(List.of(A, B), List.of()).check("collect", List.of(ada(5)), null));
        p.as(List.of(A), List.of()).refused("modifier:quorum params.council params.quorum", "collect", List.of(ada(5)), null);
        p.as(List.of(A, B), List.of()).withdrawMutants("collect", List.of(ada(5)), null);
        ok(p.as(List.of(A, C), List.of()).check("certificate", List.of(cert("RegStaking", self, Opt.NONE)), null));
        p.as(List.of(A, C), List.of()).refused("purpose:certify", "certificate",
                List.of(cert("DelegStaking", self, gov("Delegatee", "Stake", Bytes.of(fill(0x91))))), null);
        JsLeg.check(f);
        System.out.println("[M3 matrix] StakedQueue: " + f.calls + " calls, " + f.validatorRuns + " validator runs, " + f.mutantsRejected
                + " mutants rejected; purpose refusals: " + p.purposeRefusals);
        assertTrue(f.mutantsRejected > 0 && p.purposeRefusals > 0);
    }

    @Test
    void aPurposeAnnotationName_buildsInAContractWithoutPurposes() {
        // Design MD7: Withdraw (the annotation a withdraw handler lowers to) is reserved only where a handler or a migration is.
        String src = "jsc 1\npackage t\nenum Op { Pay, Withdraw, Rewards }\ncontract R {\n  state { last: Op }\n  init() { return { last: Pay } }\n"
                + "  action take() { return { last: Withdraw } }\n}\n";
        var r = Toolchain.build(Map.of("t/R.jsc", src), Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
    }

    @Test
    void aScriptOverTheRuntimesLimit_isWarnedAtBuildTime() throws Exception {
        // MixFull (security MS3): Mix with a vote handler and seven more owned actions, over the runtime's 15,000-byte
        // limit (JuLC 0.1.0-pre21's smaller scripts put Mix with the vote handler alone at 13,634 bytes, five more
        // owned actions at 14,873).
        String full = SOURCES.get("Mix").replace("contract Mix(", "contract MixFull(").replace("\n  invariant Hero.heroLevel",
                "\n  vote judge(voter: Voter, action: GovernanceActionId, choice: Vote) quorum params.council params.quorum = true\n\n"
                        + "  owned action Hero.train(steps: Int) only owner {\n    require(steps > 0 && steps <= 10, \"steps\")\n"
                        + "    return { ...state, level: level + steps }\n  }\n\n"
                        + "  owned action Hero.demote(steps: Int) only owner {\n    require(steps > 0 && steps < level, \"steps\")\n"
                        + "    return { ...state, level: level - steps }\n  }\n\n"
                        + "  owned action Seat.clear() only holder {\n    require(length(note) > 0, \"empty\")\n"
                        + "    return { ...state, note: utf8(\"\") }\n  }\n\n"
                        + "  owned action Hero.promote(steps: Int, cap: Int) only owner {\n    require(steps > 0 && level + steps <= cap, \"cap\")\n"
                        + "    return { ...state, level: level + steps }\n  }\n\n"
                        + "  owned action Seat.stamp(text: Bytes) only holder {\n    require(length(text) > 0 && length(text) <= 32, \"text\")\n"
                        + "    return { ...state, note: text }\n  }\n\n"
                        + "  owned action Hero.rank(target: Int) only owner {\n    require(target > level && target <= 100, \"rank\")\n"
                        + "    return { ...state, level: target }\n  }\n\n"
                        + "  owned action Seat.reserve(text: Bytes, limit: Int) only holder {\n    require(length(text) <= limit && limit <= 64, \"limit\")\n"
                        + "    return { ...state, note: text }\n  }\n\n"
                        + "  invariant Hero.heroLevel");
        var r = Toolchain.build(Map.of("org/julclang/state/examples/dsl/MixFull.jsc", full), Map.of());
        assertTrue(r.ok(), "a warning, not an error: " + r.diagnostics());
        int size = r.contracts().getFirst().scriptSizeBytes();
        assertTrue(size > JscV2.RUNTIME_SCRIPT_LIMIT, size + " B");
        var warning = r.diagnostics().stream().filter(d -> "JSC9015".equals(d.code())).findFirst().orElseThrow(() -> new AssertionError(r.diagnostics().toString()));
        assertEquals(Toolchain.Severity.WARNING, warning.severity());
        assertTrue(warning.file().endsWith("MixFull.jsc") && warning.message().contains(size + " bytes"), warning.toString());
        // The contracts under the limit have no such warning.
        assertTrue(Toolchain.build(Map.of("org/julclang/state/examples/dsl/Mix.jsc", SOURCES.get("Mix")), Map.of()).diagnostics().stream()
                .noneMatch(d -> "JSC9015".equals(d.code())));
        System.out.println("[M3 matrix] MixFull: " + size + " B, warned at build time (JSC9015)");
    }
}
