package org.julclang.state.examples.registry.m2;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Action;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.Init;
import org.julclang.state.api.Owned;
import org.julclang.state.api.OwnedAction;
import org.julclang.state.api.OwnedDestroy;
import org.julclang.state.api.OwnedInit;
import org.julclang.state.api.Payout;
import org.julclang.state.api.Payouts;
import org.julclang.state.api.SignedBy;
import org.julclang.state.api.StateContract;
import org.julclang.stdlib.lib.ByteStringLib;

import java.math.BigInteger;

/**
 * ADR-0016 M2: no link cooldown (C = 0), and a key derived from the call's time (a composition rebuilds it), next to
 * a key chosen by the caller. One-second slots make 'validFrom' a slot time.
 */
@StateContract(maxDatumBytes = 256)
public class Stamps {
    public record State(byte[] admin, BigInteger count) {}

    @Owned(key = "key", maxKeyBytes = 32, linkCooldownMs = 0)
    public record Stamp(byte[] key, byte[] owner) {}

    @Init
    public static State init(Ctx ctx) {
        return new State(ctx.caller(), BigInteger.ZERO);
    }

    @Action
    @SignedBy("admin")
    public static State bump(State s) {
        return new State(s.admin(), s.count().add(BigInteger.ONE));
    }

    @OwnedInit
    public static Stamp stamp(byte[] key, Ctx ctx) {
        return new Stamp(key, ctx.caller());
    }

    /** The key is the window's start, 8 bytes big-endian: a rebuilt call gets another key. */
    @OwnedInit
    public static Stamp now(Ctx ctx) {
        return new Stamp(ByteStringLib.integerToByteString(true, 8L, ctx.validFrom()), ctx.caller());
    }

    @OwnedAction
    @SignedBy("owner")
    public static Stamp touch(Stamp s) {
        return new Stamp(s.key(), s.owner());
    }

    @OwnedDestroy
    @SignedBy("owner")
    public static JulcList<Payout> drop(Stamp s, Ctx ctx) {
        return Payouts.of(s.owner(), ctx.balance());
    }

    @OwnedDestroy(retire = true)
    @SignedBy("owner")
    public static JulcList<Payout> leave(Stamp s, Ctx ctx) {
        return ctx.balance().signum() > 0 ? Payouts.of(s.owner(), ctx.balance()) : Payouts.none();
    }
}
