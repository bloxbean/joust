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

/** ADR-0016 M2: a cooldown not aligned to one-second slots (60.5 s): waits round up to the next slot. */
@StateContract(maxDatumBytes = 256)
public class Odd {
    public record State(byte[] admin) {}

    @Owned(key = "key", maxKeyBytes = 32, linkCooldownMs = 60_500)
    public record Mark(byte[] key, byte[] owner) {}

    @Init
    public static State init(Ctx ctx) {
        return new State(ctx.caller());
    }

    @OwnedInit
    public static Mark mark(byte[] key, Ctx ctx) {
        return new Mark(key, ctx.caller());
    }

    @OwnedDestroy(retire = true)
    @SignedBy("owner")
    public static JulcList<Payout> leave(Mark m, Ctx ctx) {
        return ctx.balance().signum() > 0 ? Payouts.of(m.owner(), ctx.balance()) : Payouts.none();
    }
}
