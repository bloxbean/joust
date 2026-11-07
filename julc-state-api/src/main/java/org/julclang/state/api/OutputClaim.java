package org.julclang.state.api;

import org.julclang.ledger.TxOutRef;

import java.math.BigInteger;

/**
 * One entry of the shared output-claim registry (ADR-0007 D5): output {@code output} of the transaction is a
 * {@link Send} of the transition whose UTxO is {@code by}.
 */
public record OutputClaim(BigInteger output, TxOutRef by) {}
