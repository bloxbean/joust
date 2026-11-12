package org.julclang.state.runtime.wasm;

import org.julclang.core.PlutusData;
import org.julclang.state.api.RequireException;
import org.julclang.state.runtime.described.TransitionFn;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * ADR-0020 D3/D9: runs the program ({@link UplcRunner}) and the JVM ({@link JvmTransitions}) on the same inputs.
 * While either side reports {@code joust:read} needs, it returns the union of both sides' needs to
 * {@code DescribedContract.discover} and compares nothing (provisional rounds legitimately differ). When both are
 * final it compares their outcome classes and steps (julc-core encoding, unsorted); a difference sets the per-call
 * {@link Latch} and fails the call with a short error. The program's step is the one used.
 */
final class ShadowTransitions implements TransitionFn {

    /** {@code DescribedContract.MAX_READ_ROUNDS} (ADR-0015 D3). */
    static final int MAX_ROUNDS = 4;

    final String contract;
    final UplcRunner uplc;
    final JvmTransitions jvm;
    int compared;

    ShadowTransitions(String contract, UplcRunner uplc, JvmTransitions jvm) {
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
        Latch.State s = Latch.current();
        int round = s == null ? 1 : ++s.rounds;
        if (!p.isFinal() || !j.isFinal()) {
            if (round >= MAX_ROUNDS && p.isFinal() != j.isFinal()) {
                fail(method, "round-limit on one side only: program " + (p.isFinal() ? "final" : "needs " + p.needs())
                        + ", JVM " + (j.isFinal() ? "final" : "needs " + j.needs()), p, j, target, args, ctx);
            }
            var traces = new LinkedHashSet<String>(p.needs());
            traces.addAll(j.needs());
            return new Run(p.step(), p.kind() == Outcome.Kind.STEP ? null : p.message(), new ArrayList<>(traces));
        }
        if (s != null) s.rounds = 0;   // a final run ends this transition's rounds
        compared++;
        Report.comparison(contract, method, p, j);
        if (!p.comparable().equals(j.comparable())) {
            fail(method, "program " + p + " vs JVM " + j, p, j, target, args, ctx);
        }
        return new Run(p.step(), p.kind() == Outcome.Kind.STEP ? null : p.message(), p.traces());
    }

    private void fail(String method, String why, Outcome p, Outcome j, PlutusData target, PlutusData args, PlutusData ctx) {
        Latch.mismatch(contract + "." + method + ": " + why + "\n  target " + target + "\n  args " + args + "\n  ctx " + ctx);
        throw new Latch.Latched("shadow mismatch in " + contract + "." + method);
    }

    static List<String> needsOf(List<String> traces) {
        return traces.stream().filter(t -> t.startsWith("joust:read ")).toList();
    }
}
