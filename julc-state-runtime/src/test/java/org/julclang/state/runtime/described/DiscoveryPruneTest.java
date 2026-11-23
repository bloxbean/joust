package org.julclang.state.runtime.described;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.MapEntry;
import org.julclang.state.api.mpf.Mpf;
import org.julclang.state.runtime.InMemoryNodeStore;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.MerkleSupport;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #160 Codex review: the npm host's discovery keeps only the reads the final path reaches, in the order it reaches
 * them, as the JVM host (running the method itself) records them.
 */
class DiscoveryPruneTest {
    static final String ROOT = "aa".repeat(32), A = "0a", B = "0b", C = "0c", W = "0d";

    static MapEntry entry(String key, boolean present) {
        return new MapEntry(HexFormat.of().parseHex(key), present, new byte[0]);
    }

    /** A program over one map: each lookup the {@code program} makes that {@code known} lacks is reported, in order. */
    static TransitionFn.Run run(Map<String, List<MapEntry>> known, java.util.function.Function<java.util.function.Function<String, Boolean>, Void> program) {
        var have = new HashMap<String, MapEntry>();
        for (var e : known.getOrDefault(ROOT, List.of())) have.put(HexFormat.of().formatHex(e.key()), e);
        var traces = new ArrayList<String>();
        program.apply(k -> {
            var e = have.get(k);
            if (e == null) { traces.add("joust:read " + ROOT + " " + k); return false; }
            return e.present();
        });
        return new TransitionFn.Run(null, "step", traces);
    }

    static List<String> keys(Map<String, List<MapEntry>> m) {
        return m.getOrDefault(ROOT, List.of()).stream().map(e -> HexFormat.of().formatHex(e.key())).toList();
    }

    @Test void aReadOffTheFinalPathIsDropped() {
        // W (witnessed), then A; B only when A is absent. The first round answered A as absent, so it also found B.
        java.util.function.Function<java.util.function.Function<String, Boolean>, Void> program = get -> {
            get.apply(W);
            if (!get.apply(A)) get.apply(B);
            return null;
        };
        var known = new LinkedHashMap<String, List<MapEntry>>();
        known.put(ROOT, new ArrayList<>(List.of(entry(W, true), entry(A, true), entry(B, false))));
        var path = DescribedContract.path(known, Set.of(ROOT + " " + W), k -> run(k, program));
        assertEquals(List.of(W, A), keys(path.reads()));
    }

    @Test void thePathKeepsTheJvmsLookupOrder() {
        // A; B only when A is present; then always C. Discovery found A and C first (A unknown, so absent), then B.
        java.util.function.Function<java.util.function.Function<String, Boolean>, Void> program = get -> {
            if (get.apply(A)) get.apply(B);
            get.apply(C);
            return null;
        };
        var known = new LinkedHashMap<String, List<MapEntry>>();
        known.put(ROOT, new ArrayList<>(List.of(entry(A, true), entry(C, false), entry(B, true))));
        var runs = new int[1];
        var path = DescribedContract.path(known, Set.of(), k -> { runs[0]++; return run(k, program); });
        assertEquals(List.of(A, B, C), keys(path.reads()));
        // #160 Codex re-review: one run per read and the terminal one, which the caller reuses rather than repeats.
        assertEquals(4, runs[0]);
        assertTrue(path.run().traces().isEmpty());
    }

    @Test void aSpeculativeReadOfAColdRootFetchesNoHistory() {
        // #160 Codex re-review: A is present in a warm map; the tentative run answered it absent and so also read B
        // in a cold map. Only A is on the path: B must not trigger a history read (a provider without one would end a
        // prepare the JVM host serves from the warm map alone).
        var store = new InMemoryNodeStore();
        byte[] key = HexFormat.of().parseHex(A);
        byte[] warm = new MerkleSupport.TrieProver(store).write(Mpf.emptyRoot(),
                JulcList.<MapEntry>empty().prepend(new MapEntry(key, true, new byte[] {1}))).root();
        String cold = "bb".repeat(32), warmHex = HexFormat.of().formatHex(warm);
        var histories = new ArrayList<String>();
        var prover = MerkleSupport.TrieProver.withHistory(store, root -> histories.add(HexFormat.of().formatHex(root)));
        var known = new LinkedHashMap<String, List<MapEntry>>();
        boolean fresh = new MerkleSupport(prover).run(() -> DescribedContract.resolve(
                List.of(new String[] {warmHex, A}, new String[] {cold, B}), known));
        assertTrue(fresh);
        assertEquals(List.of(), histories, "no history for a read under a tentative answer");
        assertEquals(List.of(A), known.get(warmHex).stream().map(e -> HexFormat.of().formatHex(e.key())).toList());
        assertTrue(known.get(warmHex).get(0).present());
        assertTrue(known.getOrDefault(cold, List.of()).isEmpty());
        // Once B is the path's next read, its root's history is read.
        assertThrows(JoustException.class, () -> new MerkleSupport(prover).run(() -> DescribedContract.resolve(
                List.<String[]>of(new String[] {cold, B}), known)));
        assertEquals(List.of(cold), histories);
    }

    @Test void eachMissingRootAsksForItsHistoryOnce() {
        // #160 Codex re-review: two cold roots of one call; the second is asked for, not refused as a generic miss.
        var asked = new ArrayList<String>();
        var prover = MerkleSupport.TrieProver.withHistory(new InMemoryNodeStore(), root -> asked.add(HexFormat.of().formatHex(root)));
        byte[] first = HexFormat.of().parseHex(ROOT), second = HexFormat.of().parseHex("bb".repeat(32)), key = HexFormat.of().parseHex(A);
        assertThrows(JoustException.class, () -> prover.read(first, key));
        assertThrows(JoustException.class, () -> prover.read(second, key));
        assertThrows(JoustException.class, () -> prover.read(first, key));
        assertEquals(List.of(ROOT, "bb".repeat(32)), asked);
    }

    @Test void aFailureIsRaisedOnlyWhenAsked() {
        var r = new TransitionFn.Run(null, "x", List.of(), new IllegalStateException("boom"));
        assertThrows(IllegalStateException.class, r::raise);
        new TransitionFn.Run(null, "x", List.of()).raise();
    }
}
