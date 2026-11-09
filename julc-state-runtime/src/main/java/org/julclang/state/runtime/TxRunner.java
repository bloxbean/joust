package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.api.model.Utxo;
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

    // ------------------------------------------------------------------ composition (ADR-0011 D1)

    /** What {@link Compose} collects while it runs several calls into one transaction. */
    static final class Capture {
        final Tx tx = new Tx();
        Window forced;
        final java.util.List<Window> windows = new java.util.ArrayList<>();
        int outputs;
        final java.util.List<org.julclang.state.api.OutputClaim> claims = new java.util.ArrayList<>();
        final java.util.List<ExpectedState> expected = new java.util.ArrayList<>();
        final java.util.List<ExpectedPayout> payouts = new java.util.ArrayList<>();
        final java.util.List<ExpectedSend> sends = new java.util.ArrayList<>();
        final java.util.LinkedHashMap<String, byte[]> requiredSigners = new java.util.LinkedHashMap<>();
        final java.util.Map<String, java.util.List<String>> signerCalls = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashMap<String, Account> accounts = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashMap<String, com.bloxbean.cardano.client.plutus.spec.PlutusScript> scripts = new java.util.LinkedHashMap<>();
        final java.util.Set<String> inputs = new java.util.HashSet<>();
        final java.util.Set<String> mintPolicies = new java.util.HashSet<>();
        /** Map verifier claims of every call (ADR-0002 D9): one withdrawal carries them all. */
        final java.util.List<org.julclang.state.api.mpf.MapClaim> mapClaims = new java.util.ArrayList<>();
        /** A nested composition that allows shared signers: its calls count as one for the signer check. */
        String group;
        /** Building for wallets ({@code Compose.prepare}): no key held here may take part (ADR-0013 D2). */
        boolean walletOnly;
        /** Deposits into other instances made by the calls (ADR-0011 D3), resolved to claims when submitting. */
        final java.util.List<PendingDeposit> deposits = new java.util.ArrayList<>();
        /** The deposits the probe pass saw: a receiver's Funds.deposits in the real pass. */
        java.util.List<PendingDeposit> known = java.util.List.of();
        /** Output index of each call's continuing state output, by unit (policy ++ token name). */
        final java.util.Map<String, Integer> stateOutputs = new java.util.HashMap<>();
        /** A keyed call took part (ADR-0016 D7): the composition designates an input and may rebuild after a rejection. */
        boolean keyed;
        /** Units whose call is {@code @Receives}: the only valid deposit targets (ADR-0011 D3). */
        final java.util.Set<String> receivers = new java.util.HashSet<>();

        void record(Account caller, byte[][] signers, ExpectedState exp, java.util.List<ExpectedPayout> pays,
                    java.util.List<ExpectedSend> snds, com.bloxbean.cardano.client.plutus.spec.PlutusScript[] scs,
                    java.util.List<Account> cos, String what) {
            if (walletOnly) {
                // Refused here, before anything is recorded, and whatever address the account claims (review r3 L1).
                if (!(caller instanceof WalletAccount)) throw keyHeld(caller, what);
                for (Account a : cos) if (!(a instanceof WalletAccount)) throw keyHeld(a, what);
            }
            accounts.putIfAbsent(caller.baseAddress(), caller);
            for (Account a : cos) accounts.putIfAbsent(a.baseAddress(), a);
            for (byte[] sg : signers) {
                String hex = HexUtil.encodeHexString(sg);
                requiredSigners.putIfAbsent(hex, sg);
                var calls = signerCalls.computeIfAbsent(hex, k -> new java.util.ArrayList<>());
                String label = group != null ? group : what;
                if (!calls.contains(label)) calls.add(label);
            }
            if (exp != null) expected.add(exp);
            payouts.addAll(pays);
            sends.addAll(snds);
            if (scs != null) {
                for (var sc : scs) {
                    try {
                        scripts.putIfAbsent(HexUtil.encodeHexString(sc.getScriptHash()), sc);
                    } catch (Exception e) {
                        throw new JoustException("cannot hash a script", e);
                    }
                }
            }
            outputs += (exp != null ? 1 : 0) + pays.size() + snds.size();
        }
    }

    /** A deposit a call makes into instance {@code target} (a unit, policy ++ instance id, hex). */
    record PendingDeposit(String target, org.julclang.ledger.TxOutRef by, org.julclang.ledger.Value value,
                          org.julclang.core.PlutusData memo, String what) {}

    static JoustException keyHeld(Account a, String what) {
        return new JoustException(what + ": needs the key of " + a.baseAddress() + ", held here; a wallet transaction is"
                + " signed by wallets only (ADR-0013 D2)");
    }

    static final ThreadLocal<Capture> CAPTURE = new ThreadLocal<>();

    static Capture capture() {
        return CAPTURE.get();
    }

    /** The transaction a call builds into: a fresh one, or the composition's shared one. */
    static Tx newTx() {
        Capture c = CAPTURE.get();
        return c != null ? c.tx : new Tx();
    }

    /** Index of the next output a call adds (0 outside a composition). */
    static int outputOffset() {
        Capture c = CAPTURE.get();
        return c == null ? 0 : c.outputs;
    }

    /** Spends {@code u} (with {@code redeemer} if a script input); a composition refuses the same UTxO twice. */
    static Tx collect(Tx tx, Utxo u, com.bloxbean.cardano.client.plutus.spec.PlutusData redeemer) {
        Capture c = CAPTURE.get();
        if (c != null && !c.inputs.add(u.getTxHash() + "#" + u.getOutputIndex())) {
            throw new JoustException("composition: " + u.getTxHash() + "#" + u.getOutputIndex() + " is spent by two calls");
        }
        return redeemer == null ? tx.collectFrom(java.util.List.of(u)) : tx.collectFrom(u, redeemer);
    }

    /** Mints under {@code policy}; a composition refuses two calls minting under one policy (one redeemer each). */
    static void mint(Tx tx, String policy, java.util.List<com.bloxbean.cardano.client.transaction.spec.Asset> assets,
                     com.bloxbean.cardano.client.plutus.spec.PlutusData redeemer) {
        Capture c = CAPTURE.get();
        if (c != null && !c.mintPolicies.add(policy)) {
            throw new JoustException("composition: two calls mint under policy " + policy + " (one mint redeemer per policy)");
        }
        tx.mintAsset(policy, assets, redeemer);
    }

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

    /** Further state outputs to verify (a composition's other instances). */
    java.util.List<ExpectedState> extraExpected = java.util.List.of();

    String submit(Tx tx, Account caller, Window window, byte[][] requiredSigners, ExpectedState expected,
                  java.util.List<ExpectedPayout> payouts, com.bloxbean.cardano.client.plutus.spec.PlutusScript[] referenceScripts,
                  String what) {
        return submit(tx, caller, window, requiredSigners, expected, payouts, java.util.List.of(), referenceScripts, what);
    }

    String submit(Tx tx, Account caller, Window window, byte[][] requiredSigners, ExpectedState expected,
                  java.util.List<ExpectedPayout> payouts, java.util.List<ExpectedSend> sends,
                  com.bloxbean.cardano.client.plutus.spec.PlutusScript[] referenceScripts, String what) {
        Capture cap = CAPTURE.get();
        if (cap != null) {
            cap.record(caller, requiredSigners, expected, payouts, sends, referenceScripts, cosigners, what);
            return "composed";
        }
        Transaction signed;
        try {
            var ctx = KeyedObjects.builder(rt.backend()).compose(tx)
                    .withSigner(SignerProviders.signerFrom(allSigners(caller)))
                    .feePayer(caller.baseAddress())
                    .collateralPayer(caller.baseAddress())
                    .mergeOutputs(false);
            if (referenceScripts != null && referenceScripts.length > 0) ctx = ctx.withReferenceScripts(referenceScripts);
            if (requiredSigners.length > 0) ctx = ctx.withRequiredSigners(requiredSigners).preBalanceTx(TxRunner::sortRequiredSigners);
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
        rt.built(what, signed, false);

        verify(signed, expected, what);
        for (ExpectedState e : extraExpected) verify(signed, e, what);
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
        if (awaitStart && window != null) awaitSlot(window.fromSlot(), what);
        deliver(signed, txHash, what);
        rt.log(what + ": submitted " + txHash);
        if (onSubmitted != null) onSubmitted.accept(txHash);
        awaitInclusion(txHash, what);
        return txHash;
    }

    /** Wait until the chain reaches {@code slot} before submitting (a keyed call's window may start later; D7 step 5). */
    boolean awaitStart;
    /** Observes the hash of every transaction this runner delivered (a keyed operation's attempts; D7). */
    java.util.function.Consumer<String> onSubmitted;

    private void awaitSlot(long slot, String what) {
        long tip = rt.chainTime().tipSlot();
        if (tip >= slot) return;
        rt.log(what + ": waiting for slot " + slot + " (tip " + tip + ") to submit");
        while (tip < slot) {
            sleep(Math.max(200, Math.min(2_000, (slot - tip) * rt.chainTime().slotLengthMs() / 2)));
            tip = rt.chainTime().tipSlot();
        }
    }

    /**
     * Sends the same signed bytes to every relay (ADR-0016 D7). The outcome is transaction-wide: any acceptance means
     * submitted; otherwise any unknown delivery means its fate is unknown ({@link PendingTransactionException}: never
     * rebuilt); only rejections by every relay are a rejection ({@link SubmitException}).
     */
    private void deliver(Transaction signed, String txHash, String what) {
        byte[] cbor;
        try {
            cbor = signed.serialize();
        } catch (Exception e) {
            throw new SubmitException(what + ": cannot serialise the transaction: " + rootMessage(e), e);
        }
        boolean accepted = false;
        String unknown = null;
        String rejected = null;
        for (Relay relay : rt.relays()) {
            Relay.Outcome o = relay.submit(cbor);
            switch (o.answer()) {
                case ACCEPTED -> accepted = true;
                case UNKNOWN -> { if (unknown == null) unknown = o.message(); }
                case REJECTED -> { if (rejected == null) rejected = o.message(); }
            }
        }
        if (accepted) return;
        if (unknown != null) throw new PendingTransactionException(txHash, what + ": submission outcome unknown (" + unknown + ")");
        // A definite answer from every relay: not accepted.
        throw new SubmitException(what + ": the node rejected the transaction: " + rejected);
    }

    /**
     * Required signers in byte order. QuickTx keeps them in a {@code HashSet<byte[]>} (identity hashes), so with two or
     * more the order on the wire varies from run to run; it reaches scripts as {@code txInfo.signatories} and breaks
     * byte-identical builds (ADR-0015 I3). Runs before balancing, so fees and script costs see the final order.
     */
    static void sortRequiredSigners(com.bloxbean.cardano.client.function.TxBuilderContext context,
                                    com.bloxbean.cardano.client.transaction.spec.Transaction txn) {
        var rs = txn.getBody().getRequiredSigners();
        if (rs == null || rs.size() < 2) return;
        var sorted = new java.util.ArrayList<>(rs);
        sorted.sort(java.util.Arrays::compareUnsigned);
        txn.getBody().setRequiredSigners(sorted);
    }

    private Account[] allSigners(Account caller) {
        var all = new java.util.ArrayList<Account>();
        all.add(caller);
        for (Account a : cosigners) if (!a.baseAddress().equals(caller.baseAddress())) all.add(a);
        return all.toArray(new Account[0]);
    }

    /**
     * Builds the transaction for a wallet to sign (ADR-0013 D1): balanced from the payer's UTxOs, with collateral and
     * its return to the payer, the fee covering every witness the wallets add; then the post-balancing checks.
     */
    PreparedTx prepare(Tx tx, Account payer, Window window, byte[][] requiredSigners, ExpectedState expected,
                       java.util.List<ExpectedPayout> payouts, java.util.List<ExpectedSend> sends,
                       com.bloxbean.cardano.client.plutus.spec.PlutusScript[] referenceScripts, String what,
                       java.util.Set<String> spent) {
        var needed = new java.util.LinkedHashMap<String, byte[]>();
        needed.put(HexUtil.encodeHexString(Keys.pkh(payer)), Keys.pkh(payer));   // the payer's inputs
        for (byte[] sg : requiredSigners) needed.put(HexUtil.encodeHexString(sg), sg);
        // A payout to the payer's own address can be the only output at it, and the balancer would then take the fee
        // out of it. Spending one of the payer's UTxOs gives it a change output to pay the fee from (restorePayouts).
        if (payouts.stream().anyMatch(p -> p.address().equals(payer.baseAddress()))) {
            Utxo funding = largestAdaOnly(payer.baseAddress(), spent);
            if (funding != null) tx.collectFrom(java.util.List.of(funding));
        }
        Transaction unsigned;
        try {
            var ctx = new QuickTxBuilder(rt.backend()).compose(tx)
                    .feePayer(payer.baseAddress())
                    .collateralPayer(payer.baseAddress())
                    .mergeOutputs(false)
                    .additionalSignersCount(needed.size())
                    // The fee is taken from the largest output at the payer's address, which can be a payout to the
                    // wallet itself (its change output did not exist yet): move it back from the change (ADR-0013 D1).
                    .postBalanceTx((context, txn) -> restorePayouts(txn, payer.baseAddress(), payouts, context.getProtocolParams()));
            if (referenceScripts != null && referenceScripts.length > 0) ctx = ctx.withReferenceScripts(referenceScripts);
            if (requiredSigners.length > 0) ctx = ctx.withRequiredSigners(requiredSigners).preBalanceTx(TxRunner::sortRequiredSigners);
            if (window != null) {
                ctx = ctx.validFrom(window.fromSlot()).validTo(window.toSlot());
            } else {
                // A wallet transaction always expires soon, so a withheld signature cannot be used much later (ADR-0013):
                // the runtime's TTL, as for Ctx-reading calls (DevKit's time horizon is only minutes).
                long tip = rt.backend().getBlockService().getLatestBlock().getValue().getSlot();
                ctx = ctx.validTo(tip + Math.max(1, rt.ttlMs() / rt.chainTime().slotLengthMs()));
            }
            if (rt.evaluator() != null) ctx = ctx.withTxEvaluator(rt.evaluator());
            unsigned = ctx.build();
        } catch (Exception e) {
            throw new SubmitException(what + ": could not build the transaction: " + rootMessage(e), e);
        }
        rt.built(what, unsigned, true);
        verify(unsigned, expected, what);
        for (ExpectedState e : extraExpected) verify(unsigned, e, what);
        verifyPayouts(unsigned, payouts, what);
        verifySends(unsigned, sends, what);
        // Every key whose UTxO the transaction spends (or takes as collateral) must sign too (review r3).
        for (byte[] owner : inputOwners(unsigned)) needed.putIfAbsent(HexUtil.encodeHexString(owner), owner);
        return new PreparedTx(rt, unsigned, new java.util.ArrayList<>(needed.values()), window, what);
    }

    /**
     * Each payout to {@code changeAddress} (tagged) that the balancer reduced gets its deficit back from the change
     * output there (no datum, the largest): the total, and so the balance and the fee, are unchanged.
     */
    private static void restorePayouts(Transaction txn, String changeAddress, java.util.List<ExpectedPayout> payouts,
                                       com.bloxbean.cardano.client.api.model.ProtocolParams pp) {
        for (ExpectedPayout p : payouts) {
            if (!p.address().equals(changeAddress)) continue;
            String tagHex = Datums.hex(p.tag());
            for (TransactionOutput o : txn.getBody().getOutputs()) {
                if (!o.getAddress().equals(changeAddress) || o.getInlineDatum() == null
                        || !Datums.hex(o.getInlineDatum()).equals(tagHex)) continue;
                BigInteger deficit = p.lovelace().subtract(o.getValue().getCoin());
                if (deficit.signum() <= 0) continue;
                TransactionOutput change = null;
                for (TransactionOutput c : txn.getBody().getOutputs()) {
                    if (c.getAddress().equals(changeAddress) && c.getInlineDatum() == null && c.getDatumHash() == null
                            && (change == null || c.getValue().getCoin().compareTo(change.getValue().getCoin()) > 0)) {
                        change = c;
                    }
                }
                if (change == null) return;   // nothing to take it from: verifyPayouts reports it
                // The change keeps at least its ledger minimum (it may hold tokens; review r3).
                BigInteger left = change.getValue().getCoin().subtract(deficit);
                var after = TransactionOutput.builder().address(change.getAddress())
                        .value(com.bloxbean.cardano.client.transaction.spec.Value.builder().coin(left)
                                .multiAssets(change.getValue().getMultiAssets()).build()).build();
                if (left.compareTo(new com.bloxbean.cardano.client.api.MinAdaCalculator(pp).calculateMinAda(after)) < 0) return;
                change.getValue().setCoin(change.getValue().getCoin().subtract(deficit));
                o.getValue().setCoin(p.lovelace());
            }
        }
    }

    /**
     * The UTxO at {@code address} with the most lovelace (at least 3 ADA; tokens allowed, they return as change), over
     * every page, not already spent by the composition and without a reference script; or null.
     */
    private Utxo largestAdaOnly(String address, java.util.Set<String> spent) {
        Utxo best = null;
        try {
            for (int page = 1; page <= 20; page++) {
                var r = rt.backend().getUtxoService().getUtxos(address, 100, page);
                if (!r.isSuccessful() || r.getValue() == null || r.getValue().isEmpty()) break;
                for (Utxo u : r.getValue()) {
                    if (u.getReferenceScriptHash() != null || u.getInlineDatum() != null || u.getDataHash() != null) continue;
                    if (spent.contains(u.getTxHash() + "#" + u.getOutputIndex())) continue;
                    BigInteger q = lovelace(u);
                    if (q.compareTo(BigInteger.valueOf(3_000_000)) < 0) continue;
                    if (best == null || q.compareTo(lovelace(best)) > 0) best = u;
                }
                if (r.getValue().size() < 100) break;
            }
        } catch (Exception e) {
            return best;
        }
        return best;
    }

    private static BigInteger lovelace(Utxo u) {
        return u.getAmount().stream().filter(a -> a.getUnit().equals("lovelace")).map(a -> a.getQuantity())
                .findFirst().orElse(BigInteger.ZERO);
    }

    /** The payment key hashes owning the transaction's spent and collateral inputs (those at key addresses). */
    private java.util.List<byte[]> inputOwners(Transaction t) {
        var out = new java.util.ArrayList<byte[]>();
        var refs = new java.util.ArrayList<com.bloxbean.cardano.client.transaction.spec.TransactionInput>(t.getBody().getInputs());
        if (t.getBody().getCollateral() != null) refs.addAll(t.getBody().getCollateral());
        for (var in : refs) {
            try {
                var r = rt.backend().getUtxoService().getTxOutput(in.getTransactionId(), in.getIndex());
                if (!r.isSuccessful() || r.getValue() == null) continue;
                var cred = com.bloxbean.cardano.client.address.AddressProvider.getPaymentCredential(
                        new com.bloxbean.cardano.client.address.Address(r.getValue().getAddress()));
                if (cred.isPresent() && cred.get().getType() == com.bloxbean.cardano.client.address.CredentialType.Key) {
                    out.add(cred.get().getBytes());
                }
            } catch (Exception e) {
                throw new JoustException("cannot resolve input " + in.getTransactionId() + "#" + in.getIndex(), e);
            }
        }
        return out;
    }

    /** Submits a signed transaction and waits for it (a {@link PreparedTx} after the wallets signed). */
    String submitSigned(Transaction signed, String what) {
        String txHash = TransactionUtil.getTxHash(signed);
        try {
            var r = rt.backend().getTransactionService().submitTransaction(signed.serialize());
            if (!r.isSuccessful()) throw new SubmitException(what + ": the node rejected the transaction: " + r.getResponse());
        } catch (SubmitException e) {
            throw e;
        } catch (Exception e) {
            throw new PendingTransactionException(txHash, what + ": submission outcome unknown (" + rootMessage(e) + ")");
        }
        rt.log(what + ": submitted " + txHash);
        awaitInclusion(txHash, what);
        return txHash;
    }

    /** Builds, signs, submits and confirms a transaction without scripts (e.g. publishing a reference script). */
    String submitPlain(com.bloxbean.cardano.client.quicktx.Tx tx, Account payer, String what) {
        Capture cap = CAPTURE.get();
        if (payer instanceof WalletAccount || (cap != null && cap.walletOnly)) {
            throw new JoustException(what + ": needs a key-holding account to pay and sign; publish reference scripts and"
                    + " the verifiers before wallet users call the contract (ADR-0013)");
        }
        Transaction signed;
        try {
            signed = new QuickTxBuilder(rt.backend()).compose(tx)
                    .withSigner(SignerProviders.signerFrom(payer))
                    .feePayer(payer.baseAddress())
                    .buildAndSign();
        } catch (Exception e) {
            throw new SubmitException(what + ": could not build the transaction: " + rootMessage(e), e);
        }
        rt.built(what, signed, false);
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
