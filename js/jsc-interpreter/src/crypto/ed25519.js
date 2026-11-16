// Ed25519 verification as the ledger's verifyEd25519Signature answers it: libsodium's crypto_sign_verify_detached
// (docs/reference/jsc-2-semantics.md §3.11). The lengths (key 32, signature 64) are the caller's check, where the
// builtin fails; here every input gets true or false. Plain BigInt arithmetic over the twisted Edwards curve
// -x² + y² = 1 + d·x²·y² mod p, points in extended coordinates (X : Y : Z : T), x = X/Z, y = Y/Z, xy = T/Z
// (RFC 8032 §5.1.4's formulas).

import { sha512 } from './sha2.js';

const P = 2n ** 255n - 19n;
const L = 2n ** 252n + 27742317777372353535851937790883648493n;

const mod = (a, m = P) => ((a % m) + m) % m;

function pow(b, e, m = P) {
  let r = 1n;
  b = mod(b, m);
  while (e > 0n) {
    if (e & 1n) r = (r * b) % m;
    b = (b * b) % m;
    e >>= 1n;
  }
  return r;
}

const inv = (a) => pow(a, P - 2n);
const D = mod(-121665n * inv(121666n));
const SQRT_M1 = pow(2n, (P - 1n) / 4n);

/**
 * libsodium's small-order blocklist (ge25519_has_small_order), as y coordinates read with the top bit cleared: 0 and p
 * (order 4), 1 and p + 1 (the identity), p − 1 (order 2), and the two points of order 8.
 */
const SMALL_ORDER = new Set([
  0n, 1n, P - 1n, P, P + 1n,
  2707385501144840649318225287225658788936804267575313519463743609750303402022n,
  55188659117513257062467267217118295137698188065244968500265048394206261417927n,
]);

/** Little-endian bytes to an integer. */
function le(b) {
  let x = 0n;
  for (let i = b.length - 1; i >= 0; i--) x = (x << 8n) | BigInt(b[i]);
  return x;
}

/** The y coordinate an encoding states: little-endian, the top bit of the last byte cleared (it is x's sign). */
function maskedY(enc) {
  const b = Uint8Array.from(enc);
  b[31] &= 0x7f;
  return le(b);
}

/** x for y, with the parity the sign bit asks for; null when no x makes (x, y) a curve point. */
function recoverX(y, odd) {
  const y2 = (y * y) % P;
  const x2 = mod((y2 - 1n) * inv(D * y2 + 1n));
  let x = pow(x2, (P + 3n) / 8n);
  if (mod(x * x - x2) !== 0n) x = (x * SQRT_M1) % P;
  if (mod(x * x - x2) !== 0n) return null;
  // libsodium negates by the sign bit without RFC 8032's "x = 0 with the sign set" refusal; the only such points (y = 1,
  // y = p − 1) are on the small-order list, refused before this.
  if ((x & 1n) !== (odd ? 1n : 0n)) x = mod(-x);
  return x;
}

const point = (x, y) => [x, y, 1n, (x * y) % P];
const IDENTITY = [0n, 1n, 1n, 0n];
const BASE = point(recoverX(mod(4n * inv(5n)), false), mod(4n * inv(5n)));

function add([x1, y1, z1, t1], [x2, y2, z2, t2]) {
  const a = mod((y1 - x1) * (y2 - x2));
  const b = mod((y1 + x1) * (y2 + x2));
  const c = mod(2n * D * t1 * t2);
  const d = mod(2n * z1 * z2);
  const e = b - a, f = d - c, g = d + c, h = b + a;
  return [mod(e * f), mod(g * h), mod(f * g), mod(e * h)];
}

function times(k, p) {
  let r = IDENTITY;
  for (let i = BigInt(k.toString(2).length) - 1n; i >= 0n; i--) {
    r = add(r, r);
    if ((k >> i) & 1n) r = add(r, p);
  }
  return r;
}

function encode([x, y, z]) {
  const zi = inv(z);
  const ax = (x * zi) % P, ay = (y * zi) % P;
  const out = new Uint8Array(32);
  let v = ay;
  for (let i = 0; i < 32; i++) {
    out[i] = Number(v & 0xffn);
    v >>= 8n;
  }
  if (ax & 1n) out[31] |= 0x80;
  return out;
}

/** The ledger's answer for a 32-byte key and a 64-byte signature. */
export function verifyEd25519(key, msg, sig) {
  const r = sig.subarray(0, 32);
  const s = le(sig.subarray(32, 64));
  if (s >= L) return false;                                   // 1. S not canonical
  if (SMALL_ORDER.has(maskedY(r))) return false;              // 2. R of small order
  const ky = maskedY(key);
  if (ky >= P || SMALL_ORDER.has(ky)) return false;           // 3. the key not canonical, or of small order
  const kx = recoverX(ky, (key[31] & 0x80) !== 0);
  if (kx === null) return false;                              // 4. the key is no curve point
  const a = point(kx, ky);
  const h = le(sha512(Uint8Array.from([...r, ...key, ...msg]))) % L;
  const minusA = [mod(-a[0]), a[1], a[2], mod(-a[3])];
  const check = encode(add(times(s, BASE), times(h, minusA)));
  for (let i = 0; i < 32; i++) if (check[i] !== r[i]) return false;   // 5. compared as bytes
  return true;
}
