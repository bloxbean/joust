package org.julclang.state.dsl;

/** A .jsc error at a source position, with its stable code ({@code .jsc} 2: ADR-0018 D12; null for v1). */
public final class JscException extends RuntimeException {
    public final int line;
    public final int col;
    /** The diagnostic's stable code ({@code JSC0502}), or null (the v1 front end has none). */
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
