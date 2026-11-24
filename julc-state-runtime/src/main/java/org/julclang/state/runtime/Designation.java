package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
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
 *   <li>Where designations and claims are kept, and when they end, is the runtime's {@link ClaimStore} (ADR-0022 D8):
 *       by default the static store of this JVM described above, released by the machine
 *       clock. The overloads without a store use that default.</li>
 * </ul>
 */
final class Designation {

    private Designation() {}

    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    /** The default store (ADR-0022 D8): this JVM's static designations and claims, as before. */
    static final JvmClaimStore JVM = JvmClaimStore.INSTANCE;

    // Claims are ClaimStore.Claim (ADR-0022 D8 made the store a seam; a claim is what it was): the inputs held by one
    // transaction, or by every handed-out attempt of one keyed operation, until resolved; compared by identity (F66,
    // R-E3); owned by the keyed operation (Designated) whose attempts may reuse them, or by the claim itself.

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
        /**
         * What the latest attempt depends on: the script inputs it spends and the reference inputs it reads (to tell a
         * deterministic failure from contention; F68).
         */
        private List<Utxo> dependencies = List.of();
        private boolean released;
        /** Given by a wallet's page for a prepared transaction (ADR-0016 D11), not reserved in this JVM. */
        private final boolean given;
        private final ClaimStore store;
        /** The inputs of every handed-out attempt (F64): held until the series ends, then released or kept pending. */
        private final ClaimStore.Claim held;

        Designated(Utxo utxo, String address) {
            this(utxo, address, false);
        }

        Designated(Utxo utxo, String address, boolean given) {
            this(utxo, address, given, JVM);
        }

        Designated(Utxo utxo, String address, boolean given, ClaimStore store) {
            this.utxo = utxo;
            this.address = address;
            this.given = given;
            this.store = store;
            this.held = new ClaimStore.Claim(this, store);
        }

        boolean given() { return given; }

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
            store.add(held, null);
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
                for (int page = 1; page <= StateContractHandle.MAX_PAGES; page++) {   // to the listing's end (#165 Codex r6)
                    var r = rt.backend().getUtxoService().getUtxos(address, 100, page);
                    if (!r.isSuccessful() || r.getValue() == null || r.getValue().isEmpty()) return false;
                    // One output however the listing spells its transaction id (T3, #165 Codex).
                    String want = ref();
                    for (Utxo u : r.getValue()) {
                        if (Designation.ref(u).equals(want)) return true;
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
            store.undesignate(ref(), null);
            held.release();
        }

        /** Ends the reservation and its attempts' claims at {@code untilMs} (wall clock): an attempt may land until then. */
        void releaseAt(long untilMs) {
            releaseAt(ClaimStore.Expiry.atMs(untilMs));
        }

        /** Ends the reservation and its attempts' claims at {@code when}: an attempt may land until then. */
        void releaseAt(ClaimStore.Expiry when) {
            if (released) return;
            released = true;
            store.undesignate(ref(), when);
            held.releaseAt(when);
        }
    }

    // Claim-store keys name a ledger input by one spelling: the lowercase hex of the bytes CCL serialises. CCL reads an
    // id through HexUtil.decodeHexString (either case, a leading 0x dropped, any Unicode hex digit), so every spelling it
    // writes as the same input must have the same key, or one escapes the other's reservation (I13; #145 Codex reviews).
    static String ref(Utxo u) {
        return txKey(u.getTxHash()) + "#" + u.getOutputIndex();
    }

    static String ref(TransactionInput in) {
        return txKey(in.getTransactionId()) + "#" + in.getIndex();
    }

    /** {@code id} as the ledger sees it: re-encoded from the bytes CCL decodes it to. */
    static String txKey(String id) {
        if (id == null) return "null";
        try {
            return com.bloxbean.cardano.client.util.HexUtil.encodeHexString(com.bloxbean.cardano.client.util.HexUtil.decodeHexString(id));
        } catch (RuntimeException e) {
            // Not hex to CCL either: no transaction it builds can spend it (serialising refuses), so any stable key does.
            return id.toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Some keyed operation of this JVM designated {@code ref} (still reserved). */
    static boolean isDesignated(String ref) {
        return isDesignated(JVM, ref);
    }

    /** Some keyed operation designated {@code ref} in {@code store} (still reserved). */
    static boolean isDesignated(ClaimStore store, String ref) {
        return store.isDesignated(ref);
    }

    /** Some unresolved transaction of this JVM claims {@code ref}. */
    static boolean isClaimed(String ref) {
        return isClaimed(JVM, ref);
    }

    /** Some unresolved transaction claims {@code ref} in {@code store}. */
    static boolean isClaimed(ClaimStore store, String ref) {
        for (ClaimStore.Claim c : store.claims()) if (c.refs.contains(ref)) return true;
        return false;
    }

    /** A claim on {@code ref} by anyone but {@code owner}. */
    private static boolean claimedByOther(ClaimStore store, String ref, Object owner) {
        Iterable<ClaimStore.Claim> claims = store instanceof JvmClaimStore j ? j.raw() : store.claims();
        for (ClaimStore.Claim c : claims) if (c.owner != owner && c.refs.contains(ref)) return true;
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
        return heldByAnotherOperation(JVM, ref, own);
    }

    /** As {@link #heldByAnotherOperation(String, Designated)}, in {@code store}. */
    static boolean heldByAnotherOperation(ClaimStore store, String ref, Designated own) {
        for (ClaimStore.Claim c : store.claims()) {
            if (c.owner instanceof Designated d && d != own && c.refs.contains(ref)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ explicit picks (F63, F71, F72)

    /** The picks made on this thread inside the outermost open scope. */
    private static final ThreadLocal<List<ClaimStore.Claim>> SCOPE = new ThreadLocal<>();

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
            List<ClaimStore.Claim> picks = SCOPE.get();
            SCOPE.remove();
            if (picks != null) picks.forEach(ClaimStore.Claim::release);
        }
    }

    static Scope scope() {
        if (SCOPE.get() != null) return new Scope(false);
        SCOPE.set(new ArrayList<>());
        return new Scope(true);
    }

    /**
     * The input a wallet's page designated for a prepared keyed call (ADR-0016 D11): {@code ref} must be an ordinary
     * (lovelace-only, no datum, no reference script) UTxO of {@code payer}, as the provider (the wallet's own UTxOs, in an
     * engine) lists it. Nothing is reserved in this JVM: the page holds the record, and the transaction is only prepared.
     */
    static Designated given(StateRuntime rt, Account payer, String ref) {
        String address = payer.baseAddress();
        // ADR-0022 D8, D11: a reserved input is taken only by the operation its reservation belongs to.
        ClaimStore store = rt.claimStore();
        if (store.avoids(ref) && !store.reusable(ref)) throw new ReservedInputException("the designated input", ref);
        boolean complete = false;
        // A host answers from the complete wallet listing it acquired: read all of it, so a designation past the first
        // 2,000 outputs is found (#156 Codex review). A provider is read to the listing's end too (#165 Codex r6),
        // bounded by MAX_PAGES.
        int lastPage = rt.hostedCall() ? 100_000 : StateContractHandle.MAX_PAGES;
        try {
            for (int page = 1; page <= lastPage; page++) {
                var r = rt.backend().getUtxoService().getUtxos(address, 100, page);
                if (!r.isSuccessful()) {
                    throw new JoustException("cannot read the payer's UTxOs for the designated input " + ref + ": " + r.getResponse());
                }
                if (r.getValue() == null || r.getValue().isEmpty()) {
                    complete = true;
                    break;
                }
                for (Utxo u : r.getValue()) {
                    if (!ref(u).equals(ref)) continue;
                    if (u.getAmount().size() != 1 || !"lovelace".equals(u.getAmount().get(0).getUnit())
                            || u.getInlineDatum() != null || u.getDataHash() != null || u.getReferenceScriptHash() != null) {
                        throw new JoustException("the designated input " + ref + " is not an ordinary (lovelace-only) UTxO of the"
                                + " payer (ADR-0016 D11)");
                    }
                    // As reserve's floor: it also gives the change a payout to the payer needs (the funding pick it replaces).
                    if (u.getAmount().get(0).getQuantity().compareTo(BigInteger.valueOf(5_000_000)) < 0) {
                        throw new JoustException("the designated input " + ref + " holds less than 5 ADA (ADR-0016 D11, R-G3)");
                    }
                    return new Designated(u, address, true, rt.claimStore());
                }
                if (r.getValue().size() < 100) {
                    complete = true;
                    break;
                }
            }
        } catch (JoustException e) {
            throw e;
        } catch (Exception e) {
            throw new JoustException("cannot read the payer's UTxOs for the designated input " + ref, e);
        }
        if (!complete) {
            throw new JoustException("the payer " + address + " has more UTxOs than the " + lastPage
                    + " pages read; the designated input " + ref + " was not among them (R-G3)");
        }
        throw new JoustException("the designated input " + ref + " is not an unspent UTxO of the payer " + address
                + " (spent: an earlier attempt of the operation may have landed; ADR-0016 D11)");
    }

    /**
     * Reserves an ordinary (lovelace-only, no datum, no reference script) UTxO of {@code payer} of at least 5 ADA that
     * no other operation of this JVM holds and no unresolved transaction of it spends, and that is not in
     * {@code avoid}: the largest such, else the next, within one read.
     */
    static Designated reserve(StateRuntime rt, Account payer, String what, Set<String> avoid) {
        String address = payer.baseAddress();
        ClaimStore store = rt.claimStore();
        if (store instanceof JvmClaimStore j) j.purge();
        boolean[] unanswered = {false};   // the first page failed: said in the refusal (#165 Opus P3, round 7)
        // Read and chosen under the address's lock (F63): no build of that address can spend and release a candidate
        // between the read and the designation.
        Designated chosen = locked(address, () -> {
            var candidates = new ArrayList<Utxo>();
            try {
                // To the listing's end (#165 Codex r6, r7): a failed later page is an error, a failed first one said below.
                var walk = new Pages(rt, address, 100, StateContractHandle.MAX_PAGES)
                        .toEnd((page, response) -> what + ": cannot read page " + page + " of the payer's UTxOs at " + address
                                        + ": " + response,
                                what + ": the payer's listing at " + address + " has more than "
                                        + StateContractHandle.MAX_PAGES + " pages; not read to its end");
                for (var list = walk.next(); list != null; list = walk.next()) {
                    for (Utxo u : list) {
                        if (u.getAmount().size() != 1 || !"lovelace".equals(u.getAmount().get(0).getUnit())
                                || u.getInlineDatum() != null || u.getDataHash() != null || u.getReferenceScriptHash() != null) {
                            continue;
                        }
                        if (u.getAmount().get(0).getQuantity().compareTo(BigInteger.valueOf(5_000_000)) < 0) continue;
                        candidates.add(u);
                    }
                }
                unanswered[0] = walk.unanswered();
            } catch (JoustException e) {
                throw e;
            } catch (Exception e) {
                throw new JoustException(what + ": cannot read the payer's UTxOs", e);
            }
            candidates.sort(Comparator.comparing((Utxo u) -> u.getAmount().get(0).getQuantity()).reversed());
            for (Utxo u : candidates) {
                String ref = ref(u);
                if (avoid.contains(ref) || store.avoids(ref) || isClaimed(store, ref)) continue;
                if (store.designate(ref)) return new Designated(u, address, false, store);
            }
            return null;
        });
        if (chosen != null) return chosen;
        throw new JoustException(what + ": the payer has no ordinary UTxO of at least 5 ADA free to designate"
                + (unanswered[0] ? " (or the provider did not answer its listing)" : "")
                + " (ADR-0016 D7: each concurrent keyed operation of a wallet needs its own)");
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
        return builder(backend, JVM, own);
    }

    /** As {@link #builder(BackendService, Designated)}, selecting around {@code store}'s designations, claims and avoids. */
    static QuickTxBuilder builder(StateRuntime rt, Designated own) {
        return builder(rt.backend(), rt.claimStore(), own);
    }

    static QuickTxBuilder builder(BackendService backend, ClaimStore store, Designated own) {
        var all = new com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier(backend.getUtxoService());
        // A wallet designation is an explicit spend. It gives no exemption from local keyed claims.
        Object owner = own == null || own.given() ? null : own;
        String ownRef = selectableDesignation(store, own);
        UtxoSupplier free = new UtxoSupplier() {
            record Query(String address, int size, OrderEnum order) {}
            class Cursor {
                final List<Utxo> eligible = new ArrayList<>();
                int next;
                boolean finished;
                String lastFirst;   // the previous raw page's first input: a provider that ignores the page repeats it
            }
            private final Map<Query, Cursor> pages = new java.util.HashMap<>();

            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
                if (store instanceof JvmClaimStore) {
                    return free(store, all.getPage(address, nrOfItems, page, order), ownRef, owner);
                }
                // CCL indexes pages from zero. Read just far enough to fill the requested eligible page,
                // retaining the provider's order and any surplus for the next request.
                int size = nrOfItems == null ? UtxoSupplier.DEFAULT_NR_OF_ITEMS_TO_FETCH : nrOfItems;
                int start = Math.multiplyExact(page == null ? 0 : page, size);
                int end = Math.addExact(start, size);
                Cursor cursor = pages.computeIfAbsent(new Query(address, size, order), key -> new Cursor());
                while (!cursor.finished && cursor.eligible.size() < end) {
                    var raw = all.getPage(address, size, cursor.next, order);
                    // A repeated page is the end of the answer, checked before filtering: a page every input of which
                    // is reserved would otherwise be read again and again under the payer's lock (#145 Codex).
                    String first = raw.isEmpty() ? null : ref(raw.get(0));
                    if (first != null && first.equals(cursor.lastFirst)) {
                        cursor.finished = true;
                        break;
                    }
                    cursor.lastFirst = first;
                    cursor.eligible.addAll(free(store, raw, ownRef, owner));
                    cursor.next++;
                    cursor.finished = raw.size() < size;
                }
                int count = cursor.eligible.size();
                return new ArrayList<>(cursor.eligible.subList(Math.min(start, count), Math.min(end, count)));
            }

            @Override
            public java.util.Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return all.getTxOutput(txHash, outputIndex);
            }

            @Override
            public List<Utxo> getAll(String address) {
                return free(store, all.getAll(address), ownRef, owner);
            }

            @Override
            public boolean isUsedAddress(com.bloxbean.cardano.client.address.Address address) {
                return all.isUsedAddress(address);
            }

            @Override
            public void setSearchByAddressVkh(boolean flag) {
                pages.clear();
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
        return free(JVM, utxos, ownRef, owner);
    }

    /** As {@link #free(List, String, Object)}, in {@code store}: also without the inputs it {@linkplain ClaimStore#avoids avoids}. */
    static List<Utxo> free(ClaimStore store, List<Utxo> utxos, String ownRef, Object owner) {
        if (utxos == null) return null;
        if (store instanceof JvmClaimStore j) {
            j.purge();
            if (j.quiet(owner != null)) return utxos;
        }
        boolean any = false;
        for (Utxo u : utxos) {
            if (!selectable(store, ref(u), ownRef, owner)) {
                any = true;
                break;
            }
        }
        if (!any) return utxos;
        var out = new ArrayList<Utxo>(utxos.size());
        for (Utxo u : utxos) {
            if (selectable(store, ref(u), ownRef, owner)) out.add(u);
        }
        return out;
    }

    private static boolean selectable(ClaimStore store, String ref, String ownRef, Object owner) {
        if (ref.equals(ownRef)) return !store.avoids(ref) || store.reusable(ref);
        boolean designated = store instanceof JvmClaimStore j ? j.designatedRaw(ref) : store.isDesignated(ref);
        return !designated && !store.avoids(ref) && !(owner != null && claimedByOther(store, ref, owner));
    }

    /** A built transaction and its claim. */
    record Claimed(Transaction tx, ClaimStore.Claim claim) {
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
        return build(JVM, address, own, scriptInputs, build);
    }

    /** As {@link #build(String, Designated, Set, Supplier)}, claiming in {@code store}. */
    static Claimed build(ClaimStore store, String address, Designated own, Supplier<Transaction> build) {
        return build(store, address, own, Set.of(), build);
    }

    static Claimed build(ClaimStore store, String address, Designated own, Set<String> scriptInputs, Supplier<Transaction> build) {
        return locked(address, () -> {
            Transaction t = build.get();
            // Wallet prepares retain ordinary claim ownership, including when the page supplied a designation.
            Designated owner = own != null && !own.given() ? own : null;
            var claim = new ClaimStore.Claim(owner, store);
            String ownRef = selectableDesignation(store, own);
            for (var in : t.getBody().getInputs()) {
                String ref = ref(in);
                checkReservation(store, ref, ownRef);
                if (!scriptInputs.contains(ref)) claim.refs.add(ref);
            }
            if (t.getBody().getCollateral() != null) {
                for (var in : t.getBody().getCollateral()) {
                    String ref = ref(in);
                    checkReservation(store, ref, ownRef);
                    claim.refs.add(ref);
                }
            }
            for (String r : claim.refs) {
                if (!r.equals(ownRef) && isDesignated(store, r)) {
                    throw new SubmitException(SubmitException.Kind.TRANSIENT, "input " + r + " was designated by a keyed"
                            + " operation after it was picked; rebuild");
                }
                // A keyed attempt never spends what another keyed operation's handed-out attempts hold, even as an
                // explicit input (F72); plain transactions keep their pre-M2 selection.
                if (owner != null && heldByAnotherOperation(store, r, owner)) {
                    throw new SubmitException(SubmitException.Kind.TRANSIENT, "input " + r + " is held by another keyed"
                            + " operation's attempt; rebuild");
                }
            }
            store.add(claim, null);
            return new Claimed(t, claim);
        });
    }

    /** A supplied ref alone gives no exemption from another operation's designation. */
    private static String selectableDesignation(ClaimStore store, Designated own) {
        if (own == null) return null;
        String ref = own.ref();
        if (own.given() && !(store.avoids(ref) && store.reusable(ref))) return null;
        return ref;
    }

    private static void checkReservation(ClaimStore store, String ref, String ownRef) {
        if (store.avoids(ref) && !(ref.equals(ownRef) && store.reusable(ref))) {
            throw new ReservedInputException("build", ref);
        }
    }

    /**
     * Picks a UTxO of {@code address} explicitly (a seed, a funding input) under its lock, and claims it for the open
     * {@link #scope()}: no keyed operation designates it before the transaction that spends it is built (and claims it
     * itself); the scope releases it then, or when nothing was built (F71). Outside a scope (a caller that opened none)
     * the claim lasts {@code fallbackMs}. Null when {@code pick} finds none. {@code pick} must skip designated inputs
     * and those another keyed operation holds ({@link #pickable}).
     */
    static Utxo pick(String address, long fallbackMs, Supplier<Utxo> pick) {
        return pick(JVM, address, fallbackMs, pick);
    }

    /** As {@link #pick(String, long, Supplier)}, claiming in {@code store}. */
    static Utxo pick(ClaimStore store, String address, long fallbackMs, Supplier<Utxo> pick) {
        return locked(address, () -> {
            Utxo u = pick.get();
            if (u != null) {
                var claim = new ClaimStore.Claim(null, store);
                claim.refs.add(ref(u));
                List<ClaimStore.Claim> scope = SCOPE.get();
                ClaimStore.Expiry until = null;
                if (scope != null) scope.add(claim);
                else until = ClaimStore.Expiry.atMs(System.currentTimeMillis() + fallbackMs);
                store.add(claim, until);
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
        return pickable(JVM, u, own);
    }

    /** As {@link #pickable(Utxo, Designated)}, in {@code store}: also not an input it {@linkplain ClaimStore#avoids avoids}. */
    static boolean pickable(ClaimStore store, Utxo u, Designated own) {
        String ref = ref(u);
        return !isDesignated(store, ref) && !heldByAnotherOperation(store, ref, own) && !store.avoids(ref);
    }

    /** For tests: nothing designated or claimed. */
    static boolean idle() {
        return JVM.idle();
    }
}
