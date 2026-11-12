package org.julclang.state.runtime.wasm;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.state.api.RequireException;
import org.julclang.state.runtime.described.TransitionFn;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.JulcVm;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.TermExtractor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0020 D9/D11: the transition programs evaluated as {@code UplcTransitions} evaluates them (PV11, Plutus V3, the
 * same per-evaluation cap: the spike configuration), returning the structured {@link Outcome} with units, and charging
 * a cumulative per-call limit ({@link Latch}). A failure's class: {@code require} when its last non-{@code joust:}
 * trace is a message (the engine's rule; the auxiliary tagged-{@code Require} classification is not built in this
 * spike, see the report), {@code error} when there is none, {@code budget} when the cap is exhausted.
 */
final class UplcRunner implements TransitionFn, ShadowTransitions.Side {

    static final ExBudget CAP = new ExBudget(10_000_000_000L, 14_000_000L);
    static final LedgerEvaluationTarget TARGET = LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3);
    private static final JulcVm VM = JulcVm.create();

    private final Map<String, Program> programs = new HashMap<>();

    UplcRunner(Map<String, String> programHex) {
        programHex.forEach((m, hex) -> programs.put(m, JulcScriptAdapter.toProgram(hex)));
    }

    @Override
    public PlutusData apply(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        Outcome o = outcome(method, target, shared, args, ctx);
        if (o.kind() == Outcome.Kind.STEP) return o.step();
        throw new RequireException(o.message());
    }

    @Override
    public Run run(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        Outcome o = outcome(method, target, shared, args, ctx);
        return new Run(o.kind() == Outcome.Kind.STEP ? o.step() : null,
                o.kind() == Outcome.Kind.STEP ? null : o.message(), o.traces());
    }

    @Override
    public Outcome outcome(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        Program p = programs.get(method);
        if (p == null) throw new IllegalArgumentException("no transition program for " + method + " (ADR-0020 U7)");
        long t0 = System.nanoTime();
        EvalResult r = VM.evaluateWithArgs(p, TARGET, List.of(target, shared, args, ctx), CAP, EvalOptions.DEFAULT);
        long nanos = System.nanoTime() - t0;
        var used = r.budgetConsumed();
        Latch.charge(used.cpuSteps(), used.memoryUnits());
        if (r instanceof EvalResult.Success s) {
            return new Outcome(Outcome.Kind.STEP, TermExtractor.extractData(s.resultTerm()), null, s.traces(),
                    used.cpuSteps(), used.memoryUnits(), nanos);
        }
        if (r instanceof EvalResult.BudgetExhausted b) {
            return new Outcome(Outcome.Kind.BUDGET, null, method + ": over the execution budget", b.traces(),
                    used.cpuSteps(), used.memoryUnits(), nanos);
        }
        var f = (EvalResult.Failure) r;
        String message = null;
        for (String t : f.traces()) if (!t.startsWith("joust:")) message = t;
        return new Outcome(message == null ? Outcome.Kind.ERROR : Outcome.Kind.REQUIRE, null,
                message == null ? method + " refused (" + f.error() + ")" : message, f.traces(), used.cpuSteps(),
                used.memoryUnits(), nanos);
    }
}
