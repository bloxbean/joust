package org.julclang.state.probe;

import org.julclang.state.api.Action;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Init;
import org.julclang.state.api.StateContract;

import java.math.BigInteger;

/**
 * A test-only contract (never deployed) that stores a record argument in its state without reading its fields: the
 * security review's unconfirmed case (PR #62). Built by the toolchain in TransitionInputsTest.
 */
@StateContract(maxDatumBytes = 256)
public class StoreProbe {

    public record Pair(BigInteger a, BigInteger b) {}

    public record State(BigInteger n, Pair p, byte[] key) {}

    @Init
    public static State init(Ctx ctx) {
        return new State(BigInteger.ZERO, new Pair(BigInteger.ZERO, BigInteger.ZERO), ctx.caller());
    }

    /** Stores the argument as it is. */
    @Action
    public static State put(State s, Pair p) {
        return new State(s.n().add(BigInteger.ONE), p, s.key());
    }
}
