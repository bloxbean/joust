package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;

/**
 * Builds, checks, submits and confirms one transaction. Its outcomes are deliberately distinct (ADR-0001 D14):
 * <ul>
 *   <li>{@link SubmitException}: nothing was accepted (the builder failed, or the node rejected the transaction);
 *       the caller may rebuild and retry;</li>
 *   <li>{@link PendingTransactionException}: the node may have accepted it (network error, or not seen on chain in
 *       time); the caller must not resubmit blindly;</li>
 *   <li>a tx hash: included on chain.</li>
 * </ul>
 */
final class TxRunner {

    /** The state output the built transaction must contain (null for a destroy). */
    record ExpectedState(String unit, BigInteger lovelace, PlutusData datum, org.julclang.ledger.Value value) {
        ExpectedState(String unit, BigInteger lovelace, PlutusData datum) {
            this(unit, lovelace, datum, null);
        }
    }

    /** A send output (ADR-0007): at output {@code index}, exactly this address and datum, at least {@code value}. */
    record ExpectedSend(int index, String address, String inlineDatumHex, String datumHash, org.julclang.ledger.Value value) {}

    /** A payout output the built transaction must contain: at {@code address}, at least {@code lovelace}, tagged. */
    record ExpectedPayout(String address, BigInteger lovelace, PlutusData tag) {}

    private final StateRuntime rt;
    private final java.util.List<Account> cosigners;

    TxRunner(StateRuntime rt) {
        this(rt, java.util.List.of());
    }

    /** {@code cosigners} also sign every transaction this runner submits ({@code @Quorum}, {@code @AuthorizedBy}). */
    TxRunner(StateRuntime rt, java.util.List<Account> cosigners) {
        this.rt = rt;
        this.cosigners = cosigners;
    }

    String submit(ScriptTx tx, Account caller, Window window, byte[][] requiredSigners, ExpectedState expected,
                  java.util.List<ExpectedPayout> payouts, com.bloxbean.cardano.client.plutus.spec.PlutusScript[] referenceScripts,
                  String what) {
        return submit(tx, caller, window, requiredSigners, expected, payouts, java.util.List.of(), referenceScripts, what);
    }

    String submit(ScriptTx tx, Account caller, Window window, byte[][] requiredSigners, ExpectedState expected,
                  java.util.List<ExpectedPayout> payouts, java.util.List<ExpectedSend> sends,
                  com.bloxbean.cardano.client.plutus.spec.PlutusScript[] referenceScripts, String what) {
        Transaction signed;
        try {
            var ctx = new QuickTxBuilder(rt.backend()).compose(tx)
                    .withSigner(SignerProviders.signerFrom(allSigners(caller)))
                    .feePayer(caller.baseAddress())
                    .collateralPayer(caller.baseAddress())
                    .mergeOutputs(false);
            if (referenceScripts != null && referenceScripts.length > 0) ctx = ctx.withReferenceScripts(referenceScripts);
            if (requiredSigners.length > 0) ctx = ctx.withRequiredSigners(requiredSigners);
            if (window != null) ctx = ctx.validFrom(window.fromSlot()).validTo(window.toSlot());
            if (rt.evaluator() != null) ctx = ctx.withTxEvaluator(rt.evaluator());
            if (Boolean.getBoolean("joust.debug.nodeEvaluates")) {
                // Diagnostic: skip local script-cost evaluation so the node evaluates and, on failure, reports the
                // full script context it saw. Never for normal use.
                ctx = ctx.ignoreScriptCostEvaluationError(true);
            }
            signed = ctx.buildAndSign();
        } catch (Exception e) {
            throw new SubmitException(what + ": could not build the transaction: " + rootMessage(e), e);
        }

        verify(signed, expected, what);
        verifyPayouts(signed, payouts, what);
        verifySends(signed, sends, what);

        String txHash = TransactionUtil.getTxHash(signed);
        if (System.getProperty("joust.dumpTx") != null) {
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("joust.dumpTx"), txHash + ".cbor"),
                        HexUtil.encodeHexString(signed.serialize()));
            } catch (Exception ignored) {
                // diagnostics only
            }
        }
        try {
            var r = rt.backend().getTransactionService().submitTransaction(signed.serialize());
            if (!r.isSuccessful()) {
                // A definite answer from the node: not accepted.
                throw new SubmitException(what + ": the node rejected the transaction: " + r.getResponse());
            }
        } catch (SubmitException e) {
            throw e;
        } catch (Exception e) {
            throw new PendingTransactionException(txHash, what + ": submission outcome unknown (" + rootMessage(e) + ")");
        }
        rt.log(what + ": submitted " + txHash);
        awaitInclusion(txHash, what);
        return txHash;
    }

    private Account[] allSigners(Account caller) {
        var all = new java.util.ArrayList<Account>();
        all.add(caller);
        for (Account a : cosigners) if (!a.baseAddress().equals(caller.baseAddress())) all.add(a);
        return all.toArray(new Account[0]);
    }

    /** Builds, signs, submits and confirms a transaction without scripts (e.g. publishing a reference script). */
    String submitPlain(com.bloxbean.cardano.client.quicktx.Tx tx, Account payer, String what) {
        Transaction signed;
        try {
            signed = new QuickTxBuilder(rt.backend()).compose(tx)
                    .withSigner(SignerProviders.signerFrom(payer))
                    .feePayer(payer.baseAddress())
                    .buildAndSign();
        } catch (Exception e) {
            throw new SubmitException(what + ": could not build the transaction: " + rootMessage(e), e);
        }
        String txHash = TransactionUtil.getTxHash(signed);
        if (System.getProperty("joust.dumpTx") != null) {
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("joust.dumpTx"), txHash + ".cbor"),
                        HexUtil.encodeHexString(signed.serialize()));
            } catch (Exception ignored) {
                // diagnostics only
            }
        }
        try {
            var r = rt.backend().getTransactionService().submitTransaction(signed.serialize());
            if (!r.isSuccessful()) throw new SubmitException(what + ": the node rejected the transaction: " + r.getResponse());
        } catch (SubmitException e) {
            throw e;
        } catch (Exception e) {
            throw new PendingTransactionException(txHash, what + ": submission outcome unknown (" + rootMessage(e) + ")");
        }
        awaitInclusion(txHash, what);
        return txHash;
    }

    /** The built transaction pays the state output exactly as computed on the JVM (I3, I4), or has none (destroy). */
    private static void verify(Transaction t, ExpectedState expected, String what) {
        TransactionOutput found = null;
        int count = 0;
        for (TransactionOutput o : t.getBody().getOutputs()) {
            if (holdsUnit(o, expected == null ? null : expected.unit())) {
                found = o;
                count++;
            }
        }
        if (expected == null) return;
        if (count != 1) throw new SubmitException(what + ": built transaction has " + count + " state outputs");
        if (found.getValue().getCoin().compareTo(expected.lovelace()) != 0) {
            throw new SubmitException(what + ": builder changed the state output's lovelace to "
                    + found.getValue().getCoin() + " (expected " + expected.lovelace()
                    + "); raise @StateContract(minAda) for this state size");
        }
        if (found.getInlineDatum() == null || !Datums.hex(found.getInlineDatum()).equals(Datums.hex(expected.datum()))) {
            throw new SubmitException(what + ": built transaction's state datum differs from the JVM result");
        }
        if (expected.value() != null && !org.julclang.state.api.Assets.isEmpty(
                org.julclang.state.api.Assets.subtract(valueOf(found), expected.value()))) {
            throw new SubmitException(what + ": built transaction's state output value differs from the JVM result");
        }
    }

    /** Each send is at its claimed index after balancing: address, datum, and at least its value (ADR-0007 D6). */
    private static void verifySends(Transaction t, java.util.List<ExpectedSend> sends, String what) {
        var outs = t.getBody().getOutputs();
        for (ExpectedSend s : sends) {
            if (s.index() >= outs.size()) throw new SubmitException(what + ": send output " + s.index() + " is missing");
            TransactionOutput o = outs.get(s.index());
            boolean datumOk = s.inlineDatumHex() != null
                    ? o.getInlineDatum() != null && Datums.hex(o.getInlineDatum()).equals(s.inlineDatumHex())
                    : s.datumHash() != null
                    ? o.getDatumHash() != null && HexUtil.encodeHexString(o.getDatumHash()).equals(s.datumHash())
                    : o.getInlineDatum() == null && o.getDatumHash() == null;
            boolean covers = org.julclang.state.api.Assets.covers(valueOf(o), s.value());
            if (!o.getAddress().equals(s.address()) || !datumOk || !covers) {
                throw new SubmitException(what + ": the balanced transaction's output " + s.index()
                        + " no longer delivers the send to " + s.address() + " (the builder moved or changed it)");
            }
        }
    }

    /** A built output's value as a canonical ledger value. */
    static org.julclang.ledger.Value valueOf(TransactionOutput o) {
        org.julclang.ledger.Value v = org.julclang.state.api.Assets.lovelace(o.getValue().getCoin());
        if (o.getValue().getMultiAssets() != null) {
            for (var ma : o.getValue().getMultiAssets()) {
                for (var a : ma.getAssets()) {
                    String n = a.getNameAsHex().startsWith("0x") ? a.getNameAsHex().substring(2) : a.getNameAsHex();
                    v = org.julclang.state.api.Assets.add(v, org.julclang.state.api.Assets.of(
                            HexUtil.decodeHexString(ma.getPolicyId()), HexUtil.decodeHexString(n), a.getValue()));
                }
            }
        }
        return v;
    }

    /** Each payout is still there after balancing: right address, tag, and at least what is owed (I7). */
    private static void verifyPayouts(Transaction t, java.util.List<ExpectedPayout> payouts, String what) {
        for (ExpectedPayout p : payouts) {
            String tagHex = Datums.hex(p.tag());
            boolean ok = false;
            for (TransactionOutput o : t.getBody().getOutputs()) {
                if (o.getAddress().equals(p.address()) && o.getInlineDatum() != null
                        && Datums.hex(o.getInlineDatum()).equals(tagHex)
                        && o.getValue().getCoin().compareTo(p.lovelace()) >= 0) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw new SubmitException(what + ": the balanced transaction no longer pays " + p.lovelace()
                        + " lovelace to " + p.address() + " (the builder adjusted the payout output)");
            }
        }
    }

    private static boolean holdsUnit(TransactionOutput o, String unit) {
        if (unit == null || o.getValue().getMultiAssets() == null) return false;
        String policy = unit.substring(0, 56);
        String name = unit.substring(56);
        for (var ma : o.getValue().getMultiAssets()) {
            if (!ma.getPolicyId().equals(policy)) continue;
            for (var a : ma.getAssets()) {
                String n = a.getNameAsHex().startsWith("0x") ? a.getNameAsHex().substring(2) : a.getNameAsHex();
                if (n.equalsIgnoreCase(name) && a.getValue().signum() > 0) return true;
            }
        }
        return false;
    }

    private void awaitInclusion(String txHash, String what) {
        long deadline = System.currentTimeMillis() + rt.confirmTimeout().toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                var r = rt.backend().getTransactionService().getTransaction(txHash);
                if (r.isSuccessful() && r.getValue() != null) return;
            } catch (Exception ignored) {
                // keep polling
            }
            sleep(1000);
        }
        throw new PendingTransactionException(txHash, what + ": not seen on chain within " + rt.confirmTimeout());
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JoustException("interrupted");
        }
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    static String hex(byte[] b) {
        return HexUtil.encodeHexString(b);
    }
}
