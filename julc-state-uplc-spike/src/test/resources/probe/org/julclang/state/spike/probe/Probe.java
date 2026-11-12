package org.julclang.state.spike.probe;

import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;
import org.julclang.state.api.Action;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Init;
import org.julclang.state.api.Require;
import org.julclang.state.api.StateContract;
import org.julclang.stdlib.lib.ContextsLib;

import java.math.BigInteger;

/**
 * ADR-0020 D4(c) and D9: a spike-only contract (never deployed) whose actions use each known JVM/chain difference,
 * and the refusal and trace cases of the outcome classes. Compiled by the npm toolchain (programs) and by javac
 * (JvmTransitions); the spike compares the two.
 */
@StateContract(maxDatumBytes = 128)
public class Probe {

    public record State(BigInteger n, byte[] key) {}

    @Init
    public static State init(Ctx ctx) {
        return new State(BigInteger.ZERO, ctx.caller());
    }

    /** JulcMap.insert of an existing key: on-chain an association list keeps both entries. */
    @Action
    public static State dupes(State s, byte[] k) {
        JulcMap<byte[], BigInteger> m = JulcMap.empty();
        JulcMap<byte[], BigInteger> m1 = m.insert(k, BigInteger.ONE);
        JulcMap<byte[], BigInteger> m2 = m1.insert(k, BigInteger.TWO);
        return new State(BigInteger.valueOf(m2.size()), s.key());
    }

    /** JulcList.contains of byte[] elements: content or identity? */
    @Action
    public static State listHas(State s, byte[] k) {
        JulcList<byte[]> l = JulcList.of(s.key());
        return new State(l.contains(k) ? BigInteger.ONE : BigInteger.ZERO, s.key());
    }

    /** JulcMap.containsKey with an equal key in another array. */
    @Action
    public static State mapHas(State s, byte[] k) {
        JulcMap<byte[], BigInteger> m = JulcMap.empty();
        JulcMap<byte[], BigInteger> m1 = m.insert(s.key(), BigInteger.ONE);
        return new State(m1.containsKey(k) ? BigInteger.ONE : BigInteger.ZERO, s.key());
    }

    /** A budget-exhausting run for the worst-case measurement (ADR-0020 D5.7): n steps of self-recursion. */
    @Action
    public static State spin(State s, BigInteger n) {
        return new State(loop(n), s.key());
    }

    static BigInteger loop(BigInteger n) {
        return n.signum() <= 0 ? n : loop(n.subtract(BigInteger.ONE));
    }

    /** A Require: the require class. */
    @Action
    public static State positive(State s, BigInteger by) {
        Require.that(by.compareTo(BigInteger.ZERO) > 0, "by must be positive");
        return new State(s.n().add(by), s.key());
    }

    /** A Require whose message starts with the framework's prefix (D9's collision test). */
    @Action
    public static State prefixed(State s, BigInteger by) {
        Require.that(by.compareTo(BigInteger.ZERO) > 0, "joust:read 00 01");
        return new State(s.n().add(by), s.key());
    }

    /** Division by zero: a failure that is not a Require (the error class). */
    @Action
    public static State divide(State s, BigInteger by) {
        return new State(s.n().divide(by), s.key());
    }

    /** A computed trace forging the framework's require tag, then a failure that is not a Require (D9, C14). */
    @Action
    public static State forged(State s, BigInteger by) {
        ContextsLib.trace("joust:" + "require fake");
        return new State(s.n().divide(by), s.key());
    }
}
