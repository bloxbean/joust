package org.julclang.state.toolchain;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.DepositClaim;
import org.julclang.state.api.ForeignInstance;
import org.julclang.state.api.Payout;
import org.julclang.state.dsl.v2.AbiCheck;
import org.julclang.state.runtime.Transition;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.julclang.stdlib.Builtins;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.julclang.state.toolchain.JscV2M1cGateTest.BOUND;
import static org.julclang.state.toolchain.JscV2M1cGateTest.MAX_CPU;
import static org.julclang.state.toolchain.JscV2M1cGateTest.MAX_MEM;
import static org.julclang.state.toolchain.JscV2M1cGateTest.SIZE_LIMIT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M2c's gates (J11, J19) for the oracle and fees ports, against examples/oracle and examples/fees, as
 * {@link JscV2M2GateTest} and {@link JscV2M2bGateTest} are for dex and treasury:
 * <ul>
 *   <li><b>Size:</b> each port's script within +15% of its Java example's, and below 14 KB (both built by the toolchain):
 *   Oracle, Shop (the port's Kiosk), Pot, Stall.</li>
 *   <li><b>Encoding:</b> each port's ABI manifest matches its Java example's processor description, structurally. The one
 *   stated difference: Oracle's post takes {@code newPrice} (a .jsc 2 parameter never takes a state field's name, D3),
 *   the Java example's {@code price}.</li>
 *   <li><b>Behaviour and budget:</b> each transaction built as the runtime builds it (a buy with the oracle as a reference
 *   input; a sale with the pot's collect, in one transaction, each contract's harness evaluating its own spends and the
 *   output verifier counted once), the same JVM result, within +15% of the Java example's CPU and memory.</li>
 *   <li><b>The budget gate</b>, at the one size-bound structure: a pot collecting {@value #DEPOSITS} deposits in one
 *   transaction (each with native assets of two token names of unequal length, as the registry carries them: #85),
 *   within the per-transaction maximum, warning above 80%. The states are fixed-size.</li>
 * </ul>
 */
class JscV2M2cGateTest {

    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] OWNER = StateHarness.fill(28, 0x0A), BUYER = StateHarness.fill(28, 0xB0), PUBLISHER = StateHarness.fill(28, 0x9B);
    static final byte[] ORACLE_POLICY = StateHarness.fill(28, 0x0C), ORACLE_ID = StateHarness.fill(32, 0x0D);
    static final int DEPOSITS = 8;
    static final Map<String, int[]> SIZES = new LinkedHashMap<>();
    static final List<String> ROWS = new ArrayList<>(), WARNINGS = new ArrayList<>();

    static final String JAVA_ORACLE = "org/julclang/state/examples/oracle/", JAVA_FEES = "org/julclang/state/examples/fees/";
    static final String DSL = "org/julclang/state/examples/dsl/";

    static int size(Toolchain.Result r, String contract) {
        for (var c : r.contracts()) if (c.name().endsWith("." + contract) || c.name().equals(contract)) return c.scriptSizeBytes();
        throw new AssertionError("no contract " + contract + " in " + r.contracts());
    }

    @BeforeAll
    static void sizes() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!SIZES.isEmpty()) return;
        var javaOracle = Toolchain.build(Map.of(JAVA_ORACLE + "Oracle.java", read("../examples/oracle/src/main/java/" + JAVA_ORACLE + "Oracle.java"),
                JAVA_ORACLE + "Shop.java", read("../examples/oracle/src/main/java/" + JAVA_ORACLE + "Shop.java")), Map.of());
        var portOracle = Toolchain.build(Map.of(DSL + "Oracle.jsc", read(JscV2M1aFourWayTest.DSL.resolve("Oracle.jsc").toString()),
                DSL + "Kiosk.jsc", read(JscV2M1aFourWayTest.DSL.resolve("Kiosk.jsc").toString())), Map.of());
        var javaFees = Toolchain.build(Map.of(JAVA_FEES + "Pot.java", read("../examples/fees/src/main/java/" + JAVA_FEES + "Pot.java"),
                JAVA_FEES + "Stall.java", read("../examples/fees/src/main/java/" + JAVA_FEES + "Stall.java")), Map.of());
        var portFees = Toolchain.build(Map.of(DSL + "Pot.jsc", read(JscV2M1aFourWayTest.DSL.resolve("Pot.jsc").toString()),
                DSL + "Stall.jsc", read(JscV2M1aFourWayTest.DSL.resolve("Stall.jsc").toString())), Map.of());
        for (var r : List.of(javaOracle, portOracle, javaFees, portFees)) assertTrue(r.ok(), r.diagnostics().toString());
        SIZES.put("Oracle", new int[]{size(javaOracle, "Oracle"), size(portOracle, "Oracle")});
        SIZES.put("Shop (Kiosk)", new int[]{size(javaOracle, "Shop"), size(portOracle, "Kiosk")});
        SIZES.put("Pot", new int[]{size(javaFees, "Pot"), size(portFees, "Pot")});
        SIZES.put("Stall", new int[]{size(javaFees, "Stall"), size(portFees, "Stall")});
    }

    static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void size_within15PercentOfJava_andBelow14KB() {
        SIZES.forEach((name, s) -> {
            double ratio = (double) s[1] / s[0];
            System.out.printf("[M2c size] %-12s %,7d -> %,7d (%+.1f%%)%n", name, s[0], s[1], 100 * (ratio - 1));
            assertTrue(ratio <= BOUND, name + ": " + s[1] + " B is more than 15% above Java's " + s[0] + " B");
            assertTrue(s[1] < SIZE_LIMIT, name + ": " + s[1] + " B is not below 14 KB");
        });
    }

    static String description(String qualified) throws Exception {
        var in = JscV2M2cGateTest.class.getClassLoader().getResourceAsStream("META-INF/joust/" + qualified + ".json");
        assertNotNull(in, "the Java example's description " + qualified);
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void encoding_equalsTheJavaExamples() throws Exception {
        String oracle = Files.readString(JscV2M1aFourWayTest.DSL.resolve("Oracle.abi.json"));
        var unrenamed = AbiCheck.compare(oracle, description("org.julclang.state.examples.oracle.Oracle"), "org.julclang.state.examples.oracle", "Oracle");
        assertEquals(1, unrenamed.size(), "exactly the stated renaming: " + unrenamed);
        assertEquals(List.of(), AbiCheck.compare(oracle.replace("\"name\": \"newPrice\"", "\"name\": \"price\""),
                description("org.julclang.state.examples.oracle.Oracle"), "org.julclang.state.examples.oracle", "Oracle"));
        assertEquals(List.of(), AbiCheck.compare(Files.readString(JscV2M1aFourWayTest.DSL.resolve("Kiosk.abi.json")),
                description("org.julclang.state.examples.oracle.Shop"), "org.julclang.state.examples.oracle", "Shop"));
        assertEquals(List.of(), AbiCheck.compare(Files.readString(JscV2M1aFourWayTest.DSL.resolve("Pot.abi.json")),
                description("org.julclang.state.examples.fees.Pot"), "org.julclang.state.examples.fees", "Pot"));
        assertEquals(List.of(), AbiCheck.compare(Files.readString(JscV2M1aFourWayTest.DSL.resolve("Stall.abi.json")),
                description("org.julclang.state.examples.fees.Stall"), "org.julclang.state.examples.fees", "Stall"));
        System.out.println("[M2c encoding] Oracle, Shop (Kiosk), Pot and Stall: datum, params, every redeemer, CreateInstance and"
                + " BurnInstance encode exactly as the Java examples'; one field name differs (stated): " + unrenamed);
    }

    // ------------------------------------------------------------------ the implementations, by reflection

    /** One implementation (the Java examples' package, or the ports'): its classes by the Java example's names. */
    static final class Impl {
        final boolean java;
        final String pkg;

        Impl(boolean java, String examplePkg) {
            this.java = java;
            this.pkg = java ? examplePkg : "org.julclang.state.examples.dsl";
        }

        String name(String javaName) {
            return !java && javaName.startsWith("Shop") ? "Kiosk" + javaName.substring(4) : javaName;
        }

        Class<?> cls(String javaName) throws Exception {
            return Class.forName(pkg + "." + name(javaName));
        }

        Object make(String javaName, Object... values) throws Exception {
            return cls(javaName).getDeclaredConstructors()[0].newInstance(values);
        }

        @SuppressWarnings("unchecked")
        <S> StateHarness<S> harness(String contract, Object params) throws Exception {
            var spec = (org.julclang.state.runtime.ContractSpec<S>) cls(contract + "Client").getField("SPEC").get(null);
            return params == null ? StateHarness.of(spec) : StateHarness.of(spec, params);
        }

        Object invoke(String contract, String method, Object... args) throws Exception {
            String m = !java && method.equals("open") ? "init" : method;
            for (Method x : cls(contract).getDeclaredMethods()) {
                if (x.getName().equals(m) && Modifier.isStatic(x.getModifiers()) && x.getParameterCount() == args.length) {
                    try {
                        return x.invoke(null, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause() instanceof Exception ex ? ex : new IllegalStateException(e.getCause());
                    }
                }
            }
            throw new AssertionError("no method " + contract + "." + m + "/" + args.length);
        }
    }

    record Run(String result, long cpu, long mem, boolean accepted, String verdict) {}

    static Run run(String result, StateHarness.Verdict... verdicts) {
        // One transaction evaluated by each contract's harness: every script once (the output verifier is in both).
        var all = new LinkedHashMap<String, org.julclang.vm.EvalResult>();
        for (var v : verdicts) all.putAll(v.results());
        long cpu = 0, mem = 0;
        for (var e : all.values()) {
            cpu += e.budgetConsumed().cpuSteps();
            mem += e.budgetConsumed().memoryUnits();
        }
        boolean ok = java.util.Arrays.stream(verdicts).allMatch(StateHarness.Verdict::accepted);
        var text = new StringBuilder();
        for (var v : verdicts) text.append(v);
        return new Run(result, cpu, mem, ok, text.toString());
    }

    // ------------------------------------------------------------------ oracle

    Object oracleState(Impl i, BigInteger price, BigInteger at) throws Exception {
        return i.make("Oracle$State", PUBLISHER, price, at);
    }

    Run oracle(Impl i, String call) throws Exception {
        StateHarness<Object> h = i.harness("Oracle", null);
        BigInteger price = BigInteger.valueOf(3_000_000);
        Object s = oracleState(i, price, StateHarness.T0.subtract(BigInteger.valueOf(60_000)));
        switch (call) {
            case "init" -> {
                Ctx ctx = h.ctx(PUBLISHER, BigInteger.ZERO, BigInteger.ZERO);
                Object st = i.invoke("Oracle", "open", price, ctx);
                return run(StateHarness.data(st).toString(), h.evaluate(h.create(i.make("OracleValidator$CreateInstance", h.seed(), price, ctx), st, ctx)));
            }
            case "post" -> {
                Ctx ctx = h.ctx(PUBLISHER, BigInteger.ZERO, ADA.multiply(BigInteger.TWO));
                Object st = i.invoke("Oracle", "post", s, BigInteger.valueOf(4_000_000), ctx);
                return run(StateHarness.data(st).toString(), h.evaluate(h.act(h.live(s, ADA.multiply(BigInteger.TWO)),
                        i.make("OracleValidator$Post", BigInteger.valueOf(4_000_000), ctx), st, PUBLISHER, ctx)));
            }
            default -> {
                var dl = h.live(s, ADA.multiply(BigInteger.TWO));
                Ctx ctx = h.ctx(PUBLISHER, BigInteger.ZERO, dl.lovelace());
                @SuppressWarnings("unchecked")
                var pays = (JulcList<Payout>) i.invoke("Oracle", "close", s, ctx);
                return run(StateHarness.data(pays).toString(), h.evaluate(h.destroy(dl, i.make("OracleValidator$Close", ctx),
                        i.make("OracleValidator$BurnInstance"), pays, PUBLISHER, ctx)));
            }
        }
    }

    Run shop(Impl i, String call) throws Exception {
        Object cfg = i.make("Shop$Config", new ForeignInstance(ORACLE_POLICY, ORACLE_ID), BigInteger.valueOf(600_000));
        StateHarness<Object> h = i.harness("Shop", cfg);
        Object s = i.make("Shop$State", OWNER, BigInteger.ZERO);
        BigInteger price = BigInteger.valueOf(3_000_000);
        switch (call) {
            case "init" -> {
                Ctx ctx = h.ctx(OWNER, BigInteger.ZERO, BigInteger.ZERO);
                Object st = i.invoke("Shop", "open", cfg, ctx);
                return run(StateHarness.data(st).toString(), h.evaluate(h.create(i.make("ShopValidator$CreateInstance", h.seed(), ctx), st, ctx)));
            }
            case "buy" -> {
                Object px = oracleState(i, price, StateHarness.T0.subtract(BigInteger.valueOf(60_000)));
                Ctx ctx = h.ctx(BUYER, price.multiply(BigInteger.TWO), BigInteger.ZERO);
                Object st = i.invoke("Shop", "buy", s, cfg, px, BigInteger.TWO, ctx);
                var tx = h.act(h.live(s, BigInteger.ZERO), i.make("ShopValidator$Buy", BigInteger.TWO, ctx), st, BUYER, ctx);
                Value v = Value.lovelace(BigInteger.valueOf(2_000_000)).merge(Value.singleton(new PolicyId(ORACLE_POLICY), new TokenName(ORACLE_ID), BigInteger.ONE));
                tx.referenceInputs.add(new TxInInfo(new TxOutRef(new TxId(StateHarness.fill(32, 0x0E)), BigInteger.ZERO),
                        new TxOut(new Address(new Credential.ScriptCredential(new ScriptHash(ORACLE_POLICY)), Optional.empty()), v,
                                new OutputDatum.OutputDatumInline(StateHarness.data(px)), Optional.empty())));
                return run(StateHarness.data(st).toString(), h.evaluate(tx));
            }
            default -> {
                var dl = h.live(s, ADA.multiply(BigInteger.TEN));
                Ctx ctx = h.ctx(OWNER, BigInteger.ZERO, dl.lovelace());
                @SuppressWarnings("unchecked")
                var pays = (JulcList<Payout>) i.invoke("Shop", "close", s, ctx);
                return run(StateHarness.data(pays).toString(), h.evaluate(h.destroy(dl, i.make("ShopValidator$Close", ctx),
                        i.make("ShopValidator$BurnInstance"), pays, OWNER, ctx)));
            }
        }
    }

    // ------------------------------------------------------------------ fees

    static final TxOutRef POT_REF = new TxOutRef(new TxId(StateHarness.fill(32, 0x0D)), BigInteger.ONE);

    /** {@code a} then {@code b}'s inputs, outputs, redeemers and signers (as FeesTest merges them). */
    static TxModel merge(TxModel a, TxModel b) {
        var m = a.copy();
        for (TxInInfo in : b.inputs) if (m.inputs.stream().noneMatch(x -> x.outRef().equals(in.outRef()))) m.inputs.add(in);
        m.outputs.addAll(b.outputs);
        m.spendRedeemers.putAll(b.spendRedeemers);
        for (byte[] sg : b.signers) if (m.signers.stream().noneMatch(x -> java.util.Arrays.equals(x, sg))) m.signers.add(sg);
        return m;
    }

    Run fees(Impl i, String call) throws Exception {
        StateHarness<Object> pot = i.harness("Pot", null);
        Object cfg = i.make("Stall$Config", new ForeignInstance(pot.policy(), pot.instanceId()), BigInteger.valueOf(500));
        StateHarness<Object> stall = i.harness("Stall", cfg);
        Object potState = i.make("Pot$State", OWNER, BigInteger.ZERO, BigInteger.ZERO);
        Object stallState = i.make("Stall$State", OWNER, BigInteger.ZERO);
        BigInteger price = BigInteger.valueOf(10_000_000);
        var potHeld = new StateHarness.Held(POT_REF, potState, Assets.add(Assets.lovelace(BigInteger.valueOf(pot.minAda())),
                Assets.of(pot.policy(), pot.instanceId(), BigInteger.ONE)), pot.instanceId());
        switch (call) {
            case "pot init", "stall init" -> {
                boolean p = call.startsWith("pot");
                var h = p ? pot : stall;
                Ctx ctx = h.ctx(OWNER, BigInteger.ZERO, BigInteger.ZERO);
                Object st = p ? i.invoke("Pot", "init", ctx) : i.invoke("Stall", i.java ? "init" : "init", cfg, ctx);
                Object red = p ? i.make("PotValidator$CreateInstance", h.seed(), ctx) : i.make("StallValidator$CreateInstance", h.seed(), ctx);
                return run(StateHarness.data(st).toString(), h.evaluate(h.create(red, st, ctx)));
            }
            case "handOver" -> {
                Object st = i.invoke("Pot", "handOver", potState, BUYER);
                return run(StateHarness.data(st).toString(), pot.evaluate(pot.stepWith(potHeld, i.make("PotValidator$HandOver", BUYER), null,
                        Transition.full(st, null, null, null, null), st, OWNER, null, null)));
            }
            case "sale" -> {
                // The stall's sale and the pot's collect, in one transaction (FeesTest.sale): each harness evaluates its own spend.
                Ctx ctx = stall.ctx(BUYER, price, BigInteger.ZERO);
                Object sale = i.invoke("Stall", "sell", stallState, cfg, price, ctx);
                @SuppressWarnings("unchecked")
                var deposits = (JulcList<org.julclang.state.api.Deposit>) FourWay.component(sale, "deposits");
                Object next = FourWay.component(sale, "state");
                var tx = stall.stepWith(stall.held(stallState, BigInteger.ZERO, Assets.none()), i.make("StallValidator$Sell", price, ctx), null,
                        Transition.full(next, null, null, null, null).withDeposits(deposits), next, BUYER, ctx, null);
                Value fee = deposits.head().value();
                var claim = new DepositClaim(BigInteger.valueOf(tx.outputs.size()), StateHarness.LIVE_REF, fee, Builtins.bData(BUYER));
                var merged = merge(tx, collect(i, pot, potHeld, potState, List.of(claim)));
                merged.depositClaims = new ArrayList<>(List.of(claim));
                // The deposit names each implementation's own pot (its policy): compared without it. The pot's next state too
                // (review r1, C5).
                String result = (StateHarness.data(next) + "|" + StateHarness.data(deposits) + "|" + StateHarness.data(collected))
                        .replace(java.util.HexFormat.of().formatHex(pot.policy()), "<pot>");
                return run(result, stall.evaluate(merged), pot.evaluate(merged));
            }
            case "collect @ max" -> {
                // DEPOSITS claims on the pot's output, each from its own input, each with lovelace and two token names of unequal
                // length (#85: the registry carries them in the wire's order).
                byte[] p = StateHarness.fill(28, 0x70);
                var claims = new ArrayList<DepositClaim>();
                var inputs = new ArrayList<TxInInfo>();
                for (int k = 0; k < DEPOSITS; k++) {
                    var by = new TxOutRef(new TxId(StateHarness.fill(32, 0x40 + k)), BigInteger.ZERO);
                    inputs.add(new TxInInfo(by, new TxOut(new Address(new Credential.PubKeyCredential(new org.julclang.ledger.PubKeyHash(BUYER)),
                            Optional.empty()), Assets.lovelace(ADA.multiply(BigInteger.TEN)), new OutputDatum.NoOutputDatum(), Optional.empty())));
                    Value v = Assets.add(Assets.lovelace(BigInteger.valueOf(500_000 + k)), Assets.add(Assets.of(p, new byte[]{0, (byte) 0xFF}, BigInteger.ONE),
                            Assets.of(p, new byte[]{1}, BigInteger.TWO)));
                    claims.add(new DepositClaim(BigInteger.ZERO, by, v, Builtins.bData(BUYER)));
                }
                var tx = collect(i, pot, potHeld, potState, claims);
                // The pot's output is the transaction's first: the claims name it.
                int at = tx.stateOutput;
                for (int k = 0; k < claims.size(); k++) {
                    var c = claims.get(k);
                    claims.set(k, new DepositClaim(BigInteger.valueOf(at), c.by(), c.value(), c.memo()));
                }
                tx.inputs.addAll(inputs);
                tx.depositClaims = claims;
                // The pot's next state, compared between the implementations (review r1, C5: not a constant).
                return run(StateHarness.data(collected).toString(), pot.evaluate(tx));
            }
            default -> {
                // pot close / stall close
                boolean pc = call.startsWith("pot");
                var h = pc ? pot : stall;
                Object s = pc ? potState : stallState;
                var dl = h.live(s, ADA.multiply(BigInteger.TEN));
                Ctx ctx = h.ctx(OWNER, BigInteger.ZERO, dl.lovelace());
                @SuppressWarnings("unchecked")
                var pays = (JulcList<Payout>) i.invoke(pc ? "Pot" : "Stall", "close", s, ctx);
                String v = pc ? "PotValidator$" : "StallValidator$";
                return run(StateHarness.data(pays).toString(), h.evaluate(h.destroy(dl, i.make(v + "Close", ctx), i.make(v + "BurnInstance"), pays, OWNER, ctx)));
            }
        }
    }

    /** The pot's next state of the last {@link #collect}. */
    Object collected;

    /** The pot's collect of {@code claims}, its output holding them (its value equation adds the claimed deposits). */
    TxModel collect(Impl i, StateHarness<Object> pot, StateHarness.Held potHeld, Object potState, List<DepositClaim> claims) throws Exception {
        Ctx potCtx = pot.ctx(BUYER, BigInteger.ZERO, BigInteger.ZERO);
        JulcList<DepositClaim> cs = JulcList.empty();
        for (int k = claims.size() - 1; k >= 0; k--) cs = cs.prepend(claims.get(k));
        Object next = i.invoke("Pot", "collect", potState, potCtx, pot.funds(potHeld, Assets.none(), cs));
        collected = next;
        var potTx = pot.stepWith(potHeld, i.make("PotValidator$Collect", potCtx), null, Transition.full(next, null, null, null, null), next, BUYER,
                potCtx, null);
        TxOut o = potTx.outputs.get(potTx.stateOutput);
        Value total = Assets.none();
        for (var c : claims) total = Assets.add(total, c.value());
        potTx.outputs.set(potTx.stateOutput, new TxOut(o.address(), Assets.add(o.value(), total), o.datum(), o.referenceScript()));
        return potTx;
    }

    // ------------------------------------------------------------------ the comparison

    interface Call {
        Run run(Impl i) throws Exception;
    }

    void compare(String contract, String label, Call call, boolean saturated) throws Exception {
        String pkg = contract.equals("Oracle") || contract.startsWith("Shop") ? "org.julclang.state.examples.oracle" : "org.julclang.state.examples.fees";
        Run j = call.run(new Impl(true, pkg)), p = call.run(new Impl(false, pkg));
        assertTrue(j.accepted(), contract + " " + label + " (Java):\n" + j.verdict());
        assertTrue(p.accepted(), contract + " " + label + " (.jsc 2):\n" + p.verdict());
        assertEquals(j.result(), p.result(), contract + " " + label + ": the port's JVM result differs from the Java example's");
        double cpu = (double) p.cpu() / j.cpu(), mem = (double) p.mem() / j.mem();
        String row = String.format("| %s | %s | %,d | %,d | %+.1f%% | %,d | %,d (%+.1f%%) | %.2f%% / %.2f%% |", contract, label, j.cpu(), p.cpu(),
                100 * (cpu - 1), j.mem(), p.mem(), 100 * (mem - 1), 100.0 * p.cpu() / MAX_CPU, 100.0 * p.mem() / MAX_MEM);
        System.out.println("[M2c budget] " + row);
        ROWS.add(row);
        assertTrue(cpu <= BOUND, contract + " " + label + ": CPU " + p.cpu() + " is more than 15% above Java's " + j.cpu());
        assertTrue(mem <= BOUND, contract + " " + label + ": memory " + p.mem() + " is more than 15% above Java's " + j.mem());
        assertTrue(p.cpu() <= MAX_CPU && p.mem() <= MAX_MEM, contract + " " + label + ": over the per-transaction maximum");
        if (p.cpu() > 0.8 * MAX_CPU || p.mem() > 0.8 * MAX_MEM) WARNINGS.add(contract + " " + label + " is above 80% of the maximum");
    }

    @Test
    void budgets() throws Exception {
        for (String c : List.of("init", "post", "close")) compare("Oracle", c, i -> oracle(i, c), false);
        for (String c : List.of("init", "buy", "close")) compare("Shop (Kiosk)", c, i -> shop(i, c), false);
        for (String c : List.of("pot init", "stall init", "sale", "handOver", "pot close", "stall close")) {
            compare(c.startsWith("pot") || c.equals("handOver") ? "Pot" : c.equals("sale") ? "Stall + Pot" : "Stall", c.replace("pot ", "")
                    .replace("stall ", "").replace("sale", "sell + collect (one transaction)"), i -> fees(i, c), false);
        }
        compare("Pot", "collect @ " + DEPOSITS + " deposits", i -> fees(i, "collect @ max"), true);
        System.out.println("[M2c budget] | Contract | Call | Java CPU | .jsc 2 CPU | Δ CPU | Java memory | .jsc 2 memory (Δ) | % of max (CPU / mem) |");
        ROWS.forEach(r -> System.out.println("[M2c budget] " + r));
        WARNINGS.forEach(w -> System.out.println("[M2c budget] WARNING: " + w));
    }
}
