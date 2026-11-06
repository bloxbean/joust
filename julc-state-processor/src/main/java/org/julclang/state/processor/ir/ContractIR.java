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
 * @param ownedType    the {@code @Owned} record as source text, or null (ADR-0003)
 * @param ownedComponents components of the owned record
 * @param ownedInits   {@code @OwnedInit} methods
 * @param ownedActions {@code @OwnedAction} methods
 * @param ownedDestroys {@code @OwnedDestroy} methods
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
                         Strategies strategies,
                         String ownedType,
                         List<ParamIR> ownedComponents,
                         List<TransitionIR> ownedInits,
                         List<TransitionIR> ownedActions,
                         List<TransitionIR> ownedDestroys) {

    /** The contract has an {@code @Owned} record (ADR-0003). */
    public boolean hasOwned() {
        return ownedType != null;
    }

    /** Owned spend-side transitions: owned actions then owned destroys. */
    public List<TransitionIR> ownedSpendTransitions() {
        var all = new java.util.ArrayList<TransitionIR>(ownedActions);
        all.addAll(ownedDestroys);
        return List.copyOf(all);
    }

    public String validatorName() {
        return name + "Validator";
    }

    public String clientName() {
        return name + "Client";
    }

    /** Indexes of the state's MerkleMap components (ADR-0002). */
    public List<Integer> mapComponents() {
        var out = new java.util.ArrayList<Integer>();
        for (int i = 0; i < stateComponents.size(); i++) {
            if (stateComponents.get(i).kind() == ParamIR.TypeKind.MERKLE_MAP) out.add(i);
        }
        return out;
    }

    public boolean hasMaps() {
        return !mapComponents().isEmpty();
    }

    /** {@code @Batched} actions (ADR-0004), in source order. */
    public List<TransitionIR> batched() {
        return actions.stream().filter(TransitionIR::batched).toList();
    }

    public boolean hasBatched() {
        return actions.stream().anyMatch(TransitionIR::batched);
    }

    /**
     * Spend-side transitions with a direct-call redeemer: actions (except {@code @Batched(only = true)}) then
     * destroys, in that order (the redeemer's constructor order).
     */
    public List<TransitionIR> spendTransitions() {
        var all = new java.util.ArrayList<TransitionIR>(actions.stream().filter(t -> !t.batchOnly()).toList());
        all.addAll(destroys);
        return List.copyOf(all);
    }
}
