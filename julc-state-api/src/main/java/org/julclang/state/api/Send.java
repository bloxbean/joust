package org.julclang.state.api;

import org.julclang.ledger.Address;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.Value;

/**
 * An output a method sends from the UTxO it acts on (ADR-0007 D5): to any address, key or script, with any datum.
 * The generated validator requires an output at {@code to} with exactly {@code datum}, no reference script and at
 * least {@code value}, claimed for this call in the shared output registry so no other obligation can count it.
 * <p>
 * For lovelace to a key, {@link Payout} is cheaper.
 */
public record Send(Address to, Value value, OutputDatum datum) {}
