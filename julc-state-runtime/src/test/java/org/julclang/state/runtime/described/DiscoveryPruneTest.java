package org.julclang.state.runtime.described;

import org.julclang.state.api.MapEntry;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #160 Codex review: a method that reads B only when A is absent. The first discovery run answers A as absent and so
 * also reports B; once A is known present the final run never reads B, and B's proof must not join the signed claims
 * (the JVM host, running the method itself, reads only A).
 */
class DiscoveryPruneTest {
    static final String ROOT = "aa".repeat(32), A = "0a", B = "0b", W = "0c";

    /** The program: looks up W (witnessed), then A; only if A is absent, B. Reports each key missing from {@code known}. */
    static TransitionFn.Run run(Map<String, List<MapEntry>> known) {
        var have = new HashMap<String, MapEntry>();
        for (var e : known.getOrDefault(ROOT, List.of())) have.put(HexFormat.of().formatHex(e.key()), e);
        var traces = new ArrayList<String>();
        for (String k : List.of(W, A)) if (!have.containsKey(k)) traces.add("joust:read " + ROOT + " " + k);
        var a = have.get(A);
        if (a == null || !a.present()) if (!have.containsKey(B)) traces.add("joust:read " + ROOT + " " + B);
        return new TransitionFn.Run(null, "step", traces);
    }

    static MapEntry entry(String key, boolean present) {
        return new MapEntry(HexFormat.of().parseHex(key), present, new byte[0]);
    }

    @Test void aReadOffTheFinalPathIsDropped() {
        var known = new LinkedHashMap<String, List<MapEntry>>();
        known.put(ROOT, new ArrayList<>(List.of(entry(W, true), entry(A, true), entry(B, false))));
        assertTrue(DescribedContract.prune(known, Set.of(ROOT + " " + W), DiscoveryPruneTest::run));
        assertEquals(List.of(W, A), known.get(ROOT).stream().map(e -> HexFormat.of().formatHex(e.key())).toList());
        assertTrue(run(known).traces().isEmpty(), "the pruned reads still answer every lookup");
    }

    @Test void readsOnThePathAndWitnessedReadsStay() {
        var known = new LinkedHashMap<String, List<MapEntry>>();
        known.put(ROOT, new ArrayList<>(List.of(entry(W, true), entry(A, false), entry(B, true))));   // A absent: B is read
        assertFalse(DescribedContract.prune(known, Set.of(ROOT + " " + W), DiscoveryPruneTest::run));
        assertEquals(3, known.get(ROOT).size());
    }

    @Test void aFailureIsRaisedOnlyWhenAsked() {
        var r = new TransitionFn.Run(null, "x", List.of(), new IllegalStateException("boom"));
        assertThrows(IllegalStateException.class, r::raise);
        new TransitionFn.Run(null, "x", List.of()).raise();
    }
}
