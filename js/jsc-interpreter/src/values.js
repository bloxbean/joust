// The values of .jsc 2 (docs/reference/jsc-2-semantics.md §1), in plain JavaScript:
//   Int        bigint
//   Bool       boolean
//   Bytes      Uint8Array (Key and Hash too), compared by content, never by reference
//   List       a frozen Array
//   Option     NONE, or a Some
//   record     a Rec { type, fields } (the state's type is "State"; a variant's type is the variant's name)
//   Map        a MapV: keys strictly ascending in canonical order (§1.1), values beside them
//   BigMap     a BigMapV { before, now, written } (§1.2): before and now are Maps from key hex to value
//   range      a Range { from, to }: never a value of the language, only a receiver (§3.6)

/** A call is refused: every failure of §2 (a require, a checked input, a domain, a division by zero, depth). */
export class Refused extends Error {
  constructor(message) {
    super(message);
    this.name = 'Refused';
  }
}

export const NONE = Object.freeze({ tag: 'None' });

export class Some {
  constructor(value) {
    if (value === undefined || value === NONE || value instanceof Some) throw new TypeError('Some needs a value that is not an Option');
    this.tag = 'Some';
    this.value = value;
    Object.freeze(this);
  }
}

export function some(v) {
  return new Some(v);
}

export function isNone(o) {
  return o === NONE;
}

export class Rec {
  constructor(type, fields) {
    this.type = type;
    this.fields = Object.freeze([...fields]);
    Object.freeze(this);
  }
}

export class Range {
  constructor(from, to) {
    this.from = from;
    this.to = to;
    Object.freeze(this);
  }

  *[Symbol.iterator]() {
    for (let i = this.from; i < this.to; i++) yield i;
  }
}

// ------------------------------------------------------------------ bytes

const HEX = /^(?:[0-9a-f]{2})*$/;

export function hex(b) {
  let s = '';
  for (const x of b) s += x.toString(16).padStart(2, '0');
  return s;
}

export function fromHex(s) {
  if (typeof s !== 'string' || !HEX.test(s)) throw new TypeError(`not lower-case hex: ${s}`);
  const out = new Uint8Array(s.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(s.slice(2 * i, 2 * i + 2), 16);
  return out;
}

export function bytesEqual(a, b) {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

/** Lexicographic, unsigned, a proper prefix first (§3.3): the order of < on Bytes. */
export function bytesCompare(a, b) {
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) if (a[i] !== b[i]) return a[i] < b[i] ? -1 : 1;
  return a.length === b.length ? 0 : a.length < b.length ? -1 : 1;
}

/** Canonical key order (§1.1): a shorter key first, then unsigned bytewise. */
export function keyCompare(a, b) {
  if (a.length !== b.length) return a.length < b.length ? -1 : 1;
  return bytesCompare(a, b);
}

/** A big-endian unsigned integer's bytes, as few as possible (0 is no bytes). */
export function unsignedBytes(n) {
  if (n < 0n) throw new RangeError('negative');
  if (n === 0n) return new Uint8Array(0);
  let h = n.toString(16);
  if (h.length % 2) h = '0' + h;
  return fromHex(h);
}

export function toUnsigned(b) {
  return b.length === 0 ? 0n : BigInt('0x' + hex(b));
}

// ------------------------------------------------------------------ maps

/**
 * An inline map: entries as two parallel frozen arrays. Built by MapV.EMPTY.set(...) a map is canonical; MapV.raw keeps
 * the order it is given (an input, before the checked-input rule refuses it).
 */
export class MapV {
  constructor(keys, values) {
    this.keys = Object.freeze([...keys]);
    this.values = Object.freeze([...values]);
    Object.freeze(this);
  }

  static raw(entries) {
    return new MapV(entries.map((e) => e[0]), entries.map((e) => e[1]));
  }

  indexOf(k) {
    for (let i = 0; i < this.keys.length; i++) if (bytesEqual(this.keys[i], k)) return i;
    return -1;
  }

  set(k, v) {
    const i = this.indexOf(k);
    const ks = [...this.keys], vs = [...this.values];
    if (i >= 0) {
      vs[i] = v;
    } else {
      let at = 0;
      while (at < ks.length && keyCompare(ks[at], k) < 0) at++;
      ks.splice(at, 0, k);
      vs.splice(at, 0, v);
    }
    return new MapV(ks, vs);
  }

  delete(k) {
    const i = this.indexOf(k);
    if (i < 0) return this;
    const ks = [...this.keys], vs = [...this.values];
    ks.splice(i, 1);
    vs.splice(i, 1);
    return new MapV(ks, vs);
  }
}
MapV.EMPTY = new MapV([], []);

/** A BigMap (§1.2). before and now map a key's hex to its value. */
export class BigMapV {
  constructor(before, now, written) {
    this.before = before;
    this.now = now;
    this.written = written;
    Object.freeze(this);
  }

  /** The state's field as a call starts: before = now = the contents, nothing written. */
  static of(contents) {
    return new BigMapV(contents, contents, false);
  }
}
BigMapV.EMPTY = BigMapV.of(new Map());
