package org.julclang.state.host.fixture;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Init;
import org.julclang.state.api.Owned;
import org.julclang.state.api.OwnedAction;
import org.julclang.state.api.OwnedDestroy;
import org.julclang.state.api.OwnedInit;
import org.julclang.state.api.Payable;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Payouts;
import org.julclang.state.api.Quorum;
import org.julclang.state.api.SignedBy;
import org.julclang.state.api.StateContract;
import java.math.BigInteger;

/**
 * Vector contract for the hosted-only owned branches (#154 review M2-1): an owned {@code @Quorum} action with and
 * without events, and an owned destroy whose method has events.
 */
@StateContract(maxDatumBytes = 384)
public class HostClub {
    public record State(byte[] admin) {}

    @Owned
    public record Seat(byte[] owner, JulcList<byte[]> members, BigInteger threshold, BigInteger tally) {}

    public sealed interface Event permits Tallied, Left {}
    public record Tallied(BigInteger n) implements Event {}
    public record Left() implements Event {}
    public record Tally(Seat state, JulcList<Payout> payouts, JulcList<Event> events) {}
    public record Leave(JulcList<Payout> payouts, JulcList<Event> events) {}

    @Init
    public static State open(Ctx ctx) {
        return new State(ctx.caller());
    }

    @OwnedInit @Payable
    public static Seat join(JulcList<byte[]> members, BigInteger threshold, Ctx ctx) {
        return new Seat(ctx.caller(), members, threshold, BigInteger.ZERO);
    }

    @OwnedAction @Quorum(keys = "members", threshold = "threshold")
    public static Seat touch(Seat s) {
        return new Seat(s.owner(), s.members(), s.threshold(), s.tally().add(BigInteger.ONE));
    }

    @OwnedAction @Quorum(keys = "members", threshold = "threshold")
    public static Tally tally(Seat s, BigInteger n) {
        JulcList<Event> events = JulcList.empty();
        return new Tally(new Seat(s.owner(), s.members(), s.threshold(), s.tally().add(n)),
                Payouts.of(s.owner(), BigInteger.ONE), events.prepend(new Tallied(n)));
    }

    @OwnedDestroy @SignedBy("owner")
    public static Leave leave(Seat s, Ctx ctx) {
        JulcList<Event> events = JulcList.empty();
        return new Leave(Payouts.of(s.owner(), ctx.balance()), events.prepend(new Left()));
    }
}
