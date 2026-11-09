package org.julclang.state.api;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.Deposit;
import org.julclang.state.api.DepositClaim;
import org.julclang.state.api.ForeignInstance;
import org.julclang.stdlib.annotation.OnchainLibrary;

import java.math.BigInteger;

/** Builders for the deposits a method makes, and totals of the deposits a receiver gets (ADR-0011 D3). */
@OnchainLibrary
public class Deposits {

    public static JulcList<Deposit> none() {
        return JulcList.empty();
    }

    /** One deposit of {@code value} into {@code target}, with {@code memo}. */
    public static JulcList<Deposit> of(ForeignInstance target, Value value, PlutusData memo) {
        JulcList<Deposit> empty = JulcList.empty();
        return empty.prepend(new Deposit(target.policy(), target.instance(), value, memo));
    }

    public static JulcList<Deposit> add(JulcList<Deposit> deposits, ForeignInstance target, Value value, PlutusData memo) {
        return deposits.prepend(new Deposit(target.policy(), target.instance(), value, memo));
    }

    /** The lovelace of every deposit a receiver got ({@code funds.deposits()}). */
    public static BigInteger lovelace(JulcList<DepositClaim> deposits) {
        BigInteger total = BigInteger.ZERO;
        for (var d : deposits) {
            total = total.add(Assets.lovelaceOf(d.value()));
        }
        return total;
    }
}
