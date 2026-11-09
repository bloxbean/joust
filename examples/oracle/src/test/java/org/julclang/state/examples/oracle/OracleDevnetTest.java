package org.julclang.state.examples.oracle;

import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.state.api.ForeignInstance;
import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.JoustException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** ADR-0011 on a live Yaci DevKit: @Reads of an oracle, and two contracts' calls composed into one transaction. */
@Tag("devnet")
class OracleDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void readsAndCompose() {
        var runtime = DevKit.runtime();
        var publisher = DevKit.fundedAccount(100);
        var publisher2 = DevKit.fundedAccount(100);
        var shopOwner = DevKit.fundedAccount(100);
        var buyer = DevKit.fundedAccount(300);

        var oracle = OracleClient.deploy(runtime, publisher, BigInteger.valueOf(3_000_000));
        var cfg = new Shop.Config(new ForeignInstance(HexUtil.decodeHexString(oracle.instance().contract().policyId()),
                HexUtil.decodeHexString(oracle.instanceId())), BigInteger.valueOf(600_000));
        var shop = ShopClient.deploy(runtime, cfg, shopOwner);

        shop.buy(buyer, BigInteger.TWO, BigInteger.valueOf(6_000_000));
        assertEquals(BigInteger.TWO, shop.state().sold());
        assertThrows(RequireException.class, () -> shop.buy(buyer, BigInteger.ONE, BigInteger.valueOf(2_000_000)),
                "pays less than the oracle price");

        // A second oracle (another instance of the same contract) posts while the shop sells: one transaction.
        var oracle2 = OracleClient.deploy(runtime, publisher2, BigInteger.valueOf(1_000_000));
        String tx = Compose.of(runtime, buyer)
                .add(() -> shop.buy(buyer, BigInteger.ONE, BigInteger.valueOf(3_000_000)))
                .add(() -> oracle2.post(publisher2, BigInteger.valueOf(5_000_000)))
                .submit();
        assertNotNull(tx);
        assertEquals(BigInteger.valueOf(3), shop.state().sold());
        assertEquals(BigInteger.valueOf(5_000_000), oracle2.state().price());

        assertThrows(JoustException.class, () -> Compose.of(runtime, buyer)
                .add(() -> shop.buy(buyer, BigInteger.ONE, BigInteger.valueOf(3_000_000)))
                .add(() -> shop.buy(buyer, BigInteger.ONE, BigInteger.valueOf(3_000_000)))
                .submit(), "the same instance spent twice is refused before submitting");
    }
}
