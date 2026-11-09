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
 * package to reach the unchecked insert), the node refuses it on chain: the validator's place check (I46).
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

        // Forced: no JVM check, and the node evaluates (no local evaluation to stop it first).
        var spec = new KeyedList.Insert<Registry.Entry>("claim", ann, ADA.multiply(BigInteger.TWO), true, false,
                (sh, ctx) -> Registry.claim(b, BigInteger.ONE, g, BigInteger.ONE, ctx),
                (inst, anchor, ctx) -> new RegistryValidator.Insert_claim(inst, anchor, b, BigInteger.ONE, g, BigInteger.ONE, ctx));
        System.setProperty("joust.debug.nodeEvaluates", "true");
        JoustException e;
        try {
            e = assertThrows(JoustException.class, () -> reg.entryList().insert(spec, false));
        } finally {
            System.clearProperty("joust.debug.nodeEvaluates");
        }
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        System.out.println("[duplicate] forced on chain: " + cause.getMessage().substring(0, Math.min(400, cause.getMessage().length())));
        assertTrue(cause.getMessage().contains("rejected"), cause.getMessage());
        assertEquals(List.of("62"), reg.entryKeys().stream().map(KeyedList.Entry::keyHex).toList());
    }
}
