package org.julclang.state.examples.escrow;

import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Keys;

import java.math.BigInteger;

/** Escrow on a local Yaci DevKit ({@code ./gradlew :examples:escrow:run}). */
public class EscrowApp {

    public static void main(String[] args) {
        if (!DevKit.isReachable()) {
            System.out.println("Yaci DevKit not reachable; start it first.");
            System.exit(1);
        }
        var runtime = DevKit.runtime();
        var buyer = DevKit.fundedAccount(100);
        var seller = DevKit.fundedAccount(20);
        var arbiter = DevKit.fundedAccount(20);
        var price = BigInteger.valueOf(25_000_000);
        var deadline = BigInteger.valueOf(System.currentTimeMillis() + 3_600_000);

        var deal = EscrowClient.deploy(runtime, buyer, Keys.pkh(seller), Keys.pkh(arbiter), deadline, price);
        System.out.println("escrow " + deal.instanceId() + " holds " + deal.lovelace() + " lovelace");

        try {
            deal.release(seller);   // the seller can't pay themselves
        } catch (RequireException e) {
            System.out.println("seller release rejected off-chain: " + e.getMessage());
        }
        var r = deal.release(buyer);
        System.out.println("buyer released: seller received " + r.payouts().get(0).lovelace() + " lovelace in " + r.txHash());
    }
}
