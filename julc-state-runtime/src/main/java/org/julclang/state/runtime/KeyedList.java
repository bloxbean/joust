package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxOutRef;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.RequireException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * The list of one keyed type in one instance (ADR-0016 D7): lookup by key, the head walk, and inserts. Every result the
 * provider gives is validated (policy, token, address, constructor, instance, the key's binding: I51); "not found" means
 * the provider returned nothing, never proven absence.
 *
 * @param <S> the instance state
 * @param <R> the keyed record
 */
public final class KeyedList<S, R> {

    /** How to insert: filled in by a generated client for one {@code @OwnedInit} of the type. */
    public record Insert<R>(String name, Account caller, BigInteger received, boolean usesCtx, boolean needsShared,
                            BiFunction<Object, Ctx, R> run, Redeemer redeemer) {
        /** Builds the {@code Insert_<m>(instance, anchor, args..., [ctx])} mint redeemer. */
        @FunctionalInterface
        public interface Redeemer {
            Object make(byte[] instance, TxOutRef anchor, Ctx ctx);
        }
    }

    /** A key of the list, and whether it is retired (taken for good, D10). */
    public record Entry(byte[] key, boolean retired) {
        public String keyHex() {
            return HexUtil.encodeHexString(key);
        }
    }

    final StateInstance<S> instance;
    final KeyedType<R> type;

    public KeyedList(StateInstance<S> instance, KeyedType<R> type) {
        this.instance = instance;
        this.type = type;
    }

    public KeyedType<R> type() { return type; }
    public StateInstance<S> instance() { return instance; }

    StateContractHandle<S> contract() {
        return instance.contract();
    }

    byte[] instanceBytes() {
        return HexUtil.decodeHexString(instance.instanceId());
    }

    /** The unit of {@code key}'s token: policy ++ keyId. */
    public String unit(byte[] key) {
        return contract().policyId + HexUtil.encodeHexString(type.keyId(instanceBytes(), key));
    }

    // ------------------------------------------------------------------ reads

    /** A validated UTxO of this list and its decoded datum. */
    record Listed<R>(Utxo utxo, KeyedType.Node<R> node) {
        TxOutRef ref() {
            return new TxOutRef(new TxId(HexUtil.decodeHexString(utxo.getTxHash())), BigInteger.valueOf(utxo.getOutputIndex()));
        }

        BigInteger lovelace() {
            return utxo.getAmount().stream().filter(a -> a.getUnit().equals("lovelace")).map(Amount::getQuantity).findFirst()
                    .orElse(BigInteger.ZERO);
        }
    }

    /** {@code u} decoded and validated as a UTxO of this list (I51), or null. */
    Listed<R> validate(Utxo u) {
        var c = contract();
        if (!c.address.equals(u.getAddress()) || u.getInlineDatum() == null) return null;
        KeyedType.Node<R> n;
        try {
            n = type.decode(PlutusData.deserialize(HexUtil.decodeHexString(u.getInlineDatum())));
        } catch (Exception e) {
            return null;
        }
        if (n == null || !Arrays.equals(n.instance(), instanceBytes())) return null;
        String unit = unit(n.key());
        // Exactly its own unit of this policy, quantity 1, and nothing else.
        boolean own = false;
        for (Amount a : u.getAmount()) {
            if (a.getUnit().equals("lovelace")) continue;
            if (!a.getUnit().equalsIgnoreCase(unit) || a.getQuantity().compareTo(BigInteger.ONE) != 0) return null;
            own = true;
        }
        return own ? new Listed<>(u, n) : null;
    }

    /**
     * The UTxO holding {@code key}'s token, as the provider returned it (one query with an asset filter); or empty. A key
     * of no valid length (1 to {@code maxKeyBytes}) is refused: the head's empty key is not a key (R-D10).
     */
    Optional<Listed<R>> lookup(byte[] key) {
        if (key == null || key.length < 1 || key.length > type.maxKeyBytes()) {
            throw new IllegalArgumentException(type.name() + ": a key is 1 to " + type.maxKeyBytes() + " bytes, not "
                    + (key == null ? "null" : key.length));
        }
        var c = contract();
        try {
            var r = c.runtime.backend().getUtxoService().getUtxos(c.address, unit(key), 10, 1);
            if (r.isSuccessful() && r.getValue() != null) {
                for (Utxo u : r.getValue()) {
                    Listed<R> l = validate(u);
                    if (l != null && l.node().kind() != KeyedType.Node.Kind.HEAD && Arrays.equals(l.node().key(), key)) {
                        return Optional.of(l);
                    }
                }
            }
        } catch (Exception e) {
            throw new JoustException("cannot look up " + type.name() + " " + HexUtil.encodeHexString(key), e);
        }
        return Optional.empty();
    }

    /** The object of {@code key} (live or retired), or empty when the provider returned nothing (not proven absence). */
    public Optional<KeyedObject<S, R>> find(byte[] key) {
        return lookup(key).map(l -> new KeyedObject<>(this, key));
    }

    /** The object of {@code key}; fails when the provider returned nothing. */
    public KeyedObject<S, R> get(byte[] key) {
        return find(key).orElseThrow(() -> new InstanceNotFoundException(contract().spec.name() + " " + type.name() + " "
                + HexUtil.encodeHexString(key) + " not found (the provider returned nothing)"));
    }

    /** The key is taken: live or retired (D7). */
    public boolean exists(byte[] key) {
        return lookup(key).isPresent();
    }

    /**
     * Every UTxO of this list at the state address, by key (hex; the head's is ""): keyIds are hashes, so the whole
     * address is listed and decoded (D7 step 3). A key held twice is an inconsistent snapshot.
     */
    TreeMap<String, Listed<R>> listing() {
        var c = contract();
        var out = new TreeMap<String, Listed<R>>();
        try {
            for (int page = 1; page <= 1_000; page++) {
                var r = c.runtime.backend().getUtxoService().getUtxos(c.address, 100, page);
                if (!r.isSuccessful() || r.getValue() == null || r.getValue().isEmpty()) break;
                for (Utxo u : r.getValue()) {
                    Listed<R> l = validate(u);
                    if (l == null) continue;
                    if (out.put(HexUtil.encodeHexString(l.node().key()), l) != null) {
                        throw new JoustException(type.name() + ": inconsistent snapshot, key "
                                + HexUtil.encodeHexString(l.node().key()) + " held twice");
                    }
                }
                if (r.getValue().size() < 100) break;
            }
        } catch (JoustException e) {
            throw e;
        } catch (Exception e) {
            throw new JoustException("cannot list " + type.name() + " objects", e);
        }
        if (!out.containsKey("")) throw new JoustException(type.name() + ": the list head of this instance is missing");
        return out;
    }

    /**
     * Every key, in list order, by walking from the head (D7). A missing link, a key that does not increase, or a node
     * the walk does not reach is reported as an inconsistent snapshot: none can exist on chain (I47).
     */
    public List<Entry> keys() {
        var all = listing();
        var out = new ArrayList<Entry>();
        String at = "";
        int seen = 1;
        while (true) {
            String next = HexUtil.encodeHexString(all.get(at).node().next());
            if (next.isEmpty()) break;
            Listed<R> n = all.get(next);
            if (n == null) throw new JoustException(type.name() + ": inconsistent snapshot, the walk misses " + next);
            if (next.compareTo(at) <= 0) throw new JoustException(type.name() + ": inconsistent snapshot, keys do not increase");
            out.add(new Entry(n.node().key(), n.node().kind() == KeyedType.Node.Kind.RETIRED));
            seen++;
            at = next;
        }
        if (seen != all.size()) throw new JoustException(type.name() + ": inconsistent snapshot, " + (all.size() - seen) + " nodes off the walk");
        return out;
    }

    /** The node a new {@code key} goes after: the greatest key below it (the head at least). */
    static <R> Listed<R> predecessor(TreeMap<String, Listed<R>> all, String key) {
        var e = all.lowerEntry(key);
        return e == null ? all.get("") : e.getValue();
    }

    // ------------------------------------------------------------------ insert (D4, D7)

    /** Inserts the value {@code spec} makes; refused on the JVM if its key is taken. */
    public KeyedObject<S, R> insert(Insert<R> spec) {
        return insert(spec, true);
    }

    /** As {@link #insert(Insert)}; {@code checkTaken = false} skips the JVM's duplicate check (tests force it on chain). */
    KeyedObject<S, R> insert(Insert<R> spec, boolean checkTaken) {
        var c = contract();
        var rt = c.runtime;
        String what = c.spec.name() + "." + spec.name();
        c.ensureReferenceScript(spec.caller());
        byte[][] made = new byte[1][];
        KeyedObjects.operation(rt, spec.caller(), what, d -> {
            made[0] = attemptInsert(spec, d, checkTaken, what);
            return made[0];
        });
        return new KeyedObject<>(this, made[0]);
    }

    private byte[] attemptInsert(Insert<R> spec, Designation.Designated d, boolean checkTaken, String what) {
        var c = contract();
        var rt = c.runtime;
        ChainTime time = rt.chainTime();
        var cap = TxRunner.capture();
        byte[] callerPkh = Keys.pkh(spec.caller());
        byte[] inst = instanceBytes();
        long minStart = Long.MIN_VALUE;
        for (int round = 0; round < 8; round++) {
            long tip = time.tipSlot();
            Window w;
            if (cap != null && cap.forced != null) {
                w = cap.forced;
            } else {
                var p = KeyedObjects.plan(time, tip, minStart, null, 0, type.windowMs(), c.spec.maxValidityMs(), rt.ttlMs(), rt.pageLimits());
                w = KeyedObjects.window(time, p.fromSlot(), p.toSlot());
            }
            Ctx ctx = new Ctx(callerPkh, spec.received(), BigInteger.ZERO, w.fromMs(), w.toMs());
            Object shared = spec.needsShared() ? instance.state() : null;
            R value = spec.run().apply(shared, ctx);   // the @OwnedInit, on the JVM, within the window (D7 step 2)
            byte[] key = type.keyOf(value);
            String keyHex = HexUtil.encodeHexString(key);
            if (key.length < 1 || key.length > type.maxKeyBytes()) {
                throw new RequireException(what + ": the key must be 1 to " + type.maxKeyBytes() + " bytes, not " + key.length);
            }
            if (type.signedKey() && !Arrays.equals(key, callerPkh)) {
                throw new RequireException(what + ": the key must be the caller's key hash (signedKey, I53)");
            }
            var all = listing();
            Listed<R> anchor = predecessor(all, keyHex);
            String after = HexUtil.encodeHexString(anchor.node().next());
            if (checkTaken && (all.containsKey(keyHex) || after.equals(keyHex))) {
                boolean retired = all.containsKey(keyHex) && all.get(keyHex).node().kind() == KeyedType.Node.Kind.RETIRED;
                throw new RequireException(what + ": key " + keyHex + " is already taken" + (retired ? " (retired, for good)" : ""));
            }
            // D7 step 4: wait for the anchor's cooldown by moving the window, and start again (the key may change).
            long ready = type.cooldownMs() > 0 ? KeyedObjects.firstSlotAtOrAfter(time,
                    anchor.node().linkedAt().add(BigInteger.valueOf(type.cooldownMs()))) : Long.MIN_VALUE;
            if (ready > w.fromSlot()) {
                if (cap != null && cap.forced != null) {
                    // The first pass saw another anchor, or this one before it was relinked: rebuild (R-C16).
                    throw new SubmitException(SubmitException.Kind.TRANSIENT, what + ": the composition's window starts before"
                            + " the anchor's cooldown ends (slot " + ready + "); the anchor moved since the first pass");
                }
                minStart = ready;
                continue;
            }
            if (w.toMs().subtract(w.fromMs()).compareTo(BigInteger.valueOf(type.windowMs())) > 0) {
                throw new JoustException(what + ": the window is longer than linkWindowMs " + type.windowMs()
                        + "; the composition's calls' windows cannot fit a Link window (ADR-0016 D7)");
            }
            long[] page = rt.pageLimits();
            if (page != null && cap != null && cap.walletOnly && cap.forced == null && w.fromSlot() > tip + page[0]) {
                throw KeyedObjects.notYet(what, w.fromSlot() - page[0]);   // a wallet's prepare cannot wait (D11)
            }
            if (page != null && cap == null && w.fromSlot() > tip + page[0]) {
                // D7 step 5: prepare only once the start is within the page's limit.
                TxRunner.sleep(Math.max(500, (w.fromSlot() - tip - page[0]) * time.slotLengthMs()));
                continue;
            }
            if (cap != null && cap.forced == null) cap.windows.add(w);
            build(spec, d, value, key, anchor, w, ctx, shared != null, what);
            return key;
        }
        throw new ContentionException(what + ": the anchor kept moving while waiting for its cooldown", null);
    }

    private void build(Insert<R> spec, Designation.Designated d, R value, byte[] key, Listed<R> anchor, Window w, Ctx ctx,
                       boolean shared, String what) {
        var c = contract();
        var rt = c.runtime;
        byte[] inst = instanceBytes();
        byte[] callerPkh = Keys.pkh(spec.caller());
        PlutusData redeemer = PlutusDataAdapter.convert(spec.redeemer().make(inst, anchor.ref(), ctx));
        var fields = ((ConstrPlutusData) redeemer).getData().getPlutusDataList();
        int argBytes = KeyedObjects.argumentBytes(fields.subList(2, fields.size() - (spec.usesCtx() ? 1 : 0)));
        if (argBytes > KeyedType.MAX_ARGUMENT_BYTES) {
            throw new RequireException(what + ": the arguments take " + argBytes + " serialised bytes; a keyed initializer allows"
                    + " at most " + KeyedType.MAX_ARGUMENT_BYTES + " (ADR-0016 D4c)");
        }
        // The size rule (I13 amended): next padded to maxKeyBytes, linkedAt at 9 bytes.
        PlutusData envelope = type.node(inst, new byte[type.maxKeyBytes()], BigInteger.TWO.pow(63), value);
        if (KeyedObjects.datumBytes(envelope) > c.spec.maxDatumBytes()) {
            throw new RequireException(what + ": the object's datum, with next padded to " + type.maxKeyBytes() + " bytes, exceeds"
                    + " maxDatumBytes = " + c.spec.maxDatumBytes() + " (ADR-0016 D2)");
        }
        Utxo ref = c.findReferenceScript();
        var tx = TxRunner.newTx();
        if (d != null) TxRunner.collect(tx, d.utxo(), null);
        TxRunner.collect(tx, anchor.utxo(), PlutusDataAdapter.convert(type.link()));
        tx.readFrom(ref);
        if (shared) tx.readFrom(instance.utxo());
        byte[] id = type.keyId(inst, key);
        String nodeUnit = c.policyId + HexUtil.encodeHexString(id);
        TxRunner.mint(tx, c.policyId, List.of(new Asset("0x" + HexUtil.encodeHexString(id), BigInteger.ONE)), redeemer);
        // The relinked anchor: next := key, linkedAt := upper; topped up to the ledger minimum if needed (D4b).
        String anchorUnit = unit(anchor.node().key());
        PlutusData relinked = KeyedType.relinked(anchor.node().datum(), key, w.toMs());
        BigInteger anchorLovelace = KeyedObjects.topUp(rt, anchor.lovelace(), c.minUtxoFor(c.address, relinked,
                List.of(Amount.lovelace(anchor.lovelace()), new Amount(anchorUnit, BigInteger.ONE))), what, "the relinked anchor");
        tx.payToContract(c.address, List.of(Amount.lovelace(anchorLovelace), new Amount(anchorUnit, BigInteger.ONE)), relinked);
        PlutusData node = type.node(inst, anchor.node().next(), w.toMs(), value);
        BigInteger nodeLovelace = BigInteger.valueOf(c.spec.minAda()).add(spec.received());
        tx.payToContract(c.address, List.of(Amount.lovelace(nodeLovelace), new Amount(nodeUnit, BigInteger.ONE)), node);
        var signers = new ArrayList<byte[]>();
        if (spec.usesCtx() || type.signedKey()) signers.add(callerPkh);
        var runner = new TxRunner(rt);
        runner.extraExpected = List.of(new TxRunner.ExpectedState(anchorUnit, anchorLovelace, relinked));
        runner.designated = d;
        String txHash = runner.submit(tx, spec.caller(), w, signers.toArray(new byte[0][]),
                new TxRunner.ExpectedState(nodeUnit, nodeLovelace, node), List.of(), new PlutusScript[]{c.script}, what);
        if (TxRunner.capture() == null) awaitHeld(key, txHash);
    }

    /** Waits until the provider shows {@code key}'s UTxO created by {@code txHash}. */
    void awaitHeld(byte[] key, String txHash) {
        for (int i = 0; i < 60; i++) {
            var l = lookup(key);
            if (l.isPresent() && l.get().utxo().getTxHash().equals(txHash)) return;
            TxRunner.sleepOrPending(500, txHash, "the " + type.name() + " " + HexUtil.encodeHexString(key));
        }
        throw new PendingTransactionException(txHash, "included, but the " + type.name() + " is not visible yet");
    }
}
