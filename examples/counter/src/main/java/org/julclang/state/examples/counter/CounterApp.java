package org.julclang.state.examples.counter;

import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;

import java.math.BigInteger;

/**
 * What application code looks like with the generated client. Runs against a local Yaci DevKit
 * ({@code ./gradlew :examples:counter:run}).
 */
public class CounterApp {

    public static void main(String[] args) {
        if (!DevKit.isReachable()) {
            System.out.println("Yaci DevKit not reachable; start it first.");
            System.exit(1);
        }
        var runtime = DevKit.runtime();
        var alice = DevKit.fundedAccount(500);
        var bob = DevKit.fundedAccount(500);

        CounterClient counter = CounterClient.deploy(runtime, alice);
        System.out.println("deployed instance " + counter.instanceId() + " at "
                + CounterClient.contract(runtime).address());
        System.out.println("count = " + counter.count());

        counter.increment(bob, BigInteger.valueOf(5));
        System.out.println("bob +5   -> count = " + counter.count());

        counter.increment(alice, BigInteger.TWO);
        System.out.println("alice +2 -> count = " + counter.count());

        try {
            counter.reset(bob);   // not the owner: rejected on the JVM, nothing is submitted
        } catch (RequireException e) {
            System.out.println("bob reset rejected off-chain: " + e.getMessage());
        }

        counter.reset(alice);
        System.out.println("alice reset -> count = " + counter.count());

        var closed = counter.close(alice);
        System.out.println("alice closed the counter in " + closed.txHash() + ", paid back " + closed.payouts());
    }
}
