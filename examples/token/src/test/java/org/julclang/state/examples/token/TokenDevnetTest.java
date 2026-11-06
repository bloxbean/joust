package org.julclang.state.examples.token;

import com.bloxbean.cardano.client.account.Account;
import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.InstanceNotFoundException;
import org.julclang.state.runtime.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Token with MerkleMap balances end to end on a live Yaci DevKit (reference script, proofs, mirror). */
@Tag("devnet")
class TokenDevnetTest {

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void lifecycle() {
        var runtime = DevKit.runtime();
        var admin = DevKit.fundedAccount(500);
        Account[] holders = {DevKit.fundedAccount(50), DevKit.fundedAccount(50), DevKit.fundedAccount(50)};

        var token = TokenClient.deploy(runtime, admin);
        for (int i = 0; i < holders.length; i++) {
            token.mint(admin, Keys.pkh(holders[i]), BigInteger.valueOf(100L * (i + 1)));
        }
        assertEquals(BigInteger.valueOf(600), token.supply());

        token.transfer(holders[0], Keys.pkh(holders[1]), BigInteger.valueOf(40));
        token.transfer(holders[2], Keys.pkh(holders[0]), BigInteger.valueOf(300));   // empties holder 2: key deleted
        assertEquals(BigInteger.valueOf(360), token.balanceOf(Keys.pkh(holders[0])));
        assertEquals(BigInteger.valueOf(240), token.balanceOf(Keys.pkh(holders[1])));
        assertEquals(BigInteger.ZERO, token.balanceOf(Keys.pkh(holders[2])));

        assertThrows(RequireException.class, () -> token.transfer(holders[2], Keys.pkh(holders[0]), BigInteger.ONE));
        assertThrows(RequireException.class, () -> token.close(admin), "supply not zero");

        token.burn(holders[0], BigInteger.valueOf(360));
        token.burn(holders[1], BigInteger.valueOf(240));
        assertEquals(BigInteger.ZERO, token.supply());
        token.close(admin);
        assertThrows(InstanceNotFoundException.class, token::state);
    }
}
