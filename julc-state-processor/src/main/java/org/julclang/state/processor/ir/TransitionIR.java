package org.julclang.state.processor.ir;

import java.util.List;

/**
 * One {@code @Init}, {@code @Action} or {@code @Destroy} method.
 *
 * @param kind        which annotation
 * @param method      the Java method name
 * @param variant     the redeemer variant name ({@code increment} -> {@code Increment})
 * @param params      arguments after the state parameter and before {@code Ctx}
 * @param hasCtx      the method declares a trailing {@code Ctx}
 * @param payable     {@code @Payable}
 * @param returns     what the method returns
 * @param resultType  for {@link Returns#RESULT}: the result record, as source text; otherwise null
 * @param signedBy    state fields whose key hash must sign ({@code @SignedBy}, {@code @OnlyOwner})
 * @param validAfter  state fields the validity interval must start at or after
 * @param validBefore state fields the validity interval must end at or before
 */
public record TransitionIR(Kind kind,
                           String method,
                           String variant,
                           List<ParamIR> params,
                           boolean hasCtx,
                           boolean payable,
                           Returns returns,
                           String resultType,
                           List<String> signedBy,
                           List<String> validAfter,
                           List<String> validBefore) {

    public enum Kind { INIT, ACTION, DESTROY }

    public enum Returns {
        /** the state type */
        STATE,
        /** a record {@code (S state, JulcList<Payout> payouts)} */
        RESULT,
        /** {@code JulcList<Payout>} ({@code @Destroy}) */
        PAYOUTS
    }

    public boolean paysOut() {
        return returns != Returns.STATE;
    }

    public boolean usesTime() {
        return hasCtx || !validAfter.isEmpty() || !validBefore.isEmpty();
    }
}
