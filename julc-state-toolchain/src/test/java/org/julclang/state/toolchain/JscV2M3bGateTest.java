package org.julclang.state.toolchain;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.dsl.v2.AbiCheck;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
 * ADR-0018 M3b's gates (J11, J19) for the registry port, against examples/registry: size (within +15% of the Java
 * example, and under the runtime's 15,000-byte reference-script limit; J11's 14 KB is over for both, as for the locker
 * ports: a stated exception), encoding (the manifest against the description, the stated renamings aside, the keyed
 * section included), modifiers per call (A9), and every call's budget as the runtime builds it (an insert spending its
 * anchor with Link, a keyed action, a retire, a removal spending its predecessor), saturated at the key bounds: a 64-byte
 * name after an anchor whose next is a 64-byte key, an entry whose datum fills maxDatumBytes = 384 with its envelope.
 */
class JscV2M3bGateTest {

    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] ADMIN = StateHarness.fill(28, 0xAD), ANN = StateHarness.fill(28, 0xA1), BOB = StateHarness.fill(28, 0xB0);
    static final int RUNTIME_LIMIT = 15_000;
    static final TxOutRef ANCHOR = new TxOutRef(new TxId(StateHarness.fill(32, 0x0E)), BigInteger.ZERO);
    static final BigInteger T0 = StateHarness.T0, LINKED = StateHarness.T0.subtract(BigInteger.valueOf(60_000));
    static final Map<String, int[]> SIZES = new LinkedHashMap<>();
    static final List<String> ROWS = new ArrayList<>();

    @BeforeAll
    static void sizes() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!SIZES.isEmpty()) return;
        String path = "org/julclang/state/examples/registry/Registry.java";
        var java = Toolchain.build(Map.of(path, read("../examples/registry/src/main/java/" + path)), Map.of());
        var port = Toolchain.build(Map.of("org/julclang/state/examples/dsl/Registry.jsc", read(JscV2M1aFourWayTest.DSL.resolve("Registry.jsc").toString())), Map.of());
        for (var r : List.of(java, port)) assertTrue(r.ok(), r.diagnostics().toString());
        SIZES.put("Registry", new int[]{size(java, "Registry"), size(port, "Registry")});
        var keys = Toolchain.build(Map.of("org/julclang/state/examples/dsl/Keys.jsc", read(JscV2M1aFourWayTest.DSL.resolve("Keys.jsc").toString())), Map.of());
        assertTrue(keys.ok(), keys.diagnostics().toString());
        SIZES.put("Keys", new int[]{0, size(keys, "Keys")});
    }

    @Test
    void size_within15PercentOfJava_andUnderTheRuntimesLimit() {
        int[] s = SIZES.get("Registry");
        double ratio = (double) s[1] / s[0];
        System.out.printf("[M3b size] Registry %,7d -> %,7d (%+.1f%%); J11's 14 KB: Java %s, port %s; the runtime's 15,000 B: %,d B spare%n", s[0], s[1],
                100 * (ratio - 1), s[0] < SIZE_LIMIT ? "below" : "over", s[1] < SIZE_LIMIT ? "below" : "over", RUNTIME_LIMIT - s[1]);
        System.out.printf("[M3b size] Keys (coverage) %,7d%n", SIZES.get("Keys")[1]);
        assertTrue(ratio <= BOUND, "Registry: " + s[1] + " B is more than 15% above Java's " + s[0] + " B");
        assertTrue(s[1] <= RUNTIME_LIMIT, "Registry: over the runtime's reference-script limit");
        assertTrue(SIZES.get("Keys")[1] <= RUNTIME_LIMIT, "Keys: over the runtime's reference-script limit");
    }

    @Test
    void encoding_equalsTheJavaExample() throws Exception {
        // The stated differences, each a name or withdraw's Ctx: open is a modifier word (isOpen); a parameter never takes
        // an object's field name (D3: nowOpen, pick, newTier); withdraw reads nothing of the transaction (no ctx).
        var stated = List.of("the datum: State.isOpen is named open in the description",
                "the spend redeemer: SetOpen.nowOpen is named open in the description",
                "the spend redeemer: Change.pick is named choice in the description",
                "the spend redeemer: Upgrade.newTier is named tier in the description",
                "the spend redeemer: Withdraw has 1 fields here, 2 described");
        String abi = java.nio.file.Files.readString(JscV2M1aFourWayTest.DSL.resolve("Registry.abi.json"));
        var unrenamed = AbiCheck.compare(abi, description("org.julclang.state.examples.registry.Registry"), "org.julclang.state.examples.registry", "Registry");
        System.out.println("[M3b encoding] Registry: as examples/registry's but " + unrenamed);
        assertEquals(stated, unrenamed, "exactly the stated differences (the keyed section, the list's datums and the inserts included)");
    }

    @Test
    void modifiers_perMethod_equalTheJavaExample() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var java = json.readTree(description("org.julclang.state.examples.registry.Registry")).get("x-joust");
        var port = json.readTree(description("org.julclang.state.examples.dsl.Registry")).get("x-joust");
        int compared = 0;
        for (String section : List.of("creations", "actions")) {
            var want = new LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            java.get(section).forEach(x -> want.put(x.get("name").asText(), x));
            assertEquals(want.size(), port.get(section).size(), section);
            for (var x : port.get(section)) {
                String name = x.get("name").asText();
                var w = want.get(name);
                assertNotNull(w, "the Java example has no " + name);
                for (String f : List.of("kind", "payable", "takesAssets", "needsService", "policies", "returns")) {
                    assertEquals(String.valueOf(w.get(f)), String.valueOf(x.get(f)), name + "." + f);
                }
                // withdraw's Ctx is the stated difference (the port's reads none).
                if (!name.equals("withdraw")) assertEquals(String.valueOf(w.get("ctx")), String.valueOf(x.get("ctx")), name + ".ctx");
                for (String f : List.of("shared", "signers", "validAfter", "validBefore", "params", "reads", "stepEvents")) {
                    assertEquals(String.valueOf(w.get("recipe").get(f)), String.valueOf(x.get("recipe").get(f)), name + ".recipe." + f);
                }
                compared++;
            }
        }
        // The keyed section, field by field, but the type names' package.
        for (int i = 0; i < 2; i++) {
            var jk = java.get("keyed").get(i);
            var pk = port.get("keyed").get(i);
            for (String f : List.of("simple", "key", "keyIndex", "maxKeyBytes", "signedKey", "linkCooldownMs", "linkWindowMs", "typeId", "headTag",
                    "nodeTag", "retiredTag", "link", "remove", "inserts", "actions", "removes", "retires")) {
                assertEquals(String.valueOf(jk.get(f)), String.valueOf(pk.get(f)), "keyed " + i + "." + f);
            }
        }
        System.out.println("[M3b modifiers] Registry: " + compared + " calls, every modifier and policy as the Java example's; the keyed section alike");
    }

    // ------------------------------------------------------------------ the calls, as the runtime builds them

    /** The Java example's classes, or the port's. */
    static final class Impl {
        final boolean java;
        final String pkg;
        final StateHarness<Object> h;

        @SuppressWarnings("unchecked")
        Impl(boolean java) throws Exception {
            this.java = java;
            this.pkg = java ? "org.julclang.state.examples.registry" : "org.julclang.state.examples.dsl";
            this.h = StateHarness.of((org.julclang.state.runtime.ContractSpec<Object>) cls("RegistryClient").getField("SPEC").get(null));
        }

        Class<?> cls(String name) throws Exception {
            return Class.forName(pkg + "." + name);
        }

        /** A record by name, its arguments trimmed to its arity (the port's Withdraw has no ctx). */
        Object make(String name, Object... values) throws Exception {
            var c = cls(name).getDeclaredConstructors()[0];
            Object[] a = Arrays.copyOf(values, c.getParameterCount());
            return c.newInstance(a);
        }

        /** A static method of Registry by name; an argument the method lacks (a trailing Ctx) is dropped. */
        Object invoke(String method, Object... args) throws Exception {
            for (var x : cls("Registry").getDeclaredMethods()) {
                if (!x.getName().equals(method) || !Modifier.isStatic(x.getModifiers())) continue;
                return x.invoke(null, Arrays.copyOf(args, x.getParameterCount()));
            }
            throw new AssertionError("no method " + method);
        }

        BigInteger minAda() {
            return BigInteger.valueOf(h.minAda());
        }

        byte[] keyId(String type, byte[] key) {
            return FourWay.keyId(h.instanceId(), type, key);
        }

        TxOut node(Object datum, BigInteger lovelace, byte[] token) {
            return new TxOut(h.stateAddress(), Value.lovelace(lovelace).merge(Value.singleton(new PolicyId(h.policy()), new TokenName(token), BigInteger.ONE)),
                    new OutputDatum.OutputDatumInline(StateHarness.data(datum)), Optional.empty());
        }

        void wallet(TxModel tx, byte[] signer) {
            tx.inputs.add(new TxInInfo(new TxOutRef(new TxId(StateHarness.fill(32, 0x33)), BigInteger.ZERO),
                    StateHarness.walletOut(signer, BigInteger.valueOf(50_000_000))));
            tx.signers.add(signer);
        }

        /** An insert of {@code value} (key {@code key}) after {@code anchor} (its datum and token), with {@code redeemer}. */
        TxModel insert(String type, Object anchorDatum, byte[] anchorToken, byte[] anchorNext, Object value, byte[] key, Object redeemer, Ctx c,
                       byte[] signer, Object shared) throws Exception {
            var tx = new TxModel(TxModel.Kind.ACTION);
            byte[] id = keyId(type, key);
            tx.token = id;
            var a = node(anchorDatum, minAda(), anchorToken);
            tx.inputs.add(new TxInInfo(ANCHOR, a));
            tx.spendRedeemers.put(ANCHOR, StateHarness.data(make("RegistryValidator$Link")));
            wallet(tx, signer);
            if (shared != null) tx.referenceInputs.add(h.instanceReference(shared));
            tx.outputs.add(new TxOut(a.address(), a.value(), new OutputDatum.OutputDatumInline(FourWay.relinkedDatum(
                    ((OutputDatum.OutputDatumInline) a.datum()).datum(), key, c.validTo())), Optional.empty()));
            tx.outputs.add(node(make("RegistryValidator$Keyed_" + type, h.instanceId(), anchorNext, c.validTo(), value), minAda().add(c.received()), id));
            tx.stateOutput = 1;
            tx.mint = Value.singleton(new PolicyId(h.policy()), new TokenName(id), BigInteger.ONE);
            tx.mintRedeemer = StateHarness.data(redeemer);
            tx.range = StateHarness.window(c.validFrom(), c.validTo());
            return tx;
        }

        /** A keyed action on {@code before} (key {@code key}) continuing {@code after}, paying {@code pays}. */
        TxModel act(String type, Object before, Object after, byte[] key, Object redeemer, BigInteger funds, BigInteger received, JulcList<Payout> pays,
                     byte[] signer, Ctx c) throws Exception {
            var tx = new TxModel(TxModel.Kind.ACTION);
            byte[] id = keyId(type, key);
            tx.token = id;
            BigInteger in = minAda().add(funds);
            tx.inputs.add(new TxInInfo(StateHarness.OBJECT_REF, node(make("RegistryValidator$Keyed_" + type, h.instanceId(), new byte[0], LINKED, before), in, id)));
            tx.spendRedeemers.put(StateHarness.OBJECT_REF, StateHarness.data(redeemer));
            wallet(tx, signer);
            tx.outputs.add(node(make("RegistryValidator$Keyed_" + type, h.instanceId(), new byte[0], LINKED, after), in.add(received).subtract(StateHarness.total(pays)), id));
            tx.stateOutput = 0;
            FourWay.payoutsFor(tx, StateHarness.OBJECT_REF, pays);
            if (c != null) tx.range = StateHarness.window(c.validFrom(), c.validTo());
            return tx;
        }

        TxModel retire(String type, Object before, byte[] key, Object redeemer, BigInteger funds, JulcList<Payout> pays, byte[] signer, Ctx c) throws Exception {
            var tx = new TxModel(TxModel.Kind.ACTION);
            byte[] id = keyId(type, key);
            tx.token = id;
            tx.inputs.add(new TxInInfo(StateHarness.OBJECT_REF, node(make("RegistryValidator$Keyed_" + type, h.instanceId(), new byte[0], LINKED, before),
                    minAda().add(funds), id)));
            tx.spendRedeemers.put(StateHarness.OBJECT_REF, StateHarness.data(redeemer));
            wallet(tx, signer);
            tx.outputs.add(node(make("RegistryValidator$Retired_" + type, h.instanceId(), new byte[0], LINKED, key), minAda(), id));
            tx.stateOutput = 0;
            FourWay.payoutsFor(tx, StateHarness.OBJECT_REF, pays);
            tx.range = StateHarness.window(c.validFrom(), c.validTo());
            return tx;
        }

        TxModel remove(String type, Object before, byte[] key, Object predecessor, byte[] predecessorToken, Object redeemer, BigInteger funds,
                       JulcList<Payout> pays, byte[] signer, Ctx c) throws Exception {
            var tx = new TxModel(TxModel.Kind.DESTROY);
            byte[] id = keyId(type, key);
            tx.token = id;
            tx.inputs.add(new TxInInfo(StateHarness.OBJECT_REF, node(make("RegistryValidator$Keyed_" + type, h.instanceId(), new byte[0], LINKED, before),
                    minAda().add(funds), id)));
            tx.spendRedeemers.put(StateHarness.OBJECT_REF, StateHarness.data(redeemer));
            var p = node(predecessor, minAda(), predecessorToken);
            tx.inputs.add(new TxInInfo(ANCHOR, p));
            tx.spendRedeemers.put(ANCHOR, StateHarness.data(make("RegistryValidator$Link")));
            wallet(tx, signer);
            tx.outputs.add(new TxOut(p.address(), p.value(), new OutputDatum.OutputDatumInline(FourWay.relinkedDatum(
                    ((OutputDatum.OutputDatumInline) p.datum()).datum(), new byte[0], c.validTo())), Optional.empty()));
            FourWay.payoutsFor(tx, StateHarness.OBJECT_REF, pays);
            tx.mint = Value.singleton(new PolicyId(h.policy()), new TokenName(id), BigInteger.ONE.negate());
            tx.mintRedeemer = StateHarness.data(make("RegistryValidator$Remove_" + type, ANCHOR, StateHarness.OBJECT_REF));
            tx.range = StateHarness.window(c.validFrom(), c.validTo());
            return tx;
        }
    }

    static Ctx ctx(byte[] caller, BigInteger received, BigInteger balance) {
        return new Ctx(caller, received, balance, T0, T0.add(BigInteger.valueOf(120_000)));
    }

    static JulcList<byte[]> keys(int n) {
        JulcList<byte[]> out = JulcList.empty();
        for (int i = n; i >= 1; i--) out = out.prepend(StateHarness.fill(28, 0x10 + i));
        return out;
    }

    static byte[] name(int n, int b) {
        byte[] out = new byte[n];
        java.util.Arrays.fill(out, (byte) b);
        return out;
    }

    /** The guardians a saturated entry holds: as many as keep its envelope within maxDatumBytes = 384 (found below). */
    static int saturated = -1;

    @SuppressWarnings("unchecked")
    Run registry(Impl i, String call) throws Exception {
        var h = i.h;
        byte[] inst = h.instanceId();
        Object open = i.make("Registry$State", ADMIN, true);
        byte[] alice = "alice".getBytes(StandardCharsets.UTF_8);
        Object entry = i.make("Registry$Entry", alice, ANN, ADA.multiply(BigInteger.valueOf(5)), BigInteger.ONE, keys(2), BigInteger.ONE);
        Object ballot = i.make("Registry$Ballot", ANN, BigInteger.valueOf(3));
        Object ballotHead = i.make("RegistryValidator$Head_Ballot", inst, new byte[0], LINKED);
        switch (call) {
            case "init" -> {
                Ctx c = ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO);
                Object st = i.invoke("init", c);
                var tx = h.create(i.make("RegistryValidator$CreateInstance", h.seed(), c), st, c);
                for (String t : List.of("Ballot", "Entry")) {
                    byte[] id = i.keyId(t, new byte[0]);
                    tx.outputs.add(i.node(i.make("RegistryValidator$Head_" + t, inst, new byte[0], BigInteger.ZERO), i.minAda(), id));
                    tx.mint = tx.mint.merge(Value.singleton(new PolicyId(h.policy()), new TokenName(id), BigInteger.ONE));
                }
                return run(StateHarness.data(st).toString(), h.evaluate(tx));
            }
            case "cast" -> {
                Ctx c = ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
                Object v = i.invoke("cast", open, ANN, BigInteger.valueOf(3), c);
                return run(StateHarness.data(v).toString(), h.evaluate(i.insert("Ballot", ballotHead, i.keyId("Ballot", new byte[0]), new byte[0], v, ANN,
                        i.make("RegistryValidator$Insert_cast", inst, ANCHOR, ANN, BigInteger.valueOf(3), c), c, ANN, open)));
            }
            case "claim", "claim (saturated)" -> {
                // Saturated: a 64-byte name after a head whose next is a 64-byte key, and the most guardians that fit.
                boolean sat = call.endsWith("(saturated)");
                byte[] n = sat ? name(64, 0x62) : alice;
                byte[] next = sat ? name(64, 0x7a) : new byte[0];
                JulcList<byte[]> g = keys(sat ? saturated : 1);
                Ctx c = ctx(ANN, ADA.multiply(BigInteger.TWO), BigInteger.ZERO);
                Object v = i.invoke("claim", n, BigInteger.ONE, g, BigInteger.ONE, c);
                Object head = i.make("RegistryValidator$Head_Entry", inst, next, LINKED);
                return run(StateHarness.data(v).toString(), h.evaluate(i.insert("Entry", head, i.keyId("Entry", new byte[0]), next, v, n,
                        i.make("RegistryValidator$Insert_claim", inst, ANCHOR, n, BigInteger.ONE, g, BigInteger.ONE, c), c, ANN, null)));
            }
            case "grant" -> {
                Ctx c = ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO);
                Object v = i.invoke("grant", open, alice, BOB, c);
                Object head = i.make("RegistryValidator$Head_Entry", inst, new byte[0], LINKED);
                return run(StateHarness.data(v).toString(), h.evaluate(i.insert("Entry", head, i.keyId("Entry", new byte[0]), new byte[0], v, alice,
                        i.make("RegistryValidator$Insert_grant", inst, ANCHOR, alice, BOB, c), c, ADMIN, open)));
            }
            case "change" -> {
                Object next = i.invoke("change", ballot, BigInteger.valueOf(7));
                return run(StateHarness.data(next).toString(), h.evaluate(i.act("Ballot", ballot, next, ANN, i.make("RegistryValidator$Change",
                        BigInteger.valueOf(7)), BigInteger.ZERO, BigInteger.ZERO, JulcList.empty(), ANN, null)));
            }
            case "upgrade" -> {
                Ctx c = ctx(ANN, ADA, ADA.multiply(BigInteger.valueOf(5)));
                Object next = i.invoke("upgrade", entry, BigInteger.TWO, c);
                return run(StateHarness.data(next).toString(), h.evaluate(i.act("Entry", entry, next, alice, i.make("RegistryValidator$Upgrade", BigInteger.TWO, c),
                        ADA.multiply(BigInteger.valueOf(5)), ADA, JulcList.empty(), ANN, c)));
            }
            case "withdraw" -> {
                Ctx c = ctx(ANN, BigInteger.ZERO, ADA.multiply(BigInteger.valueOf(5)));
                Object r = i.invoke("withdraw", entry, ADA.multiply(BigInteger.TWO), c);
                Object next = r.getClass().getMethod("state").invoke(r);
                var pays = (JulcList<Payout>) r.getClass().getMethod("payouts").invoke(r);
                return run(StateHarness.data(next) + " " + StateHarness.data(pays), h.evaluate(i.act("Entry", entry, next, alice,
                        i.make("RegistryValidator$Withdraw", ADA.multiply(BigInteger.TWO), c), ADA.multiply(BigInteger.valueOf(5)), BigInteger.ZERO, pays, ANN,
                        i.java ? c : null)));
            }
            case "recover" -> {
                Object next = i.invoke("recover", entry, BOB);
                var tx = i.act("Entry", entry, next, alice, i.make("RegistryValidator$Recover", BOB), ADA.multiply(BigInteger.valueOf(5)), BigInteger.ZERO,
                        JulcList.empty(), StateHarness.fill(28, 0x11), null);
                return run(StateHarness.data(next).toString(), h.evaluate(tx));
            }
            case "leave" -> {
                Ctx c = ctx(ANN, BigInteger.ZERO, ADA);
                var pays = (JulcList<Payout>) i.invoke("leave", ballot, c);
                return run(StateHarness.data(pays).toString(), h.evaluate(i.retire("Ballot", ballot, ANN, i.make("RegistryValidator$Leave", c), ADA, pays, ANN, c)));
            }
            case "retire" -> {
                Ctx c = ctx(ANN, BigInteger.ZERO, ADA.multiply(BigInteger.valueOf(5)));
                var pays = (JulcList<Payout>) i.invoke("retire", entry, c);
                return run(StateHarness.data(pays).toString(), h.evaluate(i.retire("Entry", entry, alice, i.make("RegistryValidator$Retire", c),
                        ADA.multiply(BigInteger.valueOf(5)), pays, ANN, c)));
            }
            default -> {   // release: the entry removed, its predecessor (the head) relinked past it
                BigInteger all = i.minAda().add(ADA.multiply(BigInteger.valueOf(5)));
                Ctx c = ctx(ANN, BigInteger.ZERO, all);
                var pays = (JulcList<Payout>) i.invoke("release", entry, c);
                Object head = i.make("RegistryValidator$Head_Entry", inst, alice, LINKED);
                return run(StateHarness.data(pays).toString(), h.evaluate(i.remove("Entry", entry, alice, head, i.keyId("Entry", new byte[0]),
                        i.make("RegistryValidator$Release", c), ADA.multiply(BigInteger.valueOf(5)), pays, ANN, c)));
            }
        }
    }

    void compare(String label) throws Exception {
        Run j = registry(new Impl(true), label), p = registry(new Impl(false), label);
        assertTrue(j.accepted(), "Registry " + label + " (Java):\n" + j.verdict());
        assertTrue(p.accepted(), "Registry " + label + " (.jsc 2):\n" + p.verdict());
        assertEquals(j.result(), p.result(), "Registry " + label + ": the port's JVM result differs from the Java example's");
        double cpu = (double) p.cpu() / j.cpu(), mem = (double) p.mem() / j.mem();
        String row = String.format("| %s | %,d | %,d | %+.1f%% | %,d | %,d (%+.1f%%) | %.2f%% / %.2f%% |", label, j.cpu(), p.cpu(),
                100 * (cpu - 1), j.mem(), p.mem(), 100 * (mem - 1), 100.0 * p.cpu() / MAX_CPU, 100.0 * p.mem() / MAX_MEM);
        ROWS.add(row);
        assertTrue(cpu <= BOUND, label + ": CPU " + p.cpu() + " is more than 15% above Java's " + j.cpu());
        assertTrue(mem <= BOUND, label + ": memory " + p.mem() + " is more than 15% above Java's " + j.mem());
        assertTrue(p.cpu() <= MAX_CPU && p.mem() <= MAX_MEM, label + ": over the per-transaction maximum");
    }

    @Test
    void budgets_saturatedAtTheKeyBounds() throws Exception {
        // The saturated entry: the most guardians whose envelope (next padded to 64 bytes, linkedAt 9 bytes) fits 384 bytes.
        for (int g = 1; g <= 12; g++) {
            Object v = new Impl(false).make("Registry$Entry", name(64, 0x62), ANN, ADA.multiply(BigInteger.TWO), BigInteger.ONE, keys(g), BigInteger.ONE);
            PlutusData env = new PlutusData.ConstrData(4, List.of(PlutusData.bytes(StateHarness.fill(32, 1)), PlutusData.bytes(new byte[64]),
                    PlutusData.integer(BigInteger.TWO.pow(63)), StateHarness.data(v)));
            if (org.julclang.core.cbor.PlutusDataCborEncoder.encode(env).length <= 384) saturated = g;
        }
        assertTrue(saturated >= 1, "an entry with one guardian fits");
        System.out.println("[M3b budget] saturated: a 64-byte name, " + saturated + " guardians, after a 64-byte next");
        for (String c : List.of("init", "cast", "claim", "claim (saturated)", "grant", "change", "upgrade", "withdraw", "recover", "leave", "retire", "release")) {
            compare(c);
        }
        System.out.println("[M3b budget] | Call | Java CPU | .jsc 2 CPU | Δ CPU | Java memory | .jsc 2 memory (Δ) | % of max (CPU / mem) |");
        ROWS.forEach(r -> System.out.println("[M3b budget] " + r));
    }
}
