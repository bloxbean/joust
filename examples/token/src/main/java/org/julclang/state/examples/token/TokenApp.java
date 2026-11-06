package org.julclang.state.examples.token;

import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Keys;

import java.math.BigInteger;

/** Token on a local Yaci DevKit ({@code ./gradlew :examples:token:run}). */
public class TokenApp {

    public static void main(String[] args) {
        if (!DevKit.isReachable()) {
            System.out.println("Yaci DevKit not reachable; start it first.");
            System.exit(1);
        }
        var runtime = DevKit.runtime();
        var admin = DevKit.fundedAccount(500);
        var ann = DevKit.fundedAccount(50);
        var ben = DevKit.fundedAccount(50);

        var token = TokenClient.deploy(runtime, admin);
        System.out.println("token " + token.instanceId() + " deployed (script read by reference)");
        token.mint(admin, Keys.pkh(ann), BigInteger.valueOf(1_000));
        token.mint(admin, Keys.pkh(ben), BigInteger.valueOf(500));
        token.transfer(ann, Keys.pkh(ben), BigInteger.valueOf(250));
        System.out.println("ann " + token.balanceOf(Keys.pkh(ann)) + ", ben " + token.balanceOf(Keys.pkh(ben))
                + ", supply " + token.supply());
    }
}
