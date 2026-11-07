package org.julclang.state.runtime;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Send;

/**
 * The JVM result of running a method: the next state ({@code null} when the instance or object ends), payouts, and for
 * asset-aware contracts (ADR-0007) sends, the user-token mint and the native assets accepted from the caller.
 */
public record Transition<S>(S next, JulcList<Payout> payouts, JulcList<Send> sends, Value mint, Value accepted) {

    public Transition(S next, JulcList<Payout> payouts) {
        this(next, payouts, JulcList.empty(), Assets.none(), Assets.none());
    }

    public static <S> Transition<S> of(S next) {
        return new Transition<>(next, JulcList.empty());
    }

    public static <S> Transition<S> of(S next, JulcList<Payout> payouts) {
        return new Transition<>(next, payouts);
    }

    public static <S> Transition<S> end(JulcList<Payout> payouts) {
        return new Transition<>(null, payouts);
    }

    /** Every effect; {@code null} components mean none. */
    public static <S> Transition<S> full(S next, JulcList<Payout> payouts, JulcList<Send> sends, Value mint, Value accepted) {
        return new Transition<>(next, payouts == null ? JulcList.empty() : payouts, sends == null ? JulcList.empty() : sends,
                mint == null ? Assets.none() : mint, accepted == null ? Assets.none() : accepted);
    }
}
