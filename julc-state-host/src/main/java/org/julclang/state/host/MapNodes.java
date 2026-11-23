package org.julclang.state.host;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A session's MerkleMap trie nodes (ADR-0022 D8, Q3), written by history replays and prepares and dropped on reset.
 * Content-addressed: a node belongs to the root whose hash names it, and every lookup starts from the root a chain
 * datum names, so a node left by a transaction that never lands changes no proof.
 */
final class MapNodes implements NodeStore {
    private final Map<String, byte[]> nodes = new ConcurrentHashMap<>();

    @Override public byte[] get(byte[] key) { return nodes.get(HexFormat.of().formatHex(key)); }
    @Override public void put(byte[] key, byte[] value) { nodes.put(HexFormat.of().formatHex(key), value); }
    @Override public void delete(byte[] key) { /* Older roots stay readable. */ }
}
