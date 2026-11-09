package org.julclang.state.examples.dex;

import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The AMM on a live Yaci DevKit (ADR-0007): a real native token, LP tokens minted and burned by the contract. */
@Tag("devnet")
class DexDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void liquidityAndSwaps() {
        var runtime = DevKit.runtime();
        var admin = DevKit.fundedAccount(200);
        var lp = DevKit.fundedAccount(500);
        var trader = DevKit.fundedAccount(300);
        byte[] name = "HOSKY".getBytes(StandardCharsets.UTF_8);
        byte[] policy = HexUtil.decodeHexString(DevKit.mintToken(lp, "HOSKY", 1_000_000));
        DevKit.topUp(trader.baseAddress(), 1);
        String unit = HexUtil.encodeHexString(policy) + HexUtil.encodeHexString(name);

        var dex = DexClient.deploy(runtime, admin, policy, name);
        Value tokens = Assets.of(policy, name, BigInteger.valueOf(100_000));
        dex.addLiquidity(lp, BigInteger.valueOf(100_000), ADA.multiply(BigInteger.valueOf(100)), tokens);
        Dex.Pool p = dex.state();
        assertEquals(ADA.multiply(BigInteger.valueOf(100)), p.lpSupply());
        String lpUnit = dex.instance().contract().policyId() + HexUtil.encodeHexString(p.lpName());
        assertEquals(p.lpSupply(), DevKit.balance(lp.enterpriseAddress(), lpUnit), "LP tokens minted to the provider");
        assertEquals(BigInteger.valueOf(100_000), Assets.quantity(dex.instance().value(), policy, name));

        // ADA -> token, with slippage protection.
        BigInteger expect = Dex.amountOut(ADA.multiply(BigInteger.TEN), ADA.multiply(BigInteger.valueOf(100)),
                BigInteger.valueOf(100_000));
        assertThrows(RequireException.class, () -> dex.swapAdaForToken(trader, expect.add(BigInteger.ONE),
                ADA.multiply(BigInteger.TEN), Assets.none()), "minOut above the quote");
        dex.swapAdaForToken(trader, expect, ADA.multiply(BigInteger.TEN), Assets.none());
        assertEquals(expect, DevKit.balance(trader.enterpriseAddress(), unit));

        // token -> ADA: the trader spends the tokens it just received.
        DevKit.sweepEnterprise(trader);
        BigInteger sell = expect.divide(BigInteger.TWO);
        dex.swapTokenForAda(trader, sell, BigInteger.ONE, BigInteger.ZERO, Assets.of(policy, name, sell));

        // The provider takes its liquidity out: LP burned, both reserves paid out.
        DevKit.sweepEnterprise(lp);
        BigInteger all = dex.state().lpSupply();
        dex.removeLiquidity(lp, all, BigInteger.ZERO, Assets.of(HexUtil.decodeHexString(dex.instance().contract().policyId()),
                p.lpName(), all));
        assertEquals(BigInteger.ZERO, dex.state().lpSupply());
        assertEquals(BigInteger.ZERO, DevKit.balance(lp.baseAddress(), lpUnit).add(DevKit.balance(lp.enterpriseAddress(), lpUnit)),
                "every LP token burned");
        dex.close(admin);
    }
}
