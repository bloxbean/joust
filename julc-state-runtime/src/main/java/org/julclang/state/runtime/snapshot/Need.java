package org.julclang.state.runtime.snapshot;

/**
 * A chain read the snapshot does not hold (ADR-0015 D5): the caller fetches it and calls again. An {@link Error}, not a
 * {@code RuntimeException}, so the runtime's and CCL's {@code catch (RuntimeException)} sites cannot swallow it.
 *
 * @param kind what is needed: {@code tip}, {@code protocolParams}, {@code utxos} (the complete set at an address),
 *             {@code assetUtxos} (at an address, holding a unit), {@code output} (by reference), {@code script} (by hash)
 * @param key  the address, {@code address|unit}, {@code txHash#index} or script hash; empty for tip and parameters
 */
public final class Need extends Error {
    private final String kind;
    private final String key;

    public Need(String kind, String key) {
        super(key.isEmpty() ? kind : kind + " " + key, null, false, false);
        this.kind = kind;
        this.key = key;
    }

    public String kind() {
        return kind;
    }

    public String key() {
        return key;
    }
}
