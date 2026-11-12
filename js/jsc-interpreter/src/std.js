// The std rows (docs/reference/jsc-2-semantics.md §3.10, ADR-0018 D8): each fails exactly outside its domain.

import { NONE, Refused, some, hex, toUnsigned, unsignedBytes } from './values.js';
import { sha256 } from './crypto/sha2.js';
import { sha3_256 } from './crypto/sha3.js';
import { blake2b } from './crypto/blake2b.js';
import { verifyEd25519 } from './crypto/ed25519.js';

const TWO_31 = 2n ** 31n;

/** n in [0, 2^31), else a failure naming the row. */
function index(row, what, n) {
  if (n < 0n || n >= TWO_31) throw new Refused(`${row}: ${what} must be in [0, 2^31)`);
  return Number(n);
}

/** Floor division: the quotient toward negative infinity, the remainder with the divisor's sign (or 0). */
function floorDivMod(a, b) {
  let q = a / b, r = a % b;   // BigInt truncates
  if (r !== 0n && (r < 0n) !== (b < 0n)) {
    q -= 1n;
    r += b;
  }
  return [q, r];
}

/** The bytes as an unsigned number of 8·len bits, shifted (zero fill) or rotated left by n (right when negative). */
function shiftOrRotate(b, n, rotate) {
  const bits = BigInt(8 * b.length);
  if (bits === 0n) return b;
  const x = toUnsigned(b), mask = (1n << bits) - 1n;
  let r;
  if (rotate) {
    const k = ((n % bits) + bits) % bits;
    r = ((x << k) | (x >> (bits - k))) & mask;
  } else if (n >= bits || -n >= bits) {
    r = 0n;
  } else {
    r = n >= 0n ? (x << n) & mask : x >> -n;
  }
  const raw = unsignedBytes(r), out = new Uint8Array(b.length);
  out.set(raw, b.length - raw.length);
  return out;
}

function bitwise(op, pad, a, b) {
  const n = pad ? Math.max(a.length, b.length) : Math.min(a.length, b.length);
  const out = new Uint8Array(n);
  for (let i = 0; i < n; i++) {
    if (i >= a.length) out[i] = b[i];
    else if (i >= b.length) out[i] = a[i];
    else out[i] = op === 'andBytes' ? a[i] & b[i] : op === 'orBytes' ? a[i] | b[i] : a[i] ^ b[i];
  }
  return out;
}

function popcount(b) {
  let c = 0;
  for (let x of b) for (; x; x &= x - 1) c++;
  return c;
}

/**
 * Applies a row. `args` are evaluated values; `same(a, b, type)` is the language's equality (§3.4); `elemType` is the
 * element type of a list argument; `tx` the call's transaction context.
 */
export function applyStd(name, args, { same, elemType, tx }) {
  const [a, b, c] = args;
  switch (name) {
    // std/crypto
    case 'sha2_256': return sha256(a);
    case 'sha3_256': return sha3_256(a);
    case 'blake2b_256': return blake2b(a, 32);
    case 'blake2b_224': return blake2b(a, 28);
    case 'verifyEd25519':
      if (a.length !== 32) throw new Refused('verifyEd25519: the key must be 32 bytes');
      if (c.length !== 64) throw new Refused('verifyEd25519: the signature must be 64 bytes');
      return verifyEd25519(a, b, c);
    // std/bytes
    case 'length': return BigInt(a.length);
    case 'concat': {
      const out = new Uint8Array(a.length + b.length);
      out.set(a);
      out.set(b, a.length);
      return out;
    }
    case 'slice': {
      const start = index('slice', 'start', b), n = index('slice', 'length', c);
      const from = Math.min(start, a.length), to = Math.min(start + n, a.length);
      return a.slice(from, Math.max(from, to));
    }
    case 'take': return a.slice(0, Math.min(index('take', 'n', b), a.length));
    case 'drop': return a.slice(Math.min(index('drop', 'n', b), a.length));
    case 'byteAt': {
      const i = index('at', 'index', b);
      if (i >= a.length) throw new Refused('at: index past the end');
      return BigInt(a[i]);
    }
    case 'toInt': return toUnsigned(a);
    case 'toHex': return new TextEncoder().encode(hex(a));
    // std/math
    case 'div':
    case 'mod': {
      if (b === 0n) throw new Refused(`${name}: division by zero`);
      return floorDivMod(a, b)[name === 'div' ? 0 : 1];
    }
    case 'pow':
      if (b < 0n || b > 1024n) throw new Refused('pow: the exponent must be in [0, 1024]');
      return a ** b;
    case 'expMod': {
      if (b < 0n) throw new Refused('expMod: the exponent must not be negative');
      if (c <= 0n) throw new Refused('expMod: the modulus must be positive');
      let r = 1n % c, base = ((a % c) + c) % c, e = b;
      while (e > 0n) {
        if (e & 1n) r = (r * base) % c;
        base = (base * base) % c;
        e >>= 1n;
      }
      return r;
    }
    case 'sign': return a > 0n ? 1n : a < 0n ? -1n : 0n;
    // std/bits
    case 'andBytes':
    case 'orBytes':
    case 'xorBytes': return bitwise(name, a, b, c);
    case 'complement': return a.map((x) => ~x & 0xff);
    case 'readBit': {
      const i = index('readBit', 'index', b);
      if (i >= 8 * a.length) throw new Refused('readBit: index past the end');
      return ((a[a.length - 1 - (i >> 3)] >> (i & 7)) & 1) === 1;
    }
    case 'shift':
    case 'rotate':
      if (b <= -TWO_31 || b >= TWO_31) throw new Refused(`${name}: n must be in (-2^31, 2^31)`);
      return shiftOrRotate(a, b, name === 'rotate');
    case 'countSetBits': return BigInt(popcount(a));
    case 'findFirstSetBit': {
      for (let i = a.length - 1; i >= 0; i--) {
        if (a[i] !== 0) return BigInt(8 * (a.length - 1 - i) + Math.log2(a[i] & -a[i]));
      }
      return -1n;
    }
    // std/list
    case 'last': return a.length === 0 ? NONE : some(a[a.length - 1]);
    case 'nth':
      if (b < 0n) throw new Refused('nth: a negative index');
      return b >= BigInt(a.length) ? NONE : some(a[Number(b)]);
    case 'indexOf': {
      for (let i = 0; i < a.length; i++) if (same(a[i], b, elemType)) return some(BigInt(i));
      return NONE;
    }
    // std/time
    case 'elapsedSince': return tx.validFrom - a > 0n ? tx.validFrom - a : 0n;
    case 'elapsedSinceAtMost': return tx.validTo - a > 0n ? tx.validTo - a : 0n;
    case 'windowStart': return tx.validFrom;
    case 'windowEnd': return tx.validTo;
    default: throw new Error(`unknown std row ${name}`);
  }
}

/** Every row this interpreter implements, by module (checked against the IR's CallStd nodes at load). */
export const ROWS = Object.freeze({
  'std/crypto': ['sha2_256', 'sha3_256', 'blake2b_256', 'blake2b_224', 'verifyEd25519'],
  'std/bytes': ['length', 'concat', 'slice', 'take', 'drop', 'byteAt', 'toInt', 'toHex'],
  'std/math': ['div', 'mod', 'pow', 'expMod', 'sign'],
  'std/bits': ['andBytes', 'orBytes', 'xorBytes', 'complement', 'readBit', 'shift', 'rotate', 'countSetBits', 'findFirstSetBit'],
  'std/list': ['last', 'nth', 'indexOf'],
  'std/time': ['elapsedSince', 'elapsedSinceAtMost', 'windowStart', 'windowEnd'],
});
