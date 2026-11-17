package org.julclang.state.runtime;

/**
 * A wallet's keyed call that cannot be prepared yet (ADR-0016 D7 step 5, D9): its link window would start beyond the
 * page's start limit while an anchor or predecessor cools down, and an engine cannot wait. {@link #prepareAt()} is the
 * slot from which to prepare again; the engine returns it as a structured field ({@code retryAtSlot}), which is what a
 * client waits on, never the message text (ADR-0020 §12.1: a contract can trace any text).
 */
public final class NotYetException extends SubmitException {

    private final long prepareAt;

    NotYetException(String message, long prepareAt) {
        super(Kind.TRANSIENT, message);
        this.prepareAt = prepareAt;
    }

    /** The slot from which the call may be prepared again. */
    public long prepareAt() {
        return prepareAt;
    }
}
