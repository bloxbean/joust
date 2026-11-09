package org.julclang.state.examples.rollup;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Action;
import org.julclang.state.api.Clock;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Destroy;
import org.julclang.state.api.Init;
import org.julclang.state.api.Payable;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Payouts;
import org.julclang.state.api.Require;
import org.julclang.state.api.SignedBy;
import org.julclang.state.api.StateContract;
import org.julclang.stdlib.lib.ByteStringLib;

import java.math.BigInteger;

/**
 * The L1 side of the optimistic-rollup spike (ADR-0006 D2, r2): checkpoints of an off-chain ledger, a challenge
 * window, and one-step fraud proofs that re-run {@link Ledger#step} on-chain. An ordinary JOUST contract (Option A).
 * <p>
 * A commit carries its steps in the redeemer: the validator derives the log hash, the count and the new state hash
 * from them, so every commitment is challengeable and the steps are on-chain (data availability for watchers).
 */
@StateContract(maxDatumBytes = 320)
public class Rollup {

    public record State(byte[] operator, BigInteger window, BigInteger minBond, byte[] finalHash, BigInteger height,
                        byte[] pendingLog, BigInteger pendingCount, byte[] pendingHash, BigInteger challengeUntil) {}

    public record Slash(State state, JulcList<Payout> payouts) {}

    /** The operator opens the rollup with a genesis state hash and posts the bond. */
    @Init
    @Payable
    public static State init(byte[] genesis, BigInteger window, Ctx ctx) {
        Require.that(ByteStringLib.length(genesis) == 32, "genesis must be a 32-byte hash");
        Require.that(window.compareTo(BigInteger.valueOf(60_000)) >= 0, "the challenge window must be at least a minute");
        Require.that(ctx.received().compareTo(BigInteger.valueOf(2_000_000)) >= 0, "post a bond of at least 2 ADA");
        return new State(ctx.caller(), window, ctx.received(), genesis, BigInteger.ZERO, ByteStringLib.empty(),
                BigInteger.ZERO, ByteStringLib.empty(), BigInteger.ZERO);
    }

    /** Posts a checkpoint of 1 to 16 steps; its log hash, count and new state are derived from the steps themselves. */
    @Action
    @SignedBy("operator")
    public static State commit(State s, JulcList<Ledger.Step> steps, Ctx ctx) {
        Require.that(s.pendingCount().compareTo(BigInteger.ZERO) == 0, "a checkpoint is already pending");
        BigInteger count = Ledger.count(steps);
        Require.that(count.compareTo(BigInteger.ONE) >= 0 && count.compareTo(BigInteger.valueOf(16)) <= 0, "1 to 16 steps");
        Require.that(ctx.balance().compareTo(s.minBond()) >= 0, "the bond is below the minimum: rebond first");
        byte[] newHash = Ledger.lastPost(steps, s.finalHash());
        return new State(s.operator(), s.window(), s.minBond(), s.finalHash(), s.height(), Ledger.logHash(steps), count,
                newHash, ctx.validTo().add(s.window()));
    }

    /** Anyone finalises a checkpoint once its challenge window has passed. */
    @Action
    public static State finalize(State s, Ctx ctx) {
        Require.that(s.pendingCount().compareTo(BigInteger.ZERO) > 0, "nothing pending");
        Require.that(Clock.after(ctx, s.challengeUntil()), "the challenge window is still open");
        return new State(s.operator(), s.window(), s.minBond(), s.pendingHash(), s.height().add(BigInteger.ONE),
                ByteStringLib.empty(), BigInteger.ZERO, ByteStringLib.empty(), BigInteger.ZERO);
    }

    /**
     * Anyone proves step {@code index} of the pending checkpoint wrong: the checkpoint is discarded; half the bond goes
     * to the challenger and half is burned (paid to an all-zero key nobody holds), so an operator gains nothing by
     * challenging its own fraud.
     */
    @Action
    public static Slash challenge(State s, JulcList<Ledger.Step> steps, BigInteger index, Ledger.Book pre, Ctx ctx) {
        Require.that(s.pendingCount().compareTo(BigInteger.ZERO) > 0, "nothing pending");
        Require.that(Clock.before(ctx, s.challengeUntil()), "the challenge window has closed");
        Require.that(index.compareTo(BigInteger.ZERO) >= 0 && index.compareTo(s.pendingCount()) < 0, "no such step");
        Require.that(ByteStringLib.equals(Ledger.logHash(steps), s.pendingLog()), "not the committed log");
        Require.that(Ledger.fraud(s.finalHash(), steps, index, pre), "no fraud at this step");
        State reverted = new State(s.operator(), s.window(), s.minBond(), s.finalHash(), s.height(), ByteStringLib.empty(),
                BigInteger.ZERO, ByteStringLib.empty(), BigInteger.ZERO);
        BigInteger reward = ctx.balance().divide(BigInteger.TWO);
        byte[] burn = new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        return new Slash(reverted, Payouts.add(Payouts.of(ctx.caller(), reward), burn, ctx.balance().subtract(reward)));
    }

    /** The operator restores the bond after a slash; only between checkpoints, so it can't crowd out challenges. */
    @Action
    @Payable
    @SignedBy("operator")
    public static State rebond(State s, Ctx ctx) {
        Require.that(s.pendingCount().compareTo(BigInteger.ZERO) == 0, "a checkpoint is pending");
        Require.that(ctx.received().compareTo(BigInteger.ZERO) > 0, "send some lovelace");
        return s;
    }

    /** The spike holds no user funds (ADR-0006 §3): closing returns the bond. */
    @Destroy
    @SignedBy("operator")
    public static JulcList<Payout> close(State s, Ctx ctx) {
        Require.that(s.pendingCount().compareTo(BigInteger.ZERO) == 0, "a checkpoint is pending");
        return Payouts.of(s.operator(), ctx.balance());
    }
}
