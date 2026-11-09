package org.julclang.state.runtime;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Everything a generated client needs to talk to a network: backend, optional script evaluator, network id, clock,
 * TTL, retry policy and an address book for payout recipients. Immutable; build with {@link #builder(BackendService)}.
 */
public final class StateRuntime {

    private final BackendService backend;
    private final TransactionEvaluator evaluator;
    private final Network network;
    private final ChainTime chainTime;
    private final long ttlMs;
    private final int maxRetries;
    private final Duration confirmTimeout;
    private final Map<String, String> addressBook;
    private final Consumer<String> log;
    private volatile com.bloxbean.cardano.vds.core.api.NodeStore mapStore;
    private final String mapStorePath;
    private final MerkleSupport.Prover mapProver;
    private final Consumer<BuiltTx> onBuilt;
    private final Map<String, com.bloxbean.cardano.client.api.model.Utxo> referenceScripts;
    private final java.util.List<Relay> relays;
    private final long[] pageLimits;
    private final long topUpAllowance;

    /** A transaction the runtime built: what it is for, and the transaction (signed when submitted, unsigned when prepared). */
    public record BuiltTx(String what, com.bloxbean.cardano.client.transaction.spec.Transaction tx, boolean prepared) {}

    private StateRuntime(Builder b) {
        this.backend = Objects.requireNonNull(b.backend, "backend");
        this.evaluator = b.evaluator;
        this.network = b.network;
        this.chainTime = Objects.requireNonNull(b.chainTime, "chainTime (slot <-> time conversion) is required");
        this.ttlMs = b.ttlMs;
        this.maxRetries = b.maxRetries;
        this.confirmTimeout = b.confirmTimeout;
        this.addressBook = Map.copyOf(b.addressBook);
        this.log = b.log;
        this.mapStore = b.mapStore;               // null: the default store, opened when a map contract first needs it
        this.mapStorePath = b.mapStorePath;
        this.mapProver = b.mapProver;
        this.onBuilt = b.onBuilt;
        this.referenceScripts = Map.copyOf(b.referenceScripts);
        this.relays = b.relays.isEmpty() ? java.util.List.of(Relay.of(b.backend)) : java.util.List.copyOf(b.relays);
        this.pageLimits = b.pageLimits;
        this.topUpAllowance = b.topUpAllowance;
        org.julclang.stdlib.Builtins.setCryptoProvider(new CclCryptoProvider());   // contract code may hash (ADR-0002)
    }

    public static Builder builder(BackendService backend) {
        return new Builder(backend);
    }

    public BackendService backend() { return backend; }
    /** May be null: then QuickTx uses the backend's evaluation endpoint. */
    public TransactionEvaluator evaluator() { return evaluator; }
    public Network network() { return network; }
    public ChainTime chainTime() { return chainTime; }
    public long ttlMs() { return ttlMs; }
    public int maxRetries() { return maxRetries; }
    public Duration confirmTimeout() { return confirmTimeout; }
    /**
     * Where a keyed operation's attempts go (default: the backend's submit endpoint; ADR-0016 D7). Every other
     * transaction goes to the backend alone, as before: only an operation with a designated input may rebuild after an
     * untrusted relay's answer.
     */
    public java.util.List<Relay> relays() { return relays; }
    /**
     * The most lovelace a keyed operation adds to an output to reach the ledger minimum computed from the provider's
     * protocol parameters (a relinked anchor or predecessor, a retired node, a padded payout, a head; ADR-0016 §2, R-C8).
     */
    public long topUpAllowance() { return topUpAllowance; }
    /**
     * A page's absolute window limits, in slots after the tip ({@code {maxStartSlots, maxValiditySlots}}), or null: keyed
     * calls then keep their windows within them (ADR-0016 D7 steps 1 and 5).
     */
    public long[] pageLimits() { return pageLimits == null ? null : pageLimits.clone(); }
    void log(String message) { log.accept(message); }
    /** Reports a built transaction to the observer, if any (fixtures, diagnostics; ADR-0015 M0). */
    void built(String what, com.bloxbean.cardano.client.transaction.spec.Transaction tx, boolean prepared) {
        if (onBuilt != null) onBuilt.accept(new BuiltTx(what, tx, prepared));
    }
    private volatile VerifierHandle mapVerifier;
    private volatile VerifierHandle outputVerifier;

    /** The JOUST map verifier on this network (ADR-0002 D9). */
    public VerifierHandle mapVerifier() {
        if (mapVerifier == null) {
            mapVerifier = new VerifierHandle(this, org.julclang.state.verifier.MapVerifier.class, "map verifier");
        }
        return mapVerifier;
    }

    /** The shared output verifier carrying the output-claim registry (ADR-0007 D5). */
    public VerifierHandle outputVerifier() {
        if (outputVerifier == null) {
            outputVerifier = new VerifierHandle(this, org.julclang.state.verifier.OutputVerifier.class, "output verifier");
        }
        return outputVerifier;
    }

    /** Node store of every MerkleMap mirror (content-addressed; ADR-0002 D5). */
    /**
     * Where MerkleMap mirrors keep their trie nodes (ADR-0015 §13.12 M3): the builder's store; else RocksDB at
     * {@code mapStorePath}, or the {@code joust.mapStore} system property, or {@code ./.joust/mpf}, opened on first use
     * and shared by every runtime of this JVM on that path; {@code joust.mapStore=memory} keeps it in memory (tests).
     */
    public com.bloxbean.cardano.vds.core.api.NodeStore mapStore() {
        var s = mapStore;
        if (s == null) {
            synchronized (this) {
                if (mapStore == null) mapStore = DefaultMapStore.open(mapStorePath);
                s = mapStore;
            }
        }
        return s;
    }

    /** How map calls get their reads and writes proven (§13.12 M1): the builder's prover, else a trie over {@link #mapStore}. */
    public MerkleSupport.Prover mapProver() {
        return mapProver != null ? mapProver : new MerkleSupport.TrieProver(mapStore());
    }

    /**
     * As {@link #mapProver()}, for the maps of {@code unit} (an instance's or object's token): a root the store lacks
     * is rebuilt from that unit's chain history first (ADR-0015 §13.12 M4).
     */
    public MerkleSupport.Prover mapProver(String unit) {
        if (mapProver != null) return mapProver;
        return new MerkleSupport.TrieProver(mapStore(), () -> {
            int n = MapHistory.rebuild(this, unit);
            log.accept("rebuilt the maps of " + unit + " from the chain (" + n + " claims)");
        });
    }

    /** A known address (bech32) for a payment key hash, used for payouts; null if unknown. */
    String addressOf(byte[] pkh) {
        return addressBook.get(HexUtil.encodeHexString(pkh));
    }

    /** The pinned reference script output for {@code scriptHash}, or null. */
    com.bloxbean.cardano.client.api.model.Utxo referenceScript(String scriptHash) {
        return referenceScripts.get(scriptHash.toLowerCase());
    }

    public static final class Builder {
        private final BackendService backend;
        private TransactionEvaluator evaluator;
        private Network network = Networks.testnet();
        private ChainTime chainTime;
        private long ttlMs = 600_000L;
        private int maxRetries = 3;
        private Duration confirmTimeout = Duration.ofSeconds(90);
        private final Map<String, String> addressBook = new HashMap<>();
        private Consumer<String> log = msg -> { };
        private com.bloxbean.cardano.vds.core.api.NodeStore mapStore;
        private String mapStorePath;
        private MerkleSupport.Prover mapProver;
        private Consumer<BuiltTx> onBuilt;
        private final Map<String, com.bloxbean.cardano.client.api.model.Utxo> referenceScripts = new HashMap<>();
        private final java.util.List<Relay> relays = new java.util.ArrayList<>();
        private long[] pageLimits;
        private long topUpAllowance = 10_000_000L;

        private Builder(BackendService backend) {
            this.backend = backend;
        }

        public Builder evaluator(TransactionEvaluator evaluator) { this.evaluator = evaluator; return this; }
        public Builder network(Network network) { this.network = network; return this; }
        public Builder chainTime(ChainTime chainTime) { this.chainTime = chainTime; return this; }
        /**
         * Validity window of Ctx-reading calls (clamped to the contract's maxValidityMs). Default 10 minutes. Must stay
         * inside the network's time horizon (stability window): the ledger rejects an upper bound it cannot convert to
         * POSIX time ({@code PastHorizon}). That is about 36 h on mainnet but only minutes on a local devnet.
         */
        public Builder ttl(Duration ttl) { this.ttlMs = ttl.toMillis(); return this; }
        /** How often a call is rebuilt when the node rejected it because the state moved. Default 3. */
        public Builder maxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
        public Builder confirmTimeout(Duration timeout) { this.confirmTimeout = timeout; return this; }
        /** Pay this key hash at this (base) address instead of its enterprise address. */
        public Builder address(byte[] pkh, String bech32) {
            addressBook.put(HexUtil.encodeHexString(pkh), bech32);
            return this;
        }
        public Builder log(Consumer<String> log) { this.log = log; return this; }
        /** Called with every transaction the runtime builds, before it is submitted or returned (ADR-0015 M0 fixtures). */
        public Builder onBuilt(Consumer<BuiltTx> observer) { this.onBuilt = observer; return this; }
        /** Where MerkleMap mirrors keep their trie nodes; default RocksDB (see {@link StateRuntime#mapStore()}). */
        public Builder mapStore(com.bloxbean.cardano.vds.core.api.NodeStore store) { this.mapStore = store; return this; }
        /** The default RocksDB store's directory (default {@code joust.mapStore}, else {@code ./.joust/mpf}). */
        public Builder mapStorePath(String path) { this.mapStorePath = path; return this; }
        /** Map proofs from elsewhere: the runtime engine's caller supplies them (ADR-0015 §13.12 M1). */
        public Builder mapProver(MerkleSupport.Prover prover) { this.mapProver = prover; return this; }

        /**
         * A known reference script output (a deployment's pin, ADR-0013 D2): contracts whose script hash it carries use it
         * instead of scanning their address for one (ADR-0015 D5: fewer reads).
         */
        public Builder referenceScript(com.bloxbean.cardano.client.api.model.Utxo utxo) {
            if (utxo != null && utxo.getReferenceScriptHash() != null) referenceScripts.put(utxo.getReferenceScriptHash().toLowerCase(), utxo);
            return this;
        }

        /**
         * Sends every attempt of a keyed operation to these relays instead of the backend alone (ADR-0016 D7): the same
         * bytes to each; any acceptance or unknown delivery (a relay that throws included) dominates rejections. Relays
         * are untrusted, so they are used only where a designated input keeps two attempts from both landing; every
         * other transaction (calls that are not keyed, deployments, reference scripts) goes to the backend alone.
         */
        public Builder relays(java.util.List<Relay> relays) {
            this.relays.clear();
            this.relays.addAll(relays);
            return this;
        }

        /**
         * Keeps keyed calls' windows within a page's absolute limits, in slots after the tip (ADR-0013's verifier:
         * {@code maxStartSlots} 60 and {@code maxValiditySlots} 900 by default, for one-second slots; ADR-0016 D7).
         */
        public Builder pageLimits(long maxStartSlots, long maxValiditySlots) {
            this.pageLimits = new long[]{maxStartSlots, maxValiditySlots};
            return this;
        }

        /**
         * The most lovelace a keyed operation adds to an output to reach the ledger minimum the provider's parameters
         * give (default 10 ADA, about the honest worst case); above it the call is refused (ADR-0016 §2).
         */
        public Builder topUpAllowance(long lovelace) {
            if (lovelace < 0) throw new IllegalArgumentException("topUpAllowance must not be negative");
            this.topUpAllowance = lovelace;
            return this;
        }

        public StateRuntime build() {
            return new StateRuntime(this);
        }
    }
}
