package org.julclang.state.runtime.described;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.core.PlutusData;
import org.julclang.state.api.RequireException;
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
 * Transitions as the toolchain's transition programs (ADR-0015 D3), evaluated by JuLC's VM at the ledger's semantics
 * (Plutus V3, PV11) and within the ledger's per-transaction budget. A failed run is the contract's refusal: its message
 * is the last trace that is not a discovery trace.
 */
public final class UplcTransitions implements TransitionFn {

    private static final JulcVm VM = JulcVm.create();

    private final Map<String, String> programs;
    private final Map<String, org.julclang.core.Program> decoded = new HashMap<>();

    /** Programs (script CBOR hex) by method name. */
    public UplcTransitions(Map<String, String> programs) {
        this.programs = Map.copyOf(programs);
    }

    @Override
    public PlutusData apply(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        var program = decoded.computeIfAbsent(method, m -> {
            String hex = programs.get(m);
            if (hex == null) throw new IllegalArgumentException("no transition program for " + m);
            return JulcScriptAdapter.toProgram(hex);
        });
        EvalResult r = VM.evaluateWithArgs(program, LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                List.of(target, shared, args, ctx), new ExBudget(10_000_000_000L, 14_000_000L), EvalOptions.DEFAULT);
        if (r instanceof EvalResult.Success s) return TermExtractor.extractData(s.resultTerm());
        List<String> traces = r instanceof EvalResult.Failure f ? f.traces() : ((EvalResult.BudgetExhausted) r).traces();
        String message = method + " refused";
        for (String t : traces) if (!t.startsWith("joust:")) message = t;
        if (r instanceof EvalResult.BudgetExhausted) message = method + ": over the execution budget";
        throw new RequireException(message);
    }
}
