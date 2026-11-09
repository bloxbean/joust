package org.julclang.state.examples.staking;

import org.julclang.core.types.JulcAssocMap;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.Credential;
import org.julclang.ledger.DRep;
import org.julclang.ledger.Delegatee;
import org.julclang.ledger.GovernanceAction;
import org.julclang.ledger.GovernanceActionId;
import org.julclang.ledger.ProposalProcedure;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TxCert;
import org.julclang.ledger.TxId;
import org.julclang.ledger.Vote;
import org.julclang.ledger.Voter;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Send;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-0010: staked state addresses, reward withdrawals, certificates, votes and a guardrail. */
class StakingTest {
    static final byte[] A = StateHarness.fill(28, 0x11);
    static final byte[] B = StateHarness.fill(28, 0x22);
    static final byte[] C = StateHarness.fill(28, 0x33);
    static final byte[] POOL = StateHarness.fill(28, 0x90);
    static final byte[] PAYEE = StateHarness.fill(28, 0xFA);
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    static JulcList<byte[]> keys(byte[]... ks) {
        JulcList<byte[]> l = JulcList.empty();
        for (int i = ks.length - 1; i >= 0; i--) l = l.prepend(ks[i]);
        return l;
    }

    final StakedVault.Config cfg = new StakedVault.Config(keys(A, B, C), BigInteger.TWO, POOL, PAYEE, ADA.multiply(BigInteger.valueOf(1000)));
    final StateHarness<StakedVault.State> h = StateHarness.of(StakedVaultClient.SPEC, cfg);

    @Test
    void stateLivesAtTheStakedAddress() {
        Ctx ctx = h.ctx(A, ADA.multiply(BigInteger.valueOf(50)), BigInteger.ZERO);
        var s = StakedVault.open(cfg, ctx);
        var tx = h.create(new StakedVaultValidator.CreateInstance(h.seed(), ctx), s, ctx);
        System.out.println("[open staked] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        assertTrue(h.stateAddress().stakingCredential().isPresent());
        var live = h.live(s, ADA.multiply(BigInteger.valueOf(50)));
        Ctx d = h.ctx(B, ADA.multiply(BigInteger.TEN), ADA.multiply(BigInteger.valueOf(50)));
        var dep = h.act(live, new StakedVaultValidator.Deposit(d), StakedVault.deposit(s, d), B, d);
        System.out.println("[deposit staked] " + h.assertAccepted(dep) + "  attacks rejected: " + Attacks.assertAllRejected(h, dep));
    }

    @Test
    void withdraw_rewardsToThePayeeOnly_byQuorum() {
        BigInteger amount = ADA.multiply(BigInteger.valueOf(5));
        var sends = new ArrayList<Send>();
        StakedVault.collect(cfg, amount).forEach(sends::add);
        var rewards = new StakedVaultValidator.Rewards(BigInteger.valueOf(74), amount);
        var tx = h.withdrawTx(sends, A, B);
        System.out.println("[withdraw] " + assertOk(h.evaluateWithdraw(tx, rewards, amount)));
        assertRejected(h.evaluateWithdraw(h.withdrawTx(sends, A), rewards, amount), "one signature");
        assertRejected(h.evaluateWithdraw(tx, new StakedVaultValidator.Rewards(BigInteger.valueOf(74), ADA), amount),
                "redeemer amount differs from the withdrawal");
        assertRejected(h.evaluateWithdraw(tx, new StakedVaultValidator.Rewards(BigInteger.ZERO, amount), amount), "marker");
        var thief = new ArrayList<Send>(List.of(new Send(StateHarness.pkhAddress(StateHarness.fill(28, 0xEE)),
                sends.get(0).value(), sends.get(0).datum())));
        assertRejected(h.evaluateWithdraw(h.withdrawTx(thief, A, B), rewards, amount), "rewards to someone else");
        var unclaimed = h.withdrawTx(sends, A, B);
        unclaimed.outputClaims.clear();
        assertRejected(h.evaluateWithdraw(unclaimed, rewards, amount), "send not claimed");
        var zero = new StakedVaultValidator.Rewards(BigInteger.valueOf(74), BigInteger.ZERO);
        System.out.println("[withdraw 0] " + assertOk(h.evaluateWithdraw(h.withdrawTx(List.of(), A, B), zero, BigInteger.ZERO)));
    }

    @Test
    void certificates_onlyRegistrationAndExactDelegation() {
        Credential own = h.ownCredential();
        var quorum = h.withdrawTx(List.of(), A, C);
        assertOk(h.evaluateCertify(quorum, new TxCert.RegStaking(own, Optional.of(BigInteger.TWO))));
        System.out.println("[delegate] " + assertOk(h.evaluateCertify(quorum,
                new TxCert.DelegStaking(own, new Delegatee.Stake(new PubKeyHash(POOL))))));
        assertRejected(h.evaluateCertify(quorum, new TxCert.DelegStaking(own, new Delegatee.Stake(new PubKeyHash(StateHarness.fill(28, 0x91))))),
                "another pool");
        var drep = new DRep.DRepCredential(new Credential.PubKeyCredential(new PubKeyHash(StateHarness.fill(28, 0xDD))));
        assertRejected(h.evaluateCertify(quorum, new TxCert.DelegStaking(own, new Delegatee.StakeVote(new PubKeyHash(POOL), drep))),
                "stake plus a vote delegation to an attacker DRep");
        assertRejected(h.evaluateCertify(quorum, new TxCert.UnRegStaking(own, Optional.of(BigInteger.TWO))),
                "unregistration is refused outright");
        assertRejected(h.evaluateCertify(quorum, new TxCert.UnRegDRep(own, BigInteger.TWO)), "DRep unregistration refused");
        assertRejected(h.evaluateCertify(quorum, new TxCert.AuthHotCommittee(own, new Credential.PubKeyCredential(new PubKeyHash(A)))),
                "committee certificates refused");
        assertRejected(h.evaluateCertify(h.withdrawTx(List.of(), A), new TxCert.DelegStaking(own, new Delegatee.Stake(new PubKeyHash(POOL)))),
                "one signature");
        assertOk(h.evaluateCertify(quorum, new TxCert.RegDRep(own, BigInteger.TWO)));
    }

    @Test
    void votes_asAScriptDRep_byQuorum() {
        var voter = new Voter.DRepVoter(h.ownCredential());
        var action = new GovernanceActionId(new TxId(StateHarness.fill(32, 0x61)), BigInteger.ZERO);
        System.out.println("[vote] " + assertOk(h.evaluateVote(h.withdrawTx(List.of(), B, C), voter, Map.of(action, new Vote.VoteYes()))));
        assertRejected(h.evaluateVote(h.withdrawTx(List.of(), B), voter, Map.of(action, new Vote.VoteYes())), "one signature");
        assertRejected(h.evaluateVote(h.withdrawTx(List.of(), B, C), voter, Map.of()), "no votes for this voter");
    }

    @Test
    void guardrail_capsTreasuryWithdrawals() {
        var ret = new Credential.PubKeyCredential(new PubKeyHash(A));
        var small = new GovernanceAction.TreasuryWithdrawals(JulcAssocMap.<Credential, BigInteger>empty().insert(ret, ADA.multiply(BigInteger.TEN)),
                Optional.of(new ScriptHash(h.policy())));
        var big = new GovernanceAction.TreasuryWithdrawals(JulcAssocMap.<Credential, BigInteger>empty().insert(ret, ADA.multiply(BigInteger.valueOf(5000))),
                Optional.of(new ScriptHash(h.policy())));
        var tx = h.withdrawTx(List.of());
        System.out.println("[guardrail] " + assertOk(h.evaluatePropose(tx, new ProposalProcedure(ADA, ret, small))));
        assertRejected(h.evaluatePropose(tx, new ProposalProcedure(ADA, ret, big)), "over the cap");
        assertOk(h.evaluatePropose(tx, new ProposalProcedure(ADA, ret, new GovernanceAction.InfoAction())));
    }

    static StateHarness.Verdict assertOk(StateHarness.Verdict v) {
        assertTrue(v.accepted(), "expected accepted:\n" + v);
        return v;
    }

    static void assertRejected(StateHarness.Verdict v, String what) {
        assertFalse(v.accepted(), "expected rejection (" + what + "):\n" + v);
    }
}
