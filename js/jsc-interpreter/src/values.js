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

/**
 * A call is refused (§2): every failure of the semantics. Its class says which rule refused it (§2.5, review S4):
 * `require` with the require's position and message (`require:<line>:<col>:<message>`), `input` with the parameter's name (§4), or `domain` with the operation whose
 * domain the call left (`/`, `slice`, `Map.set`, …). The class is what implementations compare; the message is prose.
 */
export class Refused extends Error {
  constructor(message, kind = 'domain', detail = '') {
    super(message);
    this.name = 'Refused';
    this.kind = kind;
    this.detail = detail;
  }

  /** `kind:detail`, as the differential tests compare it. */
  get refusal() {
    return `${this.kind}:${this.detail}`;
  }
}

/**
 * The implementation could not finish (§2.4): its stack ran out. This is no answer at all, never a refusal: the chain
 * may well accept the call within its budget. The differential tests treat it as a failure of the leg.
 */
export class Limit extends Error {
  constructor(message) {
    super(message);
    this.name = 'Limit';
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

// ------------------------------------------------------------------ values, credentials, addresses (M2, §1.3)

const MAX_Q = (1n << 127n) - 1n;
const MIN_Q = -(1n << 127n);

/** Whether a quantity fits the ledger's signed 128-bit range (CIP-153). */
export function inRange(q) {
  return q <= MAX_Q && q >= MIN_Q;
}

/**
 * A multi-asset value (§1.3): entries [policy hex, token name hex, quantity], sorted by policy, then token name, in the
 * ledger's order (unsigned bytewise, a proper prefix first: what comparing lower-case hex strings gives), with no zero
 * quantity. Built only through ValueV.of and plus, which keep it so.
 */
export class ValueV {
  constructor(entries) {
    const sums = new Map();
    for (const [p, n, q] of entries) {
      const k = `${p}:${n}`;
      sums.set(k, (sums.get(k) ?? 0n) + q);
    }
    const out = [];
    for (const [k, q] of sums) if (q !== 0n) out.push([k.slice(0, k.indexOf(':')), k.slice(k.indexOf(':') + 1), q]);
    out.sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : a[1] < b[1] ? -1 : a[1] > b[1] ? 1 : 0));
    this.entries = Object.freeze(out.map((e) => Object.freeze(e)));
    Object.freeze(this);
  }

  static of(policy, name, q) {
    return new ValueV([[hex(policy), hex(name), q]]);
  }

  quantity(policy, name) {
    const p = hex(policy), n = hex(name);
    for (const [ep, en, q] of this.entries) if (ep === p && en === n) return q;
    return 0n;
  }

  /** this + k·w, entry by entry; the caller checks the range. */
  plus(w, k) {
    return new ValueV([...this.entries, ...w.entries.map(([p, n, q]) => [p, n, q * k])]);
  }

  quantities() {
    return this.entries.map((e) => e[2]);
  }

  equals(w) {
    return this.entries.length === w.entries.length
      && this.entries.every((e, i) => e[0] === w.entries[i][0] && e[1] === w.entries[i][1] && e[2] === w.entries[i][2]);
  }
}
ValueV.NONE = new ValueV([]);

/** A credential: a key's (KeyCred) or a script's (ScriptCred) hash. */
export class CredV {
  constructor(script, hash) {
    this.script = script;
    this.hash = hash;
    Object.freeze(this);
  }
}

/** A staking credential: a hash (StakeHash) or a pointer (StakePtr). */
export class StakeHash {
  constructor(credential) {
    this.credential = credential;
    Object.freeze(this);
  }
}

export class StakePtr {
  constructor(slot, txIndex, certIndex) {
    this.slot = slot;
    this.txIndex = txIndex;
    this.certIndex = certIndex;
    Object.freeze(this);
  }
}

/** An address: a payment credential and NONE or Some(StakeHash | StakePtr). */
export class AddrV {
  constructor(payment, stake) {
    this.payment = payment;
    this.stake = stake;
    Object.freeze(this);
  }
}

/**
 * Another contract's instance (M2c, Instance<C>, ADR-0011 D2): its policy (script hash) and its 32-byte id. It appears in
 * the params; a deposit names it as its target.
 */
export class InstV {
  constructor(policy, id) {
    this.policy = policy;
    this.id = id;
    Object.freeze(this);
  }
}

/** A reserved token kind under a contract's policy (ADR-0007 D3): 32 bytes, or 29 bytes starting 0x4F or 0x49. */
export function reserved(n) {
  return n.length === 32 || (n.length === 29 && (n[0] === 0x4f || n[0] === 0x49));
}
