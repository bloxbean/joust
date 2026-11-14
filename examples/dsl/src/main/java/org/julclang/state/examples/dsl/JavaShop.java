package org.julclang.state.examples.dsl;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Action;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Destroy;
import org.julclang.state.api.Init;
import org.julclang.state.api.OnlyOwner;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Payouts;
import org.julclang.state.api.Payable;
import org.julclang.state.api.Require;
import org.julclang.state.api.StateContract;
import org.julclang.state.api.View;

import java.math.BigInteger;

/**
 * A Java contract calling a {@code .jsc} library (ADR-0018 D9, M4): {@code fees.jsc} lowers to the JuLC
 * {@code @OnchainLibrary} class {@link Fees}, which this contract calls as it calls any on-chain library. Its records
 * and enums are the library's nested types ({@code Fees.Line}, {@code Fees.Tier}), here in the state and a redeemer.
 * The same Java runs on the JVM (the next state) and on-chain (compiled by JuLC from the library's generated source).
 */
@StateContract(maxDatumBytes = 1024)
public class JavaShop {

    public record State(byte[] owner, BigInteger price, JulcList<Fees.Line> lines, Fees.Tier tier, BigInteger sold) {}

    @Init
    public static State init(BigInteger price, JulcList<Fees.Line> lines, Ctx ctx) {
        Require.that(Fees.total(lines).compareTo(BigInteger.valueOf(10_000)) <= 0, "the lines exceed 100%");
        return new State(ctx.caller(), Fees.nonNegative(price), lines, new Fees.Basic(), BigInteger.ZERO);
    }

    @Action
    @Payable
    public static State buy(State s, Ctx ctx) {
        BigInteger due = s.price().add(Fees.fee(s.price(), Fees.rate(s.tier())));
        Require.that(ctx.received().compareTo(due) >= 0, "pay the price and the fee");
        return new State(s.owner(), s.price(), s.lines(), s.tier(), s.sold().add(BigInteger.ONE));
    }

    @Action
    @OnlyOwner
    public static State setTier(State s, Fees.Tier next) {
        return new State(s.owner(), s.price(), s.lines(), next, s.sold());
    }

    @Destroy
    @OnlyOwner
    public static JulcList<Payout> close(State s, Ctx ctx) {
        BigInteger cut = sum(Fees.cuts(ctx.balance(), s.lines()));
        return pays(s.lines(), ctx.balance(), Payouts.of(s.owner(), ctx.balance().subtract(cut)));
    }

    /** Each line's cut, in front of the owner's remainder. */
    static JulcList<Payout> pays(JulcList<Fees.Line> lines, BigInteger balance, JulcList<Payout> rest) {
        if (lines.isEmpty()) return rest;
        Fees.Line l = lines.head();
        return pays(lines.tail(), balance, rest).prepend(new Payout(l.to(), Fees.fee(balance, l.bps())));
    }

    static BigInteger sum(JulcList<BigInteger> xs) {
        return xs.isEmpty() ? BigInteger.ZERO : xs.head().add(sum(xs.tail()));
    }

    @View
    public static BigInteger feeOn(State s, BigInteger amount) {
        return Fees.fee(amount, Fees.rate(s.tier()));
    }
}
