package org.julclang.state.runtime.wasm;

import org.julclang.core.PlutusData;
import org.julclang.state.api.RequireException;
import org.julclang.state.runtime.described.TransitionFn;
import org.julclang.state.runtime.described.UplcTransitions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ADR-0020 D3/D9, the comparison mode: runs the program (the engine's {@link UplcTransitions}) and the JVM
 * ({@link JvmTransitions}) on the same inputs. While either side reports {@code joust:read} needs, it returns the union
 * of both sides' needs to the read discovery and compares nothing (provisional rounds may differ). When both are final
 * it compares their outcome classes and steps; a difference is recorded in {@link #MISMATCHES} and fails the call. The
 * program's step is the one used. A test asserts {@link #MISMATCHES} empty after its calls: a batch skips an intent
 * whose call fails, so a failed call alone could go unseen.
 */
final class ShadowTransitions implements TransitionFn {

    /** One side of the comparison. */
    interface Side {
        Outcome outcome(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx);
    }

    /** The program side: the engine's own transitions, classified as the engine does. */
    record Programs(UplcTransitions programs) implements Side {
        @Override
        public Outcome outcome(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
            return Outcome.of(programs.run(method, target, shared, args, ctx));
        }
    }

    /** Every mismatch since the last {@link #reset}. */
    static final List<String> MISMATCHES = java.util.Collections.synchronizedList(new ArrayList<>());
    /** Final comparisons by contract.method. */
    static final Map<String, Integer> COMPARED = java.util.Collections.synchronizedMap(new TreeMap<>());

    static void reset() {
        MISMATCHES.clear();
        COMPARED.clear();
    }

    final String contract;
    final Side uplc;
    final Side jvm;

    ShadowTransitions(String contract, Side uplc, Side jvm) {
        this.contract = contract;
        this.uplc = uplc;
        this.jvm = jvm;
    }

    @Override
    public PlutusData apply(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        Run r = run(method, target, shared, args, ctx);
        if (r.step() == null) throw new RequireException(r.refusal());
        return r.step();
    }

    @Override
    public Run run(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        Outcome p = uplc.outcome(method, target, shared, args, ctx);
        Outcome j = jvm.outcome(method, target, shared, args, ctx);
        if (!p.isFinal() || !j.isFinal()) {
            var traces = new LinkedHashSet<String>(p.needs());
            traces.addAll(j.needs());
            return new Run(p.step(), p.kind() == Outcome.Kind.STEP ? null : p.message(), new ArrayList<>(traces));
        }
        COMPARED.merge(contract + "." + method, 1, Integer::sum);
        if (!p.comparable().equals(j.comparable())) {
            MISMATCHES.add(contract + "." + method + ": program " + p + " vs JVM " + j + "\n  target " + target + "\n  args " + args
                    + "\n  ctx " + ctx);
            throw new IllegalStateException("comparison mismatch in " + contract + "." + method);
        }
        return new Run(p.step(), p.kind() == Outcome.Kind.STEP ? null : p.message(), p.traces());
    }
}
