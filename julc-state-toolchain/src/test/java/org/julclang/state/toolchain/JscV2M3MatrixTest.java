package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.CredV;
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
 * no port covers, each on a committed coverage contract on all five legs, with its script size pinned (its recorded size,
 * 100 bytes of slack, and the runtime's 15,000-byte limit):
 * <ul>
 *   <li>{@code Mixed}: a keyed type declared before a plain one (the plain holding's tag shifted), invariants by type, a
 *   move of the plain type and its admit, and the certify, vote and propose handlers beside both kinds of object;</li>
 *   <li>{@code OwnedIntents}: plain objects beside a batched instance action and the three handlers;</li>
 *   <li>{@code Mix}: an instance, a plain owned type that moves, a keyed type with a retiring exit, a certify handler by a
 *   params quorum, invariants by type: a part of every M3 milestone in one contract.</li>
 * </ul>
 * And the build-time size warning (JSC9015): {@code Mix} with a vote handler too ({@code MixFull}) builds over the runtime's
 * limit, and the toolchain says so.
 */
class JscV2M3MatrixTest {

    static final Map<String, Toolchain.Contract> BUILT = new LinkedHashMap<>();
    static final Map<String, String> SOURCES = new LinkedHashMap<>();
    /** The recorded sizes (M3 final pass); a change of more than SLACK bytes must be justified again (§8's J11 rule). */
    static final Map<String, Integer> RECORDED = Map.of("Mixed", 13_371, "OwnedIntents", 11_004, "Mix", 14_518);
    static final int SLACK = 100;
    static final byte[] MASTER = fill(0xAD), ANN = fill(0xA1), EVE = fill(0xEE), A = fill(0x0A), B = fill(0x0B), C = fill(0x0C);

    static String ok(String o) {
        assertTrue(o.startsWith("ok:"), o);
        return o;
    }

    @BeforeAll
    static void build() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!BUILT.isEmpty()) return;
        for (String c : List.of("Mixed", "OwnedIntents", "Mix")) {
            String src = Files.readString(DSL.resolve(c + ".jsc"));
            SOURCES.put(c, src);
            var r = Toolchain.build(Map.of("org/julclang/state/examples/dsl/" + c + ".jsc", src), Map.of());
            assertTrue(r.ok(), c + ": " + r.diagnostics());
            BUILT.put(c, r.contracts().getFirst());
        }
    }

    @Test
    void sizes_pinned_andUnderTheRuntimesLimit() {
        BUILT.forEach((c, k) -> {
            System.out.printf("[M3 matrix] %-12s %,7d B (recorded %,d)%n", c, k.scriptSizeBytes(), RECORDED.get(c));
            assertTrue(k.scriptSizeBytes() <= RECORDED.get(c) + SLACK, c + ": " + k.scriptSizeBytes() + " B, over its recorded size + " + SLACK);
            assertTrue(k.scriptSizeBytes() <= JscV2.RUNTIME_SCRIPT_LIMIT, c + ": over the runtime's limit");
        });
    }

    @Test
    void mixed_keyedBeforePlain_movesAndPurposes() throws Exception {
        byte[] a = fill(0x11), e = fill(0xee);
        var f = new FourWay("Mixed", SOURCES.get("Mixed"), BUILT.get("Mixed").transitions(), rec("Config", List.of(Bytes.of(a)), n(1)), JscModules.NONE);
        var s = rec("State", Bytes.of(a), n(0));
        var badge = rec("Badge", Bytes.of(a), n(1));
        var slot = rec("Slot", Bytes.of(a), n(0));
        f.defaultShared = s;
        ok(f.check("init", null, List.of(), at(a, 0, 0), a));
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
        var f = new FourWay("OwnedIntents", SOURCES.get("OwnedIntents"), BUILT.get("OwnedIntents").transitions(),
                rec("Config", List.of(Bytes.of(a)), n(1)), JscModules.NONE);
        var s = rec("State", Bytes.of(a), n(0));
        var badge = rec("Badge", Bytes.of(a), n(1));
        f.defaultShared = s;
        ok(f.check("init", null, List.of(), at(a, 0, 0), a));
        ok(f.check("award", null, List.of(), at(a, 0, 0), a));
        ok(f.check("drop", badge, List.of(), at(a, 0, f.harness.minAda()), a));
        f.modifierRefused("drop", badge, List.of(), at(fill(0xee), 0, f.harness.minAda()), fill(0xee));
        ok(f.check("tick", s, List.of(), at(a, 0, 0), a));
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
        var f = new FourWay("Mix", SOURCES.get("Mix"), BUILT.get("Mix").transitions(), params);
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

    @Test
    void aScriptOverTheRuntimesLimit_isWarnedAtBuildTime() throws Exception {
        // MixFull (security MS3): Mix with a vote handler too, over the runtime's 15,000-byte limit.
        String full = SOURCES.get("Mix").replace("contract Mix(", "contract MixFull(").replace("\n  invariant Hero.heroLevel",
                "\n  vote judge(voter: Voter, action: GovernanceActionId, choice: Vote) quorum params.council params.quorum = true\n\n"
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
