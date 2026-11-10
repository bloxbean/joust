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
 * goes through here: coin and collateral selection ({@link #builder}), seeds and funding picks ({@link #pick}).
 * <ul>
 *   <li>A designated input is reserved for its operation: no other transaction of this JVM selects it. The exemption
 *       belongs to the operation (its {@link Designated}, passed explicitly), never to a thread.</li>
 *   <li>Claims keep an operation from designating what another transaction holds: an explicit pick until its build,
 *       a built transaction's inputs until it is resolved, a keyed attempt's inputs (once handed out) until its series
 *       ends, a prepared transaction's until its TTL. Picking, building and designating for one address are serialised
 *       (a lock per address), so none can slip between another's read and claim. Plain transactions do not skip
 *       claimed inputs (they select as before M2 while nothing is designated); keyed attempts skip other claims.</li>
 *   <li>The scope is one JVM: separate processes sharing a wallet must use separate UTxOs (or wallets).</li>
 * </ul>
 */
final class Designation {

    private Designation() {}

    /** Designated inputs ({@code txHash#index}) of this JVM's keyed operations. */
    private static final Set<String> DESIGNATED = ConcurrentHashMap.newKeySet();
    /** Designated inputs kept after their operation ended pending, until this wall-clock time (ms). */
    private static final Map<String, Long> LINGERING = new ConcurrentHashMap<>();
    /** Claims of transactions built (or inputs picked) and not yet resolved; each claim is its own identity. */
    private static final Set<Claim> CLAIMS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    /**
     * Inputs held by one transaction, or by every handed-out attempt of one keyed operation, until resolved. Claims are
     * compared by identity, so two claims of the same inputs are released separately (F66, R-E3). {@code owner} is the
     * keyed operation ({@link Designated}) whose attempts may reuse these inputs, or the claim itself.
     */
    static final class Claim {
        final Object owner;
        final Set<String> refs = ConcurrentHashMap.newKeySet();
        /** Released at this wall-clock time (ms); {@code Long.MAX_VALUE}: until released explicitly. */
        volatile long until = Long.MAX_VALUE;

        Claim(Object owner) {
            this.owner = owner == null ? this : owner;
        }

        /** Ends the claim now. */
        void release() {
            CLAIMS.remove(this);
        }

        /** Ends the claim at {@code untilMs} (wall clock). */
        void releaseAt(long untilMs) {
            if (untilMs <= System.currentTimeMillis()) CLAIMS.remove(this);
            else until = untilMs;
        }
    }

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
        /** The inputs of every handed-out attempt (F64): held until the series ends, then released or kept pending. */
        private final Claim held = new Claim(this);
        /**
         * What the latest attempt depends on: the script inputs it spends and the reference inputs it reads (to tell a
         * deterministic failure from contention; F68).
         */
        private List<Utxo> dependencies = List.of();
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

        /**
         * Records an attempt about to be handed out (any relay may deliver it, whatever it answers). Its window must be
         * finite: an attempt that never expires could never be settled (F65, R-E2).
         */
        synchronized void handedOut(String txHash, long toSlot) {
            if (toSlot == Long.MAX_VALUE || toSlot == Long.MIN_VALUE) {
                throw new IllegalArgumentException(txHash + ": an attempt with a designated input needs a finite window");
            }
            attempts.add(new Attempt(txHash, toSlot));
        }

        /** Keeps a handed-out attempt's inputs claimed for this operation until the series ends (F64). */
        void hold(java.util.Collection<String> refs) {
            held.refs.addAll(refs);
            CLAIMS.add(held);
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

        /** The script inputs the latest attempt spends and the reference inputs it reads (F68). */
        void noteInputs(List<Utxo> spent, List<Utxo> read) {
            var all = new ArrayList<Utxo>(spent);
            all.addAll(read);
            this.dependencies = List.copyOf(all);
        }

        List<Utxo> dependencies() {
            return dependencies;
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

        /** Ends the reservation, and the claims of its attempts' inputs, now. */
        void release() {
            if (released) return;
            released = true;
            DESIGNATED.remove(ref());
            held.release();
        }

        /** Ends the reservation and its attempts' claims at {@code untilMs} (wall clock): an attempt may land until then. */
        void releaseAt(long untilMs) {
            if (released) return;
            released = true;
            if (untilMs <= System.currentTimeMillis()) DESIGNATED.remove(ref());
            else LINGERING.put(ref(), untilMs);
            held.releaseAt(untilMs);
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
        long now = System.currentTimeMillis();
        if (!LINGERING.isEmpty()) {
            LINGERING.forEach((ref, until) -> {
                if (until <= now && LINGERING.remove(ref, until)) DESIGNATED.remove(ref);
            });
        }
        CLAIMS.removeIf(c -> c.until <= now);
    }

    /** Some unresolved transaction of this JVM claims {@code ref}. */
    static boolean isClaimed(String ref) {
        purge();
        for (Claim c : CLAIMS) if (c.refs.contains(ref)) return true;
        return false;
    }

    /** A claim on {@code ref} by anyone but {@code owner}. */
    private static boolean claimedByOther(String ref, Object owner) {
        for (Claim c : CLAIMS) if (c.owner != owner && c.refs.contains(ref)) return true;
        return false;
    }

    private static Object lock(String address) {
        return LOCKS.computeIfAbsent(address, a -> new Object());
    }

    /** The payer address whose lock this thread holds, if any. */
    private static final ThreadLocal<String> HELD = new ThreadLocal<>();

    /**
     * Runs {@code body} under {@code address}'s lock. Reentrant for the same address; a request for another payer's
     * lock while this thread holds one (an evaluator or callback building for a second payer) is refused before it
     * could block, so two payers' builds can never wait on each other (F73).
     */
    static <T> T locked(String address, Supplier<T> body) {
        String held = HELD.get();
        if (held != null && !held.equals(address)) {
            throw new JoustException("a transaction for " + address + " was requested while this thread builds one for "
                    + held + "; nested builds for another payer are refused (they could deadlock; ADR-0016 D7)");
        }
        synchronized (lock(address)) {
            if (held != null) return body.get();
            HELD.set(address);
            try {
                return body.get();
            } finally {
                HELD.remove();
            }
        }
    }

    /** {@code ref} is held by a keyed operation other than {@code own} (its handed-out attempts' inputs; F72). */
    static boolean heldByAnotherOperation(String ref, Designated own) {
        purge();
        for (Claim c : CLAIMS) {
            if (c.owner instanceof Designated d && d != own && c.refs.contains(ref)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ explicit picks (F63, F71, F72)

    /** The picks made on this thread inside the outermost open scope. */
    private static final ThreadLocal<List<Claim>> SCOPE = new ThreadLocal<>();

    /**
     * A scope for explicit picks: the outermost scope on a thread (an entry point such as a deployment, or a whole
     * composition) owns every pick made inside it and releases them when it closes, after the transaction spending them
     * was built and claimed its own inputs, or when nothing was built. Inner scopes are no-ops.
     */
    static final class Scope implements AutoCloseable {
        private final boolean outer;

        private Scope(boolean outer) {
            this.outer = outer;
        }

        @Override
        public void close() {
            if (!outer) return;
            List<Claim> picks = SCOPE.get();
            SCOPE.remove();
            if (picks != null) picks.forEach(Claim::release);
        }
    }

    static Scope scope() {
        if (SCOPE.get() != null) return new Scope(false);
        SCOPE.set(new ArrayList<>());
        return new Scope(true);
    }

    /**
     * Reserves an ordinary (lovelace-only, no datum, no reference script) UTxO of {@code payer} of at least 5 ADA that
     * no other operation of this JVM holds and no unresolved transaction of it spends, and that is not in
     * {@code avoid}: the largest such, else the next, within one read.
     */
    static Designated reserve(StateRuntime rt, Account payer, String what, Set<String> avoid) {
        String address = payer.baseAddress();
        purge();
        // Read and chosen under the address's lock (F63): no build of that address can spend and release a candidate
        // between the read and the designation.
        Designated chosen = locked(address, () -> {
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
            for (Utxo u : candidates) {
                String ref = ref(u);
                if (avoid.contains(ref) || isClaimed(ref)) continue;
                if (DESIGNATED.add(ref)) return new Designated(u, address);
            }
            return null;
        });
        if (chosen != null) return chosen;
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
        Object owner = own;
        UtxoSupplier free = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page,
                                      com.bloxbean.cardano.client.api.common.OrderEnum order) {
                return free(all.getPage(address, nrOfItems, page, order), ownRef, skipClaimed ? owner : null);
            }

            @Override
            public java.util.Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return all.getTxOutput(txHash, outputIndex);
            }

            @Override
            public List<Utxo> getAll(String address) {
                return free(all.getAll(address), ownRef, skipClaimed ? owner : null);
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
        return free(utxos, ownRef, null);
    }

    /**
     * As {@link #free(List, String)}; with an {@code owner} (a keyed operation), also without inputs other unresolved
     * transactions claim; the operation's own claims (its earlier attempts' inputs) stay selectable.
     */
    static List<Utxo> free(List<Utxo> utxos, String ownRef, Object owner) {
        if (utxos == null) return null;
        purge();
        if (DESIGNATED.isEmpty() && (owner == null || CLAIMS.isEmpty())) return utxos;
        boolean any = false;
        for (Utxo u : utxos) {
            if (!selectable(ref(u), ownRef, owner)) {
                any = true;
                break;
            }
        }
        if (!any) return utxos;
        var out = new ArrayList<Utxo>(utxos.size());
        for (Utxo u : utxos) {
            if (selectable(ref(u), ownRef, owner)) out.add(u);
        }
        return out;
    }

    private static boolean selectable(String ref, String ownRef, Object owner) {
        if (ref.equals(ownRef)) return true;
        return !DESIGNATED.contains(ref) && !(owner != null && claimedByOther(ref, owner));
    }

    /** A built transaction and its claim. */
    record Claimed(Transaction tx, Claim claim) {
        List<String> refs() {
            return List.copyOf(claim.refs);
        }
    }

    /**
     * Builds under {@code address}'s lock and claims the transaction's inputs (spent and collateral) before the lock is
     * released: a designation for that address made afterwards skips them, one made before was skipped by the build. An
     * input another keyed operation designated since it was picked (a seed, a funding input) is refused (F63).
     */
    static Claimed build(String address, Designated own, Supplier<Transaction> build) {
        return build(address, own, Set.of(), build);
    }

    /**
     * As {@link #build(String, Designated, Supplier)}; {@code scriptInputs} (the script UTxOs the transaction spends: list
     * nodes, instances) are not claimed. Contention for them is the ledger's business: a keyed operation must not keep
     * another from spending a shared anchor, only from taking its wallet inputs (fees, collateral, its designated input).
     */
    static Claimed build(String address, Designated own, Set<String> scriptInputs, Supplier<Transaction> build) {
        return locked(address, () -> {
            Transaction t = build.get();
            var claim = new Claim(own);
            for (var in : t.getBody().getInputs()) if (!scriptInputs.contains(ref(in))) claim.refs.add(ref(in));
            if (t.getBody().getCollateral() != null) for (var in : t.getBody().getCollateral()) claim.refs.add(ref(in));
            String ownRef = own == null ? null : own.ref();
            for (String r : claim.refs) {
                if (!r.equals(ownRef) && isDesignated(r)) {
                    throw new SubmitException(SubmitException.Kind.TRANSIENT, "input " + r + " was designated by a keyed"
                            + " operation after it was picked; rebuild");
                }
                // A keyed attempt never spends what another keyed operation's handed-out attempts hold, even as an
                // explicit input (F72); plain transactions keep their pre-M2 selection.
                if (own != null && heldByAnotherOperation(r, own)) {
                    throw new SubmitException(SubmitException.Kind.TRANSIENT, "input " + r + " is held by another keyed"
                            + " operation's attempt; rebuild");
                }
            }
            CLAIMS.add(claim);
            return new Claimed(t, claim);
        });
    }

    /**
     * Picks a UTxO of {@code address} explicitly (a seed, a funding input) under its lock, and claims it for the open
     * {@link #scope()}: no keyed operation designates it before the transaction that spends it is built (and claims it
     * itself); the scope releases it then, or when nothing was built (F71). Outside a scope (a caller that opened none)
     * the claim lasts {@code fallbackMs}. Null when {@code pick} finds none. {@code pick} must skip designated inputs
     * and those another keyed operation holds ({@link #pickable}).
     */
    static Utxo pick(String address, long fallbackMs, Supplier<Utxo> pick) {
        return locked(address, () -> {
            Utxo u = pick.get();
            if (u != null) {
                var claim = new Claim(null);
                claim.refs.add(ref(u));
                List<Claim> scope = SCOPE.get();
                if (scope != null) scope.add(claim);
                else claim.until = System.currentTimeMillis() + fallbackMs;
                CLAIMS.add(claim);
            }
            return u;
        });
    }

    /**
     * An explicit pick for operation {@code own} (null outside a keyed operation) may take {@code u}: not designated,
     * and not held by another keyed operation's attempt (F72). The operation's own held inputs stay pickable, so its
     * rebuild can pick the seed its earlier attempt used (F76). (The pick's temporary claim is the pick's own, apart
     * from the operation's.)
     */
    static boolean pickable(Utxo u, Designated own) {
        String ref = ref(u);
        return !isDesignated(ref) && !heldByAnotherOperation(ref, own);
    }

    /** For tests: nothing designated or claimed. */
    static boolean idle() {
        purge();
        return DESIGNATED.isEmpty() && CLAIMS.isEmpty();
    }
}
