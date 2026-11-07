package org.julclang.state.runtime;

import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The portable plan of a prepared transaction (ADR-0013 D4): what it spends, reads, creates, mints, runs and requires,
 * in plain values (strings, maps and lists), independent of cardano-client-lib. A JavaScript page derives the same plan
 * from the CBOR ({@code planOf} in {@code @julclang/state-client}) and compares; a builder in another language can
 * build the transaction from it. Values are maps from unit ({@code lovelace}, or policy hex + asset name hex) to a
 * decimal string; datums and redeemer data are hex CBOR; inputs are {@code txhash#index} in the transaction's order.
 */
public final class TxPlan {

    private TxPlan() {
    }

    private static final List<String> TAGS = List.of("spend", "mint", "cert", "reward", "voting", "proposing");

    /** The plan of {@code tx}. */
    public static Map<String, Object> of(Transaction tx) {
        var b = tx.getBody();
        var plan = new LinkedHashMap<String, Object>();
        plan.put("inputs", refs(b.getInputs()));
        plan.put("referenceInputs", refs(b.getReferenceInputs()));
        plan.put("collateral", refs(b.getCollateral()));
        var outs = new ArrayList<Object>();
        if (b.getOutputs() != null) for (TransactionOutput o : b.getOutputs()) outs.add(output(o));
        plan.put("outputs", outs);
        plan.put("collateralReturn", b.getCollateralReturn() == null ? null : output(b.getCollateralReturn()));
        plan.put("totalCollateral", b.getTotalCollateral() == null ? null : b.getTotalCollateral().toString());
        plan.put("mint", assets(b.getMint(), null));
        var rds = new ArrayList<Object>();
        if (tx.getWitnessSet() != null && tx.getWitnessSet().getRedeemers() != null) {
            for (Redeemer r : tx.getWitnessSet().getRedeemers()) {
                var m = new LinkedHashMap<String, Object>();
                m.put("tag", r.getTag().name().toLowerCase(Locale.ROOT));
                m.put("index", r.getIndex().longValue());
                m.put("data", r.getData().serializeToHex());
                m.put("mem", r.getExUnits().getMem().toString());
                m.put("steps", r.getExUnits().getSteps().toString());
                rds.add(m);
            }
        }
        // The ledger's order (tag, then index), as the CBOR is written: the page reads it from the bytes.
        rds.sort(java.util.Comparator.comparingInt((Object r) -> TAGS.indexOf((String) ((Map<?, ?>) r).get("tag")))
                .thenComparingLong(r -> (Long) ((Map<?, ?>) r).get("index")));
        plan.put("redeemers", rds);
        var signers = new ArrayList<String>();
        if (b.getRequiredSigners() != null) for (byte[] s : b.getRequiredSigners()) signers.add(HexUtil.encodeHexString(s));
        plan.put("requiredSigners", signers);
        plan.put("validFrom", b.getValidityStartInterval() == 0 ? null : b.getValidityStartInterval());
        plan.put("validTo", b.getTtl() == 0 ? null : b.getTtl());
        plan.put("fee", b.getFee().toString());
        // Everything else a body or witness set can carry, so that equal plans mean equal transactions (r5).
        plan.put("networkId", b.getNetworkId() == null ? null : b.getNetworkId().ordinal());
        plan.put("scriptDataHash", b.getScriptDataHash() == null ? null : HexUtil.encodeHexString(b.getScriptDataHash()));
        plan.put("auxiliaryDataHash", b.getAuxiliaryDataHash() == null ? null : HexUtil.encodeHexString(b.getAuxiliaryDataHash()));
        plan.put("certificates", b.getCerts() == null ? 0 : b.getCerts().size());
        plan.put("withdrawals", b.getWithdrawals() == null ? 0 : b.getWithdrawals().size());
        plan.put("votes", b.getVotingProcedures() != null && b.getVotingProcedures().getVoting() != null
                && !b.getVotingProcedures().getVoting().isEmpty());
        plan.put("proposals", b.getProposalProcedures() == null ? 0 : b.getProposalProcedures().size());
        plan.put("donation", b.getDonation() == null ? null : b.getDonation().toString());
        plan.put("currentTreasuryValue", b.getCurrentTreasuryValue() == null ? null : b.getCurrentTreasuryValue().toString());
        plan.put("auxiliaryData", tx.getAuxiliaryData() != null);
        plan.put("isValid", tx.isValid());
        return plan;
    }

    private static List<String> refs(List<TransactionInput> ins) {
        var out = new ArrayList<String>();
        if (ins != null) for (TransactionInput i : ins) out.add(i.getTransactionId() + "#" + i.getIndex());
        return out;
    }

    private static Map<String, Object> output(TransactionOutput o) {
        var m = new LinkedHashMap<String, Object>();
        m.put("address", o.getAddress());
        m.put("value", value(o.getValue()));
        m.put("datum", o.getInlineDatum() == null ? null : o.getInlineDatum().serializeToHex());
        m.put("datumHash", o.getDatumHash() == null ? null : HexUtil.encodeHexString(o.getDatumHash()));
        m.put("hasScript", o.getScriptRef() != null && o.getScriptRef().length > 0);
        return m;
    }

    private static Map<String, String> value(Value v) {
        return assets(v.getMultiAssets(), v.getCoin());
    }

    /** Units in sorted order (deterministic), quantities as decimal strings. */
    private static Map<String, String> assets(List<MultiAsset> mas, BigInteger lovelace) {
        var sums = new TreeMap<String, BigInteger>();
        if (lovelace != null) sums.put("lovelace", lovelace);
        if (mas != null) {
            for (MultiAsset ma : mas) {
                for (Asset a : ma.getAssets()) {
                    String unit = ma.getPolicyId() + HexUtil.encodeHexString(a.getNameAsBytes());
                    sums.merge(unit, a.getValue(), BigInteger::add);
                }
            }
        }
        var out = new LinkedHashMap<String, String>();
        sums.forEach((k, q) -> out.put(k, q.toString()));
        return out;
    }
}
