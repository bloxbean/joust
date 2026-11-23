package org.julclang.state.host;

import com.bloxbean.cardano.vds.core.api.NodeStore;
import java.util.*;

/** Session-owned content-addressed trie nodes. Only shallow and final inclusions are readable. */
final class MapMirrors {
    private final Map<String, byte[]> confirmed = new HashMap<>();
    private final Map<String, Map<String, byte[]>> staged = new HashMap<>(), promoted = new HashMap<>();
    private final Map<String, Set<String>> policies = new HashMap<>();
    private final Set<String> stale = new HashSet<>();
    private boolean conflict, rewinding;

    final class Overlay implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();
        // Chain replay has its own write set: it can survive a successful view or a discarded
        // prepared transaction without leaking a single node written by the proposed transition.
        private final Map<String, byte[]> history = new HashMap<>();
        private final NodeStore historyStore = new NodeStore() {
            public byte[] get(byte[] key) {
                String k = HexFormat.of().formatHex(key);
                return history.getOrDefault(k, confirmed.get(k));
            }
            public void put(byte[] key, byte[] value) { history.put(HexFormat.of().formatHex(key), value); }
            public void delete(byte[] key) { /* Replay preserves previous roots. */ }
        };

        NodeStore historyStore() { return historyStore; }
        void commitHistory() { confirmed.putAll(history); }

        public byte[] get(byte[] key) {
            String k = HexFormat.of().formatHex(key);
            byte[] value = nodes.get(k);
            if (value != null) return value;
            value = historyStore.get(key);
            if (value != null) return value;
            for (var layer : promoted.values()) if (layer.containsKey(k)) {
                // Retain the complete ancestry, including children not traversed by the new call.
                nodes.putAll(layer);
                return layer.get(k);
            }
            return null;
        }
        public void put(byte[] key, byte[] value) { nodes.put(HexFormat.of().formatHex(key), value); }
        public void delete(byte[] key) { /* Older roots stay readable. */ }
    }

    void stage(String id, String policy, Overlay overlay) {
        policies.put(id, Set.of(policy));
        staged.put(id, overlay.nodes);
    }

    // TODO(ADR-0022 D8): promoting restored writes would require an authenticated pre-state.
    // A journal carries claims but no verified trie. Keep those writes staged and the contract
    // fenced until final settlement and cold rebuild (ADR-0022 D8, restart).
    private final Map<String, MapWithdrawal.Restored> restored = new HashMap<>();
    void checkRestored(String id, MapWithdrawal.Restored writes, MapWithdrawal.Restored sameBatch) {
        var previous = sameBatch == null ? restored.get(id) : sameBatch;
        if (previous != null && (writes == null || !previous.claimsCbor().equals(writes.claimsCbor())))
            throw HostError.bad("conflict", "restored map witness differs from the journaled transaction");
    }
    void restore(String id, MapWithdrawal.Restored writes) {
        restored.put(id, writes);
        policies.merge(id, writes.policies(), (held, imported) -> {
            var both = new HashSet<>(held);
            both.addAll(imported);
            return Set.copyOf(both);
        });
        stale.addAll(policies.get(id));
    }

    void transition(Reservations.Entry entry) {
        String id = entry.id();
        // Restored claims outlive the reservation's final state: a rewind can revive it, and its re-import is still
        // compared with them (#160 Codex re-review).
        var layer = staged.get(id);
        if (layer == null) return;
        switch (entry.state()) {
            case shallow -> promoted.put(id, layer);
            case prepared -> promoted.remove(id);
            case confirmed, discarded, expired -> {
                if (entry.state() == Reservations.State.confirmed) confirmed.putAll(layer);
                promoted.remove(id);
                staged.remove(id);
            }
        }
    }

    void rewound() { rewinding = true; }
    void reconciled() { rewinding = false; }

    void conflict(String id) { if (policies.containsKey(id)) conflict = true; }
    void allow(String policy) {
        if (rewinding || conflict || stale.contains(policy)) throw HostError.refusal("mapsStale", "settle pending map work and reset");
    }

    void reset(Collection<Reservations.Entry> entries) {
        // A restored reservation keeps its journaled claims across a reset (between restore batches, or a rewind that
        // revives a final one): a later batch is still compared with them (#160 Codex reviews). The table retains its
        // final entries for a rewind, and these claims with them.
        var held = new HashSet<String>();
        for (var entry : entries) held.add(entry.id());
        restored.keySet().retainAll(held);
        confirmed.clear();
        promoted.clear();
        staged.clear();
        stale.clear();
        conflict = false;
        for (var entry : entries) if (entry.live() && policies.containsKey(entry.id())) stale.addAll(policies.get(entry.id()));
    }
}
