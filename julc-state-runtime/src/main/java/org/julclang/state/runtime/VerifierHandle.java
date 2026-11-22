package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.types.JulcList;
import org.julclang.state.api.mpf.MapClaim;
import org.julclang.state.api.mpf.MapClaims;
import org.julclang.state.verifier.MapVerifier;

import java.math.BigInteger;
import java.util.List;

/**
 * A shared JOUST withdraw-zero verifier on a network: the map verifier (ADR-0002 D9) or the output verifier (ADR-0007
 * D5). Its reference script is kept at the verifier's own enterprise address (unspendable: the script only validates
 * withdrawals), and its stake credential is registered. Transactions withdraw 0 from it with their claims as the
 * redeemer.
 */
public final class VerifierHandle {

    private final StateRuntime runtime;
    private final PlutusV3Script script;
    private final String hash;
    private final String holder;
    private final String rewardAddress;
    private volatile Utxo reference;

    private final String label;

    VerifierHandle(StateRuntime runtime, Class<?> verifier, String label) {
        this.runtime = runtime;
        this.label = label;
        this.script = ValidatorScripts.load(verifier);
        try {
            this.hash = HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new JoustException("cannot hash the " + label, e);
        }
        this.holder = AddressProvider.getEntAddress(script, runtime.network()).toBech32();
        this.rewardAddress = AddressProvider.getRewardAddress(script, runtime.network()).toBech32();
    }

    public PlutusV3Script script() { return script; }
    public String rewardAddress() { return rewardAddress; }
    public String hash() { return hash; }
    /** Where its reference script is kept (the verifier's own enterprise address). */
    public String holder() { return holder; }

    /** Its reference script on chain, or null. */
    public Utxo reference() { return find(); }

    /**
     * The transaction publishing its reference script and registering its stake credential, unsigned, paid from
     * {@code payerAddress} (a wallet signs it; ADR-0015 D5), or null when it is already on chain.
     */
    public com.bloxbean.cardano.client.transaction.spec.Transaction prepare(String payerAddress) {
        if (find() != null) return null;
        var tx = new Tx()
                .payToAddress(holder, List.of(Amount.lovelace(deposit())), script)
                .registerStakeAddress(rewardAddress)
                .from(payerAddress);
        try {
            var ctx = Designation.builder(runtime, null).compose(tx)
                    .feePayer(payerAddress)
                    .additionalSignersCount(1);
            // Claimed for a while: a wallet signs and submits it later, and it has no validity bound (F63).
            var claimed = Designation.build(runtime.claimStore(), payerAddress, null, ctx::build);
            return TxRunner.exportPrepared(runtime, "publish and register the JOUST " + label, claimed);
        } catch (ReservedInputException e) {
            throw e;
        } catch (Exception e) {
            throw new SubmitException("publish the " + label + ": could not build the transaction: " + e.getMessage(), e);
        }
    }

    /** The verifier's reference script, publishing it (and registering its stake credential) first if needed. */
    public Utxo ensure(Account payer) {
        Utxo found = find();
        if (found != null) return found;
        if (runtime.hostedCall()) throw new CallScope.Refusal("prerequisite", "missing reference script at " + holder);
        var tx = new Tx()
                .payToAddress(holder, List.of(Amount.lovelace(deposit())), script)
                .registerStakeAddress(rewardAddress)
                .from(payer.baseAddress());
        String txHash = new TxRunner(runtime).submitPlain(tx, payer, "publish and register the JOUST " + label);
        for (int i = 0; i < 60 && find() == null; i++) TxRunner.sleep(500);
        if (find() == null) throw new PendingTransactionException(txHash, label + " not visible yet");
        runtime.log("published and registered the " + label + " in " + txHash);
        return find();
    }

    /** Adds the zero withdrawal carrying the map {@code claims} and the verifier's reference input to {@code tx}. */
    void attach(Tx tx, List<MapClaim> claims, Account payer) {
        var cap = TxRunner.capture();
        if (cap != null) {
            // In a composition every call's claims go into one withdrawal, attached when it is submitted.
            ensure(payer);
            cap.mapClaims.addAll(claims);
            return;
        }
        JulcList<MapClaim> list = JulcList.empty();
        for (int i = claims.size() - 1; i >= 0; i--) list = list.prepend(claims.get(i));
        attachRedeemer(tx, new MapClaims(list), payer);
    }

    /** Adds the zero withdrawal carrying {@code redeemer} and the verifier's reference input to {@code tx}. */
    void attachRedeemer(Tx tx, Object redeemer, Account payer) {
        Utxo ref = ensure(payer);
        tx.readFrom(ref).withdraw(rewardAddress, BigInteger.ZERO, PlutusDataAdapter.convert(redeemer));
    }

    /** How far a scoped call looks for a pinned reference: a bound on a hostile answer, not a policy. */
    static final int PIN_PAGES = 10_000;

    private Utxo find() {
        if (runtime.hostedCall()) return runtime.hostedReference(holder, hash);
        // ADR-0022 D8: a scoped (hosted) call sees only its own answers, so it neither reads nor fills the cache that
        // unscoped callers keep; a rollback between two calls cannot hand the second call the first call's hit.
        boolean scoped = runtime.callScope() != null;
        if (!scoped && reference != null) return reference;
        var pinned = runtime.referenceScript(hash);
        if (pinned != null && !holder.equals(pinned.getAddress())) pinned = null;
        if (pinned != null && !scoped) {
            reference = pinned;
            return pinned;
        }
        // A scoped call trusts only this call's answers: a pin is used once the answer shows it (a rollback may have
        // removed it since the runtime was built; #145 Codex review), else the answer's own reference script.
        Utxo first = null;
        try {
            // Unscoped, the first page, as always; a scoped call, or one looking for a pin, every page until the answer runs
            // out: absence must be the answer's, not a page cut (#145 Codex).
            // Scoped, a failed page is a failed read, not the end of the answer: absence would publish a copy (Pages).
            var walk = new Pages(runtime, holder, 20, scoped || pinned != null ? PIN_PAGES : 1);
            for (var list = walk.next(); list != null; list = walk.next()) {
                for (Utxo u : list) {
                    if (!hash.equalsIgnoreCase(u.getReferenceScriptHash())) continue;
                    if (pinned == null) {
                        if (!scoped) reference = u;
                        return u;
                    }
                    if (Designation.ref(u).equals(Designation.ref(pinned))) return u;
                    if (first == null) first = u;
                }
            }
        } catch (Exception e) {
            throw new JoustException("cannot look up the " + label, e);
        }
        return first;
    }

    private BigInteger deposit() {
        try {
            var pp = runtime.backend().getEpochService().getProtocolParameters().getValue();
            var out = TransactionOutput.builder().address(holder)
                    .value(Value.builder().coin(BigInteger.valueOf(2_000_000)).build()).scriptRef(script).build();
            return new MinAdaCalculator(pp).calculateMinAda(out).add(BigInteger.valueOf(1_000_000));
        } catch (Exception e) {
            throw new JoustException("cannot compute the " + label + " deposit", e);
        }
    }
}
