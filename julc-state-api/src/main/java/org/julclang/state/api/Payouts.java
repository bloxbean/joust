package org.julclang.state.api;

import org.julclang.core.types.JulcList;
import org.julclang.stdlib.annotation.OnchainLibrary;

import java.math.BigInteger;

/** Builders for the payout list an action or destroy method returns. */
@OnchainLibrary
public class Payouts {

    public static JulcList<Payout> none() {
        return JulcList.empty();
    }

    public static JulcList<Payout> of(byte[] to, BigInteger lovelace) {
        JulcList<Payout> empty = JulcList.empty();
        return empty.prepend(new Payout(to, lovelace));
    }

    public static JulcList<Payout> add(JulcList<Payout> payouts, byte[] to, BigInteger lovelace) {
        return payouts.prepend(new Payout(to, lovelace));
    }

    public static BigInteger total(JulcList<Payout> payouts) {
        BigInteger sum = BigInteger.ZERO;
        for (var p : payouts) {
            sum = sum.add(p.lovelace());
        }
        return sum;
    }
}
