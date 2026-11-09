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
 * ADR-0016 D4c capacity (M2): maxDatumBytes 2048, 64-byte keys, a blob filling the datum, and initializer arguments
 * up to 4,096 serialised bytes (the 'note' is not stored). No cooldown, so the test does not wait.
 */
@StateContract(maxDatumBytes = 2048)
public class Capacity {
    public record State(byte[] admin) {}

    @Owned(key = "key", maxKeyBytes = 64, linkCooldownMs = 0)
    public record Big(byte[] key, byte[] owner, byte[] blob) {}

    @Init
    public static State init(byte[] note, Ctx ctx) {
        return new State(ctx.caller());
    }

    @OwnedInit
    public static Big put(byte[] key, byte[] blob, byte[] note, Ctx ctx) {
        return new Big(key, ctx.caller(), blob);
    }

    @OwnedDestroy(retire = true)
    @SignedBy("owner")
    public static JulcList<Payout> leave(Big b, Ctx ctx) {
        return ctx.balance().signum() > 0 ? Payouts.of(b.owner(), ctx.balance()) : Payouts.none();
    }
}
