package org.julclang.state.runtime;

import org.julclang.state.api.Payout;

import java.util.List;

/**
 * A call that landed on chain.
 *
 * @param txHash  the transaction
 * @param state   the state after the call; {@code null} if the call destroyed the instance
 * @param payouts what the contract paid out
 */
public record CallResult<S>(String txHash, S state, List<Payout> payouts) {}
