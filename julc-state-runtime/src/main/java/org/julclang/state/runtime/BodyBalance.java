package org.julclang.state.runtime;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.AssetUtil;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.cert.AuthCommitteeHotCert;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRetirement;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.RegDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.ResignCommitteeColdCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDelegation;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDeregistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeVoteDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeVoteRegDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UpdateDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.VoteDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.VoteRegDelegCert;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Value conservation of a built transaction body, per unit (#152): inputs + mint + withdrawals + refunds = outputs + fee
 * + deposits + donation, every output quantity non-negative, no input twice; and on the collateral path, collateral
 * inputs = collateral return + total collateral. The runtime checks every body it hands to a wallet (a
 * {@link PreparedTx}, a prepared publication) with it, so it never hands out one the ledger would reject for its value
 * (a negative token quantity in the change, or a token payment no input funds: such a body passes a plain
 * conservation check of the outputs alone, the negative change cancelling the payment).
 * <p>
 * Inputs are resolved through {@code resolve} (the chain reads the build used). A certificate or body field whose value
 * effect this class does not know is refused rather than guessed.
 */
public final class BodyBalance {

    private static final String LOVELACE = "lovelace";

    private BodyBalance() {}

    /** Thrown when a body does not balance: a refusal, never a transaction handed out. */
    public static final class UnbalancedException extends JoustException {
        UnbalancedException(String message) {
            super(message);
        }
    }

    /**
     * Checks {@code body}; {@code resolve} returns the output an input spends (null: unknown, refused), {@code pp} the
     * deposits of the protocol parameters the body was built with.
     */
    public static void check(TransactionBody body, Function<TransactionInput, Utxo> resolve, ProtocolParams pp,
                             String what) {
        Map<String, BigInteger> balance = inputs(body.getInputs(), resolve, what);
        if (body.getMint() != null && !body.getMint().isEmpty()) {
            for (var a : ValueUtil.toAmountList(new Value(BigInteger.ZERO, body.getMint()))) {
                balance.merge(a.getUnit(), a.getQuantity(), BigInteger::add);
            }
        }
        if (body.getWithdrawals() != null) {
            for (var w : body.getWithdrawals()) balance.merge(LOVELACE, w.getCoin(), BigInteger::add);
        }
        for (TransactionOutput o : body.getOutputs()) subtractOutput(balance, o, what);
        balance.merge(LOVELACE, body.getFee().negate(), BigInteger::add);
        if (body.getCerts() != null) {
            for (Certificate c : body.getCerts()) balance.merge(LOVELACE, deposit(c, pp, what).negate(), BigInteger::add);
        }
        if (body.getProposalProcedures() != null) {
            for (var p : body.getProposalProcedures()) balance.merge(LOVELACE, p.getDeposit().negate(), BigInteger::add);
        }
        if (body.getDonation() != null) balance.merge(LOVELACE, body.getDonation().negate(), BigInteger::add);
        if (body.getSubTransactions() != null && !body.getSubTransactions().isEmpty()
                || body.getDirectDeposits() != null && !body.getDirectDeposits().isEmpty()) {
            throw unbalanced(what, "nested transactions or direct deposits are not checked here");
        }
        requireZero(balance, what, "");

        // The phase-2 failure path: the collateral inputs pay the collateral return and the total collateral.
        if (body.getCollateral() == null || body.getCollateral().isEmpty()) return;
        Map<String, BigInteger> collateral = inputs(body.getCollateral(), resolve, what);
        if (body.getCollateralReturn() != null) subtractOutput(collateral, body.getCollateralReturn(), what);
        if (body.getTotalCollateral() != null) {
            collateral.merge(LOVELACE, body.getTotalCollateral().negate(), BigInteger::add);
        } else if (collateral.getOrDefault(LOVELACE, BigInteger.ZERO).signum() >= 0) {
            collateral.remove(LOVELACE);   // without a total, the ledger collects all the remaining collateral lovelace
        }
        requireZero(collateral, what, " on the collateral path");
    }

    /** The lovelace a certificate locks (positive) or refunds (negative). */
    static BigInteger deposit(Certificate c, ProtocolParams pp, String what) {
        return switch (c) {
            // The legacy forms carry no amount: the protocol's key deposit.
            case StakeRegistration r -> new BigInteger(pp.getKeyDeposit());
            case StakeDeregistration r -> new BigInteger(pp.getKeyDeposit()).negate();
            case RegCert r -> r.getCoin();
            case UnregCert r -> r.getCoin().negate();
            case StakeRegDelegCert r -> r.getCoin();
            case VoteRegDelegCert r -> r.getCoin();
            case StakeVoteRegDelegCert r -> r.getCoin();
            case RegDRepCert r -> r.getCoin();
            case UnregDRepCert r -> r.getCoin().negate();
            case StakeDelegation d -> BigInteger.ZERO;
            case VoteDelegCert d -> BigInteger.ZERO;
            case StakeVoteDelegCert d -> BigInteger.ZERO;
            case UpdateDRepCert d -> BigInteger.ZERO;
            case AuthCommitteeHotCert d -> BigInteger.ZERO;
            case ResignCommitteeColdCert d -> BigInteger.ZERO;
            case PoolRetirement d -> BigInteger.ZERO;   // refunded at the epoch boundary, not by this transaction
            // A pool registration locks the pool deposit only when the pool is new: not decidable from the body.
            default -> throw unbalanced(what, "a " + c.getClass().getSimpleName() + " certificate is not checked here");
        };
    }

    private static Map<String, BigInteger> inputs(List<TransactionInput> inputs, Function<TransactionInput, Utxo> resolve,
                                                  String what) {
        var balance = new HashMap<String, BigInteger>();
        var seen = new HashSet<TransactionInput>();
        for (TransactionInput in : inputs) {
            String ref = in.getTransactionId() + "#" + in.getIndex();
            if (!seen.add(in)) throw unbalanced(what, "input " + ref + " is spent twice");
            Utxo u = resolve.apply(in);
            if (u == null || u.getAmount() == null) throw unbalanced(what, "cannot resolve input " + ref);
            for (var a : u.getAmount()) balance.merge(AssetUtil.normalizeUnit(a.getUnit()), a.getQuantity(), BigInteger::add);
        }
        return balance;
    }

    private static void subtractOutput(Map<String, BigInteger> balance, TransactionOutput o, String what) {
        for (var a : ValueUtil.toAmountList(o.getValue())) {
            if (a.getQuantity().signum() < 0) {
                throw unbalanced(what, "an output to " + o.getAddress() + " holds a negative quantity of " + a.getUnit());
            }
            balance.merge(a.getUnit(), a.getQuantity().negate(), BigInteger::add);
        }
    }

    private static void requireZero(Map<String, BigInteger> balance, String what, String where) {
        var off = new java.util.TreeMap<String, BigInteger>();
        balance.forEach((u, q) -> { if (q.signum() != 0) off.put(u, q); });
        if (!off.isEmpty()) {
            throw unbalanced(what, "value is not conserved" + where + " (inputs less outputs, by unit: " + off + ")");
        }
    }

    private static UnbalancedException unbalanced(String what, String message) {
        return new UnbalancedException(what + ": the built transaction does not balance: " + message
                + "; refused, nothing handed out (#152)");
    }
}
