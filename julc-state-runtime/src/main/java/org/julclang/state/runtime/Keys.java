package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;

/** Key helpers. */
public final class Keys {
    private Keys() {}

    /** Payment key hash of an account (28 bytes). */
    public static byte[] pkh(Account account) {
        if (account instanceof WalletAccount w) return w.paymentKeyHash();   // from the address (ADR-0013 D1)
        return account.hdKeyPair().getPublicKey().getKeyHash();
    }
}
