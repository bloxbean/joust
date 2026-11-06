package org.julclang.state.api;

import java.math.BigInteger;

/**
 * Lovelace the contract pays to a public-key payment credential.
 *
 * @param to       payment key hash of the recipient
 * @param lovelace amount, greater than zero
 */
public record Payout(byte[] to, BigInteger lovelace) {}
