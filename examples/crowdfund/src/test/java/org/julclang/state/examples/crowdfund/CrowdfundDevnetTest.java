package org.julclang.state.examples.crowdfund;

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
class CrowdfundDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void goalMet_creatorClaims() throws Exception {
        var runtime = DevKit.runtime();
        var creator = DevKit.fundedAccount(20);
        var backers = new com.bloxbean.cardano.client.account.Account[]{
                DevKit.fundedAccount(100), DevKit.fundedAccount(100), DevKit.fundedAccount(100)};
        long deadline = System.currentTimeMillis() + 60_000;
        var c = CrowdfundClient.deploy(runtime, creator, ADA.multiply(BigInteger.valueOf(40)), BigInteger.valueOf(deadline),
                ADA.multiply(BigInteger.TWO));
        for (var b : backers) c.contribute(b, ADA.multiply(BigInteger.TEN));
        c.contribute(backers[0], ADA.multiply(BigInteger.TEN));          // a repeat backer
        assertEquals(ADA.multiply(BigInteger.valueOf(40)), c.raised());
        assertEquals(ADA.multiply(BigInteger.valueOf(20)), c.contributionOf(Keys.pkh(backers[0])));
        assertThrows(RequireException.class, () -> c.claim(creator), "still running");

        while (System.currentTimeMillis() < deadline + 2_000) Thread.sleep(1_000);
        assertThrows(RequireException.class, () -> c.refund(backers[1]), "goal reached");
        var claimed = c.claim(creator);
        assertEquals(ADA.multiply(BigInteger.valueOf(40)).add(BigInteger.valueOf(CrowdfundClient.SPEC.minAda())),
                claimed.payouts().get(0).lovelace());
    }
}
