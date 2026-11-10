package org.julclang.state.examples.registry;

import org.julclang.core.PlutusData;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.DatumHash;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.ScriptPurpose;
import org.julclang.ledger.StakingCredential;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.Value;
import org.julclang.state.testkit.Ledger;
import org.julclang.state.testkit.StateHarness;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/** Small, explicit changes to outputs, datums and redeemers, for the attack tests. */
final class Tamper {

    private Tamper() {}

    static PlutusData.ConstrData datum(TxOut o) {
        return (PlutusData.ConstrData) ((OutputDatum.OutputDatumInline) o.datum()).datum();
    }

    static TxOut withDatum(TxOut o, PlutusData d) {
        return new TxOut(o.address(), o.value(), new OutputDatum.OutputDatumInline(d), o.referenceScript());
    }

    static TxOut withValue(TxOut o, Value v) {
        return new TxOut(o.address(), v, o.datum(), o.referenceScript());
    }

    static TxOut withAddress(TxOut o, Address a) {
        return new TxOut(a, o.value(), o.datum(), o.referenceScript());
    }

    static TxOut withStake(TxOut o) {
        return withAddress(o, new Address(o.address().credential(), Optional.of(new StakingCredential.StakingHash(
                new Credential.PubKeyCredential(new PubKeyHash(StateHarness.fill(28, 0x5A)))))));
    }

    static TxOut withRefScript(TxOut o) {
        return new TxOut(o.address(), o.value(), o.datum(), Optional.of(new ScriptHash(StateHarness.fill(28, 0x5C))));
    }

    static TxOut withDatumHash(TxOut o) {
        return new TxOut(o.address(), o.value(), new OutputDatum.OutputDatumHash(new DatumHash(StateHarness.fill(32, 0xDA))),
                o.referenceScript());
    }

    static TxOut plusLovelace(TxOut o, BigInteger n) {
        var e = new TreeMap<>(Ledger.entries(o.value()));
        var ada = new TreeMap<>(e.getOrDefault("", Map.of()));
        ada.merge("", n, BigInteger::add);
        e.put("", ada);
        return withValue(o, Ledger.valueOf(e));
    }

    static TxOut plusToken(TxOut o, byte[] policy, byte[] name, BigInteger n) {
        var e = new TreeMap<String, Map<String, BigInteger>>();
        Ledger.entries(o.value()).forEach((p, m) -> e.put(p, new TreeMap<>(m)));
        e.computeIfAbsent(RegistryWorld.hex(policy), k -> new TreeMap<>()).merge(RegistryWorld.hex(name), n, BigInteger::add);
        return withValue(o, Ledger.valueOf(e));
    }

    /** {@code d} with field {@code i} replaced. */
    static PlutusData.ConstrData field(PlutusData d, int i, PlutusData v) {
        var c = (PlutusData.ConstrData) d;
        var fs = new ArrayList<>(c.fields());
        fs.set(i, v);
        return new PlutusData.ConstrData(c.constructorTag(), fs);
    }

    /** {@code d} with field {@code j} of its field {@code i} (a nested record) replaced. */
    static PlutusData.ConstrData nested(PlutusData d, int i, int j, PlutusData v) {
        return field(d, i, field(((PlutusData.ConstrData) d).fields().get(i), j, v));
    }

    static PlutusData.ConstrData tag(PlutusData d, int tag) {
        return new PlutusData.ConstrData(tag, ((PlutusData.ConstrData) d).fields());
    }

    static PlutusData bytes(byte[] b) {
        return new PlutusData.BytesData(b);
    }

    static PlutusData integer(BigInteger n) {
        return new PlutusData.IntData(n);
    }

    static void mapOutput(Ledger.Tx t, int i, UnaryOperator<TxOut> f) {
        t.outputs.set(i, f.apply(t.outputs.get(i)));
    }

    static void mapDatum(Ledger.Tx t, int i, UnaryOperator<PlutusData.ConstrData> f) {
        mapOutput(t, i, o -> withDatum(o, f.apply(datum(o))));
    }

    static void mapRedeemer(Ledger.Tx t, ScriptPurpose p, UnaryOperator<PlutusData> f) {
        t.redeemers.put(p, f.apply(t.redeemers.get(p)));
    }

    static List<Integer> indexes(int n) {
        var out = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) out.add(i);
        return out;
    }
}
