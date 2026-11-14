package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.JscModules;
import org.julclang.state.dsl.v2.JscV2;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The five-way fuzz test's generator is valid (JuLC ADR-063 H6): every program it emits passes the {@code .jsc} 2
 * checker and lowers, for 500 seeded programs. A program the checker refuses is a generator bug, not a finding, and would
 * make the fuzz test spend its time on nothing; the fuzz test also fails on one ({@link FiveWayFuzzTest}). The features
 * are counted, so a grammar change that stops producing one shows here.
 */
class JscGenTest {

    @Test
    void everyGeneratedProgramChecksAndLowers() {
        var failures = new ArrayList<String>();
        var features = new TreeMap<String, Integer>();
        int programs = 500;
        for (int seed = 0; seed < programs; seed++) {
            var plan = JscGen.generate(JscGen.of(new SplittableRandom(seed)));
            plan.features().forEach(f -> features.merge(f, 1, Integer::sum));
            for (var st : plan.steps()) {
                if (st.window() != null) features.merge("call: a window of its own", 1, Integer::sum);
                if (!st.cosigners().isEmpty()) features.merge("call: co-signers", 1, Integer::sum);
                if (st.mutants()) features.merge("call: mutants and attacks", 1, Integer::sum);
                if (st.offered() != null && !st.offered().entries().isEmpty()) features.merge("call: native assets offered", 1, Integer::sum);
                if (st.args().stream().anyMatch(x -> x instanceof org.julclang.state.dsl.v2.Interpreter.MapV)) {
                    features.merge("call: a map argument", 1, Integer::sum);
                }
            }
            try {
                JscV2.lower(plan.source(), plan.name() + ".jsc", plan.contractFile(), JscModules.of(plan.files()));
            } catch (RuntimeException e) {
                if (failures.size() < 5) failures.add("seed " + seed + ": " + e.getMessage() + "\n" + plan.describe());
                else failures.add("seed " + seed + ": " + e.getMessage());
            }
        }
        System.out.println("[JscGen] " + programs + " programs, " + (programs - failures.size()) + " check and lower; features " + features);
        assertEquals(List.of(), failures, failures.size() + " generated programs do not check");
        // A quota per feature (review r1, design F2, F9): a grammar change that stops producing one fails here.
        for (String f : List.of("M4 library", "M2b params", "M2b invariant", "M2b events", "M2 values", "time", "enum", "record", "M1 map",
                "M2 assets (mint, send, accept)", "call: a window of its own", "call: co-signers", "call: mutants and attacks",
                "call: native assets offered", "call: a map argument")) {
            assertTrue(features.getOrDefault(f, 0) > 10, f + ": " + features);
        }
    }

    /**
     * H6 in full for a handful of seeds (review r1, design F13): the programs also build the two ways the five-way test
     * builds them (the toolchain, and javac with the processors), with byte-identical scripts.
     */
    @Test
    void generatedProgramsBuildBothWays() throws Exception {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
        int built = 0;
        for (int seed = 0; seed < 6; seed++) {
            GeneratedContracts.build(JscGen.generate(JscGen.of(new SplittableRandom(seed))));
            built++;
        }
        assertEquals(6, built);
    }
}
