package org.julclang.state.runtime;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A balanced transaction waiting for wallet signatures (ADR-0013 D1). Give {@link #cbor()} to the wallet's CIP-30
 * {@code signTx(cbor, true)}; it returns a witness set holding only the wallet's signatures. {@link #withWitnesses}
 * adds them, after checking each one, and returns the signed transaction for the wallet's {@code submitTx}; or
 * {@link #submit} submits it from here and waits for it.
 * <p>
 * The witnesses are added to the transaction as built here: the body, the redeemers and the datums are never decoded
 * from a client, so neither the hash nor {@code script_data_hash} can change; the result's hash is checked anyway.
 */
public final class PreparedTx {

    private final StateRuntime runtime;
    private final Transaction tx;
    private final String txHash;
    private final List<byte[]> requiredSigners;
    private final Window window;
    private final String what;
    private final List<Payout> payouts;
    private final List<Send> sends;

    /**
     * A payout the build recorded (ADR-0022 D5a, F12): an output at {@code address} tagged with {@code tagCbor} (the
     * paying instance's or object's input, hex CBOR) holding at least {@code owed} lovelace, the amount the contract
     * owes; the output itself may hold more (topped up to the ledger minimum).
     */
    public record Payout(String address, java.math.BigInteger owed, String tagCbor) {}

    /**
     * A send the build recorded (ADR-0007 D6): output {@code index} of the transaction, at {@code address}, with exactly
     * this inline datum ({@code inlineDatumCbor}, hex) or datum hash, or neither, holding at least {@code value}.
     */
    public record Send(int index, String address, String inlineDatumCbor, String datumHash, org.julclang.ledger.Value value) {}

    PreparedTx(StateRuntime runtime, Transaction tx, List<byte[]> requiredSigners, Window window, String what) {
        this(runtime, tx, requiredSigners, window, what, List.of(), List.of());
    }

    PreparedTx(StateRuntime runtime, Transaction tx, List<byte[]> requiredSigners, Window window, String what,
               List<Payout> payouts, List<Send> sends) {
        this.runtime = runtime;
        this.tx = tx;
        this.txHash = TransactionUtil.getTxHash(tx);
        this.requiredSigners = List.copyOf(requiredSigners);
        this.window = window;
        this.what = what;
        this.payouts = List.copyOf(payouts);
        this.sends = List.copyOf(sends);
    }

    /** The unsigned transaction, hex CBOR, for CIP-30 {@code signTx}. */
    public String cbor() {
        try {
            return HexUtil.encodeHexString(tx.serialize());
        } catch (Exception e) {
            throw new JoustException("cannot serialise the prepared transaction", e);
        }
    }

    public String txHash() {
        return txHash;
    }

    /** The key hashes (hex) that must sign: the payer's, and every caller's required signature. */
    public List<String> requiredSigners() {
        return requiredSigners.stream().map(HexUtil::encodeHexString).toList();
    }

    /**
     * The end of the window the call chose, or -1 when the call chose none (an untimed call: its body may still carry
     * the runtime's TTL as an upper bound; {@link #validToSlot()} reads the body). Sign and submit before it.
     */
    public long validTo() {
        return window == null ? -1 : window.toSlot();
    }

    /**
     * The transaction body's validity upper bound (its {@code ttl}: the first slot it is no longer valid in), or empty
     * when the body has none (ADR-0022 D5: read from the final body, never from build metadata).
     */
    public java.util.OptionalLong validToSlot() {
        long ttl = tx.getBody().getTtl();
        return ttl == 0 ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(ttl);
    }

    /** The transaction body's validity lower bound (its first valid slot), or empty when the body has none. */
    public java.util.OptionalLong validFromSlot() {
        long from = tx.getBody().getValidityStartInterval();
        return from == 0 ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(from);
    }

    /** The payouts the build recorded, with the amounts owed (ADR-0022 D5a); the transaction pays each, possibly more. */
    public List<Payout> payouts() {
        return payouts;
    }

    /** The sends the build recorded, at their output indices (ADR-0007 D6, ADR-0022 D5a). */
    public List<Send> sends() {
        return sends;
    }

    /** The portable plan of this transaction (ADR-0013 D4): see {@link TxPlan}. */
    public java.util.Map<String, Object> plan() {
        return TxPlan.of(tx);
    }

    /** A copy of the transaction this prepared, for inspection. */
    public Transaction transaction() {
        return TransactionUtil.createCopy(tx);
    }

    /**
     * The transaction with the wallets' signatures, hex CBOR. Each witness set must hold vkey witnesses only, each a
     * valid Ed25519 signature of this transaction's hash; together they must cover every {@link #requiredSigners()}.
     */
    public String withWitnesses(String... witnessSetsCbor) {
        try {
            return HexUtil.encodeHexString(signed(witnessSetsCbor).serialize());
        } catch (JoustException e) {
            throw e;
        } catch (Exception e) {
            throw new JoustException(what + ": cannot serialise the signed transaction", e);
        }
    }

    /** Adds the witnesses as {@link #withWitnesses} does, submits, and waits until the transaction is on chain. */
    public String submit(String... witnessSetsCbor) {
        runtime.requireUnscopedSubmission();
        // Refused before its witnesses are read: a prepared transaction never takes part in a composition (#145 Codex item 3).
        var cap = TxRunner.capture();
        if (cap != null) {
            throw cap.refuse(new TxRunner.EndsComposition("composition: " + what + " submits a transaction already built;"
                    + " it cannot take part"));
        }
        return new TxRunner(runtime).submitSigned(signed(witnessSetsCbor), what);
    }

    private Transaction signed(String... witnessSetsCbor) {
        byte[] hash = HexFormat.of().parseHex(txHash);
        var bySigner = new LinkedHashMap<String, VkeyWitness>();
        for (String hex : witnessSetsCbor) {
            TransactionWitnessSet ws = decodeWitnessSet(hex);
            if (hasMoreThanVkeys(ws)) {
                throw new JoustException(what + ": a wallet witness set may carry signatures only (no scripts, datums,"
                        + " redeemers or bootstrap witnesses)");
            }
            if (ws.getVkeyWitnesses() == null) continue;
            for (VkeyWitness w : ws.getVkeyWitnesses()) {
                boolean valid;
                try {
                    valid = CryptoConfiguration.INSTANCE.getSigningProvider().verify(w.getSignature(), hash, w.getVkey());
                } catch (Exception e) {
                    valid = false;
                }
                if (!valid) throw new JoustException(what + ": a witness does not sign this transaction");
                String signer = HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(w.getVkey()));
                // Only the keys that must sign: the fee was sized for them (a wallet may add others).
                if (requiredSigners.stream().anyMatch(r -> HexUtil.encodeHexString(r).equals(signer))) bySigner.put(signer, w);
            }
        }
        for (byte[] needed : requiredSigners) {
            if (!bySigner.containsKey(HexUtil.encodeHexString(needed))) {
                throw new JoustException(what + ": key " + HexUtil.encodeHexString(needed) + " has not signed");
            }
        }
        // The same body object, so its serialisation, and the hash the wallets signed, cannot change.
        TransactionWitnessSet ws = tx.getWitnessSet() == null ? new TransactionWitnessSet() : tx.getWitnessSet();
        var vkeys = new ArrayList<VkeyWitness>(ws.getVkeyWitnesses() == null ? List.of() : ws.getVkeyWitnesses());
        vkeys.addAll(bySigner.values());
        TransactionWitnessSet merged = TransactionWitnessSet.builder()
                .vkeyWitnesses(vkeys)
                .nativeScripts(ws.getNativeScripts())
                .bootstrapWitnesses(ws.getBootstrapWitnesses())
                .plutusV1Scripts(ws.getPlutusV1Scripts())
                .plutusDataList(ws.getPlutusDataList())
                .redeemers(ws.getRedeemers())
                .plutusV2Scripts(ws.getPlutusV2Scripts())
                .plutusV3Scripts(ws.getPlutusV3Scripts())
                .build();
        Transaction signed = Transaction.builder()
                .era(tx.getEra())
                .body(tx.getBody())
                .witnessSet(merged)
                .auxiliaryData(tx.getAuxiliaryData())
                .isValid(tx.isValid())
                .build();
        if (!TransactionUtil.getTxHash(signed).equals(txHash)) {
            throw new JoustException(what + ": adding the signatures changed the transaction hash");
        }
        return signed;
    }

    private static boolean hasMoreThanVkeys(TransactionWitnessSet ws) {
        return notEmpty(ws.getNativeScripts()) || notEmpty(ws.getBootstrapWitnesses()) || notEmpty(ws.getPlutusV1Scripts())
                || notEmpty(ws.getPlutusDataList()) || notEmpty(ws.getRedeemers()) || notEmpty(ws.getPlutusV2Scripts())
                || notEmpty(ws.getPlutusV3Scripts());
    }

    private static boolean notEmpty(List<?> l) {
        return l != null && !l.isEmpty();
    }

    /**
     * A wallet's witness set: exactly one CBOR map whose only key is 0 (vkey witnesses), in any valid encoding:
     * indefinite lengths and tag 258 sets included (review r3).
     */
    private TransactionWitnessSet decodeWitnessSet(String hex) {
        List<DataItem> items;
        try {
            items = CborDecoder.decode(HexUtil.decodeHexString(hex));
        } catch (Exception e) {
            throw new JoustException(what + ": not CBOR", e);
        }
        if (items.size() != 1 || !(items.get(0) instanceof co.nstant.in.cbor.model.Map map)) {
            throw new JoustException(what + ": a witness set is exactly one CBOR map");
        }
        for (DataItem k : map.getKeys()) {
            if (!(k instanceof co.nstant.in.cbor.model.UnsignedInteger u) || u.getValue().intValue() != 0) {
                throw new JoustException(what + ": a wallet witness set may carry signatures (key 0) only");
            }
        }
        try {
            return TransactionWitnessSet.deserialize((co.nstant.in.cbor.model.Map) withoutBreaks(map));
        } catch (Exception e) {
            throw new JoustException(what + ": not a transaction witness set", e);
        }
    }

    /** {@code item} with the BREAK markers the CBOR decoder leaves inside indefinite-length arrays removed. */
    private static DataItem withoutBreaks(DataItem item) {
        if (item instanceof co.nstant.in.cbor.model.Array a) {
            var copy = new co.nstant.in.cbor.model.Array();
            for (DataItem d : a.getDataItems()) {
                if (d == co.nstant.in.cbor.model.Special.BREAK) continue;
                copy.add(withoutBreaks(d));
            }
            if (a.getTag() != null) copy.setTag(a.getTag());
            return copy;
        }
        if (item instanceof co.nstant.in.cbor.model.Map m) {
            var copy = new co.nstant.in.cbor.model.Map();
            for (DataItem k : m.getKeys()) copy.put(withoutBreaks(k), withoutBreaks(m.get(k)));
            if (m.getTag() != null) copy.setTag(m.getTag());
            return copy;
        }
        return item;
    }
}
