package org.julclang.state.examples.staking;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.core.types.JulcList;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The staked vault on a live Yaci DevKit (ADR-0010): staked addresses, registration, delegation, reward withdrawal. */
@Tag("devnet")
class StakingDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void stakedFundsDelegateAndWithdraw() {
        var runtime = DevKit.runtime();
        Account[] council = {DevKit.fundedAccount(300), DevKit.fundedAccount(300), DevKit.fundedAccount(300)};
        Arrays.sort(council, Comparator.comparing(a -> HexUtil.encodeHexString(Keys.pkh(a))));
        var payee = DevKit.fundedAccount(5);
        JulcList<byte[]> keys = JulcList.empty();
        for (int i = council.length - 1; i >= 0; i--) keys = keys.prepend(Keys.pkh(council[i]));
        String pool = DevKit.poolId();
        var cfg = new StakedVault.Config(keys, BigInteger.TWO, HexUtil.decodeHexString(pool), Keys.pkh(payee),
                ADA.multiply(BigInteger.valueOf(1000)));

        var vault = StakedVaultClient.deploy(runtime, cfg, council[0], ADA.multiply(BigInteger.valueOf(100)));
        String address = vault.instance().contract().address();
        assertTrue(address.startsWith("addr_test1x"), "a base address staked to the script: " + address);
        vault.deposit(council[1], ADA.multiply(BigInteger.valueOf(20)));

        var handle = StakedVaultClient.contract(runtime, cfg);
        handle.registerStake(council[0]);
        handle.delegateTo(pool, council[0], council[1]);
        assertThrows(RuntimeException.class, () -> handle.delegateTo(pool, council[2]), "one signature is not a quorum");

        // Rewards take epochs to accrue on DevKit; withdrawing whatever is there (zero now) runs @Withdraw.
        StakedVaultClient.collect(runtime, cfg, council[0], council[2]);
        vault.close(council[1], council[2]);
    }
}
