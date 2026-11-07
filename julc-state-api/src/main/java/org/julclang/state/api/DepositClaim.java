package org.julclang.state.api;

import org.julclang.core.PlutusData;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;

import java.math.BigInteger;

/**
 * A deposit in the shared output registry (ADR-0011 D3): output {@code output} receives {@code value} from the input
 * {@code by}, with {@code memo}. The output verifier checks the value is canonical and positive, free of reserved
 * tokens, and that {@code by} is an input; the depositor checks its own claims, the receiver counts every claim on its
 * continuing output exactly once.
 */
public record DepositClaim(BigInteger output, TxOutRef by, Value value, PlutusData memo) {}
