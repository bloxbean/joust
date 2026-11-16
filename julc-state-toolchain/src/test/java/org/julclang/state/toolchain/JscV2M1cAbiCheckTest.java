package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.AbiCheck;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M1c review C3 and S4: a batched contract's manifest is checked against the processor's description down to
 * the intent's roles. Each mutation of Pool's manifest (and of its description) below is a difference, and the roles
 * are bound to the description's {@code x-joust.intents}, not to the schemas the manifest names.
 */
class JscV2M1cAbiCheckTest {

    static final String PKG = "org.julclang.state.examples.dsl";

    @Test
    void intentMutations_areDifferences() throws Exception {
        var r = Toolchain.build(Map.of("org/julclang/state/examples/dsl/Pool.jsc", Files.readString(JscV2M1aFourWayTest.DSL.resolve("Pool.jsc"))), Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        String abi = r.generated().get(0).abiJson();
        String desc = r.contracts().get(0).descriptionJson();
        assertEquals(Files.readString(JscV2M1aFourWayTest.DSL.resolve("Pool.abi.json")), abi, "the committed manifest");
        assertEquals(List.of(), AbiCheck.compare(abi, desc, PKG, "Pool"));

        String roles = "\"intent\": { \"datum\": \"Intent\", \"refund\": \"IntentRefund\" }";
        var abiMutants = new LinkedHashMap<String, String>();
        abiMutants.put("datum and refund roles swapped (S4)", abi.replace(roles, "\"intent\": { \"datum\": \"IntentRefund\", \"refund\": \"Intent\" }"));
        abiMutants.put("refund root is a bare TxOutRef (C3)", abi.replace(roles, "\"intent\": { \"datum\": \"Intent\", \"refund\": \"TxOutRef\" }"));
        abiMutants.put("datum root is the state (C3)", abi.replace(roles, "\"intent\": { \"datum\": \"Ctx\", \"refund\": \"IntentRefund\" }"));
        abiMutants.put("intent root removed", abi.replace("  " + roles + ",\n", ""));
        abiMutants.put("IntentCall variants swapped", abi.replace("{ \"name\": \"DonateIntent\", \"constr\": 0", "{ \"name\": \"XTMP\", \"constr\": 1")
                .replace("{ \"name\": \"BuyIntent\", \"constr\": 1", "{ \"name\": \"DonateIntent\", \"constr\": 0").replace("XTMP", "BuyIntent"));
        abiMutants.put("Batch field renamed", abi.replace("{ \"name\": \"validFrom\", \"type\": \"Int\"", "{ \"name\": \"from\", \"type\": \"Int\""));
        abiMutants.put("Intent.refund as bytes", abi.replace("{ \"name\": \"refund\", \"type\": \"Address\", \"data\": \"Address\" }",
                "{ \"name\": \"refund\", \"type\": \"Bytes\", \"data\": \"bytes\" }"));
        abiMutants.put("CancelIntent dropped", abi.replace(",\n      { \"name\": \"CancelIntent\", \"constr\": 5, \"fields\": [] }", ""));
        abiMutants.put("IntentRefund's field renamed", abi.replace("\"IntentRefund\", \"constr\": 0, \"fields\": [\n        { \"name\": \"intent\"",
                "\"IntentRefund\", \"constr\": 0, \"fields\": [\n        { \"name\": \"ref\""));
        abiMutants.forEach((what, a) -> {
            assertNotEquals(abi, a, "the mutation applies: " + what);
            var problems = AbiCheck.compare(a, desc, PKG, "Pool");
            assertFalse(problems.isEmpty(), what + " passed the check");
            System.out.println("[C3/S4] " + what + ": " + problems.get(0));
        });

        // The processor's side changes (as a processor change would): each is caught through the right root, and a manifest
        // naming another root for the refund does not hide it.
        int at = desc.indexOf("\"" + PKG + ".PoolValidator.IntentRefund\": {");
        int idx = desc.indexOf("\"index\": 0", at);
        assertTrue(at > 0 && idx > at, "the description's IntentRefund");
        String descRefund9 = desc.substring(0, idx) + "\"index\": 9" + desc.substring(idx + "\"index\": 0".length());
        assertNotEquals(desc, descRefund9);
        assertFalse(AbiCheck.compare(abi, descRefund9, PKG, "Pool").isEmpty(), "a changed refund tag");
        assertFalse(AbiCheck.compare(abiMutants.get("refund root is a bare TxOutRef (C3)"), descRefund9, PKG, "Pool").isEmpty(),
                "a changed refund tag behind a wrong root");
        String descNoIntents = desc.replaceFirst("\"intents\"", "\"intentsGone\"");
        assertFalse(AbiCheck.compare(abi, descNoIntents, PKG, "Pool").isEmpty(), "a manifest with intents, a description without");
    }
}
