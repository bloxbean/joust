package org.julclang.state.toolchain;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Funds;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Send;
import org.julclang.state.dsl.v2.AbiCheck;
import org.julclang.state.runtime.Transition;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.julclang.state.toolchain.JscV2M1cGateTest.BOUND;
import static org.julclang.state.toolchain.JscV2M1cGateTest.MAX_CPU;
import static org.julclang.state.toolchain.JscV2M1cGateTest.MAX_MEM;
import static org.julclang.state.toolchain.JscV2M1cGateTest.SIZE_LIMIT;
import static org.julclang.state.toolchain.JscV2M1cGateTest.cpu;
import static org.julclang.state.toolchain.JscV2M1cGateTest.mem;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M2's gates (J11, J19) for the dex port, against examples/dex, as M1c's are for its ports
 * ({@link JscV2M1cGateTest}):
 * <ul>
 *   <li><b>Size:</b> the port's script within +15% of the Java example's, and below 14 KB (both built by the toolchain).</li>
 *   <li><b>Encoding:</b> the port's ABI manifest matches the Java example's processor description, structurally: the datum
 *   (the Java example names its state record Pool, the port State: the one renaming, stated), every spend redeemer with
 *   its {@code assetsIn}, the mint's {@code CreateInstance}, {@code BurnInstance} and {@code ByAction}.</li>
 *   <li><b>Behaviour:</b> on every scenario, the port's JVM result (next state, payouts, sends, mint, accepted) equals the
 *   Java example's.</li>
 *   <li><b>Budget:</b> each transaction, built as the runtime builds it ({@code createWith}, {@code stepWith}), costs
 *   within +15% of the Java example's in CPU and memory, summed over every script it runs (the spend, the mint's
 *   ByAction, the output registry's verifier).</li>
 *   <li><b>The budget gate:</b> every method at a saturated state (a 32-byte token name, quantities of many digits, the
 *   pool holding other assets beside its reserve) within the per-transaction maximum, warning above 80%.</li>
 * </ul>
 */
class JscV2M2GateTest {

    static final HexFormat HEX = HexFormat.of();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final BigInteger T0 = StateHarness.T0;
    static final byte[] ADMIN = StateHarness.fill(28, 0xAD), LP = StateHarness.fill(28, 0x1A), TRADER = StateHarness.fill(28, 0x7E),
            TOKEN_POLICY = StateHarness.fill(28, 0x70), OTHER_POLICY = StateHarness.fill(28, 0x71);
    static final byte[] TOKEN = "HOSKY".getBytes(StandardCharsets.UTF_8), LONG_TOKEN = StateHarness.fill(32, 0x48);
    static final Map<String, int[]> SIZES = new LinkedHashMap<>();
    static final List<String> ROWS = new ArrayList<>(), WARNINGS = new ArrayList<>();

    static BigInteger ada(long n) {
        return ADA.multiply(BigInteger.valueOf(n));
    }

    static BigInteger n(long v) {
        return BigInteger.valueOf(v);
    }

    @BeforeAll
    static void sizes() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!SIZES.isEmpty()) return;
        String javaPath = "org/julclang/state/examples/dex/Dex.java";
        var j = Toolchain.build(Map.of(javaPath, Files.readString(Path.of("../examples/dex/src/main/java/" + javaPath))), Map.of());
        var p = Toolchain.build(Map.of("org/julclang/state/examples/dsl/Dex.jsc", Files.readString(JscV2M1aFourWayTest.DSL.resolve("Dex.jsc"))), Map.of());
        assertTrue(j.ok() && p.ok(), j.diagnostics() + " " + p.diagnostics());
        SIZES.put("Dex", new int[]{j.contracts().get(0).scriptSizeBytes(), p.contracts().get(0).scriptSizeBytes()});
        // The toolchain's scripts are Gradle's: the ones the harnesses below evaluate, by their hashes.
        assertEquals(j.contracts().get(0).scriptHash(), HEX.formatHex(impl(true).h.policy()), "toolchain vs Gradle: the Java example");
        assertEquals(p.contracts().get(0).scriptHash(), HEX.formatHex(impl(false).h.policy()), "toolchain vs Gradle: the port");
    }

    @Test
    void size_within15PercentOfJava_andBelow14KB() {
        int[] s = SIZES.get("Dex");
        double ratio = (double) s[1] / s[0];
        System.out.printf("[M2 size] Dex %,7d -> %,7d (%+.1f%%)%n", s[0], s[1], 100 * (ratio - 1));
        assertTrue(ratio <= BOUND, "Dex: " + s[1] + " B is more than 15% above Java's " + s[0] + " B");
        assertTrue(s[1] < SIZE_LIMIT, "Dex: " + s[1] + " B is not below 14 KB");
    }

    @Test
    void encoding_equalsTheJavaExample() throws Exception {
        String abi = Files.readString(JscV2M1aFourWayTest.DSL.resolve("Dex.abi.json"));
        var desc = JscV2M2GateTest.class.getClassLoader().getResourceAsStream("META-INF/joust/org.julclang.state.examples.dex.Dex.json");
        assertNotNull(desc, "the Java example's description");
        // The one renaming: examples/dex names its state record Pool; a .jsc 2 contract's is State. The encoding is the
        // record's shape, which the comparison then checks field by field.
        String described = new String(desc.readAllBytes(), StandardCharsets.UTF_8)
                .replace("org.julclang.state.examples.dex.Dex.Pool", "org.julclang.state.examples.dex.Dex.State").replace("\"title\":\"Pool\"", "\"title\":\"State\"")
                .replace("\"title\": \"Pool\"", "\"title\": \"State\"");
        var problems = AbiCheck.compare(abi, described, "org.julclang.state.examples.dex", "Dex");
        assertEquals(List.of(), problems, "the port encodes differently from examples/dex");
        assertTrue(abi.contains("\"assetsIn\"") && abi.contains("\"ByAction\""), "the manifest states assetsIn and ByAction");
        System.out.println("[M2 encoding] Dex: datum, spend redeemers (with assetsIn), CreateInstance, BurnInstance and ByAction encode exactly as examples/dex's");
    }

    // ------------------------------------------------------------------ one implementation, by reflection

    static final class Impl {
        final String pkg;
        final Class<?> cls, stateClass;
        final StateHarness<Object> h;

        @SuppressWarnings("unchecked")
        Impl(String pkg, String state) throws Exception {
            this.pkg = pkg;
            this.cls = Class.forName(pkg + ".Dex");
            this.stateClass = Class.forName(pkg + ".Dex$" + state);
            var spec = (org.julclang.state.runtime.ContractSpec<Object>) Class.forName(pkg + ".DexClient").getField("SPEC").get(null);
            // "@ max" measures quantities of 25 digits, more than an output may hold (2^64 - 1): the scripts' worst case,
            // not a transaction the ledger takes. So the harness's ledger rules are off, and run() applies them itself to
            // every other scenario (review r1 S2, r2 S7).
            this.h = StateHarness.of(spec).ledgerRules(false);
        }

        Object state(byte[] tokenName, BigInteger lpSupply) throws Exception {
            byte[] lpName = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash224(h.instanceId());
            return stateClass.getDeclaredConstructors()[0].newInstance(ADMIN, TOKEN_POLICY, tokenName, lpName, lpSupply);
        }

        Object construct(String nested, List<Object> values) throws Exception {
            Class<?> rc = Class.forName(pkg + ".Dex" + nested);
            var comps = rc.getRecordComponents();
            var types = new Class<?>[comps.length];
            for (int i = 0; i < comps.length; i++) types[i] = comps[i].getType();
            return rc.getDeclaredConstructor(types).newInstance(values.toArray());
        }

        Object invoke(String method, List<Object> call) throws Exception {
            for (Method x : cls.getDeclaredMethods()) {
                if (x.getName().equals(method) && java.lang.reflect.Modifier.isStatic(x.getModifiers()) && x.getParameterCount() == call.size()) {
                    try {
                        return x.invoke(null, call.toArray());
                    } catch (InvocationTargetException e) {
                        throw e.getCause() instanceof Exception ex ? ex : new IllegalStateException(e.getCause());
                    }
                }
            }
            throw new AssertionError("no method " + method);
        }
    }

    static final Map<Boolean, Impl> IMPLS = new LinkedHashMap<>();

    static Impl impl(boolean java) throws Exception {
        Impl i = IMPLS.get(java);
        if (i == null) {
            i = java ? new Impl("org.julclang.state.examples.dex", "Pool") : new Impl("org.julclang.state.examples.dsl", "State");
            IMPLS.put(java, i);
        }
        return i;
    }

    /**
     * A scenario: {@code kind} init, action or destroy; the state's token name and LP supply (null for an init); the
     * arguments; the caller; the lovelace received and the instance's balance (an action's: above the deposit; a
     * destroy's: everything); the native assets offered and held.
     */
    record Call(String label, String kind, String method, byte[] tokenName, BigInteger lpSupply, List<Object> args, byte[] caller,
                BigInteger received, BigInteger balance, Value offered, Value held) {}

    record Run(String result, long cpu, long mem, StateHarness.Verdict verdict) {}

    @SuppressWarnings("unchecked")
    static Run run(Impl impl, Call c, boolean saturated) throws Exception {
        var h = impl.h;
        Ctx ctx = new Ctx(c.caller(), c.received(), c.balance(), T0, T0.add(StateHarness.WINDOW_MS));
        boolean destroy = c.kind().equals("destroy");
        Object before = c.lpSupply() == null ? null : impl.state(c.tokenName(), c.lpSupply());
        String cap = Character.toUpperCase(c.method().charAt(0)) + c.method().substring(1);
        String method = c.kind().equals("init") ? (impl.pkg.endsWith("dex") ? "open" : "init") : c.method();
        StateHarness.Held held = before == null ? null
                : h.held(before, destroy ? c.balance().subtract(BigInteger.valueOf(h.minAda())) : c.balance(), c.held());
        // No offer given: the caller offers this side's own LP tokens, as many as the first argument (removeLiquidity).
        Value offered = c.offered() != null ? c.offered() : lpOf(impl, (BigInteger) c.args().get(0));
        Funds funds = before == null ? h.createFunds(false, offered) : h.funds(held, offered);
        var call = new ArrayList<Object>();
        if (before != null) call.add(before);
        call.addAll(c.args());
        call.add(ctx);
        call.add(funds);
        Object r = impl.invoke(method, call);
        Object next = destroy ? null : impl.stateClass.isInstance(r) ? r : FourWay.component(r, "state");
        JulcList<Payout> pays = impl.stateClass.isInstance(r) ? JulcList.empty() : (JulcList<Payout>) FourWay.component(r, "payouts");
        JulcList<Send> sends = impl.stateClass.isInstance(r) ? JulcList.empty() : (JulcList<Send>) FourWay.component(r, "sends");
        Value mint = impl.stateClass.isInstance(r) ? null : (Value) FourWay.component(r, "mint");
        Value accepted = impl.stateClass.isInstance(r) ? null : (Value) FourWay.component(r, "accepted");
        if (mint == null) mint = Assets.none();
        if (accepted == null) accepted = Assets.none();
        if (pays == null) pays = JulcList.empty();
        if (sends == null) sends = JulcList.empty();
        // Each side mints under its own policy: the policy's bytes are replaced by a placeholder of the same length, so
        // the two results compare (the LP token's name, from the same seed, is the same on both).
        String result = FourWay.step(next, pays, sends, mint, accepted).replace(HEX.formatHex(h.policy()), "00".repeat(28));
        var tr = Transition.full(next, pays, sends, mint, accepted);
        var redeemerArgs = new ArrayList<Object>(c.args());
        org.julclang.state.testkit.TxModel tx;
        if (c.kind().equals("init")) {
            redeemerArgs.addFirst(h.seed());
            redeemerArgs.add(ctx);
            tx = h.createWith(impl.construct("Validator$CreateInstance", redeemerArgs), next, ctx, accepted, false, null);
        } else {
            redeemerArgs.add(ctx);
            var comps = Class.forName(impl.pkg + ".DexValidator$" + cap).getRecordComponents();
            if (comps[comps.length - 1].getName().equals("assetsIn")) redeemerArgs.add(offered.toPlutusData());
            Object redeemer = impl.construct("Validator$" + cap, redeemerArgs);
            Object mintRedeemer = !Assets.isEmpty(mint) ? impl.construct("Validator$ByAction", List.of(held.ref()))
                    : destroy ? impl.construct("Validator$BurnInstance", List.of()) : null;
            tx = h.stepWith(held, redeemer, mintRedeemer, tr, next, c.caller(), ctx, null);
        }
        // Only "@ max" may leave the ledger's ranges (review r2 S7): an ordinary scenario must be a transaction it takes.
        if (!saturated) assertNull(StateHarness.ledgerProblem(tx), "Dex " + c.label() + ": the ledger would refuse it");
        var v = h.evaluate(tx);
        return new Run(result, cpu(v), mem(v), v);
    }

    static void compare(Call c, boolean saturated) throws Exception {
        Run j = run(impl(true), c, saturated), p = run(impl(false), c, saturated);
        assertTrue(j.verdict().accepted(), "Dex " + c.label() + " (Java):\n" + j.verdict());
        assertTrue(p.verdict().accepted(), "Dex " + c.label() + " (.jsc 2):\n" + p.verdict());
        assertEquals(j.result(), p.result(), "Dex " + c.label() + ": the port's JVM result differs from the Java example's");
        double cpu = (double) p.cpu() / j.cpu(), mem = (double) p.mem() / j.mem();
        String row = String.format("| Dex | %s%s | %,d | %,d | %,d (%+.1f%%) | %,d | %,d (%+.1f%%) | %.2f%% / %.2f%% |", c.label(), saturated ? " @ max" : "",
                j.cpu(), p.cpu(), p.cpu() - j.cpu(), 100 * (cpu - 1), j.mem(), p.mem(), 100 * (mem - 1), 100.0 * p.cpu() / MAX_CPU, 100.0 * p.mem() / MAX_MEM);
        System.out.println("[M2 budget] " + row);
        ROWS.add(row);
        assertTrue(cpu <= BOUND, "Dex " + c.label() + ": CPU " + p.cpu() + " is more than 15% above Java's " + j.cpu());
        assertTrue(mem <= BOUND, "Dex " + c.label() + ": memory " + p.mem() + " is more than 15% above Java's " + j.mem());
        assertTrue(p.cpu() <= MAX_CPU && p.mem() <= MAX_MEM, "Dex " + c.label() + ": over the per-transaction maximum");
        if (p.cpu() > 0.8 * MAX_CPU || p.mem() > 0.8 * MAX_MEM) WARNINGS.add("Dex " + c.label() + " is above 80% of the maximum");
    }

    static Value tokens(byte[] name, BigInteger n) {
        return Assets.of(TOKEN_POLICY, name, n);
    }

    /** The scenarios, ordinary and saturated; LP tokens are offered under each side's own policy. */
    @Test
    void budgets() throws Exception {
        for (boolean max : List.of(false, true)) {
            byte[] name = max ? LONG_TOKEN : TOKEN;
            // Saturated: quantities of many digits, the pool holding other assets beside its reserve (up to assetSlots).
            BigInteger big = max ? BigInteger.TEN.pow(24) : BigInteger.ZERO;
            BigInteger supply = ada(100).add(big), reserveAda = ada(100).add(big), reserveTok = n(1000).add(big);
            Value extra = max ? Assets.of(OTHER_POLICY, LONG_TOKEN, BigInteger.TEN.pow(30)) : Assets.none();
            Value held = Assets.add(tokens(name, reserveTok), extra);
            var calls = new ArrayList<Call>();
            calls.add(new Call("init", "init", "init", null, null, List.of(TOKEN_POLICY, name), ADMIN, BigInteger.ZERO, BigInteger.ZERO, Assets.none(), Assets.none()));
            calls.add(new Call("addLiquidity (first)", "action", "addLiquidity", name, BigInteger.ZERO, List.of(n(500).add(big)), LP, ada(50).add(big),
                    BigInteger.ZERO, tokens(name, n(500).add(big)), extra));
            calls.add(new Call("addLiquidity", "action", "addLiquidity", name, supply, List.of(n(100)), LP, ada(10), reserveAda, tokens(name, n(100)), held));
            calls.add(new Call("swapAdaForToken", "action", "swapAdaForToken", name, supply, List.of(n(1)), TRADER, ada(10), reserveAda, Assets.none(), held));
            calls.add(new Call("swapTokenForAda", "action", "swapTokenForAda", name, supply, List.of(n(100), n(1)), TRADER, BigInteger.ZERO, reserveAda,
                    tokens(name, n(100)), held));
            calls.add(new Call("removeLiquidity", "action", "removeLiquidity", name, supply, List.of(ada(40).add(big.divide(n(2)))), LP, BigInteger.ZERO,
                    reserveAda, null, held));
            calls.add(new Call("close", "destroy", "close", name, BigInteger.ZERO, List.of(), ADMIN, BigInteger.ZERO,
                    BigInteger.valueOf(impl(false).h.minAda()).add(ada(2)), Assets.none(), Assets.add(tokens(name, n(3)), extra)));
            for (Call c : calls) compare(c, max);
        }
        System.out.println("[M2 budget] | Contract | Call | Java CPU | .jsc 2 CPU | Δ CPU | Java memory | .jsc 2 memory (Δ) | % of max (CPU / mem) |");
        ROWS.forEach(r -> System.out.println("[M2 budget] " + r));
        WARNINGS.forEach(w -> System.out.println("[M2 budget] WARNING: " + w));
    }

    static Value lpOf(Impl i, BigInteger n) {
        byte[] lpName = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash224(i.h.instanceId());
        return Assets.of(i.h.policy(), lpName, n);
    }
}
