package org.julclang.state.examples.counter;

import org.julclang.state.api.Action;
import org.julclang.state.api.Init;
import org.julclang.state.api.StateContract;
import org.julclang.state.api.View;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ByteStringLib;

import java.math.BigInteger;

/**
 * ============================================================================
 *  THE ONLY FILE THE DEVELOPER WRITES.
 * ============================================================================
 *
 * A counter that anyone can increment and only the owner can reset.
 * There's nothing about UTxOs, datums, redeemers, thread tokens or tx building here:
 * just a state record and pure functions that compute the next state.
 *
 * The same methods run in two places:
 *   - on the JVM, inside the generated CounterClient, to compute the next state
 *     and build the transaction;
 *   - on-chain, compiled by JuLC into the generated CounterValidator, which
 *     re-runs them and checks that the tx's output datum equals the result.
 *
 * {@code caller} is supplied by the framework. It's the PubKeyHash that the generated
 * validator has verified signed the transaction (the eUTxO equivalent of msg.sender).
 *
 * Today's stand-in: {@code @OnchainLibrary} is what lets JuLC compile this class for
 * on-chain use. In the proposed framework, {@code @StateContract} would imply it.
 */
@StateContract
@OnchainLibrary
public class Counter {

    /** The contract's state. Stored as the inline datum of the state UTxO. */
    public record State(BigInteger count, byte[] owner) {}

    /** Called once when the contract is deployed. The caller becomes the owner. */
    @Init
    public static State init(byte[] caller) {
        return new State(BigInteger.ZERO, caller);
    }

    /** Anyone can increment by a positive amount. */
    @Action
    public static State increment(State s, BigInteger by, byte[] caller) {
        if (by.compareTo(BigInteger.ZERO) <= 0) {          // proposed sugar: require(by > 0, "positive")
            Builtins.error();
            return s;
        } else {
            return new State(s.count().add(by), s.owner());
        }
    }

    /** Only the owner can reset. */
    @Action
    public static State reset(State s, byte[] caller) {
        if (!ByteStringLib.equals(caller, s.owner())) {     // proposed sugar: require(caller == owner, "owner only")
            Builtins.error();
            return s;
        } else {
            return new State(BigInteger.ZERO, s.owner());
        }
    }

    /** Read-only view. Runs off-chain only (no transaction). */
    @View
    public static BigInteger count(State s) {
        return s.count();
    }
}
