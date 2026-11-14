package org.julclang.state.toolchain;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Funds;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Send;
import org.julclang.state.dsl.v2.AbiCheck;
import org.julclang.state.runtime.InMemoryNodeStore;
import org.julclang.state.runtime.MerkleSupport;
import org.julclang.state.runtime.Transition;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * ADR-0018 M3a2's gates (J11, J19) for the locker ports (Locker and Safes), against examples/locker:
 * <ul>
 *   <li><b>Size:</b> each port within +15% of the Java example's script and under the runtime's 15,000-byte reference
 *   script limit. Neither the Java examples (14,505 and 14,570 B) nor the ports are below J11's 14 KB: the bound is
 *   stated as an exception with the numbers (the M3a2 report).</li>
 *   <li><b>Encoding:</b> the ports' manifests match the descriptions structurally, but for Safes' state field
 *   {@code isOpen} ({@code open} is a modifier word); Locker's unlock takes Funds (a send is checked against the own
 *   policy), so its description says so.</li>
 *   <li><b>Modifiers:</b> per call, as the Java example's (A9), Locker's unlock's Ctx/assets aside.</li>
 *   <li><b>Budget:</b> every call, each transaction as the runtime builds it (map claims from the JVM run), within +15% of
 *   the Java example's CPU and memory and the per-transaction maximum.</li>
 * </ul>
 */
class JscV2M3a2GateTest {

    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] ADMIN = StateHarness.fill(28, 0xAD), ANN = StateHarness.fill(28, 0xA1), BEN = StateHarness.fill(28, 0xB2);
    static final byte[] POLICY = StateHarness.fill(28, 0x5E), GOLD = "GOLD".getBytes();
    static final int RUNTIME_LIMIT = 15_000;
    static final String JAVA = "org/julclang/state/examples/locker/", DSL = "org/julclang/state/examples/dsl/";
    static final Map<String, int[]> SIZES = new LinkedHashMap<>();
    static final List<String> ROWS = new ArrayList<>();

    @BeforeAll
    static void sizes() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!SIZES.isEmpty()) return;
        for (String c : List.of("Locker", "Safes")) {
            var java = Toolchain.build(Map.of(JAVA + c + ".java", read("../examples/locker/src/main/java/" + JAVA + c + ".java")), Map.of());
            var port = Toolchain.build(Map.of(DSL + c + ".jsc", read(JscV2M1aFourWayTest.DSL.resolve(c + ".jsc").toString())), Map.of());
            for (var r : List.of(java, port)) assertTrue(r.ok(), r.diagnostics().toString());
            SIZES.put(c, new int[]{size(java, c), size(port, c)});
        }
    }

    @Test
    void size_within15PercentOfJava_andUnderTheRuntimeLimit() {
        SIZES.forEach((c, s) -> {
            double ratio = (double) s[1] / s[0];
            System.out.printf("[M3a2 size] %-8s %,7d -> %,7d (%+.1f%%); J11's 14 KB: Java %s, port %s%n", c, s[0], s[1], 100 * (ratio - 1),
                    s[0] < SIZE_LIMIT ? "below" : "over", s[1] < SIZE_LIMIT ? "below" : "over");
            assertTrue(ratio <= BOUND, c + ": " + s[1] + " B is more than 15% above Java's " + s[0] + " B");
            assertTrue(s[1] <= RUNTIME_LIMIT, c + ": " + s[1] + " B is over the runtime's reference-script limit");
        });
    }

    @Test
    void encoding_equalsTheJavaExamples() throws Exception {
        String locker = java.nio.file.Files.readString(JscV2M1aFourWayTest.DSL.resolve("Locker.abi.json"));
        assertEquals(List.of(), AbiCheck.compare(locker, description("org.julclang.state.examples.locker.Locker"), "org.julclang.state.examples.locker",
                "Locker"));
        String safes = java.nio.file.Files.readString(JscV2M1aFourWayTest.DSL.resolve("Safes.abi.json"));
        var unrenamed = AbiCheck.compare(safes, description("org.julclang.state.examples.locker.Safes"), "org.julclang.state.examples.locker", "Safes");
        assertEquals(1, unrenamed.size(), "exactly the stated renaming: " + unrenamed);
        assertEquals(List.of(), AbiCheck.compare(safes.replace("\"name\": \"isOpen\"", "\"name\": \"open\""),
                description("org.julclang.state.examples.locker.Safes"), "org.julclang.state.examples.locker", "Safes"));
        System.out.println("[M3a2 encoding] Locker: datum and every redeemer as examples/locker's; Safes: datum, Holding, every redeemer and"
                + " OpenSafe, but " + unrenamed);
    }

    @Test
    void modifiers_perMethod_equalTheJavaExamples() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        int compared = 0;
        for (String c : List.of("Locker", "Safes")) {
            var java = json.readTree(description("org.julclang.state.examples.locker." + c)).get("x-joust");
            var port = json.readTree(description("org.julclang.state.examples.dsl." + c)).get("x-joust");
            for (String section : List.of("creations", "actions")) {
                var want = new LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
                java.get(section).forEach(x -> want.put(x.get("name").asText(), x));
                assertEquals(want.size(), port.get(section).size(), c + " " + section);
                for (var x : port.get(section)) {
                    String name = x.get("name").asText();
                    var w = want.get(name);
                    assertNotNull(w, c + ": the Java example has no " + name);
                    // Locker's unlock takes Funds in the port (a send is checked against the own policy, D4): it takes assets.
                    boolean unlock = c.equals("Locker") && name.equals("unlock");
                    for (String f : List.of("kind", "ctx", "payable", "takesAssets", "needsService", "policies", "returns")) {
                        if (unlock && f.equals("takesAssets")) continue;
                        assertEquals(String.valueOf(w.get(f)), String.valueOf(x.get(f)), c + "." + name + "." + f);
                    }
                    for (String f : List.of("shared", "signers", "validAfter", "validBefore", "params", "reads", "stepEvents")) {
                        assertEquals(String.valueOf(w.get("recipe").get(f)), String.valueOf(x.get("recipe").get(f)), c + "." + name + ".recipe." + f);
                    }
                    compared++;
                }
            }
        }
        System.out.println("[M3a2 modifiers] Locker and Safes: " + compared + " calls, every modifier and policy as examples/locker's");
    }

    // ------------------------------------------------------------------ the calls

    /** The Java example's classes, or the port's (the same names in another package). */
    static final class Impl {
        final boolean java;
        final String pkg;
        final InMemoryNodeStore store = new InMemoryNodeStore();

        Impl(boolean java) {
            this.java = java;
            this.pkg = java ? "org.julclang.state.examples.locker" : "org.julclang.state.examples.dsl";
        }

        Class<?> cls(String name) throws Exception {
            return Class.forName(pkg + "." + name);
        }

        Object make(String name, Object... values) throws Exception {
            return cls(name).getDeclaredConstructors()[0].newInstance(values);
        }

        @SuppressWarnings("unchecked")
        <S> StateHarness<S> harness(String contract) throws Exception {
            return StateHarness.of((org.julclang.state.runtime.ContractSpec<S>) cls(contract + "Client").getField("SPEC").get(null));
        }

        /** The method by name; Funds dropped when this implementation's method takes none (Java's Locker.unlock). */
        Object invoke(MerkleSupport ms, String contract, String method, Object... args) throws Exception {
            for (var x : cls(contract).getDeclaredMethods()) {
                if (!x.getName().equals(method) || !Modifier.isStatic(x.getModifiers())) continue;
                Object[] a = args;
                if (x.getParameterCount() == args.length - 1 && args[args.length - 1] instanceof Funds) a = Arrays.copyOf(args, args.length - 1);
                if (x.getParameterCount() != a.length) continue;
                Object[] call = a;
                return ms.run(() -> {
                    try {
                        return x.invoke(null, call);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
            throw new AssertionError("no method " + contract + "." + method);
        }
    }

    static Object part(Object r, String name) throws Exception {
        return r.getClass().getMethod(name).invoke(r);
    }

    Value gold(long n) {
        return Assets.of(POLICY, GOLD, BigInteger.valueOf(n));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Run locker(Impl i, String call) throws Exception {
        StateHarness<Object> h = i.harness("Locker");
        Class state = i.cls("Locker$State");
        MerkleSupport ms = new MerkleSupport(i.store);
        Ctx admin = h.ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO);
        ms = new MerkleSupport(i.store);   // one MerkleSupport for a call's run and its witnesses
        Object made = i.invoke(ms, "Locker", "init", POLICY, GOLD, admin);
        Object created = ms.prepareCreation(h.policy(), state, made, h.seed()).stored();
        if (call.equals("init")) {
            var p = ms.prepareCreation(h.policy(), state, made, h.seed());
            return run(StateHarness.data(p.stored()).toString(), h.evaluate(h.withClaims(h.createWith(i.make("LockerValidator$CreateInstance", h.seed(),
                    POLICY, GOLD, admin), p.stored(), admin, Assets.none(), false, null), p.claims())));
        }
        // ANN and BEN have locked 30 and 10: the state the calls start from.
        Object s = created;
        long locked = 0;
        for (Object[] who : new Object[][]{{ANN, 30L}, {BEN, 10L}}) {
            Ctx c = h.ctx((byte[]) who[0], BigInteger.ZERO, BigInteger.ZERO);
            var held = h.held(s, BigInteger.ZERO, locked == 0 ? Assets.none() : gold(locked));
            ms = new MerkleSupport(i.store);   // one MerkleSupport for a call's run and its witnesses
            Object r = i.invoke(ms, "Locker", "lock", s, BigInteger.valueOf((Long) who[1]), c, h.funds(held, gold((Long) who[1])));
            s = ms.prepare(h.policy(), state, s, part(r, "state"), StateHarness.LIVE_REF).stored();
            locked += (Long) who[1];
        }
        Object before = s;
        long had = locked;
        switch (call) {
            case "lock" -> {
                Ctx c = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
                var held = h.held(before, BigInteger.ZERO, gold(had));
                ms = new MerkleSupport(i.store);   // one MerkleSupport for a call's run and its witnesses
                Object r = i.invoke(ms, "Locker", "lock", before, BigInteger.valueOf(5), c, h.funds(held, gold(5)));
                var p = ms.prepare(h.policy(), state, before, part(r, "state"), StateHarness.LIVE_REF);
                var tx = h.stepWith(held, i.make("LockerValidator$Lock", BigInteger.valueOf(5), c, gold(5).toPlutusData()), null,
                        Transition.full(p.stored(), null, null, null, (Value) part(r, "accepted")), p.stored(), ANN, c, null);
                return run(StateHarness.data(p.stored()).toString(), h.evaluate(h.withClaims(tx, p.claims())));
            }
            case "unlock" -> {
                Ctx c = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
                var held = h.held(before, BigInteger.ZERO, gold(had));
                ms = new MerkleSupport(i.store);   // one MerkleSupport for a call's run and its witnesses
                Object r = i.invoke(ms, "Locker", "unlock", before, BigInteger.valueOf(30), c, h.funds(held, Assets.none()));
                var p = ms.prepare(h.policy(), state, before, part(r, "state"), StateHarness.LIVE_REF);
                var sends = (JulcList<Send>) part(r, "sends");
                var tx = h.stepWith(held, i.make("LockerValidator$Unlock", BigInteger.valueOf(30), c), null,
                        Transition.full(p.stored(), null, sends, null, null), p.stored(), ANN, c, null);
                return run(StateHarness.data(p.stored()) + " " + StateHarness.data(sends), h.evaluate(h.withClaims(tx, p.claims())));
            }
            default -> {
                // Closing the empty locker.
                Ctx c = h.ctx(ADMIN, BigInteger.ZERO, BigInteger.valueOf(h.minAda()));
                var held = h.held(created, BigInteger.ZERO, Assets.none());
                ms = new MerkleSupport(i.store);   // one MerkleSupport for a call's run and its witnesses
                var pays = (JulcList<Payout>) i.invoke(ms, "Locker", "close", created, c);
                var p = ms.prepare(h.policy(), state, created, null, StateHarness.LIVE_REF);
                var tx = h.stepWith(held, i.make("LockerValidator$Close", c), i.make("LockerValidator$BurnInstance"),
                        Transition.full(null, pays, null, null, null), null, ADMIN, c, null);
                return run(StateHarness.data(pays).toString(), h.evaluate(h.withClaims(tx, p.claims())));
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Run safes(Impl i, String call) throws Exception {
        StateHarness<Object> h = i.harness("Safes");
        Class safe = i.cls("Safes$Safe");
        MerkleSupport ms = new MerkleSupport(i.store);
        Ctx admin = h.ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO);
        ms = new MerkleSupport(i.store);   // one MerkleSupport for a call's run and its witnesses
        Object keeper = i.invoke(ms, "Safes", "init", admin);
        if (call.equals("init")) {
            return run(StateHarness.data(keeper).toString(), h.evaluate(h.createWith(i.make("SafesValidator$CreateInstance", h.seed(), admin), keeper,
                    admin, Assets.none(), false, null)));
        }
        Ctx ann = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        ms = new MerkleSupport(i.store);   // one MerkleSupport for a call's run and its witnesses
        Object opened = i.invoke(ms, "Safes", "openSafe", keeper, POLICY, GOLD, BigInteger.valueOf(70), ann, h.createFunds(true, gold(70)));
        var created = ms.prepareCreation(h.policy(), safe, part(opened, "state"), h.seed());
        Object s = created.stored();
        switch (call) {
            case "openSafe" -> {
                var tx = h.createWith(i.make("SafesValidator$OpenSafe", h.seed(), h.instanceId(), POLICY, GOLD, BigInteger.valueOf(70), ann,
                        gold(70).toPlutusData()), i.make("SafesValidator$Holding", h.instanceId(), s), ann, gold(70), true, keeper);
                return run(StateHarness.data(s).toString(), h.evaluate(h.withClaims(tx, created.claims())));
            }
            case "note" -> {
                ms = new MerkleSupport(i.store);   // one MerkleSupport for a call's run and its witnesses
                Object next = i.invoke(ms, "Safes", "note", s, "why".getBytes(), BigInteger.valueOf(7));
                var p = ms.prepare(h.policy(), safe, s, next, StateHarness.OBJECT_REF);
                var held = h.heldObject(i.make("SafesValidator$Holding", h.instanceId(), s), BigInteger.ZERO, gold(70));
                var tx = h.stepWith(held, i.make("SafesValidator$Note", "why".getBytes(), BigInteger.valueOf(7)), null,
                        Transition.full(p.stored(), null, null, null, null), i.make("SafesValidator$Holding", h.instanceId(), p.stored()), ANN, null, null);
                return run(StateHarness.data(p.stored()).toString(), h.evaluate(h.withClaims(tx, p.claims())));
            }
            default -> {
                var held = h.heldObject(i.make("SafesValidator$Holding", h.instanceId(), s), BigInteger.ZERO, gold(70));
                Ctx c = h.ctx(ANN, BigInteger.ZERO, held.lovelace());
                ms = new MerkleSupport(i.store);   // one MerkleSupport for a call's run and its witnesses
                Object r = i.invoke(ms, "Safes", "empty", s, c, h.funds(held, Assets.none()));
                var pays = (JulcList<Payout>) part(r, "payouts");
                var sends = (JulcList<Send>) part(r, "sends");
                var p = ms.prepare(h.policy(), safe, s, null, StateHarness.OBJECT_REF);
                var tx = h.stepWith(held, i.make("SafesValidator$Empty", c), i.make("SafesValidator$BurnInstance"),
                        Transition.full(null, pays, sends, null, null), null, ANN, c, null);
                return run(StateHarness.data(pays) + " " + StateHarness.data(sends), h.evaluate(h.withClaims(tx, p.claims())));
            }
        }
    }

    interface Call {
        Run run(Impl i) throws Exception;
    }

    void compare(String contract, String label, Call call) throws Exception {
        Run j = call.run(new Impl(true)), p = call.run(new Impl(false));
        assertTrue(j.accepted(), contract + " " + label + " (Java):\n" + j.verdict());
        assertTrue(p.accepted(), contract + " " + label + " (.jsc 2):\n" + p.verdict());
        assertEquals(j.result(), p.result(), contract + " " + label + ": the port's JVM result differs from the Java example's");
        double cpu = (double) p.cpu() / j.cpu(), mem = (double) p.mem() / j.mem();
        String row = String.format("| %s | %s | %,d | %,d | %+.1f%% | %,d | %,d (%+.1f%%) | %.2f%% / %.2f%% |", contract, label, j.cpu(), p.cpu(),
                100 * (cpu - 1), j.mem(), p.mem(), 100 * (mem - 1), 100.0 * p.cpu() / MAX_CPU, 100.0 * p.mem() / MAX_MEM);
        ROWS.add(row);
        assertTrue(cpu <= BOUND, contract + " " + label + ": CPU " + p.cpu() + " is more than 15% above Java's " + j.cpu());
        assertTrue(mem <= BOUND, contract + " " + label + ": memory " + p.mem() + " is more than 15% above Java's " + j.mem());
        assertTrue(p.cpu() <= MAX_CPU && p.mem() <= MAX_MEM, contract + " " + label + ": over the per-transaction maximum");
    }

    @Test
    void budgets() throws Exception {
        for (String c : List.of("init", "lock", "unlock", "close")) compare("Locker", c, i -> locker(i, c));
        for (String c : List.of("init", "openSafe", "note", "empty")) compare("Safes", c, i -> safes(i, c));
        System.out.println("[M3a2 budget] | Contract | Call | Java CPU | .jsc 2 CPU | Δ CPU | Java memory | .jsc 2 memory (Δ) | % of max (CPU / mem) |");
        ROWS.forEach(r -> System.out.println("[M3a2 budget] " + r));
    }
}
