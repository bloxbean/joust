package @@package@@;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Action;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Destroy;
import org.julclang.state.api.Init;
import org.julclang.state.api.OnlyOwner;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Payouts;
import org.julclang.state.api.Require;
import org.julclang.state.api.StateContract;
import org.julclang.state.api.View;

import java.math.BigInteger;

@StateContract(maxDatumBytes = 128)
public class Counter {

    public record State(BigInteger count, byte[] owner) {}

    @Init
    public static State init(Ctx ctx) {
        return new State(BigInteger.ZERO, ctx.caller());
    }

    @Action
    public static State increment(State s, BigInteger by) {
        Require.that(by.compareTo(BigInteger.ZERO) > 0, "increment must be positive");
        return new State(s.count().add(by), s.owner());
    }

    @Action
    @OnlyOwner
    public static State reset(State s) {
        return new State(BigInteger.ZERO, s.owner());
    }

    @Destroy
    @OnlyOwner
    public static JulcList<Payout> close(State s, Ctx ctx) {
        return Payouts.of(s.owner(), ctx.balance());
    }

    @View
    public static BigInteger count(State s) {
        return s.count();
    }
}
