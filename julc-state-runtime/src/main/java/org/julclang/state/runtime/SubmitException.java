package org.julclang.state.runtime;

/** The node or the transaction builder rejected the transaction; nothing was accepted. */
public class SubmitException extends JoustException {
    public SubmitException(String message) {
        super(message);
    }

    public SubmitException(String message, Throwable cause) {
        super(message, cause);
    }
}
