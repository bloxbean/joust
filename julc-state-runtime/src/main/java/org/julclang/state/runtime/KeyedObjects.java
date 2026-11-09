package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** What keyed lists and objects share (ADR-0016 D7): the designated input, link windows, argument sizes, retries. */
final class KeyedObjects {

    private KeyedObjects() {}

    // ------------------------------------------------------------------ the designated input (D7)

    /** Ordinary wallet UTxOs reserved by an operation in this JVM: two operations never share one. */
    private static final Set<String> RESERVED = ConcurrentHashMap.newKeySet();
    /**
     * The designated input of the innermost operation running on this thread (reserved and released on it), if any. An
     * operation started inside another (a relay or callback calling the runtime) restores the outer one's on release.
     */
    private static final ThreadLocal<String> OWN = new ThreadLocal<>();

    /**
     * One ordinary wallet UTxO, spent by every attempt of one logical operation (a keyed call or a composition with
     * one), the first included: any two attempts conflict, so at most one of the series can land, whatever relays and
     * providers claim. It is never replaced; while it cannot be spent, nothing is rebuilt.
     */
    record Designated(Utxo utxo, String address, String outer) {
        String ref() {
            return utxo.getTxHash() + "#" + utxo.getOutputIndex();
        }

        /** Still unspent, as far as the provider shows. */
        boolean unspent(StateRuntime rt) {
            try {
                for (int page = 1; page <= 20; page++) {
                    var r = rt.backend().getUtxoService().getUtxos(address, 100, page);
                    if (!r.isSuccessful() || r.getValue() == null || r.getValue().isEmpty()) return false;
                    for (Utxo u : r.getValue()) {
                        if (u.getTxHash().equals(utxo.getTxHash()) && u.getOutputIndex() == utxo.getOutputIndex()) return true;
                    }
                    if (r.getValue().size() < 100) return false;
                }
            } catch (Exception e) {
                return false;   // unknown: treat as spent, so nothing is rebuilt
            }
            return false;
        }

        void release() {
            RESERVED.remove(ref());
            if (outer == null) OWN.remove();
            else OWN.set(outer);
        }
    }

    /**
     * The QuickTx builder for a transaction of this JVM: while keyed operations hold designated inputs, coin and
     * collateral selection skip those of other operations (this thread's own stays selectable, for collateral), so one
     * operation never spends another's, which would make that one's retry rule (D7) read a false "spent". With no other
     * reservation, the plain builder.
     */
    static com.bloxbean.cardano.client.quicktx.QuickTxBuilder builder(com.bloxbean.cardano.client.backend.api.BackendService backend) {
        String own = OWN.get();
        if (RESERVED.isEmpty() || (own != null && RESERVED.size() == 1 && RESERVED.contains(own))) {
            return new com.bloxbean.cardano.client.quicktx.QuickTxBuilder(backend);
        }
        var all = new com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier(backend.getUtxoService());
        return new com.bloxbean.cardano.client.quicktx.QuickTxBuilder(backend, new com.bloxbean.cardano.client.api.UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page,
                                      com.bloxbean.cardano.client.api.common.OrderEnum order) {
                return free(all.getPage(address, nrOfItems, page, order), own);
            }

            @Override
            public java.util.Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return all.getTxOutput(txHash, outputIndex);
            }

            @Override
            public List<Utxo> getAll(String address) {
                return free(all.getAll(address), own);
            }

            @Override
            public boolean isUsedAddress(com.bloxbean.cardano.client.address.Address address) {
                return all.isUsedAddress(address);
            }

            @Override
            public void setSearchByAddressVkh(boolean flag) {
                all.setSearchByAddressVkh(flag);
            }
        });
    }

    private static List<Utxo> free(List<Utxo> utxos, String own) {
        if (utxos == null) return null;
        return utxos.stream().filter(u -> {
            String ref = u.getTxHash() + "#" + u.getOutputIndex();
            return ref.equals(own) || !RESERVED.contains(ref);
        }).toList();
    }

    /**
     * Reserves an ordinary (lovelace-only, no datum, no reference script) UTxO of {@code payer} of at least 5 ADA that no
     * other operation of this JVM holds; the largest such.
     */
    static Designated reserve(StateRuntime rt, Account payer, String what) {
        String address = payer.baseAddress();
        for (int tries = 0; tries < 3; tries++) {
            Utxo best = null;
            try {
                for (int page = 1; page <= 20; page++) {
                    var r = rt.backend().getUtxoService().getUtxos(address, 100, page);
                    if (!r.isSuccessful() || r.getValue() == null || r.getValue().isEmpty()) break;
                    for (Utxo u : r.getValue()) {
                        if (u.getAmount().size() != 1 || u.getInlineDatum() != null || u.getDataHash() != null
                                || u.getReferenceScriptHash() != null) continue;
                        BigInteger q = u.getAmount().get(0).getQuantity();
                        if (q.compareTo(BigInteger.valueOf(5_000_000)) < 0 || RESERVED.contains(u.getTxHash() + "#" + u.getOutputIndex())) continue;
                        if (best == null || q.compareTo(best.getAmount().get(0).getQuantity()) > 0) best = u;
                    }
                    if (r.getValue().size() < 100) break;
                }
            } catch (Exception e) {
                throw new JoustException(what + ": cannot read the payer's UTxOs", e);
            }
            if (best != null && RESERVED.add(best.getTxHash() + "#" + best.getOutputIndex())) {
                String outer = OWN.get();
                OWN.set(best.getTxHash() + "#" + best.getOutputIndex());
                return new Designated(best, address, outer);
            }
        }
        throw new JoustException(what + ": the payer has no ordinary UTxO of at least 5 ADA free to designate (ADR-0016 D7:"
                + " each concurrent keyed operation of a wallet needs its own)");
    }

    /**
     * Runs a keyed operation (D7): designates an input, then attempts; after a definite rejection it rebuilds the whole
     * call (window, JVM result, anchor), but only while the designated input can still be spent; an attempt whose fate is
     * unknown is never rebuilt ({@link PendingTransactionException} propagates). Inside a composition, the composition
     * designates and retries.
     */
    static <T> T operation(StateRuntime rt, Account payer, String what, Function<Designated, T> attempt) {
        var cap = TxRunner.capture();
        if (cap != null) {
            cap.keyed = true;
            return attempt.apply(null);
        }
        Designated d = reserve(rt, payer, what);
        try {
            SubmitException last = null;
            for (int i = 0; i <= rt.maxRetries(); i++) {
                if (i > 0 && !d.unspent(rt)) {
                    throw new PendingTransactionException(d.ref(), what + ": the designated input is spent, so an earlier attempt"
                            + " may have landed; not rebuilding (ADR-0016 D7)");
                }
                try {
                    return attempt.apply(d);
                } catch (SubmitException e) {
                    last = e;
                    rt.log(what + ": attempt " + (i + 1) + " rejected (" + e.getMessage() + "); rebuilding with the same designated"
                            + " input " + d.ref());
                    TxRunner.sleep(2000);   // let the provider's index catch up with whatever won (a neighbour's relink)
                }
            }
            throw new ContentionException(what + ": rejected " + (rt.maxRetries() + 1) + " times", last);
        } finally {
            d.release();
        }
    }

    // ------------------------------------------------------------------ link windows (D7, D9)

    /**
     * A relink's window, in slots: it starts at {@code minStart} (at least the tip), and no earlier than the first slot at
     * or after {@code linkedAt + C} when {@code C > 0} (rounded up, never down); it is at most {@code W}, the contract's
     * {@code maxValidityMs} and the runtime's TTL long. With page limits, its start is at most {@code maxStartSlots} and
     * its end at most {@code maxValiditySlots} after the tip: {@code prepareAt} is the first tip at which it may be
     * prepared (the start being then near enough), and the window is shortened to the end limit.
     */
    record LinkPlan(long fromSlot, long toSlot, long prepareAt) {}

    static LinkPlan plan(ChainTime time, long tip, long minStart, BigInteger linkedAt, long cooldownMs, long windowMs,
                         long maxValidityMs, long ttlMs, long[] pageLimits) {
        long slot = time.slotLengthMs();
        long from = Math.max(tip, minStart);
        if (cooldownMs > 0 && linkedAt != null) from = Math.max(from, firstSlotAtOrAfter(time, linkedAt.add(BigInteger.valueOf(cooldownMs))));
        long lengthMs = Math.min(windowMs, Math.min(maxValidityMs, ttlMs));
        long slots = lengthMs / slot;
        if (slots < 1) throw new JoustException("a link window of " + lengthMs + " ms is shorter than one " + slot + " ms slot");
        long to = from + slots;
        long prepareAt = tip;
        if (pageLimits != null) {
            prepareAt = Math.max(tip, from - pageLimits[0]);
            to = Math.min(to, prepareAt + pageLimits[1]);
            if (to <= from) throw new JoustException("the page's window limits leave no room for a link window");
        }
        return new LinkPlan(from, to, prepareAt);
    }

    /** The first slot whose time is at least {@code ms}. */
    static long firstSlotAtOrAfter(ChainTime time, BigInteger ms) {
        long s = time.posixMsToSlotFloor(ms.longValueExact());
        return time.slotToPosixMs(s) < ms.longValueExact() ? s + 1 : s;
    }

    static Window window(ChainTime time, long from, long to) {
        return new Window(from, to, BigInteger.valueOf(time.slotToPosixMs(from)), BigInteger.valueOf(time.slotToPosixMs(to)));
    }

    // ------------------------------------------------------------------ sizes (D2, D4c)

    /** The serialised size of {@code args} as one Plutus list. */
    static int argumentBytes(List<PlutusData> args) {
        try {
            var list = ListPlutusData.builder().plutusDataList(new java.util.ArrayList<>(args)).build();
            return com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(list.serialize()).length;
        } catch (Exception e) {
            throw new JoustException("cannot serialise arguments", e);
        }
    }

    /** The datum's serialised size, as {@code datumFits} measures it on chain (Plutus {@code serialiseData}). */
    static int datumBytes(PlutusData d) {
        try {
            return org.julclang.core.cbor.PlutusDataCborEncoder.encode(PlutusDataAdapter.fromClientLib(d)).length;
        } catch (Exception e) {
            throw new JoustException("cannot serialise a datum", e);
        }
    }

    static String hex(byte[] b) {
        return HexUtil.encodeHexString(b);
    }

    private static final class PlutusDataAdapter {
        static org.julclang.core.PlutusData fromClientLib(PlutusData d) {
            return org.julclang.clientlib.PlutusDataAdapter.fromClientLib(d);
        }
    }
}
