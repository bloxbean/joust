package org.julclang.state.serve;

/**
 * The caller's mistake in a request to a served contract (an unknown method, a missing or bad argument, a payment to
 * a call that takes none): a transport answers it as a bad request with the message (HTTP 400, a dev host's
 * {@code bad}).
 */
public final class BadRequest extends RuntimeException {
    public BadRequest(String message) {
        super(message);
    }
}
