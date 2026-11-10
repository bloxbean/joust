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
 * ADR-0016 D4c (M2): eleven keyed types, the most whose minimal script fits 15,000 bytes (14,892 bytes; twelve take
 * 15,546 and sixteen 18,169), so a creation mints eleven heads; @Init arguments up to 4,096 bytes.
 */
@StateContract(maxDatumBytes = 256)
public class Eleven {
    public record State(byte[] admin) {}

    @Init
    public static State init(byte[] note, Ctx ctx) {
        return new State(ctx.caller());
    }

    @Owned(key = "key", maxKeyBytes = 32, linkCooldownMs = 0)
    public record K00(byte[] key, byte[] owner) {}
    @OwnedInit
    public static K00 add00(byte[] key, Ctx ctx) {
        return new K00(key, ctx.caller());
    }
    @OwnedDestroy(retire = true)
    @SignedBy("owner")
    public static JulcList<Payout> leave00(K00 k, Ctx ctx) {
        return ctx.balance().signum() > 0 ? Payouts.of(k.owner(), ctx.balance()) : Payouts.none();
    }

    @Owned(key = "key", maxKeyBytes = 32, linkCooldownMs = 0)
    public record K01(byte[] key, byte[] owner) {}
    @OwnedInit
    public static K01 add01(byte[] key, Ctx ctx) {
        return new K01(key, ctx.caller());
    }
    @OwnedDestroy(retire = true)
    @SignedBy("owner")
    public static JulcList<Payout> leave01(K01 k, Ctx ctx) {
        return ctx.balance().signum() > 0 ? Payouts.of(k.owner(), ctx.balance()) : Payouts.none();
    }

    @Owned(key = "key", maxKeyBytes = 32, linkCooldownMs = 0)
    public record K02(byte[] key, byte[] owner) {}
    @OwnedInit
    public static K02 add02(byte[] key, Ctx ctx) {
        return new K02(key, ctx.caller());
    }
    @OwnedDestroy(retire = true)
    @SignedBy("owner")
    public static JulcList<Payout> leave02(K02 k, Ctx ctx) {
        return ctx.balance().signum() > 0 ? Payouts.of(k.owner(), ctx.balance()) : Payouts.none();
    }

    @Owned(key = "key", maxKeyBytes = 32, linkCooldownMs = 0)
    public record K03(byte[] key, byte[] owner) {}
    @OwnedInit
    public static K03 add03(byte[] key, Ctx ctx) {
        return new K03(key, ctx.caller());
    }
    @OwnedDestroy(retire = true)
    @SignedBy("owner")
    public static JulcList<Payout> leave03(K03 k, Ctx ctx) {
        return ctx.balance().signum() > 0 ? Payouts.of(k.owner(), ctx.balance()) : Payouts.none();
    }

    @Owned(key = "key", maxKeyBytes = 32, linkCooldownMs = 0)
    public record K04(byte[] key, byte[] owner) {}
    @OwnedInit
    public static K04 add04(byte[] key, Ctx ctx) {
        return new K04(key, ctx.caller());
    }
    @OwnedDestroy(retire = true)
    @SignedBy("owner")
    public static JulcList<Payout> leave04(K04 k, Ctx ctx) {
        return ctx.balance().signum() > 0 ? Payouts.of(k.owner(), ctx.balance()) : Payouts.none();
    }

    @Owned(key = "key", maxKeyBytes = 32, linkCooldownMs = 0)
    public record K05(byte[] key, byte[] owner) {}
    @OwnedInit
    public static K05 add05(byte[] key, Ctx ctx) {
        return new K05(key, ctx.caller());
    }
    @OwnedDestroy(retire = true)
    @SignedBy("owner")
    public static JulcList<Payout> leave05(K05 k, Ctx ctx) {
        return ctx.balance().signum() > 0 ? Payouts.of(k.owner(), ctx.balance()) : Payouts.none();
    }

    @Owned(key = "key", maxKeyBytes = 32, linkCooldownMs = 0)
    public record K06(byte[] key, byte[] owner) {}
    @OwnedInit
    public static K06 add06(byte[] key, Ctx ctx) {
        return new K06(key, ctx.caller());
    }
    @OwnedDestroy(retire = true)
    @SignedBy("owner")
    public static JulcList<Payout> leave06(K06 k, Ctx ctx) {
        return ctx.balance().signum() > 0 ? Payouts.of(k.owner(), ctx.balance()) : Payouts.none();
    }

    @Owned(key = "key", maxKeyBytes = 32, linkCooldownMs = 0)
    public record K07(byte[] key, byte[] owner) {}
    @OwnedInit
    public static K07 add07(byte[] key, Ctx ctx) {
        return new K07(key, ctx.caller());
    }
    @OwnedDestroy(retire = true)
    @SignedBy("owner")
    public static JulcList<Payout> leave07(K07 k, Ctx ctx) {
        return ctx.balance().signum() > 0 ? Payouts.of(k.owner(), ctx.balance()) : Payouts.none();
    }

    @Owned(key = "key", maxKeyBytes = 32, linkCooldownMs = 0)
    public record K08(byte[] key, byte[] owner) {}
    @OwnedInit
    public static K08 add08(byte[] key, Ctx ctx) {
        return new K08(key, ctx.caller());
    }
    @OwnedDestroy(retire = true)
    @SignedBy("owner")
    public static JulcList<Payout> leave08(K08 k, Ctx ctx) {
        return ctx.balance().signum() > 0 ? Payouts.of(k.owner(), ctx.balance()) : Payouts.none();
    }

    @Owned(key = "key", maxKeyBytes = 32, linkCooldownMs = 0)
    public record K09(byte[] key, byte[] owner) {}
    @OwnedInit
    public static K09 add09(byte[] key, Ctx ctx) {
        return new K09(key, ctx.caller());
    }
    @OwnedDestroy(retire = true)
    @SignedBy("owner")
    public static JulcList<Payout> leave09(K09 k, Ctx ctx) {
        return ctx.balance().signum() > 0 ? Payouts.of(k.owner(), ctx.balance()) : Payouts.none();
    }

    @Owned(key = "key", maxKeyBytes = 32, linkCooldownMs = 0)
    public record K10(byte[] key, byte[] owner) {}
    @OwnedInit
    public static K10 add10(byte[] key, Ctx ctx) {
        return new K10(key, ctx.caller());
    }
    @OwnedDestroy(retire = true)
    @SignedBy("owner")
    public static JulcList<Payout> leave10(K10 k, Ctx ctx) {
        return ctx.balance().signum() > 0 ? Payouts.of(k.owner(), ctx.balance()) : Payouts.none();
    }





}
