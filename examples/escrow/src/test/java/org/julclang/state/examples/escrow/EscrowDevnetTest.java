package org.julclang.state.examples.escrow;

import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("devnet")
class EscrowDevnetTest {
    static final BigInteger PRICE = BigInteger.valueOf(25_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void releaseAndRefund() throws Exception {
        var runtime = DevKit.runtime();
        var buyer = DevKit.fundedAccount(100);
        var seller = DevKit.fundedAccount(20);
        var arbiter = DevKit.fundedAccount(20);
        var stranger = DevKit.fundedAccount(20);
        var deposit = BigInteger.valueOf(EscrowClient.SPEC.minAda());

        // Released by the buyer: the seller gets everything.
        var deal = EscrowClient.deploy(runtime, buyer, Keys.pkh(seller), Keys.pkh(arbiter),
                BigInteger.valueOf(System.currentTimeMillis() + 3_600_000), PRICE);
        assertThrows(RequireException.class, () -> deal.release(seller));
        var r = deal.release(buyer);
        assertEquals(PRICE.add(deposit), r.payouts().get(0).lovelace());
        assertArrayEquals(Keys.pkh(seller), r.payouts().get(0).to());

        // Refunded by a stranger after a short deadline.
        long deadline = System.currentTimeMillis() + 30_000;
        var deal2 = EscrowClient.deploy(runtime, buyer, Keys.pkh(seller), Keys.pkh(arbiter), BigInteger.valueOf(deadline), PRICE);
        assertThrows(RequireException.class, () -> deal2.refund(stranger));
        while (System.currentTimeMillis() < deadline + 2_000) Thread.sleep(1_000);
        var back = deal2.refund(stranger);
        assertArrayEquals(Keys.pkh(buyer), back.payouts().get(0).to());
    }
}
