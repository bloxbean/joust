package org.julclang.state.toolchain;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.state.api.Ctx;
import org.julclang.state.examples.dsl.Bank;
import org.julclang.state.examples.dsl.BankClient;
import org.julclang.state.examples.dsl.BankValidator;
import org.julclang.state.examples.dsl.Members;
import org.julclang.state.examples.dsl.MembersClient;
import org.julclang.state.examples.dsl.MembersValidator;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3a1: the security review's attacks on owned objects (scratchpad m3-attacks.md: A2, A4, A5, A6, A13, A17)
 * that the five-way vectors, the attack suite and the copied examples/bank tests do not already carry, each a raw
 * transaction against the ports' validators (Bank, Members). Every rejection has an accepted control beside it, so the
 * rejection is the attack's.
 */
class JscV2M3a1AttackTest {

    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final BigInteger FOUR = ADA.multiply(BigInteger.valueOf(4)), TEN = ADA.multiply(BigInteger.TEN);
    static final byte[] ADMIN = StateHarness.fill(28, 0xAD), ANN = StateHarness.fill(28, 0xA1);

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    final StateHarness<Bank.State> h = StateHarness.of(BankClient.SPEC);
    final Bank.State open = new Bank.State(ADMIN, false, ADA.multiply(BigInteger.valueOf(50)));
    final Bank.State paused = new Bank.State(ADMIN, true, ADA.multiply(BigInteger.valueOf(50)));
    final Bank.Account acct = new Bank.Account(ANN, TEN);
    final Ctx ctx = h.ctx(ANN, BigInteger.ZERO, TEN);
    final Bank.AccountOutcome withdrawn = Bank.withdraw(acct, open, FOUR, ctx);

    BankValidator.Holding held(Bank.Account a) {
        return new BankValidator.Holding(h.instanceId(), a);
    }

    /** ANN's withdrawal of four ada, reading {@code shared} by reference (null: no reference). */
    TxModel withdraw(Bank.State shared) {
        return h.actObject(h.liveObject(held(acct), TEN), new BankValidator.Withdraw(FOUR, ctx), held(withdrawn.state()),
                withdrawn.payouts(), ANN, ctx, shared);
    }

    static TxInInfo at(int b, TxOut out) {
        return new TxInInfo(new TxOutRef(new TxId(StateHarness.fill(32, b)), BigInteger.ZERO), out);
    }

    static TxOut withToken(TxOut base, byte[] policy, byte[] name, Object datum, BigInteger lovelace) {
        return new TxOut(base.address(), Value.lovelace(lovelace).merge(Value.singleton(new PolicyId(policy), new TokenName(name), BigInteger.ONE)),
                new OutputDatum.OutputDatumInline(StateHarness.data(datum)), Optional.empty());
    }

    static byte[] objectToken(int b) {
        byte[] t = new byte[29];
        t[0] = 0x4F;
        System.arraycopy(StateHarness.fill(28, b), 0, t, 1, 28);
        return t;
    }

    /** A2: an object UTxO of the policy (a 29-byte 0x4F token) is no instance: offered as the shared reference, it is refused. */
    @Test
    void anObjectOfferedAsTheInstance_isRefused() {
        h.assertAccepted(withdraw(open));
        var tx = withdraw(null);
        // Its datum is the bank's State (so only the token tells it apart), and it carries no instance token.
        tx.referenceInputs.add(at(0x5B, withToken(h.stateOut(open, BigInteger.ZERO), h.policy(), objectToken(0x5B), open,
                BigInteger.valueOf(h.minAda()))));
        h.assertRejected(tx, "an object UTxO as the shared reference");
    }

    /** A4: the shared read is the object's own instance: another, unpaused bank of the same policy does not stand in for it. */
    @Test
    void aSecondInstanceAsTheSharedRead_isRefused_whicheverOrder() {
        byte[] other = StateHarness.fill(32, 0x77);
        var alone = withdraw(null);
        alone.referenceInputs.add(at(0x5A, h.instanceOut(other, open, BigInteger.valueOf(h.minAda()))));
        h.assertRejected(alone, "an unpaused second instance of the policy as the shared read");
        var wrongFirst = withdraw(paused);
        wrongFirst.referenceInputs.add(0, at(0x5A, h.instanceOut(other, open, BigInteger.valueOf(h.minAda()))));
        h.assertRejected(wrongFirst, "the own (paused) bank with an unpaused other bank before it");
        var wrongLast = withdraw(paused);
        wrongLast.referenceInputs.add(at(0x5A, h.instanceOut(other, open, BigInteger.valueOf(h.minAda()))));
        h.assertRejected(wrongLast, "the own (paused) bank with an unpaused other bank after it");
        h.assertAccepted(withdraw(open));
    }

    /**
     * A5 (decision, ADR-0018 r23): an owned action's shared read is a reference input, so it sees the instance as the
     * transaction's inputs hold it, never an instance action's output in the same transaction. Pausing the bank while
     * withdrawing, the withdrawal reading the unpaused input: the scripts accept it, each reading the inputs' state, and
     * that is the defined outcome. Today's ledger refuses the transaction (it spends and references one UTxO:
     * cardano-ledger's disjointRefInputs, enforced at protocol versions 9 and 10 only); the runtime never composes the two.
     */
    @Test
    void aPauseComposedWithAWithdrawal_readsTheInputsState() {
        TxModel tx = withdraw(open);
        TxModel pause = h.act(h.live(open, BigInteger.ZERO), new BankValidator.SetPaused(true), Bank.setPaused(open, true), ADMIN, null);
        tx.inputs.add(pause.inputs.get(0));
        tx.spendRedeemers.putAll(pause.spendRedeemers);
        tx.outputs.add(pause.outputs.get(pause.stateOutput));
        tx.signers.add(ADMIN);
        assertTrue(tx.referenceInputs.stream().anyMatch(r -> r.outRef().equals(pause.inputs.get(0).outRef())),
                "the instance UTxO is both spent and referenced");
        var v = h.evaluate(tx);
        System.out.println("[M3a1 A5] pause + withdraw in one transaction, scripts only: " + v);
        assertTrue(v.accepted(), "each script reads the inputs' state, so both accept: " + v);
        // Reading the paused bank (as a composition might wish the withdrawal to), the withdrawal is refused.
        h.assertRejected(withdraw(paused), "a withdrawal reading a paused bank");
    }

    /** A6: the instance UTxO spent with an owned redeemer (its token is 32 bytes; an owned arm takes only 29-byte 0x4F tokens). */
    @Test
    void theInstanceSpentWithAnOwnedRedeemer_isRefused() {
        var live = h.live(open, BigInteger.ZERO);
        h.assertAccepted(h.act(live, new BankValidator.SetPaused(true), Bank.setPaused(open, true), ADMIN, null));
        Ctx admin = h.ctx(ADMIN, ADA, BigInteger.ZERO);
        h.assertRejected(h.act(live, new BankValidator.Deposit(admin), open, ADMIN, admin), "an owned action's redeemer on the instance");
        Ctx none = h.ctx(ADMIN, BigInteger.ZERO, BigInteger.ZERO);
        h.assertRejected(h.act(live, new BankValidator.Withdraw(ADA, none), open, ADMIN, none),
                "an owned action's redeemer (one with a shared read) on the instance");
    }

    /**
     * A13: an owned action's payout carries its object's input as its datum (I7), so two objects withdrawing in one
     * transaction cannot share a payout: the second object's withdrawal with no payout of its own is refused, a payout
     * large enough for both but tagged with one is refused, and one payout each is accepted.
     */
    @Test
    void twoObjectsCannotShareOnePayout() {
        TxModel one = withdraw(open);
        h.assertAccepted(one);
        TxOutRef ref2 = new TxOutRef(new TxId(StateHarness.fill(32, 0x0C)), BigInteger.ZERO);
        BigInteger lovelace = BigInteger.valueOf(h.minAda()).add(TEN);
        TxOut base = h.stateOut(open, BigInteger.ZERO);
        TxModel two = one.copy();
        two.inputs.add(new TxInInfo(ref2, withToken(base, h.policy(), objectToken(0x22), held(acct), lovelace)));
        two.spendRedeemers.put(ref2, StateHarness.data(new BankValidator.Withdraw(FOUR, ctx)));
        two.outputs.add(withToken(base, h.policy(), objectToken(0x22), held(withdrawn.state()), lovelace.subtract(FOUR)));
        h.assertRejected(two, "a second object's withdrawal riding on the first one's payout");
        TxModel doubled = two.copy();
        int idx = doubled.payoutOutputs.get(0);
        TxOut p = doubled.outputs.get(idx);
        doubled.outputs.set(idx, new TxOut(p.address(), Value.lovelace(FOUR.add(FOUR)), p.datum(), p.referenceScript()));
        h.assertRejected(doubled, "one payout covering both objects, tagged with one");
        TxModel both = two.copy();
        both.outputs.add(new TxOut(StateHarness.pkhAddress(ANN), Value.lovelace(FOUR), new OutputDatum.OutputDatumInline(StateHarness.data(ref2)),
                Optional.empty()));
        h.assertAccepted(both);
    }

    /**
     * A17 (#91 at a new position): an owned init's enum argument with a constructor the type does not have. The object
     * would hold it, so the datum carries it too, and only the validator's decoding can refuse it.
     */
    @Test
    void anOwnedInitsMalformedEnumArgument() {
        var config = new Members.Config(ADA.multiply(BigInteger.TWO));
        StateHarness<Members.State> m = StateHarness.of(MembersClient.SPEC, config);
        var club = new Members.State(ADMIN, true, BigInteger.ZERO);
        Ctx join = m.ctx(ANN, ADA.multiply(BigInteger.valueOf(5)), BigInteger.ZERO);
        JulcList<byte[]> guardians = JulcList.<byte[]>empty().prepend(StateHarness.fill(28, 0x33)).prepend(StateHarness.fill(28, 0x22))
                .prepend(StateHarness.fill(28, 0x11));
        BigInteger until = StateHarness.T0.add(BigInteger.valueOf(1_000_000));
        var made = Members.join(club, config, guardians, BigInteger.TWO, until, new Members.Basic(), join);
        var redeemer = new MembersValidator.Join(m.seed(), m.instanceId(), guardians, BigInteger.TWO, until, new Members.Basic(), join);
        var holding = new MembersValidator.Holding(m.instanceId(), made);
        m.assertAccepted(m.createObject(redeemer, holding, club, join));
        // Tier has the tags 0 (Basic) and 1 (Gold { since }): the argument (Join's field 5) and the object's tier
        // (Member's field 4) take tag 2, then a Gold without its field.
        PlutusData r0 = StateHarness.data(redeemer), h0 = StateHarness.data(holding);
        PlutusData member = ((PlutusData.ConstrData) h0).fields().get(1);
        for (PlutusData bad : new PlutusData[]{PlutusData.constr(2), PlutusData.constr(1)}) {
            var v = m.evaluate(m.createObject(with(r0, 5, bad), with(h0, 1, with(member, 4, bad)), club, join));
            System.out.println("[M3a1 A17] Members.join with the Tier " + bad + ": " + v);
            assertFalse(v.accepted(), "a malformed Tier " + bad + " is refused by the validator: " + v);
        }
    }

    static PlutusData with(PlutusData c, int i, PlutusData v) {
        var cd = (PlutusData.ConstrData) c;
        var fs = new ArrayList<>(cd.fields());
        fs.set(i, v);
        return new PlutusData.ConstrData(cd.constructorTag(), fs);
    }
}
