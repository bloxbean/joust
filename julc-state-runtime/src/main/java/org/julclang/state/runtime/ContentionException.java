package org.julclang.state.runtime;

import java.util.List;

/**
 * The state kept moving under the call; it gave up after the configured number of retries. For a keyed operation
 * (ADR-0016 D7) this is definite: the runtime raises it only once no attempt it handed out can still land (their
 * windows have passed and the designated input is unspent); {@link #attempts()} names them.
 */
public class ContentionException extends JoustException {
    private final List<String> attempts;
    private final String designatedInput;

    public ContentionException(String message, Throwable cause) {
        this(message, cause, List.of(), null);
    }

    public ContentionException(String message, Throwable cause, List<String> attempts, String designatedInput) {
        super(message, cause);
        this.attempts = List.copyOf(attempts);
        this.designatedInput = designatedInput;
    }

    /** The hashes of the attempts a keyed operation handed out (none landed); empty otherwise. */
    public List<String> attempts() {
        return attempts;
    }

    /** The out-ref a keyed operation designated, or null. */
    public String designatedInput() {
        return designatedInput;
    }
}
