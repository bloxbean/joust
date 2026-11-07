package org.julclang.state.server;

/** Request errors: the caller's mistake, answered with 400 and the message. */
final class Request {

    private Request() {
    }

    static final class Bad extends RuntimeException {
        Bad(String message) {
            super(message);
        }
    }
}
