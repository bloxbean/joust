package org.julclang.state.api;

import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.StakingCredential;
import org.julclang.core.PlutusData;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ByteStringLib;

import java.util.Optional;

/**
 * Audited adapters for {@code .jsc}'s {@code Address} and {@code Credential} (ADR-0018 D5, D8; M2): construction,
 * projection, structural equality and the checked-input rule, one body on the JVM and on-chain. Credentials are the
 * ledger's ({@code PubKeyCredential}, {@code ScriptCredential}); an address is a payment credential and an optional
 * staking credential ({@code StakingHash} or {@code StakingPtr}), encoded as the ledger encodes them. A checked input
 * never holds a pointer ({@link #addressOk}), so in {@code .jsc} an address's staking part is none or a hash.
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

    /**
     * Whether a send to {@code a} can be delivered (ADR-0007 I30): never at the contract's own script ({@code policy}, the
     * script's hash), whose input every call spends, so the validator refuses such a send. A key, or another script, is
     * fine here; another script spent in the same transaction is a transaction-level rule (semantics §2, the transaction-level rules).
     */
    public static boolean notOwnScript(Address a, byte[] policy) {
        Credential c = a.credential();
        return switch (c) {
            case Credential.PubKeyCredential k -> true;
            case Credential.ScriptCredential s -> !ByteStringLib.equals(scriptOf(s), policy);
        };
    }

    // ------------------------------------------------------------------ checked inputs (ADR-0018 D5, J15)
    //
    // Decided on the data itself, not through the typed accessors: a transition program (and any code that JuLC's strict
    // entrypoint decoding does not stand in front of) may be handed a value whose constructor tags or field counts are
    // not the ledger's, and the accessors project fields of whatever constructor is there, while Optional.isPresent()
    // reads only "tag 0". So every tag and every field count is checked here. The same body runs on the JVM, where a
    // ledger record converts to its data (Builtins' Object overloads), and on-chain, where it is that data.

    /** A checked input: the ledger's encoding of a credential (Constr 0 or 1 with one field), holding a 28-byte hash. */
    public static boolean credentialOk(Credential c) {
        return credentialData(Builtins.constrTag(c), Builtins.constrFields(c));
    }

    /**
     * A checked input: the ledger's encoding of an address (Constr 0 [payment, staking]), a checked payment credential, and
     * a staking part that is none (Constr 1 []) or a staking hash (Constr 0 [Constr 0 [credential]]) with a checked
     * credential. A staking pointer is refused (review S1): Conway gives pointers no staking meaning, their components'
     * widths are the era's, and a stored pointer no output could carry would make every send to that address fail.
     */
    public static boolean addressOk(Address a) {
        return addressData(Builtins.constrTag(a), Builtins.constrFields(a));
    }

    static boolean addressData(long tag, PlutusData fields) {
        if (tag == 0L && two(fields)) {
            PlutusData payment = Builtins.headList(fields);
            PlutusData stake = Builtins.headList(Builtins.tailList(fields));
            return credentialData(Builtins.constrTag(payment), Builtins.constrFields(payment))
                    && stakeData(Builtins.constrTag(stake), Builtins.constrFields(stake));
        } else {
            return false;
        }
    }

    /** The staking part: None (Constr 1 []) or Some (Constr 0 [a staking hash]). */
    static boolean stakeData(long tag, PlutusData fields) {
        if (tag == 1L) {
            return Builtins.nullList(fields);
        } else if (tag == 0L && one(fields)) {
            PlutusData s = Builtins.headList(fields);
            return stakingHashData(Builtins.constrTag(s), Builtins.constrFields(s));
        } else {
            return false;
        }
    }

    /** A staking hash: Constr 0 [credential] (Constr 1, a pointer, is refused). */
    static boolean stakingHashData(long tag, PlutusData fields) {
        if (tag == 0L && one(fields)) {
            PlutusData c = Builtins.headList(fields);
            return credentialData(Builtins.constrTag(c), Builtins.constrFields(c));
        } else {
            return false;
        }
    }

    /** Whether a list of fields has exactly one element. */
    static boolean one(PlutusData fields) {
        return !Builtins.nullList(fields) && Builtins.nullList(Builtins.tailList(fields));
    }

    /** Whether a list of fields has exactly two elements. */
    static boolean two(PlutusData fields) {
        return !Builtins.nullList(fields) && one(Builtins.tailList(fields));
    }

    /** A credential: Constr 0 (a key) or Constr 1 (a script), with one field, a 28-byte hash. */
    static boolean credentialData(long tag, PlutusData fields) {
        if ((tag == 0L || tag == 1L) && one(fields)) {
            return Builtins.lengthOfByteString(Builtins.unBData(Builtins.headList(fields))) == 28L;
        } else {
            return false;
        }
    }
}
