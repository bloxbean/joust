package org.julclang.state.examples.crowdfund;

import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Keys;

import java.math.BigInteger;

/** Crowdfund on a local Yaci DevKit ({@code ./gradlew :examples:crowdfund:run}): a campaign that misses its goal. */
public class CrowdfundApp {

    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    public static void main(String[] args) throws Exception {
        if (!DevKit.isReachable()) {
            System.out.println("Yaci DevKit not reachable; start it first.");
            System.exit(1);
        }
        var runtime = DevKit.runtime();
        var creator = DevKit.fundedAccount(50);
        var ann = DevKit.fundedAccount(100);
        var ben = DevKit.fundedAccount(100);

        long deadline = System.currentTimeMillis() + 60_000;
        var campaign = CrowdfundClient.deploy(runtime, creator, ADA.multiply(BigInteger.valueOf(100)),
                BigInteger.valueOf(deadline), ADA.multiply(BigInteger.TWO));
        System.out.println("campaign " + campaign.instanceId() + " launched; goal 100 ADA");

        campaign.contribute(ann, ADA.multiply(BigInteger.TEN));
        campaign.contribute(ben, ADA.multiply(BigInteger.valueOf(5)));
        campaign.contribute(ann, ADA.multiply(BigInteger.valueOf(3)));
        System.out.println("raised " + campaign.raised() + "; ann " + campaign.contributionOf(Keys.pkh(ann)));

        while (System.currentTimeMillis() < deadline + 2_000) Thread.sleep(1_000);
        campaign.refund(ann);
        campaign.refund(ben);
        System.out.println("goal missed: both backers refunded; raised now " + campaign.raised());
        campaign.close(creator);
        System.out.println("creator closed the campaign");
    }
}
