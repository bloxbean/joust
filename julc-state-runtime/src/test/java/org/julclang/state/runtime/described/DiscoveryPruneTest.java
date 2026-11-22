package org.julclang.state.runtime.described;

import org.julclang.state.api.MapEntry;
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
        assertEquals(List.of(W, A), keys(path));
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
        var path = DescribedContract.path(known, Set.of(), k -> run(k, program));
        assertEquals(List.of(A, B, C), keys(path));
    }

    @Test void aFailureIsRaisedOnlyWhenAsked() {
        var r = new TransitionFn.Run(null, "x", List.of(), new IllegalStateException("boom"));
        assertThrows(IllegalStateException.class, r::raise);
        new TransitionFn.Run(null, "x", List.of()).raise();
    }
}
