package org.julclang.state.runtime;

/**
 * A transaction was accepted for submission but was not seen on chain in time. The runtime does not resubmit: the
 * call may still land. Check {@link #txHash()} before trying again.
 */
public class PendingTransactionException extends JoustException {
    private final String txHash;

    public PendingTransactionException(String txHash, String message) {
        super(message + " (tx " + txHash + ")");
        this.txHash = txHash;
    }

    public String txHash() {
        return txHash;
    }
}
