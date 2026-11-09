package org.julclang.state.runtime;

/** Base class of the runtime's failures. Contract rule failures are {@link org.julclang.state.api.RequireException}. */
public class JoustException extends RuntimeException {
    public JoustException(String message) {
        super(message);
    }

    public JoustException(String message, Throwable cause) {
        super(message, cause);
    }
}
