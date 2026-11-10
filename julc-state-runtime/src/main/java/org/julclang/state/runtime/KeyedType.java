package org.julclang.state.runtime;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.ledger.TxOutRef;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiFunction;

/**
 * A keyed owned type of a contract (ADR-0016): what the runtime needs to find, insert, remove and retire its objects.
 * A generated client holds one per keyed type, and lists them in its {@link ContractSpec}.
 *
 * @param name          the record's simple name; {@code typeId = I2OSP(len(name), 1) ++ utf8(name)} (D2)
 * @param headTag       constructor tag of {@code Head_T}; {@code Keyed_T} is {@code headTag + 1}, {@code Retired_T} {@code + 2}
 * @param keyIndex      the key component's index in the record
 * @param maxKeyBytes   longest key
 * @param signedKey     the key is a key hash that must sign the insert (I53)
 * @param cooldownMs    {@code C}, least time between relinks of a node (D9; 0: none)
 * @param windowMs      {@code W}, widest validity window of a relink (D9)
 * @param valueClass    the record; {@code org.julclang.core.PlutusData} for a contract known by its description (its
 *                      values are Data, ADR-0015 D4; ADR-0016 M3)
 * @param link          the {@code Link} spend redeemer
 * @param remove        builds {@code Remove_T(anchor, removed)}; null if the type has no removing destroy
 * @param <R>           the record type
 */
public record KeyedType<R>(String name, int headTag, int keyIndex, int maxKeyBytes, boolean signedKey, long cooldownMs,
                           long windowMs, Class<R> valueClass, Object link, BiFunction<TxOutRef, TxOutRef, Object> remove) {

    /** The most bytes of serialised arguments a keyed initializer or a keyed contract's {@code @Init} may take (D4c). */
    public static final int MAX_ARGUMENT_BYTES = 4_096;

    public int nodeTag() {
        return headTag + 1;
    }

    public int retiredTag() {
        return headTag + 2;
    }

    public byte[] typeId() {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        byte[] t = new byte[n.length + 1];
        t[0] = (byte) n.length;
        System.arraycopy(n, 0, t, 1, n.length);
        return t;
    }

    /** {@code 0x4B ++ blake2b_224(instance ++ typeId ++ key)} (I44); the head's key is empty. */
    public byte[] keyId(byte[] instance, byte[] key) {
        byte[] t = typeId();
        byte[] pre = new byte[instance.length + t.length + key.length];
        System.arraycopy(instance, 0, pre, 0, instance.length);
        System.arraycopy(t, 0, pre, instance.length, t.length);
        System.arraycopy(key, 0, pre, instance.length + t.length, key.length);
        byte[] id = new byte[29];
        id[0] = 0x4B;
        System.arraycopy(Blake2bUtil.blake2bHash224(pre), 0, id, 1, 28);
        return id;
    }

    /** Values are Plutus data (a described contract, ADR-0015 D4): no record class, no reflection. */
    boolean described() {
        return valueClass == org.julclang.core.PlutusData.class;
    }

    /** The key of a record (its component at {@link #keyIndex}). */
    public byte[] keyOf(R value) {
        if (described()) {
            if (!(value instanceof org.julclang.core.PlutusData.ConstrData c) || c.fields().size() <= keyIndex
                    || !(c.fields().get(keyIndex) instanceof org.julclang.core.PlutusData.BytesData b)) {
                throw new JoustException("a " + name + " value without a byte-string key at field " + keyIndex);
            }
            return b.value();
        }
        try {
            return (byte[]) valueClass.getRecordComponents()[keyIndex].getAccessor().invoke(value);
        } catch (ReflectiveOperationException e) {
            throw new JoustException("cannot read the key of a " + name, e);
        }
    }

    // ------------------------------------------------------------------ datums (D2)

    PlutusData head(byte[] instance, byte[] next, BigInteger linkedAt) {
        return constr(headTag, List.of(BytesPlutusData.of(instance), BytesPlutusData.of(next), BigIntPlutusData.of(linkedAt)));
    }

    PlutusData node(byte[] instance, byte[] next, BigInteger linkedAt, R value) {
        return constr(nodeTag(), List.of(BytesPlutusData.of(instance), BytesPlutusData.of(next), BigIntPlutusData.of(linkedAt),
                valueData(value)));
    }

    /** A value as Plutus data (cardano-client-lib's). */
    PlutusData valueData(R value) {
        return described() ? PlutusDataAdapter.toClientLib((org.julclang.core.PlutusData) value) : PlutusDataAdapter.convert(value);
    }

    PlutusData retired(byte[] instance, byte[] next, BigInteger linkedAt, byte[] key) {
        return constr(retiredTag(), List.of(BytesPlutusData.of(instance), BytesPlutusData.of(next), BigIntPlutusData.of(linkedAt),
                BytesPlutusData.of(key)));
    }

    /**
     * {@code d} with {@code next} and {@code linkedAt} replaced (relinked, D4b), in the ledger's canonical encoding: every
     * copied field is re-encoded (non-empty lists indefinite), whatever encoding the node's writer chose, except that a
     * map is written in canonical key order with one entry per key ({@link #requireRelinkable} refuses those). The Data is the
     * same (the validator compares decoded fields), and a page then needs to accept only the canonical bytes, from which it
     * computes the relink's allowance (ADR-0016 M3 review: F94, R-F11, R-F12).
     */
    static PlutusData relinked(ConstrPlutusData d, byte[] next, BigInteger linkedAt) {
        var fields = new java.util.ArrayList<PlutusData>();
        for (PlutusData f : d.getData().getPlutusDataList()) fields.add(canonical(f));
        fields.set(1, BytesPlutusData.of(next));
        fields.set(2, BigIntPlutusData.of(linkedAt));
        return constr((int) d.getAlternative(), fields);
    }

    /**
     * Refuses to relink a list node when the relink would change its Data beyond {@code next} and {@code linkedAt}
     * (ADR-0016 M3 review, R-F17, F99): cardano-client-lib keeps one entry per key when it decodes a Plutus map and writes
     * map entries in canonical key order, so a datum holding a map with a repeated key, or with its keys in another
     * order, would be relinked to different Data, which the page and the validator refuse. Checked by relinking the
     * datum as the runtime does and comparing every other field with the original, decoded by JuLC (which keeps each
     * entry in order). Such a node cannot be an anchor or a predecessor through this runtime.
     */
    static void requireRelinkable(String inlineDatumHex, String what) {
        if (inlineDatumHex == null) return;
        byte[] bytes = com.bloxbean.cardano.client.util.HexUtil.decodeHexString(inlineDatumHex);
        var original = org.julclang.core.cbor.PlutusDataCborDecoder.decode(bytes);
        String reason = null;
        if (duplicateMapKeys(original)) {
            reason = "holds a Plutus map with a repeated key";
        } else {
            try {
                var rewritten = org.julclang.core.cbor.PlutusDataCborDecoder.decode(
                        relinked((ConstrPlutusData) PlutusData.deserialize(bytes), new byte[0], BigInteger.ZERO).serializeToBytes());
                if (!sameApartFromLink(original, rewritten)) reason = "holds a Plutus map whose keys are not in canonical order";
            } catch (Exception e) {
                reason = "cannot be re-encoded (" + e.getMessage() + ")";
            }
        }
        if (reason != null) {
            throw new org.julclang.state.api.RequireException(what + ": the neighbouring node's datum " + reason + "; relinking"
                    + " it would change its Data, so it cannot be relinked here (ADR-0016 M3, R-F17, F99)");
        }
    }

    /** Both constructors alike in tag and every field but {@code next} (1) and {@code linkedAt} (2), compared as encoded by JuLC. */
    private static boolean sameApartFromLink(org.julclang.core.PlutusData a, org.julclang.core.PlutusData b) {
        if (!(a instanceof org.julclang.core.PlutusData.ConstrData x) || !(b instanceof org.julclang.core.PlutusData.ConstrData y)) return false;
        if (x.tag() != y.tag() || x.fields().size() != y.fields().size()) return false;
        for (int i = 0; i < x.fields().size(); i++) {
            if (i == 1 || i == 2) continue;
            if (!java.util.Arrays.equals(org.julclang.core.cbor.PlutusDataCborEncoder.encode(x.fields().get(i)),
                    org.julclang.core.cbor.PlutusDataCborEncoder.encode(y.fields().get(i)))) return false;
        }
        return true;
    }

    private static boolean duplicateMapKeys(org.julclang.core.PlutusData d) {
        if (d instanceof org.julclang.core.PlutusData.ConstrData c) {
            for (var f : c.fields()) if (duplicateMapKeys(f)) return true;
        } else if (d instanceof org.julclang.core.PlutusData.ListData l) {
            for (var x : l.items()) if (duplicateMapKeys(x)) return true;
        } else if (d instanceof org.julclang.core.PlutusData.MapData m) {
            var seen = new java.util.HashSet<String>();
            for (var e : m.entries()) {
                if (!seen.add(java.util.HexFormat.of().formatHex(org.julclang.core.cbor.PlutusDataCborEncoder.encode(e.key())))) return true;
                if (duplicateMapKeys(e.key()) || duplicateMapKeys(e.value())) return true;
            }
        }
        return false;
    }

    /** {@code d} rebuilt so that it encodes canonically: lists (and constructor fields) indefinite, maps definite. */
    static PlutusData canonical(PlutusData d) {
        if (d instanceof ConstrPlutusData c) {
            var fs = new java.util.ArrayList<PlutusData>();
            for (PlutusData f : c.getData().getPlutusDataList()) fs.add(canonical(f));
            return constr((int) c.getAlternative(), fs);
        }
        if (d instanceof ListPlutusData l) {
            var items = new java.util.ArrayList<PlutusData>();
            for (PlutusData x : l.getPlutusDataList()) items.add(canonical(x));
            return new ListPlutusData(items, true);
        }
        if (d instanceof com.bloxbean.cardano.client.plutus.spec.MapPlutusData m) {
            var out = new java.util.LinkedHashMap<PlutusData, PlutusData>();
            for (var e : m.getMap().entrySet()) out.put(canonical(e.getKey()), canonical(e.getValue()));
            return com.bloxbean.cardano.client.plutus.spec.MapPlutusData.builder().map(out).build();
        }
        if (d instanceof BytesPlutusData b) return BytesPlutusData.of(b.getValue());
        if (d instanceof BigIntPlutusData i) return BigIntPlutusData.of(i.getValue());
        return d;
    }

    static PlutusData constr(int tag, List<PlutusData> fields) {
        return ConstrPlutusData.builder().alternative(tag).data(ListPlutusData.builder().plutusDataList(new java.util.ArrayList<>(fields))
                .build()).build();
    }

    /**
     * A decoded list datum of this type: a head, node or retired node, with its instance, next, linkedAt, key and (for a
     * node) value; null if {@code d} is not one of this type's constructors.
     */
    Node<R> decode(PlutusData d) {
        if (!(d instanceof ConstrPlutusData c)) return null;
        long tag = c.getAlternative();
        if (tag != headTag && tag != nodeTag() && tag != retiredTag()) return null;
        var fs = c.getData().getPlutusDataList();
        try {
            byte[] instance = ((BytesPlutusData) fs.get(0)).getValue();
            byte[] next = ((BytesPlutusData) fs.get(1)).getValue();
            BigInteger linkedAt = ((BigIntPlutusData) fs.get(2)).getValue();
            if (tag == headTag) return new Node<>(Node.Kind.HEAD, instance, next, linkedAt, new byte[0], null, c);
            if (tag == retiredTag()) {
                return new Node<>(Node.Kind.RETIRED, instance, next, linkedAt, ((BytesPlutusData) fs.get(3)).getValue(), null, c);
            }
            var v = (ConstrPlutusData) fs.get(3);
            byte[] key = ((BytesPlutusData) v.getData().getPlutusDataList().get(keyIndex)).getValue();
            @SuppressWarnings("unchecked")
            R value = described() ? (R) PlutusDataAdapter.fromClientLib(v) : PlutusDataAdapter.convert(v, valueClass);
            return new Node<>(Node.Kind.LIVE, instance, next, linkedAt, key, value, c);
        } catch (RuntimeException e) {
            return null;   // the right tag, but not this type's shape: not one of ours
        }
    }

    /** A list datum, decoded. */
    record Node<R>(Kind kind, byte[] instance, byte[] next, BigInteger linkedAt, byte[] key, R value, ConstrPlutusData datum) {
        enum Kind { HEAD, LIVE, RETIRED }
    }
}
