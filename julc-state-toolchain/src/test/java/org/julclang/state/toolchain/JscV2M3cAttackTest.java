package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.ledger.Credential;
import org.julclang.ledger.GovernanceAction;
import org.julclang.ledger.GovernanceActionId;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.ProposalProcedure;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.ScriptInfo;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxCert;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.Value;
import org.julclang.ledger.Vote;
import org.julclang.ledger.Voter;
import org.julclang.state.api.Approval;
import org.julclang.state.api.Approvals;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Send;
import org.julclang.state.examples.dsl.CounterV1;
import org.julclang.state.examples.dsl.CounterV1Client;
import org.julclang.state.examples.dsl.CounterV1Validator;
import org.julclang.state.examples.dsl.CounterV2;
import org.julclang.state.examples.dsl.CounterV2Client;
import org.julclang.state.examples.dsl.CounterV2Validator;
import org.julclang.state.examples.dsl.Guardrail;
import org.julclang.state.examples.dsl.GuardrailClient;
import org.julclang.state.examples.dsl.StakedVault;
import org.julclang.state.examples.dsl.StakedVaultClient;
import org.julclang.state.examples.dsl.StakedVaultValidator;
import org.julclang.state.examples.dsl.StakedCounterV1;
import org.julclang.state.examples.dsl.StakedCounterV1Client;
import org.julclang.state.examples.dsl.StakedCounterV1Validator;
import org.julclang.state.examples.dsl.Steward;
import org.julclang.state.examples.dsl.StewardClient;
import org.julclang.state.examples.dsl.TallyV1;
import org.julclang.state.examples.dsl.TallyV1Client;
import org.julclang.state.examples.dsl.TallyV1Validator;
import org.julclang.state.examples.dsl.TallyV2;
import org.julclang.state.examples.dsl.TallyV2Client;
import org.julclang.state.examples.dsl.TallyV2Validator;
import org.julclang.state.runtime.StateContractHandle;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3c: the security review's items that need a raw transaction or a script context no ledger type can build, on
 * the ports (the attack suites of examples/staking and examples/upgrade run against the ports through copyPortTests:
 * StakingPortTest, UpgradePortTest). A35: a purpose the contract does not handle fails closed. A41: an Approvals list
 * under the withdraw purpose is no Rewards redeemer (the marker). A37: a certificate of a tag past Conway's is refused,
 * not taken by the last arm, even by a handler that accepts every certificate. A44, K48: the adopted old state is checked
 * as data: an Option of tag 2, a map out of canonical order or with a key twice, a state of the wrong arity. A43: a
 * successor that is the old version's own policy is no migration.
 */
class JscV2M3cAttackTest {

    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] ADMIN = StateHarness.fill(28, 0xAD), A = StateHarness.fill(28, 0x11), B = StateHarness.fill(28, 0x22),
            C = StateHarness.fill(28, 0x33), POOL = StateHarness.fill(28, 0x90), PAYEE = StateHarness.fill(28, 0xFA),
            GOVERNOR = StateHarness.fill(28, 0x61), EVE = StateHarness.fill(28, 0xEE);
    static final HexFormat HEX = HexFormat.of();

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    static JulcList<byte[]> keys(byte[]... ks) {
        JulcList<byte[]> l = JulcList.empty();
        for (int i = ks.length - 1; i >= 0; i--) l = l.prepend(ks[i]);
        return l;
    }

    static void accepted(StateHarness.Verdict v, String what) {
        assertTrue(v.accepted(), what + "\n" + v);
    }

    static void refused(StateHarness.Verdict v, String what) {
        assertFalse(v.accepted(), "accepted: " + what + "\n" + v);
    }

    static StakedVault.Config vaultParams() {
        return new StakedVault.Config(keys(A, B, C), BigInteger.TWO, POOL, PAYEE, ADA.multiply(BigInteger.valueOf(1000)));
    }

    // ------------------------------------------------------------------ A35: a purpose not handled fails closed

    @Test
    void aPurposeTheContractDoesNotHandle_failsClosed() {
        var guard = StateHarness.of(GuardrailClient.SPEC, new Guardrail.Config(PAYEE, ADA.multiply(BigInteger.valueOf(1000))));
        var counter = StateHarness.of(CounterV1Client.SPEC);
        var ret = new Credential.PubKeyCredential(new PubKeyHash(A));
        int n = 0;
        // Guardrail handles propose only: its honest proposal is accepted, every other purpose refused.
        accepted(guard.evaluatePropose(guard.withdrawTx(List.of()), new ProposalProcedure(ADA, ret, new GovernanceAction.InfoAction())), "Guardrail's rail");
        for (var h : List.of(guard, counter)) {
            String who = h == guard ? "Guardrail" : "CounterV1";
            var own = h.ownCredential();
            var tx = h.withdrawTx(List.of(), A, B);
            refused(h.evaluateCertify(tx, new TxCert.RegStaking(own, Optional.empty())), who + ": a certificate");
            var id = new GovernanceActionId(new TxId(StateHarness.fill(32, 0x61)), BigInteger.ZERO);
            refused(h.evaluateVote(tx, new Voter.DRepVoter(own), Map.of(id, new Vote.VoteYes())), who + ": a vote");
            // Rewards(74, 0): the withdraw purpose's redeemer shape, which neither declares.
            var rewards = new PlutusData.ConstrData(0, List.of(PlutusData.integer(74), PlutusData.integer(0)));
            refused(h.evaluateWithdraw(tx, rewards, BigInteger.ZERO), who + ": a withdrawal");
            n += 3;
        }
        refused(counter.evaluatePropose(counter.withdrawTx(List.of()), new ProposalProcedure(ADA, ret, new GovernanceAction.InfoAction())),
                "CounterV1: a proposal");
        System.out.println("[M3c attacks] A35: " + (n + 1) + " purposes no handler takes, each refused");
    }

    // ------------------------------------------------------------------ A41: Approvals is no Rewards

    @Test
    void anApprovalsListUnderTheWithdrawPurpose_isNoRewards() {
        var h = StateHarness.of(StakedVaultClient.SPEC, vaultParams());
        BigInteger amount = ADA.multiply(BigInteger.valueOf(5));
        var sends = new ArrayList<Send>();
        StakedVault.collect(vaultParams(), amount).forEach(sends::add);
        var tx = h.withdrawTx(sends, A, B);
        accepted(h.evaluateWithdraw(tx, new StakedVaultValidator.Rewards(BigInteger.valueOf(74), amount), amount), "the honest withdrawal");
        // The approvals a governor would give the withdrawal (its own policy, the purpose's identity, action 1002), as the
        // whole redeemer: an Approvals list (Constr 0 [list]) has no marker 74.
        byte[] target = Blake2bUtil.blake2bHash224(org.julclang.core.cbor.PlutusDataCborEncoder.encode(
                new ScriptInfo.RewardingScript(h.ownCredential()).toPlutusData()));
        var approvals = new Approvals(JulcList.<Approval>empty().prepend(new Approval(h.policy(), target, BigInteger.valueOf(1002))));
        refused(h.evaluateWithdraw(tx, approvals, amount), "an Approvals list as the withdraw redeemer");
        refused(h.evaluateWithdraw(tx, new PlutusData.ConstrData(0, List.of(PlutusData.integer(amount))), amount), "Rewards without its marker");
        refused(h.evaluateWithdraw(tx, new PlutusData.ConstrData(0, List.of(PlutusData.integer(0), PlutusData.integer(amount))), amount),
                "Rewards with marker 0 (the leading field of an empty Approvals' shape)");
        refused(h.evaluateWithdraw(tx, new PlutusData.ConstrData(1, List.of(PlutusData.integer(74), PlutusData.integer(amount))), amount),
                "Rewards under constructor 1");
        System.out.println("[M3c attacks] A41: an Approvals list, a missing or zero marker and another constructor under the withdraw purpose, each refused");
    }

    // ------------------------------------------------------------------ A37: a certificate tag past Conway's

    /** The CERTIFY context of {@code honest} with the certificate replaced by {@code cert} (its ScriptInfo and TxInfo.certificates). */
    static PlutusData withCert(PlutusData ctx, PlutusData cert) {
        var sc = (PlutusData.ConstrData) ctx;
        var info = (PlutusData.ConstrData) sc.fields().get(0);
        var fields = new ArrayList<>(info.fields());
        fields.set(5, new PlutusData.ListData(List.of(cert)));
        var si = (PlutusData.ConstrData) sc.fields().get(2);
        var siFields = new ArrayList<>(si.fields());
        siFields.set(1, cert);
        return new PlutusData.ConstrData(sc.tag(), List.of(new PlutusData.ConstrData(info.tag(), fields), sc.fields().get(1),
                new PlutusData.ConstrData(si.tag(), siFields)));
    }

    /** {@code ctx} with the governor's approval of its CERTIFY purpose (as examples/treasury's Guardian approves), action 1003. */
    static PlutusData approved(PlutusData ctx, byte[] policy, PlutusData scriptInfo) {
        byte[] target = Blake2bUtil.blake2bHash224(org.julclang.core.cbor.PlutusDataCborEncoder.encode(scriptInfo));
        var approvals = StateHarness.data(new Approvals(JulcList.<Approval>empty().prepend(new Approval(policy, target, BigInteger.valueOf(1003)))));
        var sc = (PlutusData.ConstrData) ctx;
        var info = (PlutusData.ConstrData) sc.fields().get(0);
        var fields = new ArrayList<>(info.fields());
        // TxInfo.withdrawals (field 6): the governor's zero withdrawal; TxInfo.redeemers (field 9): its Approvals.
        var gov = new Credential.ScriptCredential(new ScriptHash(GOVERNOR)).toPlutusData();
        fields.set(6, new PlutusData.MapData(List.of(new PlutusData.Pair(gov, PlutusData.integer(0)))));
        var reds = new ArrayList<>(((PlutusData.MapData) fields.get(9)).entries());
        reds.add(new PlutusData.Pair(new PlutusData.ConstrData(2, List.of(gov)), approvals));
        fields.set(9, new PlutusData.MapData(reds));
        return new PlutusData.ConstrData(sc.tag(), List.of(new PlutusData.ConstrData(info.tag(), fields), sc.fields().get(1), sc.fields().get(2)));
    }

    @Test
    void aCertificatePastConway_isRefused_notTakenByTheLastArm() {
        // StakedVault (quorum): its registration accepted; the same certificate under tags 11 and 255 refused.
        var h = StateHarness.of(StakedVaultClient.SPEC, vaultParams());
        var own = h.ownCredential();
        var tx = h.withdrawTx(List.of(), A, B);
        var reg = new TxCert.RegStaking(own, Optional.empty());
        accepted(h.evaluateCertify(tx, reg), "StakedVault's registration");
        var regData = (PlutusData.ConstrData) reg.toPlutusData();
        for (int tag : new int[]{11, 12, 255}) {
            var bad = new PlutusData.ConstrData(tag, regData.fields());
            refused(h.evaluateContext("CERTIFY", withCert(h.certifyContext(tx, reg), bad)), "StakedVault: a certificate of tag " + tag);
        }
        // Steward (authorized by its governor) accepts every certificate its filter admits, its match total over TxCert:
        // ResignColdCommittee, the last arm, is true. A tag past it, approved by the governor for that very purpose, is refused.
        var gov = new Credential.ScriptCredential(new ScriptHash(GOVERNOR));
        var cfg = new Steward.Config(gov, keys(A, B, C), BigInteger.TWO, POOL, PAYEE, StateHarness.T0.add(BigInteger.valueOf(600_000)), new byte[]{1});
        var s = StateHarness.of(StewardClient.SPEC, cfg);
        var sOwn = s.ownCredential();
        var upd = new TxCert.UpdateDRep(sOwn);
        var stx = s.withdrawTx(List.of(), A);
        var honest = s.certifyContext(stx, upd);
        accepted(s.evaluateContext("CERTIFY", approved(honest, s.policy(), ((PlutusData.ConstrData) honest).fields().get(2))), "Steward's DRep update, approved");
        refused(s.evaluateContext("CERTIFY", honest), "Steward's DRep update without its approval");
        for (int tag : new int[]{11, 12}) {
            var bad = new PlutusData.ConstrData(tag, ((PlutusData.ConstrData) upd.toPlutusData()).fields());
            var ctx = withCert(honest, bad);
            refused(s.evaluateContext("CERTIFY", approved(ctx, s.policy(), ((PlutusData.ConstrData) ctx).fields().get(2))),
                    "Steward: an approved certificate of tag " + tag);
        }
        System.out.println("[M3c attacks] A37: certificates of tags 11, 12 and 255 refused (StakedVault), 11 and 12 approved and refused (Steward)");
    }

    // ------------------------------------------------------------------ A44, K48: the adopted state as data

    static byte[] successorId() {
        return HEX.parseHex(StateContractHandle.instanceIdOf(HEX.formatHex(StateHarness.LIVE_REF.txId().hash()), StateHarness.LIVE_REF.index().intValueExact()));
    }

    static Value token(byte[] policy, byte[] name, long q) {
        return Value.singleton(new PolicyId(policy), new TokenName(name), BigInteger.valueOf(q));
    }

    /**
     * TallyV1's instance (its datum {@code oldDatum}, as data) moved by its quorum into TallyV2, adopted with bonus 5 by
     * its admin: the transaction as TallyV2's mint sees it.
     */
    static TxModel tallyAdoption(StateHarness<Object> h1, StateHarness<Object> h2, TallyV1.State honestOld, PlutusData oldDatum) {
        BigInteger funds = ADA.multiply(BigInteger.TEN);
        var live = h1.live(honestOld, funds);
        Ctx c1 = h1.ctx(A, BigInteger.ZERO, live.lovelace());
        JulcList<Payout> none = JulcList.empty();
        var tx = h1.destroy(live, new TallyV1Validator.Move(c1), new TallyV1Validator.BurnInstance(), none, A, c1);
        tx.signers.add(B);
        tx.signers.add(ADMIN);
        var in = tx.inputs.get(0);
        var o = in.resolved();
        tx.inputs.set(0, new TxInInfo(in.outRef(), new TxOut(o.address(), o.value(), new OutputDatum.OutputDatumInline(oldDatum), o.referenceScript())));
        var adopted = new TallyV2.State(ADMIN, honestOld.voters(), BigInteger.valueOf(3), BigInteger.valueOf(5), honestOld.live());
        tx.outputs.add(new TxOut(h2.stateAddress(), Value.lovelace(live.lovelace()).merge(token(h2.policy(), successorId(), 1)),
                new OutputDatum.OutputDatumInline(StateHarness.data(adopted)), Optional.empty()));
        tx.mint = tx.mint.merge(token(h2.policy(), successorId(), 1));
        tx.stateOutput = tx.outputs.size() - 1;
        tx.token = successorId();
        var mine = tx.copy();
        mine.spendRedeemers.clear();
        Ctx c2 = h2.ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO);
        mine.mintRedeemer = StateHarness.data(new TallyV2Validator.Adopt(StateHarness.LIVE_REF, BigInteger.valueOf(5), c2));
        mine.range = StateHarness.window(c2.validFrom(), c2.validTo());
        return mine;
    }

    static PlutusData field(PlutusData d, int i, UnaryOperator<PlutusData> f) {
        var c = (PlutusData.ConstrData) d;
        var fs = new ArrayList<>(c.fields());
        fs.set(i, f.apply(fs.get(i)));
        return new PlutusData.ConstrData(c.tag(), fs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAdoptedState_isCheckedAsData() {
        var h1 = (StateHarness<Object>) (StateHarness<?>) StateHarness.of(TallyV1Client.SPEC);
        var cfg = new TallyV2.Config(h1.policy(), keys(A, B), BigInteger.TWO, StateHarness.T0.add(BigInteger.valueOf(600_000)));
        var h2 = (StateHarness<Object>) (StateHarness<?>) StateHarness.of(TallyV2Client.SPEC, cfg);
        JulcMap<byte[], BigInteger> tags = JulcMap.<byte[], BigInteger>empty().insert(new byte[]{'b'}, BigInteger.valueOf(4)).insert(new byte[]{'a'}, BigInteger.valueOf(3));
        var old = new TallyV1.State(ADMIN, h2.policy(), keys(A, B, C), BigInteger.TWO, tags, Optional.of(new byte[]{'b'}), true,
                new TallyV1.Weighted(BigInteger.valueOf(3)));
        PlutusData honest = StateHarness.data(old);
        accepted(h2.evaluate(tallyAdoption(h1, h2, old, honest)), "the honest adoption");
        var bad = new LinkedHashMap<String, PlutusData>();
        // note: an Option (Some = 0, None = 1) of tag 2, and Some without its field.
        bad.put("the note an Option of tag 2", field(honest, 5, d -> new PlutusData.ConstrData(2, ((PlutusData.ConstrData) d).fields())));
        bad.put("the note Some with no field", field(honest, 5, d -> new PlutusData.ConstrData(0, List.of())));
        // tags: a map out of canonical order, a key twice, a key of 65 bytes, a value that is no integer.
        var a = new PlutusData.Pair(PlutusData.bytes(new byte[]{'a'}), PlutusData.integer(3));
        var b = new PlutusData.Pair(PlutusData.bytes(new byte[]{'b'}), PlutusData.integer(4));
        bad.put("the tags out of canonical order", field(honest, 4, d -> new PlutusData.MapData(List.of(b, a))));
        bad.put("a tag twice", field(honest, 4, d -> new PlutusData.MapData(List.of(a, new PlutusData.Pair(PlutusData.bytes(new byte[]{'a'}), PlutusData.integer(4))))));
        bad.put("a tag key of 65 bytes", field(honest, 4, d -> new PlutusData.MapData(List.of(a, new PlutusData.Pair(PlutusData.bytes(StateHarness.fill(65, 0x62)),
                PlutusData.integer(4))))));
        // voters: a key of 27 bytes, an element that is an integer; admin: 29 bytes.
        bad.put("a voter of 27 bytes", field(honest, 2, d -> new PlutusData.ListData(List.of(PlutusData.bytes(new byte[27]), PlutusData.bytes(B), PlutusData.bytes(C)))));
        bad.put("a voter that is an integer", field(honest, 2, d -> new PlutusData.ListData(List.of(PlutusData.integer(1), PlutusData.bytes(B), PlutusData.bytes(C)))));
        bad.put("an admin of 29 bytes", field(honest, 0, d -> PlutusData.bytes(new byte[29])));
        bad.put("an admin that is an integer", field(honest, 0, d -> PlutusData.integer(1)));
        // Review F3: a Bool the adoption reads and stores (live, field 6) of tag 2: refused (JuLC's Bool decoding).
        bad.put("live a Bool of tag 2", field(honest, 6, d -> new PlutusData.ConstrData(2, List.of())));
        // the state's shape: a field short, a field more, another constructor.
        var fs = ((PlutusData.ConstrData) honest).fields();
        bad.put("a field short", new PlutusData.ConstrData(0, fs.subList(0, 7)));
        var more = new ArrayList<>(fs);
        more.add(PlutusData.integer(0));
        bad.put("a field more", new PlutusData.ConstrData(0, more));
        bad.put("constructor 1", new PlutusData.ConstrData(1, fs));
        for (var e : bad.entrySet()) refused(h2.evaluate(tallyAdoption(h1, h2, old, e.getValue())), "TallyV2 adopts an old state with " + e.getKey());
        // Stated (review F3, semantics §4): the sum types' other malformations are taken as the previous version wrote them,
        // as a read state's are. The previous version is a .jsc contract (init migrate imports one), whose validator checks
        // every output datum against its typed state, so an instance holding its token never has one of these. The outcomes,
        // as measured: a Bool with a field (stored as written), an enum of tag 2, Weighted without its field and Plain with
        // one (never read) are accepted.
        var stated = new LinkedHashMap<String, PlutusData>();
        stated.put("live a Bool with a field", field(honest, 6, d -> new PlutusData.ConstrData(1, List.of(PlutusData.integer(0)))));
        stated.put("mode an enum of tag 2", field(honest, 7, d -> new PlutusData.ConstrData(2, List.of())));
        stated.put("mode Weighted without its field", field(honest, 7, d -> new PlutusData.ConstrData(1, List.of())));
        stated.put("mode Plain with a field", field(honest, 7, d -> new PlutusData.ConstrData(0, List.of(PlutusData.integer(1)))));
        for (var e : stated.entrySet()) accepted(h2.evaluate(tallyAdoption(h1, h2, old, e.getValue())), "stated: " + e.getKey());
        // Stated (D5): the check is the checked-input rule (key and hash lengths, map order and keys), as for a read state;
        // a value's kind is its writer's type, so an Int that is bytes in a field the adoption never reads (a tag's value)
        // is not looked at: read, it would fail. No build of TallyV1 can write one (its outputs are its typed states).
        var unread = field(honest, 4, d -> new PlutusData.MapData(List.of(a, new PlutusData.Pair(PlutusData.bytes(new byte[]{'b'}),
                PlutusData.bytes(new byte[]{4})))));
        accepted(h2.evaluate(tallyAdoption(h1, h2, old, unread)), "stated: a tag value of the wrong kind, never read");
        System.out.println("[M3c attacks] A44/K48: " + bad.size() + " malformed old states refused by the adopting mint: " + bad.keySet());
    }


    // ------------------------------------------------------------------ review S1 / F2: the handshake's two sides

    /** CounterV1's migrating destroy of a deposit-only instance, with CounterV2's instance minted for it holding {@code next}. */
    static TxModel counterMigration(StateHarness<Object> h1, StateHarness<Object> h2, Object next) {
        var s = new CounterV1.State(ADMIN, h2.policy(), BigInteger.valueOf(41));
        var live = h1.live(s, BigInteger.ZERO);
        Ctx c = h1.ctx(ADMIN, BigInteger.ZERO, live.lovelace());
        JulcList<Payout> none = JulcList.empty();
        var tx = h1.destroy(live, new CounterV1Validator.Upgrade(c), new CounterV1Validator.BurnInstance(), none, ADMIN, c);
        tx.outputs.add(new TxOut(h2.stateAddress(), Value.lovelace(BigInteger.valueOf(h2.minAda())).merge(token(h2.policy(), successorId(), 1)),
                new OutputDatum.OutputDatumInline(StateHarness.data(next)), Optional.empty()));
        tx.mint = tx.mint.merge(token(h2.policy(), successorId(), 1));
        tx.stateOutput = tx.outputs.size() - 1;
        tx.token = successorId();
        return tx;
    }

    static void scriptRejects(StateHarness.Verdict v, String prefix, String what) {
        JscV2M3cFourWayTest.scriptRejects(v, prefix, what);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anOrdinaryInitSeededByThePreviousInstance_isNoAdoption() {
        var h1 = (StateHarness<Object>) (StateHarness<?>) StateHarness.of(CounterV1Client.SPEC);
        var h2 = (StateHarness<Object>) (StateHarness<?>) StateHarness.of(CounterV2Client.SPEC, new CounterV2.Config(h1.policy()));
        assertTrue(h2.minAda() >= h1.minAda(), "the successor's deposit covers the old one's");
        // The state the attacker makes up (EVE the admin), in place of the adoption's (the old admin's, the old count).
        var made = new CounterV2.State(EVE, BigInteger.ZERO, BigInteger.ONE);
        var tx = counterMigration(h1, h2, made);
        tx.signers.add(EVE);
        // The old side accepts it: its rules are the successor's token, address and value, which the ordinary init meets.
        accepted(h1.evaluate(tx), "the old side, as it was before and after S1 (its half is sound alone)");
        Ctx c2 = h2.ctx(EVE, BigInteger.ZERO, BigInteger.ZERO);
        var mine = tx.copy();
        mine.spendRedeemers.clear();
        mine.mintRedeemer = StateHarness.data(new CounterV2Validator.CreateInstance(StateHarness.LIVE_REF, c2));
        mine.range = StateHarness.window(c2.validFrom(), c2.validTo());
        scriptRejects(h2.evaluate(mine), "MINT", "CounterV2's ordinary init seeded by the CounterV1 instance (S1)");
        // Controls: the same init from a wallet seed is accepted; the honest adoption of the same instance is accepted.
        accepted(h2.evaluate(h2.create(new CounterV2Validator.CreateInstance(h2.seed(), c2), made, c2)), "CounterV2's init from a wallet seed");
        var adopted = new CounterV2.State(ADMIN, BigInteger.valueOf(41), BigInteger.ONE);
        var honest = counterMigration(h1, h2, adopted).copy();
        honest.spendRedeemers.clear();
        honest.mintRedeemer = StateHarness.data(new CounterV2Validator.Adopt(StateHarness.LIVE_REF));
        accepted(h2.evaluate(honest), "the honest adoption");
        System.out.println("[M3c attacks] S1: an ordinary init seeded by the previous instance refused by the new mint (the old side accepts it; the controls pass)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoOldInstancesIntoOneNew_isRefused() {
        var h1 = (StateHarness<Object>) (StateHarness<?>) StateHarness.of(CounterV1Client.SPEC);
        var h2 = (StateHarness<Object>) (StateHarness<?>) StateHarness.of(CounterV2Client.SPEC, new CounterV2.Config(h1.policy()));
        var adopted = new CounterV2.State(ADMIN, BigInteger.valueOf(41), BigInteger.ONE);
        var tx = counterMigration(h1, h2, adopted);
        accepted(h1.evaluate(tx), "the control: one instance into one");
        // A second CounterV1 instance spent by its migrating destroy in the same transaction, its token burned, with no
        // successor of its own: only the first one's is minted.
        var ref2 = new org.julclang.ledger.TxOutRef(new TxId(StateHarness.fill(32, 0x7B)), BigInteger.ONE);
        byte[] id2 = HEX.parseHex(StateContractHandle.instanceIdOf(HEX.formatHex(ref2.txId().hash()), ref2.index().intValueExact()));
        var s2 = new CounterV1.State(ADMIN, h2.policy(), BigInteger.valueOf(7));
        var two = tx.copy();
        two.inputs.add(new TxInInfo(ref2, new TxOut(h1.stateAddress(), Value.lovelace(BigInteger.valueOf(h1.minAda())).merge(token(h1.policy(), id2, 1)),
                new OutputDatum.OutputDatumInline(StateHarness.data(s2)), Optional.empty())));
        Ctx c = h1.ctx(ADMIN, BigInteger.ZERO, BigInteger.valueOf(h1.minAda()));
        two.spendRedeemers.put(ref2, StateHarness.data(new CounterV1Validator.Upgrade(c)));
        two.mint = two.mint.merge(token(h1.policy(), id2, -1));
        scriptRejects(h1.evaluate(two), "SPEND", "two CounterV1 instances into one CounterV2 (F2)");
        System.out.println("[M3c attacks] F2: two old instances into one new refused by the old side (the second has no successor)");
    }


    // ------------------------------------------------------------------ M3 final pass MS1: a staked contract migrates

    @Test
    @SuppressWarnings("unchecked")
    void aStakedContractMigrates_andItsMigrationIsChecked() {
        var cfg = new StakedCounterV1.Config(keys(A, B, C), BigInteger.TWO, POOL, PAYEE);
        var h1 = (StateHarness<Object>) (StateHarness<?>) StateHarness.of(StakedCounterV1Client.SPEC, cfg);
        var h2 = (StateHarness<Object>) (StateHarness<?>) StateHarness.of(CounterV2Client.SPEC, new CounterV2.Config(h1.policy()));
        assertTrue(h1.stateAddress().stakingCredential().isPresent(), "stakeSelf: the state is staked to its own script");
        var adopted = new CounterV2.State(ADMIN, BigInteger.valueOf(41), BigInteger.ONE);
        java.util.function.BiFunction<org.julclang.ledger.Address, Long, TxModel> migration = (to, delta) -> {
            var s = new StakedCounterV1.State(ADMIN, h2.policy(), BigInteger.valueOf(41));
            var live = h1.live(s, ADA.multiply(BigInteger.TEN));
            Ctx c = h1.ctx(ADMIN, BigInteger.ZERO, live.lovelace());
            JulcList<Payout> none = JulcList.empty();
            var tx = h1.destroy(live, new StakedCounterV1Validator.Upgrade(c), new StakedCounterV1Validator.BurnInstance(), none, ADMIN, c);
            tx.outputs.add(new TxOut(to, Value.lovelace(live.lovelace().add(BigInteger.valueOf(delta))).merge(token(h2.policy(), successorId(), 1)),
                    new OutputDatum.OutputDatumInline(StateHarness.data(adopted)), Optional.empty()));
            tx.mint = tx.mint.merge(token(h2.policy(), successorId(), 1));
            tx.stateOutput = tx.outputs.size() - 1;
            tx.token = successorId();
            return tx;
        };
        var honest = migration.apply(h2.stateAddress(), 0L);
        accepted(h1.evaluate(honest), "the staked old side (it refused every migration before the fix)");
        var mine = honest.copy();
        mine.spendRedeemers.clear();
        mine.mintRedeemer = StateHarness.data(new CounterV2Validator.Adopt(StateHarness.LIVE_REF));
        accepted(h2.evaluate(mine), "CounterV2 adopts the staked instance");
        // The old side's I41 rules, each refused by its SPEND script.
        scriptRejects(h1.evaluate(migration.apply(StateHarness.pkhAddress(EVE), 0L)), "SPEND", "the successor at a key address");
        scriptRejects(h1.evaluate(migration.apply(h2.stateAddress(), -1L)), "SPEND", "one lovelace skimmed");
        var noMint = migration.apply(h2.stateAddress(), 0L);
        noMint.mint = JscV2M3cFourWayTest.withoutZeros(noMint.mint.merge(token(h2.policy(), successorId(), -1)));
        scriptRejects(h1.evaluate(noMint), "SPEND", "the successor's output, but no mint");
        var notBurned = migration.apply(h2.stateAddress(), 0L);
        notBurned.mint = JscV2M3cFourWayTest.withoutZeros(notBurned.mint.merge(token(h1.policy(), h1.instanceId(), 1)));
        scriptRejects(h1.evaluate(notBurned), "SPEND", "the old token not burned");
        System.out.println("[M3c attacks] MS1: a staked contract (withdraw and certify handlers) migrates into CounterV2; four I41 mutants refused by its SPEND");
    }

    // ------------------------------------------------------------------ A43: the successor is no own policy

    @Test
    @SuppressWarnings("unchecked")
    void aSuccessorThatIsTheOldPolicy_isNoMigration() {
        var h1 = (StateHarness<Object>) (StateHarness<?>) StateHarness.of(CounterV1Client.SPEC);
        var h2 = (StateHarness<Object>) (StateHarness<?>) StateHarness.of(CounterV2Client.SPEC, new CounterV2.Config(h1.policy()));
        BigInteger funds = ADA.multiply(BigInteger.TEN);
        for (byte[] to : List.of(h2.policy(), h1.policy())) {
            boolean own = java.util.Arrays.equals(to, h1.policy());
            var s = new CounterV1.State(ADMIN, to, BigInteger.valueOf(41));
            var live = h1.live(s, funds);
            Ctx c = h1.ctx(ADMIN, BigInteger.ZERO, live.lovelace());
            JulcList<Payout> none = JulcList.empty();
            var tx = h1.destroy(live, new CounterV1Validator.Upgrade(c), new CounterV1Validator.BurnInstance(), none, ADMIN, c);
            // The successor's instance: minted under the policy the field names, at that version's address, with the value.
            var addr = own ? h1.stateAddress() : h2.stateAddress();
            Object next = own ? new CounterV1.State(ADMIN, new byte[0], BigInteger.valueOf(41)) : new CounterV2.State(ADMIN, BigInteger.valueOf(41), BigInteger.ONE);
            tx.outputs.add(new TxOut(addr, Value.lovelace(live.lovelace()).merge(token(to, successorId(), 1)),
                    new OutputDatum.OutputDatumInline(StateHarness.data(next)), Optional.empty()));
            tx.mint = tx.mint.merge(token(to, successorId(), 1));
            var v = h1.evaluate(tx);
            if (own) refused(v, "CounterV1 migrating into its own policy");
            else accepted(v, "CounterV1 migrating into CounterV2");
        }
        System.out.println("[M3c attacks] A43: a migration into the old version's own policy refused (the honest one accepted)");
    }
}
