package org.julclang.state.runtime.described;

import org.julclang.state.api.MapEntry;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The npm host's MerkleMap read discovery walks the path one read per run, in the JVM host's lookup order, with no
 * limit on rounds (#160 review M-1).
 */
class DiscoveryWalkTest {
    static final String ROOT = "aa".repeat(32), A = "0a", B = "0b", C = "0c", D = "0d", E = "0e";

    /** Aborts a run (a failed Require): the lookup that guards it was unknown. */
    static final class Abort extends RuntimeException {}

    /**
     * A program over one map. A lookup {@code known} lacks is reported and answered absent; {@code guarded} lookups then
     * abort the run, as a {@code Require} on the answer does.
     */
    static TransitionFn.Run run(Map<String, List<MapEntry>> known, Function<Function<String, Boolean>, Void> program, boolean guarded) {
        var have = new HashMap<String, MapEntry>();
        for (var e : known.getOrDefault(ROOT, List.of())) have.put(HexFormat.of().formatHex(e.key()), e);
        var traces = new ArrayList<String>();
        try {
            program.apply(k -> {
                var e = have.get(k);
                if (e != null) return e.present();
                traces.add("joust:read " + ROOT + " " + k);
                if (guarded) throw new Abort();
                return false;
            });
        } catch (Abort refused) {
            return new TransitionFn.Run(null, "not a member", traces);
        }
        return new TransitionFn.Run(org.julclang.core.PlutusData.integer(java.math.BigInteger.ONE), null, traces);
    }

    static List<String> keys(Map<String, List<MapEntry>> m) {
        return m.getOrDefault(ROOT, List.of()).stream().map(e -> HexFormat.of().formatHex(e.key())).toList();
    }

    static Map<String, List<MapEntry>> start() {
        var known = new LinkedHashMap<String, List<MapEntry>>();
        known.put(ROOT, new ArrayList<>());
        return known;
    }

    @Test void fiveGuardedLookupsTakeOneRunEach() {
        // require(get(A)); require(get(B)); ... five in a row: each run stops at its first unknown key.
        Function<Function<String, Boolean>, Void> program = get -> {
            for (String k : List.of(A, B, C, D, E)) if (!get.apply(k)) throw new Abort();
            return null;
        };
        var known = start();
        var read = new ArrayList<String>();
        var runs = new int[1];
        var r = DescribedContract.walk(known, k -> { runs[0]++; return run(k, program, true); },
                (root, key) -> { read.add(key); return new MapEntry(HexFormat.of().parseHex(key), true, new byte[0]); }, "vote");
        assertNotNull(r.step(), r.refusal());
        assertEquals(List.of(A, B, C, D, E), read);
        assertEquals(List.of(A, B, C, D, E), keys(known));
        assertEquals(6, runs[0], "one run per read and the final one");
    }

    @Test void aReadOffThePathIsNeverMade() {
        // A; B only when A is absent. The first run answers A as absent and also reports B; A is present on chain.
        Function<Function<String, Boolean>, Void> program = get -> {
            if (!get.apply(A)) get.apply(B);
            return null;
        };
        var known = start();
        var read = new ArrayList<String>();
        DescribedContract.walk(known, k -> run(k, program, false),
                (root, key) -> { read.add(key); return new MapEntry(HexFormat.of().parseHex(key), true, new byte[0]); }, "t");
        assertEquals(List.of(A), read);
    }

    @Test void readsFollowTheJvmsLookupOrder() {
        // A; B only when A is present; then always C.
        Function<Function<String, Boolean>, Void> program = get -> {
            if (get.apply(A)) get.apply(B);
            get.apply(C);
            return null;
        };
        var known = start();
        var r = DescribedContract.walk(known, k -> run(k, program, false),
                (root, key) -> new MapEntry(HexFormat.of().parseHex(key), true, new byte[0]), "t");
        assertEquals(List.of(A, B, C), keys(known));
        assertTrue(r.traces().isEmpty());
    }

    @Test void aFailureIsRaisedOnlyWhenAsked() {
        var r = new TransitionFn.Run(null, "x", List.of(), new IllegalStateException("boom"));
        assertThrows(IllegalStateException.class, r::raise);
        new TransitionFn.Run(null, "x", List.of()).raise();
    }
}
