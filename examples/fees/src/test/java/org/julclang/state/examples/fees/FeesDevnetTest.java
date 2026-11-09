package org.julclang.state.examples.fees;

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

/** A stall deposits each sale's fee into a pot, in one composed transaction, on a live Yaci DevKit (ADR-0011 D3). */
@Tag("devnet")
class FeesDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void salesDepositTheirFeesIntoThePot() {
        var runtime = DevKit.runtime();
        var owner = DevKit.fundedAccount(300);
        var buyer = DevKit.fundedAccount(300);
        var pot = PotClient.deploy(runtime, owner);
        var config = new Stall.Config(new ForeignInstance(HexUtil.decodeHexString(PotClient.contract(runtime).policyId()),
                HexUtil.decodeHexString(pot.instanceId())), BigInteger.valueOf(500));
        var stall = StallClient.deploy(runtime, config, owner);
        BigInteger potBefore = pot.lovelace();
        BigInteger stallBefore = stall.lovelace();

        // A deposit needs its receiver in the same transaction.
        assertThrows(JoustException.class, () -> stall.sell(buyer, ADA.multiply(BigInteger.TEN), ADA.multiply(BigInteger.TEN)));
        // The pot cannot collect nothing.
        assertThrows(RequireException.class, () -> pot.collect(buyer));

        for (int i = 0; i < 2; i++) {
            Compose.of(runtime, buyer).allowSharedSigners()
                    .add(() -> stall.sell(buyer, ADA.multiply(BigInteger.TEN), ADA.multiply(BigInteger.TEN)))
                    .add(() -> pot.collect(buyer))
                    .submit();
        }
        // 5% of each 10 ADA sale went into the pot, the rest stayed with the stall.
        assertEquals(ADA, pot.state().collected());
        assertEquals(BigInteger.TWO, pot.state().deposits());
        assertEquals(potBefore.add(ADA), pot.lovelace());
        assertEquals(stallBefore.add(ADA.multiply(BigInteger.valueOf(19))), stall.lovelace());

        // The receiver may come first in the composition: its call sees the deposits made after it.
        Compose.of(runtime, buyer).allowSharedSigners()
                .add(() -> pot.collect(buyer))
                .add(() -> stall.sell(buyer, ADA.multiply(BigInteger.valueOf(20)), ADA.multiply(BigInteger.valueOf(20))))
                .submit();
        assertEquals(ADA.multiply(BigInteger.TWO), pot.state().collected());
    }
}
