package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Designated inputs (ADR-0016 D7) and how every transaction of this JVM selects wallet UTxOs around them. All selection
 * goes through here: coin and collateral selection ({@link #builder}), seeds and funding picks ({@link #isDesignated}).
 * <ul>
 *   <li>A designated input is reserved for its operation: no other transaction of this JVM selects it. The exemption
 *       belongs to the operation (its {@link Designated}, passed explicitly), never to a thread.</li>
 *   <li>A transaction's own inputs are claimed from its build until it is resolved, so an operation does not designate
 *       an input another transaction has already selected. Building and designating for one address are serialised
 *       (a lock per address), so neither can slip between the other's read and claim.</li>
 *   <li>The scope is one JVM: separate processes sharing a wallet must use separate UTxOs (or wallets).</li>
 * </ul>
 */
final class Designation {

    private Designation() {}

    /** Designated inputs ({@code txHash#index}) of this JVM's keyed operations. */
    private static final Set<String> DESIGNATED = ConcurrentHashMap.newKeySet();
    /** Designated inputs kept after their operation ended pending, until this wall-clock time (ms). */
    private static final Map<String, Long> LINGERING = new ConcurrentHashMap<>();
    /** Inputs of transactions built and not yet resolved, with the number of such transactions. */
    private static final Map<String, Integer> CLAIMED = new ConcurrentHashMap<>();
    /** Claims kept after their transaction ended pending, until this wall-clock time (ms). */
    private static final Map<List<String>, Long> LINGERING_CLAIMS = new ConcurrentHashMap<>();
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    /** One attempt handed to the relays: its hash and its window's end (exclusive). */
    record Attempt(String txHash, long toSlot) {}

    /**
     * One ordinary wallet UTxO, spent by every attempt of one logical operation (a keyed call, or a composition with
     * one), the first included: any two attempts conflict, so at most one of the series can land, whatever relays and
     * providers claim. It is never replaced; while it cannot be spent, nothing is rebuilt.
     */
    static final class Designated {
        private final Utxo utxo;
        private final String address;
        private final List<Attempt> attempts = new ArrayList<>();
        /** The script inputs the latest attempt spends (to tell a deterministic failure from contention). */
        private List<Utxo> scriptInputs = List.of();
        private boolean released;

        Designated(Utxo utxo, String address) {
            this.utxo = utxo;
            this.address = address;
        }

        Utxo utxo() { return utxo; }

        String address() { return address; }

        String ref() {
            return Designation.ref(utxo);
        }

        /** Records an attempt about to be handed out (any relay may deliver it, whatever it answers). */
        synchronized void handedOut(String txHash, long toSlot) {
            attempts.add(new Attempt(txHash, toSlot));
        }

        synchronized List<Attempt> attempts() {
            return List.copyOf(attempts);
        }

        synchronized List<String> attemptHashes() {
            return attempts.stream().map(Attempt::txHash).toList();
        }

        /** The latest end of the attempts' windows, or {@code Long.MIN_VALUE} if none was handed out. */
        synchronized long lastToSlot() {
            return attempts.stream().mapToLong(Attempt::toSlot).max().orElse(Long.MIN_VALUE);
        }

        void noteScriptInputs(List<Utxo> inputs) {
            this.scriptInputs = List.copyOf(inputs);
        }

        List<Utxo> scriptInputs() {
            return scriptInputs;
        }

        /** Still unspent, as far as the provider shows; an unreadable answer counts as spent (nothing is rebuilt). */
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
                return false;
            }
            return false;
        }

        /** Ends the reservation now. */
        void release() {
            if (released) return;
            released = true;
            DESIGNATED.remove(ref());
        }

        /** Ends the reservation at {@code untilMs} (wall clock): an attempt may still land until then. */
        void releaseAt(long untilMs) {
            if (released) return;
            released = true;
            if (untilMs <= System.currentTimeMillis()) DESIGNATED.remove(ref());
            else LINGERING.put(ref(), untilMs);
        }
    }

    static String ref(Utxo u) {
        return u.getTxHash() + "#" + u.getOutputIndex();
    }

    static String ref(TransactionInput in) {
        return in.getTransactionId() + "#" + in.getIndex();
    }

    /** Some keyed operation of this JVM designated {@code ref} (still reserved). */
    static boolean isDesignated(String ref) {
        purge();
        return DESIGNATED.contains(ref);
    }

    private static void purge() {
        if (LINGERING.isEmpty() && LINGERING_CLAIMS.isEmpty()) return;
        long now = System.currentTimeMillis();
        LINGERING.forEach((ref, until) -> {
            if (until <= now && LINGERING.remove(ref, until)) DESIGNATED.remove(ref);
        });
        LINGERING_CLAIMS.forEach((refs, until) -> {
            if (until <= now && LINGERING_CLAIMS.remove(refs, until)) unclaim(refs);
        });
    }

    private static Object lock(String address) {
        return LOCKS.computeIfAbsent(address, a -> new Object());
    }

    /**
     * Reserves an ordinary (lovelace-only, no datum, no reference script) UTxO of {@code payer} of at least 5 ADA that
     * no other operation of this JVM holds and no unresolved transaction of it spends, and that is not in
     * {@code avoid}: the largest such, else the next, within one read.
     */
    static Designated reserve(StateRuntime rt, Account payer, String what, Set<String> avoid) {
        String address = payer.baseAddress();
        var candidates = new ArrayList<Utxo>();
        try {
            for (int page = 1; page <= 20; page++) {
                var r = rt.backend().getUtxoService().getUtxos(address, 100, page);
                if (!r.isSuccessful() || r.getValue() == null || r.getValue().isEmpty()) break;
                for (Utxo u : r.getValue()) {
                    if (u.getAmount().size() != 1 || !"lovelace".equals(u.getAmount().get(0).getUnit())
                            || u.getInlineDatum() != null || u.getDataHash() != null || u.getReferenceScriptHash() != null) {
                        continue;
                    }
                    if (u.getAmount().get(0).getQuantity().compareTo(BigInteger.valueOf(5_000_000)) < 0) continue;
                    candidates.add(u);
                }
                if (r.getValue().size() < 100) break;
            }
        } catch (Exception e) {
            throw new JoustException(what + ": cannot read the payer's UTxOs", e);
        }
        candidates.sort(Comparator.comparing((Utxo u) -> u.getAmount().get(0).getQuantity()).reversed());
        purge();
        synchronized (lock(address)) {
            for (Utxo u : candidates) {
                String ref = ref(u);
                if (avoid.contains(ref) || CLAIMED.containsKey(ref)) continue;
                if (DESIGNATED.add(ref)) return new Designated(u, address);
            }
        }
        throw new JoustException(what + ": the payer has no ordinary UTxO of at least 5 ADA free to designate (ADR-0016 D7:"
                + " each concurrent keyed operation of a wallet needs its own)");
    }

    /**
     * The QuickTx builder of every transaction of this JVM: coin and collateral selection skip every designated input
     * but {@code own}'s (the operation's own stays selectable, for collateral). A keyed operation's attempt (with
     * {@code own}) also skips the inputs other unresolved transactions of this JVM selected, so concurrent operations of
     * one wallet do not refuse each other over a fee input; other transactions select as before (a wallet with one UTxO
     * still serves two calls one after the other). Built as {@code new QuickTxBuilder(backend)} builds itself (the same
     * protocol-parameter, script and transaction suppliers); with nothing designated, the UTxO pages are the backend's
     * own lists, so the transaction is the same.
     */
    static QuickTxBuilder builder(BackendService backend, Designated own) {
        var all = new com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier(backend.getUtxoService());
        String ownRef = own == null ? null : own.ref();
        boolean skipClaimed = own != null;
        UtxoSupplier free = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page,
                                      com.bloxbean.cardano.client.api.common.OrderEnum order) {
                return free(all.getPage(address, nrOfItems, page, order), ownRef, skipClaimed);
            }

            @Override
            public java.util.Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return all.getTxOutput(txHash, outputIndex);
            }

            @Override
            public List<Utxo> getAll(String address) {
                return free(all.getAll(address), ownRef, skipClaimed);
            }

            @Override
            public boolean isUsedAddress(com.bloxbean.cardano.client.address.Address address) {
                return all.isUsedAddress(address);
            }

            @Override
            public void setSearchByAddressVkh(boolean flag) {
                all.setSearchByAddressVkh(flag);
            }
        };
        com.bloxbean.cardano.client.api.ScriptSupplier scripts = null;
        try {
            scripts = new com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier(backend.getScriptService());
        } catch (Exception ignored) {
            // as QuickTxBuilder(BackendService) does: no script supplier
        }
        return new QuickTxBuilder(free, new com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier(backend.getEpochService()),
                scripts, new com.bloxbean.cardano.client.backend.api.DefaultTransactionProcessor(backend.getTransactionService()));
    }

    /** {@code utxos} without the designated inputs of other operations: the same list when there is none. */
    static List<Utxo> free(List<Utxo> utxos, String ownRef) {
        return free(utxos, ownRef, false);
    }

    /** As {@link #free(List, String)}; with {@code skipClaimed}, also without other unresolved transactions' inputs. */
    static List<Utxo> free(List<Utxo> utxos, String ownRef, boolean skipClaimed) {
        if (utxos == null) return null;
        purge();
        if (DESIGNATED.isEmpty() && (!skipClaimed || CLAIMED.isEmpty())) return utxos;
        boolean any = false;
        for (Utxo u : utxos) {
            if (!selectable(ref(u), ownRef, skipClaimed)) {
                any = true;
                break;
            }
        }
        if (!any) return utxos;
        var out = new ArrayList<Utxo>(utxos.size());
        for (Utxo u : utxos) {
            if (selectable(ref(u), ownRef, skipClaimed)) out.add(u);
        }
        return out;
    }

    private static boolean selectable(String ref, String ownRef, boolean skipClaimed) {
        if (ref.equals(ownRef)) return true;
        return !DESIGNATED.contains(ref) && !(skipClaimed && CLAIMED.containsKey(ref));
    }

    /** A built transaction and the inputs it claims until {@link #unclaim}. */
    record Claimed(Transaction tx, List<String> refs) {}

    /**
     * Builds under {@code address}'s lock and claims the transaction's inputs (spent and collateral) before the lock is
     * released: a designation for that address made afterwards skips them, one made before was skipped by the build.
     */
    static Claimed build(String address, Supplier<Transaction> build) {
        synchronized (lock(address)) {
            Transaction t = build.get();
            var refs = new ArrayList<String>();
            for (var in : t.getBody().getInputs()) refs.add(ref(in));
            if (t.getBody().getCollateral() != null) for (var in : t.getBody().getCollateral()) refs.add(ref(in));
            for (String r : refs) CLAIMED.merge(r, 1, Integer::sum);
            return new Claimed(t, refs);
        }
    }

    /** Ends a transaction's claims (it was resolved: included or refused). */
    static void unclaim(List<String> refs) {
        for (String r : refs) CLAIMED.computeIfPresent(r, (k, n) -> n <= 1 ? null : n - 1);
    }

    /** Ends a pending transaction's claims at {@code untilMs} (wall clock), when its window has passed. */
    static void unclaimAt(List<String> refs, long untilMs) {
        if (untilMs <= System.currentTimeMillis()) unclaim(refs);
        else LINGERING_CLAIMS.put(new ArrayList<>(refs), untilMs);
    }

    /** For tests: nothing designated or claimed. */
    static boolean idle() {
        purge();
        return DESIGNATED.isEmpty() && CLAIMED.isEmpty();
    }
}
