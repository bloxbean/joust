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

    /** A co-caller's return: the output at {@code index}, no datum, exactly {@code value}. */
    record Return(int index, org.julclang.ledger.Value value) {}

    /**
     * What a body may take from each caller other than the payer (T1): the native assets it pays in, the return made to
     * it, and the address each of its collected inputs was listed at (by reference), checked against the chain.
     */
    record CoCallers(java.util.Map<String, org.julclang.ledger.Value> paid, java.util.Map<String, Return> returns,
                     java.util.Map<String, String> owners) {
        static final CoCallers NONE = new CoCallers(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
    }

    /** A payout output the built transaction must contain: at {@code address}, at least {@code lovelace}, tagged. */
    record ExpectedPayout(String address, BigInteger lovelace, PlutusData tag) {}

    // ------------------------------------------------------------------ composition (ADR-0011 D1)

    /** What {@link Compose} collects while it runs several calls into one transaction. */
    static final class Capture {
        final Tx tx = new RecordingTx();
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
        /** The inputs {@code AssetTx.collectAssets} spent for the native assets a caller pays in (#152). */
        final java.util.Set<String> assetInputs = new java.util.HashSet<>();
        /** Inputs whose native assets {@code collectAssets} counted, and what of them is left, by address and unit. */
        final java.util.Set<String> countedInputs = new java.util.HashSet<>();
        final java.util.Map<String, java.util.Map<String, BigInteger>> spareAssets = new java.util.HashMap<>();
        /**
         * What the first pass's calls read (reference inputs, by lower-case reference): a later call's read is never
         * collected by an earlier call for its assets (#165 Codex round 8).
         */
        final java.util.Set<String> knownReads = new java.util.HashSet<>();
        /** Inputs spent without a redeemer (seeds, designated inputs, asset inputs), by reference, as collected (T1). */
        final java.util.Map<String, Utxo> regularInputs = new java.util.LinkedHashMap<>();
        /**
         * The address each regular input was collected from (R7): the caller's base address for an asset input (it was
         * listed there), else the provider's {@code Utxo.address}, possibly null (then {@code returnSurplus} refuses).
         */
        final java.util.Map<String, String> regularOwners = new java.util.HashMap<>();
        /** The native assets each caller's calls pay from its own inputs, by base address and unit (T1). */
        final java.util.Map<String, java.util.Map<String, BigInteger>> paidAssets = new java.util.HashMap<>();
        /**
         * The output {@code AssetTx.returnSurplus} appends for each caller other than the payer, by address: its index and
         * exact value (#165 Fable, Codex round 3).
         */
        final java.util.Map<String, Return> returns = new java.util.LinkedHashMap<>();
        /**
         * The address whose listing supplied a seed (by reference; #165 Codex round 4): the seed is that caller's,
         * whatever address the provider writes in it, and the chain must agree.
         */
        final java.util.Map<String, String> listedAt = new java.util.HashMap<>();
        final java.util.Set<String> mintPolicies = new java.util.HashSet<>();
        /** Map verifier claims of every call (ADR-0002 D9): one withdrawal carries them all. */
        final java.util.List<org.julclang.state.api.mpf.MapClaim> mapClaims = new java.util.ArrayList<>();
        /** A nested composition that allows shared signers: its calls count as one for the signer check. */
        String group;
        /** The keyed operation this composition's attempt belongs to, once designated (its picks may reuse its inputs). */
        Designation.Designated designated;
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
        /**
         * A keyed contract's creation took part (its heads, ADR-0016 D3): for a wallet's prepare it is a keyed call too
         * (D11, R-G1); the JVM's own compositions keep M2's rules.
         */
        boolean keyedCreation;
        /** Units whose call is {@code @Receives}: the only valid deposit targets (ADR-0011 D3). */
        final java.util.Set<String> receivers = new java.util.HashSet<>();

        void record(Account caller, byte[][] signers, ExpectedState exp, java.util.List<ExpectedState> extra,
                    java.util.List<ExpectedPayout> pays, java.util.List<ExpectedSend> snds,
                    com.bloxbean.cardano.client.plutus.spec.PlutusScript[] scs, java.util.List<Account> cos, String what) {
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
            // A keyed insert also continues its anchor, a keyed creation its heads (ADR-0016 D7): verified like the
            // call's own state output, and counted, so later calls' output indices stay right (F54, R-C1).
            expected.addAll(extra);
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
            outputs += (exp != null ? 1 : 0) + extra.size() + pays.size() + snds.size();
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

    /** The keyed operation the calls on this thread are building for (a keyed composition's), or null (F76). */
    static Designation.Designated currentOperation() {
        Capture c = CAPTURE.get();
        return c == null ? null : c.designated;
    }

    /** The transaction a call builds into: a fresh one, or the composition's shared one. */
    static Tx newTx() {
        Capture c = CAPTURE.get();
        return c != null ? c.tx : new RecordingTx();
    }

    /**
     * The transaction every call builds into ({@link #newTx()}): a {@code Tx} that also records the script inputs it
     * spends and the reference inputs it reads, whichever call added them, so a keyed operation's attempt knows what it
     * depends on without each call reporting it (ADR-0016 D7: a failure is contention only if one of them moved; F68).
     * It only records: the transaction built is the same.
     */
    static class RecordingTx extends Tx {
        final java.util.List<Utxo> spent = new java.util.ArrayList<>();
        final java.util.List<Utxo> read = new java.util.ArrayList<>();
        /** Every reference input read, reference scripts included, by lower-case reference: never spent too (#165 Codex r8). */
        final java.util.Set<String> readRefs = new java.util.HashSet<>();
        /** The outputs added so far, in order: every payToAddress/payToContract overload comes through here. */
        int outputs;

        @Override
        protected Tx payToAddress(String address, java.util.List<com.bloxbean.cardano.client.api.model.Amount> amounts,
                                  byte[] datumHash, com.bloxbean.cardano.client.plutus.spec.PlutusData datum,
                                  com.bloxbean.cardano.client.spec.Script scriptRef, byte[] scriptRefBytes) {
            Tx t = super.payToAddress(address, amounts, datumHash, datum, scriptRef, scriptRefBytes);
            outputs++;
            return t;
        }

        @Override
        public Tx collectFrom(Utxo utxo, com.bloxbean.cardano.client.plutus.spec.PlutusData redeemer) {
            spent.add(utxo);
            return super.collectFrom(utxo, redeemer);
        }

        @Override
        public Tx collectFrom(Utxo utxo, com.bloxbean.cardano.client.plutus.spec.PlutusData redeemer,
                              com.bloxbean.cardano.client.plutus.spec.PlutusData datum) {
            spent.add(utxo);
            return super.collectFrom(utxo, redeemer, datum);
        }

        @Override
        public Tx collectFrom(java.util.List<Utxo> utxos, com.bloxbean.cardano.client.plutus.spec.PlutusData redeemer) {
            spent.addAll(utxos);
            return super.collectFrom(utxos, redeemer);
        }

        @Override
        public Tx readFrom(Utxo... utxos) {
            // A reference script never moves; everything else read may (an instance, a foreign read).
            for (Utxo u : utxos) if (u != null && u.getReferenceScriptHash() == null) read.add(u);
            for (Utxo u : utxos) if (u != null) readRefs.add(Designation.ref(u));
            return super.readFrom(utxos);
        }
    }

    /** Index of the next output a call adds (0 outside a composition). */
    static int outputOffset() {
        Capture c = CAPTURE.get();
        return c == null ? 0 : c.outputs;
    }

    /** Spends {@code u} (with {@code redeemer} if a script input); a composition refuses the same UTxO twice. */
    static Tx collect(Tx tx, Utxo u, com.bloxbean.cardano.client.plutus.spec.PlutusData redeemer) {
        Capture c = CAPTURE.get();
        // One spelling of each reference (lower case, Designation.ref; #165 reviewer3 R3-2): readers compare it so.
        if (c != null && !c.inputs.add(Designation.ref(u))) {
            throw new JoustException("composition: " + u.getTxHash() + "#" + u.getOutputIndex() + " is spent by two calls");
        }
        if (c != null && redeemer == null && c.regularInputs.putIfAbsent(Designation.ref(u), u) == null) {
            String owner = c.listedAt.getOrDefault(Designation.ref(u), u.getAddress());
            if (owner != null) c.regularOwners.put(Designation.ref(u), owner);
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

    /** The payer UTxOs a composition spent for the assets its calls pay in ({@code Capture.assetInputs}; #152). */
    java.util.Set<String> assetInputs = java.util.Set.of();

    /**
     * The native assets each caller other than the payer pays from its own inputs, by its address ({@code
     * AssetTx.returnSurplus}; T1): a prepared transaction may take nothing else from that address.
     */
    CoCallers coCallers = CoCallers.NONE;

    /** The composition has callers or co-signers other than the payer: its body is checked by {@link #checkCoCallers} (R8). */
    boolean otherCallers;

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
            cap.record(caller, requiredSigners, expected, extraExpected, payouts, sends, referenceScripts, cosigners, what);
            return "composed";
        }
        if (designated != null && window == null) window = untimedWindow(rt.chainTime(), rt.ttlMs());
        // What the attempt depends on, as the calls built it: the script inputs it spends and the references it reads
        // (ADR-0016 D7: a build or script failure is contention only if one of them moved; F68).
        if (designated != null && tx instanceof RecordingTx r) designated.noteInputs(r.spent, r.read);
        Designation.Claimed built;
        try {
            var ctx = Designation.builder(rt.backend(), designated).compose(tx)
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
            var finalCtx = ctx;
            // Selected and claimed under the payer's lock: no keyed operation designates these inputs meanwhile (D7).
            var scriptInputs = new java.util.HashSet<String>();
            if (tx instanceof RecordingTx r) for (Utxo u : r.spent) scriptInputs.add(Designation.ref(u));
            built = Designation.build(caller.baseAddress(), designated, scriptInputs, finalCtx::buildAndSign);
        } catch (SubmitException e) {
            throw e;
        } catch (Exception e) {
            throw new SubmitException(SubmitException.Kind.BUILD, what + ": could not build the transaction: " + rootMessage(e), e);
        }
        boolean pending = false;
        boolean handedOut = false;
        try {
            // The bytes the runtime signs and submits, decoded: every check below reads them (#165 Opus P3). Observers see
            // the signed transaction only once every check passed (#165 Codex round 8); a refused one without its
            // signatures, so nothing an observer keeps can be submitted.
            Transaction signed;
            try {
                signed = handedOut(built.tx(), what);
                // A composition with other callers, or a call with co-signers (key accounts the runtime signs for): it
                // takes from each only what its calls pay in (R8; #165 Opus P3: a co-signer too), and the collateral is
                // the caller's. Read only then, so other submissions read nothing more.
                if (otherCallers || !cosigners.isEmpty()) checkCoCallers(signed, resolveInputs(rt, signed), caller.baseAddress(), coCallers, what);
                // The value sizes, as a prepared body's (#165 Codex round 3): nothing the node would refuse is submitted.
                BodyBalance.checkValueSizes(signed.getBody(), () -> protocolParams(rt, what), what);
                requireDisjointReads(signed, what);
                verify(signed, expected, what);
                for (ExpectedState e : extraExpected) verify(signed, e, what);
                verifyPayouts(signed, payouts, what);
                verifySends(signed, sends, what);
                if (designated != null) requireSpends(signed, designated.ref(), what);
            } catch (RuntimeException refused) {
                observeRefused(rt, what, built.tx(), refused);
                throw refused;
            }
            rt.built(what, built.tx(), false);

            String txHash = TransactionUtil.getTxHash(signed);
            if (System.getProperty("joust.dumpTx") != null) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("joust.dumpTx"), txHash + ".cbor"),
                            HexUtil.encodeHexString(signed.serialize()));
                } catch (Exception ignored) {
                    // diagnostics only
                }
            }
            if (designated == null) {
                submitToBackend(signed, txHash, what);
            } else {
                // A keyed operation's attempt (ADR-0016 D7): sent at its window's start, to every relay.
                if (window != null) awaitWindow(window, what);
                byte[] cbor;
                try {
                    cbor = signed.serialize();
                } catch (Exception e) {
                    throw new SubmitException(SubmitException.Kind.BUILD, what + ": cannot serialise the transaction: " + rootMessage(e), e);
                }
                designated.handedOut(txHash, window.toSlot());
                handedOut = true;
                deliver(rt.relays(), cbor, txHash, what, rt.relayTimeout());
            }
            rt.log(what + ": submitted " + txHash);
            awaitInclusion(txHash, what);
            return txHash;
        } catch (PendingTransactionException e) {
            pending = true;
            throw e;
        } finally {
            if (handedOut) {
                // A handed-out attempt's inputs stay claimed for its operation until the series ends, whatever a relay
                // answered (F64): it may still land.
                designated.hold(built.claim().refs);
                built.claim().release();
            } else if (pending) {
                built.claim().releaseAt(window != null ? safeMs(rt.chainTime(), window.toSlot()) + 10_000
                        : System.currentTimeMillis() + UNBOUNDED_CLAIM_MS);
            } else {
                built.claim().release();
            }
        }
    }

    /**
     * How long a pending transaction without a validity bound (a reference-script publication, a prepared publication)
     * keeps its inputs claimed: it could land at any time, so this is a bound on the bookkeeping, not on the chain.
     */
    static final long UNBOUNDED_CLAIM_MS = 600_000L;

    /**
     * Hands a prepared transaction without a validity bound (a reference-script or verifier publication) to the caller:
     * observers see it first ({@code onBuilt}: diagnostic only, what they keep is not tracked), then it must balance per
     * unit ({@link BodyBalance}, #152). Returned, its inputs
     * stay claimed for {@link #UNBOUNDED_CLAIM_MS} (a bookkeeping bound, not proof it cannot land); not returned (anything
     * thrown, an {@code Error} included), nothing escaped and the claim ends at once (F71, F77).
     */
    static Transaction exportPrepared(StateRuntime rt, String what, Designation.Claimed claimed) {
        boolean returned = false;
        try {
            rt.built(what, claimed.tx(), true);
            Transaction handed = handedOut(claimed.tx(), what);
            checkBalance(rt, handed, resolveInputs(rt, handed), what);
            returned = true;
            return handed;
        } finally {
            if (returned) claimed.claim().releaseAt(System.currentTimeMillis() + UNBOUNDED_CLAIM_MS);
            else claimed.claim().release();
        }
    }

    /** How long an explicit pick (a seed, a funding input) stays claimed before the build that spends it claims it. */
    static final long PICK_CLAIM_MS = 120_000L;

    /** {@code slot}'s POSIX ms, saturating instead of overflowing (an absurd slot from a provider is not a time). */
    static long safeMs(ChainTime time, long slot) {
        try {
            return time.slotToPosixMs(slot);
        } catch (ArithmeticException e) {
            return slot > 0 ? Long.MAX_VALUE / 2 : Long.MIN_VALUE / 2;
        }
    }

    /**
     * The window of a keyed attempt whose calls use no time (F65, R-E2; F74): from the current slot, the clock its
     * expiry is checked against before delivery (not the tip block's, which lags when slots pass without blocks), for
     * the runtime's TTL. Its validator does not read it, and its Ctx stays zero.
     */
    static Window untimedWindow(ChainTime time, long ttlMs) {
        long from = time.currentSlot();
        long to = Math.addExact(from, Math.max(1, ttlMs / time.slotLengthMs()));
        return KeyedObjects.window(time, from, to);
    }

    /**
     * The keyed operation this transaction is an attempt of (ADR-0016 D7), or null. With one, the built transaction must
     * spend its designated input, it is sent at its window's start to every relay, and its hash is recorded first. With
     * none, it goes to the backend alone, as before M2.
     */
    Designation.Designated designated;

    /** The longest the runtime waits for a window to start (beyond D9's longest cooldown, an hour). */
    static final long MAX_START_WAIT_MS = 2 * 3_600_000L;

    /**
     * Waits until the current slot (this machine's clock, the network's slot configuration: slots pass without blocks)
     * reaches the window's start, then checks the window is still open: an attempt that was never handed out and whose
     * window closed is refused as transient, so the operation rebuilds it with the same designated input (F57, R-C18).
     */
    private void awaitWindow(Window window, String what) {
        ChainTime time = rt.chainTime();
        long now = time.currentSlot();
        if (now < window.fromSlot()) {
            long waitMs = time.slotToPosixMs(window.fromSlot()) - System.currentTimeMillis();
            if (waitMs > MAX_START_WAIT_MS) {
                throw new JoustException(what + ": its window starts at slot " + window.fromSlot() + ", " + waitMs / 1000
                        + " s from now: too far ahead (the provider's link times may be wrong)");
            }
            rt.log(what + ": waiting for slot " + window.fromSlot() + " (now " + now + ") to submit");
            while (time.currentSlot() < window.fromSlot()) {
                sleep(Math.max(50, Math.min(2_000, time.slotToPosixMs(window.fromSlot()) - System.currentTimeMillis())));
            }
        }
        if (time.currentSlot() >= window.toSlot()) {
            throw new SubmitException(SubmitException.Kind.TRANSIENT, what + ": its window [" + window.fromSlot() + ", "
                    + window.toSlot() + ") closed before it was handed out; rebuilding");
        }
    }

    /** The pre-M2 submission, kept as it was for every transaction that is not a keyed operation's attempt. */
    private void submitToBackend(Transaction signed, String txHash, String what) {
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
    }

    /**
     * D7's safety rests on every attempt spending the designated input: checked on the signed body, before anything is
     * handed out (R-C9). A failure is a defect, not contention: never retried.
     */
    static void requireSpends(Transaction signed, String designatedRef, String what) {
        for (var in : signed.getBody().getInputs()) {
            if (Designation.ref(in).equals(designatedRef)) return;
        }
        throw new JoustException(what + ": the built transaction does not spend its designated input " + designatedRef
                + "; not submitting it (ADR-0016 D7)");
    }

    /**
     * Sends the same signed bytes to every relay (ADR-0016 D7). The outcome is transaction-wide: any acceptance means
     * submitted; otherwise any unknown delivery means its fate is unknown ({@link PendingTransactionException}: never
     * rebuilt). A relay that throws, or answers nothing, is an unknown delivery (R-C3). Only rejections by every relay
     * are a rejection ({@link SubmitException}): of kind SCRIPT when every reason is a phase-2 script failure.
     */
    static void deliver(java.util.List<Relay> relays, byte[] cbor, String txHash, String what) {
        deliver(relays, cbor, txHash, what, java.time.Duration.ofSeconds(60));
    }

    /** The most relay calls running at once in this JVM (F70). */
    static final int RELAY_THREADS = 64;

    /**
     * Threads that call relays: all of one attempt's at once, so one slow relay delays no other (R-E8), and at most
     * {@link #RELAY_THREADS} in all (F70). A relay call still running when its attempt stops waiting is cancelled
     * (interrupted); one that ignores interrupts keeps its thread until it returns. A relay that cannot be started
     * because every thread is busy is an unknown delivery: it never got the bytes, but another relay may have.
     */
    private static final java.util.concurrent.ExecutorService RELAYS = new java.util.concurrent.ThreadPoolExecutor(0,
            RELAY_THREADS, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(), r -> {
        Thread t = new Thread(r, "joust-relay");
        t.setDaemon(true);
        return t;
    });

    /**
     * As {@link #deliver(java.util.List, byte[], String, String)}, with every relay called in parallel and given until
     * {@code timeout}: a relay that has not answered by then is an unknown delivery (R-E8). An interrupt while waiting
     * makes the unanswered ones unknown too (the flag kept).
     */
    static void deliver(java.util.List<Relay> relays, byte[] cbor, String txHash, String what, java.time.Duration timeout) {
        var futures = new java.util.ArrayList<java.util.concurrent.Future<Relay.Outcome>>();
        try {
            for (Relay relay : relays) {
                try {
                    futures.add(RELAYS.submit(() -> relay.submit(cbor)));
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    futures.add(java.util.concurrent.CompletableFuture.completedFuture(
                            Relay.Outcome.unknown("no relay thread free (" + RELAY_THREADS + " busy)")));
                }
            }
            combine(futures, txHash, what, timeout);
        } finally {
            // Unanswered relays are not waited for: cancel them (an unknown delivery all the same; F70).
            for (var f : futures) if (!f.isDone()) f.cancel(true);
        }
    }

    private static void combine(java.util.List<java.util.concurrent.Future<Relay.Outcome>> futures, String txHash, String what,
                                java.time.Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean interrupted = false;
        boolean accepted = false;
        boolean unknown = false;
        String unknownWhy = null;
        boolean rejectedAny = false;   // tracked apart from its (nullable) reason (F67)
        String rejected = null;
        boolean allScript = true;
        for (var f : futures) {
            Relay.Outcome o;
            if (interrupted) {
                o = Relay.Outcome.unknown("interrupted before the relay answered");
            } else {
                try {
                    o = f.get(Math.max(0, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    o = Relay.Outcome.unknown("the relay did not answer within " + timeout);
                } catch (java.util.concurrent.CancellationException e) {
                    o = Relay.Outcome.unknown("the relay call was cancelled");
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    o = Relay.Outcome.unknown("the relay threw " + t.getClass().getSimpleName() + ": " + rootMessage(t));
                } catch (InterruptedException e) {
                    interrupted = true;
                    o = Relay.Outcome.unknown("interrupted before the relay answered");
                } catch (RuntimeException e) {
                    o = Relay.Outcome.unknown("the relay failed: " + rootMessage(e));
                }
            }
            if (o == null || o.answer() == null) o = Relay.Outcome.unknown("the relay gave no answer");
            switch (o.answer()) {
                case ACCEPTED -> accepted = true;
                case UNKNOWN -> {
                    if (!unknown) unknownWhy = o.message();
                    unknown = true;
                }
                case REJECTED -> {
                    if (rejected == null) rejected = o.message();
                    rejectedAny = true;
                    if (!scriptFailure(o.message())) allScript = false;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (accepted) return;
        if (unknown) throw new PendingTransactionException(txHash, what + ": submission outcome unknown (" + unknownWhy + ")");
        if (!rejectedAny) throw new PendingTransactionException(txHash, what + ": no relay to submit to");
        // A definite answer from every relay: not accepted.
        throw new SubmitException(allScript ? SubmitException.Kind.SCRIPT : SubmitException.Kind.REJECTED,
                what + ": the node rejected the transaction: " + (rejected != null ? rejected : "(no reason given)"));
    }

    /** A node's phase-2 refusal: a script failed (not an input, the window, or the fee). */
    static boolean scriptFailure(String message) {
        return message != null && (message.contains("PlutusFailure") || message.contains("ValidationTagMismatch")
                || message.contains("ScriptFailure"));
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
        // The funding pick is claimed in this scope, released once the transaction was built and claimed (F71).
        try (var picks = Designation.scope()) {
            return prepareInScope(tx, payer, window, requiredSigners, expected, payouts, sends, referenceScripts, what, spent);
        }
    }

    private PreparedTx prepareInScope(Tx tx, Account payer, Window window, byte[][] requiredSigners, ExpectedState expected,
                                      java.util.List<ExpectedPayout> payouts, java.util.List<ExpectedSend> sends,
                                      com.bloxbean.cardano.client.plutus.spec.PlutusScript[] referenceScripts, String what,
                                      java.util.Set<String> spent) {
        var needed = new java.util.LinkedHashMap<String, byte[]>();
        needed.put(HexUtil.encodeHexString(Keys.pkh(payer)), Keys.pkh(payer));   // the payer's inputs
        for (byte[] sg : requiredSigners) needed.put(HexUtil.encodeHexString(sg), sg);
        // A payout to the payer's own address can be the only output at it, and the balancer would then take the fee
        // out of it. Spending one of the payer's UTxOs gives it a change output to pay the fee from (restorePayouts).
        // A keyed call's designated input (ADR-0016 D11) is one of the payer's ordinary UTxOs, spent already: it gives the
        // change output (and picking another largest UTxO could pick it twice).
        // A UTxO a call collected for the assets it pays in may be the largest: it gives the change output already, and
        // collecting it again would count it twice (ADR-0015 §13.8, #152).
        if (designated == null && payouts.stream().anyMatch(p -> p.address().equals(payer.baseAddress()))) {
            var others = new java.util.HashSet<>(spent);
            others.removeAll(assetInputs);
            // Nor a reference input the calls read (#165 Codex round 8).
            if (tx instanceof RecordingTx r) others.addAll(r.readRefs);
            // Picked and claimed under the payer's lock, so no keyed operation designates it before the build (F63).
            Utxo funding = Designation.pick(payer.baseAddress(), PICK_CLAIM_MS, () -> largestAdaOnly(payer.baseAddress(), others));
            if (funding != null && !assetInputs.contains(Designation.ref(funding))) tx.collectFrom(java.util.List.of(funding));
        }
        Transaction unsigned;
        Designation.Claimed built;
        try {
            var ctx = Designation.builder(rt.backend(), null).compose(tx)
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
            var finalCtx = ctx;
            // Built and claimed under the payer's lock (F63).
            built = Designation.build(payer.baseAddress(), null, finalCtx::build);
            unsigned = built.tx();
            // ADR-0016 D11: a keyed call prepared for a wallet spends the page's designated input (checked again on the
            // handed-out body below).
            if (designated != null) requireSpends(unsigned, designated.ref(), what);
        } catch (Exception e) {
            throw new SubmitException(what + ": could not build the transaction: " + rootMessage(e), e);
        }
        boolean returned = false;
        try {
            rt.built(what, unsigned, true);
            // What the wallet will sign, decoded from its bytes: every check below reads it, and nothing else holds it.
            Transaction handed = handedOut(unsigned, what);
            if (designated != null) requireSpends(handed, designated.ref(), what);
            // Resolved once: the per-unit balance (#152) and the keys whose inputs the transaction spends.
            var spends = resolveInputs(rt, handed);
            checkBalance(rt, handed, spends, what);
            requireDisjointReads(handed, what);
            checkCoCallers(handed, spends, payer.baseAddress(), coCallers, what);
            verify(handed, expected, what);
            for (ExpectedState e : extraExpected) verify(handed, e, what);
            verifyPayouts(handed, payouts, what);
            verifySends(handed, sends, what);
            // Every key whose UTxO the transaction spends (or takes as collateral) must sign too (review r3).
            for (byte[] owner : inputOwners(spends)) needed.putIfAbsent(HexUtil.encodeHexString(owner), owner);
            var prepared = new PreparedTx(rt, handed, new java.util.ArrayList<>(needed.values()), window, what);
            returned = true;
            return prepared;
        } finally {
            // Returned: the wallet may sign and submit it until its TTL, so its inputs stay claimed until then.
            // Not returned (a check or a lookup failed): nothing can be submitted, so the claim ends now (F71).
            if (returned) {
                long ttl = unsigned.getBody().getTtl();
                built.claim().releaseAt(ttl > 0 ? safeMs(rt.chainTime(), ttl) + 10_000 : System.currentTimeMillis() + UNBOUNDED_CLAIM_MS);
            } else {
                built.claim().release();
            }
        }
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
            for (int page = 1; ; page++) {   // to the listing's end (#165 Codex r6, r7)
                var r = rt.backend().getUtxoService().getUtxos(address, 100, page);
                if (!r.isSuccessful()) {
                    if (page == 1) break;   // nothing listed, or the provider did not answer: no funding pick
                    throw new JoustException("cannot read page " + page + " of the payer's UTxOs at " + address + ": "
                            + r.getResponse());
                }
                if (r.getValue() == null || r.getValue().isEmpty()) break;
                for (Utxo u : r.getValue()) {
                    if (u.getReferenceScriptHash() != null || u.getInlineDatum() != null || u.getDataHash() != null) continue;
                    if (spent.contains(Designation.ref(u))) continue;
                    if (!Designation.pickable(u, currentOperation())) continue;   // another operation's (ADR-0016 D7, F72)
                    BigInteger q = lovelace(u);
                    if (q.compareTo(BigInteger.valueOf(3_000_000)) < 0) continue;
                    if (best == null || q.compareTo(lovelace(best)) > 0) best = u;
                }
                if (r.getValue().size() < 100) break;
                if (page == StateContractHandle.MAX_PAGES) {
                    throw new JoustException("the payer's listing at " + address + " has more than " + StateContractHandle.MAX_PAGES
                            + " pages; not read to its end");
                }
            }
        } catch (JoustException e) {
            throw e;
        } catch (Exception e) {
            // A listing that could not be read is not one without a candidate (#165 Codex round 7).
            throw new JoustException("cannot read the payer's UTxOs at " + address, e);
        }
        return best;
    }

    private static BigInteger lovelace(Utxo u) {
        return u.getAmount().stream().filter(a -> a.getUnit().equals("lovelace")).map(a -> a.getQuantity())
                .findFirst().orElse(BigInteger.ZERO);
    }

    /** The payment key hashes owning the transaction's spent and collateral inputs (those at key addresses). */
    static java.util.List<byte[]> inputOwners(java.util.Map<com.bloxbean.cardano.client.transaction.spec.TransactionInput, Utxo> spends) {
        var out = new java.util.ArrayList<byte[]>();
        for (var e : spends.entrySet()) {
            Utxo u = e.getValue();
            // R3-3: a collateral-only input is not seen by checkCoCallers; its missing address is a clear refusal too.
            if (u.getAddress() == null) {
                throw new JoustException("the backend gave no address for input "
                        + e.getKey().getTransactionId() + "#" + e.getKey().getIndex());
            }
            var cred = com.bloxbean.cardano.client.address.AddressProvider.getPaymentCredential(
                    new com.bloxbean.cardano.client.address.Address(u.getAddress()));
            if (cred.isPresent() && cred.get().getType() == com.bloxbean.cardano.client.address.CredentialType.Key) {
                out.add(cred.get().getBytes());
            }
        }
        return out;
    }

    /**
     * The outputs {@code t} spends and takes as collateral, in body order, from the backend (the chain reads the build
     * used); an input it cannot resolve is missing.
     */
    static java.util.Map<com.bloxbean.cardano.client.transaction.spec.TransactionInput, Utxo> resolveInputs(StateRuntime rt, Transaction t) {
        var out = new java.util.LinkedHashMap<com.bloxbean.cardano.client.transaction.spec.TransactionInput, Utxo>();
        var refs = new java.util.ArrayList<com.bloxbean.cardano.client.transaction.spec.TransactionInput>(t.getBody().getInputs());
        if (t.getBody().getCollateral() != null) refs.addAll(t.getBody().getCollateral());
        for (var in : refs) {
            if (out.containsKey(in)) continue;
            try {
                var r = rt.backend().getUtxoService().getTxOutput(in.getTransactionId(), in.getIndex());
                if (r.isSuccessful() && r.getValue() != null) out.put(in, r.getValue());
            } catch (Exception e) {
                throw new JoustException("cannot resolve input " + in.getTransactionId() + "#" + in.getIndex(), e);
            }
        }
        return out;
    }

    /**
     * The transaction handed to a wallet (#165 Codex): decoded from the built one's bytes, after the observers saw it.
     * The checks then read what the wallet signs: a field the object holds twice (two withdrawals from one reward
     * account, a policy listed twice) is one map key in CBOR, and the decoded copy has what survived. An observer that
     * kept the built object cannot change the handed-out one. Bytes that do not read back the same are refused.
     */
    static Transaction handedOut(Transaction built, String what) {
        return BodyBalance.readBack(built, what);
    }

    /** Shows observers a signed body the runtime refused, without its signatures; an observer's failure is suppressed. */
    private static void observeRefused(StateRuntime rt, String what, Transaction tx, RuntimeException refused) {
        try {
            rt.builtRefused(what, tx);
        } catch (RuntimeException | Error e) {
            refused.addSuppressed(e);
        }
    }

    /**
     * Refuses a body that spends one of its own reference inputs (#165 Codex round 8): the ledger rejects it
     * ({@code BabbageNonDisjointRefInputs}). References compare in lower case (a provider may spell an id either way).
     */
    static void requireDisjointReads(Transaction t, String what) {
        var refs = t.getBody().getReferenceInputs();
        if (refs == null || refs.isEmpty() || t.getBody().getInputs() == null) return;
        var read = new java.util.HashSet<String>();
        for (var r : refs) read.add(r.getTransactionId().toLowerCase() + "#" + r.getIndex());
        for (var in : t.getBody().getInputs()) {
            String ref = in.getTransactionId().toLowerCase() + "#" + in.getIndex();
            if (read.contains(ref)) {
                throw new JoustException(what + ": the transaction spends and reads " + ref + " (the ledger refuses a"
                        + " reference input that is also spent); refused");
            }
        }
    }

    /**
     * Refuses a body that does not balance per unit ({@link BodyBalance}, #152): after the observers saw it (onBuilt
     * observes every built transaction), before it is handed out.
     */
    static void checkBalance(StateRuntime rt, Transaction t,
                             java.util.Map<com.bloxbean.cardano.client.transaction.spec.TransactionInput, Utxo> spends, String what) {
        // Read only when a legacy stake certificate needs the key deposit (R3).
        BodyBalance.check(t.getBody(), spends::get, () -> protocolParams(rt, what), what);
    }

    /** The current protocol parameters, or a JoustException naming {@code what}. */
    static com.bloxbean.cardano.client.api.model.ProtocolParams protocolParams(StateRuntime rt, String what) {
        com.bloxbean.cardano.client.api.model.Result<com.bloxbean.cardano.client.api.model.ProtocolParams> r;
        try {
            r = rt.backend().getEpochService().getProtocolParameters();
        } catch (Exception e) {
            throw new JoustException(what + ": cannot read the protocol parameters", e);
        }
        if (r == null || !r.isSuccessful() || r.getValue() == null) {
            throw new JoustException(what + ": cannot read the protocol parameters" + (r == null ? "" : ": " + r.getResponse()));
        }
        return r.getValue();
    }

    /**
     * A prepared transaction takes from no key address but the payer's more than its calls pay in from there (T1): per
     * unit, what the inputs at that address hold less what the outputs returning value to it hold is at most
     * {@code paid}'s quantity (nothing for lovelace; the payer funds lovelace and the fee). Defence in depth behind
     * {@code AssetTx.returnSurplus}. Only the return {@code returnSurplus} made counts, by identity: one output at that
     * address, no datum, holding exactly the value it recorded ({@code returns}). Any other output there (a payout, a
     * send, an intent's refund to an enterprise address that is also a wallet's) is owed to it by someone else and
     * cannot stand in for a short return (#165 Codex, Fable). And exactly what it pays in (#165 Opus): a larger return
     * would give it the payer's value. The collateral is the payer's: every collateral input is at the payer's address
     * and so is the collateral return (the phase-2 failure path takes from no one else; #165 Codex).
     */
    static void checkCoCallers(Transaction t,
                               java.util.Map<com.bloxbean.cardano.client.transaction.spec.TransactionInput, Utxo> spends,
                               String payer, CoCallers coCallers, String what) {
        var paid = coCallers.paid();
        var returns = coCallers.returns();
        var body = t.getBody();
        // Each collected input of another caller is that caller's on the chain too (#165 Codex round 3): one the chain
        // puts at the payer's address would leave the caller out of the accounting below.
        for (var in : body.getInputs()) {
            String listed = coCallers.owners().get(Designation.ref(in));
            if (listed == null || listed.equals(payer)) continue;
            Utxo u = spends.get(in);
            if (u == null || u.getAddress() == null || !u.getAddress().equals(listed)) {
                throw new JoustException(what + ": input " + in.getTransactionId() + "#" + in.getIndex() + " was listed at "
                        + listed + " but the chain has it at " + (u == null ? "no address" : u.getAddress()) + "; refused");
            }
        }
        if (body.getCollateral() != null) {
            for (var in : body.getCollateral()) {
                Utxo u = spends.get(in);
                if (u == null || u.getAddress() == null || !u.getAddress().equals(payer)) {
                    throw new JoustException(what + ": collateral input " + in.getTransactionId() + "#" + in.getIndex()
                            + " is not at the payer's address" + (u == null || u.getAddress() == null ? " (unknown)" : " but at "
                            + u.getAddress()) + "; refused");
                }
            }
        }
        if (body.getCollateralReturn() != null && !payer.equals(body.getCollateralReturn().getAddress())) {
            throw new JoustException(what + ": the collateral return goes to " + body.getCollateralReturn().getAddress()
                    + ", not the payer; refused");
        }
        var net = new java.util.LinkedHashMap<String, java.util.Map<String, BigInteger>>();
        for (var in : body.getInputs()) {
            Utxo u = spends.get(in);
            if (u == null || u.getAddress() == null) {
                // Fail closed (R7): an input whose owner is unknown could be a co-caller's.
                throw new JoustException(what + ": cannot tell whose input " + in.getTransactionId() + "#" + in.getIndex()
                        + " is (the provider gave no address); refused");
            }
            if (u.getAddress().equals(payer)) continue;
            var cred = com.bloxbean.cardano.client.address.AddressProvider.getPaymentCredential(
                    new com.bloxbean.cardano.client.address.Address(u.getAddress()));
            if (cred.isEmpty() || cred.get().getType() != com.bloxbean.cardano.client.address.CredentialType.Key) continue;
            var m = net.computeIfAbsent(u.getAddress(), k -> new java.util.HashMap<>());
            for (var a : u.getAmount()) {
                m.merge(com.bloxbean.cardano.client.api.util.AssetUtil.normalizeUnit(a.getUnit()), a.getQuantity(), BigInteger::add);
            }
        }
        // Every caller other than the payer: those whose inputs the body spends, and those the plan pays in for or returns
        // to (#165 Codex round 3), even when none of the inputs resolves to it.
        for (String a : paid.keySet()) if (!a.equals(payer)) net.computeIfAbsent(a, k -> new java.util.HashMap<>());
        for (String a : returns.keySet()) if (!a.equals(payer)) net.computeIfAbsent(a, k -> new java.util.HashMap<>());
        var outs = body.getOutputs();
        for (var e : net.entrySet()) {
            var back = returns.get(e.getKey());
            if (back == null) continue;
            // The return by its place (#165 Codex round 3): an equal output elsewhere (a send) is not it.
            TransactionOutput o = back.index() < outs.size() ? outs.get(back.index()) : null;
            var v = o == null ? null : valueOf(o);
            if (o == null || !o.getAddress().equals(e.getKey()) || o.getInlineDatum() != null || o.getDatumHash() != null
                    || !org.julclang.state.api.Assets.covers(v, back.value()) || !org.julclang.state.api.Assets.covers(back.value(), v)) {
                throw new JoustException(what + ": output " + back.index() + " is not the return to " + e.getKey()
                        + " the composition made; refused (T1, #152)");
            }
            for (var a : com.bloxbean.cardano.client.api.util.ValueUtil.toAmountList(o.getValue())) {
                e.getValue().merge(com.bloxbean.cardano.client.api.util.AssetUtil.normalizeUnit(a.getUnit()), a.getQuantity().negate(), BigInteger::add);
            }
        }
        for (var e : net.entrySet()) {
            var allowed = new java.util.HashMap<String, BigInteger>();
            var v = paid.get(e.getKey());
            if (v != null) {
                org.julclang.state.api.Assets.read(v).forEach((p, ts) -> ts.forEach((n, q) -> allowed.put(
                        p.isEmpty() ? "lovelace" : com.bloxbean.cardano.client.api.util.AssetUtil.normalizeUnit(p + n), q)));
            }
            // Exactly what it pays in (#165 Opus P2): less would take its value, more would give it someone else's (the
            // payer's, when a listing and the chain disagree).
            var units = new java.util.TreeSet<String>(e.getValue().keySet());
            units.addAll(allowed.keySet());
            for (String unit : units) {
                BigInteger taken = e.getValue().getOrDefault(unit, BigInteger.ZERO);
                BigInteger pays = allowed.getOrDefault(unit, BigInteger.ZERO);
                if (taken.compareTo(pays) > 0) {
                    throw new JoustException(what + ": the transaction would take " + taken + " " + unit + " from "
                            + e.getKey() + ", which is not the payer and pays in only " + pays + "; refused (T1, #152)");
                }
                if (taken.compareTo(pays) < 0) {
                    throw new JoustException(what + ": the transaction would give " + pays.subtract(taken) + " " + unit
                            + " to " + e.getKey() + " beyond what its inputs return, which is not the payer; refused (T1, #152)");
                }
            }
        }
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
        Designation.Claimed built;
        try {
            var ctx = Designation.builder(rt.backend(), null).compose(tx)
                    .withSigner(SignerProviders.signerFrom(payer))
                    .feePayer(payer.baseAddress());
            built = Designation.build(payer.baseAddress(), null, ctx::buildAndSign);
        } catch (Exception e) {
            throw new SubmitException(what + ": could not build the transaction: " + rootMessage(e), e);
        }
        boolean pending = false;
        try {
            return submitPlain(built.tx(), what);
        } catch (PendingTransactionException e) {
            pending = true;
            throw e;
        } finally {
            // A pending publication may still land: it has no validity bound, so its inputs stay claimed for a while.
            if (pending) built.claim().releaseAt(System.currentTimeMillis() + UNBOUNDED_CLAIM_MS);
            else built.claim().release();
        }
    }

    private String submitPlain(Transaction signed, String what) {
        // A publication's change can hold what several inputs held (#165 Codex round 4): sized like any other body.
        // Observed signed only after the check (#165 Codex round 8).
        try {
            BodyBalance.checkValueSizes(signed.getBody(), () -> protocolParams(rt, what), what);
        } catch (RuntimeException refused) {
            observeRefused(rt, what, signed, refused);
            throw refused;
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
        if (count != 1) throw new SubmitException(SubmitException.Kind.BUILD, what + ": built transaction has " + count + " state outputs");
        if (found.getValue().getCoin().compareTo(expected.lovelace()) != 0) {
            throw new SubmitException(SubmitException.Kind.BUILD, what + ": builder changed the state output's lovelace to "
                    + found.getValue().getCoin() + " (expected " + expected.lovelace()
                    + "); raise @StateContract(minAda) for this state size");
        }
        if (found.getInlineDatum() == null || !Datums.hex(found.getInlineDatum()).equals(Datums.hex(expected.datum()))) {
            throw new SubmitException(SubmitException.Kind.BUILD, what + ": built transaction's state datum differs from the JVM result");
        }
        if (expected.value() != null && !org.julclang.state.api.Assets.isEmpty(
                org.julclang.state.api.Assets.subtract(valueOf(found), expected.value()))) {
            throw new SubmitException(SubmitException.Kind.BUILD, what + ": built transaction's state output value differs from the JVM result");
        }
    }

    /** Each send is at its claimed index after balancing: address, datum, and at least its value (ADR-0007 D6). */
    private static void verifySends(Transaction t, java.util.List<ExpectedSend> sends, String what) {
        var outs = t.getBody().getOutputs();
        for (ExpectedSend s : sends) {
            if (s.index() >= outs.size()) throw new SubmitException(SubmitException.Kind.BUILD, what + ": send output " + s.index() + " is missing");
            TransactionOutput o = outs.get(s.index());
            boolean datumOk = s.inlineDatumHex() != null
                    ? o.getInlineDatum() != null && Datums.hex(o.getInlineDatum()).equals(s.inlineDatumHex())
                    : s.datumHash() != null
                    ? o.getDatumHash() != null && HexUtil.encodeHexString(o.getDatumHash()).equals(s.datumHash())
                    : o.getInlineDatum() == null && o.getDatumHash() == null;
            boolean covers = org.julclang.state.api.Assets.covers(valueOf(o), s.value());
            if (!o.getAddress().equals(s.address()) || !datumOk || !covers) {
                throw new SubmitException(SubmitException.Kind.BUILD, what + ": the balanced transaction's output " + s.index()
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
                throw new SubmitException(SubmitException.Kind.BUILD, what + ": the balanced transaction no longer pays " + p.lovelace()
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
            sleepOrPending(1000, txHash, what);
        }
        throw new PendingTransactionException(txHash, what + ": not seen on chain within " + rt.confirmTimeout());
    }

    /**
     * Sleeps while a handed-out transaction may land: an interrupt then leaves it pending (the flag restored; R-C4),
     * not a plain failure.
     */
    static void sleepOrPending(long ms, String txHash, String what) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PendingTransactionException(txHash, what + ": interrupted while waiting for the transaction");
        }
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
