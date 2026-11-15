package org.julclang.state.toolchain;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Payouts;
import org.julclang.state.dsl.v2.JscV2;
import org.julclang.state.runtime.ContractSpec;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #112 review C2: the keyed refusals the five-way asserts by a require are the validator's requires, not a side effect of
 * the transaction. Each transaction proposes what the body would make without its require, as {@code FourWay.unguarded}
 * (or an insert's candidate) does in {@code JscV2M3bFourWayTest}: the port's validator refuses it, and a validator built
 * from the same lowering with only that require removed accepts it, so the five-way vector fails when the require is gone.
 */
class JscV2M3bGuardMutationTest {

    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] ANN = StateHarness.fill(28, 0xA1);

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static ContractSpec<Object> withScript(ContractSpec<?> spec, String cborHex) throws Exception {
        var comps = ContractSpec.class.getRecordComponents();
        var types = new Class<?>[comps.length];
        var vals = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            vals[i] = comps[i].getName().equals("scriptCborHex") ? cborHex : comps[i].getAccessor().invoke(spec);
        }
        return (ContractSpec<Object>) ContractSpec.class.getDeclaredConstructor(types).newInstance(vals);
    }

    /** The port's validator with the require whose message is {@code message} removed from {@code method}. */
    static StateHarness<Object> without(String message) throws Exception {
        String java = JscV2.lower(Files.readString(JscV2M1aFourWayTest.DSL.resolve("Registry.jsc")), "Registry.jsc").java();
        // every require with that message (cast's and change's share one)
        String mutated = String.join("\n", java.lines().filter(l -> !(l.contains("Require.that(") && l.contains("\"" + message + "\""))).toList()) + "\n";
        assertNotEquals(java, mutated);
        var built = Toolchain.build(Map.of("org/julclang/state/examples/dsl/Registry.java", mutated), Map.of());
        assertTrue(built.ok(), built.diagnostics().toString());
        return StateHarness.of(withScript(org.julclang.state.examples.dsl.RegistryClient.SPEC, built.contracts().get(0).scriptCborHex()));
    }

    interface Unguarded {
        TxModel build(JscV2M3bGateTest.Impl i) throws Exception;
    }

    static void failsWithoutItsGuard(String label, String message, Unguarded tx) throws Exception {
        var real = new JscV2M3bGateTest.Impl(false);
        var r = real.h.evaluate(tx.build(real));
        assertFalse(r.accepted(), label + ": the port refuses it\n" + r);
        assertTrue(r.toString().contains(message), label + ": refused by the require\n" + r);
        var mutant = new JscV2M3bGateTest.Impl(false, without(message));
        var v = mutant.h.evaluate(tx.build(mutant));
        System.out.println("[M3b C2] " + label + " without its require: " + (v.accepted() ? "accepted" : "refused\n" + v));
        assertTrue(v.accepted(), label + ": without the require the unguarded outcome is accepted, so the vector tests it: " + v);
    }

    static Ctx ctx(BigInteger received, BigInteger balance) {
        return new Ctx(ANN, received, balance, StateHarness.T0, StateHarness.T0.add(BigInteger.valueOf(120_000)));
    }

    @Test
    void change_toTen() throws Exception {
        failsWithoutItsGuard("change(10)", "choose 0 to 9", i -> {
            Object b = i.make("Registry$Ballot", ANN, BigInteger.valueOf(3)), next = i.make("Registry$Ballot", ANN, BigInteger.TEN);
            return i.act("Ballot", b, next, ANN, i.make("RegistryValidator$Change", BigInteger.TEN), BigInteger.ZERO, BigInteger.ZERO, JulcList.empty(), ANN, null);
        });
    }

    @Test
    void withdraw_overTheStake() throws Exception {
        byte[] name = "alice".getBytes();
        failsWithoutItsGuard("withdraw(6 ADA) of a 5 ADA stake", "withdraw within the stake", i -> {
            JulcList<byte[]> g = JulcList.<byte[]>empty().prepend(ANN);
            Object e = i.make("Registry$Entry", name, ANN, ADA.multiply(BigInteger.valueOf(5)), BigInteger.ONE, g, BigInteger.ONE);
            Object next = i.make("Registry$Entry", name, ANN, ADA.negate(), BigInteger.ONE, g, BigInteger.ONE);
            JulcList<Payout> pays = Payouts.of(ANN, ADA.multiply(BigInteger.valueOf(6)));
            return i.act("Entry", e, next, name, i.make("RegistryValidator$Withdraw", ADA.multiply(BigInteger.valueOf(6))), ADA.multiply(BigInteger.valueOf(8)),
                    BigInteger.ZERO, pays, ANN, null);
        });
    }

    @Test
    void cast_ofTen() throws Exception {
        failsWithoutItsGuard("cast(ANN, 10)", "choose 0 to 9", i -> {
            Ctx c = ctx(BigInteger.ZERO, BigInteger.ZERO);
            Object open = i.make("Registry$State", StateHarness.fill(28, 0xAD), true);
            Object v = i.make("Registry$Ballot", ANN, BigInteger.TEN);
            Object head = i.make("RegistryValidator$Head_Ballot", i.h.instanceId(), new byte[0], JscV2M3bGateTest.LINKED);
            return i.insert("Ballot", head, i.keyId("Ballot", new byte[0]), new byte[0], v, ANN,
                    i.make("RegistryValidator$Insert_cast", i.h.instanceId(), JscV2M3bGateTest.ANCHOR, ANN, BigInteger.TEN, c), c, ANN, open);
        });
    }
}
