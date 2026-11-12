package org.julclang.state.runtime.wasm;

import org.julclang.state.runtime.described.TransitionFn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ADR-0020 D4(a): the DevKit leg. The {@code ShadowDescribed*DevnetTest} classes are copies of
 * julc-state-runtime-wasm's {@code Described*DevnetTest} with {@link ShadowTransitions} in place of
 * {@code UplcTransitions}: every transition of those live flows runs as the program and on the JVM, and the chain
 * accepts (or refuses) what the program computed.
 */
final class ShadowDevKit {
    private ShadowDevKit() {}

    /** Every shadow mismatch seen by a call that is not inside a latched prepare. */
    static final List<String> MISMATCHES = Collections.synchronizedList(new ArrayList<>());
    static final AtomicInteger SHADOWED = new AtomicInteger();

    static Prepare.Contract contract(String example) throws Exception {
        return Spike.prepareContract(Spike.build(example).contracts().get(0));
    }

    static Prepare.Contract contract(String example, String simple) throws Exception {
        for (var c : Spike.build(example).contracts()) if (c.name().equals(simple)) return Spike.prepareContract(c);
        throw new IllegalArgumentException(example + " has no " + simple);
    }

    static TransitionFn shadow(Prepare.Contract c) {
        SHADOWED.incrementAndGet();
        return Spike.fn(Spike.Mode.SHADOW, c);
    }

    static TransitionFn shadow(org.julclang.state.toolchain.Toolchain.Contract c) {
        return shadow(Spike.prepareContract(c));
    }

    static void assertNoMismatch() {
        var copy = new ArrayList<>(MISMATCHES);
        MISMATCHES.clear();
        var body = Report.JSON.createObjectNode();
        body.put("mismatches", copy.size());
        body.set("comparisons", Report.JSON.valueToTree(Report.COUNTS));
        Report.write("devkit-shadow", body);
        if (!copy.isEmpty()) {
            Report.mismatches(copy);
            throw new AssertionError("shadow mismatches in a DevKit flow: " + copy.size() + " (build/spike/mismatches.txt)");
        }
    }

    @SuppressWarnings("unused")
    private static Map<String, String> none() {
        return Map.of();
    }
}
