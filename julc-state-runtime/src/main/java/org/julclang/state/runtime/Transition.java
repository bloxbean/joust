package org.julclang.state.runtime;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.Deposit;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Send;

/**
 * The JVM result of running a method: the next state ({@code null} when the instance or object ends), payouts, and for
 * asset-aware contracts (ADR-0007) sends, the user-token mint and the native assets accepted from the caller.
 */
public record Transition<S>(S next, JulcList<Payout> payouts, JulcList<Send> sends, Value mint, Value accepted,
                            JulcList<?> events, JulcList<Deposit> deposits) {

    public Transition(S next, JulcList<Payout> payouts) {
        this(next, payouts, JulcList.empty(), Assets.none(), Assets.none(), JulcList.empty(), JulcList.empty());
    }

    public Transition(S next, JulcList<Payout> payouts, JulcList<Send> sends, Value mint, Value accepted) {
        this(next, payouts, sends, mint, accepted, JulcList.empty(), JulcList.empty());
    }

    /** The same transition with {@code events} (ADR-0009 D5). */
    public Transition<S> withEvents(JulcList<?> events) {
        return new Transition<>(next, payouts, sends, mint, accepted, events == null ? JulcList.empty() : events, deposits);
    }

    /** The same transition with {@code deposits} into other instances (ADR-0011 D3). */
    public Transition<S> withDeposits(JulcList<Deposit> deposits) {
        return new Transition<>(next, payouts, sends, mint, accepted, events, deposits == null ? JulcList.empty() : deposits);
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
