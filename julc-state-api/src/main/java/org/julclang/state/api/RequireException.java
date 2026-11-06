package org.julclang.state.api;

/** A contract rule ({@link Require#that}) failed while running a contract method on the JVM. */
public class RequireException extends RuntimeException {
    public RequireException(String message) {
        super(message);
    }
}
