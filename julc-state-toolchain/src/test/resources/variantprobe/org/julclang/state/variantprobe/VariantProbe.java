package org.julclang.state.variantprobe;

import org.julclang.state.api.Action;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Init;
import org.julclang.state.api.StateContract;

import java.math.BigInteger;

/** Test-only contract whose transitions use a library with a (non-allowlisted) transition-program variant. */
@StateContract(maxDatumBytes = 128)
public class VariantProbe {

    public record State(BigInteger n, byte[] key) {}

    @Init
    public static State init(Ctx ctx) {
        return new State(BigInteger.ONE, ctx.caller());
    }

    @Action
    public static State double_(State s) {
        return new State(Helper.twice(s.n()), s.key());
    }
}
