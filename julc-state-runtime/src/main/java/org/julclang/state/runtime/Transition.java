package org.julclang.state.runtime;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Payout;

/**
 * The JVM result of running an action or destroy: the next state ({@code null} when the instance ends) and payouts.
 */
public record Transition<S>(S next, JulcList<Payout> payouts) {

    public static <S> Transition<S> of(S next) {
        return new Transition<>(next, JulcList.empty());
    }

    public static <S> Transition<S> of(S next, JulcList<Payout> payouts) {
        return new Transition<>(next, payouts);
    }

    public static <S> Transition<S> end(JulcList<Payout> payouts) {
        return new Transition<>(null, payouts);
    }
}
