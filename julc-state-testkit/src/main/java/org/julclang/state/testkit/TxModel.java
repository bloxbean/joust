package org.julclang.state.testkit;

import org.julclang.core.PlutusData;
import org.julclang.ledger.Interval;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A transaction as the scripts see it: inputs, outputs, mint, signers, validity range and redeemers. Mutable so the
 * attack suite can tamper with copies ({@link #copy()}).
 */
public final class TxModel {

    /** What a transaction does to an instance; decides which mutations apply. */
    public enum Kind { CREATE, ACTION, DESTROY }

    public final Kind kind;
    public final List<TxInInfo> inputs = new ArrayList<>();
    public final List<TxInInfo> referenceInputs = new ArrayList<>();
    public final List<TxOut> outputs = new ArrayList<>();
    public Value mint = Value.zero();
    public final List<byte[]> signers = new ArrayList<>();
    public Interval range = Interval.always();
    /** Spend redeemers by state input. */
    public final Map<TxOutRef, PlutusData> spendRedeemers = new LinkedHashMap<>();
    /** The MINT redeemer, if the transaction mints or burns under the contract's policy. */
    public PlutusData mintRedeemer;
    /** Index in {@link #outputs} of the state output (create, action), or -1. */
    public int stateOutput = -1;
    /** The map verifier's claims (ADR-0002 D9), if the transaction touches MerkleMaps; else null. */
    public java.util.List<org.julclang.state.api.mpf.MapClaim> mapClaims;

    /** The name of the token this transaction creates or spends: an instance id, or an object id (ADR-0003). */
    public byte[] token;

    /** Indexes in {@link #outputs} of payout outputs. */
    public final List<Integer> payoutOutputs = new ArrayList<>();

    public TxModel(Kind kind) {
        this.kind = kind;
    }

    public TxModel copy() {
        var c = new TxModel(kind);
        c.inputs.addAll(inputs);
        c.referenceInputs.addAll(referenceInputs);
        c.outputs.addAll(outputs);
        c.mint = mint;
        c.signers.addAll(signers);
        c.range = range;
        c.spendRedeemers.putAll(spendRedeemers);
        c.mintRedeemer = mintRedeemer;
        c.stateOutput = stateOutput;
        c.payoutOutputs.addAll(payoutOutputs);
        c.mapClaims = mapClaims == null ? null : new java.util.ArrayList<>(mapClaims);
        c.token = token;
        return c;
    }

    /** True if the transaction's validity range is bounded (the call read Ctx or a time annotation). */
    public boolean hasRange() {
        return !range.equals(Interval.always());
    }
}
