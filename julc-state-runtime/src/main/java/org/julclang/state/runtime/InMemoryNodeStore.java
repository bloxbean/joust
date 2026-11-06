package org.julclang.state.runtime;

import com.bloxbean.cardano.vds.core.api.NodeStore;

import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A content-addressed MPF node store in memory (the default MerkleMap mirror store). */
public final class InMemoryNodeStore implements NodeStore {
    private final Map<String, byte[]> nodes = new ConcurrentHashMap<>();

    public byte[] get(byte[] key) { return nodes.get(HexFormat.of().formatHex(key)); }
    public void put(byte[] key, byte[] value) { nodes.put(HexFormat.of().formatHex(key), value); }
    public void delete(byte[] key) { nodes.remove(HexFormat.of().formatHex(key)); }
}
