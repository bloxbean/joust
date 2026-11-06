package org.julclang.state.examples.rollup;

import org.julclang.core.types.JulcList;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ByteStringLib;
import org.julclang.stdlib.lib.CryptoLib;

import java.math.BigInteger;

/**
 * The L2 program of the optimistic-rollup spike (ADR-0006 D1): a payments ledger. Pure, total functions in JuLC's
 * subset, so the sequencer runs them on the JVM and a fraud proof re-runs one step on-chain.
 * <p>
 * Total: no input makes a function fail. An invalid order is a no-op ({@link #step}); every encoding is domain-tagged,
 * length-prefixed and sign-aware, so even a malformed log can be hashed (and disputed) and no two different values
 * share an encoding (ADR-0006 r2).
 * <p>
 * Semantics: an order moves {@code amount > 0} from {@code from} to {@code to}, needs {@code from}'s next nonce and
 * balance, and a signature over {@link #message}. {@code from == to} is a valid no-transfer that uses up the nonce. A
 * new {@code to} becomes an account, up to {@link #MAX_ACCOUNTS}. An invalid order changes nothing (no nonce).
 */
@OnchainLibrary
public final class Ledger {

    private Ledger() {}

    /** The most accounts a book holds: a fraud proof carries the whole pre-state (ADR-0006 r2). */
    public static final long MAX_ACCOUNTS = 32;

    public record Acct(byte[] key, BigInteger balance, BigInteger nonce) {}

    /** The L2 state: a domain (unique per rollup, so orders can't replay across rollups) and the accounts. */
    public record Book(byte[] domain, JulcList<Acct> accounts) {}

    /** A transfer, signed by {@code from}'s ed25519 key over {@link #message}; {@code nonce} is from's next nonce. */
    public record Order(byte[] from, byte[] to, BigInteger amount, BigInteger nonce) {}

    /** One sequenced step: the order, its signature and the post-state hash. */
    public record Step(Order order, byte[] sig, byte[] post) {}

    // ------------------------------------------------------------------ encodings (total, unambiguous, tagged)

    /** {@code I2OSP(len, 2) ‖ b}. */
    public static byte[] bytesEnc(byte[] b) {
        return ByteStringLib.append(ByteStringLib.integerToByteString(true, 2L, ByteStringLib.length(b)), b);
    }

    /** A sign byte, then the magnitude's minimal big-endian bytes, length-prefixed. */
    public static byte[] intEnc(BigInteger n) {
        boolean negative = n.compareTo(BigInteger.ZERO) < 0;
        BigInteger magnitude = negative ? n.negate() : n;
        byte[] body = bytesEnc(ByteStringLib.integerToByteString(true, 0L, magnitude));
        return negative ? ByteStringLib.cons(1L, body) : ByteStringLib.cons(0L, body);
    }

    public static byte[] orderEnc(Order o) {
        return ByteStringLib.append(ByteStringLib.append(bytesEnc(o.from()), bytesEnc(o.to())),
                ByteStringLib.append(intEnc(o.amount()), intEnc(o.nonce())));
    }

    /** What the sender signs: "joust-l2/order" ‖ the rollup's domain ‖ the order. */
    public static byte[] message(byte[] domain, Order o) {
        byte[] tag = new byte[]{106, 111, 117, 115, 116, 45, 108, 50, 47, 111, 114, 100, 101, 114};
        return ByteStringLib.append(tag, ByteStringLib.append(bytesEnc(domain), orderEnc(o)));
    }

    /** The state commitment: "joust-l2/state" ‖ domain ‖ account count ‖ each account, in order. */
    public static byte[] hash(Book b) {
        byte[] tag = new byte[]{106, 111, 117, 115, 116, 45, 108, 50, 47, 115, 116, 97, 116, 101};
        byte[] body = ByteStringLib.empty();
        BigInteger n = BigInteger.ZERO;
        for (var a : b.accounts()) {
            body = ByteStringLib.append(body, ByteStringLib.append(bytesEnc(a.key()),
                    ByteStringLib.append(intEnc(a.balance()), intEnc(a.nonce()))));
            n = n.add(BigInteger.ONE);
        }
        byte[] head = ByteStringLib.append(tag, ByteStringLib.append(bytesEnc(b.domain()), intEnc(n)));
        return CryptoLib.blake2b_256(ByteStringLib.append(head, body));
    }

    /** The log commitment: {@code h₀ = blake2b("joust-l2/log")}, {@code h ← blake2b(h ‖ order ‖ sig ‖ post)}. */
    public static byte[] logHash(JulcList<Step> steps) {
        byte[] h = CryptoLib.blake2b_256(new byte[]{106, 111, 117, 115, 116, 45, 108, 50, 47, 108, 111, 103});
        for (var s : steps) {
            Order o = s.order();
            byte[] body = ByteStringLib.append(orderEnc(o), ByteStringLib.append(bytesEnc(s.sig()), bytesEnc(s.post())));
            h = CryptoLib.blake2b_256(ByteStringLib.append(h, body));
        }
        return h;
    }

    public static BigInteger count(JulcList<Step> steps) {
        BigInteger n = BigInteger.ZERO;
        for (var s : steps) {
            n = n.add(BigInteger.ONE);
        }
        return n;
    }

    /** The last step's post-state hash (the checkpoint's new state), or {@code otherwise} if there are no steps. */
    public static byte[] lastPost(JulcList<Step> steps, byte[] otherwise) {
        byte[] last = otherwise;
        for (var s : steps) {
            last = s.post();
        }
        return last;
    }

    // ------------------------------------------------------------------ the program

    /** The account of {@code key}: its balance and nonce, zero if absent; {@code found} tells which. */
    public record Lookup(boolean found, BigInteger balance, BigInteger nonce, BigInteger size) {}

    public static Lookup lookup(Book b, byte[] key) {
        boolean found = false;
        BigInteger balance = BigInteger.ZERO;
        BigInteger nonce = BigInteger.ZERO;
        BigInteger size = BigInteger.ZERO;
        for (var a : b.accounts()) {
            if (ByteStringLib.equals(a.key(), key)) {
                found = true;
                balance = a.balance();
                nonce = a.nonce();
            }
            size = size.add(BigInteger.ONE);
        }
        return new Lookup(found, balance, nonce, size);
    }

    public static BigInteger balanceOf(Book b, byte[] key) {
        Lookup l = lookup(b, key);
        return l.balance();
    }

    public static BigInteger nonceOf(Book b, byte[] key) {
        Lookup l = lookup(b, key);
        return l.nonce();
    }

    /**
     * Valid: well-formed keys and signature (checked before anything that would fail on malformed input), positive
     * amount, from's next nonce, room for a new account, a correct signature, and enough balance.
     */
    public static boolean valid(Book b, Order o, byte[] sig) {
        boolean shape = ByteStringLib.length(o.from()) == 32 && ByteStringLib.length(o.to()) == 32
                && ByteStringLib.length(sig) == 64;
        if (!shape || o.amount().compareTo(BigInteger.ZERO) <= 0) {
            return false;
        }
        Lookup from = lookup(b, o.from());
        Lookup to = lookup(b, o.to());
        boolean room = to.found() || to.size().compareTo(BigInteger.valueOf(32)) < 0;
        return from.found() && o.nonce().compareTo(from.nonce()) == 0 && room
                && from.balance().compareTo(o.amount()) >= 0
                && CryptoLib.verifyEd25519Signature(o.from(), message(b.domain(), o), sig);
    }

    /** Adds {@code balance} and {@code nonce} deltas to {@code key}'s account; a new key is appended. */
    public static JulcList<Acct> update(JulcList<Acct> accounts, byte[] key, BigInteger balance, BigInteger nonce) {
        JulcList<Acct> reversed = JulcList.empty();
        boolean found = false;
        for (var a : accounts) {
            if (ByteStringLib.equals(a.key(), key)) {
                reversed = reversed.prepend(new Acct(a.key(), a.balance().add(balance), a.nonce().add(nonce)));
                found = true;
            } else {
                reversed = reversed.prepend(a);
            }
        }
        JulcList<Acct> withNew = found ? reversed : reversed.prepend(new Acct(key, balance, nonce));
        return withNew.reverse();
    }

    /** One step, total: a valid order moves the funds and uses up the nonce; an invalid one changes nothing. */
    public static Book step(Book b, Order o, byte[] sig) {
        if (!valid(b, o, sig)) {
            return b;
        }
        JulcList<Acct> debited = update(b.accounts(), o.from(), o.amount().negate(), BigInteger.ONE);
        return new Book(b.domain(), update(debited, o.to(), o.amount(), BigInteger.ZERO));
    }

    /**
     * ADR-0006 D2 (r2): step {@code index} is fraudulent if {@code pre} is the state before it (the final state for
     * step 0, else the previous step's post-hash) and re-running it does not give its post-hash.
     */
    public static boolean fraud(byte[] finalHash, JulcList<Step> steps, BigInteger index, Book pre) {
        Step target = steps.head();
        byte[] targetPre = finalHash;
        byte[] prev = finalHash;
        BigInteger i = BigInteger.ZERO;
        for (var s : steps) {
            if (i.compareTo(index) == 0) {
                target = s;
                targetPre = prev;
            }
            prev = s.post();
            i = i.add(BigInteger.ONE);
        }
        boolean inRange = index.compareTo(BigInteger.ZERO) >= 0 && index.compareTo(i) < 0;
        if (!inRange || !ByteStringLib.equals(hash(pre), targetPre)) {
            return false;
        }
        Order order = target.order();
        Book expected = step(pre, order, target.sig());
        return !ByteStringLib.equals(hash(expected), target.post());
    }
}
