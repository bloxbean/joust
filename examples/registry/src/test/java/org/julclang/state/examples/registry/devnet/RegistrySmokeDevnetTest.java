package org.julclang.state.examples.registry.devnet;

import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.examples.registry.RegistryClient;
import org.julclang.state.runtime.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The registry on a live Yaci DevKit: every keyed operation through the generated client (ADR-0016 M2). */
@Tag("devnet")
class RegistrySmokeDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void everyOperation() {
        var rig = M2Kit.rig();
        var runtime = rig.runtime();
        var admin = DevKit.fundedAccount(500);
        var ann = DevKit.fundedAccount(300);
        var reg = RegistryClient.deploy(runtime, admin);
        // The deployment published the validator (14.7 KB) as a reference script, which every call reads.
        var contract = RegistryClient.contract(runtime);
        var ref = contract.findReferenceScript();
        assertNotNull(ref, "the registry's reference script");
        assertEquals(contract.policyId(), ref.getReferenceScriptHash());
        System.out.println("[smoke] the registry's reference script: " + ref.getTxHash() + "#" + ref.getOutputIndex());
        var ballot = reg.cast(ann, Keys.pkh(ann), BigInteger.TWO);
        assertEquals(BigInteger.TWO, ballot.value().choice());
        var b = reg.claim(ann, new byte[]{0x62}, BigInteger.ONE, org.julclang.core.types.JulcList.<byte[]>empty().prepend(Keys.pkh(ann)),
                BigInteger.ONE, ADA.multiply(BigInteger.valueOf(5)));
        System.out.println("keys after one claim: " + reg.entryKeys().stream().map(e -> e.keyHex()).toList());
        assertTrue(reg.entryExists(new byte[]{0x62}));
        var up = b.upgrade(ann, BigInteger.TWO, ADA);
        assertEquals(BigInteger.TWO, up.state().tier());
        b.withdraw(ann, ADA.multiply(BigInteger.TWO));
        // A payout below the ledger minimum (0.3 ADA) is padded to it from the wallet (decision D-c): the output pays
        // at least what the contract requires, and the ledger accepts it.
        var small = b.withdraw(ann, BigInteger.valueOf(300_000));
        assertEquals(BigInteger.valueOf(300_000), small.payouts().get(0).lovelace());
        var tx = rig.built("Registry.withdraw").get(1).tx();
        var paid = tx.getBody().getOutputs().stream().filter(o -> o.getAddress().equals(ann.enterpriseAddress()))
                .map(o -> o.getValue().getCoin()).toList();
        System.out.println("[smoke] a 0.3 ADA payout was paid as " + paid + " lovelace");
        assertEquals(1, paid.size());
        assertTrue(paid.get(0).compareTo(BigInteger.valueOf(900_000)) > 0, "padded to the ledger minimum");
        var removed = b.release(ann);
        System.out.println("release paid " + removed.payouts());
        assertFalse(reg.entryExists(new byte[]{0x62}));
        var leave = ballot.leave(ann);
        assertTrue(reg.ballot(Keys.pkh(ann)).retired());
        System.out.println("ballot keys " + reg.ballotKeys());
        // Every keyed call read the reference script, none carried the script itself (R-D13.4).
        var calls = rig.built().stream().filter(x -> x.what().startsWith("Registry.")).toList();
        assertEquals(7, calls.size(), calls.stream().map(M2Kit.Built::what).toList().toString());
        for (var c : calls) {
            var refs = c.tx().getBody().getReferenceInputs();
            assertTrue(refs != null && refs.stream().anyMatch(i -> i.getTransactionId().equals(ref.getTxHash())
                    && i.getIndex() == ref.getOutputIndex()), c.what() + " reads the reference script");
            var ws = c.tx().getWitnessSet();
            assertTrue(ws.getPlutusV3Scripts() == null || ws.getPlutusV3Scripts().isEmpty(), c.what() + " carries no script");
        }
    }
}
