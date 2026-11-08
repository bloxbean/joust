package org.julclang.state.runtime.snapshot;

import com.bloxbean.cardano.vds.core.api.NodeStore;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The MerkleMap mirror's nodes for one prepare (ADR-0015 D5): those the caller keeps (content-addressed, by hash), a
 * missing one being a {@link Need} ({@code mapNode}); and the nodes this prepare writes, which the caller keeps for the
 * next call. The mirror is the caller's (a file, IndexedDB), as a JVM runtime's is its node store.
 */
public final class SnapshotNodeStore implements NodeStore {

    private final Map<String, byte[]> known = new LinkedHashMap<>();
    private final Map<String, byte[]> written = new LinkedHashMap<>();

    /** The caller's nodes: hash (hex) to node (hex). */
    public SnapshotNodeStore(Map<String, String> nodes) {
        if (nodes != null) nodes.forEach((k, v) -> known.put(k.toLowerCase(), HexFormat.of().parseHex(v)));
    }

    @Override
    public byte[] get(byte[] key) {
        String k = HexFormat.of().formatHex(key);
        byte[] v = written.get(k);
        if (v == null) v = known.get(k);
        if (v == null) throw new Need("mapNode", k);
        return v;
    }

    @Override
    public void put(byte[] key, byte[] value) {
        String k = HexFormat.of().formatHex(key);
        if (!known.containsKey(k)) written.put(k, value);
    }

    @Override
    public void delete(byte[] key) {
        // Content-addressed: every past root stays readable (ADR-0002 D5); nothing is deleted.
    }

    /** The nodes this prepare wrote (hash to node, hex). */
    public Map<String, String> written() {
        var out = new LinkedHashMap<String, String>();
        written.forEach((k, v) -> out.put(k, HexFormat.of().formatHex(v)));
        return out;
    }
}
