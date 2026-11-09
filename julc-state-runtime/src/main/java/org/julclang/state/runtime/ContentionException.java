package org.julclang.state.runtime;

/** The state kept moving under the call; it gave up after the configured number of retries. */
public class ContentionException extends JoustException {
    public ContentionException(String message, Throwable cause) {
        super(message, cause);
    }
}
