package org.julclang.state.runtime;

import com.bloxbean.cardano.client.backend.api.BackendService;

/**
 * Where the runtime sends a keyed operation's signed attempts (ADR-0016 D7). A runtime may send the same signed bytes to
 * several relays; their answers combine transaction-wide: any acceptance, or any delivery whose fate is unknown (a relay
 * that throws or gives no answer included), dominates every rejection. Relays are untrusted: an answer only decides
 * whether the runtime may build another transaction, and the operation's designated input keeps any two of its attempts
 * from both landing. Transactions without a designated input go to the backend alone.
 */
@FunctionalInterface
public interface Relay {

    /** What a relay answered. */
    enum Answer { ACCEPTED, REJECTED, UNKNOWN }

    /** Its answer, and a message for the log (the node's reason, or the error). */
    record Outcome(Answer answer, String message) {
        public static Outcome accepted() {
            return new Outcome(Answer.ACCEPTED, "accepted");
        }

        public static Outcome rejected(String why) {
            return new Outcome(Answer.REJECTED, why);
        }

        public static Outcome unknown(String why) {
            return new Outcome(Answer.UNKNOWN, why);
        }
    }

    /**
     * Sends {@code cbor}. Should not throw: a failure to deliver is {@link Answer#UNKNOWN}, and so is an exception or a
     * null answer (the runtime treats them alike).
     */
    Outcome submit(byte[] cbor);

    /**
     * The backend's submit endpoint. A 4xx answer with a body (the node's or the provider's reason: a ledger or
     * validation error) is a rejection; a 408 or 429, any 5xx (a gateway may time out after the node accepted it), an
     * empty body, an answer without a status, and a network error are unknown (R-C10).
     */
    static Relay of(BackendService backend) {
        return cbor -> {
            try {
                return classify(backend.getTransactionService().submitTransaction(cbor));
            } catch (Exception e) {
                return Outcome.unknown(TxRunner.rootMessage(e));
            }
        };
    }

    /** A submit endpoint's answer as an {@link Outcome} (see {@link #of}). */
    static Outcome classify(com.bloxbean.cardano.client.api.model.Result<?> r) {
        if (r == null) return Outcome.unknown("no answer");
        if (r.isSuccessful()) return Outcome.accepted();
        int code = r.code();
        String body = r.getResponse();
        boolean definite = code >= 400 && code < 500 && code != 408 && code != 425 && code != 429
                && body != null && !body.isBlank();
        String why = "HTTP " + code + ": " + body;
        return definite ? Outcome.rejected(why) : Outcome.unknown(why);
    }
}
