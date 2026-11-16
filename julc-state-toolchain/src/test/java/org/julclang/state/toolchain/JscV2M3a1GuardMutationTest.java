package org.julclang.state.toolchain;

import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.state.api.Ctx;
import org.julclang.state.dsl.v2.JscV2;
import org.julclang.state.examples.dsl.Members;
import org.julclang.state.examples.dsl.MembersClient;
import org.julclang.state.examples.dsl.MembersValidator;
import org.julclang.state.runtime.ContractSpec;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #101 review C2: the checked-input refusal the five-way harness asserts (Members' score with a 27-byte {@code by},
 * {@code input:by}) is the validator's guard's, not a side effect. The transaction proposes what the body would produce
 * without the guard (the note stored under the short key, the best note, the event), as {@code FourWay.unguarded} does
 * for the five-way vector. The port's validator refuses it; a validator built from the same lowering with only that
 * guard removed accepts it, so the vector fails when the guard is gone.
 */
class JscV2M3a1GuardMutationTest {

    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] ANN = StateHarness.fill(28, 0xA1);
    static final byte[] K1 = StateHarness.fill(28, 0x11), K2 = StateHarness.fill(28, 0x22), K3 = StateHarness.fill(28, 0x33);

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    /** The port's spec with another script in place of its own (the params are applied to it as to the port's). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ContractSpec<Members.State> withScript(ContractSpec<Members.State> spec, String cborHex) throws Exception {
        var comps = ContractSpec.class.getRecordComponents();
        var types = new Class<?>[comps.length];
        var vals = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            vals[i] = comps[i].getName().equals("scriptCborHex") ? cborHex : comps[i].getAccessor().invoke(spec);
        }
        return (ContractSpec<Members.State>) ContractSpec.class.getDeclaredConstructor(types).newInstance(vals);
    }

    /** ANN's score from a 27-byte key: the object and event the body would make without the guard. */
    static TxModel unguardedScore(StateHarness<Members.State> h) {
        byte[] by27 = new byte[27];
        BigInteger until = StateHarness.T0.add(BigInteger.valueOf(10_000_000));
        JulcList<byte[]> guardians = JulcList.<byte[]>empty().prepend(K3).prepend(K2).prepend(K1);
        var member = new Members.Member(ANN, guardians, BigInteger.TWO, JulcMap.empty(), new Members.Basic(), Optional.empty(), until);
        JulcMap<byte[], BigInteger> notes = JulcMap.empty();
        var next = new Members.Member(ANN, guardians, BigInteger.TWO, notes.insert(by27, BigInteger.valueOf(3)), new Members.Basic(),
                Optional.of(new Members.Note(by27, "noted".getBytes())), until);
        Ctx ctx = h.ctx(ANN, BigInteger.ZERO, BigInteger.ZERO);
        JulcList<Members.Event> events = JulcList.<Members.Event>empty().prepend(new Members.Noted(by27, BigInteger.valueOf(3)));
        var obj = h.liveObject(new MembersValidator.Holding(h.instanceId(), member), BigInteger.ZERO);
        return h.actObject(obj, new MembersValidator.Score(by27, BigInteger.valueOf(3), ctx, events), new MembersValidator.Holding(h.instanceId(), next),
                JulcList.empty(), ANN, ctx, null);
    }

    @Test
    void theInputByVector_failsWithoutItsGuard() throws Exception {
        var params = new Members.Config(ADA.multiply(BigInteger.TWO));
        var real = StateHarness.of(MembersClient.SPEC, params);
        real.assertRejected(unguardedScore(real), "the port refuses a 27-byte key (input:by)");

        // The same lowering, with only the guard of score's `by` removed.
        String java = JscV2.lower(Files.readString(JscV2M1aFourWayTest.DSL.resolve("Members.jsc")), "Members.jsc").java();
        String guard = java.lines().filter(l -> l.contains("Require.that(check__Key(by)")).findFirst().orElseThrow();
        String mutated = java.replace(guard + "\n", "");
        assertNotEquals(java, mutated);
        var built = Toolchain.build(Map.of("org/julclang/state/examples/dsl/Members.java", mutated), Map.of());
        assertTrue(built.ok(), built.diagnostics().toString());
        var mutant = StateHarness.of(withScript(MembersClient.SPEC, built.contracts().get(0).scriptCborHex()), params);
        var v = mutant.evaluate(unguardedScore(mutant));
        System.out.println("[M3a1 C2] the score vector against the validator without its input guard: " + v);
        assertTrue(v.accepted(), "without the guard the unguarded outcome is accepted, so the vector tests the guard: " + v);
    }
}
