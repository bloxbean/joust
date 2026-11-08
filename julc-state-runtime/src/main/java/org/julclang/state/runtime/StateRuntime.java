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
    private final com.bloxbean.cardano.vds.core.api.NodeStore mapStore;
    private final Consumer<BuiltTx> onBuilt;
    private final Map<String, com.bloxbean.cardano.client.api.model.Utxo> referenceScripts;

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
        this.mapStore = b.mapStore != null ? b.mapStore : new InMemoryNodeStore();
        this.onBuilt = b.onBuilt;
        this.referenceScripts = Map.copyOf(b.referenceScripts);
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
    public com.bloxbean.cardano.vds.core.api.NodeStore mapStore() { return mapStore; }

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
        private Consumer<BuiltTx> onBuilt;
        private final Map<String, com.bloxbean.cardano.client.api.model.Utxo> referenceScripts = new HashMap<>();

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
        /** Where MerkleMap mirrors keep their trie nodes; default in memory. Use a persistent store to reconnect later. */
        public Builder mapStore(com.bloxbean.cardano.vds.core.api.NodeStore store) { this.mapStore = store; return this; }

        /**
         * A known reference script output (a deployment's pin, ADR-0013 D2): contracts whose script hash it carries use it
         * instead of scanning their address for one (ADR-0015 D5: fewer reads).
         */
        public Builder referenceScript(com.bloxbean.cardano.client.api.model.Utxo utxo) {
            if (utxo != null && utxo.getReferenceScriptHash() != null) referenceScripts.put(utxo.getReferenceScriptHash().toLowerCase(), utxo);
            return this;
        }

        public StateRuntime build() {
            return new StateRuntime(this);
        }
    }
}
