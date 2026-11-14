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
        for (String c : List.of("StakedVault", "CounterV1", "CounterV2", "Steward", "TallyV1", "TallyV2")) {
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

    static LedgerMapV ledgerMap(List<Object> keys, List<Object> values) {
        return new LedgerMapV(keys, values);
    }
}
