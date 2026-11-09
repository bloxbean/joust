package org.julclang.state.api;

/**
 * Another JOUST contract's instance (ADR-0011 D2): its applied script hash (policy) and its 32-byte instance id. Held
 * only in a {@link Params} record, so the script it trusts is fixed at deploy.
 */
public record ForeignInstance(byte[] policy, byte[] instance) {}
