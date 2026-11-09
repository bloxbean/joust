package org.julclang.state.runtime.described;

import org.julclang.core.PlutusData;

/**
 * A transition's off-chain meaning (ADR-0015 D3): the target (the state, the owned value, or nothing for a creation),
 * the instance state for {@code @Shared}, the arguments and the Ctx in, as Data; the step out, as Data:
 * {@code Constr 0 [Some next | None, payouts]}. A refusal (the contract's {@code Require}) throws
 * {@link org.julclang.state.api.RequireException} with the contract's message.
 * <p>
 * Implementations: the transition programs evaluated by JuLC's VM ({@link UplcTransitions}; the engine), or the
 * contract's JVM methods.
 */
@FunctionalInterface
public interface TransitionFn {

    PlutusData apply(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx);

    /** One run: the step, or the refusal's message (step null); and the run's traces (MerkleMap read discovery). */
    record Run(PlutusData step, String refusal, java.util.List<String> traces) {}

    /** As {@link #apply}, keeping the traces and returning a refusal instead of throwing it. */
    default Run run(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        try {
            return new Run(apply(method, target, shared, args, ctx), null, java.util.List.of());
        } catch (org.julclang.state.api.RequireException e) {
            return new Run(null, e.getMessage(), java.util.List.of());
        }
    }
}
