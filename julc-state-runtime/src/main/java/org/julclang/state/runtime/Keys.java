package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;

/** Key helpers. */
public final class Keys {
    private Keys() {}

    /** Payment key hash of an account (28 bytes). */
    public static byte[] pkh(Account account) {
        return account.hdKeyPair().getPublicKey().getKeyHash();
    }
}
