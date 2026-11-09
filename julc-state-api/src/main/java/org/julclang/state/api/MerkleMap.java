package org.julclang.state.api;

import org.julclang.state.api.MapEntry;

import org.julclang.core.types.JulcList;

/**
 * A map stored as a Merkle Patricia Forestry root (ADR-0002). Use it as a component of the state record to hold an
 * unbounded map: the datum keeps only the 32-byte {@code root}; each call proves the keys it touches.
 * <p>
 * Read and change it only through {@link MerkleMaps}. {@code reads} and {@code writes} exist only while a contract
 * method runs: the keys this call may read (proven against {@code root}) and the changes it makes (latest first). In
 * the datum both are empty.
 *
 * @param root   MPF root of the map before this call
 * @param reads  proven entries this call may read
 * @param writes this call's writes, latest first
 */
public record MerkleMap(byte[] root, JulcList<MapEntry> reads, JulcList<MapEntry> writes) {}
