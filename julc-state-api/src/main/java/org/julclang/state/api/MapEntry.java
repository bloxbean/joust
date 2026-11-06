package org.julclang.state.api;

/**
 * One key of a {@link MerkleMap}: present with a value, or absent.
 *
 * @param key     the key
 * @param present whether the key is in the map
 * @param value   the value (empty when absent)
 */
public record MapEntry(byte[] key, boolean present, byte[] value) {}
