package org.julclang.state.runtime.wasm;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Keys;
import org.julclang.state.runtime.described.DescribedContract;
import org.julclang.state.runtime.described.UplcTransitions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0015 M3 on a live DevKit, JVM: StakedVault's purpose handlers (ADR-0010) through the described dispatcher. Its
 * funds sit at base addresses staked to its own script; the council (a quorum in the params) delegates the stake
 * (@Certify) and collects the rewards (@Withdraw, whose sends come from its transition program).
 */
@Tag("devnet")
class DescribedStakingDevnetTest {

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    static final ObjectMapper JSON = new ObjectMapper();
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @Test
    void delegateCollectClose() throws Exception {
        var c = DescribedReplayTest.contract("staking", "StakedVault");
        var vault = new DescribedContract(JSON.readTree(c.description()), c.script(), new UplcTransitions(c.transitions()));
        var rt = DevKit.runtime();
        Account[] council = {DevKit.fundedAccount(300), DevKit.fundedAccount(300), DevKit.fundedAccount(300)};
        Arrays.sort(council, Comparator.comparing(a -> HexUtil.encodeHexString(Keys.pkh(a))));
        var payee = DevKit.fundedAccount(5);
        var keys = JSON.createArrayNode();
        for (var m : council) keys.add(HexUtil.encodeHexString(Keys.pkh(m)));
        String pool = DevKit.poolId();
        var params = JSON.createObjectNode();
        params.set("council", keys);
        params.put("quorum", "2");
        params.put("pool", pool);
        params.put("payee", HexUtil.encodeHexString(Keys.pkh(payee)));
        params.put("cap", ADA.multiply(BigInteger.valueOf(1000)).toString());

        String id = vault.call(rt, council[0], "init", "open", null, null, JSON.createObjectNode(), ADA.multiply(BigInteger.valueOf(100)), params);
        assertTrue(vault.handle(rt, params).address().startsWith("addr_test1x"), "a base address staked to the script");
        vault.call(rt, council[1], "action", "deposit", id, null, JSON.createObjectNode(), ADA.multiply(BigInteger.valueOf(20)), params);

        vault.handle(rt, params).registerStake(council[0]);
        assertThrows(RuntimeException.class, () -> vault.delegate(rt, council[0], params, pool, List.of()), "one signature is not a quorum");
        vault.delegate(rt, council[0], params, pool, List.of(council[1]));
        // Rewards take epochs on DevKit: collecting the balance (zero now) still runs @Withdraw and its program.
        var amount = vault.handle(rt, params).rewardBalance();
        vault.collect(rt, council[0], params, amount, null, List.of(council[2]));
        assertThrows(IllegalArgumentException.class, () -> vault.collect(rt, council[0], params, amount,
                JSON.readTree("{\"to\":\"00\"}"), List.of(council[2])), "collect takes no arguments");
        vault.call(rt, council[1], "destroy", "close", id, null, JSON.createObjectNode(), BigInteger.ZERO, params, null, null,
                List.of(council[2]));
        System.out.println("[M3] StakedVault (@Certify delegation, @Withdraw rewards, staked funds) through the described dispatcher on DevKit");
    }
}
