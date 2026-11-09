package org.julclang.state.examples.bank;

import org.julclang.state.examples.devkit.DevKit;

import java.math.BigInteger;

/** Bank with owned accounts on a local Yaci DevKit ({@code ./gradlew :examples:bank:run}). */
public class BankApp {
    public static void main(String[] args) {
        if (!DevKit.isReachable()) {
            System.out.println("Yaci DevKit not reachable; start it first.");
            System.exit(1);
        }
        var ada = BigInteger.valueOf(1_000_000);
        var runtime = DevKit.runtime();
        var admin = DevKit.fundedAccount(500);
        var ann = DevKit.fundedAccount(200);
        var bank = BankClient.deploy(runtime, admin, ada.multiply(BigInteger.valueOf(20)));
        var acct = bank.open(ann, ada.multiply(BigInteger.valueOf(30)));
        acct.withdraw(ann, ada.multiply(BigInteger.TEN));
        System.out.println("account " + acct.objectId() + " balance " + acct.value().balance());
        acct.close(ann);
        System.out.println("closed");
    }
}
