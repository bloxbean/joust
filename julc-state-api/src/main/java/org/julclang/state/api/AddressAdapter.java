package org.julclang.state.api;

import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.StakingCredential;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ByteStringLib;

import java.util.Optional;

/**
 * Audited adapters for {@code .jsc}'s {@code Address} and {@code Credential} (ADR-0018 D5, D8; M2): construction,
 * projection, structural equality and the checked-input rule, one body on the JVM and on-chain. Credentials are the
 * ledger's ({@code PubKeyCredential}, {@code ScriptCredential}); an address is a payment credential and an optional
 * staking credential ({@code StakingHash} or {@code StakingPtr}), encoded as the ledger encodes them.
 */
@OnchainLibrary
public class AddressAdapter {

    public static Credential keyCred(byte[] key) {
        return new Credential.PubKeyCredential(new PubKeyHash(key));
    }

    public static Credential scriptCred(byte[] hash) {
        return new Credential.ScriptCredential(new ScriptHash(hash));
    }

    /** An enterprise address of a key, or with a staking key. */
    public static Address wallet(byte[] key) {
        return new Address(new Credential.PubKeyCredential(new PubKeyHash(key)), Optional.empty());
    }

    public static Address walletStaked(byte[] key, byte[] stakeKey) {
        StakingCredential stake = new StakingCredential.StakingHash(new Credential.PubKeyCredential(new PubKeyHash(stakeKey)));
        return new Address(new Credential.PubKeyCredential(new PubKeyHash(key)), Optional.of(stake));
    }

    /** A script's address, or with a staking key. */
    public static Address script(byte[] hash) {
        return new Address(new Credential.ScriptCredential(new ScriptHash(hash)), Optional.empty());
    }

    public static Address scriptStaked(byte[] hash, byte[] stakeKey) {
        StakingCredential stake = new StakingCredential.StakingHash(new Credential.PubKeyCredential(new PubKeyHash(stakeKey)));
        return new Address(new Credential.ScriptCredential(new ScriptHash(hash)), Optional.of(stake));
    }

    /** {@code a.payment}. */
    public static Credential payment(Address a) {
        return a.credential();
    }

    /** The key hash of a key credential. */
    public static byte[] keyOf(Credential.PubKeyCredential c) {
        PubKeyHash h = c.hash();
        return h.hash();
    }

    /** The script hash of a script credential. */
    public static byte[] scriptOf(Credential.ScriptCredential c) {
        ScriptHash h = c.hash();
        return h.hash();
    }

    /** The bytes of a credential, of either kind. */
    public static byte[] hashOf(Credential c) {
        return switch (c) {
            case Credential.PubKeyCredential k -> keyOf(k);
            case Credential.ScriptCredential s -> scriptOf(s);
        };
    }

    /** std/address: the key hash an address is paid to, if it is a key's. */
    public static Optional<byte[]> paymentKey(Address a) {
        Credential c = a.credential();
        return switch (c) {
            case Credential.PubKeyCredential k -> Optional.of(keyOf(k));
            case Credential.ScriptCredential s -> Optional.empty();
        };
    }

    /** std/address: the script hash an address is paid to, if it is a script's. */
    public static Optional<byte[]> paymentScript(Address a) {
        Credential c = a.credential();
        return switch (c) {
            case Credential.PubKeyCredential k -> Optional.empty();
            case Credential.ScriptCredential s -> Optional.of(scriptOf(s));
        };
    }

    /** Structural equality: the same kind and the same hash. */
    public static boolean sameCredential(Credential a, Credential b) {
        return switch (a) {
            case Credential.PubKeyCredential x -> switch (b) {
                case Credential.PubKeyCredential y -> ByteStringLib.equals(keyOf(x), keyOf(y));
                case Credential.ScriptCredential y -> false;
            };
            case Credential.ScriptCredential x -> switch (b) {
                case Credential.PubKeyCredential y -> false;
                case Credential.ScriptCredential y -> ByteStringLib.equals(scriptOf(x), scriptOf(y));
            };
        };
    }

    /** Structural equality: the same payment credential and the same staking part (none, a hash or a pointer). */
    public static boolean sameAddress(Address a, Address b) {
        Optional<StakingCredential> sa = a.stakingCredential();
        Optional<StakingCredential> sb = b.stakingCredential();
        return sameCredential(a.credential(), b.credential()) && sameStake(sa, sb);
    }

    static boolean sameStake(Optional<StakingCredential> a, Optional<StakingCredential> b) {
        if (a.isPresent()) {
            return b.isPresent() && sameStaking(a.get(), b.get());
        } else {
            return b.isEmpty();
        }
    }

    static boolean sameStaking(StakingCredential a, StakingCredential b) {
        return switch (a) {
            case StakingCredential.StakingHash x -> switch (b) {
                case StakingCredential.StakingHash y -> sameCredential(x.credential(), y.credential());
                case StakingCredential.StakingPtr y -> false;
            };
            case StakingCredential.StakingPtr x -> switch (b) {
                case StakingCredential.StakingHash y -> false;
                case StakingCredential.StakingPtr y -> x.slot().compareTo(y.slot()) == 0 && x.txIndex().compareTo(y.txIndex()) == 0
                        && x.certIndex().compareTo(y.certIndex()) == 0;
            };
        };
    }

    /** A checked input (ADR-0018 D5): a credential holds a 28-byte hash. */
    public static boolean credentialOk(Credential c) {
        return ByteStringLib.length(hashOf(c)) == 28;
    }

    /** A checked input: a 28-byte payment credential, and a staking hash (if any) of 28 bytes too. */
    public static boolean addressOk(Address a) {
        Optional<StakingCredential> s = a.stakingCredential();
        return credentialOk(a.credential()) && stakeOk(s);
    }

    static boolean stakeOk(Optional<StakingCredential> s) {
        if (s.isPresent()) {
            StakingCredential c = s.get();
            return switch (c) {
                case StakingCredential.StakingHash h -> credentialOk(h.credential());
                case StakingCredential.StakingPtr p -> true;
            };
        } else {
            return true;
        }
    }
}
