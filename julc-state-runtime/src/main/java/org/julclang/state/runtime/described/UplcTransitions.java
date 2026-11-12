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
 * is never taken as a refusal message, and a run over the budget is a budget failure whatever it traced. Contract code
 * can trace any text, so a forged tag can make a failure show text the contract chose: the step, and so the
 * transaction, never depends on the classification, and no decision is taken on the refusal's text (a keyed call's
 * cooldown reaches the client as a structured field of the engine's result, ADR-0020 §12.1).
 * <p>
 * Instances come only from {@link #verified}: the programs' manifest is checked first (ADR-0020 D8, U3), on every path
 * that runs transition programs, the engine's ({@code Prepare}) and any JVM caller's.
 */
public final class UplcTransitions implements TransitionFn {

    private static final JulcVm VM = JulcVm.create();

    /** The trace a transition program's {@code Require} writes before failing: this prefix, then the message. */
    public static final String REQUIRE_TAG = "joust:require ";

    private final Map<String, org.julclang.core.Program> decoded;

    /** How a run ended (ADR-0020 D9), from the VM's result: never from a trace, except the refusal's tag. */
    public enum Kind { STEP, REFUSAL, ERROR, BUDGET }

    /** One run with its kind. */
    public record Evaluation(Kind kind, Run run) {}

    /**
     * The programs (script CBOR hex, by transition) of a contract built by the toolchain, once their manifest matches the
     * description and the unapplied script ({@link ProgramManifest#check}): the only way to get an instance. Throws
     * {@link IllegalArgumentException} with every mismatch and the remedy.
     */
    public static UplcTransitions verified(String manifest, String description, String scriptCborHex, Map<String, String> programs) {
        var p = programs == null ? Map.<String, String>of() : programs;
        ProgramManifest.check(manifest, description, scriptCborHex, p);
        return new UplcTransitions(p);
    }

    /**
     * Programs (script CBOR hex) by method name, decoded here once: the instance is then read-only, so concurrent calls
     * may share it (ADR-0020 D11). Unchecked: {@link #verified} is the public way in.
     */
    UplcTransitions(Map<String, String> programs) {
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
        return evaluate(method, target, shared, args, ctx).run();
    }

    /** One run, with how it ended: a budget exhaustion is {@code BUDGET} even after a tagged trace (C4). */
    public Evaluation evaluate(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        var program = decoded.get(method);
        if (program == null) {
            throw new IllegalArgumentException("no transition program for " + method
                    + (decoded.isEmpty() ? " (the contract was built without transition programs: joust.transitions=false)" : ""));
        }
        EvalResult r = VM.evaluateWithArgs(program, LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3),
                List.of(target, shared, args, ctx), new ExBudget(10_000_000_000L, 14_000_000L), EvalOptions.DEFAULT);
        if (r instanceof EvalResult.Success s) {
            return new Evaluation(Kind.STEP, new Run(TermExtractor.extractData(s.resultTerm()), null, s.traces()));
        }
        if (r instanceof EvalResult.BudgetExhausted b) {
            return new Evaluation(Kind.BUDGET, new Run(null, method + ": over the execution budget", b.traces()));
        }
        if (r instanceof EvalResult.Failure f) {
            String required = required(f.traces());
            return required != null
                    ? new Evaluation(Kind.REFUSAL, new Run(null, required, f.traces()))
                    : new Evaluation(Kind.ERROR, new Run(null, method + " failed: "
                            + (f.error() == null || f.error().isBlank() ? "the program failed" : f.error()), f.traces()));
        }
        return new Evaluation(Kind.ERROR, new Run(null, method + " failed: " + r, List.of()));
    }

    /** The last {@link #REQUIRE_TAG} trace's message, or null. */
    static String required(List<String> traces) {
        String required = null;
        for (String t : traces) if (t.startsWith(REQUIRE_TAG)) required = t.substring(REQUIRE_TAG.length());
        return required;
    }
}
