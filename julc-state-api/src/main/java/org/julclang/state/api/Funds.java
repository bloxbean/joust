package org.julclang.state.api;

import org.julclang.ledger.Value;

/**
 * Native assets seen by a method of an asset-aware contract (ADR-0007 D1). Declare it as the last parameter, after
 * {@link Ctx} if there is one. The client supplies it; the generated validator checks every field.
 *
 * @param policy   this contract's policy id (its script hash): name your own tokens with it
 * @param self     the token name of what the method acts on: the instance id, or the object id for owned methods
 * @param received native assets the caller adds in this call: no lovelace entry (that is {@code Ctx.received}),
 *                 positive amounts only, empty unless the method is {@link Payable}
 * @param held     native assets the UTxO holds before the call, without lovelace and without its own thread token;
 *                 empty when creating
 */
public record Funds(byte[] policy, byte[] self, Value received, Value held) {}
