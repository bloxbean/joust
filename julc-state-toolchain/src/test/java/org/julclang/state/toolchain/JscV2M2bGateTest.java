package org.julclang.state.toolchain;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.Credential;
import org.julclang.ledger.ScriptHash;
import org.julclang.state.api.Approval;
import org.julclang.state.api.Approvals;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.dsl.v2.AbiCheck;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
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
 * ADR-0018 M2b's gates (J11, J19) for the treasury port, against examples/treasury, as M2a's are for dex
 * ({@link JscV2M2GateTest}):
 * <ul>
 *   <li><b>Size:</b> the port's script within +15% of the Java example's, and below 14 KB (both built by the toolchain).</li>
 *   <li><b>Encoding:</b> the port's ABI manifest matches the Java example's processor description, structurally: the datum,
 *   the params, every spend redeemer with its events, the mint's {@code CreateInstance} and {@code BurnInstance}. The
 *   stated differences are three field names: a .jsc 2 parameter never takes a state field's name (ADR-0018 D3), so
 *   rotate's {@code council} and {@code quorum} are {@code newCouncil} and {@code newQuorum}, and freeze's {@code frozen}
 *   is {@code on}. Names are not encoded: the data is the same.</li>
 *   <li><b>Behaviour:</b> on every scenario, the port's JVM result (next state, payouts, events) encodes as the Java
 *   example's.</li>
 *   <li><b>Budget:</b> each transaction, signed and approved as the runtime builds it (the quorum's co-signers; the
 *   guardian's zero withdrawal), costs within +15% of the Java example's in CPU and memory.</li>
 *   <li><b>The budget gate:</b> every method at a saturated state (the largest council the datum bound allows, 21 keys,
 *   a majority of it signing) within the per-transaction maximum, warning above 80%.</li>
 * </ul>
 */
class JscV2M2bGateTest {

    static final HexFormat HEX = HexFormat.of();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] BOB = StateHarness.fill(28, 0xB0), GUARDIAN = StateHarness.fill(28, 0x6A);
    static final Map<String, int[]> SIZES = new LinkedHashMap<>();
    static final List<String> ROWS = new ArrayList<>(), WARNINGS = new ArrayList<>();

    static BigInteger ada(long n) {
        return ADA.multiply(BigInteger.valueOf(n));
    }

    @BeforeAll
    static void sizes() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!SIZES.isEmpty()) return;
        String javaPath = "org/julclang/state/examples/treasury/Treasury.java";
        var j = Toolchain.build(Map.of(javaPath, Files.readString(Path.of("../examples/treasury/src/main/java/" + javaPath))), Map.of());
        var p = Toolchain.build(Map.of("org/julclang/state/examples/dsl/Treasury.jsc", Files.readString(JscV2M1aFourWayTest.DSL.resolve("Treasury.jsc"))),
                Map.of());
        assertTrue(j.ok() && p.ok(), j.diagnostics() + " " + p.diagnostics());
        SIZES.put("Treasury", new int[]{j.contracts().get(0).scriptSizeBytes(), p.contracts().get(0).scriptSizeBytes()});
    }

    @Test
    void size_within15PercentOfJava_andBelow14KB() {
        int[] s = SIZES.get("Treasury");
        double ratio = (double) s[1] / s[0];
        System.out.printf("[M2b size] Treasury %,7d -> %,7d (%+.1f%%)%n", s[0], s[1], 100 * (ratio - 1));
        assertTrue(ratio <= BOUND, "Treasury: " + s[1] + " B is more than 15% above Java's " + s[0] + " B");
        assertTrue(s[1] < SIZE_LIMIT, "Treasury: " + s[1] + " B is not below 14 KB");
    }

    @Test
    void encoding_equalsTheJavaExample() throws Exception {
        String abi = Files.readString(JscV2M1aFourWayTest.DSL.resolve("Treasury.abi.json"));
        var desc = JscV2M2bGateTest.class.getClassLoader().getResourceAsStream("META-INF/joust/org.julclang.state.examples.treasury.Treasury.json");
        assertNotNull(desc, "the Java example's description");
        // The stated renamings (D3: a parameter does not take a state field's name), applied to the port's manifest:
        // each of these names is a redeemer field of rotate or freeze only.
        for (String renamed : List.of("newCouncil", "newQuorum", "on")) assertEquals(1, count(abi, "\"name\": \"" + renamed + "\""), renamed);
        String stated = abi.replace("\"name\": \"newCouncil\"", "\"name\": \"council\"").replace("\"name\": \"newQuorum\"", "\"name\": \"quorum\"")
                .replace("\"name\": \"on\"", "\"name\": \"frozen\"");
        var problems = AbiCheck.compare(stated, new String(desc.readAllBytes(), StandardCharsets.UTF_8), "org.julclang.state.examples.treasury", "Treasury");
        assertEquals(List.of(), problems, "the port encodes differently from examples/treasury");
        // Unrenamed, the comparison finds exactly those three names.
        var unrenamed = AbiCheck.compare(abi, new String(JscV2M2bGateTest.class.getClassLoader()
                .getResourceAsStream("META-INF/joust/org.julclang.state.examples.treasury.Treasury.json").readAllBytes(), StandardCharsets.UTF_8),
                "org.julclang.state.examples.treasury", "Treasury");
        assertEquals(3, unrenamed.size(), unrenamed.toString());
        assertTrue(abi.contains("\"params\"") && abi.contains("\"events\""), "the manifest states the params and the events");
        System.out.println("[M2b encoding] Treasury: datum, params, spend redeemers (with events), CreateInstance and BurnInstance encode exactly as"
                + " examples/treasury's; field names differ in three places (stated): " + unrenamed);
    }

    static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
    }

    // ------------------------------------------------------------------ one implementation, by reflection

    static final class Impl {
        final String pkg;
        final Class<?> cls;
        final Object cfg;
        final StateHarness<Object> h;

        @SuppressWarnings("unchecked")
        Impl(String pkg) throws Exception {
            this.pkg = pkg;
            this.cls = Class.forName(pkg + ".Treasury");
            this.cfg = Class.forName(pkg + ".Treasury$Config").getDeclaredConstructors()[0]
                    .newInstance(new Credential.ScriptCredential(new ScriptHash(GUARDIAN)), ada(100));
            var spec = (org.julclang.state.runtime.ContractSpec<Object>) Class.forName(pkg + ".TreasuryClient").getField("SPEC").get(null);
            this.h = StateHarness.of(spec, cfg);
        }

        Object state(JulcList<byte[]> council, BigInteger quorum, boolean frozen) throws Exception {
            return Class.forName(pkg + ".Treasury$State").getDeclaredConstructors()[0].newInstance(council, quorum, frozen);
        }

        Object redeemer(String variant, Object... values) throws Exception {
            Class<?> rc = Class.forName(pkg + ".TreasuryValidator$" + variant);
            return rc.getDeclaredConstructors()[0].newInstance(values);
        }

        Object invoke(String method, Object... args) throws Exception {
            for (Method x : cls.getDeclaredMethods()) {
                if (x.getName().equals(method) && java.lang.reflect.Modifier.isStatic(x.getModifiers()) && x.getParameterCount() == args.length) {
                    try {
                        return x.invoke(null, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause() instanceof Exception ex ? ex : new IllegalStateException(e.getCause());
                    }
                }
            }
            throw new AssertionError("no method " + method + "/" + args.length);
        }
    }

    static final Map<Boolean, Impl> IMPLS = new LinkedHashMap<>();

    static Impl impl(boolean java) throws Exception {
        Impl i = IMPLS.get(java);
        if (i == null) {
            i = new Impl(java ? "org.julclang.state.examples.treasury" : "org.julclang.state.examples.dsl");
            IMPLS.put(java, i);
        }
        return i;
    }

    static JulcList<byte[]> list(List<byte[]> ks) {
        JulcList<byte[]> l = JulcList.empty();
        for (int i = ks.size() - 1; i >= 0; i--) l = l.prepend(ks.get(i));
        return l;
    }

    static TxModel signedBy(TxModel tx, List<byte[]> signers) {
        tx.signers.clear();
        tx.signers.addAll(signers);
        return tx;
    }

    record Run(String result, long cpu, long mem, StateHarness.Verdict verdict) {}

    /** A component of a result record (none: null), as data. */
    static String part(Object r, String name) {
        Object c = FourWay.component(r, name);
        return c == null ? "-" : StateHarness.data(c).toString();
    }

    /** One call of {@code method} on a council of {@code council} keys with quorum {@code q}, signed by the first {@code q}. */
    static Run run(Impl i, String method, List<byte[]> council, int q) throws Exception {
        var h = i.h;
        JulcList<byte[]> keys = list(council);
        List<byte[]> signers = council.subList(0, q);
        byte[] first = council.getFirst();
        BigInteger balance = ada(500);
        Object s = i.state(keys, BigInteger.valueOf(q), false);
        var live = h.live(s, balance);
        TxModel tx;
        String result;
        switch (method) {
            case "init" -> {
                Ctx ctx = h.ctx(first, balance, BigInteger.ZERO);
                Object st = i.invoke(i.pkg.endsWith("treasury") ? "open" : "init", i.cfg, keys, BigInteger.valueOf(q), ctx);
                result = StateHarness.data(st).toString();
                tx = h.create(i.redeemer("CreateInstance", h.seed(), keys, BigInteger.valueOf(q), ctx), st, ctx);
            }
            case "fund" -> {
                Ctx ctx = h.ctx(BOB, ada(5), balance);
                Object st = i.invoke("fund", s, ctx);
                result = StateHarness.data(st).toString();
                tx = h.act(live, i.redeemer("Fund", ctx), st, BOB, ctx);
            }
            case "pay" -> {
                Ctx ctx = h.ctx(first, BigInteger.ZERO, balance);
                Object r = i.invoke("pay", s, i.cfg, BOB, ada(10), ctx);
                result = part(r, "state") + part(r, "payouts") + part(r, "events");
                @SuppressWarnings("unchecked")
                var pays = (JulcList<Payout>) FourWay.component(r, "payouts");
                tx = signedBy(h.act(live, i.redeemer("Pay", BOB, ada(10), ctx, FourWay.component(r, "events")), FourWay.component(r, "state"), pays,
                        first, ctx), signers);
            }
            case "rotate" -> {
                List<byte[]> next = council.subList(0, Math.max(1, council.size() - 1));
                Object r = i.invoke("rotate", s, list(next), BigInteger.ONE);
                result = part(r, "state") + part(r, "events");
                tx = signedBy(h.act(live, i.redeemer("Rotate", list(next), BigInteger.ONE, FourWay.component(r, "events")), FourWay.component(r, "state"),
                        first, null), signers);
            }
            case "freeze" -> {
                Object r = i.invoke("freeze", s, true);
                result = part(r, "state") + part(r, "events");
                tx = h.act(live, i.redeemer("Freeze", true, FourWay.component(r, "events")), FourWay.component(r, "state"), null, null);
                var a = new Approval(h.policy(), h.instanceId(), BigInteger.valueOf(3));
                tx.withdrawals.put(new Credential.ScriptCredential(new ScriptHash(GUARDIAN)),
                        StateHarness.data(new Approvals(JulcList.<Approval>empty().prepend(a))));
            }
            case "close" -> {
                var dl = h.live(s, balance);
                Ctx ctx = h.ctx(first, BigInteger.ZERO, dl.lovelace());
                @SuppressWarnings("unchecked")
                var pays = (JulcList<Payout>) i.invoke("close", s, BOB, ctx);
                result = StateHarness.data(pays).toString();
                tx = signedBy(h.destroy(dl, i.redeemer("Close", BOB, ctx), i.redeemer("BurnInstance"), pays, first, ctx), signers);
            }
            default -> throw new IllegalArgumentException(method);
        }
        var v = h.evaluate(tx);
        return new Run(result, cpu(v), mem(v), v);
    }

    static void compare(String method, List<byte[]> council, int q, boolean saturated) throws Exception {
        Run j = run(impl(true), method, council, q), p = run(impl(false), method, council, q);
        String label = method + (saturated ? " @ max" : "");
        assertTrue(j.verdict().accepted(), "Treasury " + label + " (Java):\n" + j.verdict());
        assertTrue(p.verdict().accepted(), "Treasury " + label + " (.jsc 2):\n" + p.verdict());
        // Compared by component (state, payouts, events): rotate's and freeze's Changed has no payouts, the port's Outcome
        // empty ones, so those two are compared on their state and events.
        assertEquals(j.result(), p.result(), "Treasury " + label + ": the port's JVM result differs from the Java example's");
        double cpu = (double) p.cpu() / j.cpu(), mem = (double) p.mem() / j.mem();
        String row = String.format("| Treasury | %s | %,d | %,d | %,d (%+.1f%%) | %,d | %,d (%+.1f%%) | %.2f%% / %.2f%% |", label, j.cpu(), p.cpu(),
                p.cpu() - j.cpu(), 100 * (cpu - 1), j.mem(), p.mem(), 100 * (mem - 1), 100.0 * p.cpu() / MAX_CPU, 100.0 * p.mem() / MAX_MEM);
        System.out.println("[M2b budget] " + row);
        ROWS.add(row);
        assertTrue(cpu <= BOUND, "Treasury " + label + ": CPU " + p.cpu() + " is more than 15% above Java's " + j.cpu());
        assertTrue(mem <= BOUND, "Treasury " + label + ": memory " + p.mem() + " is more than 15% above Java's " + j.mem());
        assertTrue(p.cpu() <= MAX_CPU && p.mem() <= MAX_MEM, "Treasury " + label + ": over the per-transaction maximum");
        if (p.cpu() > 0.8 * MAX_CPU || p.mem() > 0.8 * MAX_MEM) WARNINGS.add("Treasury " + label + " is above 80% of the maximum");
    }

    /** Sorted 28-byte keys 0x11.., 0x12.., … */
    static List<byte[]> council(int n) {
        var out = new ArrayList<byte[]>();
        for (int k = 0; k < n; k++) out.add(StateHarness.fill(28, 0x11 + k));
        return out;
    }

    @Test
    void budgets() throws Exception {
        // Ordinary: a council of three, two signing. Saturated (#84 review F2): the largest council both the Java example
        // and the port accept under the 640-byte datum bound (a key is 30 bytes of datum: 21 keys; 22 are refused at init),
        // a majority of it (11) signing.
        assertEquals(21, (640 - 10) / 30, "the saturated council");
        for (boolean max : List.of(false, true)) {
            var c = council(max ? 21 : 3);
            int q = max ? 11 : 2;
            for (String m : List.of("init", "fund", "pay", "rotate", "freeze", "close")) compare(m, c, q, max);
        }
        System.out.println("[M2b budget] | Contract | Call | Java CPU | .jsc 2 CPU | Δ CPU | Java memory | .jsc 2 memory (Δ) | % of max (CPU / mem) |");
        ROWS.forEach(r -> System.out.println("[M2b budget] " + r));
        WARNINGS.forEach(w -> System.out.println("[M2b budget] WARNING: " + w));
    }
}
