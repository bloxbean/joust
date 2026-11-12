package org.julclang.state.toolchain;

import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.MapEntry;
import org.julclang.state.api.MerkleMap;
import org.julclang.state.api.Payout;
import org.julclang.state.api.mpf.Mpf;
import org.julclang.state.dsl.v2.AbiCheck;
import org.julclang.state.runtime.InMemoryNodeStore;
import org.julclang.state.runtime.MerkleSupport;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.julclang.vm.EvalResult;
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
import java.util.TreeMap;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M1c's gates (J11, J19) for the five example ports, each against its Java example:
 * <ul>
 *   <li><b>Size:</b> the port's script is within +15% of the Java example's, and below 14 KB (both built by the
 *   toolchain, as Gradle builds them).</li>
 *   <li><b>Encoding:</b> the port's ABI manifest matches the Java example's own processor description, structurally
 *   (AbiCheck): the datum, every redeemer and the intent datum encode exactly as the Java example's, so a port is a
 *   drop-in for the Java contract's clients and data.</li>
 *   <li><b>Behaviour:</b> on every scenario below, the port's JVM result (next state and payouts, as data) equals the
 *   Java example's.</li>
 *   <li><b>Budget:</b> every call's execution units (CPU and memory, summed over every script the transaction runs)
 *   are within +15% of the Java example's on the same transaction shape.</li>
 *   <li><b>The budget gate:</b> every method of every port, at a state saturated to {@code maxDatumBytes} (the largest
 *   input state for which the call is still valid; Int fields padded, maps filled, Token's BigMap with 1,024 holders),
 *   fits the protocol's per-transaction maximum (10,000,000,000 CPU steps, 14,000,000 memory units: the pre-Plomin
 *   memory limit, the stricter one), with a warning above 80% (J19).</li>
 * </ul>
 */
class JscV2M1cGateTest {

    static final long MAX_CPU = 10_000_000_000L;
    static final long MAX_MEM = 14_000_000L;
    static final int SIZE_LIMIT = 14_000;
    static final double BOUND = 1.15;
    static final HexFormat HEX = HexFormat.of();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final BigInteger T0 = StateHarness.T0;
    static final BigInteger LATER = T0.add(BigInteger.valueOf(3_600_000));
    static final BigInteger PAST = T0.subtract(BigInteger.valueOf(60_000));
    static final byte[] OWNER = StateHarness.fill(28, 0x0A), ANN = StateHarness.fill(28, 0xF1), BEN = StateHarness.fill(28, 0x21),
            CAT = StateHarness.fill(28, 0xC1), SELLER = StateHarness.fill(28, 0x5E), ARBITER = StateHarness.fill(28, 0xAB),
            STRANGER = StateHarness.fill(28, 0x77);

    /** Each port: its Java example's package and init method, its state's maxDatumBytes. */
    record Example(String name, String javaPkg, String javaInit) {}

    static final List<Example> EXAMPLES = List.of(new Example("Vault", "vault", "open"), new Example("Escrow", "escrow", "open"),
            new Example("Crowdfund", "crowdfund", "launch"), new Example("Token", "token", "create"), new Example("Pool", "pool", "init"));
    static final Map<String, int[]> SIZES = new LinkedHashMap<>();   // name -> {java, port}
    static final Map<String, String[]> HASHES = new LinkedHashMap<>();   // name -> {java, port}
    static final List<String> ROWS = new ArrayList<>();

    static BigInteger ada(long n) {
        return ADA.multiply(BigInteger.valueOf(n));
    }

    static BigInteger n(long v) {
        return BigInteger.valueOf(v);
    }

    static BigInteger big(int digits) {
        return BigInteger.TEN.pow(digits);
    }

    /** A time {@code digits} digits long, after every window here / before every window here. */
    static BigInteger future(int digits) {
        return LATER.add(big(digits));
    }

    static BigInteger past(int digits) {
        return PAST.subtract(big(digits));
    }

    @BeforeAll
    static void sizes() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!SIZES.isEmpty()) return;
        for (Example e : EXAMPLES) {
            String javaPath = "org/julclang/state/examples/" + e.javaPkg() + "/" + e.name() + ".java";
            var j = Toolchain.build(Map.of(javaPath, Files.readString(Path.of("../examples/" + e.javaPkg() + "/src/main/java/" + javaPath))), Map.of());
            var p = Toolchain.build(Map.of("org/julclang/state/examples/dsl/" + e.name() + ".jsc",
                    Files.readString(JscV2M1aFourWayTest.DSL.resolve(e.name() + ".jsc"))), Map.of());
            assertTrue(j.ok() && p.ok(), j.diagnostics() + " " + p.diagnostics());
            SIZES.put(e.name(), new int[]{j.contracts().get(0).scriptSizeBytes(), p.contracts().get(0).scriptSizeBytes()});
            HASHES.put(e.name(), new String[]{j.contracts().get(0).scriptHash(), p.contracts().get(0).scriptHash()});
        }
    }

    // ------------------------------------------------------------------ size and encoding

    @Test
    void sizes_within15PercentOfJava_andBelow14KB() {
        System.out.println("[M1c size] contract: Java bytes -> .jsc 2 bytes (change)");
        SIZES.forEach((name, s) -> {
            // The toolchain's scripts are Gradle's (I1): the ones the harnesses below evaluate, by their hashes.
            try {
                Example e = EXAMPLES.stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
                String gradleJava = HEX.formatHex(impl("org.julclang.state.examples." + e.javaPkg(), name, e.javaInit()).h.policy());
                String gradlePort = HEX.formatHex(port(name).h.policy());
                assertEquals(List.of(HASHES.get(name)[0], HASHES.get(name)[1]), List.of(gradleJava, gradlePort), name + ": toolchain vs Gradle scripts");
            } catch (Exception x) {
                throw new IllegalStateException(x);
            }
            double ratio = (double) s[1] / s[0];
            System.out.printf("[M1c size] %-9s %,7d -> %,7d (%+.1f%%)%n", name, s[0], s[1], 100 * (ratio - 1));
            assertTrue(ratio <= BOUND, name + ": " + s[1] + " B is more than 15% above Java's " + s[0] + " B");
            assertTrue(s[1] < SIZE_LIMIT, name + ": " + s[1] + " B is not below 14 KB");
        });
    }

    @Test
    void encodings_equalTheJavaExamples() throws Exception {
        for (Example e : EXAMPLES) {
            String abi = Files.readString(JscV2M1aFourWayTest.DSL.resolve(e.name() + ".abi.json"));
            String pkg = "org.julclang.state.examples." + e.javaPkg();
            var desc = JscV2M1cGateTest.class.getClassLoader().getResourceAsStream("META-INF/joust/" + pkg + "." + e.name() + ".json");
            assertNotNull(desc, e.name() + "'s Java description");
            var problems = AbiCheck.compare(abi, new String(desc.readAllBytes(), StandardCharsets.UTF_8), pkg, e.name());
            assertEquals(List.of(), problems, e.name() + ": the port encodes differently from the Java example");
            System.out.println("[M1c encoding] " + e.name() + ": datum, redeemers" + (abi.contains("\"intent\"") ? ", intent datum" : "")
                    + " encode exactly as examples/" + e.javaPkg() + "'s");
        }
    }

    // ------------------------------------------------------------------ one implementation, by reflection

    /** A contract implementation (the Java example or the port): its classes, its harness, a node store for its maps. */
    static final class Impl {
        final String pkg, name, init;
        final Class<?> cls, stateClass;
        final StateHarness<Object> h;
        final InMemoryNodeStore store = new InMemoryNodeStore();

        @SuppressWarnings("unchecked")
        Impl(String pkg, String name, String init) throws Exception {
            this.pkg = pkg;
            this.name = name;
            this.init = init;
            this.cls = Class.forName(pkg + "." + name);
            this.stateClass = Class.forName(pkg + "." + name + "$State");
            var spec = (org.julclang.state.runtime.ContractSpec<Object>) Class.forName(pkg + "." + name + "Client").getField("SPEC").get(null);
            // Each its own script: the Java example's and the port's validators share a simple name (FourWay.pinned).
            this.h = StateHarness.of((org.julclang.state.runtime.ContractSpec<Object>) FourWay.pinned(spec));
        }

        /** A state from its fields in declaration order; a {@code Map<byte[], BigInteger>} becomes a stored MerkleMap of those contents. */
        @SuppressWarnings("unchecked")
        Object state(List<Object> fields) throws Exception {
            var comps = stateClass.getRecordComponents();
            var types = new Class<?>[comps.length];
            var vals = new Object[comps.length];
            for (int i = 0; i < comps.length; i++) {
                types[i] = comps[i].getType();
                Object v = fields.get(i);
                vals[i] = types[i] == MerkleMap.class ? new MerkleMap(root((Map<byte[], BigInteger>) v), JulcList.empty(), JulcList.empty()) : v;
            }
            return stateClass.getDeclaredConstructor(types).newInstance(vals);
        }

        final Map<Object, byte[]> roots = new java.util.IdentityHashMap<>();

        byte[] root(Map<byte[], BigInteger> m) {
            if (m.isEmpty()) return Mpf.emptyRoot();
            return roots.computeIfAbsent(m, x -> build(m));
        }

        private byte[] build(Map<byte[], BigInteger> m) {
            var sorted = new TreeMap<String, byte[]>();
            m.forEach((k, v) -> sorted.put(HEX.formatHex(k), org.julclang.stdlib.Builtins.integerToByteString(true, 0, v)));
            JulcList<MapEntry> net = JulcList.empty();
            for (var e : sorted.descendingMap().entrySet()) net = net.prepend(new MapEntry(HEX.parseHex(e.getKey()), true, e.getValue()));
            return new MerkleSupport.TrieProver(store).write(Mpf.emptyRoot(), net).root();
        }

        Object construct(String nested, List<Object> values) throws Exception {
            Class<?> rc = Class.forName(pkg + "." + name + nested);
            var comps = rc.getRecordComponents();
            var types = new Class<?>[comps.length];
            for (int i = 0; i < comps.length; i++) types[i] = comps[i].getType();
            return rc.getDeclaredConstructor(types).newInstance(values.toArray());
        }

        boolean takesCtx(String nested) throws Exception {
            for (var c : Class.forName(pkg + "." + name + nested).getRecordComponents()) if (c.getName().equals("ctx")) return true;
            return false;
        }

        Object invoke(String method, Object state, List<Object> args, Ctx ctx) throws Exception {
            String m = method.equals("init") ? init : method;
            Method found = null;
            for (Method x : cls.getDeclaredMethods()) if (x.getName().equals(m)) found = x;
            assertNotNull(found, name + "." + m);
            var call = new ArrayList<Object>();
            if (state != null) call.add(state);
            call.addAll(args);
            if (found.getParameterCount() > call.size()) call.add(ctx);
            try {
                return found.invoke(null, call.toArray());
            } catch (InvocationTargetException e) {
                throw e.getCause() instanceof Exception x ? x : new IllegalStateException(e.getCause());
            }
        }
    }

    static final Map<String, Impl> IMPLS = new LinkedHashMap<>();

    static Impl impl(String pkg, String name, String init) throws Exception {
        String key = pkg + "." + name;
        Impl i = IMPLS.get(key);
        if (i == null) {
            i = new Impl(pkg, name, init);
            IMPLS.put(key, i);
        }
        return i;
    }

    static Impl port(String name) throws Exception {
        return impl("org.julclang.state.examples.dsl", name, "init");
    }

    /** The size of the state a call starts from, or for an init the one it creates (the JVM only). */
    static int size(Impl impl, Call c) throws Exception {
        if (c.state() != null) return bytes(impl.state(c.state()));
        Ctx ctx = new Ctx(c.caller(), c.received(), c.balance(), T0, T0.add(StateHarness.WINDOW_MS));
        return bytes(new MerkleSupport(impl.store).run(() -> {
            try {
                return impl.invoke(c.method(), null, c.args(), ctx);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    /** A call to run on both implementations: kind is init, action, destroy or batch (an intent applied by a batch of one). */
    record Call(String label, String kind, String method, List<Object> state, List<Object> args, byte[] caller, BigInteger received,
                BigInteger balance) {}

    /** The outcome of one call on one implementation: its JVM result as data, its datum size, its execution units. */
    record Run(String result, int inBytes, int outBytes, long cpu, long mem, StateHarness.Verdict verdict) {}

    static int bytes(Object state) {
        try {
            return org.julclang.clientlib.PlutusDataAdapter.convert(state).serializeToBytes().length;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Run run(Impl impl, Call c) throws Exception {
        var h = impl.h;
        Ctx ctx = new Ctx(c.caller(), c.received(), c.balance(), T0, T0.add(StateHarness.WINDOW_MS));
        boolean maps = MerkleSupport.hasMaps(impl.stateClass);
        var ms = new MerkleSupport(impl.store);
        Object before = c.state() == null ? null : impl.state(c.state());
        Object r = ms.run(() -> {
            try {
                return impl.invoke(c.method(), before, c.args(), ctx);
            } catch (Exception e) {
                throw new IllegalStateException(c.label() + " on " + impl.pkg, e);
            }
        });
        Object next = r;
        JulcList<Payout> pays = JulcList.empty();
        if (r instanceof JulcList<?> l) {
            pays = (JulcList<Payout>) l;
            next = null;
        } else if (!impl.stateClass.isInstance(r)) {
            next = r.getClass().getMethod("state").invoke(r);
            pays = (JulcList<Payout>) r.getClass().getMethod("payouts").invoke(r);
        }
        String result = (next == null ? "-" : StateHarness.data(next).toString()) + " / " + StateHarness.data(pays);
        String cap = Character.toUpperCase(c.method().charAt(0)) + c.method().substring(1);
        var redeemerArgs = new ArrayList<Object>(c.args());
        TxModel tx;
        switch (c.kind()) {
            case "init" -> {
                redeemerArgs.addFirst(h.seed());
                if (impl.takesCtx("Validator$CreateInstance")) redeemerArgs.add(ctx);
                Object redeemer = impl.construct("Validator$CreateInstance", redeemerArgs);
                if (maps) {
                    var p = ms.prepareCreation(h.policy(), (Class) impl.stateClass, next, h.seed());
                    tx = h.withClaims(h.create(redeemer, p.stored(), ctx), p.claims());
                } else {
                    tx = h.create(redeemer, next, ctx);
                }
            }
            case "action" -> {
                if (impl.takesCtx("Validator$" + cap)) redeemerArgs.add(ctx);
                Object redeemer = impl.construct("Validator$" + cap, redeemerArgs);
                var live = h.live(before, c.balance());
                Ctx given = impl.takesCtx("Validator$" + cap) ? ctx : null;
                if (maps) {
                    var p = ms.prepare(h.policy(), (Class) impl.stateClass, before, next, StateHarness.LIVE_REF);
                    tx = h.withClaims(h.act(live, redeemer, p.stored(), pays, c.caller(), given), p.claims());
                } else {
                    tx = h.act(live, redeemer, next, pays, c.caller(), given);
                }
            }
            case "destroy" -> {
                redeemerArgs.add(ctx);
                Object redeemer = impl.construct("Validator$" + cap, redeemerArgs);
                var live = h.live(before, c.balance().subtract(BigInteger.valueOf(h.minAda())));
                tx = h.destroy(live, redeemer, impl.construct("Validator$BurnInstance", List.of()), pays, c.caller(), ctx);
                if (maps) tx = h.withClaims(tx, ms.prepare(h.policy(), (Class) impl.stateClass, before, null, StateHarness.LIVE_REF).claims());
            }
            case "batch" -> {
                var codec = (org.julclang.state.runtime.IntentCodec<Object>) Class.forName(impl.pkg + "." + impl.name + "Client").getField("INTENTS").get(null);
                Object call = impl.construct("Validator$" + cap + "Intent", c.args());
                Object intent = codec.intent(h.instanceId(), c.caller(), StateHarness.pkhAddress(c.caller()), LATER, c.received(), call);
                var it = h.liveIntent(1, intent, c.received().add(ada(2)));
                var b = h.batch(h.live(before, c.balance()), codec, List.of(it));
                assertEquals(1, b.plan().applied().size(), c.label() + ": skipped " + b.plan().skipped());
                tx = b.tx();
            }
            default -> throw new IllegalArgumentException(c.kind());
        }
        var v = h.evaluate(tx);
        long cpu = 0, mem = 0;
        for (EvalResult e : v.results().values()) {
            var u = e.budgetConsumed();
            cpu += u.cpuSteps();
            mem += u.memoryUnits();
        }
        return new Run(result, before == null ? 0 : bytes(before), next == null ? 0 : bytes(next), cpu, mem, v);
    }

    /** Runs {@code c} on the Java example and on the port: both accepted, the same JVM result, the port within +15%. */
    static Run compare(String name, Call c, boolean saturated) throws Exception {
        Example e = EXAMPLES.stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
        Run j = run(impl("org.julclang.state.examples." + e.javaPkg(), name, e.javaInit()), c);
        Run p = run(port(name), c);
        assertTrue(j.verdict().accepted(), name + " " + c.label() + " (Java):\n" + j.verdict());
        assertTrue(p.verdict().accepted(), name + " " + c.label() + " (.jsc 2):\n" + p.verdict());
        assertEquals(j.result(), p.result(), name + " " + c.label() + ": the port's JVM result differs from the Java example's");
        double cpu = (double) p.cpu() / j.cpu(), mem = (double) p.mem() / j.mem();
        String row = String.format("| %s | %s | %s | %,d | %,d | %,d (%+.1f%%) | %,d | %,d (%+.1f%%) | %.2f%% / %.2f%% |", name, c.label(),
                saturated ? p.inBytes() + " → " + p.outBytes() : "", j.cpu(), p.cpu(), p.cpu() - j.cpu(), 100 * (cpu - 1), j.mem(), p.mem(), 100 * (mem - 1),
                100.0 * p.cpu() / MAX_CPU, 100.0 * p.mem() / MAX_MEM);
        System.out.println("[M1c budget] " + row);
        ROWS.add(row);
        assertTrue(cpu <= BOUND, name + " " + c.label() + ": CPU " + p.cpu() + " is more than 15% above Java's " + j.cpu());
        assertTrue(mem <= BOUND, name + " " + c.label() + ": memory " + p.mem() + " is more than 15% above Java's " + j.mem());
        assertTrue(p.cpu() <= MAX_CPU && p.mem() <= MAX_MEM, name + " " + c.label() + ": over the per-transaction maximum");
        if (p.cpu() > 0.8 * MAX_CPU || p.mem() > 0.8 * MAX_MEM) System.out.println("[M1c budget] WARNING: " + name + " " + c.label() + " is above 80% of the maximum");
        return p;
    }

    /**
     * The saturated form of a call: the largest padding (Int fields of {@code digits} decimal digits, collections filled)
     * whose input state fits {@code maxDatumBytes} and for which the call is still valid (its output fits too).
     */
    static void saturated(String name, String label, IntFunction<Call> at) throws Exception {
        var port = port(name);
        int max = port.h.spec().maxDatumBytes();
        // The largest padding whose input state (an init: its output) fits; a call without padding is measured as it is.
        int d = 1;
        if (size(port, at.apply(1)) != size(port, at.apply(400))) {
            while (d < 4000 && size(port, at.apply(d + 1)) <= max) d++;
        }
        for (int k = d; k > 0; k--) {
            Call c = at.apply(k);
            Run p;
            try {
                p = run(port, c);
            } catch (IllegalStateException refused) {
                continue;   // refused on the JVM at this padding (an output past the cap is refused on-chain only)
            }
            if (p.verdict().accepted()) {
                compare(name, new Call(label + " @ max", c.kind(), c.method(), c.state(), c.args(), c.caller(), c.received(), c.balance()), true);
                return;
            }
        }
        fail(name + " " + label + ": no saturated state accepted");
    }

    static Map<byte[], BigInteger> holders(int n, BigInteger each) {
        var m = new LinkedHashMap<byte[], BigInteger>();
        for (int i = 0; i < n; i++) {
            byte[] k = new byte[28];
            k[0] = (byte) (i >> 8);
            k[1] = (byte) i;
            k[27] = 0x11;
            m.put(k, each);
        }
        return m;
    }

    static JulcMap<byte[], BigInteger> backers(int n, BigInteger each) {
        // Canonical order: keys of one length, ascending; JulcMap.insert prepends, so insert from the last.
        var keys = new ArrayList<byte[]>();
        for (int i = 0; i < n; i++) keys.add(StateHarness.fill(28, 0x30 + i));
        JulcMap<byte[], BigInteger> m = JulcMap.empty();
        for (int i = keys.size() - 1; i >= 0; i--) m = m.insert(keys.get(i), each);
        return m;
    }

    // ------------------------------------------------------------------ the calls

    @Test
    void budgets() throws Exception {
        long vaultMin = port("Vault").h.minAda();
        compare("Vault", new Call("init (first deposit)", "init", "init", null, List.of(LATER), OWNER, ada(10), BigInteger.ZERO), false);
        compare("Vault", new Call("deposit", "action", "deposit", List.of(OWNER, LATER, ada(10)), List.of(), STRANGER, ada(5), ada(10)), false);
        compare("Vault", new Call("withdraw", "action", "withdraw", List.of(OWNER, PAST, ada(10)), List.of(ada(3)), OWNER, BigInteger.ZERO, ada(10)), false);
        compare("Vault", new Call("close", "destroy", "close", List.of(OWNER, PAST, ada(10)), List.of(), OWNER, BigInteger.ZERO, ada(10).add(n(vaultMin))), false);
        saturated("Vault", "init", d -> new Call("", "init", "init", null, List.of(future(d)), OWNER, ada(10), BigInteger.ZERO));
        saturated("Vault", "deposit", d -> new Call("", "action", "deposit", List.of(OWNER, future(d), big(d)), List.of(), STRANGER, ada(5), ada(10)));
        saturated("Vault", "withdraw", d -> new Call("", "action", "withdraw", List.of(OWNER, past(d), ada(10).add(big(d))), List.of(ada(3)), OWNER, BigInteger.ZERO, ada(10)));
        saturated("Vault", "close", d -> new Call("", "destroy", "close", List.of(OWNER, past(d), big(d)), List.of(), OWNER, BigInteger.ZERO, ada(10).add(n(vaultMin))));

        long escrowMin = port("Escrow").h.minAda();
        BigInteger held = ada(25).add(n(escrowMin));
        compare("Escrow", new Call("init", "init", "init", null, List.of(SELLER, ARBITER, LATER), OWNER, ada(25), BigInteger.ZERO), false);
        compare("Escrow", new Call("release (buyer)", "destroy", "release", List.of(OWNER, SELLER, ARBITER, ada(25), LATER), List.of(), OWNER, BigInteger.ZERO, held), false);
        compare("Escrow", new Call("release (arbiter)", "destroy", "release", List.of(OWNER, SELLER, ARBITER, ada(25), LATER), List.of(), ARBITER, BigInteger.ZERO, held), false);
        compare("Escrow", new Call("refund (seller)", "destroy", "refund", List.of(OWNER, SELLER, ARBITER, ada(25), LATER), List.of(), SELLER, BigInteger.ZERO, held), false);
        compare("Escrow", new Call("refund (anyone, after)", "destroy", "refund", List.of(OWNER, SELLER, ARBITER, ada(25), PAST), List.of(), STRANGER, BigInteger.ZERO, held), false);
        saturated("Escrow", "init", d -> new Call("", "init", "init", null, List.of(SELLER, ARBITER, future(d)), OWNER, ada(25), BigInteger.ZERO));
        saturated("Escrow", "release (arbiter)", d -> new Call("", "destroy", "release", List.of(OWNER, SELLER, ARBITER, big(d), future(d)), List.of(), ARBITER, BigInteger.ZERO, held));
        saturated("Escrow", "refund (anyone, after)", d -> new Call("", "destroy", "refund", List.of(OWNER, SELLER, ARBITER, big(d), past(d)), List.of(), STRANGER, BigInteger.ZERO, held));

        long cfMin = port("Crowdfund").h.minAda();
        JulcMap<byte[], BigInteger> none = JulcMap.empty();
        JulcMap<byte[], BigInteger> two = none.insert(ANN, ada(10)).insert(BEN, ada(5));
        compare("Crowdfund", new Call("init", "init", "init", null, List.of(ada(100), LATER, ada(2)), OWNER, BigInteger.ZERO, BigInteger.ZERO), false);
        compare("Crowdfund", new Call("contribute (new backer)", "action", "contribute", List.of(OWNER, ada(100), LATER, ada(2), ada(15), two), List.of(), CAT, ada(3), ada(15)), false);
        compare("Crowdfund", new Call("contribute (again)", "action", "contribute", List.of(OWNER, ada(100), LATER, ada(2), ada(15), two), List.of(), ANN, ada(3), ada(15)), false);
        compare("Crowdfund", new Call("refund", "action", "refund", List.of(OWNER, ada(100), PAST, ada(2), ada(15), two), List.of(), ANN, BigInteger.ZERO, ada(15)), false);
        compare("Crowdfund", new Call("claim", "destroy", "claim", List.of(OWNER, ada(15), PAST, ada(2), ada(15), two), List.of(), OWNER, BigInteger.ZERO, ada(15).add(n(cfMin))), false);
        compare("Crowdfund", new Call("close", "destroy", "close", List.of(OWNER, ada(100), PAST, ada(2), BigInteger.ZERO, none), List.of(), OWNER, BigInteger.ZERO, n(cfMin)), false);
        // Saturated: the backers map filled to the last entry that fits (each 2 ADA), then an Int padded.
        int full = 0;
        var port = port("Crowdfund");
        while (bytes(port.state(List.of(OWNER, ada(100), LATER, ada(2), ada(2L * (full + 1)), backers(full + 1, ada(2))))) <= 1024) full++;
        final int k = full;
        System.out.println("[M1c budget] Crowdfund: " + k + " backers fit in 1,024 bytes");
        BigInteger raised = ada(2L * k);
        byte[] first = StateHarness.fill(28, 0x30);
        saturated("Crowdfund", "init", d -> new Call("", "init", "init", null, List.of(big(d), future(d), ada(2).add(big(d))), OWNER, BigInteger.ZERO, BigInteger.ZERO));
        saturated("Crowdfund", "contribute (again, " + k + " backers)", d -> new Call("", "action", "contribute",
                List.of(OWNER, big(d), future(d), ada(2), raised, backers(k, ada(2))), List.of(), first, ada(3), raised));
        saturated("Crowdfund", "contribute (new, " + (k - 1) + " backers)", d -> new Call("", "action", "contribute",
                List.of(OWNER, big(d), future(d), ada(2), raised, backers(k - 1, ada(2))), List.of(), StateHarness.fill(28, 0xEE), ada(3), raised));
        saturated("Crowdfund", "refund (" + k + " backers)", d -> new Call("", "action", "refund",
                List.of(OWNER, raised.add(big(d)), past(d), ada(2), raised, backers(k, ada(2))), List.of(), first, BigInteger.ZERO, raised));
        saturated("Crowdfund", "claim (" + k + " backers)", d -> new Call("", "destroy", "claim",
                List.of(OWNER, ada(2), past(d), big(d), raised, backers(k, ada(2))), List.of(), OWNER, BigInteger.ZERO, raised.add(n(cfMin))));
        saturated("Crowdfund", "close (no backers)", d -> new Call("", "destroy", "close",
                List.of(OWNER, big(d), past(d), big(d), BigInteger.ZERO, none), List.of(), OWNER, BigInteger.ZERO, n(cfMin)));

        long tokenMin = port("Token").h.minAda();
        Map<byte[], BigInteger> three = new LinkedHashMap<>();
        three.put(ANN, n(100));
        three.put(BEN, n(50));
        three.put(CAT, n(25));
        compare("Token", new Call("init", "init", "init", null, List.of(), OWNER, BigInteger.ZERO, BigInteger.ZERO), false);
        compare("Token", new Call("mint", "action", "mint", List.of(OWNER, n(175), three), List.of(ANN, n(10)), OWNER, BigInteger.ZERO, BigInteger.ZERO), false);
        compare("Token", new Call("transfer", "action", "transfer", List.of(OWNER, n(175), three), List.of(BEN, n(30)), ANN, BigInteger.ZERO, BigInteger.ZERO), false);
        compare("Token", new Call("transfer (all: key removed)", "action", "transfer", List.of(OWNER, n(175), three), List.of(ANN, n(25)), CAT, BigInteger.ZERO, BigInteger.ZERO), false);
        compare("Token", new Call("transfer (to self)", "action", "transfer", List.of(OWNER, n(175), three), List.of(ANN, n(40)), ANN, BigInteger.ZERO, BigInteger.ZERO), false);
        compare("Token", new Call("burn", "action", "burn", List.of(OWNER, n(175), three), List.of(n(10)), BEN, BigInteger.ZERO, BigInteger.ZERO), false);
        compare("Token", new Call("close", "destroy", "close", List.of(OWNER, BigInteger.ZERO, Map.of()), List.of(), OWNER, BigInteger.ZERO, n(tokenMin)), false);
        // Saturated: supply padded, 1,024 holders (deeper proofs), the caller's balance padded too.
        var many = holders(1024, n(7));
        byte[] holder = many.keySet().iterator().next();
        saturated("Token", "init (fixed size)", d -> new Call("", "init", "init", null, List.of(), OWNER, BigInteger.ZERO, BigInteger.ZERO));
        saturated("Token", "mint (1,024 holders)", d -> new Call("", "action", "mint", List.of(OWNER, big(d), many), List.of(ANN, n(10)), OWNER, BigInteger.ZERO, BigInteger.ZERO));
        saturated("Token", "transfer (1,024 holders)", d -> new Call("", "action", "transfer", List.of(OWNER, big(d), many), List.of(ANN, n(3)), holder, BigInteger.ZERO, BigInteger.ZERO));
        saturated("Token", "burn (1,024 holders)", d -> new Call("", "action", "burn", List.of(OWNER, big(d), many), List.of(n(3)), holder, BigInteger.ZERO, BigInteger.ZERO));
        saturated("Token", "close (fixed size)", d -> new Call("", "destroy", "close", List.of(OWNER, BigInteger.ZERO, Map.of()), List.of(), OWNER, BigInteger.ZERO, n(tokenMin)));

        long poolMin = port("Pool").h.minAda();
        List<Object> pool = List.of(OWNER, ada(5), n(2), ada(10), ada(1));
        compare("Pool", new Call("init", "init", "init", null, List.of(ada(10), ada(1)), OWNER, BigInteger.ZERO, BigInteger.ZERO), false);
        compare("Pool", new Call("donate (direct)", "action", "donate", pool, List.of(), ANN, ada(5), ada(3)), false);
        compare("Pool", new Call("donate (batch of 1)", "batch", "donate", pool, List.of(), ANN, ada(5), ada(3)), false);
        compare("Pool", new Call("buy (batch of 1, change)", "batch", "buy", pool, List.of(ada(11)), ANN, ada(12), ada(3)), false);
        compare("Pool", new Call("buy (batch of 1, exact)", "batch", "buy", pool, List.of(ada(10)), ANN, ada(10), ada(3)), false);
        compare("Pool", new Call("withdraw", "action", "withdraw", pool, List.of(ada(1)), OWNER, BigInteger.ZERO, ada(3)), false);
        compare("Pool", new Call("close", "destroy", "close", pool, List.of(), OWNER, BigInteger.ZERO, ada(3).add(n(poolMin))), false);
        saturated("Pool", "init", d -> new Call("", "init", "init", null, List.of(ada(10), big(d)), OWNER, BigInteger.ZERO, BigInteger.ZERO));
        saturated("Pool", "donate (direct)", d -> new Call("", "action", "donate", List.of(OWNER, big(d), big(d), ada(10), big(d)), List.of(), ANN, ada(5), ada(3)));
        saturated("Pool", "donate (batch of 1)", d -> new Call("", "batch", "donate", List.of(OWNER, big(d), big(d), ada(10), big(d)), List.of(), ANN, ada(5), ada(3)));
        saturated("Pool", "buy (batch of 1)", d -> new Call("", "batch", "buy", List.of(OWNER, big(d), big(d), ada(10), big(d)), List.of(ada(11)), ANN, ada(12), ada(3)));
        saturated("Pool", "withdraw", d -> new Call("", "action", "withdraw", List.of(OWNER, big(d), big(d), ada(10), big(d)), List.of(ada(1)), OWNER, BigInteger.ZERO, ada(3)));
        saturated("Pool", "close", d -> new Call("", "destroy", "close", List.of(OWNER, big(d), big(d), ada(10), big(d)), List.of(), OWNER, BigInteger.ZERO, ada(3).add(n(poolMin))));

        System.out.println("[M1c budget table]\n| Contract | Call | Datum bytes (saturated: in → out) | Java CPU | .jsc 2 CPU | Δ CPU | Java memory | .jsc 2 memory (Δ) | % of max CPU / memory |\n|---|---|---|---|---|---|---|---|---|\n"
                + String.join("\n", ROWS));
    }
}
