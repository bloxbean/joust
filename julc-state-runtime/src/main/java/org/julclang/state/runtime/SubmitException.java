package org.julclang.state.runtime;

/** The node or the transaction builder rejected the transaction; nothing was accepted. */
public class SubmitException extends JoustException {

    /**
     * Why, for a keyed operation's retry rule (ADR-0016 D7): whether another transaction may succeed where this one
     * failed. A rebuild is allowed in every case only while the designated input can be spent.
     */
    enum Kind {
        /** Every relay rejected it, not for a script failure: contention (an input moved, the window). Rebuilt. */
        REJECTED,
        /** Every relay rejected it for a phase-2 script failure: rebuilt only if an input it spent has moved. */
        SCRIPT,
        /** Never handed out: the builder, its script evaluation or the post-build checks failed. Rebuilt only if moved. */
        BUILD,
        /** Never handed out, for a reason that passes (an inconsistent snapshot, a window that closed first). Rebuilt. */
        TRANSIENT
    }

    final Kind kind;

    public SubmitException(String message) {
        this(Kind.REJECTED, message, null);
    }

    public SubmitException(String message, Throwable cause) {
        this(Kind.REJECTED, message, cause);
    }

    SubmitException(Kind kind, String message) {
        this(kind, message, null);
    }

    SubmitException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }
}
