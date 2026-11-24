package org.julclang.state.host.fixture;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.Credential;
import org.julclang.ledger.PubKeyHash;
import org.julclang.state.api.Action;
import org.julclang.state.api.AuthorizedBy;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Destroy;
import org.julclang.state.api.Init;
import org.julclang.state.api.Payable;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Payouts;
import org.julclang.state.api.Quorum;
import org.julclang.state.api.StateContract;
import java.math.BigInteger;

/** Vector contract: quorum without Ctx or @Params, plus a tagged payout and destroy event. */
@StateContract(maxDatumBytes = 384)
public class HostCouncil {
    public record State(JulcList<byte[]> members, BigInteger threshold, Credential governor) {}
    public sealed interface Event permits Closed, Voted {}
    public record Closed() implements Event {}
    public record Voted(BigInteger n) implements Event {}
    public record Vote(State state, JulcList<Payout> payouts, JulcList<Event> events) {}
    public record End(JulcList<Payout> payouts, JulcList<Event> events) {}

    @Init @Payable
    public static State open(JulcList<byte[]> members, BigInteger threshold, Ctx ctx) {
        return new State(members, threshold, new Credential.PubKeyCredential(new PubKeyHash(ctx.caller())));
    }

    @Action @Quorum(keys = "members", threshold = "threshold")
    public static Vote vote(State state, BigInteger n, byte[] to) {
        JulcList<Event> events = JulcList.empty();
        return new Vote(state, Payouts.of(to, BigInteger.ONE), events.prepend(new Voted(n)));
    }

    @Action @AuthorizedBy("governor")
    public static State veto(State state) { return state; }

    @Destroy
    public static End close(State state, Ctx ctx) {
        JulcList<Event> events = JulcList.empty();
        return new End(Payouts.of(ctx.caller(), ctx.balance()), events.prepend(new Closed()));
    }
}
