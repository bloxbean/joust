package org.julclang.state.api;

import java.math.BigInteger;

/**
 * One approval granted by a script credential for {@link AuthorizedBy} (ADR-0009 r2): the JOUST contract
 * {@code policy} may run its action with redeemer tag {@code action} on {@code target} (an instance or object id).
 */
public record Approval(byte[] policy, byte[] target, BigInteger action) {}
