package org.julclang.state.runtime;

import org.julclang.state.api.Payout;

import java.util.List;

/**
 * A call that landed on chain.
 *
 * @param txHash  the transaction
 * @param state   the state after the call; {@code null} if the call destroyed the instance
 * @param payouts what the contract paid out
 * @param events  the events the method emitted, as carried by the redeemer (ADR-0009 D5)
 */
public record CallResult<S>(String txHash, S state, List<Payout> payouts, List<Object> events) {

    public CallResult(String txHash, S state, List<Payout> payouts) {
        this(txHash, state, payouts, List.of());
    }

    /** The call's events (ADR-0009 D5): the validator required the redeemer to carry exactly these. */
    public <E> List<E> events(Class<E> type) {
        return events.stream().map(type::cast).toList();
    }
}
