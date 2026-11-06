package org.julclang.state.examples.vault;

import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.InstanceNotFoundException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("devnet")
class VaultDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void lifecycle() throws Exception {
        var runtime = DevKit.runtime();
        var owner = DevKit.fundedAccount(100);
        var bob = DevKit.fundedAccount(50);
        long unlockAt = System.currentTimeMillis() + 40_000;

        var vault = VaultClient.deploy(runtime, owner, BigInteger.valueOf(unlockAt), ADA.multiply(BigInteger.TEN));
        vault.deposit(bob, ADA.multiply(BigInteger.valueOf(5)));
        assertEquals(ADA.multiply(BigInteger.valueOf(15)), vault.total());
        assertEquals(ADA.multiply(BigInteger.valueOf(15)).add(BigInteger.valueOf(VaultClient.SPEC.minAda())), vault.lovelace());

        assertThrows(RequireException.class, () -> vault.withdraw(owner, ADA), "locked");
        while (System.currentTimeMillis() < unlockAt + 2_000) Thread.sleep(1_000);
        assertThrows(RequireException.class, () -> vault.withdraw(bob, ADA), "not the owner");

        var w = vault.withdraw(owner, ADA.multiply(BigInteger.valueOf(4)));
        assertEquals(ADA.multiply(BigInteger.valueOf(4)), w.payouts().get(0).lovelace());
        assertEquals(ADA.multiply(BigInteger.valueOf(11)), vault.total());

        var closed = vault.close(owner);
        assertEquals(ADA.multiply(BigInteger.valueOf(11)).add(BigInteger.valueOf(VaultClient.SPEC.minAda())),
                closed.payouts().get(0).lovelace());
        assertThrows(InstanceNotFoundException.class, vault::state);
    }
}
