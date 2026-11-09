package org.julclang.state.runtime;

import com.bloxbean.cardano.client.backend.api.BackendService;

/**
 * Where the runtime sends a signed transaction (ADR-0016 D7). A runtime may send the same signed bytes to several relays;
 * their answers combine transaction-wide: any acceptance, or any delivery whose fate is unknown, dominates every
 * rejection. Relays are untrusted: an answer only decides whether the runtime may build another transaction, and a keyed
 * operation's designated input keeps any two of its attempts from both landing.
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

    /** Sends {@code cbor}; never throws (a failure to deliver is {@link Answer#UNKNOWN}). */
    Outcome submit(byte[] cbor);

    /** The backend's submit endpoint: a definite answer from the node, or unknown on a network error. */
    static Relay of(BackendService backend) {
        return cbor -> {
            try {
                var r = backend.getTransactionService().submitTransaction(cbor);
                return r.isSuccessful() ? Outcome.accepted() : Outcome.rejected(String.valueOf(r.getResponse()));
            } catch (Exception e) {
                return Outcome.unknown(TxRunner.rootMessage(e));
            }
        };
    }
}
