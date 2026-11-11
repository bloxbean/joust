package @@package@@;

import org.julclang.state.api.RequireException;

import java.math.BigInteger;

public class CounterMain {
    public static void main(String[] args) throws Exception {
        var runtime = Devnet.runtime();
        var alice = Devnet.fundedAccount(500);
        var bob = Devnet.fundedAccount(500);

        CounterClient counter = CounterClient.deploy(runtime, alice);
        System.out.println("deployed instance " + counter.instanceId());

        counter.increment(bob, BigInteger.valueOf(5));
        counter.increment(alice, BigInteger.TWO);
        System.out.println("count = " + counter.count());               // 7

        try {
            counter.reset(bob);                                         // not the owner
        } catch (RequireException e) {
            System.out.println("refused before submitting: " + e.getMessage());
        }

        var closed = counter.close(alice);                              // the deposit goes back to alice
        System.out.println("closed in " + closed.txHash());
    }
}
