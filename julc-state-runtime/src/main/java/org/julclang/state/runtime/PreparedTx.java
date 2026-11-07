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

    PreparedTx(StateRuntime runtime, Transaction tx, List<byte[]> requiredSigners, Window window, String what) {
        this.runtime = runtime;
        this.tx = tx;
        this.txHash = TransactionUtil.getTxHash(tx);
        this.requiredSigners = List.copyOf(requiredSigners);
        this.window = window;
        this.what = what;
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

    /** The last slot the transaction is valid in, or -1 if it has no upper bound. Sign and submit before it. */
    public long validTo() {
        return window == null ? -1 : window.toSlot();
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
