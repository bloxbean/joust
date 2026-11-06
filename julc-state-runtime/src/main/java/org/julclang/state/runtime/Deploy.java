package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import org.julclang.ledger.TxOutRef;
import org.julclang.state.api.Ctx;

import java.math.BigInteger;
import java.util.Objects;
import java.util.function.Function;

/**
 * How to create an instance: filled in by a generated client's {@code deploy(...)}.
 *
 * @param caller    pays, signs, and is {@code ctx.caller()}
 * @param received  lovelace the {@code @Init} receives ({@code @Payable}); zero otherwise
 * @param usesCtx   the {@code @Init} declares Ctx (the transaction then gets a validity interval)
 * @param run       runs the {@code @Init} on the JVM
 * @param redeemer  builds the {@code Create} redeemer from the seed and the Ctx
 */
public record Deploy<S>(Account caller, BigInteger received, boolean usesCtx, Function<Ctx, S> run,
                        CreateRedeemer redeemer) {

    /** Builds the {@code CreateInstance} redeemer from the seed and the Ctx. */
    @FunctionalInterface
    public interface CreateRedeemer {
        Object make(TxOutRef seed, Ctx ctx);
    }

    public Deploy {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(received, "received");
        if (received.signum() < 0) throw new IllegalArgumentException("received must not be negative");
    }
}
