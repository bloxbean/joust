package org.julclang.state.probe;

import org.julclang.state.api.Action;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Init;
import org.julclang.state.api.Require;
import org.julclang.state.api.StateContract;
import org.julclang.stdlib.lib.ContextsLib;

import java.math.BigInteger;

/**
 * A test-only contract (never deployed) for the outcome classes of a transition program (ADR-0020 D9, adopted in
 * §11.4): a refusal, a refusal whose message starts with the framework's prefix, a failure that is not a refusal, a
 * forged tag, and a run over the budget. Built by the toolchain in OutcomeClassTest. From the spike's Probe.
 */
@StateContract(maxDatumBytes = 128)
public class Probe {

    public record State(BigInteger n, byte[] key) {}

    @Init
    public static State init(Ctx ctx) {
        return new State(BigInteger.ZERO, ctx.caller());
    }

    /** A Require: the contract's refusal. */
    @Action
    public static State positive(State s, BigInteger by) {
        Require.that(by.compareTo(BigInteger.ZERO) > 0, "by must be positive");
        return new State(s.n().add(by), s.key());
    }

    /** A Require whose message starts with the framework's prefix: the last-trace rule lost it. */
    @Action
    public static State prefixed(State s, BigInteger by) {
        Require.that(by.compareTo(BigInteger.ZERO) > 0, "joust:read 00 01");
        return new State(s.n().add(by), s.key());
    }

    /** A trace, then a division by zero: a failure that is not a Require. */
    @Action
    public static State divide(State s, BigInteger by) {
        ContextsLib.trace("dividing");
        return new State(s.n().divide(by), s.key());
    }

    /** A computed trace forging the require tag, then a failure that is not a Require. */
    @Action
    public static State forged(State s, BigInteger by) {
        ContextsLib.trace("joust:" + "require fake");
        return new State(s.n().divide(by), s.key());
    }

    /** A forged tag, then over the budget: a budget failure, not a refusal (PR #62 C4). */
    @Action
    public static State forgedSpin(State s, BigInteger n) {
        ContextsLib.trace("joust:" + "require forged");
        return new State(loop(n), s.key());
    }

    /** A forged tag carrying a keyed client's retry text, then a division by zero (PR #62 S2). */
    @Action
    public static State cooldown(State s, BigInteger by) {
        ContextsLib.trace("joust:" + "require prepare again at slot 99999999999");
        return new State(s.n().divide(by), s.key());
    }

    /** A forged tag, then a real Require: the real one is the refusal. */
    @Action
    public static State forgedThenReal(State s, BigInteger by) {
        ContextsLib.trace("joust:" + "require forged");
        Require.that(by.compareTo(BigInteger.ZERO) > 0, "real refusal");
        return new State(s.n().add(by), s.key());
    }

    /** n steps of self-recursion: over the budget for a large n. */
    @Action
    public static State spin(State s, BigInteger n) {
        return new State(loop(n), s.key());
    }

    static BigInteger loop(BigInteger n) {
        return n.signum() <= 0 ? n : loop(n.subtract(BigInteger.ONE));
    }
}
