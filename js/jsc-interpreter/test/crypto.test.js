// The interpreter's own hashes and Ed25519, against published vectors and against Node's OpenSSL (which the
// interpreter does not use: it must also run where there is no node:crypto).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, sign, randomBytes } from 'node:crypto';
import { sha256, sha512, sha3_256, blake2b, verifyEd25519, hex, fromHex } from '../src/index.js';

const utf8 = (s) => new TextEncoder().encode(s);
const node = (alg, b) => new Uint8Array(createHash(alg).update(b).digest());

/** Inputs around every block boundary of the four hashes (64, 128, 136 bytes). */
function inputs() {
  const out = [new Uint8Array(0), utf8('abc')];
  for (const n of [55, 56, 63, 64, 65, 111, 112, 127, 128, 129, 135, 136, 137, 255, 256, 1000]) out.push(Uint8Array.from(randomBytes(n)));
  return out;
}

test('published vectors: SHA-256, SHA-512, SHA3-256, BLAKE2b', () => {
  assert.equal(hex(sha256(utf8('abc'))), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  assert.equal(hex(sha256(new Uint8Array(0))), 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
  assert.equal(hex(sha512(utf8('abc'))).slice(0, 32), 'ddaf35a193617abacc417349ae204131');
  assert.equal(hex(sha3_256(new Uint8Array(0))), 'a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a');
  assert.equal(hex(sha3_256(utf8('abc'))), '3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532');
  assert.equal(hex(blake2b(new Uint8Array(0), 32)), '0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8');
  assert.equal(hex(blake2b(new Uint8Array(0), 28)), '836cc68931c2e4e3e838602eca1902591d216837bafddfe6f0c8cb07');
  assert.equal(hex(blake2b(utf8('abc'), 64)).slice(0, 32), 'ba80a53f981c4d0d6a2797b69f12f6e9');
});

test('every hash equals OpenSSL at each block boundary', () => {
  for (const b of inputs()) {
    assert.equal(hex(sha256(b)), hex(node('sha256', b)), `sha256 of ${b.length} bytes`);
    assert.equal(hex(sha512(b)), hex(node('sha512', b)), `sha512 of ${b.length} bytes`);
    assert.equal(hex(sha3_256(b)), hex(node('sha3-256', b)), `sha3-256 of ${b.length} bytes`);
    assert.equal(hex(blake2b(b, 64)), hex(node('blake2b512', b)), `blake2b-512 of ${b.length} bytes`);
  }
});

// RFC 8032 §7.1, test 1 (empty message) and test 2 (one byte).
const RFC1 = {
  key: fromHex('d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a'),
  msg: new Uint8Array(0),
  sig: fromHex('e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b'),
};
const RFC2 = {
  key: fromHex('3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c'),
  msg: fromHex('72'),
  sig: fromHex('92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00'),
};

const P = 2n ** 255n - 19n;
const L = 2n ** 252n + 27742317777372353535851937790883648493n;
const le32 = (n) => {
  const out = new Uint8Array(32);
  for (let i = 0; i < 32; i++, n >>= 8n) out[i] = Number(n & 0xffn);
  return out;
};

test("Ed25519: RFC 8032's vectors, and libsodium's refusals (semantics §3.11)", () => {
  assert.equal(verifyEd25519(RFC1.key, RFC1.msg, RFC1.sig), true);
  assert.equal(verifyEd25519(RFC2.key, RFC2.msg, RFC2.sig), true);
  assert.equal(verifyEd25519(RFC1.key, fromHex('00'), RFC1.sig), false, 'another message');
  const flipped = Uint8Array.from(RFC1.sig);
  flipped[0] ^= 1;
  assert.equal(verifyEd25519(RFC1.key, RFC1.msg, flipped), false, 'R changed');
  // S + L: the same point equation, but S is not canonical.
  const s = RFC1.sig.subarray(32);
  let sv = 0n;
  for (let i = 31; i >= 0; i--) sv = (sv << 8n) | BigInt(s[i]);
  const sPlusL = Uint8Array.from([...RFC1.sig.subarray(0, 32), ...le32(sv + L)]);
  assert.equal(verifyEd25519(RFC1.key, RFC1.msg, sPlusL), false, 'S + L');
  // The identity as the key, with R the identity and S = 0: [0]B − [h]·0 is the identity, which the RFC accepts and
  // libsodium refuses (a small-order key and R).
  const identity = le32(1n);
  assert.equal(verifyEd25519(identity, new Uint8Array(0), Uint8Array.from([...identity, ...new Uint8Array(32)])), false);
  // A key encoding p + 1 (y = 1, not canonical), and a key that is no curve point (y = 2).
  assert.equal(verifyEd25519(le32(P + 1n), RFC1.msg, RFC1.sig), false, 'p + 1');
  assert.equal(verifyEd25519(le32(2n), RFC1.msg, RFC1.sig), false, 'no curve point');
});

test('Ed25519: signatures made by OpenSSL verify, and changed ones do not', () => {
  for (let i = 0; i < 8; i++) {
    const { publicKey, privateKey } = generateKeyPairSync('ed25519');
    const key = new Uint8Array(publicKey.export({ format: 'der', type: 'spki' }).subarray(12));
    const msg = Uint8Array.from(randomBytes(i * 17));
    const sig = new Uint8Array(sign(null, msg, privateKey));
    assert.equal(verifyEd25519(key, msg, sig), true);
    const bad = Uint8Array.from(sig);
    bad[40] ^= 0x10;
    assert.equal(verifyEd25519(key, msg, bad), false);
    const other = Uint8Array.from([...msg, 1]);
    assert.equal(verifyEd25519(key, other, sig), false);
  }
});

// ------------------------------------------------------------------ the ledger's libsodium, recorded (review F2, S2)

import { readFileSync } from 'node:fs';

const fixture = (name) => JSON.parse(readFileSync(new URL(`./fixtures/${name}`, import.meta.url), 'utf8'));

test('Ed25519: 783 edge vectors answered as the ledger\'s libsodium 1.0.18 answers them', () => {
  // The oracle and generator are named in the file. Categories: every small-order encoding (both sign bits) as the key,
  // as R, and as both with S = 0; R = identity with S = h·a; y = p … p + 18 and 2^255 − 18 … 2^255 − 1 as the key and as
  // R; keys off the curve; mixed-order keys aB + T (T of order 2, 4 and 8), accepted exactly when the cofactorless
  // equation holds (h = 0 mod 8 for order 8); mixed-order R with mixed and prime-order keys; S at L − 1, L, L + S,
  // 2^253 − 1 and 2^256 − 1; honest signatures and messages of 0 to 1,000 bytes.
  const { oracle, vectors } = fixture('ed25519-libsodium.json');
  assert.match(oracle, /libsodium 1\.0\.18, IOG build/);
  const wrong = vectors.filter((v) => verifyEd25519(fromHex(v.key), fromHex(v.msg), fromHex(v.sig)) !== v.valid);
  assert.deepEqual(wrong.map((v) => v.label), []);
  assert.equal(vectors.length, 783);
  const count = (re, valid) => vectors.filter((v) => re.test(v.label) && v.valid === valid).length;
  assert.ok(count(/^R=identity-S=ha$/, false) >= 8, 'R = identity, S = h·a: the cofactorless equation holds, libsodium refuses');
  assert.ok(count(/^A-mixed8-h0mod8$/, true) >= 6 && count(/^A-mixed8-hnot0$/, false) >= 6, 'mixed-order keys, both ways');
  assert.ok(count(/^R-mixed-A-mixed-eqholds$/, true) >= 10, 'mixed R with a mixed key, accepted');
  assert.ok(count(/^R-noncanon-on$/, false) >= 10, 'a non-canonical R encoding of a curve point is always false (§3.11)');
});

test('Ed25519: the vectors that tell libsodium from RFC 8032, by name (security review S2)', () => {
  // R = identity, S = h·a: OpenSSL (RFC 8032) accepts; libsodium refuses (R of small order). Removing rule 2 of §3.11
  // turns this true.
  assert.equal(verifyEd25519(fromHex('2550eb0033a7279366447f5bd1a6de211ce90d1837221e91c9f486e054d575be'), fromHex('aba77c1609'),
    fromHex('0100000000000000000000000000000000000000000000000000000000000000549901fff159b3bd013411f4c775a01b4cdfe48db6e2acd7b0ea69a064a24109')), false);
  // A mixed-order key whose signature verifies on libsodium, JuLC's VM, OpenSSL and here.
  assert.equal(verifyEd25519(fromHex('b3a443e23dceba54af4a44833cf249756e0bccde25a5833efb3b6545b2e652d9'), fromHex('ba5816162ccc382a'),
    fromHex('723d49c93446b0efc44a835ea9a3904b708c3041dca86206db2e90660587a5cf59c5746fbdd58a0eb8ca9d8e9e9e0521814e2f765fa8d33d9586a3edbe175b05')), true);
});

test('hashes: multi-block vectors from the ledger\'s libsodium (BLAKE2b-224/256, SHA-256/512) and hashlib (SHA3-256)', () => {
  const { vectors } = fixture('hashes.json');
  assert.equal(vectors.length, 18);
  for (const v of vectors) {
    const b = fromHex(v.input);
    assert.equal(hex(blake2b(b, 28)), v.blake2b_224, `blake2b_224 of ${b.length} bytes`);
    assert.equal(hex(blake2b(b, 32)), v.blake2b_256, `blake2b_256 of ${b.length} bytes`);
    assert.equal(hex(sha256(b)), v.sha2_256, `sha2_256 of ${b.length} bytes`);
    assert.equal(hex(sha512(b)), v.sha512, `sha512 of ${b.length} bytes`);
    assert.equal(hex(sha3_256(b)), v.sha3_256, `sha3_256 of ${b.length} bytes`);
  }
});
