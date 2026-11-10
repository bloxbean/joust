package org.julclang.state.runtime;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.registry.Registry;
import org.julclang.state.examples.registry.RegistryClient;
import org.julclang.state.examples.registry.RegistryValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0016 M2: a duplicate key is refused on the JVM; forced past the JVM's check (this test lives in the runtime's
 * package to reach the unchecked insert), the node runs the validator with real budgets and refuses it: the place check
 * (I46). The refusal is a script failure whose inputs did not move: deterministic, so it is not rebuilt (R-C21).
 */
@Tag("devnet")
class DuplicateKeyDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void refusedOnTheJvmAndForcedPastIt_onChain() {
        var runtime = DevKit.runtime();
        var ann = DevKit.fundedAccount(400);
        var reg = RegistryClient.deploy(runtime, ann);
        JulcList<byte[]> g = JulcList.<byte[]>empty().prepend(Keys.pkh(ann));
        byte[] b = {0x62};
        reg.claim(ann, b, BigInteger.ONE, g, BigInteger.ONE, ADA.multiply(BigInteger.TWO));
        var live = assertThrows(RequireException.class, () -> reg.claim(ann, b, BigInteger.ONE, g, BigInteger.ONE, ADA.multiply(BigInteger.TWO)));
        assertTrue(live.getMessage().contains("already taken"), live.getMessage());
        reg.cast(ann, Keys.pkh(ann), BigInteger.ONE);
        reg.ballot(Keys.pkh(ann)).leave(ann);
        var retired = assertThrows(RequireException.class, () -> reg.cast(ann, Keys.pkh(ann), BigInteger.TWO));
        assertTrue(retired.getMessage().contains("retired"), retired.getMessage());

        // Forced: no JVM check. The local evaluator refuses the duplicate, so its budget would never be filled in; this
        // runtime then answers generous budgets instead, and the node runs the scripts with real units (R-D3): the
        // refusal is the validator's own error, not a starved budget.
        var forcing = forcingRuntime();
        var regF = RegistryClient.at(forcing, reg.instanceId());
        var spec = new KeyedList.Insert<Registry.Entry>("claim", ann, ADA.multiply(BigInteger.TWO), true, false,
                (sh, ctx) -> Registry.claim(b, BigInteger.ONE, g, BigInteger.ONE, ctx),
                (inst, anchor, ctx) -> new RegistryValidator.Insert_claim(inst, anchor, b, BigInteger.ONE, g, BigInteger.ONE, ctx));
        var e = assertThrows(SubmitException.class, () -> regF.entryList().insert(spec, false));
        String m = e.getMessage();
        System.out.println("[duplicate] forced on chain: " + m.substring(0, Math.min(600, m.length())));
        assertEquals(SubmitException.Kind.SCRIPT, e.kind, "the node refused it for a script failure");
        assertTrue(LOCAL_FAILURES.get() >= 1, "the local evaluator refused it first");
        assertTrue(m.contains("PlutusFailure"), m);
        assertTrue(m.contains("terminated because of an error"), "the validator's error (the place check, I46): " + m);
        assertFalse(m.contains("overspending the budget"), "not a starved budget: " + m);
        assertEquals(List.of("62"), reg.entryKeys().stream().map(KeyedList.Entry::keyHex).toList());
    }

    static final java.util.concurrent.atomic.AtomicInteger LOCAL_FAILURES = new java.util.concurrent.atomic.AtomicInteger();

    /** As DevKit's runtime, whose evaluator answers generous budgets where JuLC's local evaluation fails. */
    static StateRuntime forcingRuntime() {
        var backend = DevKit.backend();
        var time = DevKit.chainTime(backend);
        var local = new org.julclang.clientlib.eval.JulcTransactionEvaluator(
                new com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier(backend.getUtxoService()),
                new com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier(backend.getEpochService()),
                new com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier(backend.getScriptService()),
                new org.julclang.clientlib.eval.SlotConfig(time.zeroSlot(), time.zeroTimeMs(), time.slotLengthMs()));
        com.bloxbean.cardano.client.api.TransactionEvaluator generous = (cbor, utxos) -> {
            var r = local.evaluateTx(cbor, utxos);
            if (r.isSuccessful()) return r;
            LOCAL_FAILURES.incrementAndGet();
            try {
                var redeemers = com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(cbor).getWitnessSet().getRedeemers();
                int n = redeemers.size();
                var out = new java.util.ArrayList<com.bloxbean.cardano.client.api.model.EvaluationResult>();
                for (var red : redeemers) {
                    // Per-transaction limits (14,000,000 memory, 10,000,000,000 steps), shared out, with room to spare.
                    out.add(new com.bloxbean.cardano.client.api.model.EvaluationResult(red.getTag(), red.getIndex().intValue(),
                            new com.bloxbean.cardano.client.plutus.spec.ExUnits(BigInteger.valueOf(12_000_000L / n),
                                    BigInteger.valueOf(9_000_000_000L / n))));
                }
                return com.bloxbean.cardano.client.api.model.Result.success("generous budgets").withValue(out);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        };
        return StateRuntime.builder(backend).network(com.bloxbean.cardano.client.common.model.Networks.testnet()).chainTime(time)
                .ttl(java.time.Duration.ofSeconds(20)).evaluator(generous).log(msg -> System.out.println("  [joust] " + msg)).build();
    }
}
