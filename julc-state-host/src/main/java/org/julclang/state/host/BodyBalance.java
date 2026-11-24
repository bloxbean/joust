package org.julclang.state.host;

import com.bloxbean.cardano.client.api.util.AssetUtil;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import org.julclang.state.runtime.snapshot.Snapshot;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.bloxbean.cardano.client.common.CardanoConstants.LOVELACE;

/** D13 value conservation for the host's prepare and publication bodies, using this call's D6 evidence. */
final class BodyBalance {
    private BodyBalance() {}

    static void check(TransactionBody body, Snapshot snapshot) {
        var balance = inputs(body.getInputs(), snapshot);
        if (body.getMint() != null) {
            for (var amount : ValueUtil.toAmountList(new Value(BigInteger.ZERO, body.getMint())))
                balance.merge(amount.getUnit(), amount.getQuantity(), BigInteger::add);
        }
        for (var withdrawal : body.getWithdrawals())
            balance.merge(LOVELACE, withdrawal.getCoin(), BigInteger::add);
        for (var output : body.getOutputs()) subtractOutput(balance, output);
        balance.merge(LOVELACE, body.getFee().negate(), BigInteger::add);
        for (var cert : body.getCerts()) {
            // D14 allows only pure registrations. The legacy form omits the protocol deposit on the wire.
            BigInteger deposit = new BigInteger(snapshot.protocolParams.getKeyDeposit());
            if (!(cert instanceof StakeRegistration || cert instanceof RegCert reg && deposit.equals(reg.getCoin())))
                throw unbalanced("unknown certificate or incorrect registration deposit");
            balance.merge(LOVELACE, deposit.negate(), BigInteger::add);
        }
        if (body.getDonation() != null || body.getProposalProcedures() != null && !body.getProposalProcedures().isEmpty())
            throw unbalanced("unexpected governance payment");
        requireZero(balance);

        // The phase-2 failure path accounts for collateral separately from ordinary inputs and outputs.
        var collateral = inputs(body.getCollateral(), snapshot);
        if (body.getCollateralReturn() != null) subtractOutput(collateral, body.getCollateralReturn());
        if (body.getTotalCollateral() != null) {
            collateral.merge(LOVELACE, body.getTotalCollateral().negate(), BigInteger::add);
        } else if (collateral.getOrDefault(LOVELACE, BigInteger.ZERO).signum() >= 0) {
            collateral.remove(LOVELACE); // The ledger collects all remaining collateral lovelace.
        }
        requireZero(collateral);
    }

    /**
     * The shape of a call's body (#151 review SB3, defence in depth): no certificate, every withdrawal a zero one from
     * a reward account the call may use (the contract's own, or a shared verifier's), and every mint under the
     * contract's policy. No served call builds anything else, so anything else is a host fault, refused before it is
     * reserved; the client checks the body independently (D13).
     */
    static void checkCall(TransactionBody body, java.util.Set<String> rewardAccounts, String policy) {
        if (body.getCerts() != null && !body.getCerts().isEmpty()) throw unbalanced("unexpected certificate in a call");
        if (body.getWithdrawals() != null) {
            for (var withdrawal : body.getWithdrawals()) {
                if (withdrawal.getCoin().signum() != 0 || !rewardAccounts.contains(withdrawal.getRewardAddress()))
                    throw unbalanced("unexpected withdrawal from " + withdrawal.getRewardAddress());
            }
        }
        if (body.getMint() != null) {
            for (var minted : body.getMint()) {
                if (!policy.equals(minted.getPolicyId())) throw unbalanced("unexpected mint under " + minted.getPolicyId());
            }
        }
    }

    private static Map<String, BigInteger> inputs(List<TransactionInput> inputs, Snapshot snapshot) {
        var balance = new HashMap<String, BigInteger>();
        var seen = new HashSet<TransactionInput>();
        for (var in : inputs) {
            String ref = Snapshot.ref(in.getTransactionId(), in.getIndex());
            if (!seen.add(in)) throw unbalanced("duplicate input " + ref);
            var u = snapshot.outputs.get(ref);
            if (u == null) throw unbalanced("missing input value for " + ref);
            for (var amount : u.getAmount())
                balance.merge(AssetUtil.normalizeUnit(amount.getUnit()), amount.getQuantity(), BigInteger::add);
        }
        return balance;
    }

    private static void subtractOutput(Map<String, BigInteger> balance, TransactionOutput output) {
        for (var amount : ValueUtil.toAmountList(output.getValue())) {
            if (amount.getQuantity().signum() < 0) throw unbalanced("negative output quantity for " + amount.getUnit());
            balance.merge(amount.getUnit(), amount.getQuantity().negate(), BigInteger::add);
        }
    }

    private static void requireZero(Map<String, BigInteger> balance) {
        for (var entry : balance.entrySet())
            if (entry.getValue().signum() != 0) throw unbalanced("value is not conserved for " + entry.getKey());
    }

    private static HostError unbalanced(String message) {
        return HostError.internal("unbalanced", message);
    }
}
