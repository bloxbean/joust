package org.julclang.state.runtime;

/**
 * A build would spend (or take as collateral) an input the runtime's {@link ClaimStore} reserves
 * ({@link ClaimStore#avoids}). The host can report this as {@code pending} (ADR-0022 D8).
 */
public final class ReservedInputException extends JoustException {
    private final String ref;

    public ReservedInputException(String what, String ref) {
        super(what + ": input " + ref + " is reserved by a pending transaction");
        this.ref = ref;
    }

    /** The reserved input, {@code txHash#index}. */
    public String ref() {
        return ref;
    }
}
