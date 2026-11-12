package org.julclang.state.dsl.v2;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/**
 * Ed25519 verification as the ledger's {@code verifyEd25519Signature} answers it, for the reference interpreter (ADR-0018
 * D8, D15; review C3, S5). The builtin is libsodium's {@code crypto_sign_verify_detached} (cardano-crypto-class), whose
 * acceptance rules are stricter than RFC 8032 and than the JDK's: it answers false when S is not below the group order,
 * when R or the key A is a point of small order (by their encodings), when A is not canonical or not on the curve, and
 * otherwise when the encoding of [S]B − [h]A differs from R's bytes, h = SHA-512(R ‖ A ‖ M) mod L. Written here from
 * those rules with plain {@code BigInteger} arithmetic, independent of the JVM's provider and of the JuLC VM.
 */
final class Ed25519 {
    private Ed25519() {}

    static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
    static final BigInteger L = BigInteger.TWO.pow(252).add(new BigInteger("27742317777372353535851937790883648493"));
    static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    static final BigInteger I = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);   // sqrt(-1)
    static final BigInteger BY = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
    static final BigInteger[] B = {xFor(BY, false), BY, BigInteger.ONE, xFor(BY, false).multiply(BY).mod(P)};

    /**
     * libsodium's {@code ge25519_has_small_order} blocklist, as the y coordinates it compares (the encoding with its top
     * bit masked): 0 and p (order 4), 1 and p + 1 (order 1), p − 1 (order 2), and the two order-8 points.
     */
    static final List<BigInteger> SMALL_ORDER_Y = List.of(BigInteger.ZERO, BigInteger.ONE,
            new BigInteger("2707385501144840649318225287225658788936804267575313519463743609750303402022"),
            new BigInteger("55188659117513257062467267217118295137698188065244968500265048394206261417927"),
            P.subtract(BigInteger.ONE), P, P.add(BigInteger.ONE));

    /** The ledger's answer; the lengths (32, 64) are checked by the caller, where the builtin fails. */
    static boolean verify(byte[] pk, byte[] msg, byte[] sig) {
        byte[] r = Arrays.copyOfRange(sig, 0, 32);
        BigInteger s = le(Arrays.copyOfRange(sig, 32, 64));
        if (s.compareTo(L) >= 0) return false;                 // S not canonical
        if (smallOrder(r)) return false;                        // R of small order
        if (!canonical(pk) || smallOrder(pk)) return false;     // A not canonical, or of small order
        BigInteger[] a = decode(pk);
        if (a == null) return false;                            // A not on the curve
        BigInteger h = le(sha512(r, pk, msg)).mod(L);
        BigInteger[] check = add(mul(s, B), mul(h, neg(a)));
        return Arrays.equals(encode(check), r);                 // compared as bytes, as crypto_verify_32 does
    }

    static boolean smallOrder(byte[] enc) {
        return SMALL_ORDER_Y.contains(yMasked(enc));
    }

    /** libsodium's {@code ge25519_is_canonical}: the y coordinate (top bit masked) is below p. */
    static boolean canonical(byte[] enc) {
        return yMasked(enc).compareTo(P) < 0;
    }

    static BigInteger yMasked(byte[] enc) {
        byte[] b = enc.clone();
        b[31] &= 0x7f;
        return le(b);
    }

    static BigInteger le(byte[] b) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) r[i] = b[b.length - 1 - i];
        return new BigInteger(1, r);
    }

    static byte[] sha512(byte[]... parts) {
        try {
            var md = MessageDigest.getInstance("SHA-512");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** x for y with the given sign, or null when there is none. */
    static BigInteger xFor(BigInteger y, boolean odd) {
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        BigInteger x2 = u.multiply(v.modInverse(P)).mod(P);
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).subtract(x2).mod(P).equals(BigInteger.ZERO)) x = x.multiply(I).mod(P);
        if (!x.multiply(x).subtract(x2).mod(P).equals(BigInteger.ZERO)) return null;
        if (x.testBit(0) != odd) x = P.subtract(x).mod(P);
        return x;
    }

    /** Extended coordinates (X, Y, Z, T) of a canonical encoding, or null. */
    static BigInteger[] decode(byte[] enc) {
        BigInteger y = yMasked(enc);
        if (y.compareTo(P) >= 0) return null;
        BigInteger x = xFor(y, (enc[31] & 0x80) != 0);
        if (x == null) return null;
        return new BigInteger[]{x, y, BigInteger.ONE, x.multiply(y).mod(P)};
    }

    static byte[] encode(BigInteger[] p) {
        BigInteger zi = p[2].modInverse(P);
        BigInteger x = p[0].multiply(zi).mod(P), y = p[1].multiply(zi).mod(P);
        byte[] be = y.toByteArray(), out = new byte[32];
        for (int i = 0; i < be.length && i < 32; i++) out[i] = be[be.length - 1 - i];
        if (x.testBit(0)) out[31] |= (byte) 0x80;
        return out;
    }

    static BigInteger[] neg(BigInteger[] p) {
        return new BigInteger[]{P.subtract(p[0]).mod(P), p[1], p[2], P.subtract(p[3]).mod(P)};
    }

    /** Point addition in extended coordinates (a = −1). */
    static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        BigInteger a = p[1].subtract(p[0]).multiply(q[1].subtract(q[0])).mod(P);
        BigInteger b = p[1].add(p[0]).multiply(q[1].add(q[0])).mod(P);
        BigInteger c = BigInteger.TWO.multiply(D).multiply(p[3]).multiply(q[3]).mod(P);
        BigInteger d = BigInteger.TWO.multiply(p[2]).multiply(q[2]).mod(P);
        BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
        return new BigInteger[]{e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }

    static BigInteger[] mul(BigInteger k, BigInteger[] p) {
        BigInteger[] r = {BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO};
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = add(r, r);
            if (k.testBit(i)) r = add(r, p);
        }
        return r;
    }
}
