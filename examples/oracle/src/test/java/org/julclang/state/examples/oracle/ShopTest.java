package org.julclang.state.examples.oracle;

import org.julclang.core.PlutusData;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.ForeignInstance;
import org.julclang.state.api.RequireException;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-0011 D2: a shop reading an oracle's state by reference, pinned by params and fully authenticated. */
class ShopTest {
    static final byte[] OWNER = StateHarness.fill(28, 0x0A);
    static final byte[] BUYER = StateHarness.fill(28, 0xB0);
    static final byte[] ORACLE_POLICY = StateHarness.fill(28, 0x0C);
    static final byte[] ORACLE_ID = StateHarness.fill(32, 0x0D);
    static final BigInteger PRICE = BigInteger.valueOf(3_000_000);

    final Shop.Config cfg = new Shop.Config(new ForeignInstance(ORACLE_POLICY, ORACLE_ID), BigInteger.valueOf(600_000));
    final StateHarness<Shop.State> h = StateHarness.of(ShopClient.SPEC, cfg);
    final Shop.State shop = new Shop.State(OWNER, BigInteger.ZERO);

    static TxInInfo oracleInput(Address at, Value value, Object state) {
        return new TxInInfo(new TxOutRef(new TxId(StateHarness.fill(32, 0x0E)), BigInteger.ZERO),
                new TxOut(at, value, new OutputDatum.OutputDatumInline(StateHarness.data(state)), Optional.empty()));
    }

    static Address oracleAddress() {
        return new Address(new Credential.ScriptCredential(new ScriptHash(ORACLE_POLICY)), Optional.empty());
    }

    static Value oracleValue() {
        return Value.lovelace(BigInteger.valueOf(2_000_000))
                .merge(Value.singleton(new PolicyId(ORACLE_POLICY), new TokenName(ORACLE_ID), BigInteger.ONE));
    }

    Oracle.State fresh() {
        return new Oracle.State(StateHarness.fill(28, 0x9B), PRICE, StateHarness.T0.subtract(BigInteger.valueOf(60_000)));
    }

    TxModel buy(Oracle.State px, TxInInfo oracle) {
        var live = h.live(shop, BigInteger.ZERO);
        Ctx ctx = h.ctx(BUYER, PRICE.multiply(BigInteger.TWO), BigInteger.ZERO);
        var tx = h.act(live, new ShopValidator.Buy(BigInteger.TWO, ctx), new Shop.State(OWNER, BigInteger.TWO), BUYER, ctx);
        if (oracle != null) tx.referenceInputs.add(oracle);
        return tx;
    }

    @Test
    void buy_atAFreshOraclePrice() {
        Ctx ctx = h.ctx(BUYER, PRICE.multiply(BigInteger.TWO), BigInteger.ZERO);
        assertEquals(BigInteger.TWO, Shop.buy(shop, cfg, fresh(), BigInteger.TWO, ctx).sold());
        var tx = buy(fresh(), oracleInput(oracleAddress(), oracleValue(), fresh()));
        System.out.println("[buy via @Reads] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void stalePrice_rejectedOnTheJvmAndOnChain() {
        var stale = new Oracle.State(StateHarness.fill(28, 0x9B), PRICE, StateHarness.T0.subtract(BigInteger.valueOf(700_000)));
        Ctx ctx = h.ctx(BUYER, PRICE.multiply(BigInteger.TWO), BigInteger.ZERO);
        assertThrows(RequireException.class, () -> Shop.buy(shop, cfg, stale, BigInteger.TWO, ctx));
        h.assertRejected(buy(stale, oracleInput(oracleAddress(), oracleValue(), stale)), "stale price");
    }

    @Test
    void spoofedOracles_rejected() {
        h.assertRejected(buy(fresh(), null), "no oracle reference input");
        h.assertRejected(buy(fresh(), oracleInput(StateHarness.pkhAddress(StateHarness.fill(28, 0xEE)), oracleValue(), fresh())),
                "the oracle token at a key address");
        var otherPolicy = Value.lovelace(BigInteger.valueOf(2_000_000))
                .merge(Value.singleton(new PolicyId(StateHarness.fill(28, 0x0F)), new TokenName(ORACLE_ID), BigInteger.ONE));
        h.assertRejected(buy(fresh(), oracleInput(oracleAddress(), otherPolicy, fresh())), "another policy's token");
        var twoTokens = oracleValue().merge(Value.singleton(new PolicyId(ORACLE_POLICY), new TokenName(new byte[]{1}), BigInteger.ONE));
        h.assertRejected(buy(fresh(), oracleInput(oracleAddress(), twoTokens, fresh())), "more than one token of the policy");
        var wrongShape = new PlutusData.ConstrData(0, java.util.List.of(PlutusData.integer(1)));
        h.assertRejected(buy(fresh(), new TxInInfo(new TxOutRef(new TxId(StateHarness.fill(32, 0x0E)), BigInteger.ZERO),
                new TxOut(oracleAddress(), oracleValue(), new OutputDatum.OutputDatumInline(wrongShape), Optional.empty()))),
                "a datum of another shape");
    }
}
