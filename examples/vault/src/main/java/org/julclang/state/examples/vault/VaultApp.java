package org.julclang.state.examples.vault;

import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;

import java.math.BigInteger;

/** Vault on a local Yaci DevKit ({@code ./gradlew :examples:vault:run}). */
public class VaultApp {

    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    public static void main(String[] args) throws Exception {
        if (!DevKit.isReachable()) {
            System.out.println("Yaci DevKit not reachable; start it first.");
            System.exit(1);
        }
        var runtime = DevKit.runtime();
        var alice = DevKit.fundedAccount(100);
        var bob = DevKit.fundedAccount(100);

        long unlockAt = System.currentTimeMillis() + 45_000;
        var vault = VaultClient.deploy(runtime, alice, BigInteger.valueOf(unlockAt), ADA.multiply(BigInteger.TEN));
        System.out.println("vault " + vault.instanceId() + ": total " + vault.total() + ", unlocks in 45 s");

        vault.deposit(bob, ADA.multiply(BigInteger.valueOf(5)));
        System.out.println("bob deposited 5 ADA -> total " + vault.total());

        try {
            vault.withdraw(alice, ADA);
        } catch (RequireException e) {
            System.out.println("withdraw before unlock rejected off-chain: " + e.getMessage());
        }
        while (System.currentTimeMillis() < unlockAt + 2_000) Thread.sleep(1_000);

        vault.withdraw(alice, ADA.multiply(BigInteger.valueOf(3)));
        System.out.println("alice withdrew 3 ADA -> total " + vault.total());

        var closed = vault.close(alice);
        System.out.println("closed: paid " + closed.payouts().get(0).lovelace() + " lovelace to alice");
    }
}
