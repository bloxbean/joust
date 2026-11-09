package org.julclang.state.examples.registry.widest;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Action;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Init;
import org.julclang.state.api.Owned;
import org.julclang.state.api.OwnedAction;
import org.julclang.state.api.OwnedDestroy;
import org.julclang.state.api.OwnedInit;
import org.julclang.state.api.Payable;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Payouts;
import org.julclang.state.api.Require;
import org.julclang.state.api.Shared;
import org.julclang.state.api.SignedBy;
import org.julclang.state.api.StateContract;

import java.math.BigInteger;

/**
 * The widest combination ADR-0016 M1 gates on script size (test only): two keyed types, each with every kind of keyed
 * call (an insert, an action, a removing and a retiring exit), and a plain owned type.
 * <p>
 * The ADR's widest contract gives the plain type a MerkleMap. That does not fit: 18,375 bytes as written here with
 * {@code Vault(byte[] owner, MerkleMap inventory)}, and still 15,977 bytes with each keyed type cut down to its insert and
 * retire, against the runtime's 15,000. The processor therefore refuses MerkleMaps in a contract with keyed types
 * (ADR-0016 implementation notes, M1); the plain type here holds a counter instead.
 */
@StateContract(maxDatumBytes = 384)
public class Widest {

    public record State(byte[] admin, boolean open) {}

    @Owned(key = "voter", maxKeyBytes = 28, signedKey = true)
    public record Ballot(byte[] voter, BigInteger choice) {}

    @Owned(key = "name", maxKeyBytes = 64)
    public record Entry(byte[] name, byte[] owner, BigInteger stake) {}

    /** A plain owned type (a MerkleMap here would not fit with keyed types; see above). */
    @Owned
    public record Vault(byte[] owner, BigInteger count) {}

    @Init
    public static State init(Ctx ctx) {
        return new State(ctx.caller(), true);
    }

    @Action
    @SignedBy("admin")
    public static State setOpen(State s, boolean open) {
        return new State(s.admin(), open);
    }

    @OwnedInit
    public static Ballot cast(@Shared State s, byte[] voter, BigInteger choice, Ctx ctx) {
        Require.that(s.open(), "voting is closed");
        return new Ballot(voter, choice);
    }

    @OwnedAction
    @SignedBy("voter")
    public static Ballot change(Ballot b, BigInteger choice) {
        return new Ballot(b.voter(), choice);
    }

    @OwnedDestroy
    @SignedBy("voter")
    public static JulcList<Payout> withdrawBallot(Ballot b, Ctx ctx) {
        return Payouts.of(b.voter(), ctx.balance());
    }

    @OwnedDestroy(retire = true)
    @SignedBy("voter")
    public static JulcList<Payout> leave(Ballot b, Ctx ctx) {
        return ctx.balance().signum() > 0 ? Payouts.of(b.voter(), ctx.balance()) : Payouts.none();
    }

    @OwnedInit
    @Payable
    public static Entry claim(byte[] name, Ctx ctx) {
        return new Entry(name, ctx.caller(), ctx.received());
    }

    @OwnedAction
    @Payable
    @SignedBy("owner")
    public static Entry topUp(Entry e, Ctx ctx) {
        return new Entry(e.name(), e.owner(), e.stake().add(ctx.received()));
    }

    @OwnedDestroy
    @SignedBy("owner")
    public static JulcList<Payout> release(Entry e, Ctx ctx) {
        return Payouts.of(e.owner(), ctx.balance());
    }

    /**
     * Pays the balance in one payout, even a zero one: such a payout is refused on-chain (payouts must be positive), so
     * an entry whose balance is zero cannot retire this way. Written so for the test of that rule; a real contract
     * returns {@code Payouts.none()} then, as Registry does.
     */
    @OwnedDestroy(retire = true)
    @SignedBy("owner")
    public static JulcList<Payout> retire(Entry e, Ctx ctx) {
        return Payouts.of(e.owner(), ctx.balance());
    }

    @OwnedInit
    public static Vault openVault(@Shared State s, Ctx ctx) {
        Require.that(s.open(), "closed");
        return new Vault(ctx.caller(), BigInteger.ZERO);
    }

    @OwnedAction
    @SignedBy("owner")
    public static Vault stash(Vault v, byte[] item, BigInteger count) {
        Require.that(count.signum() > 0, "stash a positive count");
        return new Vault(v.owner(), v.count().add(count));
    }

    @OwnedDestroy
    @SignedBy("owner")
    public static JulcList<Payout> closeVault(Vault v, Ctx ctx) {
        return Payouts.of(v.owner(), ctx.balance());
    }
}
