package org.julclang.state.examples.upgrade;

import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.InstanceNotFoundException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** ADR-0011 D4 on a live Yaci DevKit: a v1 counter migrates into v2, count and funds, in one transaction. */
@Tag("devnet")
class UpgradeDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void v1MigratesToV2() {
        var runtime = DevKit.runtime();
        var admin = DevKit.fundedAccount(200);
        var v1 = CounterV1Client.deploy(runtime, admin, ADA.multiply(BigInteger.TEN));
        v1.increment(admin);
        v1.increment(admin);
        BigInteger lovelace = v1.lovelace();

        var cfg = new CounterV2.Config(HexUtil.decodeHexString(v1.instance().contract().policyId()));
        String v2Policy = CounterV2Client.policyIdFor(cfg);
        v1.setSuccessor(admin, HexUtil.decodeHexString(v2Policy));

        var adopted = new CounterV2Client[1];
        String tx = Compose.of(runtime, admin)
                .add(() -> v1.upgrade(admin))
                .add(() -> adopted[0] = CounterV2Client.adopt(runtime, cfg, admin, v1.instance()))
                .submit();
        assertNotNull(tx);
        var v2 = CounterV2Client.at(runtime, cfg, adopted[0].instanceId());
        assertEquals(BigInteger.TWO, v2.state().count(), "the count moved");
        assertEquals(lovelace, v2.lovelace(), "the funds moved");
        assertThrows(InstanceNotFoundException.class, v1::state, "v1 ended");
        v2.increment(admin);
        assertEquals(BigInteger.valueOf(3), v2.state().count());
    }
}
