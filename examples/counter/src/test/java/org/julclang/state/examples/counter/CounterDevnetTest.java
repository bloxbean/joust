package org.julclang.state.examples.counter;

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

/** End to end on a live Yaci DevKit through the generated client. Skipped when the DevKit is not running. */
@Tag("devnet")
class CounterDevnetTest {

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void lifecycle() {
        var runtime = DevKit.runtime();
        var alice = DevKit.fundedAccount(500);
        var bob = DevKit.fundedAccount(500);

        var counter = CounterClient.deploy(runtime, alice);
        assertEquals(BigInteger.ZERO, counter.count());
        assertArrayEquals(Keys.pkh(alice), counter.state().owner());
        assertEquals(BigInteger.valueOf(CounterClient.SPEC.minAda()), counter.lovelace());

        counter.increment(bob, BigInteger.valueOf(5));
        counter.increment(alice, BigInteger.TWO);
        assertEquals(BigInteger.valueOf(7), counter.count());

        // Rejected on the JVM before anything is submitted.
        assertThrows(RequireException.class, () -> counter.increment(bob, BigInteger.ZERO));
        assertThrows(RequireException.class, () -> counter.reset(bob));

        // Reconnect by id from a fresh client.
        var again = CounterClient.at(runtime, counter.instanceId());
        again.reset(alice);
        assertEquals(BigInteger.ZERO, counter.count());

        var closed = counter.close(alice);
        assertEquals(1, closed.payouts().size());
        assertEquals(BigInteger.valueOf(CounterClient.SPEC.minAda()), closed.payouts().get(0).lovelace());
        assertThrows(InstanceNotFoundException.class, counter::state);
    }
}
