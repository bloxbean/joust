package org.julclang.state.examples.treasury;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.clientlib.JulcScriptLoader;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.Credential;
import org.julclang.ledger.ScriptHash;
import org.julclang.state.api.Approval;
import org.julclang.state.api.Approvals;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Authorization;
import org.julclang.state.runtime.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The treasury on a live Yaci DevKit (ADR-0009): quorum payments, a guardian script freeze, authenticated events. */
@Tag("devnet")
class TreasuryDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void quorumGuardianEvents() throws Exception {
        var runtime = DevKit.runtime();
        Account[] members = {DevKit.fundedAccount(300), DevKit.fundedAccount(300), DevKit.fundedAccount(300)};
        Arrays.sort(members, Comparator.comparing(a -> HexUtil.encodeHexString(Keys.pkh(a))));
        var a = members[0];
        var b = members[1];
        var owner = DevKit.fundedAccount(100);
        var bob = DevKit.fundedAccount(10);

        var guardian = JulcScriptLoader.load(Guardian.class, new BytesPlutusData(Keys.pkh(owner)));
        String reward = DevKit.registerScriptStake(guardian, owner);
        var cfg = new Treasury.Config(new Credential.ScriptCredential(new ScriptHash(guardian.getScriptHash())),
                ADA.multiply(BigInteger.valueOf(50)));
        JulcList<byte[]> council = JulcList.empty();
        for (int i = members.length - 1; i >= 0; i--) council = council.prepend(Keys.pkh(members[i]));

        var t = TreasuryClient.deploy(runtime, cfg, a, council, BigInteger.TWO, ADA.multiply(BigInteger.valueOf(200)));
        assertEquals(TreasuryClient.policyIdFor(cfg), t.instance().contract().policyId(), "the params pin the script");

        var paid = t.pay(a, Keys.pkh(bob), ADA.multiply(BigInteger.TEN), b);
        assertEquals(new Treasury.Paid(Keys.pkh(bob), ADA.multiply(BigInteger.TEN)).amount(),
                paid.events(Treasury.Paid.class).get(0).amount(), "the authenticated event");
        assertThrows(RuntimeException.class, () -> t.pay(a, Keys.pkh(bob), ADA), "one signature is not a quorum");

        byte[] policy = HexUtil.decodeHexString(t.instance().contract().policyId());
        byte[] id = HexUtil.decodeHexString(t.instanceId());
        JulcList<Approval> approvals = JulcList.<Approval>empty().prepend(new Approval(policy, id, BigInteger.valueOf(3)));
        var frozen = t.freeze(owner, true, Authorization.script(reward, guardian, new Approvals(approvals), owner));
        assertTrue(frozen.state().frozen());
        assertThrows(RuntimeException.class, () -> t.pay(a, Keys.pkh(bob), ADA, b), "frozen");
        t.freeze(owner, false, Authorization.script(reward, guardian, new Approvals(approvals), owner));

        t.rotate(a, JulcList.<byte[]>empty().prepend(Keys.pkh(b)).prepend(Keys.pkh(a)), BigInteger.TWO, a, b);
        t.close(a, Keys.pkh(a), b);
    }
}
