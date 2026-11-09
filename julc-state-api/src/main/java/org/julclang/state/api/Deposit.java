package org.julclang.state.api;

import org.julclang.core.PlutusData;
import org.julclang.ledger.Value;

/**
 * A payment into another JOUST instance (ADR-0011 D3): {@code value} goes into the continuing output of instance
 * {@code instance} of contract {@code policy}, which must be spent in the same transaction by an action that
 * {@link Receives} deposits. {@code memo} is any data the receiver may read (a beneficiary, an order id).
 */
public record Deposit(byte[] policy, byte[] instance, Value value, PlutusData memo) {}
