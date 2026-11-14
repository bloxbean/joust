package org.julclang.state.dsl;

/** A .jsc error at a source position, with its stable code (ADR-0018 D12; null for a few build-level errors of JscMain). */
public final class JscException extends RuntimeException {
    public final int line;
    public final int col;
    /** The diagnostic's stable code ({@code JSC0502}), or null (a build-level error of JscMain that has none). */
    public final String code;

    public JscException(int line, int col, String message) {
        this(line, col, null, message);
    }

    public JscException(int line, int col, String code, String message) {
        super(message);
        this.line = line;
        this.col = col;
        this.code = code;
    }

    /** {@code file:line:col: error[CODE]: message} ({@code error:} without a code). */
    public String format(String file) {
        return file + ":" + line + ":" + col + ": error" + (code == null ? "" : "[" + code + "]") + ": " + getMessage();
    }
}
