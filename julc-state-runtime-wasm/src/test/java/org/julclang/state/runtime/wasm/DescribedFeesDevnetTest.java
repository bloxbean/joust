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
 * ADR-0015 M3 on a live DevKit, JVM: Fees (a Stall's sale deposits its fee into a Pot whose @Receives action takes it,
 * in one composed transaction) through the described dispatcher with UPLC transitions.
 */
@Tag("devnet")
class DescribedFeesDevnetTest {

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    static final ObjectMapper JSON = new ObjectMapper();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @Test
    void depositsInOneComposedTransaction() throws Exception {
        var p = DescribedReplayTest.contract("fees", "Pot");
        var s = DescribedReplayTest.contract("fees", "Stall");
        var pot = new DescribedContract(JSON.readTree(p.description()), p.script(), new UplcTransitions(p.transitions()));
        var stall = new DescribedContract(JSON.readTree(s.description()), s.script(), new UplcTransitions(s.transitions()));
        var rt = DevKit.runtime();
        var owner = DevKit.fundedAccount(300);
        var buyer = DevKit.fundedAccount(300);
        var none = JSON.createObjectNode();
        String potId = pot.call(rt, owner, "init", "init", null, null, none, BigInteger.ZERO);
        var cfg = JSON.readTree("{\"pot\":{\"policy\":\"" + pot.handle(rt).policyId() + "\",\"instance\":\"" + potId + "\"},\"feeBps\":\"500\"}");
        String stallId = stall.call(rt, owner, "init", "init", null, null, none, BigInteger.ZERO, cfg);
        var price = JSON.readTree("{\"price\":\"10000000\"}");
        for (int i = 0; i < 2; i++) {
            Compose.of(rt, buyer).allowSharedSigners()
                    .add(() -> stall.call(rt, buyer, "action", "sell", stallId, null, price, ADA.multiply(BigInteger.TEN), cfg))
                    .add(() -> pot.call(rt, buyer, "action", "collect", potId, null, none, BigInteger.ZERO))
                    .submit();
        }
        assertEquals(String.valueOf(ADA), pot.state(rt, potId).get("collected").asText());
        assertEquals("2", pot.state(rt, potId).get("deposits").asText());
        // The receiver first: its call sees the deposits made after it.
        var price20 = JSON.readTree("{\"price\":\"20000000\"}");
        Compose.of(rt, buyer).allowSharedSigners()
                .add(() -> pot.call(rt, buyer, "action", "collect", potId, null, none, BigInteger.ZERO))
                .add(() -> stall.call(rt, buyer, "action", "sell", stallId, null, price20, ADA.multiply(BigInteger.valueOf(20)), cfg))
                .submit();
        assertEquals(String.valueOf(ADA.multiply(BigInteger.TWO)), pot.state(rt, potId).get("collected").asText());
        System.out.println("[M3] Fees (deposits, @Receives, composition) through the described dispatcher on DevKit");
    }
}
