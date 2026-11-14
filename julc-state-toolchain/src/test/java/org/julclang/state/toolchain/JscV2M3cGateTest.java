package org.julclang.state.toolchain;

import org.julclang.core.types.JulcAssocMap;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.Credential;
import org.julclang.ledger.GovernanceAction;
import org.julclang.ledger.GovernanceActionId;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.ProposalProcedure;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxCert;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.Value;
import org.julclang.ledger.Vote;
import org.julclang.ledger.Voter;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Send;
import org.julclang.state.dsl.v2.AbiCheck;
import org.julclang.state.runtime.StateContractHandle;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.julclang.state.toolchain.JscV2M1cGateTest.BOUND;
import static org.julclang.state.toolchain.JscV2M1cGateTest.MAX_CPU;
import static org.julclang.state.toolchain.JscV2M1cGateTest.MAX_MEM;
import static org.julclang.state.toolchain.JscV2M1cGateTest.SIZE_LIMIT;
import static org.julclang.state.toolchain.JscV2M2cGateTest.Run;
import static org.julclang.state.toolchain.JscV2M2cGateTest.description;
import static org.julclang.state.toolchain.JscV2M2cGateTest.read;
import static org.julclang.state.toolchain.JscV2M2cGateTest.run;
import static org.julclang.state.toolchain.JscV2M2cGateTest.size;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3c's gates (J11, J19) for the staking and upgrade ports, against examples/staking and examples/upgrade:
 * size (within +15% of the Java example and below 14 KB; the coverage contracts below the runtime's 15,000-byte
 * reference-script limit), encoding (the manifests against the descriptions, the stated renamings aside: the purposes and
 * the migration included), modifiers per call (A9: every policy, the purposes' too), and every call's budget as the
 * runtime builds it, saturated: a council of seven keys all signing (quorum seven), the vote handler over eight votes of
 * the script DRep, a certificate that reaches the last test of the policy, a treasury withdrawal of twenty entries, a
 * migration evaluated whole (the old version's spend and burn, the new one's mint).
 */
class JscV2M3cGateTest {

    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final int RUNTIME_LIMIT = 15_000;
    static final byte[] ADMIN = StateHarness.fill(28, 0xAD), POOL = StateHarness.fill(28, 0x90), PAYEE = StateHarness.fill(28, 0xFA);
    static final Map<String, int[]> SIZES = new LinkedHashMap<>();
    static final List<String> ROWS = new ArrayList<>();
    static final String DSL = "org/julclang/state/examples/dsl/";

    /** The saturated council: seven keys, ascending. */
    static final List<byte[]> COUNCIL = new ArrayList<>();

    static {
        for (int i = 1; i <= 7; i++) COUNCIL.add(StateHarness.fill(28, 0x10 * i + 1));
    }

    static String jsc(String name) throws Exception {
        return read(JscV2M1aFourWayTest.DSL.resolve(name + ".jsc").toString());
    }

    @BeforeAll
    static void sizes() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!SIZES.isEmpty()) return;
        String staking = "org/julclang/state/examples/staking/StakedVault.java";
        var javaStaking = Toolchain.build(Map.of(staking, read("../examples/staking/src/main/java/" + staking)), Map.of());
        String up = "org/julclang/state/examples/upgrade/";
        var javaUpgrade = Toolchain.build(Map.of(up + "CounterV1.java", read("../examples/upgrade/src/main/java/" + up + "CounterV1.java"),
                up + "CounterV2.java", read("../examples/upgrade/src/main/java/" + up + "CounterV2.java")), Map.of());
        var ports = new LinkedHashMap<String, String>();
        for (String c : List.of("StakedVault", "CounterV1", "CounterV2", "Steward", "Guardrail", "TallyV1", "TallyV2")) ports.put(DSL + c + ".jsc", jsc(c));
        var port = Toolchain.build(ports, Map.of());
        for (var r : List.of(javaStaking, javaUpgrade, port)) assertTrue(r.ok(), r.diagnostics().toString());
        SIZES.put("StakedVault", new int[]{size(javaStaking, "StakedVault"), size(port, "StakedVault")});
        SIZES.put("CounterV1", new int[]{size(javaUpgrade, "CounterV1"), size(port, "CounterV1")});
        SIZES.put("CounterV2", new int[]{size(javaUpgrade, "CounterV2"), size(port, "CounterV2")});
        for (String c : List.of("Steward", "Guardrail", "TallyV1", "TallyV2")) SIZES.put(c, new int[]{0, size(port, c)});
    }

    @Test
    void size_within15PercentOfJava_andBelow14KB() {
        SIZES.forEach((name, s) -> {
            if (s[0] == 0) {
                System.out.printf("[M3c size] %-12s %,7d (coverage)%n", name, s[1]);
                assertTrue(s[1] <= RUNTIME_LIMIT, name + ": over the runtime's reference-script limit");
                return;
            }
            double ratio = (double) s[1] / s[0];
            System.out.printf("[M3c size] %-12s %,7d -> %,7d (%+.1f%%)%n", name, s[0], s[1], 100 * (ratio - 1));
            assertTrue(ratio <= BOUND, name + ": " + s[1] + " B is more than 15% above Java's " + s[0] + " B");
            assertTrue(s[1] < SIZE_LIMIT, name + ": " + s[1] + " B is not below 14 KB");
        });
    }

    @Test
    void encoding_equalsTheJavaExamples() throws Exception {
        var all = new LinkedHashMap<String, List<String>>();
        all.put("StakedVault", AbiCheck.compare(read(JscV2M1aFourWayTest.DSL.resolve("StakedVault.abi.json").toString()),
                description("org.julclang.state.examples.staking.StakedVault"), "org.julclang.state.examples.staking", "StakedVault"));
        for (String c : List.of("CounterV1", "CounterV2")) {
            all.put(c, AbiCheck.compare(read(JscV2M1aFourWayTest.DSL.resolve(c + ".abi.json").toString()),
                    description("org.julclang.state.examples.upgrade." + c), "org.julclang.state.examples.upgrade", c));
        }
        all.forEach((c, d) -> System.out.println("[M3c encoding] " + c + ": as the Java example's but " + d));
        // The stated differences: a parameter never takes a state field's name (D3: newSuccessor, newStep). The creations'
        // names (init, init migrate's migrate; the examples write open and adopt) are no part of the encoding.
        assertEquals(Map.of("StakedVault", List.of(),
                "CounterV1", List.of("the spend redeemer: SetSuccessor.newSuccessor is named successor in the description"),
                "CounterV2", List.of("the spend redeemer: SetStep.newStep is named step in the description")), all,
                "exactly the stated differences (the purposes, their redeemers and the migration included)");
    }

    /** A port's call by the Java example's name (the ports name their creations init and migrate). */
    static String javaName(String port) {
        return switch (port) {
            case "init" -> "open";
            case "migrate" -> "adopt";
            default -> port;
        };
    }

    @Test
    void modifiers_perMethod_equalTheJavaExamples() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        int compared = 0;
        for (String[] c : List.of(new String[]{"staking", "StakedVault"}, new String[]{"upgrade", "CounterV1"}, new String[]{"upgrade", "CounterV2"})) {
            var java = json.readTree(description("org.julclang.state.examples." + c[0] + "." + c[1])).get("x-joust");
            var port = json.readTree(description("org.julclang.state.examples.dsl." + c[1])).get("x-joust");
            assertEquals(java.get("stakeSelf").asText(), port.get("stakeSelf").asText(), c[1] + ".stakeSelf");
            for (String section : List.of("creations", "actions", "purposes")) {
                var want = new LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
                if (java.get(section) != null) java.get(section).forEach(x -> want.put(x.get("name").asText(), x));
                assertEquals(want.size(), port.get(section) == null ? 0 : port.get(section).size(), c[1] + "." + section);
                if (port.get(section) == null) continue;
                for (var x : port.get(section)) {
                    String name = javaName(x.get("name").asText());
                    var w = want.get(name);
                    assertNotNull(w, "the Java example has no " + name);
                    for (String f : List.of("kind", "payable", "takesAssets", "needsService", "policies", "returns", "redeemer", "ctx")) {
                        assertEquals(String.valueOf(w.get(f)), String.valueOf(x.get(f)), c[1] + "." + name + "." + f);
                    }
                    compared++;
                }
            }
        }
        System.out.println("[M3c modifiers] " + compared + " calls, every modifier, policy and redeemer as the Java examples'");
    }

    // ------------------------------------------------------------------ the calls, as the runtime builds them

    /** The Java example's classes, or the port's. */
    static final class Impl {
        final String pkg, contract;
        final StateHarness<Object> h;
        final Object params;

        @SuppressWarnings("unchecked")
        Impl(boolean java, String example, String contract, Object params) throws Exception {
            this.pkg = java ? "org.julclang.state.examples." + example : "org.julclang.state.examples.dsl";
            this.contract = contract;
            this.params = params;
            var spec = (org.julclang.state.runtime.ContractSpec<Object>) cls(contract + "Client").getField("SPEC").get(null);
            this.h = params == null ? StateHarness.of(spec) : StateHarness.of(spec, params);
        }

        Class<?> cls(String name) throws Exception {
            return Class.forName(pkg + "." + name);
        }

        /** A record by name, its arguments trimmed to its arity. */
        Object make(String name, Object... values) throws Exception {
            var c = cls(name).getDeclaredConstructors()[0];
            return c.newInstance(Arrays.copyOf(values, c.getParameterCount()));
        }

        Object redeemer(String name, Object... values) throws Exception {
            return make(contract + "Validator$" + name, values);
        }

        /** A static method of the contract by name; arguments past its arity (a trailing Ctx) are dropped. */
        Object invoke(String method, Object... args) throws Exception {
            for (var x : cls(contract).getDeclaredMethods()) {
                if (x.getName().equals(method) && Modifier.isStatic(x.getModifiers())) return x.invoke(null, Arrays.copyOf(args, x.getParameterCount()));
            }
            throw new AssertionError("no method " + method + " in " + pkg + "." + contract);
        }
    }

    static JulcList<byte[]> keys(List<byte[]> ks) {
        JulcList<byte[]> l = JulcList.empty();
        for (int i = ks.size() - 1; i >= 0; i--) l = l.prepend(ks.get(i));
        return l;
    }

    static Impl vault(boolean java) throws Exception {
        String pkg = java ? "org.julclang.state.examples.staking" : "org.julclang.state.examples.dsl";
        var c = Class.forName(pkg + ".StakedVault$Config").getDeclaredConstructors()[0];
        Object cfg = c.newInstance(keys(COUNCIL), BigInteger.valueOf(COUNCIL.size()), POOL, PAYEE, ADA.multiply(BigInteger.valueOf(1_000_000)));
        return new Impl(java, "staking", "StakedVault", cfg);
    }

    @SuppressWarnings("unchecked")
    Run staking(boolean java, String call) throws Exception {
        var i = vault(java);
        var h = i.h;
        Object cfg = i.params;
        byte[][] all = COUNCIL.toArray(new byte[0][]);
        BigInteger funds = ADA.multiply(BigInteger.valueOf(50));
        switch (call) {
            case "init" -> {
                Ctx c = h.ctx(ADMIN, funds, BigInteger.ZERO);
                Object s = i.invoke(java ? "open" : "init", cfg, c);
                return run(StateHarness.data(s).toString(), h.evaluate(h.create(i.redeemer("CreateInstance", h.seed(), c), s, c)));
            }
            case "deposit" -> {
                Object s = i.make("StakedVault$State", funds);
                var live = h.live(s, funds);
                Ctx c = h.ctx(ADMIN, ADA.multiply(BigInteger.TEN), funds);
                Object next = i.invoke("deposit", s, c);
                return run(StateHarness.data(next).toString(), h.evaluate(h.act(live, i.redeemer("Deposit", c), next, ADMIN, c)));
            }
            case "close (7 of 7)" -> {
                Object s = i.make("StakedVault$State", funds);
                var live = h.live(s, funds);
                Ctx c = h.ctx(COUNCIL.get(0), BigInteger.ZERO, live.lovelace());
                var pays = (JulcList<Payout>) i.invoke("close", s, cfg, c);
                var tx = h.destroy(live, i.redeemer("Close", c), i.redeemer("BurnInstance"), pays, COUNCIL.get(0), c);
                for (byte[] k : COUNCIL.subList(1, COUNCIL.size())) tx.signers.add(k);
                return run(StateHarness.data(pays).toString(), h.evaluate(tx));
            }
            case "collect (7 of 7)" -> {
                BigInteger amount = ADA.multiply(BigInteger.valueOf(5));
                var sends = new ArrayList<Send>();
                ((JulcList<Send>) i.invoke("collect", cfg, amount)).forEach(sends::add);
                return run(sends.toString(), h.evaluateWithdraw(h.withdrawTx(sends, all), i.redeemer("Rewards", BigInteger.valueOf(74), amount), amount));
            }
            case "certificate (7 of 7, its last test)" -> {
                // UpdateDRep: not a registration, no delegation: the policy's third test (isDRepLifecycle) decides.
                var cert = new TxCert.UpdateDRep(h.ownCredential());
                return run(String.valueOf(i.invoke("certificate", cfg, cert)), h.evaluateCertify(h.withdrawTx(List.of(), all), cert));
            }
            case "certificate (a delegation to the pool)" -> {
                var cert = new TxCert.DelegStaking(h.ownCredential(), new org.julclang.ledger.Delegatee.Stake(new PubKeyHash(POOL)));
                return run(String.valueOf(i.invoke("certificate", cfg, cert)), h.evaluateCertify(h.withdrawTx(List.of(), all), cert));
            }
            case "vote (7 of 7, 8 votes)" -> {
                var voter = new Voter.DRepVoter(h.ownCredential());
                var votes = new LinkedHashMap<GovernanceActionId, Vote>();
                for (int k = 0; k < 8; k++) votes.put(new GovernanceActionId(new TxId(StateHarness.fill(32, 0x61)), BigInteger.valueOf(k)), new Vote.VoteYes());
                return run("true", h.evaluateVote(h.withdrawTx(List.of(), all), voter, votes));
            }
            case "proposal (20 withdrawals)" -> {
                org.julclang.core.types.JulcMap<Credential, BigInteger> m = JulcAssocMap.empty();
                for (int k = 0; k < 20; k++) m = m.insert(new Credential.PubKeyCredential(new PubKeyHash(StateHarness.fill(28, 0x40 + k))), ADA);
                var action = new GovernanceAction.TreasuryWithdrawals(m, Optional.of(new ScriptHash(h.policy())));
                var p = new ProposalProcedure(ADA, new Credential.PubKeyCredential(new PubKeyHash(ADMIN)), action);
                return run(String.valueOf(i.invoke("proposal", cfg, p)), h.evaluatePropose(h.withdrawTx(List.of()), p));
            }
            default -> throw new AssertionError(call);
        }
    }

    // ---- examples/upgrade: CounterV1 and CounterV2

    static Impl v1(boolean java) throws Exception {
        return new Impl(java, "upgrade", "CounterV1", null);
    }

    static Impl v2(boolean java, byte[] previous) throws Exception {
        String pkg = java ? "org.julclang.state.examples.upgrade" : "org.julclang.state.examples.dsl";
        Object cfg = Class.forName(pkg + ".CounterV2$Config").getDeclaredConstructors()[0].newInstance((Object) previous);
        return new Impl(java, "upgrade", "CounterV2", cfg);
    }

    static Value token(byte[] policy, byte[] name, long q) {
        return Value.singleton(new PolicyId(policy), new TokenName(name), BigInteger.valueOf(q));
    }

    /** A call of examples/upgrade or its port, its result with the versions' policies (each build's own) named V1 and V2. */
    Run upgrade(boolean java, String call) throws Exception {
        Run r = upgradeRun(java, call);
        var a = v1(java);
        var b = v2(java, a.h.policy());
        String named = r.result().replace(HexFormat.of().formatHex(a.h.policy()), "V1").replace(HexFormat.of().formatHex(b.h.policy()), "V2");
        return new Run(named, r.cpu(), r.mem(), r.accepted(), r.verdict());
    }

    @SuppressWarnings("unchecked")
    Run upgradeRun(boolean java, String call) throws Exception {
        var a = v1(java);
        var b = v2(java, a.h.policy());
        var h1 = a.h;
        var h2 = b.h;
        BigInteger funds = ADA.multiply(BigInteger.TEN);
        Object named = a.make("CounterV1$State", ADMIN, h2.policy(), BigInteger.valueOf(41));
        switch (call) {
            case "v1 init" -> {
                Ctx c = h1.ctx(ADMIN, funds, BigInteger.ZERO);
                Object s = a.invoke(java ? "open" : "init", c);
                return run(StateHarness.data(s).toString(), h1.evaluate(h1.create(a.redeemer("CreateInstance", h1.seed(), c), s, c)));
            }
            case "v1 increment" -> {
                var live = h1.live(named, funds);
                Object next = a.invoke("increment", named);
                return run(StateHarness.data(next).toString(), h1.evaluate(h1.act(live, a.redeemer("Increment"), next, ADMIN, null)));
            }
            case "v1 setSuccessor" -> {
                Object fresh = a.make("CounterV1$State", ADMIN, new byte[0], BigInteger.valueOf(41));
                var live = h1.live(fresh, funds);
                Object next = a.invoke("setSuccessor", fresh, h2.policy());
                return run(StateHarness.data(next).toString(), h1.evaluate(h1.act(live, a.redeemer("SetSuccessor", h2.policy()), next, ADMIN, null)));
            }
            case "the migration (v1 upgrade + v2 adopt)" -> {
                var live = h1.live(named, funds);
                Ctx c = h1.ctx(ADMIN, BigInteger.ZERO, live.lovelace());
                var none = (JulcList<Payout>) a.invoke("upgrade", named, c);
                var tx = h1.destroy(live, a.redeemer("Upgrade", c), a.redeemer("BurnInstance"), none, ADMIN, c);
                Object adopted = b.invoke(java ? "adopt" : "migrate", b.params, named);
                byte[] id = HexFormat.of().parseHex(StateContractHandle.instanceIdOf(HexFormat.of().formatHex(StateHarness.LIVE_REF.txId().hash()),
                        StateHarness.LIVE_REF.index().intValueExact()));
                tx.outputs.add(new TxOut(h2.stateAddress(), Value.lovelace(live.lovelace()).merge(token(h2.policy(), id, 1)),
                        new OutputDatum.OutputDatumInline(StateHarness.data(adopted)), Optional.empty()));
                tx.mint = tx.mint.merge(token(h2.policy(), id, 1));
                tx.stateOutput = tx.outputs.size() - 1;
                tx.token = id;
                var mine = tx.copy();
                mine.spendRedeemers.clear();
                mine.mintRedeemer = StateHarness.data(b.redeemer("Adopt", StateHarness.LIVE_REF));
                return run(StateHarness.data(adopted).toString(), h1.evaluate(tx), h2.evaluate(mine));
            }
            case "v2 setStep" -> {
                Object s = b.make("CounterV2$State", ADMIN, BigInteger.valueOf(41), BigInteger.ONE);
                var live = h2.live(s, funds);
                Object next = b.invoke("setStep", s, BigInteger.valueOf(5));
                return run(StateHarness.data(next).toString(), h2.evaluate(h2.act(live, b.redeemer("SetStep", BigInteger.valueOf(5)), next, ADMIN, null)));
            }
            case "v2 close" -> {
                Object s = b.make("CounterV2$State", ADMIN, BigInteger.valueOf(41), BigInteger.ONE);
                var live = h2.live(s, funds);
                Ctx c = h2.ctx(ADMIN, BigInteger.ZERO, live.lovelace());
                var pays = (JulcList<Payout>) b.invoke("close", s, c);
                return run(StateHarness.data(pays).toString(), h2.evaluate(h2.destroy(live, b.redeemer("Close", c), b.redeemer("BurnInstance"), pays, ADMIN, c)));
            }
            default -> throw new AssertionError(call);
        }
    }

    void compare(String label, Run j, Run p) {
        assertTrue(j.accepted(), label + " (Java):\n" + j.verdict());
        assertTrue(p.accepted(), label + " (.jsc 2):\n" + p.verdict());
        assertEquals(j.result(), p.result(), label + ": the port's JVM result differs from the Java example's");
        double cpu = (double) p.cpu() / j.cpu(), mem = (double) p.mem() / j.mem();
        ROWS.add(String.format("| %s | %,d | %,d | %+.1f%% | %,d | %,d (%+.1f%%) | %.2f%% / %.2f%% |", label, j.cpu(), p.cpu(),
                100 * (cpu - 1), j.mem(), p.mem(), 100 * (mem - 1), 100.0 * p.cpu() / MAX_CPU, 100.0 * p.mem() / MAX_MEM));
        assertTrue(cpu <= BOUND, label + ": CPU " + p.cpu() + " is more than 15% above Java's " + j.cpu());
        assertTrue(mem <= BOUND, label + ": memory " + p.mem() + " is more than 15% above Java's " + j.mem());
        assertTrue(p.cpu() <= MAX_CPU && p.mem() <= MAX_MEM, label + ": over the per-transaction maximum");
    }

    @Test
    void budgets_saturated() throws Exception {
        for (String c : List.of("init", "deposit", "close (7 of 7)", "collect (7 of 7)", "certificate (7 of 7, its last test)",
                "certificate (a delegation to the pool)", "vote (7 of 7, 8 votes)", "proposal (20 withdrawals)")) {
            compare("StakedVault " + c, staking(true, c), staking(false, c));
        }
        for (String c : List.of("v1 init", "v1 increment", "v1 setSuccessor", "the migration (v1 upgrade + v2 adopt)", "v2 setStep", "v2 close")) {
            compare("Counter " + c, upgrade(true, c), upgrade(false, c));
        }
        System.out.println("[M3c budget] | Call | Java CPU | .jsc 2 CPU | Δ CPU | Java memory | .jsc 2 memory (Δ) | % of max (CPU / mem) |");
        ROWS.forEach(r -> System.out.println("[M3c budget] " + r));
    }
}
