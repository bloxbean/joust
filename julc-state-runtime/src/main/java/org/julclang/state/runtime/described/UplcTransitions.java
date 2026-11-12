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
 * (Plutus V3, PV11) and within the ledger's per-transaction budget.
 * <p>
 * A failed run is classified by its tag (ADR-0020 D9, adopted in §11.4): the toolchain compiles the programs with a
 * {@code Require} that traces {@code joust:require <message>} before failing, so a run whose traces hold that tag is the
 * contract's refusal, with the tagged message (the last one, the requirement that failed). Any other failure (a builtin
 * error, a failed decode, an {@code error} outside {@code Require}) is an error, reported with the VM's cause; a trace
 * is never taken as a refusal message. Contract code can trace any text, so a forged tag can only change which text a
 * failure shows: the step, and so the transaction, never depends on the classification.
 */
public final class UplcTransitions implements TransitionFn {

    private static final JulcVm VM = JulcVm.create();

    /** The trace a transition program's {@code Require} writes before failing: this prefix, then the message. */
    public static final String REQUIRE_TAG = "joust:require ";

    private final Map<String, org.julclang.core.Program> decoded;

    /**
     * Programs (script CBOR hex) by method name, decoded here once: the instance is then read-only, so concurrent calls
     * may share it (ADR-0020 D11).
     */
    public UplcTransitions(Map<String, String> programs) {
        var d = new HashMap<String, org.julclang.core.Program>();
        programs.forEach((m, hex) -> d.put(m, JulcScriptAdapter.toProgram(hex)));
        this.decoded = Map.copyOf(d);
    }

    @Override
    public PlutusData apply(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        Run r = run(method, target, shared, args, ctx);
        if (r.step() == null) throw new RequireException(r.refusal());
        return r.step();
    }

    @Override
    public Run run(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        var program = decoded.get(method);
        if (program == null) throw new IllegalArgumentException("no transition program for " + method);
        EvalResult r = VM.evaluateWithArgs(program, LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                List.of(target, shared, args, ctx), new ExBudget(10_000_000_000L, 14_000_000L), EvalOptions.DEFAULT);
        if (r instanceof EvalResult.Success s) return new Run(TermExtractor.extractData(s.resultTerm()), null, s.traces());
        if (r instanceof EvalResult.BudgetExhausted b) return new Run(null, method + ": over the execution budget", b.traces());
        var f = (EvalResult.Failure) r;
        return new Run(null, refusal(method, f.traces(), f.error()), f.traces());
    }

    /**
     * A failed run's message: the contract's refusal (the last {@link #REQUIRE_TAG} trace's message), or, with no tag,
     * an error with the VM's cause.
     */
    static String refusal(String method, List<String> traces, String cause) {
        String required = null;
        for (String t : traces) if (t.startsWith(REQUIRE_TAG)) required = t.substring(REQUIRE_TAG.length());
        if (required != null) return required;
        return method + " failed: " + (cause == null || cause.isBlank() ? "the program failed" : cause);
    }
}
