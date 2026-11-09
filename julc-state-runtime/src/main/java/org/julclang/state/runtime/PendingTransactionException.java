package org.julclang.state.runtime;

import java.util.List;

/**
 * A transaction may have landed: it was handed to the node (or a relay) and its fate is unknown, or it was not seen on
 * chain in time. The runtime does not resubmit: the call may still land. Check {@link #txHash()} before trying again.
 * <p>For a keyed operation (ADR-0016 D7) the exception also names every attempt it handed out ({@link #attempts()}) and
 * its designated input ({@link #designatedInput()}): any one attempt may land, and none can land once the designated
 * input is spent by another or every attempt's validity window has passed. To resume, look the attempts up; if none
 * landed and the designated input is still unspent after their windows, the operation did not happen.
 */
public class PendingTransactionException extends JoustException {
    private final String txHash;
    private final List<String> attempts;
    private final String designatedInput;

    public PendingTransactionException(String txHash, String message) {
        super(message + " (tx " + txHash + ")");
        this.txHash = txHash;
        this.attempts = txHash == null ? List.of() : List.of(txHash);
        this.designatedInput = null;
    }

    /**
     * A keyed operation's pending outcome (ADR-0016 D7): {@code attempts} are the hashes of every attempt handed out, in
     * order (the last is {@link #txHash()}); {@code designatedInput} is the out-ref ({@code txHash#index}) they all spend.
     */
    public PendingTransactionException(String message, List<String> attempts, String designatedInput) {
        super(message + " (attempts " + attempts + ", designated input " + designatedInput + ")");
        this.attempts = List.copyOf(attempts);
        this.txHash = this.attempts.isEmpty() ? null : this.attempts.get(this.attempts.size() - 1);
        this.designatedInput = designatedInput;
    }

    /** The hash of the (last) transaction that may have landed; null when no attempt was handed out. */
    public String txHash() {
        return txHash;
    }

    /** The hashes of every attempt that was handed out, in order (one for a call that is not keyed). */
    public List<String> attempts() {
        return attempts;
    }

    /** The out-ref ({@code txHash#index}) every attempt of a keyed operation spends, or null. */
    public String designatedInput() {
        return designatedInput;
    }
}
