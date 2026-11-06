package org.julclang.state.processor.ir;

import java.util.List;

/**
 * The State IR of one {@code @StateContract} class: everything the generators need, nothing javac-specific.
 * Generators read storage, execution and transaction-level validation from {@link #strategies()}; Option A only has
 * INLINE / DIRECT / SPEND, later layers add implementations (ADR-0001 D12).
 *
 * @param packageName  package of the contract (generated classes go there too)
 * @param name         simple name of the contract class
 * @param stateType    the state record, as source text relative to the contract package (e.g. {@code Counter.State})
 * @param stateComponents components of the state record, in order
 * @param minAda       min-ADA deposit held by the state UTxO, in lovelace
 * @param maxDatumBytes largest serialised state the validator accepts
 * @param maxValidityMs widest validity interval a Ctx-reading call may use
 * @param init         the {@code @Init} transition
 * @param actions      {@code @Action} transitions, in source order
 * @param destroys     {@code @Destroy} transitions, in source order
 * @param views        {@code @View} methods, in source order
 * @param imports      fully qualified names the generated code must import for the types above
 * @param strategies   how state is stored, executed and validated
 */
public record ContractIR(String packageName,
                         String name,
                         String stateType,
                         List<ParamIR> stateComponents,
                         long minAda,
                         int maxDatumBytes,
                         long maxValidityMs,
                         TransitionIR init,
                         List<TransitionIR> actions,
                         List<TransitionIR> destroys,
                         List<ViewIR> views,
                         List<String> imports,
                         Strategies strategies) {

    public String validatorName() {
        return name + "Validator";
    }

    public String clientName() {
        return name + "Client";
    }

    /** Spend-side transitions: actions then destroys, in that order (the redeemer's constructor order). */
    public List<TransitionIR> spendTransitions() {
        var all = new java.util.ArrayList<TransitionIR>(actions);
        all.addAll(destroys);
        return List.copyOf(all);
    }
}
