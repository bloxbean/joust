package org.julclang.state.dsl;

/** A .jsc error at a source position. */
public final class JscException extends RuntimeException {
    public final int line;
    public final int col;

    public JscException(int line, int col, String message) {
        super(message);
        this.line = line;
        this.col = col;
    }

    /** {@code file:line:col: error: message}. */
    public String format(String file) {
        return file + ":" + line + ":" + col + ": error: " + getMessage();
    }
}
