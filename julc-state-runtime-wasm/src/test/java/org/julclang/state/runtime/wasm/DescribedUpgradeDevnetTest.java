package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.described.DescribedContract;
import org.julclang.state.runtime.described.UplcTransitions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0015 M3 on a live DevKit, JVM: a creation inside a composition. CounterV1's migrating destroy (@MigrateTo) and
 * CounterV2's adoption (@Migrate) in one transaction (ADR-0011 D4), through the described dispatcher.
 */
@Tag("devnet")
class DescribedUpgradeDevnetTest {

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void upgradeAndAdoptInOneTransaction() throws Exception {
        var c1 = DescribedReplayTest.contract("upgrade", "CounterV1");
        var c2 = DescribedReplayTest.contract("upgrade", "CounterV2");
        var v1 = new DescribedContract(JSON.readTree(c1.description()), c1.script(), UplcTransitions.verified(c1.manifest(), c1.description(), c1.script(), c1.transitions()));
        var v2 = new DescribedContract(JSON.readTree(c2.description()), c2.script(), UplcTransitions.verified(c2.manifest(), c2.description(), c2.script(), c2.transitions()));
        var rt = DevKit.runtime();
        var admin = DevKit.fundedAccount(100);
        var empty = JSON.createObjectNode();
        String old = v1.call(rt, admin, "init", "open", null, null, empty, BigInteger.valueOf(10_000_000), null);
        v1.call(rt, admin, "action", "increment", old, null, empty, BigInteger.ZERO, null);
        v1.call(rt, admin, "action", "increment", old, null, empty, BigInteger.ZERO, null);
        var params = JSON.createObjectNode().put("previous", v1.handle(rt).policyId());
        String v2Policy = v2.handle(rt, params).policyId();
        v1.call(rt, admin, "action", "setSuccessor", old, null, JSON.createObjectNode().put("successor", v2Policy), BigInteger.ZERO, null);
        BigInteger funds = v1.handle(rt).at(old).lovelace();
        // An adoption alone is refused: it runs only beside the old instance's migrating destroy.
        assertThrows(RuntimeException.class, () -> v2.call(rt, admin, "migrate", "adopt", old, null, empty, BigInteger.ZERO, params));
        String[] adopted = new String[1];
        String tx = Compose.of(rt, admin)
                .add(() -> v1.call(rt, admin, "destroy", "upgrade", old, null, empty, BigInteger.ZERO, null))
                .add(() -> adopted[0] = v2.call(rt, admin, "migrate", "adopt", old, null, empty, BigInteger.ZERO, params))
                .submit();
        assertNotNull(tx);
        var s = v2.state(rt, adopted[0], params);
        assertEquals("2", s.get("count").asText(), "the count moved");
        assertEquals(funds, v2.handle(rt, params).at(adopted[0]).lovelace(), "the funds moved");
        assertThrows(RuntimeException.class, () -> v1.state(rt, old), "v1 ended");
        v2.call(rt, admin, "action", "increment", adopted[0], null, empty, BigInteger.ZERO, params);
        assertEquals("3", v2.state(rt, adopted[0], params).get("count").asText());
        System.out.println("[M3] Upgrade (a creation in a composition: @MigrateTo + @Migrate) through the described dispatcher on DevKit");
    }
}
