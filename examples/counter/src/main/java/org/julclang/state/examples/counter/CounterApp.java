package org.julclang.state.examples.counter;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import org.julclang.state.examples.counter.generated.CounterClient;
import org.julclang.state.examples.counter.generated.YaciHelper;

import java.math.BigInteger;

/**
 * What application code looks like with the generated client.
 * Runs against a local Yaci DevKit (./gradlew run).
 */
public class CounterApp {

    public static void main(String[] args) {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable; start it first.");
            System.exit(1);
        }
        var backend = YaciHelper.createBackendService();
        var evaluator = YaciHelper.julcEvaluator(backend);

        var alice = new Account(Networks.testnet());
        var bob = new Account(Networks.testnet());
        YaciHelper.topUp(alice.baseAddress(), 100);
        YaciHelper.topUp(bob.baseAddress(), 100);

        // ---------------------------------------------------------------- the interesting part
        CounterClient counter = CounterClient.deploy(backend, evaluator, alice);
        System.out.println("deployed at " + counter.scriptAddress() + " (seed " + counter.seed() + ")");
        System.out.println("count = " + counter.count());

        counter.increment(BigInteger.valueOf(5), bob);
        System.out.println("bob +5   -> count = " + counter.count());

        counter.increment(BigInteger.TWO, alice);
        System.out.println("alice +2 -> count = " + counter.count());

        try {
            counter.reset(bob);  // not the owner: Counter.reset throws on the JVM, nothing is submitted
        } catch (RuntimeException e) {
            System.out.println("bob reset rejected off-chain: " + e.getMessage());
        }

        counter.reset(alice);
        System.out.println("alice reset -> count = " + counter.count());
        // -----------------------------------------------------------------------------------------
    }
}
