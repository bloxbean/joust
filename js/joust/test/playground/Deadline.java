package app;

import org.julclang.state.api.Action;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Init;
import org.julclang.state.api.StateContract;
import org.julclang.state.api.ValidBefore;

import java.math.BigInteger;

/** Probe: a time rule without Ctx (the validator reads tx.validRange directly), and the same rule with Ctx. */
@StateContract(maxDatumBytes = 128)
public class Deadline {
    public record State(BigInteger count, BigInteger deadline) {}

    @Init
    public static State init(BigInteger deadline, Ctx ctx) {
        return new State(BigInteger.ZERO, deadline);
    }

    @Action
    @ValidBefore("deadline")
    public static State bump(State s) {
        return new State(s.count().add(BigInteger.ONE), s.deadline());
    }

    @Action
    @ValidBefore("deadline")
    public static State bumpCtx(State s, Ctx ctx) {
        return new State(s.count().add(BigInteger.ONE), s.deadline());
    }
}
