package org.julclang.state.toolchain;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.dsl.v2.AbiCheck;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
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
 * ADR-0018 M3a1's gates (J11, J19) for the bank port, against examples/bank:
 * <ul>
 *   <li><b>Size:</b> the port's script within +15% of the Java example's, and below 14 KB (both built by the toolchain).</li>
 *   <li><b>Encoding:</b> the port's ABI manifest matches the Java example's processor description structurally (the datum,
 *   the owned datum Holding, every redeemer, the owned creation); the stated differences are three names: the port's
 *   setPaused takes {@code pause} and setLimit {@code limit} (a parameter never takes a state field's name, D3), and its
 *   Account's field is {@code saved} ({@code balance} is a built-in name).</li>
 *   <li><b>Behaviour and budget:</b> each transaction built as the runtime builds it (an owned creation with the bank as a
 *   reference input, an owned action reading it by reference, an owned destroy), the same JVM result, within +15% of the
 *   Java example's CPU and memory, and within the per-transaction maximum. The states are fixed-size; the saturated row is
 *   an account whose savings fill its 160-byte datum (an integer of {@value #SATURATED_BITS} bits).</li>
 * </ul>
 */
class JscV2M3a1GateTest {

    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] ADMIN = StateHarness.fill(28, 0xAD), ANN = StateHarness.fill(28, 0xA1);
    static final int SATURATED_BITS = 656;   // #101 review F6: the largest the 160-byte datum takes (theSaturatedAccount_…)
    static int[] SIZE;
    static final List<String> ROWS = new ArrayList<>();

    static final String JAVA_BANK = "org/julclang/state/examples/bank/";
    static final String DSL = "org/julclang/state/examples/dsl/";

    @BeforeAll
    static void sizes() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (SIZE != null) return;
        var java = Toolchain.build(Map.of(JAVA_BANK + "Bank.java", read("../examples/bank/src/main/java/" + JAVA_BANK + "Bank.java")), Map.of());
        var port = Toolchain.build(Map.of(DSL + "Bank.jsc", read(JscV2M1aFourWayTest.DSL.resolve("Bank.jsc").toString())), Map.of());
        for (var r : List.of(java, port)) assertTrue(r.ok(), r.diagnostics().toString());
        SIZE = new int[]{size(java, "Bank"), size(port, "Bank")};
    }

    @Test
    void size_within15PercentOfJava_andBelow14KB() {
        double ratio = (double) SIZE[1] / SIZE[0];
        System.out.printf("[M3a1 size] %-12s %,7d -> %,7d (%+.1f%%)%n", "Bank", SIZE[0], SIZE[1], 100 * (ratio - 1));
        assertTrue(ratio <= BOUND, "Bank: " + SIZE[1] + " B is more than 15% above Java's " + SIZE[0] + " B");
        assertTrue(SIZE[1] < SIZE_LIMIT, "Bank: " + SIZE[1] + " B is not below 14 KB");
    }

    @Test
    void encoding_equalsTheJavaExample() throws Exception {
        String abi = java.nio.file.Files.readString(JscV2M1aFourWayTest.DSL.resolve("Bank.abi.json"));
        String want = description("org.julclang.state.examples.bank.Bank");
        var unrenamed = AbiCheck.compare(abi, want, "org.julclang.state.examples.bank", "Bank");
        assertEquals(3, unrenamed.size(), "exactly the stated renamings: " + unrenamed);
        String renamed = abi.replace("\"name\": \"pause\"", "\"name\": \"paused\"").replace("\"name\": \"limit\"", "\"name\": \"withdrawLimit\"")
                .replace("\"name\": \"saved\"", "\"name\": \"balance\"");
        assertEquals(List.of(), AbiCheck.compare(renamed, want, "org.julclang.state.examples.bank", "Bank"));
        System.out.println("[M3a1 encoding] Bank: datum, Holding, every redeemer, CreateInstance, BurnInstance and Open encode exactly as"
                + " examples/bank's; three names differ (stated): " + unrenamed);
    }

    /**
     * Security review A9: the port drops no modifier the Java example has. Per call, the processor's descriptions of both
     * agree on the kind, Ctx, payable, assets, every policy (signedBy, validAfter/Before, quorum, authorizedBy, shared,
     * into, admits, receives, batched, migrateTo), what it returns and the recipe's signers, the stated renamings aside.
     */
    @Test
    void modifiers_perMethod_equalTheJavaExample() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var java = json.readTree(description("org.julclang.state.examples.bank.Bank")).get("x-joust");
        var port = json.readTree(description("org.julclang.state.examples.dsl.Bank")).get("x-joust");
        int compared = 0;
        for (String section : List.of("creations", "actions")) {
            var want = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            java.get(section).forEach(c -> want.put(c.get("name").asText(), c));
            assertEquals(want.size(), port.get(section).size(), section);
            for (var c : port.get(section)) {
                String name = c.get("name").asText();
                var w = want.get(name);
                assertNotNull(w, section + ": the Java example has no " + name);
                for (String f : List.of("kind", "ctx", "payable", "takesAssets", "needsService", "policies", "returns")) {
                    assertEquals(String.valueOf(w.get(f)), String.valueOf(c.get(f)), name + "." + f);
                }
                for (String f : List.of("shared", "signers", "validAfter", "validBefore", "params", "reads", "stepEvents")) {
                    assertEquals(String.valueOf(w.get("recipe").get(f)), String.valueOf(c.get("recipe").get(f)), name + ".recipe." + f);
                }
                compared++;
            }
        }
        System.out.println("[M3a1 modifiers] Bank: " + compared + " calls, every modifier and policy as examples/bank's");
    }

    // ------------------------------------------------------------------ the calls

    /** The Java example's classes, or the port's, by the Java example's names (the bank's are the same names). */
    static final class Impl {
        final String pkg;

        Impl(boolean java, String examplePkg) {
            this.pkg = java ? examplePkg : "org.julclang.state.examples.dsl";
        }

        Object make(String name, Object... values) throws Exception {
            return Class.forName(pkg + "." + name).getDeclaredConstructors()[0].newInstance(values);
        }

        @SuppressWarnings("unchecked")
        <S> StateHarness<S> harness(String contract, Object params) throws Exception {
            var spec = (org.julclang.state.runtime.ContractSpec<S>) Class.forName(pkg + "." + contract + "Client").getField("SPEC").get(null);
            return params == null ? StateHarness.of(spec) : StateHarness.of(spec, params);
        }

        Object invoke(String contract, String method, Object... args) throws Exception {
            for (var x : Class.forName(pkg + "." + contract).getDeclaredMethods()) {
                if (x.getName().equals(method) && java.lang.reflect.Modifier.isStatic(x.getModifiers()) && x.getParameterCount() == args.length) {
                    try {
                        return x.invoke(null, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause() instanceof Exception ex ? ex : new IllegalStateException(e.getCause());
                    }
                }
            }
            throw new AssertionError("no method " + contract + "." + method + "/" + args.length);
        }
    }

    Object account(Impl i, BigInteger saved) throws Exception {
        return i.make("Bank$Account", ANN, saved);
    }

    Object held(Impl i, StateHarness<Object> h, Object account) throws Exception {
        return i.make("BankValidator$Holding", h.instanceId(), account);
    }

    @SuppressWarnings("unchecked")
    Run bank(Impl i, String call, BigInteger saved) throws Exception {
        StateHarness<Object> h = i.harness("Bank", null);
        Object open = i.make("Bank$State", ADMIN, false, ADA.multiply(BigInteger.valueOf(50)));
        Object acct = account(i, saved);
        switch (call) {
            case "init" -> {
                Ctx ctx = h.ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO);
                Object st = i.invoke("Bank", "init", ADA.multiply(BigInteger.valueOf(50)), ctx);
                return run(StateHarness.data(st).toString(), h.evaluate(h.create(i.make("BankValidator$CreateInstance", h.seed(),
                        ADA.multiply(BigInteger.valueOf(50)), ctx), st, ctx)));
            }
            case "setPaused" -> {
                Object st = i.invoke("Bank", "setPaused", open, true);
                return run(StateHarness.data(st).toString(), h.evaluate(h.act(h.live(open, BigInteger.ZERO), i.make("BankValidator$SetPaused", true),
                        st, ADMIN, null)));
            }
            case "setLimit" -> {
                // #101 review F6: the seventh call's row.
                BigInteger limit = ADA.multiply(BigInteger.valueOf(20));
                Object st = i.invoke("Bank", "setLimit", open, limit);
                return run(StateHarness.data(st).toString(), h.evaluate(h.act(h.live(open, BigInteger.ZERO), i.make("BankValidator$SetLimit", limit),
                        st, ADMIN, null)));
            }
            case "open" -> {
                Ctx ctx = h.ctx(ANN, ADA.multiply(BigInteger.TEN), BigInteger.ZERO);
                Object made = i.invoke("Bank", "open", open, ctx);
                return run(StateHarness.data(made).toString(), h.evaluate(h.createObject(i.make("BankValidator$Open", h.seed(), h.instanceId(), ctx),
                        held(i, h, made), open, ctx)));
            }
            case "deposit" -> {
                var obj = h.liveObject(held(i, h, acct), ADA.multiply(BigInteger.TEN));
                Ctx ctx = h.ctx(ANN, ADA.multiply(BigInteger.valueOf(5)), ADA.multiply(BigInteger.TEN));
                Object next = i.invoke("Bank", "deposit", acct, ctx);
                return run(StateHarness.data(next).toString(), h.evaluate(h.actObject(obj, i.make("BankValidator$Deposit", ctx), held(i, h, next),
                        JulcList.empty(), ANN, ctx, null)));
            }
            case "withdraw" -> {
                var obj = h.liveObject(held(i, h, acct), ADA.multiply(BigInteger.TEN));
                Ctx ctx = h.ctx(ANN, BigInteger.ZERO, ADA.multiply(BigInteger.TEN));
                BigInteger amount = ADA.multiply(BigInteger.valueOf(4));
                Object r = i.invoke("Bank", "withdraw", acct, open, amount, ctx);
                Object next = r.getClass().getMethod("state").invoke(r);
                var pays = (JulcList<Payout>) r.getClass().getMethod("payouts").invoke(r);
                return run(StateHarness.data(next) + " " + StateHarness.data(pays), h.evaluate(h.actObject(obj, i.make("BankValidator$Withdraw", amount, ctx),
                        held(i, h, next), pays, ANN, ctx, open)));
            }
            default -> {
                var obj = h.liveObject(held(i, h, acct), ADA.multiply(BigInteger.TEN));
                Ctx ctx = h.ctx(ANN, BigInteger.ZERO, obj.lovelace());
                var pays = (JulcList<Payout>) i.invoke("Bank", "close", acct, ctx);
                return run(StateHarness.data(pays).toString(), h.evaluate(h.destroyObject(obj, i.make("BankValidator$Close", ctx),
                        i.make("BankValidator$BurnInstance"), pays, ANN, ctx)));
            }
        }
    }

    interface Call {
        Run run(Impl i) throws Exception;
    }

    void compare(String label, Call call) throws Exception {
        String pkg = "org.julclang.state.examples.bank";
        Run j = call.run(new Impl(true, pkg)), p = call.run(new Impl(false, pkg));
        assertTrue(j.accepted(), "Bank " + label + " (Java):\n" + j.verdict());
        assertTrue(p.accepted(), "Bank " + label + " (.jsc 2):\n" + p.verdict());
        assertEquals(j.result(), p.result(), "Bank " + label + ": the port's JVM result differs from the Java example's");
        double cpu = (double) p.cpu() / j.cpu(), mem = (double) p.mem() / j.mem();
        String row = String.format("| Bank | %s | %,d | %,d | %+.1f%% | %,d | %,d (%+.1f%%) | %.2f%% / %.2f%% |", label, j.cpu(), p.cpu(),
                100 * (cpu - 1), j.mem(), p.mem(), 100 * (mem - 1), 100.0 * p.cpu() / MAX_CPU, 100.0 * p.mem() / MAX_MEM);
        System.out.println("[M3a1 budget] " + row);
        ROWS.add(row);
        assertTrue(cpu <= BOUND, label + ": CPU " + p.cpu() + " is more than 15% above Java's " + j.cpu());
        assertTrue(mem <= BOUND, label + ": memory " + p.mem() + " is more than 15% above Java's " + j.mem());
        assertTrue(p.cpu() <= MAX_CPU && p.mem() <= MAX_MEM, label + ": over the per-transaction maximum");
    }

    @Test
    void budgets() throws Exception {
        BigInteger ten = ADA.multiply(BigInteger.TEN), full = BigInteger.ONE.shiftLeft(SATURATED_BITS).subtract(BigInteger.ONE);
        for (String c : List.of("init", "setPaused", "setLimit", "open", "deposit", "withdraw", "close")) compare(c, i -> bank(i, c, ten));
        // The deposit's starts five ADA below the largest account, so the account it makes fills the datum (K9).
        for (String c : List.of("deposit", "withdraw", "close")) {
            BigInteger from = c.equals("deposit") ? full.subtract(ADA.multiply(BigInteger.valueOf(5))) : full;
            compare(c + " (saturated)", i -> bank(i, c, from));
        }
        System.out.println("[M3a1 budget] | Contract | Call | Java CPU | .jsc 2 CPU | Δ CPU | Java memory | .jsc 2 memory (Δ) | % of max (CPU / mem) |");
        ROWS.forEach(r -> System.out.println("[M3a1 budget] " + r));
    }

    /** The size of a datum as the ledger receives it: its canonical CBOR (as CCL writes inline datums). */
    static int datumBytes(Object datum) throws Exception {
        var ccl = org.julclang.clientlib.PlutusDataAdapter.convert(datum);
        return com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(ccl.serialize(), true).length;
    }

    /**
     * #101 review F6 (K9, J11's saturation rule): the saturated rows' account is the largest the 160-byte bound accepts.
     * An account's object datum is Holding(instance, Account(owner, saved)); savings of 2^(8B) - 1 fill it exactly, and a
     * deposit onto them is accepted, while a deposit that carries them over to 2^(8B) (one byte more) is refused.
     */
    @Test
    void theSaturatedAccount_isTheLargestTheBoundAccepts() throws Exception {
        StateHarness<org.julclang.state.examples.dsl.Bank.State> h = StateHarness.of(org.julclang.state.examples.dsl.BankClient.SPEC);
        java.util.function.Function<BigInteger, Object> held = saved -> new org.julclang.state.examples.dsl.BankValidator.Holding(h.instanceId(),
                new org.julclang.state.examples.dsl.Bank.Account(ANN, saved));
        int bytes = 1;
        while (datumBytes(held.apply(BigInteger.ONE.shiftLeft(8 * (bytes + 1)).subtract(BigInteger.ONE))) <= 160) bytes++;
        BigInteger max = BigInteger.ONE.shiftLeft(8 * bytes).subtract(BigInteger.ONE);
        assertEquals(160, datumBytes(held.apply(max)), "savings of " + (8 * bytes) + " bits fill the datum exactly");
        assertEquals(161, datumBytes(held.apply(max.add(BigInteger.ONE))), "one more is one byte over");
        assertEquals(SATURATED_BITS, 8 * bytes, "the saturated rows' savings are the largest the bound accepts");
        BigInteger five = ADA.multiply(BigInteger.valueOf(5));
        Ctx ctx = h.ctx(ANN, five, ADA.multiply(BigInteger.TEN));
        for (BigInteger to : List.of(max, max.add(BigInteger.ONE))) {
            BigInteger from = to.subtract(five);
            var tx = h.actObject(h.liveObject(held.apply(from), ADA.multiply(BigInteger.TEN)), new org.julclang.state.examples.dsl.BankValidator.Deposit(ctx),
                    held.apply(to), JulcList.empty(), ANN, ctx, null);
            if (to.equals(max)) h.assertAccepted(tx);
            else h.assertRejected(tx, "a deposit making the object's datum 161 bytes");
        }
        System.out.println("[M3a1 K9] savings of " + (8 * bytes) + " bits: a 160-byte datum (accepted); one more: 161 bytes (refused)");
    }
}
