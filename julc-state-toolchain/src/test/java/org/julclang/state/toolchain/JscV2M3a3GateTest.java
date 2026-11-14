package org.julclang.state.toolchain;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.Ctx;
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
 * ADR-0018 M3a3's gates (J11, J19) for the guild ports (Guild, Armory) and the market port, against examples/guild and
 * examples/market: size (within +15% of the Java example, and 14 KB or the stated exception), encoding (the manifest
 * against the description, the stated renamings aside), modifiers per call (A9), and every call's budget, a move with
 * both its halves in one transaction (the destination another instance of the policy), within +15% of the Java
 * example's and the per-transaction maximum.
 */
class JscV2M3a3GateTest {

    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] MASTER = StateHarness.fill(28, 0xAD), ANN = StateHarness.fill(28, 0xA1), BEN = StateHarness.fill(28, 0xB2);
    static final byte[] POLICY = StateHarness.fill(28, 0x5E), NFT = "nft".getBytes();
    static final byte[] B = StateHarness.fill(32, 0xB2);
    static final TxOutRef B_REF = new TxOutRef(new TxId(StateHarness.fill(32, 0x0C)), BigInteger.ZERO);
    static final int RUNTIME_LIMIT = 15_000;
    static final Map<String, int[]> SIZES = new LinkedHashMap<>();
    static final List<String> ROWS = new ArrayList<>();

    @BeforeAll
    static void sizes() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!SIZES.isEmpty()) return;
        for (String[] c : new String[][]{{"guild", "Guild"}, {"guild", "Armory"}, {"market", "Market"}}) {
            String path = "org/julclang/state/examples/" + c[0] + "/" + c[1] + ".java";
            var java = Toolchain.build(Map.of(path, read("../examples/" + c[0] + "/src/main/java/" + path)), Map.of());
            var port = Toolchain.build(Map.of("org/julclang/state/examples/dsl/" + c[1] + ".jsc",
                    read(JscV2M1aFourWayTest.DSL.resolve(c[1] + ".jsc").toString())), Map.of());
            for (var r : List.of(java, port)) assertTrue(r.ok(), r.diagnostics().toString());
            SIZES.put(c[1], new int[]{size(java, c[1]), size(port, c[1])});
        }
    }

    @Test
    void size_within15PercentOfJava() {
        SIZES.forEach((c, s) -> {
            double ratio = (double) s[1] / s[0];
            System.out.printf("[M3a3 size] %-8s %,7d -> %,7d (%+.1f%%); J11's 14 KB: Java %s, port %s%n", c, s[0], s[1], 100 * (ratio - 1),
                    s[0] < SIZE_LIMIT ? "below" : "over", s[1] < SIZE_LIMIT ? "below" : "over");
            assertTrue(ratio <= BOUND, c + ": " + s[1] + " B is more than 15% above Java's " + s[0] + " B");
            assertTrue(s[1] <= RUNTIME_LIMIT, c + ": over the runtime's reference-script limit");
        });
        assertTrue(SIZES.get("Guild")[1] < SIZE_LIMIT && SIZES.get("Armory")[1] < SIZE_LIMIT, "the guild ports are below 14 KB");
    }

    @Test
    void encoding_equalsTheJavaExamples() throws Exception {
        // The stated differences, each a name: open is a modifier word (isOpen); a parameter never takes a state field's
        // name (D3: nowOpen, pause, newPrice); market's state record is State (Config is the params record's name).
        var stated = Map.of(
                "Guild", List.of("the datum: State.isOpen is named open in the description", "the spend redeemer: SetOpen.nowOpen is named open in the description"),
                "Armory", List.of("the datum: State.isOpen is named open in the description", "the spend redeemer: SetOpen.nowOpen is named open in the description"),
                "Market", List.of("the datum: org.julclang.state.examples.market.Market.State is not in the description",
                        "the spend redeemer: SetPaused.pause is named paused in the description",
                        "the spend redeemer: Relist.newPrice is named price in the description"));
        for (String[] c : new String[][]{{"guild", "Guild"}, {"guild", "Armory"}, {"market", "Market"}}) {
            String abi = java.nio.file.Files.readString(JscV2M1aFourWayTest.DSL.resolve(c[1] + ".abi.json"));
            String pkg = "org.julclang.state.examples." + c[0];
            var unrenamed = AbiCheck.compare(abi, description(pkg + "." + c[1]), pkg, c[1]);
            System.out.println("[M3a3 encoding] " + c[1] + ": as examples/" + c[0] + "'s but " + unrenamed);
            assertEquals(stated.get(c[1]), unrenamed, c[1] + ": exactly the stated differences");
        }
    }

    @Test
    void modifiers_perMethod_equalTheJavaExamples() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        int compared = 0;
        for (String[] c : new String[][]{{"guild", "Guild"}, {"guild", "Armory"}, {"market", "Market"}}) {
            var java = json.readTree(description("org.julclang.state.examples." + c[0] + "." + c[1])).get("x-joust");
            var port = json.readTree(description("org.julclang.state.examples.dsl." + c[1])).get("x-joust");
            for (String section : List.of("creations", "actions")) {
                var want = new LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
                java.get(section).forEach(x -> want.put(x.get("name").asText().equals("open") && section.equals("creations")
                        && c[1].equals("Market") ? "init" : x.get("name").asText(), x));
                assertEquals(want.size(), port.get(section).size(), c[1] + " " + section);
                for (var x : port.get(section)) {
                    String name = x.get("name").asText();
                    var w = want.get(name);
                    assertNotNull(w, c[1] + ": the Java example has no " + name);
                    for (String f : List.of("kind", "ctx", "payable", "takesAssets", "needsService", "policies", "returns")) {
                        assertEquals(String.valueOf(w.get(f)), String.valueOf(x.get(f)), c[1] + "." + name + "." + f);
                    }
                    for (String f : List.of("shared", "signers", "validAfter", "validBefore", "params", "reads", "stepEvents")) {
                        assertEquals(String.valueOf(w.get("recipe").get(f)), String.valueOf(x.get("recipe").get(f)), c[1] + "." + name + ".recipe." + f);
                    }
                    compared++;
                }
            }
        }
        System.out.println("[M3a3 modifiers] Guild, Armory and Market: " + compared + " calls, every modifier and policy as the Java examples'");
    }

    // ------------------------------------------------------------------ the calls

    /** The Java example's classes, or the port's; Market's state and init are Config and open in the Java example. */
    static final class Impl {
        final boolean java;
        final String pkg;
        final InMemoryNodeStore store = new InMemoryNodeStore();

        Impl(boolean java, String example) {
            this.java = java;
            this.pkg = java ? "org.julclang.state.examples." + example : "org.julclang.state.examples.dsl";
        }

        String n(String name) {
            return java ? name.replace("Market$State", "Market$Config") : name;
        }

        Class<?> cls(String name) throws Exception {
            return Class.forName(pkg + "." + n(name));
        }

        Object make(String name, Object... values) throws Exception {
            return cls(name).getDeclaredConstructors()[0].newInstance(values);
        }

        @SuppressWarnings("unchecked")
        <S> StateHarness<S> harness(String contract) throws Exception {
            return StateHarness.of((org.julclang.state.runtime.ContractSpec<S>) cls(contract + "Client").getField("SPEC").get(null));
        }

        /** A static method by name ({@code init} is Market's {@code open} in Java); an argument the method lacks (an admit's arriving object) is dropped. */
        Object invoke(MerkleSupport ms, String contract, String method, Object... args) throws Exception {
            String m = java && contract.equals("Market") && method.equals("init") ? "open" : method;
            for (var x : cls(contract).getDeclaredMethods()) {
                if (!x.getName().equals(m) || !Modifier.isStatic(x.getModifiers())) continue;
                Object[] a = args;
                if (x.getParameterCount() == args.length - 1) {
                    // the port's enlist reads no arriving object: drop it (the third argument)
                    var l = new ArrayList<>(List.of(args));
                    l.remove(2);
                    a = l.toArray();
                }
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
            throw new AssertionError("no method " + contract + "." + m);
        }
    }

    static Object part(Object r, String name) throws Exception {
        return r.getClass().getMethod(name).invoke(r);
    }

    @SuppressWarnings("unchecked")
    Run guild(Impl i, String call) throws Exception {
        StateHarness<Object> h = i.harness("Guild");
        var ms = new MerkleSupport(i.store);
        Object open = i.make("Guild$State", MASTER, true, BigInteger.ZERO, BigInteger.ONE);
        Ctx ann = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        Object hero2 = i.make("Guild$Hero", ANN, BigInteger.TWO), item5 = i.make("Guild$Item", ANN, BigInteger.valueOf(5));
        java.util.function.Function<Object, Object> heldHero = v -> uncheck(() -> i.make("GuildValidator$Holding_Hero", h.instanceId(), v));
        java.util.function.Function<Object, Object> heldItem = v -> uncheck(() -> i.make("GuildValidator$Holding_Item", h.instanceId(), v));
        switch (call) {
            case "init" -> {
                Ctx c = h.ctx(MASTER, BigInteger.ZERO, BigInteger.ZERO);
                Object st = i.invoke(ms, "Guild", "init", BigInteger.ONE, c);
                return run(StateHarness.data(st).toString(), h.evaluate(h.create(i.make("GuildValidator$CreateInstance", h.seed(), BigInteger.ONE, c), st, c)));
            }
            case "recruit" -> {
                Object made = i.invoke(ms, "Guild", "recruit", open, ann);
                return run(StateHarness.data(made).toString(), h.evaluate(h.createObject(i.make("GuildValidator$Recruit", h.seed(), h.instanceId(), ann),
                        heldHero.apply(made), open, ann)));
            }
            case "levelUp" -> {
                Object next = i.invoke(ms, "Guild", "levelUp", hero2);
                return run(StateHarness.data(next).toString(), h.evaluate(h.actObject(h.liveObject(heldHero.apply(hero2), BigInteger.ZERO),
                        i.make("GuildValidator$LevelUp"), heldHero.apply(next), JulcList.empty(), ANN, null, null)));
            }
            case "enchant" -> {
                Object next = i.invoke(ms, "Guild", "enchant", item5, BigInteger.TEN);
                return run(StateHarness.data(next).toString(), h.evaluate(h.actObject(h.liveObject(heldItem.apply(item5), BigInteger.ZERO),
                        i.make("GuildValidator$Enchant", BigInteger.TEN), heldItem.apply(next), JulcList.empty(), ANN, null, null)));
            }
            case "transfer" -> {
                // The move with its other half: guild B admits the hero in the same transaction.
                Object moved = i.invoke(ms, "Guild", "transfer", hero2, open, open, ann);
                Object admitted = i.invoke(ms, "Guild", "admit", open, h.objectId(), hero2, ann);
                var tx = h.actObject(h.liveObject(heldHero.apply(hero2), BigInteger.ZERO), i.make("GuildValidator$Transfer", B, ann),
                        i.make("GuildValidator$Holding_Hero", B, moved), JulcList.empty(), ANN, ann, open);
                tx = h.withInstance(tx, B, B_REF, open, i.make("GuildValidator$Admit", h.objectId(), ann), admitted, BigInteger.valueOf(h.minAda()));
                return run(StateHarness.data(moved) + " " + StateHarness.data(admitted), h.evaluate(tx));
            }
            default -> {
                Ctx c = h.ctx(ANN, BigInteger.ZERO, BigInteger.valueOf(h.minAda()));
                var pays = (JulcList<Payout>) i.invoke(ms, "Guild", "retire", hero2, c);
                return run(StateHarness.data(pays).toString(), h.evaluate(h.destroyObject(h.liveObject(heldHero.apply(hero2), BigInteger.ZERO),
                        i.make("GuildValidator$Retire", c), i.make("GuildValidator$BurnInstance"), pays, ANN, c)));
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Run armory(Impl i, String call) throws Exception {
        StateHarness<Object> h = i.harness("Armory");
        Class vault = i.cls("Armory$Vault");
        Object open = i.make("Armory$State", MASTER, true);
        Ctx ann = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        MerkleSupport ms = new MerkleSupport(i.store);
        Object made = i.invoke(ms, "Armory", "openVault", open, ann);
        var created = ms.prepareCreation(h.policy(), vault, made, h.seed());
        if (call.equals("openVault")) {
            return run(StateHarness.data(created.stored()).toString(), h.evaluate(h.withClaims(h.createObject(i.make("ArmoryValidator$OpenVault", h.seed(),
                    h.instanceId(), ann), i.make("ArmoryValidator$Holding", h.instanceId(), created.stored()), open, ann), created.claims())));
        }
        // A vault holding 3 swords.
        ms = new MerkleSupport(i.store);
        Object v0 = created.stored();
        Object r = i.invoke(ms, "Armory", "stash", v0, "sword".getBytes(), BigInteger.valueOf(3));
        Object v = ms.prepare(h.policy(), vault, v0, r, StateHarness.OBJECT_REF).stored();
        ms = new MerkleSupport(i.store);
        Object next = i.invoke(ms, "Armory", call, v, "sword".getBytes(), BigInteger.ONE);
        var p = ms.prepare(h.policy(), vault, v, next, StateHarness.OBJECT_REF);
        var tx = h.actObject(h.liveObject(i.make("ArmoryValidator$Holding", h.instanceId(), v), BigInteger.ZERO),
                i.make("ArmoryValidator$" + Character.toUpperCase(call.charAt(0)) + call.substring(1), "sword".getBytes(), BigInteger.ONE),
                i.make("ArmoryValidator$Holding", h.instanceId(), p.stored()), JulcList.empty(), ANN, null, null);
        return run(StateHarness.data(p.stored()).toString(), h.evaluate(h.withClaims(tx, p.claims())));
    }

    @SuppressWarnings("unchecked")
    Run market(Impl i, String call) throws Exception {
        StateHarness<Object> h = i.harness("Market");
        var ms = new MerkleSupport(i.store);
        Address vault = new Address(new Credential.PubKeyCredential(new PubKeyHash(StateHarness.fill(28, 0x77))), Optional.empty());
        Object open = i.make("Market$State", MASTER, BigInteger.valueOf(200), vault, BigInteger.valueOf(300), false);
        Value nft = Assets.of(POLICY, NFT, BigInteger.ONE);
        BigInteger price = ADA.multiply(BigInteger.valueOf(5));
        Object listing = i.make("Market$Listing", ANN, POLICY, NFT, price);
        java.util.function.Function<Object, Object> held = l -> uncheck(() -> i.make("MarketValidator$Holding", h.instanceId(), l));
        switch (call) {
            case "offer" -> {
                Ctx c = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
                Object r = i.invoke(ms, "Market", "offer", open, POLICY, NFT, price, c, h.createFunds(true, nft));
                Object made = part(r, "state");
                return run(StateHarness.data(made).toString(), h.evaluate(h.createWith(i.make("MarketValidator$Offer", h.seed(), h.instanceId(), POLICY, NFT,
                        price, c, nft.toPlutusData()), held.apply(made), c, nft, true, open)));
            }
            case "buy" -> {
                var live = h.heldObject(held.apply(listing), BigInteger.ZERO, nft);
                Ctx c = h.ctx(BEN, price, live.lovelace());
                Object r = i.invoke(ms, "Market", "buy", listing, open, c, h.funds(live, Assets.none()));
                var t = Transition.full(null, (JulcList<Payout>) part(r, "payouts"), (JulcList<Send>) part(r, "sends"), null, (Value) part(r, "accepted"));
                // The payouts and sends as sets: the port makes them in source order (the seller, then the admin; the NFT,
                // then the royalty), examples/market's Payouts.add and Sends.add put the later one first; the validator
                // checks each, whatever their order.
                var pays = new java.util.TreeSet<String>();
                for (Payout x : (JulcList<Payout>) part(r, "payouts")) pays.add(StateHarness.data(x).toString());
                var sends = new java.util.TreeSet<String>();
                for (Send x : (JulcList<Send>) part(r, "sends")) sends.add(StateHarness.data(x).toString());
                return run(pays + " " + sends,
                        h.evaluate(h.stepWith(live, i.make("MarketValidator$Buy", c, Assets.none().toPlutusData()), i.make("MarketValidator$BurnInstance"), t,
                                null, BEN, c, open)));
            }
            case "relist" -> {
                var live = h.heldObject(held.apply(listing), BigInteger.ZERO, nft);
                Ctx c = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
                BigInteger newPrice = price.add(price);
                Object moved = i.invoke(ms, "Market", "relist", listing, open, newPrice, c);
                Object admitted = i.invoke(ms, "Market", "enlist", open, h.objectId(), listing, c);
                var tx = h.stepWith(live, i.make("MarketValidator$Relist", B, newPrice, c), null, Transition.full(moved, null, null, null, null),
                        i.make("MarketValidator$Holding", B, moved), ANN, c, null);
                tx = h.withInstance(tx, B, B_REF, open, i.make("MarketValidator$Enlist", h.objectId(), c), admitted, BigInteger.valueOf(h.minAda()));
                return run(StateHarness.data(moved) + " " + StateHarness.data(admitted), h.evaluate(tx));
            }
            default -> {
                var live = h.heldObject(held.apply(listing), BigInteger.ZERO, nft);
                Ctx c = h.ctx(ANN, BigInteger.ZERO, live.lovelace());
                Object r = i.invoke(ms, "Market", "cancel", listing, c, h.funds(live, Assets.none()));
                var t = Transition.full(null, (JulcList<Payout>) part(r, "payouts"), (JulcList<Send>) part(r, "sends"), null, null);
                return run(StateHarness.data(part(r, "payouts")) + " " + StateHarness.data(part(r, "sends")),
                        h.evaluate(h.stepWith(live, i.make("MarketValidator$Cancel", c), i.make("MarketValidator$BurnInstance"), t, null, ANN, c, null)));
            }
        }
    }

    interface Unchecked {
        Object get() throws Exception;
    }

    static Object uncheck(Unchecked u) {
        try {
            return u.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    interface Call {
        Run run(Impl i) throws Exception;
    }

    void compare(String example, String contract, String label, Call call) throws Exception {
        Run j = call.run(new Impl(true, example)), p = call.run(new Impl(false, example));
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
        for (String c : List.of("init", "recruit", "levelUp", "enchant", "transfer", "retire")) compare("guild", "Guild", c, i -> guild(i, c));
        for (String c : List.of("openVault", "stash", "take")) compare("guild", "Armory", c, i -> armory(i, c));
        for (String c : List.of("offer", "buy", "relist", "cancel")) compare("market", "Market", c, i -> market(i, c));
        System.out.println("[M3a3 budget] | Contract | Call | Java CPU | .jsc 2 CPU | Δ CPU | Java memory | .jsc 2 memory (Δ) | % of max (CPU / mem) |");
        ROWS.forEach(r -> System.out.println("[M3a3 budget] " + r));
    }
}
