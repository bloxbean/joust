package org.julclang.state.examples.upgrade;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.Value;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Payout;
import org.julclang.state.runtime.StateContractHandle;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-0011 D4: v1 migrates into v2 in one transaction, both sides checking the handshake. */
class UpgradeTest {
    static final byte[] ADMIN = StateHarness.fill(28, 0xAD), EVE = StateHarness.fill(28, 0xEE);
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    final StateHarness<CounterV1.State> v1 = StateHarness.of(CounterV1Client.SPEC);
    final CounterV2.Config cfg = new CounterV2.Config(v1.policy());
    final StateHarness<CounterV2.State> v2 = StateHarness.of(CounterV2Client.SPEC, cfg);

    byte[] successorId() {
        return HexFormat.of().parseHex(StateContractHandle.instanceIdOf(
                HexFormat.of().formatHex(StateHarness.LIVE_REF.txId().hash()), StateHarness.LIVE_REF.index().intValueExact()));
    }

    /** The migration transaction: v1's upgrade (spend + burn) and v2's adoption (mint + new output). */
    TxModel migration(byte[] successorPolicyInState, BigInteger newLovelace) {
        var old = new CounterV1.State(ADMIN, successorPolicyInState, BigInteger.valueOf(41));
        var live = v1.live(old, ADA.multiply(BigInteger.TEN));
        Ctx ctx = v1.ctx(ADMIN, BigInteger.ZERO, live.lovelace());
        JulcList<Payout> none = JulcList.empty();
        var tx = v1.destroy(live, new CounterV1Validator.Upgrade(ctx), new CounterV1Validator.BurnInstance(), none, ADMIN, ctx);
        var adopted = CounterV2.adopt(cfg, old);
        tx.outputs.add(new TxOut(v2.stateAddress(), Value.lovelace(newLovelace).merge(
                Value.singleton(new org.julclang.ledger.PolicyId(v2.policy()), new org.julclang.ledger.TokenName(successorId()), BigInteger.ONE)),
                new OutputDatum.OutputDatumInline(StateHarness.data(adopted)), Optional.empty()));
        tx.mint = tx.mint.merge(Value.singleton(new org.julclang.ledger.PolicyId(v2.policy()),
                new org.julclang.ledger.TokenName(successorId()), BigInteger.ONE));
        return tx;
    }

    /** The same transaction as v2's minting policy sees it (its own Adopt redeemer). */
    TxModel asV2(TxModel tx) {
        var c = tx.copy();
        c.spendRedeemers.clear();
        c.mintRedeemer = StateHarness.data(new CounterV2Validator.Adopt(StateHarness.LIVE_REF));
        return c;
    }

    @Test
    void migrate_v1IntoV2() {
        BigInteger lovelace = BigInteger.valueOf(CounterV1Client.SPEC.minAda()).add(ADA.multiply(BigInteger.TEN));
        var tx = migration(v2.policy(), lovelace);
        System.out.println("[v1 upgrade] " + v1.assertAccepted(tx));
        System.out.println("[v2 adopt] " + v2.assertAccepted(asV2(tx)));
        assertEquals(BigInteger.valueOf(41), CounterV2.adopt(cfg, new CounterV1.State(ADMIN, v2.policy(), BigInteger.valueOf(41))).count());
    }

    @Test
    void handshake_violations_rejected() {
        BigInteger lovelace = BigInteger.valueOf(CounterV1Client.SPEC.minAda()).add(ADA.multiply(BigInteger.TEN));
        // v1 names another successor: v1 refuses (its funds may only go where the admin said).
        v1.assertRejected(migration(StateHarness.fill(28, 0x5E), lovelace), "successor not the one named");
        // The new instance holds less than the old one: both sides refuse.
        var short1 = migration(v2.policy(), lovelace.subtract(ADA));
        v1.assertRejected(short1, "value not conserved (old side)");
        v2.assertRejected(asV2(short1), "value not conserved (new side)");
        // No successor minted: v1 refuses.
        var noMint = migration(v2.policy(), lovelace);
        noMint.mint = Value.singleton(new org.julclang.ledger.PolicyId(v1.policy()),
                new org.julclang.ledger.TokenName(v1.instanceId()), BigInteger.ONE.negate());
        noMint.outputs.remove(noMint.outputs.size() - 1);
        v1.assertRejected(noMint, "no successor");
        // The old instance does not end (its token is not burned): v2 refuses to adopt.
        var notBurned = asV2(migration(v2.policy(), lovelace));
        notBurned.mint = Value.singleton(new org.julclang.ledger.PolicyId(v2.policy()),
                new org.julclang.ledger.TokenName(successorId()), BigInteger.ONE);
        v2.assertRejected(notBurned, "the previous instance must end");
    }
    /**
     * ADR-0018 M3c review S1, S6 (I41): v2's ordinary creation seeded by the v1 instance (its token named as @MigrateTo
     * expects) in place of the adoption. The v1 instance holds only its deposit, so v2's exact-value rule fits and only
     * the seed rule refuses it; the old side accepts the same transaction. Controls: v2's creation from a wallet seed.
     */
    @Test
    void anOrdinaryCreationSeededByTheV1Instance_isRefused() {
        assertTrue(CounterV2Client.SPEC.minAda() >= CounterV1Client.SPEC.minAda(), "the successor's deposit covers the old one's");
        var old = new CounterV1.State(ADMIN, v2.policy(), BigInteger.valueOf(41));
        var live = v1.live(old, BigInteger.ZERO);
        Ctx c1 = v1.ctx(ADMIN, BigInteger.ZERO, live.lovelace());
        JulcList<Payout> none = JulcList.empty();
        var tx = v1.destroy(live, new CounterV1Validator.Upgrade(c1), new CounterV1Validator.BurnInstance(), none, ADMIN, c1);
        Ctx c2 = v2.ctx(EVE, BigInteger.ZERO, BigInteger.ZERO);
        // A state EVE makes up (EVE the admin, the count reset), as v2's own creation writes it (open's state; built here,
        // not called, since the .jsc port's creation is named init: examples/dsl runs this test against it).
        var made = new CounterV2.State(EVE, BigInteger.ZERO, BigInteger.ONE);
        var token = Value.singleton(new org.julclang.ledger.PolicyId(v2.policy()), new org.julclang.ledger.TokenName(successorId()), BigInteger.ONE);
        tx.outputs.add(new TxOut(v2.stateAddress(), Value.lovelace(BigInteger.valueOf(CounterV2Client.SPEC.minAda())).merge(token),
                new OutputDatum.OutputDatumInline(StateHarness.data(made)), Optional.empty()));
        tx.mint = tx.mint.merge(token);
        tx.signers.add(EVE);
        v1.assertAccepted(tx);   // the old half alone is satisfied: its successor's token, address and value
        var ordinary = tx.copy();
        ordinary.spendRedeemers.clear();
        ordinary.mintRedeemer = StateHarness.data(new CounterV2Validator.CreateInstance(StateHarness.LIVE_REF, c2));
        ordinary.range = StateHarness.window(c2.validFrom(), c2.validTo());
        v2.assertRejected(ordinary, "an ordinary creation consumes the previous version's instance");
        v2.assertAccepted(v2.create(new CounterV2Validator.CreateInstance(v2.seed(), c2), made, c2));
    }
}
