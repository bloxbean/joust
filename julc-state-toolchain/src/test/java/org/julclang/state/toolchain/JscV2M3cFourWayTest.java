package org.julclang.state.toolchain;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.Value;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.dsl.v2.Interpreter;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.CredV;
import org.julclang.state.dsl.v2.Interpreter.DataV;
import org.julclang.state.dsl.v2.Interpreter.GovV;
import org.julclang.state.dsl.v2.Interpreter.LedgerMapV;
import org.julclang.state.dsl.v2.Interpreter.MapV;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.dsl.v2.Interpreter.Tx;
import org.julclang.state.dsl.v2.JscModules;
import org.julclang.state.runtime.StateContractHandle;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.julclang.state.toolchain.FourWay.rec;
import static org.julclang.state.toolchain.JscV2M1aFourWayTest.*;
import static org.julclang.state.toolchain.PurposeLegs.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3c (J3, J11): the script purposes (ADR-0010) and migration (ADR-0011 D4) on all five legs: both reference
 * interpreters (semantics §2.10, §2.11), the generated Java on the JVM, the transition program (a creation, a destroy, a
 * withdrawal without a Ctx; certify, vote and propose only decide on chain) and the validator, run for the purpose or
 * spent and minted as the runtime builds the migration. {@code StakedVault} is the port of examples/staking and
 * {@code CounterV1}/{@code CounterV2} of examples/upgrade: their vectors also run on the Java examples' methods.
 * {@code Steward} and {@code TallyV1}/{@code TallyV2} are the coverage contracts. Refusals are asserted by class: the
 * purposes' rules ({@code purpose:certificate}, {@code purpose:voter}, {@code purpose:votes}, {@code purpose:withdraw},
 * {@code purpose:certify}, {@code purpose:vote}, {@code purpose:propose}), the migration's halves ({@code migrate:to},
 * {@code migrate:from}), the adopted state ({@code input:old}), the modifiers, the requires and the output checks.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JscV2M3cFourWayTest {

    static final Map<String, FourWay> LEGS = new LinkedHashMap<>();
    static final Map<String, String> SOURCES = new LinkedHashMap<>();
    static final Map<String, Map<String, String>> TRANSITIONS = new LinkedHashMap<>();
    static JscModules MODULES;
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] A = fill(0x11), B = fill(0x22), C = fill(0x33), EVE = fill(0xEE), PAYEE = fill(0xFA), POOL = fill(0x90),
            GOV = fill(0x60), GOVERNOR = fill(0x61), ADMIN = fill(0xAD);
    static final HexFormat HEX = HexFormat.of();

    static BigInteger ada(long v) {
        return ADA.multiply(BigInteger.valueOf(v));
    }

    static String ok(String outcome) {
        assertTrue(outcome.startsWith("ok:"), outcome);
        return outcome;
    }

    static List<Object> keys(byte[]... ks) {
        var out = new ArrayList<Object>();
        for (byte[] k : ks) out.add(Bytes.of(k));
        return out;
    }

    static FourWay legs(String name, Rec params) throws Exception {
        return new FourWay(name, SOURCES.get(name + ".jsc"), TRANSITIONS.get(name), params, MODULES);
    }

    @BeforeAll
    static void build() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        if (!LEGS.isEmpty()) return;
        Map<String, String> files = new LinkedHashMap<>();
        for (String c : List.of("StakedVault", "CounterV1", "CounterV2", "Steward", "Guardrail", "TallyV1", "TallyV2", "StakedCounterV1", "StakedCounterV2")) {
            SOURCES.put(c + ".jsc", Files.readString(DSL.resolve(c + ".jsc")));
            files.put("org/julclang/state/examples/dsl/" + c + ".jsc", SOURCES.get(c + ".jsc"));
        }
        var r = Toolchain.build(files, Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        MODULES = JscModules.of(SOURCES);
        for (var c : r.contracts()) {
            System.out.println("[M3c] " + c.name() + ": " + c.scriptSizeBytes() + " bytes");
            TRANSITIONS.put(c.name(), c.transitions());
        }
        LEGS.put("StakedVault", legs("StakedVault", rec("Config", keys(A, B, C), n(2), Bytes.of(POOL), Bytes.of(PAYEE), ada(1000))));
        LEGS.put("CounterV1", legs("CounterV1", null));
        LEGS.put("CounterV2", legs("CounterV2", rec("Config", Bytes.of(LEGS.get("CounterV1").harness.policy()))));
    }

    static void report(FourWay f, PurposeLegs p) {
        System.out.println("[M3c five-way] " + f.name + ": " + f.calls + " calls, interpreter = JVM = transition program = validator (" + f.refusals
                + " refusals, " + f.validatorRuns + " validator runs); purpose refusals: " + (p == null ? 0 : p.purposeRefusals) + ", migration"
                + " refusals: " + f.migrateRefusals + ", modifier refusals: " + f.modifierRefusals + ", invariant refusals: " + f.invariantRefusals
                + ", transaction-level: " + f.transactionLevelRefusals + "; mutants rejected: " + f.mutantsRejected + ", attacks rejected: "
                + f.attacksRejected + (f.javaDiffs.isEmpty() ? "" : "; differs from the Java example on: " + f.javaDiffs));
        assertTrue(f.refusals > 0 && f.refusals < f.calls, "accepted and refused calls");
        assertTrue(f.mutantsRejected > 0, "mutation coverage");
    }

    // ------------------------------------------------------------------ the staking port (examples/staking)

    @Test
    @Order(1)
    void staking() throws Exception {
        var f = LEGS.get("StakedVault").reference("org.julclang.state.examples.staking", "open");
        var p = new PurposeLegs(f);
        byte[] own = f.harness.policy();
        CredV self = scriptCred(own);
        // The instance: at the base address staked to its own script (I36); deposits; a close by the council's quorum.
        assertTrue(f.interp.stakeSelf() && f.harness.stateAddress().stakingCredential().isPresent());
        ok(f.check("init", null, List.of(), at(A, ada(50).longValue(), 0), A));
        Rec s = rec("State", ada(50));
        ok(f.check("deposit", s, List.of(), at(B, ada(10).longValue(), ada(50).longValue()), B));
        assertEquals("refused", f.check("deposit", s, List.of(), at(B, 0, ada(50).longValue()), B));
        assertTrue(f.lastRefusal.endsWith(":deposit some ADA"), f.lastRefusal);
        ok(f.as(List.of(A, B), null).check("close", s, List.of(), at(A, 0, ada(52).longValue()), A));
        f.modifierRefused("close", s, List.of(), at(A, 0, ada(52).longValue()), A);
        f.mutants("deposit", s, List.of(), at(B, ada(10).longValue(), ada(50).longValue()), B);
        f.as(List.of(A, B), null).mutants("close", s, List.of(), at(A, 0, ada(52).longValue()), A);

        // ---- withdraw (I37, A36, K41, K42): the rewards to the payee only, by the council's quorum; zero withdraws nothing
        ok(p.as(List.of(A, B), List.of()).check("collect", List.of(ada(5)), null));
        ok(p.as(List.of(A, B), List.of()).check("collect", List.of(BigInteger.ZERO), null));
        p.as(List.of(A), List.of()).refused("modifier:quorum params.council params.quorum", "collect", List.of(ada(5)), null);
        p.as(List.of(A, B), List.of()).withdrawMutants("collect", List.of(ada(5)), null);

        // ---- certify (I38, A37, K43): registration and stake-only delegation to the pool; the filter before the handler
        p.as(List.of(A, C), List.of());
        ok(p.check("certificate", List.of(cert("RegStaking", self, Opt.some(n(2_000_000)))), null));
        ok(p.as(List.of(A, C), List.of()).check("certificate", List.of(cert("RegStaking", self, Opt.NONE)), null));
        ok(p.as(List.of(A, C), List.of()).check("certificate", List.of(cert("DelegStaking", self, gov("Delegatee", "Stake", Bytes.of(POOL)))), null));
        ok(p.as(List.of(A, C), List.of()).check("certificate", List.of(cert("RegDeleg", self, gov("Delegatee", "Stake", Bytes.of(POOL)), n(2))), null));
        ok(p.as(List.of(A, C), List.of()).check("certificate", List.of(cert("RegDRep", self, n(2))), null));
        ok(p.as(List.of(A, C), List.of()).check("certificate", List.of(cert("UpdateDRep", self)), null));
        GovV attacker = gov("DRep", "DRepCredential", keyCred(fill(0xDD)));
        p.as(List.of(A, C), List.of()).refused("purpose:certify", "certificate", List.of(cert("DelegStaking", self, gov("Delegatee", "Stake", Bytes.of(fill(0x91))))), null);
        p.as(List.of(A, C), List.of()).refused("purpose:certify", "certificate",
                List.of(cert("DelegStaking", self, gov("Delegatee", "StakeVote", Bytes.of(POOL), attacker))), null);
        p.as(List.of(A, C), List.of()).refused("purpose:certify", "certificate", List.of(cert("RegDeleg", self, gov("Delegatee", "Vote", attacker), n(2))), null);
        // Refused outright, whatever the handler: unregistration, DRep unregistration, committee and pool certificates.
        p.as(List.of(A, C), List.of()).refused("purpose:certificate", "certificate", List.of(cert("UnRegStaking", self, Opt.some(n(2)))), null);
        p.as(List.of(A, C), List.of()).refused("purpose:certificate", "certificate", List.of(cert("UnRegDRep", self, n(2))), null);
        p.as(List.of(A, C), List.of()).refused("purpose:certificate", "certificate", List.of(cert("AuthHotCommittee", self, keyCred(A))), null);
        p.as(List.of(A, C), List.of()).refused("purpose:certificate", "certificate", List.of(cert("ResignColdCommittee", self)), null);
        p.as(List.of(A, C), List.of()).refused("purpose:certificate", "certificate", List.of(cert("PoolRegister", Bytes.of(POOL), Bytes.of(fill(0x92)))), null);
        p.as(List.of(A, C), List.of()).refused("purpose:certificate", "certificate", List.of(cert("PoolRetire", Bytes.of(POOL), n(300))), null);
        // A key credential of the script's own bytes is not the script's (K43).
        p.as(List.of(A, C), List.of()).refused("purpose:certificate", "certificate", List.of(cert("RegStaking", keyCred(own), Opt.NONE)), null);
        p.as(List.of(A), List.of()).refused("modifier:quorum params.council params.quorum", "certificate",
                List.of(cert("DelegStaking", self, gov("Delegatee", "Stake", Bytes.of(POOL)))), null);

        // ---- vote (A38, K44): as the script DRep, every vote of its own, by the quorum
        GovV dRep = voter("DRepVoter", self);
        GovV a0 = actionId(fill(0x61), 0), a1 = actionId(fill(0x61), 1);
        ok(p.as(List.of(B, C), List.of()).check("vote", List.of(dRep, List.of(pair(a0, vote("VoteYes")))), null));
        ok(p.as(List.of(B, C), List.of()).check("vote", List.of(dRep, List.of(pair(a0, vote("VoteNo")), pair(a1, vote("Abstain")))), null));
        ok(p.as(List.of(B, C), List.of()).check("vote", List.of(voter("CommitteeVoter", self), List.of(pair(a0, vote("VoteYes")))), null));
        p.as(List.of(B), List.of()).refused("modifier:quorum params.council params.quorum", "vote", List.of(dRep, List.of(pair(a0, vote("VoteYes")))), null);
        p.as(List.of(B, C), List.of()).refused("purpose:votes", "vote", List.of(dRep, List.of()), null);
        p.as(List.of(B, C), List.of()).refused("purpose:voter", "vote", List.of(voter("StakePoolVoter", Bytes.of(POOL)), List.of(pair(a0, vote("VoteYes")))), null);
        p.as(List.of(B, C), List.of()).refused("purpose:voter", "vote", List.of(voter("DRepVoter", keyCred(own)), List.of(pair(a0, vote("VoteYes")))), null);

        // ---- propose (A38, K45): the guardrail caps the treasury withdrawals in total
        CredV ret = keyCred(A);
        var small = action("TreasuryWithdrawals", ledgerMap(List.of(ret), List.of(ada(10))), Opt.some(Bytes.of(own)));
        ok(p.as(List.of(), List.of()).check("proposal", List.of(proposal(ADA, ret, small)), null));
        ok(p.check("proposal", List.of(proposal(ADA, ret, action("TreasuryWithdrawals", ledgerMap(List.of(ret), List.of(ada(1000))), Opt.NONE))), null));
        p.refused("purpose:propose", "proposal", List.of(proposal(ADA, ret, action("TreasuryWithdrawals",
                ledgerMap(List.of(ret), List.of(ada(1000).add(BigInteger.ONE))), Opt.NONE))), null);
        p.refused("purpose:propose", "proposal", List.of(proposal(ADA, ret, action("TreasuryWithdrawals", ledgerMap(List.of(ret), List.of(ada(5000))), Opt.NONE))), null);
        // Each below the cap, the sum over it (K45); several credentials, large totals.
        p.refused("purpose:propose", "proposal", List.of(proposal(ADA, ret, action("TreasuryWithdrawals",
                ledgerMap(List.of(keyCred(A), keyCred(B), scriptCred(C)), List.of(ada(400), ada(400), ada(400))), Opt.NONE))), null);
        ok(p.check("proposal", List.of(proposal(ADA, ret, action("InfoAction"))), null));
        ok(p.check("proposal", List.of(proposal(ADA, ret, action("ParameterChange", Opt.NONE, new DataV("d87980"), Opt.NONE))), null));
        ok(p.check("proposal", List.of(proposal(ADA, ret, action("NoConfidence", Opt.some(a0)))), null));
        JsLeg.check(f);
        report(f, p);
    }

    // ------------------------------------------------------------------ coverage: Steward (the purposes)

    static final BigInteger DEADLINE = T0.add(n(600_000));

    static Rec stewardParams(CredV gov) {
        return rec("Config", gov, keys(A, B, C), n(2), Bytes.of(POOL), Bytes.of(PAYEE), DEADLINE, Bytes.of(fill(0x6B)));
    }

    /**
     * K46: an approval replayed with one component of its identity changed: another policy, another purpose's action, the
     * target of another script info. Each is refused by the validator alone (the interpreters see the approver, §2.6).
     */
    static int approvalMutants(PurposeLegs p, String method, List<Object> args, Tx t, List<org.julclang.ledger.ScriptInfo> others, int tag)
            throws Exception {
        int n = 0;
        var variants = new ArrayList<org.julclang.state.api.Approval>();
        byte[] policy = p.f.harness.policy();
        byte[] honest = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash224(org.julclang.core.cbor.PlutusDataCborEncoder.encode(
                new org.julclang.ledger.ScriptInfo.RewardingScript(p.f.harness.ownCredential()).toPlutusData()));
        variants.add(new org.julclang.state.api.Approval(fill(0x0B), honest, n(1000 + tag)));
        for (int other : List.of(2, 3, 4, 0)) if (other != tag) variants.add(new org.julclang.state.api.Approval(policy, honest, n(1000 + other)));
        for (var info : others) {
            byte[] target = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash224(org.julclang.core.cbor.PlutusDataCborEncoder.encode(info.toPlutusData()));
            variants.add(new org.julclang.state.api.Approval(policy, target, n(1000 + tag)));
        }
        for (var a : variants) {
            var data = StateHarness.data(new org.julclang.state.api.Approvals(JulcList.<org.julclang.state.api.Approval>empty().prepend(a)));
            p.mutate = tx -> {
                var c = tx.copy();
                c.withdrawals.replaceAll((k, v) -> data);
                return c;
            };
            var v = p.mutantVerdict(method, args, t);
            assertFalse(v.accepted(), p.f.name + "." + method + ": a replayed approval " + a + " is accepted\n" + v);
            n++;
        }
        p.f.mutantsRejected += n;
        p.purposeMutants += n;
        return n;
    }

    @Test
    @Order(3)
    void steward() throws Exception {
        var f = legs("Steward", stewardParams(keyCred(GOV)));
        var p = new PurposeLegs(f);
        byte[] own = f.harness.policy();
        CredV self = scriptCred(own);
        ok(f.check("init", null, List.of(), at(ADMIN, 0, 0), ADMIN));
        Rec s = rec("State", Bytes.of(ADMIN));
        ok(f.check("close", s, List.of(), at(ADMIN, 0, ada(3).longValue()), ADMIN));
        f.modifierRefused("close", s, List.of(), at(EVE, 0, ada(3).longValue()), EVE);

        // ---- withdraw with arguments and a Ctx (K41): the key credential signs; the window before the deadline
        Tx byGov = at(GOV, 0, 0);
        ok(p.as(List.of(GOV), List.of()).check("take", List.of(ada(10), Bytes.of(B), ada(1)), byGov));
        ok(p.as(List.of(GOV), List.of()).check("take", List.of(ada(10), Bytes.of(B), ada(10)), byGov));
        ok(p.as(List.of(GOV), List.of()).check("take", List.of(BigInteger.ZERO, Bytes.of(B), BigInteger.ZERO), byGov));
        assertEquals("refused", p.as(List.of(GOV), List.of()).check("take", List.of(ada(10), Bytes.of(B), ada(11)), byGov));
        assertTrue(f.lastRefusal.endsWith(":the tip is within the amount"), f.lastRefusal);
        assertEquals("refused", p.as(List.of(GOV), List.of()).check("take", List.of(ada(10), Bytes.of(B), ada(1)),
                FourWay.tx(GOV, 0, 0, DEADLINE.subtract(n(60_000)), DEADLINE.add(BigInteger.ONE))));
        assertTrue(f.lastRefusal.endsWith(":too late to withdraw"), f.lastRefusal);
        ok(p.as(List.of(GOV), List.of()).check("take", List.of(ada(10), Bytes.of(B), ada(1)), FourWay.tx(GOV, 0, 0, DEADLINE.subtract(n(60_000)), DEADLINE)));
        p.as(List.of(GOV), List.of()).refused("input:to", "take", List.of(ada(10), Bytes.of(new byte[27]), ada(1)), byGov);
        // I37: the sends allocate the amount exactly: a one-lovelace tip is not sent, so the allocation is short (purpose:withdraw).
        p.as(List.of(GOV), List.of()).refused("purpose:withdraw", "take", List.of(ada(10), Bytes.of(B), BigInteger.ONE), byGov);
        // Review C1: a negative send (a tip of -1) cancelling one lovelace of the other: the total is the amount, but each
        // send must hold a positive quantity, as the validator's sends do (purpose:withdraw on every leg). The control: a
        // tip of 2 lovelace, two positive sends with the same total, accepted.
        p.as(List.of(GOV), List.of()).refused("purpose:withdraw", "take", List.of(ada(10), Bytes.of(B), BigInteger.ONE.negate()), byGov);
        ok(p.as(List.of(GOV), List.of()).check("take", List.of(ada(10), Bytes.of(B), BigInteger.TWO), byGov));
        p.as(List.of(EVE), List.of()).refused("modifier:authorized params.gov", "take", List.of(ada(10), Bytes.of(B), ada(1)), at(EVE, 0, 0));
        p.as(List.of(GOV), List.of()).withdrawMutants("take", List.of(ada(10), Bytes.of(B), ada(1)), byGov);
        // K41: the withdrawal's Ctx: its balance is the amount, received 0, the caller signs (each one step away).
        for (var mut : List.<java.util.function.UnaryOperator<org.julclang.core.PlutusData>>of(
                d -> ctxField(d, 2, ada(10).add(BigInteger.ONE)), d -> ctxField(d, 1, BigInteger.ONE))) {
            p.as(List.of(GOV), List.of()).redeemerMutant = mut;
            assertFalse(p.mutantVerdict("take", List.of(ada(10), Bytes.of(B), ada(1)), byGov).accepted(), "a Ctx one step away");
            f.mutantsRejected++;
        }

        // ---- certify: the handler allows everything it sees; the built-in filter refuses before it (K43, I38)
        p.as(List.of(GOV), List.of());
        GovV stake = gov("Delegatee", "Stake", Bytes.of(POOL)), abstain = gov("DRep", "AlwaysAbstain");
        for (GovV c : List.of(cert("RegStaking", self, Opt.some(n(2))), cert("RegStaking", self, Opt.NONE), cert("DelegStaking", self, stake),
                cert("DelegStaking", self, gov("Delegatee", "Vote", abstain)), cert("RegDeleg", self, stake, n(2)), cert("RegDRep", self, n(2)),
                cert("UpdateDRep", self))) {
            ok(p.as(List.of(GOV), List.of()).check("anything", List.of(c), null));
        }
        for (GovV c : List.of(cert("DelegStaking", self, gov("Delegatee", "Stake", Bytes.of(fill(0x91)))),
                cert("DelegStaking", self, gov("Delegatee", "Vote", gov("DRep", "DRepCredential", keyCred(EVE)))),
                cert("DelegStaking", self, gov("Delegatee", "StakeVote", Bytes.of(POOL), abstain)),
                cert("RegDeleg", self, stake, n(-1)), cert("RegDRep", self, n(0)))) {
            p.as(List.of(GOV), List.of()).refused("purpose:certify", "anything", List.of(c), null);
        }
        for (GovV c : List.of(cert("UnRegStaking", self, Opt.NONE), cert("UnRegDRep", self, n(2)), cert("PoolRegister", Bytes.of(POOL), Bytes.of(A)),
                cert("PoolRetire", Bytes.of(POOL), n(9)), cert("AuthHotCommittee", self, keyCred(A)), cert("ResignColdCommittee", self),
                cert("DelegStaking", keyCred(own), stake))) {
            p.as(List.of(GOV), List.of()).refused("purpose:certificate", "anything", List.of(c), null);
        }

        // ---- vote (K44): every vote of exactly this voter, each decided on its action and its vote
        GovV me = voter("DRepVoter", self), them = voter("DRepVoter", scriptCred(fill(0x5B)));
        GovV a0 = actionId(fill(0x61), 0), a1 = actionId(fill(0x61), 1), banned = actionId(fill(0x6B), 0);
        ok(p.as(List.of(A, B), List.of()).check("judge", List.of(me, List.of(pair(a0, vote("VoteNo")), pair(a1, vote("Abstain")))), null));
        // The second vote refused: a handler run on the first entry only would accept it.
        p.as(List.of(A, B), List.of()).refused("purpose:vote", "judge", List.of(me, List.of(pair(a0, vote("VoteYes")), pair(a1, vote("VoteYes")))), null);
        p.as(List.of(A, B), List.of()).refused("purpose:vote", "judge", List.of(me, List.of(pair(banned, vote("VoteYes")))), null);
        // Another voter's accepted votes beside this voter's refused one, and beside none of this voter's.
        p.as(List.of(A, B), List.of()).otherVoter = them;
        p.otherVotes = List.of(pair(a0, vote("VoteYes")));
        p.refused("purpose:vote", "judge", List.of(me, List.of(pair(a1, vote("VoteYes")))), null);
        p.as(List.of(A, B), List.of()).otherVoter = them;
        p.otherVotes = List.of(pair(a0, vote("VoteYes")));
        p.dropOwnVotes = true;
        p.refused("purpose:votes", "judge", List.of(me, List.of()), null);
        p.as(List.of(A), List.of()).refused("modifier:quorum params.council params.quorum", "judge", List.of(me, List.of(pair(a0, vote("VoteNo")))), null);

        JsLeg.check(f);
        report(f, p);

        // ---- the same contract governed by a script: approvals bound to the purpose's identity (K46)
        var g = legs("Steward", stewardParams(scriptCred(GOVERNOR)));
        var q = new PurposeLegs(g);
        Tx byAnyone = at(EVE, 0, 0);
        ok(q.as(List.of(EVE), List.of(GOVERNOR)).check("take", List.of(ada(10), Bytes.of(B), ada(1)), byAnyone));
        q.as(List.of(EVE), List.of()).refused("modifier:authorized params.gov", "take", List.of(ada(10), Bytes.of(B), ada(1)), byAnyone);
        ok(q.as(List.of(EVE), List.of(GOVERNOR)).check("anything", List.of(cert("DelegStaking", scriptCred(g.harness.policy()), stake)), null));
        q.as(List.of(EVE), List.of()).refused("modifier:authorized params.gov", "anything", List.of(cert("DelegStaking", scriptCred(g.harness.policy()), stake)), null);
        var otherCert = (org.julclang.ledger.TxCert) PurposeLegs.ledger(cert("RegStaking", scriptCred(g.harness.policy()), Opt.NONE), org.julclang.ledger.TxCert.class);
        var myCert = (org.julclang.ledger.TxCert) PurposeLegs.ledger(cert("DelegStaking", scriptCred(g.harness.policy()), stake), org.julclang.ledger.TxCert.class);
        q.as(List.of(EVE), List.of(GOVERNOR));
        approvalMutants(q, "take", List.of(ada(10), Bytes.of(B), ada(1)), byAnyone,
                List.of(new org.julclang.ledger.ScriptInfo.RewardingScript(new org.julclang.ledger.Credential.ScriptCredential(new org.julclang.ledger.ScriptHash(fill(0x0C))))), 2);
        // A certificate's approval names it and its index: another certificate's, or the same one at another index, is refused.
        approvalMutants(q, "anything", List.of(cert("DelegStaking", scriptCred(g.harness.policy()), stake)), null,
                List.of(new org.julclang.ledger.ScriptInfo.CertifyingScript(BigInteger.ZERO, otherCert),
                        new org.julclang.ledger.ScriptInfo.CertifyingScript(BigInteger.ONE, myCert)), 3);
        JsLeg.check(g);
        report(g, q);
    }

    @Test
    @Order(4)
    void guardrail() throws Exception {
        var f = legs("Guardrail", rec("Config", Bytes.of(PAYEE), ada(1000)));
        var p = new PurposeLegs(f);
        byte[] own = f.harness.policy();
        GovV a0 = actionId(fill(0x61), 0), a1 = actionId(fill(0x61), 1);
        ok(f.check("init", null, List.of(), at(ADMIN, 0, 0), ADMIN));
        Rec s = rec("State", Bytes.of(ADMIN));
        ok(f.check("close", s, List.of(), at(ADMIN, 0, ada(3).longValue()), ADMIN));
        f.mutants("close", s, List.of(), at(ADMIN, 0, ada(3).longValue()), ADMIN);
        // The guardrail: a guardrail over every governance action, the ledger types' fields and LedgerMaps (K40, K45)
        CredV ret = keyCred(A);
        GovV pv = gov("ProtocolVersion", "ProtocolVersion", n(10), n(0)), pv12 = gov("ProtocolVersion", "ProtocolVersion", n(12), n(0));
        GovV q23 = gov("Rational", "Rational", n(2), n(3)), q12 = gov("Rational", "Rational", n(1), n(2));
        var accepted = List.of(
                action("ParameterChange", Opt.NONE, new DataV("d87980"), Opt.some(Bytes.of(own))),
                action("HardForkInitiation", Opt.some(a0), pv),
                action("TreasuryWithdrawals", ledgerMap(List.of(keyCred(A), scriptCred(C)), List.of(ada(400), ada(600))), Opt.NONE),
                action("UpdateCommittee", Opt.NONE, List.of(keyCred(C)), ledgerMap(List.of(keyCred(A)), List.of(n(90))), q23),
                action("NewConstitution", Opt.some(a1), gov("Constitution", "Constitution", Opt.some(Bytes.of(own)))),
                action("InfoAction"));
        for (GovV a : accepted) ok(p.check("rail", List.of(proposal(ADA, ret, a)), null));
        var refusedActions = List.of(
                action("ParameterChange", Opt.some(a0), new DataV("d87980"), Opt.some(Bytes.of(own))),
                action("ParameterChange", Opt.NONE, new DataV("d87980"), Opt.NONE),
                action("HardForkInitiation", Opt.NONE, pv12),
                action("TreasuryWithdrawals", ledgerMap(List.of(keyCred(A), scriptCred(C)), List.of(ada(400), ada(601))), Opt.NONE),
                action("TreasuryWithdrawals", ledgerMap(List.of(keyCred(A), keyCred(B)), List.of(ada(1), n(0))), Opt.NONE),
                action("TreasuryWithdrawals", ledgerMap(List.of(keyCred(A), keyCred(PAYEE)), List.of(ada(1), ada(1))), Opt.NONE),
                action("TreasuryWithdrawals", ledgerMap(List.of(keyCred(A), keyCred(B), keyCred(C), scriptCred(A)), List.of(ada(1), ada(1), ada(1), ada(1))), Opt.NONE),
                action("TreasuryWithdrawals", ledgerMap(List.of(), List.of()), Opt.NONE),
                action("NoConfidence", Opt.NONE),
                action("UpdateCommittee", Opt.NONE, List.of(keyCred(C)), ledgerMap(List.of(keyCred(A)), List.of(n(101))), q23),
                action("UpdateCommittee", Opt.NONE, List.of(keyCred(C), keyCred(B)), ledgerMap(List.of(), List.of()), q23),
                action("UpdateCommittee", Opt.NONE, List.of(), ledgerMap(List.of(), List.of()), q12),
                action("NewConstitution", Opt.NONE, gov("Constitution", "Constitution", Opt.NONE)));
        for (GovV a : refusedActions) p.refused("purpose:propose", "rail", List.of(proposal(ADA, ret, a)), null);
        p.refused("purpose:propose", "rail", List.of(proposal(BigInteger.ZERO, ret, action("InfoAction"))), null);
        p.refused("purpose:propose", "rail", List.of(proposal(ADA, keyCred(PAYEE), action("InfoAction"))), null);
        // A duplicate key outside the ledger's domain (a map from the script context has none): every entry counts.
        p.refused("purpose:propose", "rail", List.of(proposal(ADA, ret, action("TreasuryWithdrawals",
                ledgerMap(List.of(keyCred(A), keyCred(A)), List.of(ada(600), ada(600))), Opt.NONE))), null);
        JsLeg.check(f);
        report(f, p);
    }

    /** A Rewards redeemer with its Ctx's field {@code i} (caller, received, balance, validFrom, validTo) set to {@code v}. */
    static org.julclang.core.PlutusData ctxField(org.julclang.core.PlutusData d, int i, BigInteger v) {
        var r = (org.julclang.core.PlutusData.ConstrData) d;
        var fs = new ArrayList<>(r.fields());
        var ctx = (org.julclang.core.PlutusData.ConstrData) fs.getLast();
        var cf = new ArrayList<>(ctx.fields());
        cf.set(i, org.julclang.core.PlutusData.integer(v));
        fs.set(fs.size() - 1, new org.julclang.core.PlutusData.ConstrData(ctx.constructorTag(), cf));
        return new org.julclang.core.PlutusData.ConstrData(r.constructorTag(), fs);
    }

    // ------------------------------------------------------------------ migration (ADR-0011 D4, I41)

    /** The token name a successor mints for the instance spent at {@code StateHarness.LIVE_REF}: blake2b_256 of the outref. */
    static byte[] successorId() {
        return HEX.parseHex(StateContractHandle.instanceIdOf(HEX.formatHex(StateHarness.LIVE_REF.txId().hash()),
                StateHarness.LIVE_REF.index().intValueExact()));
    }

    static Value token(byte[] policy, byte[] name, long q) {
        return Value.singleton(new PolicyId(policy), new TokenName(name), BigInteger.valueOf(q));
    }

    /**
     * The successor's half of a migration transaction, added to the old version's destroy: {@code policy}'s instance
     * minted for the spent input and its output at {@code to} holding {@code lovelace} more than the old input's
     * ({@code delta}), with {@code adopted} as its datum.
     */
    static java.util.function.BiFunction<TxModel, StateHarness.Live<Object>, TxModel> successor(byte[] policy, org.julclang.ledger.Address to,
                                                                                               Object adopted, long delta) {
        return (tx, live) -> {
            var c = tx.copy();
            c.outputs.add(new TxOut(to, Value.lovelace(live.lovelace().add(BigInteger.valueOf(delta))).merge(token(policy, successorId(), 1)),
                    new OutputDatum.OutputDatumInline(StateHarness.data(adopted)), Optional.empty()));
            c.mint = c.mint.merge(token(policy, successorId(), 1));
            return c;
        };
    }

    /**
     * The whole migration as the new version's mint sees it: {@code v1}'s instance (its state {@code old}, holding {@code funds}
     * beyond its deposit) spent by its migrating destroy and burned, the new instance minted for it, holding the old input's
     * lovelace plus {@code delta}, its datum the adopted first state.
     */
    static java.util.function.Function<Object, TxModel> adoption(FourWay v1, String destroy, Object old, BigInteger funds, FourWay v2, long delta,
                                                                 byte[] signer, byte[]... cosigners) {
        return first -> {
            try {
                var live = v1.harness.live(old, funds);
                Ctx c1 = v1.harness.ctx(signer, BigInteger.ZERO, live.lovelace());
                JulcList<Payout> none = JulcList.empty();
                var tx = v1.harness.destroy(live, v1.construct(Character.toUpperCase(destroy.charAt(0)) + destroy.substring(1), List.of(c1)),
                        v1.construct("BurnInstance", List.of()), none, signer, c1);
                for (byte[] k : cosigners) tx.signers.add(k);
                tx = successor(v2.harness.policy(), v2.harness.stateAddress(), first, delta).apply(tx, live);
                tx.stateOutput = tx.outputs.size() - 1;
                tx.token = successorId();
                return tx;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    @Test
    @Order(2)
    void upgrade() throws Exception {
        var v1 = LEGS.get("CounterV1").reference("org.julclang.state.examples.upgrade", "open");
        var v2 = LEGS.get("CounterV2").reference("org.julclang.state.examples.upgrade", "open");
        byte[] p1 = v1.harness.policy(), p2 = v2.harness.policy();
        Bytes none = Bytes.of(new byte[0]);
        // Version 1: created, counted, its successor named by its admin (only the admin: A43's audience).
        ok(v1.check("init", null, List.of(), at(ADMIN, ada(10).longValue(), 0), ADMIN));
        Rec fresh = rec("State", Bytes.of(ADMIN), none, n(0));
        ok(v1.check("increment", fresh, List.of(), null, ADMIN));
        ok(v1.check("setSuccessor", fresh, List.of(Bytes.of(p2)), null, ADMIN));
        v1.modifierRefused("setSuccessor", fresh, List.of(Bytes.of(p2)), null, EVE);
        // A43: an admin may change the successor while no migration has happened (examples/upgrade's rule, kept): stated.
        Rec named = rec("State", Bytes.of(ADMIN), Bytes.of(p2), n(41));
        ok(v1.check("setSuccessor", named, List.of(Bytes.of(fill(0x5E))), null, ADMIN));
        v1.mutants("setSuccessor", fresh, List.of(Bytes.of(p2)), null, ADMIN);

        // ---- the old half (I41): the instance moves into the successor its field names, with its whole value
        BigInteger funds = ada(10);
        BigInteger lovelace = BigInteger.valueOf(v1.harness.minAda()).add(funds);
        Tx up = at(ADMIN, 0, lovelace.longValue());
        Rec adopted2 = rec("State", Bytes.of(ADMIN), n(41), n(1));
        v1.successor = successor(p2, v2.harness.stateAddress(), v2.java(adopted2), 0);
        ok(v1.check("upgrade", named, List.of(), up.withSuccessor(Bytes.of(p2)), ADMIN));
        v1.modifierRefused("upgrade", named, List.of(), at(EVE, 0, lovelace.longValue()).withSuccessor(Bytes.of(p2)), EVE);
        // A42: into a policy other than the one its field names (that policy's instance minted): migrate:to.
        v1.successor = successor(fill(0x5F), v2.harness.stateAddress(), v2.java(adopted2), 0);
        migrateRefused(v1, "migrate:to", "upgrade", named, List.of(), up.withSuccessor(Bytes.of(fill(0x5F))), ADMIN);
        // An unset successor (the field still empty): migrate:to.
        v1.successor = successor(p2, v2.harness.stateAddress(), v2.java(adopted2), 0);
        migrateRefused(v1, "migrate:to", "upgrade", rec("State", Bytes.of(ADMIN), none, n(41)), List.of(), up.withSuccessor(Bytes.of(p2)), ADMIN);
        // A42, K49: value skimmed between the two: the successor holds one lovelace less (a transaction-level refusal: the old
        // side's value rule).
        v1.successor = successor(p2, v2.harness.stateAddress(), v2.java(adopted2), -1);
        v1.transactionLevel("upgrade", named, List.of(), up.withSuccessor(Bytes.of(p2)), ADMIN);
        // The successor's mint, its address and its token, each one step away: the old side refuses.
        oldSideMutants(v1, named, lovelace, p2, v2.harness.stateAddress(), v2.java(adopted2));

        // ---- the new half: CounterV2 adopts the instance its params' previous policy names, as its input holds it
        ok(v2.check("init", null, List.of(), at(ADMIN, 0, 0), ADMIN));
        Object oldJ = v1.java(named);
        v2.adoption = adoption(v1, "upgrade", oldJ, funds, v2, 0, ADMIN);
        Tx adopt = new Tx(Bytes.of(ADMIN), BigInteger.ZERO, BigInteger.ZERO, T0, T0.add(WINDOW)).withPrior(new Interpreter.PriorV(Bytes.of(p1), as("CounterV1.State", named)));
        assertEquals(ok(v2.check("migrate", null, List.of(), adopt, ADMIN)), "ok:" + v2.encoded(v2.java(adopted2), null, null));
        // A42: an instance of another policy than params.previous: migrate:from (the prior at that policy's address).
        var honestAdoption = adoption(v1, "upgrade", oldJ, funds, v2, 0, ADMIN);
        v2.adoption = first -> elsewhere(honestAdoption.apply(first), p1, fill(0x5D), v1.harness.instanceId());
        migrateRefused(v2, "migrate:from", "migrate", null, List.of(), adopt.withPrior(new Interpreter.PriorV(Bytes.of(fill(0x5D)), as("CounterV1.State", named))), ADMIN);
        // A44: the old state is a checked input: an admin of 27 bytes (no v1 build could store it; the check is the new side's).
        Rec hostile = rec("State", Bytes.of(new byte[27]), Bytes.of(p2), n(41));
        v2.adoption = adoption(v1, "upgrade", v1.java(hostile), funds, v2, 0, ADMIN);
        migrateRefused(v2, "input:old", "migrate", null, List.of(), adopt.withPrior(new Interpreter.PriorV(Bytes.of(p1), as("CounterV1.State", hostile))), ADMIN,
                rec("State", Bytes.of(new byte[27]), n(41), n(1)));
        // Review S1 (I41): v2's ordinary init seeded by the v1 instance (its token named as @MigrateTo expects) in place of the
        // adoption: refused on every leg (migrate:from). The v1 instance holds only its deposit, so v2's init value fits
        // and only the new rule refuses (JscV2M3cAttackTest shows the old side accepting the same transaction).
        Rec bare = rec("State", Bytes.of(ADMIN), Bytes.of(p2), n(41));
        v2.adoption = adoption(v1, "upgrade", v1.java(bare), BigInteger.ZERO, v2, v2.harness.minAda() - v1.harness.minAda(), ADMIN);
        migrateRefused(v2, "migrate:from", "init", null, List.of(), at(ADMIN, 0, 0).withPrior(new Interpreter.PriorV(Bytes.of(p1), as("CounterV1.State", bare))),
                ADMIN, rec("State", Bytes.of(ADMIN), n(0), n(1)));
        // The control: the same init with no previous instance in its transaction (a wallet seed) is accepted.
        ok(v2.check("init", null, List.of(), at(ADMIN, 0, 0), ADMIN));
        // K49: exactly the old input's lovelace: one more or one less is refused (transaction-level: the new side's value rule).
        v2.adoption = adoption(v1, "upgrade", oldJ, funds, v2, 1, ADMIN);
        v2.transactionLevel("migrate", null, List.of(), adopt, ADMIN);
        v2.adoption = adoption(v1, "upgrade", oldJ, funds, v2, -1, ADMIN);
        v2.transactionLevel("migrate", null, List.of(), adopt, ADMIN);
        // The migration transaction one step away, as the new side's mint sees it (A42: the old instance not burned, one v1
        // into two v2, the seed not spent, a wallet seed, the prior at a key address; two v1 into one v2 is
        // JscV2M3cAttackTest's, refused by the old side), and the attack suite on its new instance.
        v2.adoption = adoption(v1, "upgrade", oldJ, funds, v2, 0, ADMIN);
        newSideMutants(v2, v1, oldJ, adopted2, funds);
        // The new version's own methods.
        Rec s2 = rec("State", Bytes.of(ADMIN), n(41), n(1));
        ok(v2.check("increment", s2, List.of(), null, ADMIN));
        ok(v2.check("setStep", s2, List.of(n(5)), null, ADMIN));
        v2.modifierRefused("setStep", s2, List.of(n(5)), null, EVE);
        ok(v2.check("close", s2, List.of(), at(ADMIN, 0, lovelace.longValue()), ADMIN));
        v2.mutants("setStep", s2, List.of(n(5)), null, ADMIN);
        // The Java examples agree on every vector but one stated difference (A44): the port's adopted state is a checked input,
        // so an admin of 27 bytes is refused, where examples/upgrade's adopt takes the old state as it is.
        assertEquals(List.of(), v1.javaDiffs, v1.javaDiffs.toString());
        assertEquals(1, v2.javaDiffs.size(), v2.javaDiffs.toString());
        assertTrue(v2.javaDiffs.get(0).contains("fields=[#" + "00".repeat(27) + ",") && v2.javaDiffs.get(0).endsWith("the Java example accepts, the port refuses"),
                v2.javaDiffs.toString());
        JsLeg.check(v1);
        JsLeg.check(v2);
        report(v1, null);
        report(v2, null);
    }


    // ------------------------------------------------------------------ M3 final pass MS1: a staked contract that migrates

    @Test
    @Order(6)
    void stakedMigration() throws Exception {
        // StakedCounterV1 has stakeSelf, a withdraw and a certify handler, so it is asset-aware; its migrating destroy must
        // still move the instance into its successor (I41), CounterV2 deployed with its policy as params.previous.
        var v1 = legs("StakedCounterV1", rec("Config", keys(A, B, C), n(2), Bytes.of(POOL), Bytes.of(PAYEE)));
        byte[] p1 = v1.harness.policy();
        var v2 = legs("CounterV2", rec("Config", Bytes.of(p1)));
        byte[] p2 = v2.harness.policy();
        Bytes none = Bytes.of(new byte[0]);
        ok(v1.check("init", null, List.of(), at(ADMIN, ada(10).longValue(), 0), ADMIN));
        Rec fresh = rec("State", Bytes.of(ADMIN), none, n(0));
        ok(v1.check("setSuccessor", fresh, List.of(Bytes.of(p2)), null, ADMIN));
        v1.mutants("setSuccessor", fresh, List.of(Bytes.of(p2)), null, ADMIN);
        BigInteger funds = ada(10);
        BigInteger lovelace = BigInteger.valueOf(v1.harness.minAda()).add(funds);
        Rec named = rec("State", Bytes.of(ADMIN), Bytes.of(p2), n(41));
        Rec adopted = rec("State", Bytes.of(ADMIN), n(41), n(1));
        Tx up = at(ADMIN, 0, lovelace.longValue()).withSuccessor(Bytes.of(p2));
        // The regression: the honest migration is accepted on every leg (the validator refused it before the fix).
        v1.successor = successor(p2, v2.harness.stateAddress(), v2.java(adopted), 0);
        ok(v1.check("upgrade", named, List.of(), up, ADMIN));
        v1.modifierRefused("upgrade", named, List.of(), at(EVE, 0, lovelace.longValue()).withSuccessor(Bytes.of(p2)), EVE);
        migrateRefused(v1, "migrate:to", "upgrade", rec("State", Bytes.of(ADMIN), none, n(41)), List.of(), up, ADMIN);
        v1.successor = successor(p2, v2.harness.stateAddress(), v2.java(adopted), -1);
        v1.transactionLevel("upgrade", named, List.of(), up, ADMIN);
        v1.successor = successor(p2, v2.harness.stateAddress(), v2.java(adopted), 0);
        oldSideMutants(v1, named, lovelace, p2, v2.harness.stateAddress(), v2.java(adopted));
        // The new half: CounterV2 adopts it.
        v2.adoption = adoption(v1, "upgrade", v1.java(named), funds, v2, 0, ADMIN);
        Tx adopt = new Tx(Bytes.of(ADMIN), BigInteger.ZERO, BigInteger.ZERO, T0, T0.add(WINDOW))
                .withPrior(new Interpreter.PriorV(Bytes.of(p1), as("CounterV1.State", named)));
        assertEquals(ok(v2.check("migrate", null, List.of(), adopt, ADMIN)), "ok:" + v2.encoded(v2.java(adopted), null, null));
        JsLeg.check(v1);
        JsLeg.check(v2);
        report(v1, null);
    }

    @Test
    @Order(7)
    void stakedAdoption() throws Exception {
        // Design MD2: the adopting side in a staked contract with handlers. StakedCounterV2 (stakeSelf, a withdraw and a vote
        // handler, so asset-aware) adopts StakedCounterV1's instance at its own staked address; its handlers work beside it.
        var v1 = legs("StakedCounterV1", rec("Config", keys(A, B, C), n(2), Bytes.of(POOL), Bytes.of(PAYEE)));
        byte[] p1 = v1.harness.policy();
        var v2 = legs("StakedCounterV2", rec("Config", Bytes.of(p1), keys(A, B, C), n(2), Bytes.of(PAYEE)));
        byte[] p2 = v2.harness.policy();
        assertTrue(v2.interp.stakeSelf() && v2.harness.stateAddress().stakingCredential().isPresent());
        BigInteger funds = ada(10);
        BigInteger lovelace = BigInteger.valueOf(v1.harness.minAda()).add(funds);
        Rec named = rec("State", Bytes.of(ADMIN), Bytes.of(p2), n(41));
        Rec adopted = rec("State", Bytes.of(ADMIN), n(41), n(1));
        // The old half into the staked successor (MS1's branch, here with a stakeSelf successor address).
        v1.successor = successor(p2, v2.harness.stateAddress(), v2.java(adopted), 0);
        ok(v1.check("upgrade", named, List.of(), at(ADMIN, 0, lovelace.longValue()).withSuccessor(Bytes.of(p2)), ADMIN));
        // The new half: the adoption on every leg, its value rule, and the migration one step away.
        Object oldJ = v1.java(named);
        v2.adoption = adoption(v1, "upgrade", oldJ, funds, v2, 0, ADMIN);
        Tx adopt = new Tx(Bytes.of(ADMIN), BigInteger.ZERO, BigInteger.ZERO, T0, T0.add(WINDOW))
                .withPrior(new Interpreter.PriorV(Bytes.of(p1), as("StakedCounterV1.State", named)));
        // (An asset-aware contract's step is its state with the effects, so the adopted state is checked by the mutants below.)
        ok(v2.check("migrate", null, List.of(), adopt, ADMIN));
        var honestAdoption = adoption(v1, "upgrade", oldJ, funds, v2, 0, ADMIN);
        v2.adoption = first -> elsewhere(honestAdoption.apply(first), p1, fill(0x5D), v1.harness.instanceId());
        migrateRefused(v2, "migrate:from", "migrate", null, List.of(),
                adopt.withPrior(new Interpreter.PriorV(Bytes.of(fill(0x5D)), as("StakedCounterV1.State", named))), ADMIN);
        v2.adoption = adoption(v1, "upgrade", oldJ, funds, v2, 1, ADMIN);
        v2.transactionLevel("migrate", null, List.of(), adopt, ADMIN);
        v2.adoption = adoption(v1, "upgrade", oldJ, funds, v2, 0, ADMIN);
        newSideMutants(v2, v1, oldJ, adopted, funds);
        // The adopted instance's own methods and the handlers beside it.
        ok(v2.check("increment", adopted, List.of(), null, ADMIN));
        ok(v2.check("close", adopted, List.of(), at(ADMIN, 0, lovelace.longValue()), ADMIN));
        v2.modifierRefused("close", adopted, List.of(), at(EVE, 0, lovelace.longValue()), EVE);
        var p = new PurposeLegs(v2);
        CredV self = scriptCred(p2);
        ok(p.as(List.of(A, B), List.of()).check("collect", List.of(ada(5)), null));
        p.as(List.of(A), List.of()).refused("modifier:quorum params.council params.quorum", "collect", List.of(ada(5)), null);
        p.as(List.of(A, B), List.of()).withdrawMutants("collect", List.of(ada(5)), null);
        ok(p.as(List.of(A, B), List.of()).check("decide", List.of(voter("DRepVoter", self), List.of(pair(actionId(fill(0x61), 0), vote("VoteYes")))), null));
        p.as(List.of(A), List.of()).refused("modifier:quorum params.council params.quorum", "decide",
                List.of(voter("DRepVoter", self), List.of(pair(actionId(fill(0x61), 0), vote("VoteNo")))), null);
        ok(p.as(List.of(), List.of()).check("guard", List.of(proposal(n(1), keyCred(A), action("InfoAction"))), null));
        JsLeg.check(v1);
        JsLeg.check(v2);
        report(v2, p);
    }

    // ------------------------------------------------------------------ Tally (coverage): migration with every kind of input

    static Rec tallyParams(byte[] previous, List<Object> council, long needed) {
        return rec("Config", Bytes.of(previous), council, n(needed), DEADLINE);
    }

    static MapV tags(String... kv) {
        var ks = new ArrayList<Bytes>();
        var vs = new ArrayList<Object>();
        for (int i = 0; i < kv.length; i += 2) {
            ks.add(Bytes.of(kv[i].getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            vs.add(n(Long.parseLong(kv[i + 1])));
        }
        return new MapV(ks, vs);
    }

    static Bytes text(String s) {
        return Bytes.of(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A call refused on all five legs; the validator sees {@code candidate} (the transition refuses: there is no next state). */
    static String refusedWith(FourWay f, String method, Rec state, List<Object> args, Tx t, byte[] signer, Rec candidate) throws Exception {
        assertEquals("refused", f.check(method, state, args, t, signer, candidate), f.name + "." + method + " " + args);
        return f.lastRefusal;
    }

    @Test
    @Order(5)
    void tally() throws Exception {
        var v1 = legs("TallyV1", null);
        byte[] p1 = v1.harness.policy();
        var v2 = legs("TallyV2", tallyParams(p1, keys(A, B), 2));
        byte[] p2 = v2.harness.policy();
        Bytes none = Bytes.of(new byte[0]);
        List<Object> voters = keys(A, B, C);
        Tx create = at(ADMIN, ada(10).longValue(), 0);
        // Version 1: created with a reachable quorum of its voters (its invariant, and the quorum's shape, on the output).
        ok(v1.check("init", null, List.of(voters, n(2)), create, ADMIN));
        v1.invariantRefused("invariant:quorum voters needed", "init", null, List.of(voters, n(4)), create, ADMIN);
        v1.invariantRefused("invariant:quorum voters needed", "init", null, List.of(keys(B, A, C), n(2)), create, ADMIN);
        Rec fresh = rec("State", Bytes.of(ADMIN), none, voters, n(2), tags(), Opt.NONE, true, rec("Plain"));
        ok(v1.check("tag", fresh, List.of(text("a"), n(3)), null, ADMIN));
        v1.modifierRefused("tag", fresh, List.of(text("a"), n(3)), null, EVE);
        v1.mutants("tag", fresh, List.of(text("a"), n(3)), null, ADMIN);
        // Review F3: a Bool and an enum in the migrating state (weigh sets both).
        ok(v1.check("weigh", fresh, List.of(n(3)), null, ADMIN));
        v1.modifierRefused("weigh", fresh, List.of(n(3)), null, EVE);
        // A43: the successor is set once (the lock the contract states; JSC9014 is silent for it): a second set is refused.
        ok(v1.check("setSuccessor", fresh, List.of(Bytes.of(p2)), null, ADMIN));
        Rec named = rec("State", Bytes.of(ADMIN), Bytes.of(p2), voters, n(2), tags("a", "3", "b", "4"), Opt.some(text("b")), true, rec("Weighted", n(3)));
        assertEquals("refused", v1.check("setSuccessor", named, List.of(Bytes.of(fill(0x5E))), null, ADMIN));
        assertEquals("require:27:5:the successor is set once", v1.lastRefusal);
        v1.mutants("setSuccessor", fresh, List.of(Bytes.of(p2)), null, ADMIN);

        // ---- the old half: moved by a quorum of its voters into the successor its field names
        BigInteger funds = ada(10);
        BigInteger lovelace = BigInteger.valueOf(v1.harness.minAda()).add(funds);
        Tx mv = at(A, 0, lovelace.longValue()).withSuccessor(Bytes.of(p2));
        // count = the tags' size + the note's length: 2 + 1.
        Rec adopted = rec("State", Bytes.of(ADMIN), voters, n(3), n(5), true);
        v1.successor = successor(p2, v2.harness.stateAddress(), v2.java(adopted), 0);
        ok(v1.as(List.of(A, B), null).check("move", named, List.of(), mv, A));
        v1.as(List.of(A), null).modifierRefused("move", named, List.of(), mv, A);
        v1.as(List.of(A, EVE), null).modifierRefused("move", named, List.of(), mv, A);
        migrateRefused(v1.as(List.of(A, B), null), "migrate:to", "move",
                rec("State", Bytes.of(ADMIN), none, voters, n(2), tags("a", "3", "b", "4"), Opt.some(text("b")), true, rec("Weighted", n(3))), List.of(), mv, A);
        v1.successor = successor(fill(0x5F), v2.harness.stateAddress(), v2.java(adopted), 0);
        migrateRefused(v1.as(List.of(A, B), null), "migrate:to", "move", named, List.of(), mv.withSuccessor(Bytes.of(fill(0x5F))), A);
        v1.successor = successor(p2, v2.harness.stateAddress(), v2.java(adopted), -1);
        v1.as(List.of(A, B), null).transactionLevel("move", named, List.of(), mv, A);
        v1.successor = successor(p2, v2.harness.stateAddress(), v2.java(adopted), 0);
        oldSideMutants(v1, "Move", List.of(A, B), named, lovelace, p2, v2.harness.stateAddress(), v2.java(adopted));

        // ---- the new half: adopted with an argument, by the old state's admin, before the params' deadline
        ok(v2.check("init", null, List.of(), at(ADMIN, 0, 0), ADMIN));
        Object oldJ = v1.java(named);
        Interpreter.PriorV prior = new Interpreter.PriorV(Bytes.of(p1), asTallyV1(named));
        Tx adopt = new Tx(Bytes.of(ADMIN), BigInteger.ZERO, BigInteger.ZERO, T0, T0.add(WINDOW)).withPrior(prior);
        v2.adoption = adoption(v1, "move", oldJ, funds, v2, 0, A, B);
        assertEquals(ok(v2.check("migrate", null, List.of(n(5)), adopt, ADMIN)), "ok:" + v2.encoded(v2.java(adopted), null, null));
        // The body's requires: another caller (the old admin did not adopt), a window past params.until.
        Tx byEve = new Tx(Bytes.of(EVE), BigInteger.ZERO, BigInteger.ZERO, T0, T0.add(WINDOW)).withPrior(prior);
        v2.adoption = adoption(v1, "move", oldJ, funds, v2, 0, A, B);
        assertEquals("require:19:5:the admin adopts", refusedWith(v2, "migrate", null, List.of(n(5)), byEve, EVE, adopted));
        Tx late = new Tx(Bytes.of(ADMIN), BigInteger.ZERO, BigInteger.ZERO, DEADLINE, DEADLINE.add(WINDOW)).withPrior(prior);
        assertEquals("require:20:5:too late to adopt", refusedWith(v2, "migrate", null, List.of(n(5)), late, ADMIN, adopted));
        // The invariant on the adopted state (a bonus over 100).
        v2.adoption = adoption(v1, "move", oldJ, funds, v2, 0, A, B);
        v2.invariantRefused("invariant:sane", "migrate", null, List.of(n(101)), adopt, ADMIN);
        // A42: an instance of another policy than params.previous.
        var honest = adoption(v1, "move", oldJ, funds, v2, 0, A, B);
        v2.adoption = first -> elsewhere(honest.apply(first), p1, fill(0x5D), v1.harness.instanceId());
        migrateRefused(v2, "migrate:from", "migrate", null, List.of(n(5)), adopt.withPrior(new Interpreter.PriorV(Bytes.of(fill(0x5D)), asTallyV1(named))), ADMIN);
        // A44, K48: the old state is a checked input, of every kind it holds: a voter of 27 bytes, an admin of 27 bytes (no
        // v1 build stores either; the new side checks).
        var hostile = new LinkedHashMap<String, Rec[]>();
        hostile.put("a 27-byte voter", new Rec[] {
                rec("State", Bytes.of(ADMIN), Bytes.of(p2), keys(new byte[27], B, C), n(2), tags("a", "3", "b", "4"), Opt.some(text("b")), true, rec("Weighted", n(3))),
                rec("State", Bytes.of(ADMIN), keys(new byte[27], B, C), n(3), n(5), true)});
        hostile.put("a 27-byte admin", new Rec[] {
                rec("State", Bytes.of(new byte[27]), Bytes.of(p2), voters, n(2), tags("a", "3", "b", "4"), Opt.some(text("b")), true, rec("Weighted", n(3))),
                rec("State", Bytes.of(new byte[27]), voters, n(3), n(5), true)});
        for (var e : hostile.entrySet()) {
            Rec old = e.getValue()[0];
            v2.adoption = adoption(v1, "move", v1.java(old), funds, v2, 0, A, B);
            Tx t = new Tx(Bytes.of(ADMIN), BigInteger.ZERO, BigInteger.ZERO, T0, T0.add(WINDOW)).withPrior(new Interpreter.PriorV(Bytes.of(p1), asTallyV1(old)));
            migrateRefused(v2, "input:old", "migrate", null, List.of(n(5)), t, ADMIN, e.getValue()[1]);
        }
        // A map out of canonical order or with a key twice: the interpreters refuse it (input:old); the validator's check is
        // the generated SortedMaps key walk an argument's map has (mapKeys__Bytes), and the harness cannot carry the datum
        // (it writes canonical CBOR, which sorts the keys and keeps one of each: as JscV2M2cHostileInputTest states).
        for (MapV m : List.of(tags("b", "4", "a", "3"), tags("a", "3", "a", "4"))) {
            Rec old = rec("State", Bytes.of(ADMIN), Bytes.of(p2), voters, n(2), m, Opt.some(text("b")), true, rec("Weighted", n(3)));
            Tx t = FourWay.interpreterTx(new Tx(Bytes.of(ADMIN), BigInteger.ZERO, BigInteger.ZERO, T0, T0.add(WINDOW))
                    .withPrior(new Interpreter.PriorV(Bytes.of(p1), asTallyV1(old))), ADMIN);
            var e = assertThrows(Interpreter.Refused.class, () -> v2.interp.call("migrate", null, List.of(n(5)), t));
            assertEquals("input:old", e.refusal(), m.toString());
            v2.jsCalls.add(JsLeg.call("migrate", null, List.of(n(5)), t, v2.params, "refused", null, e, "TallyV2.migrate " + m));
        }
        // Review S1: TallyV2's ordinary init seeded by the TallyV1 instance skips the adoption's requires (the admin, the
        // deadline) and the adopted state: refused on every leg (migrate:from), even past the deadline.
        Rec bareOld = rec("State", Bytes.of(ADMIN), Bytes.of(p2), voters, n(2), tags("a", "3", "b", "4"), Opt.some(text("b")), true, rec("Weighted", n(3)));
        v2.adoption = adoption(v1, "move", v1.java(bareOld), BigInteger.ZERO, v2, v2.harness.minAda() - v1.harness.minAda(), A, B, EVE);
        migrateRefused(v2, "migrate:from", "init", null, List.of(), new Tx(Bytes.of(EVE), BigInteger.ZERO, BigInteger.ZERO, DEADLINE, DEADLINE.add(WINDOW))
                .withPrior(new Interpreter.PriorV(Bytes.of(p1), asTallyV1(bareOld))), EVE, rec("State", Bytes.of(EVE), List.of(), n(0), n(0), true));
        // K49: exactly the old input's lovelace.
        v2.adoption = adoption(v1, "move", oldJ, funds, v2, 1, A, B);
        v2.transactionLevel("migrate", null, List.of(n(5)), adopt, ADMIN);
        v2.adoption = adoption(v1, "move", oldJ, funds, v2, 0, A, B, ADMIN);   // the adopter (the Ctx's caller) signs too
        newSideMutants(v2, v1, oldJ, adopted, funds, List.of(BigInteger.valueOf(5), v2.harness.ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO)));
        // The params' quorum is checked at adoption as at every creation (semantics §2.7 step 1): a council out of order.
        var bad = legs("TallyV2", tallyParams(p1, keys(B, A), 2));
        bad.adoption = adoption(v1, "move", oldJ, funds, bad, 0, A, B);
        bad.invariantRefused("invariant:quorum params.council params.needed", "migrate", null, List.of(n(5)), adopt, ADMIN);
        bad.invariantRefused("invariant:quorum params.council params.needed", "init", null, List.of(), at(ADMIN, 0, 0), ADMIN);
        // The new version's own methods.
        ok(v2.check("count", adopted, List.of(), null, ADMIN));
        v2.modifierRefused("count", adopted, List.of(), null, EVE);
        v2.mutants("count", adopted, List.of(), null, ADMIN);
        ok(v2.as(List.of(A, B), null).check("close", adopted, List.of(), at(A, 0, lovelace.longValue()), A));
        v2.as(List.of(A), null).modifierRefused("close", adopted, List.of(), at(A, 0, lovelace.longValue()), A);
        JsLeg.check(v1);
        JsLeg.check(v2);
        JsLeg.check(bad);
        report(v1, null);
        report(v2, null);
    }

    /** A TallyV1 state as TallyV2 names it: TallyV1.State, its enum's variant TallyV1.Plain or TallyV1.Weighted (field 7). */
    static Rec asTallyV1(Rec r) {
        var fs = new ArrayList<Object>(r.fields());
        Rec mode = (Rec) fs.get(7);
        fs.set(7, new Rec("TallyV1." + mode.type(), mode.fields()));
        return new Rec("TallyV1.State", fs);
    }

    /** A state of another contract, as an importer names it (C.State). */
    static Rec as(String type, Rec r) {
        return new Rec(type, r.fields());
    }

    static org.julclang.ledger.Address script(byte[] h) {
        return new org.julclang.ledger.Address(new org.julclang.ledger.Credential.ScriptCredential(new org.julclang.ledger.ScriptHash(h)), Optional.empty());
    }

    /** The migration with its old instance (token {@code id}) under another policy: at that script, its token and burn that policy's. */
    static TxModel elsewhere(TxModel tx, byte[] from, byte[] to, byte[] id) {
        var c = tx.copy();
        var in = c.inputs.get(0);
        var o = in.resolved();
        Value v = Value.lovelace(o.value().lovelaceOf()).merge(token(to, id, 1));
        c.inputs.set(0, new org.julclang.ledger.TxInInfo(in.outRef(), new TxOut(script(to), v, o.datum(), o.referenceScript())));
        c.mint = withoutZeros(c.mint.merge(token(from, id, 1)).merge(token(to, id, -1)));
        return c;
    }

    /** A migration's half refused on all five legs with {@code cls} (the transition computes it; the validator refuses). */
    static void migrateRefused(FourWay f, String cls, String method, Rec state, List<Object> args, Tx t, byte[] signer) throws Exception {
        migrateRefused(f, cls, method, state, args, t, signer, null);
    }

    static void migrateRefused(FourWay f, String cls, String method, Rec state, List<Object> args, Tx t, byte[] signer, Rec candidate) throws Exception {
        assertEquals("refused", f.check(method, state, args, t, signer, candidate), f.name + "." + method);
        assertEquals(cls, f.lastRefusal, f.name + "." + method);
        // Review C4: the validator's refusal is a script's (phase 2), not the ledger's: the transaction is well formed.
        scriptRejects(f.lastVerdict, method.equals("migrate") || method.equals("init") ? "MINT" : "SPEND", f.name + "." + method + " (" + cls + ")");
        f.migrateRefusals++;
    }

    /**
     * Review C4: a refusal by the script that holds the defence ({@code prefix}: "MINT", "SPEND"), in a transaction the
     * ledger accepts (no zero mint entry, no other phase-1 problem), so removing that script's check makes the test fail.
     */
    static void scriptRejects(StateHarness.Verdict v, String prefix, String what) {
        assertNotNull(v, what);
        assertFalse(v.accepted(), what + ": accepted\n" + v);
        assertFalse(v.results().containsKey("LEDGER"), what + ": refused by the ledger, not by a script\n" + v);
        assertTrue(v.results().entrySet().stream().anyMatch(e -> e.getKey().startsWith(prefix) && !e.getValue().isSuccess()),
                what + ": no " + prefix + " script refuses it\n" + v);
    }

    /** A value without zero quantities (Value.merge keeps a cancelled entry, which the ledger refuses: review C4). */
    static Value withoutZeros(Value v) {
        Value out = Value.zero();
        for (PolicyId p : v.inner().keys()) {
            var tokens = v.inner().get(p);
            for (TokenName n : tokens.keys()) {
                BigInteger q = tokens.get(n);
                if (q.signum() != 0) out = out.merge(Value.singleton(p, n, q));
            }
        }
        return out;
    }

    /** The old side's transaction one step away (each rejected by the old version's validator alone), and the attack suite. */
    static void oldSideMutants(FourWay v1, Rec named, BigInteger lovelace, byte[] p2, org.julclang.ledger.Address to, Object adopted) throws Exception {
        oldSideMutants(v1, "Upgrade", List.of(ADMIN), named, lovelace, p2, to, adopted);
    }

    static void oldSideMutants(FourWay v1, String destroy, List<byte[]> signers, Rec named, BigInteger lovelace, byte[] p2,
                               org.julclang.ledger.Address to, Object adopted) throws Exception {
        var live = v1.harness.live(v1.java(named), lovelace.subtract(BigInteger.valueOf(v1.harness.minAda())));
        Ctx c1 = v1.harness.ctx(signers.get(0), BigInteger.ZERO, live.lovelace());
        JulcList<Payout> none = JulcList.empty();
        var base = v1.harness.destroy(live, v1.construct(destroy, List.of(c1)), v1.construct("BurnInstance", List.of()), none, signers.get(0), c1);
        for (byte[] k : signers.subList(1, signers.size())) base.signers.add(k);
        var honest = successor(p2, to, adopted, 0).apply(base, live);
        assertTrue(v1.harness.evaluate(honest).accepted(), "the control");
        var muts = new LinkedHashMap<String, TxModel>();
        muts.put("no successor minted", base);
        var noMint = honest.copy();
        noMint.mint = base.mint;
        muts.put("the successor's output, but no mint", noMint);
        muts.put("the successor at a key address", successor(p2, StateHarness.pkhAddress(EVE), adopted, 0).apply(base, live));
        muts.put("the successor at another script", successor(p2, script(fill(0x5C)), adopted, 0).apply(base, live));
        var otherName = base.copy();
        otherName.outputs.add(new TxOut(to, Value.lovelace(live.lovelace()).merge(token(p2, fill(0x01), 1)),
                new OutputDatum.OutputDatumInline(StateHarness.data(adopted)), Optional.empty()));
        otherName.mint = otherName.mint.merge(token(p2, fill(0x01), 1));
        muts.put("the successor's token not named for the spent input", otherName);
        var unburned = honest.copy();
        unburned.mint = withoutZeros(unburned.mint.merge(token(v1.harness.policy(), v1.harness.instanceId(), 1)));
        muts.put("the old token not burned", unburned);
        for (var e : muts.entrySet()) {
            var v = v1.harness.evaluate(e.getValue());
            v1.validatorRuns++;
            scriptRejects(v, "SPEND", "the old side, the mutant \"" + e.getKey() + "\"");
            v1.mutantsRejected++;
        }
        v1.attacksRejected += Attacks.assertAllRejected(v1.harness, honest);
    }

    /** The new side's transaction one step away (each rejected by the new version's mint alone), and the attack suite. */
    static void newSideMutants(FourWay v2, FourWay v1, Object oldJ, Rec adopted, BigInteger funds) throws Exception {
        newSideMutants(v2, v1, oldJ, adopted, funds, List.of());
    }

    /** {@code args}: the Adopt redeemer's fields after the seed (the init migrate's arguments, its Ctx). */
    static void newSideMutants(FourWay v2, FourWay v1, Object oldJ, Rec adopted, BigInteger funds, List<Object> args) throws Exception {
        var adoptWith = new java.util.function.Function<org.julclang.ledger.TxOutRef, Object>() {
            public Object apply(org.julclang.ledger.TxOutRef seed) {
                var fs = new ArrayList<Object>(List.of(seed));
                fs.addAll(args);
                try {
                    return v2.construct("Adopt", fs);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        Object first = v2.java(adopted);
        var honest = v2.adoption.apply(first);
        var mine = honest.copy();
        mine.spendRedeemers.clear();
        mine.mintRedeemer = StateHarness.data(adoptWith.apply(StateHarness.LIVE_REF));
        assertTrue(v2.harness.evaluate(mine).accepted(), "the control\n" + v2.harness.evaluate(mine));
        var muts = new LinkedHashMap<String, TxModel>();
        var unburned = mine.copy();
        unburned.mint = withoutZeros(unburned.mint.merge(token(v1.harness.policy(), v1.harness.instanceId(), 1)));
        muts.put("the old instance not burned (it does not end)", unburned);
        var twice = mine.copy();
        twice.mint = twice.mint.merge(token(v2.harness.policy(), successorId(), 1));
        twice.outputs.add(twice.outputs.get(twice.stateOutput));
        muts.put("one v1 into two v2 instances", twice);
        // Review C4: the prior at a key address, still holding the previous version's token (only the address rule refuses it).
        var keyPrior = mine.copy();
        var pin = keyPrior.inputs.get(0);
        keyPrior.inputs.set(0, new org.julclang.ledger.TxInInfo(pin.outRef(), new TxOut(StateHarness.pkhAddress(EVE), pin.resolved().value(),
                pin.resolved().datum(), pin.resolved().referenceScript())));
        muts.put("the prior at a key address, holding the previous token", keyPrior);
        var noPrior = mine.copy();
        noPrior.inputs.remove(0);
        muts.put("the seed not spent (a reference, or another input)", noPrior);
        var otherSeed = mine.copy();
        otherSeed.mintRedeemer = StateHarness.data(adoptWith.apply(new org.julclang.ledger.TxOutRef(
                new org.julclang.ledger.TxId(StateHarness.fill(32, 0x33)), BigInteger.ZERO)));
        muts.put("a wallet input as the seed", otherSeed);
        for (var e : muts.entrySet()) {
            var v = v2.harness.evaluate(e.getValue());
            v2.validatorRuns++;
            scriptRejects(v, "MINT", "the new side, the mutant \"" + e.getKey() + "\"");
            v2.mutantsRejected++;
        }
        // The attack suite on the whole migration (both halves' scripts run in one transaction): each attack is refused by the
        // old version's spend and burn or by the new version's mint.
        var refusedBy = new LinkedHashMap<String, String>();
        // The new version sees a creation (its attacks are a creation's: the spent instance's, and its modifiers', are the old
        // side's attack suite's, run on the old version's destroy in oldSideMutants).
        for (var a : Attacks.applicable(v2.harness, honest.copyAs(TxModel.Kind.CREATE))) {
            // An adoption's seed is the spent old instance, not the harness's wallet seed this attack moves (a no-op here): the
            // mutants above cover it ("the seed not spent", "a wallet input as the seed").
            if (a.name().equals("seed not consumed")) continue;
            var m = a.mutate().apply(honest);
            if (m == null) continue;
            var oldSide = v1.harness.evaluate(m);
            var newView = m.copy();
            newView.spendRedeemers.clear();
            newView.mintRedeemer = mine.mintRedeemer;
            var newSide = v2.harness.evaluate(newView);
            v1.validatorRuns++;
            v2.validatorRuns++;
            assertFalse(oldSide.accepted() && newSide.accepted(), "the migration accepts the attack \"" + a.name() + "\"\n" + oldSide + newSide);
            refusedBy.put(a.name(), oldSide.accepted() ? "new" : newSide.accepted() ? "old" : "both");
            v2.attacksRejected++;
        }
        System.out.println("[M3c migration attacks] " + refusedBy);
    }

    static LedgerMapV ledgerMap(List<Object> keys, List<Object> values) {
        return new LedgerMapV(keys, values);
    }
}
