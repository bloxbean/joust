package org.julclang.state.runtime.snapshot;

/**
 * A chain read that was asked for and failed (ADR-0022 D6, T8): the answer is neither a value nor "absent", so the call
 * must end rather than build on an empty result. An {@link Error}, like {@link Need}, so the runtime's and CCL's
 * {@code catch (RuntimeException)} and {@code catch (Exception)} sites cannot swallow it and mistake it for absence.
 *
 * @param kind    the read: {@code utxos}, {@code output}, {@code tip}, {@code protocolParams}, {@code script}, ...
 * @param key     what was read (an address, {@code txHash#index}, a script hash); empty for tip and parameters
 * @param message the reason the reader gave (for example the client's {@code failed.message})
 */
public final class ReadFailed extends Error implements org.julclang.state.runtime.CallAbort {
    private final String kind;
    private final String key;
    private final String reason;

    public ReadFailed(String kind, String key, String message) {
        super((key == null || key.isEmpty() ? kind : kind + " " + key) + " failed" + (message == null || message.isEmpty() ? "" : ": " + message),
                null, false, false);
        this.kind = kind;
        this.key = key == null ? "" : key;
        this.reason = message == null ? "" : message;
    }

    public String kind() {
        return kind;
    }

    public String key() {
        return key;
    }

    /** The reader's own message, without the kind and key. */
    public String reason() {
        return reason;
    }
}
