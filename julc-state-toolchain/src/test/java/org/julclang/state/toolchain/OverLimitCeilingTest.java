package org.julclang.state.toolchain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The five-way target's over-limit ceiling (#123, review r4 Codex C15): one population, the distinct programs seen,
 * oversized ones included, for both the share and the 100-program threshold.
 */
class OverLimitCeilingTest {

    /** Codex's table: deployable, oversized, and whether the run must fail. */
    @Test
    void theCeilingCountsOneDistinctPopulation() {
        long[][] rows = {
                {0, 100, 1},    // all oversized: fails (it passed before, with 0 "built")
                {97, 3, 1},     // 3 of 100: fails (passed before: 97 built, under the threshold)
                {99, 100, 1},   // 100 of 199: fails
                {100, 2, 0},    // 2 of 102: passes
                {100, 3, 1},    // 3 of 103: fails
                {148, 3, 0},    // 3 of 151, 1.99%: passes (failed before, against 148 built)
                {98, 1, 0},     // 99 programs: under the threshold
                {98, 2, 0},     // 2 of 100, exactly 2%: passes
                {97, 3, 1},     // 3 of 100: fails
        };
        for (long[] r : rows) {
            long total = r[0] + r[1];
            String v = GeneratedContracts.Population.verdict(total, r[1]);
            assertEquals(r[2] == 1, v != null, r[0] + " deployable, " + r[1] + " oversized: " + v);
        }
    }

    /** Revisits, rebuilds after the cache evicts a program, and oversized programs seen again count once. */
    @Test
    void revisitsEvictionsAndRepeatsCountOnce() {
        var p = new GeneratedContracts.Population();
        for (int i = 0; i < 20; i++) p.seen("k" + i);   // more than the cache keeps (16)
        p.seen("k0");                                     // rebuilt after eviction
        p.seen("k19");                                    // a cache hit
        p.over("big");
        p.over("big");                                    // the cached refusal, thrown again
        p.seen("big");
        assertEquals(21, p.total());
        assertEquals(1, p.over());
        for (int i = 0; i < 3; i++) p.over("big" + i);
        // 4 oversized of 24 distinct programs: under the 100 threshold, so no verdict yet.
        assertNull(GeneratedContracts.Population.verdict(p.total(), p.over()));
        for (int i = 20; i < 100; i++) p.seen("k" + i);
        // 4 of 104: more than 2%.
        assertEquals(104, p.total());
        assertNotNull(GeneratedContracts.Population.verdict(p.total(), p.over()));
    }
}
