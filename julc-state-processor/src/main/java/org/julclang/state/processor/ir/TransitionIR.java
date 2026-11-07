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
 * @param shared      the method takes the instance state read-only ({@code @Shared}, owned methods only)
 * @param batched     the action may also run as an intent ({@code @Batched}, ADR-0004)
 * @param batchOnly   only as an intent: no direct-call redeemer ({@code @Batched(only = true)})
 * @param hasFunds    the method declares a trailing {@code Funds} (ADR-0007)
 * @param resultPayouts the result record has a {@code payouts} component (RESULT, SETTLE)
 * @param resultSends the result record has a {@code sends} component (ADR-0007)
 * @param resultMint  the result record has a {@code mint} component (ADR-0007)
 * @param resultAccepted the result record has an {@code accepted} component (ADR-0007 r2)
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
                           List<String> validBefore,
                           boolean shared,
                           boolean batched,
                           boolean batchOnly,
                           boolean hasFunds,
                           boolean resultPayouts,
                           boolean resultSends,
                           boolean resultMint,
                           boolean resultAccepted) {

    public enum Kind { INIT, ACTION, DESTROY, OWNED_INIT, OWNED_ACTION, OWNED_DESTROY }

    /** An owned-object method (ADR-0003). */
    public boolean owned() {
        return kind == Kind.OWNED_INIT || kind == Kind.OWNED_ACTION || kind == Kind.OWNED_DESTROY;
    }

    /** Ends what it acts on (instance or object): burns its token. */
    public boolean destroys() {
        return kind == Kind.DESTROY || kind == Kind.OWNED_DESTROY;
    }

    /** The redeemer variant of this action's intent call ({@code donate} -> {@code DonateIntent}). */
    public String intentVariant() {
        return variant + "Intent";
    }

    public enum Returns {
        /** the state type */
        STATE,
        /** a record {@code (S state, JulcList<Payout> payouts)} */
        RESULT,
        /** {@code JulcList<Payout>} ({@code @Destroy}) */
        PAYOUTS,
        /** a destroy's record of {@code payouts}, {@code sends} and/or {@code mint}, without state (ADR-0007) */
        SETTLE
    }

    /** Returns a record (RESULT or SETTLE). */
    public boolean returnsRecord() {
        return returns == Returns.RESULT || returns == Returns.SETTLE;
    }

    /** The method pays out with {@code Payout}s (a payout list, or a record with a payouts component). */
    public boolean hasPayouts() {
        return returns == Returns.PAYOUTS || (returnsRecord() && resultPayouts);
    }

    /** Touches native assets: takes Funds, sends or mints (ADR-0007). */
    public boolean assetAware() {
        return hasFunds || resultSends || resultMint;
    }

    public boolean paysOut() {
        return returns != Returns.STATE;
    }

    public boolean usesTime() {
        return hasCtx || !validAfter.isEmpty() || !validBefore.isEmpty();
    }
}
