package org.julclang.state.runtime;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.julclang.core.types.JulcList;
import org.julclang.state.api.MapEntry;
import org.julclang.state.api.MerkleMap;
import org.julclang.state.api.MerkleMaps;
import org.julclang.state.api.mpf.MapClaim;
import org.julclang.state.api.mpf.Mpf;
import org.julclang.state.api.mpf.ProofStep;
import org.julclang.state.api.mpf.ReadProof;
import org.julclang.state.api.mpf.WriteProof;

import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The off-chain half of MerkleMap state (ADR-0002 D4, D5): runs a contract method with reads served (and recorded)
 * from the mirror, then builds the read and write witnesses and the new roots. The mirror is an {@code MpfTrie} over a
 * content-addressed {@link NodeStore}: every past root stays readable, so nothing is rolled back on failure.
 */
public final class MerkleSupport {

    /**
     * Where a call's map reads and writes come from (ADR-0015 §13.12 M1): a trie over a node store (the JVM's mirror),
     * or proofs the caller supplies (the engine's; each checked against the root before it is used).
     */
    public interface Prover {
        /** The entry for {@code key} in the map with {@code root}, with its proof. */
        ReadProof read(byte[] root, byte[] key);

        /** The proofs for {@code net} applied in order from {@code root}, and the root they lead to. */
        WriteResult write(byte[] root, JulcList<MapEntry> net);
    }

    /** Write proofs, in order, and the new root. */
    public record WriteResult(JulcList<WriteProof> proofs, byte[] root) {}

    private final Prover prover;
    private final Map<String, Set<String>> readsByRoot = new LinkedHashMap<>();
    private final Map<String, ReadProof> readCache = new java.util.HashMap<>();

    public MerkleSupport(NodeStore store) {
        this(new TrieProver(store));
    }

    public MerkleSupport(Prover prover) {
        this.prover = prover;
    }

    private ReadProof readProof(byte[] root, byte[] key) {
        return readCache.computeIfAbsent(hex(root) + ":" + hex(key), k -> prover.read(root, key));
    }

    /** Runs {@code body} with lookups outside the read set served from the mirror and recorded. */
    public <T> T run(Supplier<T> body) {
        return MerkleMaps.withReads((root, key) -> {
            readsByRoot.computeIfAbsent(hex(root), r -> new LinkedHashSet<>()).add(hex(key));
            return readProof(root, key).entry();
        }, body);
    }

    /**
     * Witnesses for each MerkleMap component of {@code before}, in component order, and the state to store (each map
     * replaced by {@code (newRoot, [], [])}). {@code after} is the method's result; null for a destroy (reads only).
     */
    public <S> Prepared<S> prepare(byte[] policy, Class<S> stateClass, S before, S after, org.julclang.ledger.TxOutRef owner) {
        return prepare(policy, layout(stateClass), before, after, owner, false);
    }

    /** As {@link #prepare(byte[], Class, Object, Object, org.julclang.ledger.TxOutRef)}, for any {@link Layout}. */
    public <S> Prepared<S> prepare(byte[] policy, Layout<S> layout, S before, S after, org.julclang.ledger.TxOutRef owner) {
        return prepare(policy, layout, before, after, owner, false);
    }

    /**
     * Where a state's MerkleMaps are (ADR-0002): its record components (a typed client's state), or fields of Plutus data
     * by index (a described contract's state; ADR-0015 D4).
     */
    public interface Layout<S> {
        /** Indexes of the MerkleMap components. */
        List<Integer> maps();

        MerkleMap map(S state, int index);

        /** {@code state} with each map replaced by its datum form {@code (roots[j], [], [])}. */
        S withRoots(S state, byte[][] roots);

        /** {@code state} with each map opened with {@code reads[j]} (as the validator opens it). */
        S opened(S state, List<JulcList<MapEntry>> reads);

        /** A comparable form of a state: maps reduced to (root, net writes). */
        String canonical(S state);

        default boolean hasMaps() {
            return !maps().isEmpty();
        }
    }

    /** The layout of a typed state (record components). */
    @SuppressWarnings("unchecked")
    public static <S> Layout<S> layout(Class<S> stateClass) {
        if (stateClass != null && !stateClass.isRecord() && org.julclang.core.PlutusData.class.isAssignableFrom(stateClass)) {
            return (Layout<S>) dataLayout(List.of());
        }
        List<Integer> maps = stateClass == null ? List.of() : mapComponents(stateClass);
        return new Layout<>() {
            public List<Integer> maps() { return maps; }
            public MerkleMap map(S state, int index) { return (MerkleMap) component(state, index); }
            public S withRoots(S state, byte[][] roots) { return MerkleSupport.withRoots(stateClass, state, maps, roots); }
            public S opened(S state, List<JulcList<MapEntry>> reads) { return MerkleSupport.opened(stateClass, state, maps, reads); }
            public String canonical(S state) { return MerkleSupport.canonical(state); }
        };
    }

    /** The layout of Plutus-data state whose MerkleMaps are the fields {@code maps} (a described contract). */
    public static Layout<org.julclang.core.PlutusData> dataLayout(List<Integer> maps) {
        return new Layout<>() {
            public List<Integer> maps() { return maps; }

            public MerkleMap map(org.julclang.core.PlutusData state, int index) {
                var f = ((org.julclang.core.PlutusData.ConstrData) state).fields().get(index);
                return org.julclang.clientlib.PlutusDataAdapter.convert(org.julclang.clientlib.PlutusDataAdapter.toClientLib(f), MerkleMap.class);
            }

            private org.julclang.core.PlutusData with(org.julclang.core.PlutusData state, java.util.function.IntFunction<MerkleMap> at) {
                var c = (org.julclang.core.PlutusData.ConstrData) state;
                var fields = new ArrayList<>(c.fields());
                for (int j = 0; j < maps.size(); j++) {
                    fields.set(maps.get(j), org.julclang.clientlib.PlutusDataAdapter.fromClientLib(
                            org.julclang.clientlib.PlutusDataAdapter.convert(at.apply(j))));
                }
                return new org.julclang.core.PlutusData.ConstrData(c.tag(), fields);
            }

            public org.julclang.core.PlutusData withRoots(org.julclang.core.PlutusData state, byte[][] roots) {
                JulcList<MapEntry> none = JulcList.empty();
                return with(state, j -> new MerkleMap(roots[j], none, none));
            }

            public org.julclang.core.PlutusData opened(org.julclang.core.PlutusData state, List<JulcList<MapEntry>> reads) {
                return with(state, j -> new MerkleMap(map(state, maps.get(j)).root(), reads.get(j), JulcList.empty()));
            }

            public String canonical(org.julclang.core.PlutusData state) {
                if (state == null) return "end";
                return HexFormat.of().formatHex(org.julclang.core.cbor.PlutusDataCborEncoder.encode(with(state, j -> {
                    MerkleMap m = map(state, maps.get(j));
                    return new MerkleMap(m.root(), JulcList.empty(), MerkleMaps.netWrites(m));
                })));
            }
        };
    }

    /**
     * Witnesses for a creation's maps, which start empty: claimed by the seed under fields -1, -2, ..., so they never
     * share a key with a spend of the same contract whose input is that seed (ADR-0014 review).
     */
    public <S> Prepared<S> prepareCreation(byte[] policy, Class<S> stateClass, S made, org.julclang.ledger.TxOutRef seed) {
        return prepare(policy, layout(stateClass), made, made, seed, true);
    }

    public <S> Prepared<S> prepareCreation(byte[] policy, Layout<S> layout, S made, org.julclang.ledger.TxOutRef seed) {
        return prepare(policy, layout, made, made, seed, true);
    }

    private <S> Prepared<S> prepare(byte[] policy, Layout<S> layout, S before, S after, org.julclang.ledger.TxOutRef owner,
                                    boolean creation) {
        List<Integer> maps = layout.maps();
        List<MapClaim> ordered = new ArrayList<>();
        byte[][] newRoots = new byte[maps.size()][];
        for (int i = 0; i < maps.size(); i++) {
            MerkleMap pre = layout.map(before, maps.get(i));
            byte[] root = pre.root();
            JulcList<ReadProof> reads = readProofs(root);
            JulcList<WriteProof> writes = JulcList.empty();
            byte[] newRoot = root;
            if (after != null) {
                MerkleMap post = layout.map(after, maps.get(i));
                var result = writeProofs(root, MerkleMaps.netWrites(post));
                writes = result.proofs();
                newRoot = result.root();
            }
            ordered.add(new MapClaim(policy, owner, BigInteger.valueOf(creation ? -1 - i : i), root, reads, writes, newRoot));
            newRoots[i] = newRoot;
        }
        S stored = after == null ? null : layout.withRoots(after, newRoots);
        return new Prepared<>(List.copyOf(ordered), stored);
    }

    /**
     * Replay check (ADR-0002 r2): runs {@code method} again on {@code before} with every map opened exactly as the
     * validator will open it (the witnessed reads, nothing else readable), and fails unless it computes the same
     * outcome as the recorded run. Catches a method whose access set depends on the map's internals.
     */
    public static <S> void verifyReplay(Class<S> stateClass, S before, List<MapClaim> claims,
                                        java.util.function.Function<S, Object> method, Object recorded) {
        verifyReplay(layout(stateClass), before, claims, method, recorded);
    }

    /** As {@link #verifyReplay(Class, Object, List, java.util.function.Function, Object)}, for any {@link Layout}. */
    public static <S> void verifyReplay(Layout<S> layout, S before, List<MapClaim> claims,
                                        java.util.function.Function<S, Object> method, Object recorded) {
        var readsPerMap = new ArrayList<JulcList<MapEntry>>();
        for (MapClaim claim : claims) {
            JulcList<MapEntry> reads = JulcList.empty();
            for (ReadProof r : claim.reads()) reads = reads.prepend(r.entry());
            readsPerMap.add(reads);
        }
        S opened = layout.opened(before, readsPerMap);
        Object replayed = MerkleMaps.withReads((root, key) -> {
            throw new JoustException("replay check: the method read key " + hex(key) + " outside the witnessed read set;"
                    + " its reads must not depend on the map's internals (ADR-0002 r2)");
        }, () -> method.apply(opened));
        if (!canonical(layout, replayed).equals(canonical(layout, recorded))) {
            throw new JoustException("replay check: the method computed a different result against the witnessed read"
                    + " set than when recording it; refusing to submit (ADR-0002 r2)");
        }
    }

    @SuppressWarnings("unchecked")
    private static <S> String canonical(Layout<S> layout, Object result) {
        if (result instanceof Transition<?> t) {
            String effects = org.julclang.clientlib.PlutusDataAdapter.convert(t.payouts()).serializeToHex() + "|"
                    + org.julclang.clientlib.PlutusDataAdapter.convert(t.sends()).serializeToHex() + "|"
                    + org.julclang.clientlib.PlutusDataAdapter.convert(t.mint()).serializeToHex() + "|"
                    + org.julclang.clientlib.PlutusDataAdapter.convert(t.accepted()).serializeToHex() + "|"
                    + org.julclang.clientlib.PlutusDataAdapter.convert(t.events()).serializeToHex() + "|"
                    + org.julclang.clientlib.PlutusDataAdapter.convert(t.deposits()).serializeToHex();
            return (t.next() == null ? "end" : layout.canonical((S) t.next())) + "|" + effects;
        }
        return result == null ? "end" : layout.canonical((S) result);
    }

    /** A comparable form of a method result: maps reduced to (root, net writes); everything else as Plutus data. */
    private static String canonical(Object result) {
        Object r = result;
        if (r instanceof Transition<?> t) {
            String payouts = org.julclang.clientlib.PlutusDataAdapter.convert(t.payouts()).serializeToHex();
            // Every effect, not only the state and payouts: an asset-aware method must replay to the same sends, mint,
            // accepted assets, events and deposits too (ADR-0014 review).
            String effects = org.julclang.clientlib.PlutusDataAdapter.convert(t.sends()).serializeToHex() + "|"
                    + org.julclang.clientlib.PlutusDataAdapter.convert(t.mint()).serializeToHex() + "|"
                    + org.julclang.clientlib.PlutusDataAdapter.convert(t.accepted()).serializeToHex() + "|"
                    + org.julclang.clientlib.PlutusDataAdapter.convert(t.events()).serializeToHex() + "|"
                    + org.julclang.clientlib.PlutusDataAdapter.convert(t.deposits()).serializeToHex();
            return (t.next() == null ? "end" : canonical(t.next())) + "|" + payouts + "|" + effects;
        }
        if (r != null && r.getClass().isRecord()) {
            var sb = new StringBuilder();
            for (RecordComponent c : r.getClass().getRecordComponents()) {
                Object v;
                try {
                    v = c.getAccessor().invoke(r);
                } catch (ReflectiveOperationException e) {
                    throw new JoustException("cannot read " + c.getName(), e);
                }
                if (v instanceof MerkleMap m) {
                    v = new MerkleMap(m.root(), JulcList.empty(), MerkleMaps.netWrites(m));
                }
                sb.append(org.julclang.clientlib.PlutusDataAdapter.convert(v).serializeToHex()).append(';');
            }
            return sb.toString();
        }
        return String.valueOf(r);
    }

    /** What {@link #prepare} returns. */
    public record Prepared<S>(List<MapClaim> claims, S stored) {}

    /** {@code state} with every MerkleMap component replaced by its datum form for an initial (empty) state. */
    public static boolean hasMaps(Class<?> stateClass) {
        return !mapComponents(stateClass).isEmpty();
    }

    // ------------------------------------------------------------------ witnesses

    private JulcList<ReadProof> readProofs(byte[] root) {
        Set<String> keys = readsByRoot.getOrDefault(hex(root), Set.of());
        // No reads: no proofs, and no need for the mirror (an escape hatch must work even without it; ADR-0012 r4).
        if (keys.isEmpty()) return JulcList.empty();
        var list = new ArrayList<ReadProof>();
        for (String k : keys) list.add(readProof(root, HexFormat.of().parseHex(k)));
        JulcList<ReadProof> out = JulcList.empty();
        for (int i = list.size() - 1; i >= 0; i--) out = out.prepend(list.get(i));
        return out;
    }

    private WriteResult writeProofs(byte[] root, JulcList<MapEntry> net) {
        if (net.isEmpty()) return new WriteResult(JulcList.empty(), root);
        return prover.write(root, net);
    }

    /** The JVM's prover: an {@code MpfTrie} over a content-addressed node store (every past root stays readable). */
    public static final class TrieProver implements Prover {
        private final NodeStore store;
        private final Runnable rebuild;

        public TrieProver(NodeStore store) {
            this(store, null);
        }

        /** {@code rebuild}: run once when a root is missing from the store (rebuilding from the chain, §13.12 M4). */
        public TrieProver(NodeStore store, Runnable rebuild) {
            this.store = store;
            this.rebuild = rebuild;
        }

        private boolean rebuilt;

        @Override
        public ReadProof read(byte[] root, byte[] key) {
            var trie = trie(root);
            byte[] v = trie.get(key);
            MapEntry e = v == null ? new MapEntry(key, false, new byte[0]) : new MapEntry(key, true, v);
            return new ReadProof(e, proof(trie, key));
        }

        @Override
        public WriteResult write(byte[] root, JulcList<MapEntry> net) {
            var trie = trie(root);
            var list = new ArrayList<WriteProof>();
            for (MapEntry n : net) {
                byte[] old = trie.get(n.key());
                MapEntry before = old == null ? new MapEntry(n.key(), false, new byte[0]) : new MapEntry(n.key(), true, old);
                list.add(new WriteProof(before, n, proof(trie, n.key())));
                if (n.present()) trie.put(n.key(), n.value());
                else if (old != null) trie.delete(n.key());
            }
            JulcList<WriteProof> out = JulcList.empty();
            for (int i = list.size() - 1; i >= 0; i--) out = out.prepend(list.get(i));
            return new WriteResult(out, normalize(trie.getRootHash()));
        }

        private MpfTrie trie(byte[] root) {
            if (Arrays.equals(root, Mpf.emptyRoot())) return new MpfTrie(store);
            // A root the store lacks: rebuild this map from the chain once (the history is checked, not trusted).
            if (store.get(root) == null && rebuild != null && !rebuilt) {
                rebuilt = true;
                rebuild.run();
            }
            // An MpfTrie over a store missing this root's nodes answers "absent" for everything: refuse instead.
            if (store.get(root) == null) {
                throw new JoustException("the MerkleMap mirror holds no nodes for root " + hex(root) + ": use a store that"
                        + " has this instance's map (StateRuntime.mapStore; ADR-0002 D5)");
            }
            return new MpfTrie(store, root);
        }
    }

    /** cardano-client-lib's proof (Plutus data) as JuLC {@link ProofStep}s. */
    static JulcList<ProofStep> proof(MpfTrie trie, byte[] key) {
        ListPlutusData pd = trie.getProofPlutusData(key).orElse(ListPlutusData.of());
        var steps = new ArrayList<ProofStep>();
        for (PlutusData d : pd.getPlutusDataList()) steps.add(step((ConstrPlutusData) d));
        JulcList<ProofStep> out = JulcList.empty();
        for (int i = steps.size() - 1; i >= 0; i--) out = out.prepend(steps.get(i));
        return out;
    }

    private static ProofStep step(ConstrPlutusData c) {
        var f = c.getData().getPlutusDataList();
        BigInteger skip = ((BigIntPlutusData) f.get(0)).getValue();
        return switch ((int) c.getAlternative()) {
            case 0 -> new ProofStep.Branch(skip, ((BytesPlutusData) f.get(1)).getValue());
            case 1 -> {
                var n = ((ConstrPlutusData) f.get(1)).getData().getPlutusDataList();
                yield new ProofStep.Fork(skip, new ProofStep.Neighbor(((BigIntPlutusData) n.get(0)).getValue(),
                        ((BytesPlutusData) n.get(1)).getValue(), ((BytesPlutusData) n.get(2)).getValue()));
            }
            case 2 -> new ProofStep.Leaf(skip, ((BytesPlutusData) f.get(1)).getValue(), ((BytesPlutusData) f.get(2)).getValue());
            default -> throw new JoustException("unknown MPF proof step " + c.getAlternative());
        };
    }

    // ------------------------------------------------------------------ records

    static List<Integer> mapComponents(Class<?> stateClass) {
        var out = new ArrayList<Integer>();
        if (!stateClass.isRecord()) return out;   // a described contract's state is Plutus data (ADR-0015 D4)
        RecordComponent[] cs = stateClass.getRecordComponents();
        for (int i = 0; i < cs.length; i++) if (cs[i].getType() == MerkleMap.class) out.add(i);
        return out;
    }

    private static Object component(Object record, int index) {
        try {
            return record.getClass().getRecordComponents()[index].getAccessor().invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new JoustException("cannot read state component " + index, e);
        }
    }

    static <S> S opened(Class<S> stateClass, S state, List<Integer> maps, List<JulcList<MapEntry>> reads) {
        RecordComponent[] cs = stateClass.getRecordComponents();
        Object[] values = new Object[cs.length];
        Class<?>[] types = new Class<?>[cs.length];
        for (int i = 0; i < cs.length; i++) {
            types[i] = cs[i].getType();
            values[i] = component(state, i);
        }
        for (int j = 0; j < maps.size(); j++) {
            MerkleMap pre = (MerkleMap) values[maps.get(j)];
            values[maps.get(j)] = new MerkleMap(pre.root(), reads.get(j), JulcList.empty());
        }
        try {
            var ctor = stateClass.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new JoustException("cannot rebuild " + stateClass.getSimpleName(), e);
        }
    }

    static <S> S withRoots(Class<S> stateClass, S state, List<Integer> maps, byte[][] roots) {
        RecordComponent[] cs = stateClass.getRecordComponents();
        Object[] values = new Object[cs.length];
        Class<?>[] types = new Class<?>[cs.length];
        for (int i = 0; i < cs.length; i++) {
            types[i] = cs[i].getType();
            values[i] = component(state, i);
        }
        JulcList<MapEntry> none = JulcList.empty();
        for (int j = 0; j < maps.size(); j++) values[maps.get(j)] = new MerkleMap(roots[j], none, none);
        try {
            var ctor = stateClass.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new JoustException("cannot rebuild " + stateClass.getSimpleName(), e);
        }
    }

    private static byte[] normalize(byte[] root) {
        return root == null || root.length == 0 ? Mpf.emptyRoot() : root;
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
