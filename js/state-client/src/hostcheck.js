// A dev-host client's check of a host result before signing (dev-host protocol 1, D13; ADR-0024 D7 step 3, I2): the
// conformance kit's ReferenceChecker (julc-state-host/src/conformance: ReferenceChecker, CallRules, KeyedRules, Ledger,
// Cbor) in JavaScript, so a browser client decides every verifier vector as the reference does.
//
// Input: one verifier-vector-shaped object. Only `result` comes from the host; everything else is the client's own:
//   { method: 'prepare' | 'publish', request: { now, ttlSlots, call | contract, prerequisite, address },
//     networkId, maxFee, anchors: { description, language, policy, address, outputVerifier?, mapVerifier? },
//     resolved: { ref: snapshot UTxO }   (every spending, reference and collateral input, from the trusted chain)
//     node: { scriptsAt: { address: [hash] }, registrations: { scriptHash: true | false | 'unknown' } }   (publish)
//     clock: { zeroSlot, zeroTimeMs, slotLengthMs }   (keyed calls)
//     protocolParams (the chain's, snake_case), result: { prepared } }
// Output: { problems: [code], sites: [`<code>/<check>`], report }; empty problems means the body may be reviewed and
// signed. The report is what the client shows for review, every item read from the body and the trusted inputs, none
// from the host's claims: { plan (the body's own summary), walletNet ({ unit: quantity } the payer's wallet loses, fee
// and deposits included), signers (the key hashes that must sign), created (a creation's instance, object or key),
// before / after (the state, or an owned call's object value: the target output's datum and the body's continuation,
// decoded with the description), removed (a destroy) }.
//
// One deliberate difference: map claims (ADR-0014) are not checked here (the reference verifies their MPF proofs), so a
// call on a contract state or owned value with a MerkleMap field is refused as notVerifiable (fail closed).

import { blake2b } from '@noble/hashes/blake2.js';
import { hexToBytes, bytesToHex, compareBytes } from './hex.js';
import { addressHex, bech32Of } from './ledger.js';
import { codec as makeCodec } from './data.js';
import { decodeData, dataEqual, canonicalHex } from './pdata.js';

class Malformed extends Error {}
const req = (ok) => { if (!ok) throw new Malformed('malformed CBOR or ledger field'); };
const MAP_TYPE = 'org.julclang.state.api.MerkleMap';
const PURPOSES = ['spend', 'mint', 'cert', 'reward', 'voting', 'proposing'];
const MAX_TOP = 10_000_000n;
const INT_MAX = 2147483647n;

// ------------------------------------------------------------------------------------------------- CBOR (Cbor.java)

const MAX_DEPTH = 256 + 16;
function item(src, start, depth) {
  req(depth < MAX_DEPTH && start < src.length);
  const header = src[start];
  const major = header >> 5;
  const ai = header & 31;
  let pos = start + 1;
  const indefinite = ai === 31;
  req(!indefinite || (major >= 2 && major <= 5));
  let arg;
  if (ai < 24) arg = BigInt(ai);
  else if (indefinite) arg = -1n;
  else {
    req(ai <= 27);
    const size = 1 << (ai - 24);
    req(size <= src.length - pos);
    arg = 0n;
    for (let i = 0; i < size; i++) arg = (arg << 8n) | BigInt(src[pos + i]);
    pos += size;
  }
  const payload = pos;
  const items = [];
  if ((major === 2 || major === 3) && !indefinite) {
    req(arg <= BigInt(src.length - pos));
    pos += Number(arg);
  } else if (indefinite || (major >= 4 && major <= 6)) {
    req(indefinite || arg <= INT_MAX);
    const count = indefinite ? -1 : major === 6 ? 1 : Number(arg) * (major === 5 ? 2 : 1);
    req(count <= INT_MAX);
    while (indefinite ? pos < src.length && src[pos] !== 0xff : items.length < count) {
      const child = item(src, pos, depth + 1);
      if (major === 2 || major === 3) req(child.major === major && child.arg >= 0n);
      items.push(child);
      pos = child.end;
    }
    if (indefinite) {
      req(pos < src.length && src[pos] === 0xff && (major !== 5 || items.length % 2 === 0));
      pos++;
    }
  }
  return { src, start, end: pos, major, arg, payload, items };
}
function readCbor(bytes) {
  req(bytes.length <= 1_048_576);
  const n = item(bytes, 0, 0);
  req(n.end === bytes.length);
  return n;
}
const arrayOf = (n) => { req(n && n.major === 4); return n.items; };
const setOf = (n) => (n.major === 6 ? arrayOf(tagged(n, 258)) : arrayOf(n));
const at = (n, i) => { const a = arrayOf(n); req(i < a.length); return a[i]; };
const tagged = (n, tag) => { req(n && n.major === 6 && n.arg === BigInt(tag)); return n.items[0]; };
const integer = (n) => { req(n && n.major <= 1); return n.major === 0 ? n.arg : -n.arg - 1n; };
const uint = (n) => { req(n && n.major === 0 && n.arg <= INT_MAX); return Number(n.arg); };
const bytesOf = (n) => { req(n && n.major === 2 && n.arg >= 0n); return bytesToHex(n.src.subarray(n.payload, n.end)); };
const rawOf = (n) => bytesToHex(n.src.subarray(n.start, n.end));
function fieldsOf(n) {
  req(n && n.major === 5);
  const m = new Map();
  for (let i = 0; i < n.items.length; i += 2) {
    const k = uint(n.items[i]);
    req(!m.has(k));
    m.set(k, n.items[i + 1]);
  }
  return m;
}
const digest = (bits, bytes) => bytesToHex(blake2b(bytes, { dkLen: bits / 8 }));

// A minimal CBOR writer (definite lengths, shortest heads) for the re-encodings the keyed rules compare against.
function head(major, n) {
  n = BigInt(n);
  const m = major << 5;
  if (n < 24n) return [m | Number(n)];
  if (n < 0x100n) return [m | 24, Number(n)];
  if (n < 0x10000n) return [m | 25, Number(n >> 8n), Number(n & 0xffn)];
  if (n < 0x100000000n) return [m | 26, ...[24n, 16n, 8n, 0n].map((s) => Number((n >> s) & 0xffn))];
  return [m | 27, ...[56n, 48n, 40n, 32n, 24n, 16n, 8n, 0n].map((s) => Number((n >> s) & 0xffn))];
}
const cInt = (n) => (n >= 0n ? head(0, n) : head(1, -1n - n));
const cBytes = (hex) => [...head(2, hex.length / 2), ...hexToBytes(hex)];
const cArray = (items) => [...head(4, items.length), ...items.flat()];
/** A canonical map (RFC 7049 §3.9: keys by encoded length, then bytewise). */
const cMap = (entries) => {
  const sorted = entries.map(([k, v]) => [Uint8Array.from(k), v]).sort((a, b) => compareBytes(a[0], b[0]));
  return [...head(5, sorted.length), ...sorted.flatMap(([k, v]) => [...k, ...v])];
};

// ------------------------------------------------------------------------------------------------- JSON helpers

const has = (o, k) => o !== null && typeof o === 'object' && !Array.isArray(o) && Object.hasOwn(o, k);
const rq = (o, k) => { req(has(o, k)); return o[k]; };
const rqi = (a, i) => { req(Array.isArray(a) && i < a.length); return a[i]; };
const path = (o, k) => (has(o, k) ? o[k] : undefined);
const atp = (o, p) => p.split('/').filter(Boolean).reduce((x, k) => (Array.isArray(x) ? (/^\d+$/.test(k) ? x[Number(k)] : undefined) : path(x, k)), o);
const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const nonNull = (o, k) => has(o, k) && o[k] !== null;
/** Jackson's asBoolean. */
const asBool = (v) => v === true || v === 'true' || (typeof v === 'number' && v !== 0) || (typeof v === 'bigint' && v !== 0n);
/** Jackson's asText. */
const asText = (v, dflt = '') => (v === undefined || v === null ? dflt : typeof v === 'object' ? '' : String(v));
const textValue = (v) => (typeof v === 'string' ? v : null);
/** Jackson's isEmpty (a missing node, null or a scalar is empty). */
const isEmpty = (v) => (Array.isArray(v) ? v.length === 0 : isObj(v) ? Object.keys(v).length === 0 : true);
const isIntegral = (v) => typeof v === 'bigint' || (typeof v === 'number' && Number.isInteger(v));
const bi = (v) => (isIntegral(v) ? BigInt(v) : 0n);
/** ReferenceChecker.n: a field's decimal value (a number or a decimal string). */
function n(o, k) {
  const v = rq(o, k);
  if (typeof v === 'bigint') return v;
  if (typeof v === 'number') { req(Number.isInteger(v)); return BigInt(v); }
  req(typeof v === 'string' && /^[+-]?[0-9]+$/.test(v));
  return BigInt(v.replace(/^\+/, ''));
}
function strings(a) {
  if (a === undefined) return [];
  req(Array.isArray(a));
  return a.map((s) => { req(typeof s === 'string'); return s; });
}
/** JSON equality, integral numbers by value (ReferenceChecker.equal). */
function jeq(a, b) {
  if (isIntegral(a) && isIntegral(b)) return BigInt(a) === BigInt(b);
  if (a === undefined || b === undefined) return a === b;
  if (a === null || b === null) return a === b;
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((x, i) => jeq(x, b[i]));
  }
  if (isObj(a) || isObj(b)) {
    if (!isObj(a) || !isObj(b)) return false;
    const ka = Object.keys(a);
    return ka.length === Object.keys(b).length && ka.every((k) => has(b, k) && jeq(a[k], b[k]));
  }
  return typeof a === typeof b && a === b;
}
const addValue = (into, from, sign) => { for (const [k, q] of from) into.set(k, (into.get(k) ?? 0n) + (sign === 1 ? q : -q)); };
const isZero = (m) => [...m.values()].every((q) => q === 0n);
const valueOfPlan = (obj) => new Map(Object.entries(obj).map(([k, q]) => [k, BigInt(q)]));
function utxoValue(u) {
  const out = new Map();
  for (const a of rq(u, 'amount')) {
    const unit = asText(rq(a, 'unit')).replaceAll('.', '');
    out.set(unit, (out.get(unit) ?? 0n) + n(a, 'quantity'));
  }
  return out;
}
const mapEquals = (a, b) => a.size === b.size && [...a].every(([k, q]) => b.has(k) && b.get(k) === q);
/** An exact decimal (with an optional exponent) as a fraction [numerator, denominator]. */
function fraction(text) {
  const m = /^([+-]?)(\d*)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/.exec(text);
  req(m && (m[2] || m[3]));
  let num = BigInt((m[1] === '-' ? '-' : '') + ((m[2] || '') + (m[3] || '') || '0'));
  let den = 10n ** BigInt((m[3] || '').length);
  const e = Number(m[4] || 0);
  if (e >= 0) num *= 10n ** BigInt(e);
  else den *= 10n ** BigInt(-e);
  return [num, den];
}
const ceilDiv = (a, b) => (a >= 0n ? (a + b - 1n) / b : -((-a) / b));

// ------------------------------------------------------------------------------------------------- addresses

const addressBytes = (addr) => {
  try {
    return hexToBytes(addressHex(addr));
  } catch {
    throw new Malformed('not an address');
  }
};
const networkOf = (addr) => addressBytes(addr)[0] & 15;
function paymentKey(addr) {
  const b = addressBytes(addr);
  return [0, 2, 4, 6].includes(b[0] >> 4) ? bytesToHex(b.subarray(1, 29)) : null;
}
/**
 * An output's address as bech32, only when it encodes the received bytes exactly: the ledger library reads some
 * forms leniently (a pointer address's trailing bytes) and would re-encode another address than the body names, which
 * the reference (encoding the received bytes) never does. Fail closed.
 */
const bech32 = (bytesNode) => {
  const hex = bytesOf(bytesNode);
  let out;
  try {
    out = bech32Of(hex);
  } catch {
    throw new Malformed('not an address');
  }
  let back;
  try {
    back = addressHex(out);
  } catch {
    back = null;
  }
  if (back === null || back.toLowerCase() !== hex) throw new Malformed('an address that does not round-trip to its received bytes');
  return out;
};

// ------------------------------------------------------------------------------------------------- the plan (Ledger.java)

function assetsInto(out, node) {
  if (!node) return;
  req(node.major === 5);
  const policies = new Set();
  for (let i = 0; i < node.items.length; i += 2) {
    const policy = bytesOf(node.items[i]);
    req(policy.length === 56 && !policies.has(policy));
    policies.add(policy);
    const names = node.items[i + 1];
    req(names.major === 5 && names.items.length > 0);
    for (let j = 0; j < names.items.length; j += 2) {
      const name = bytesOf(names.items[j]);
      req(name.length <= 64 && !has(out, policy + name));
      out[policy + name] = integer(names.items[j + 1]).toString();
    }
  }
}
function planOutput(o) {
  const map = o.major === 5;
  const f = map ? fieldsOf(o) : new Map();
  req(map ? [...f.keys()].every((k) => k <= 3) : arrayOf(o).length === 2 || o.items.length === 3);
  const addr = map ? f.get(0) : at(o, 0);
  const val = map ? f.get(1) : at(o, 1);
  req(addr && val);
  const out = { address: bech32(addr), value: {} };
  if (val.major === 4) {
    req(arrayOf(val).length === 2);
    out.value.lovelace = integer(at(val, 0)).toString();
    assetsInto(out.value, at(val, 1));
  } else out.value.lovelace = integer(val).toString();
  out.datum = null;
  out.datumHash = null;
  out.hasScript = f.has(3);
  if (f.has(2)) {
    const d = f.get(2);
    req(arrayOf(d).length === 2 && uint(at(d, 0)) <= 1);
    if (uint(at(d, 0)) === 1) out.datum = bytesOf(tagged(at(d, 1), 24));
    else out.datumHash = bytesOf(at(d, 1));
  } else if (o.major === 4 && o.items.length === 3) out.datumHash = bytesOf(at(o, 2));
  return out;
}

function ledgerOf(hex) {
  req(typeof hex === 'string' && hex.length <= 2 * 1_048_576 && /^([0-9a-fA-F]{2})*$/.test(hex));
  const bytes = hexToBytes(hex);
  const tx = readCbor(bytes);
  req(arrayOf(tx).length === 4);
  const body = at(tx, 0);
  const b = fieldsOf(body);
  const allowed = new Set([0, 1, 2, 3, 4, 5, 7, 8, 9, 11, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22]);
  req([...b.keys()].every((k) => allowed.has(k)));
  const set = (k) => (b.has(k) ? setOf(b.get(k)) : []);
  const outputs = arrayOf(b.get(1));
  const plan = {};
  const refs = (name, k) => {
    const seen = new Set();
    plan[name] = set(k).map((i) => {
      req(arrayOf(i).length === 2 && bytesOf(at(i, 0)).length === 64);
      const ref = bytesOf(at(i, 0)) + '#' + uint(at(i, 1));
      req(!seen.has(ref));
      seen.add(ref);
      return ref;
    });
  };
  const number = (k) => (b.has(k) ? integer(b.get(k)) : null);
  const decimal = (name, k) => { plan[name] = b.has(k) ? integer(b.get(k)).toString() : null; };
  refs('inputs', 0);
  refs('referenceInputs', 18);
  refs('collateral', 13);
  plan.outputs = outputs.map(planOutput);
  plan.collateralReturn = b.has(16) ? planOutput(b.get(16)) : null;
  decimal('totalCollateral', 17);
  plan.mint = {};
  assetsInto(plan.mint, b.get(9));
  plan.requiredSigners = set(14).map(bytesOf);
  plan.validFrom = number(8);
  plan.validTo = number(3);
  plan.networkId = number(15);
  decimal('fee', 2);
  plan.scriptDataHash = b.has(11) ? bytesOf(b.get(11)) : null;
  plan.auxiliaryDataHash = b.has(7) ? bytesOf(b.get(7)) : null;
  plan.certificates = set(4).length;
  if (b.has(5)) {
    const accounts = new Set();
    const ws = b.get(5);
    for (let i = 0; i < ws.items.length; i += 2) {
      req(ws.major === 5);
      const a = bytesOf(ws.items[i]);
      req(!accounts.has(a));
      accounts.add(a);
    }
  }
  plan.withdrawals = b.has(5) ? b.get(5).items.length / 2 : 0;
  plan.votes = b.has(19);
  plan.proposals = set(20).length;
  decimal('donation', 22);
  decimal('currentTreasuryValue', 21);
  plan.auxiliaryData = rawOf(at(tx, 3)) !== 'f6';
  plan.isValid = rawOf(at(tx, 2)) === 'f5';
  const witnesses = fieldsOf(at(tx, 1));
  req([...witnesses.keys()].every((k) => k === 4 || k === 5 || k === 7)); // an unsigned Plutus transaction
  const sorted = new Map();
  const redeemer = (tag, index, data, units) => {
    const t = uint(tag);
    req(t < PURPOSES.length);
    const r = { tag: PURPOSES[t], index: uint(index), data: rawOf(data), mem: integer(at(units, 0)).toString(),
      steps: integer(at(units, 1)).toString() };
    const key = `${t}:${r.index}`;
    req(!sorted.has(key));
    sorted.set(key, [t, r.index, r]);
  };
  const rs = witnesses.get(5);
  if (rs) {
    if (rs.major === 5) {
      for (let i = 0; i < rs.items.length; i += 2) {
        const k = rs.items[i];
        const v = rs.items[i + 1];
        req(arrayOf(k).length === 2 && arrayOf(v).length === 2);
        redeemer(at(k, 0), at(k, 1), at(v, 0), at(v, 1));
      }
    } else {
      for (const r of arrayOf(rs)) {
        req(arrayOf(r).length === 4);
        redeemer(at(r, 0), at(r, 1), at(r, 2), at(r, 3));
      }
    }
  }
  plan.redeemers = [...sorted.values()].sort((x, y) => x[0] - y[0] || x[1] - y[1]).map((x) => x[2]);
  return { tx, body, b, plan, outputs, length: bytes.length, set, witnesses };
}

/** The script integrity hash over the received witness bytes and the PlutusV3 language view (Ledger.scriptIntegrity). */
function scriptIntegrity(ledger, pp) {
  const w = ledger.witnesses;
  if (!w.has(5) && !w.has(4)) return null;
  const parts = [];
  for (const key of [5, 4]) {
    const it = w.get(key);
    if (it) parts.push(...it.src.subarray(it.start, it.end));
    else if (key === 5) parts.push(0xa0);
  }
  let costs = pp.cost_models_raw?.PlutusV3;
  if (!Array.isArray(costs) || !costs.length) {
    const named = pp.cost_models?.PlutusV3;
    req(isObj(named));
    let keys = Object.keys(named);
    if (has(named, '0') && has(named, '1')) keys.sort((a, c) => Number(a) - Number(c));
    else if (keys.every((k) => /^\d+$/.test(k))) keys.sort((a, c) => Number(a) - Number(c));
    costs = keys.map((k) => named[k]);
  }
  const views = cMap([[head(0, 2), cArray(costs.map((c) => cInt(BigInt(c))))]]);
  return digest(256, Uint8Array.from([...parts, ...views]));
}

// ------------------------------------------------------------------------------------------------- the checker

/**
 * Checks one host result (verifier-vector shape, see the header) as protocol 1's reference does. Never throws: a body
 * or input it cannot read is `malformed`.
 */
export function verifyHostResult(v) {
  const sites = [];
  const problems = new Set();
  try {
    const c = new Checker(v, sites, problems);
    c.verify();
    return { problems: [...problems], sites, report: c.report() };
  } catch (e) {
    return { problems: ['malformed'], sites: [`malformed/${e instanceof Malformed ? 'read' : 'internal'}: ${String(e.message ?? e).slice(0, 200)}`] };
  }
}

class Checker {
  constructor(v, sites, problems) {
    Object.assign(this, { v, sites, problems });
    this.prepared = rq(rq(v, 'result'), 'prepared');
    this.req = rq(v, 'request');
    this.publish = rq(v, 'method') === 'publish';
    this.method = rq(v, 'method');
    this.call = this.publish ? this.req : rq(this.req, 'call');
    this.payer = rq(this.call, 'address');
    req(typeof this.payer === 'string');
    this.network = Number(n(v, 'networkId'));
    this.caller = paymentKey(this.payer);
    req(this.caller !== null);
    this.anchors = rq(v, 'anchors');
    this.description = rq(this.anchors, 'description');
    this.x = rq(this.description, 'x-joust');
    this.policy = rq(this.anchors, 'policy');
    this.address = rq(this.anchors, 'address');
    this.pp = rq(v, 'protocolParams');
    this.ledger = ledgerOf(rq(this.prepared, 'cbor'));
    this.plan = this.ledger.plan;
    this.inputs = strings(this.plan.inputs);
    this.kind = this.publish ? 'publish' : rq(this.call, 'kind');
    this.owned = typeof this.kind === 'string' && this.kind.startsWith('owned');
    this.creation = ['init', 'ownedInit', 'keyedInit'].includes(this.kind);
    this.destroy = ['destroy', 'ownedDestroy'].includes(this.kind);
    this.keyed = false;
    this.mapFields = [];
    this.rewards = [];
    this.consumed = new Map();
    this.walletIn = new Map();
    this.targetValue = new Map();
    this.minted = valueOfPlan(this.plan.mint);
    this.walletRefs = new Set();
    this.meta = null;
    this.target = null;
    this.spent = null;
    this.token = null;
    this.deposit = 0n;
    this.codec = makeCodec(this.description);
  }

  report() {
    const net = new Map(this.walletIn);
    for (const o of this.plan.outputs) if (this.wallet(o.address)) addValue(net, valueOfPlan(o.value), -1);
    return { plan: this.plan, walletNet: Object.fromEntries([...net].filter(([, q]) => q !== 0n).map(([k, q]) => [k, q.toString()])),
      signers: [...(this.witnesses ?? [])], created: this.created ?? null,
      // The call's state (an owned call: the object's value) before, decoded from the trusted chain's target output, and
      // after, from the body's checked continuation; a destroy removes it, a creation has no before.
      before: this.rules?.before ?? null, after: this.destroy ? null : this.rules?.after ?? null,
      removed: this.destroy || this.rules?.removed === true };
  }

  test(site, ok) {
    if (!ok) {
      this.problems.add(site.slice(0, site.indexOf('/')));
      this.sites.push(site);
    }
  }
  mapContract() { return has(rq(this.description, 'definitions'), MAP_TYPE); }
  reward(verifier) { return 'f' + this.network.toString(16) + asText(atp(this.anchors, `/${verifier}/hash`)); }
  definition(name) { const d = this.description.definitions?.[name]; req(d); return d; }

  /** The payer's exact address, or the caller's payment key in an enterprise address on the selected network. */
  wallet(addr) {
    return this.payer === addr || (this.caller === paymentKey(addr) && (addressBytes(addr)[0] >> 4 & 15) === 6
      && networkOf(addr) === this.network);
  }
  clean(o) { return o.datum === null && o.datumHash === null && !o.hasScript; }

  verify() {
    const { plan, prepared } = this;
    this.test('bodyHash/digest', digest(256, this.ledger.body.src.subarray(this.ledger.body.start, this.ledger.body.end)) === rq(prepared, 'txHash'));
    this.test('planMismatch/plan', jeq(plan, rq(prepared, 'plan')));
    this.test('planMismatch/validFrom', jeq(plan.validFrom, rq(prepared, 'validFrom')));
    this.test('planMismatch/validTo', jeq(plan.validTo, rq(prepared, 'validTo')));
    this.test('network/client', this.network >= 0 && this.network <= 1 && networkOf(this.payer) === this.network);
    this.test('network/body', plan.networkId === null || BigInt(this.network) === plan.networkId);
    this.test('scriptIntegrity/hash', scriptIntegrity(this.ledger, this.pp) === plan.scriptDataHash);
    this.test('isValid/flag', plan.isValid);
    this.test('auxiliaryData/present', !plan.auxiliaryData && plan.auxiliaryDataHash === null);
    this.test('governance/votes', !this.ledger.b.has(19));
    this.test('governance/proposals', !this.ledger.b.has(20));
    this.test('governance/treasury', !this.ledger.b.has(21));
    this.test('governance/donation', !this.ledger.b.has(22));
    if (!this.classify()) {
      this.test('notVerifiable/classify', false);
      return;
    }
    if (!this.creation && !this.publish && !this.keyed) this.token = this.policy + asText(rq(this.call, this.owned ? 'object' : 'instance'));
    const resolved = rq(this.v, 'resolved');
    for (const [field, site] of [['inputs', 'unresolvedInput/spending'], ['referenceInputs', 'unresolvedInput/reference'], ['collateral', 'unresolvedInput/collateral']]) {
      for (const ref of strings(plan[field])) {
        const u = path(resolved, ref);
        if (!isObj(u) || ref !== `${asText(u.tx_hash)}#${asText(u.output_index)}`) {
          this.test(site, false);
          if (field !== 'referenceInputs') return; // no independently resolved value to balance
        }
      }
    }
    this.inputsCheck(resolved);
    this.envelope();
    this.certificates();
    this.withdrawals();
    if (this.publish) this.publication();
    else if (this.keyed) (this.rules = new KeyedRules(this)).check();
    else (this.rules = new CallRules(this)).check();
    const balance = new Map(this.consumed);
    addValue(balance, this.minted, 1);
    for (const o of plan.outputs) {
      this.test('network/output', networkOf(o.address) === this.network);
      const amount = valueOfPlan(o.value);
      this.test('balance/nonNegative', [...amount.values()].every((q) => q >= 0n));
      addValue(balance, amount, -1);
    }
    balance.set('lovelace', (balance.get('lovelace') ?? 0n) - (n(plan, 'fee') + this.deposit));
    this.test('balance/conserved', isZero(balance));
  }

  classify() {
    const { x, call, description } = this;
    const knownContract = asText(atp(description, '/preamble/title')) === asText(rq(call, 'contract'))
      && !nonNull(x, 'params')
      && (!this.mapContract() || /^[0-9a-f]{56}$/.test(asText(atp(this.anchors, '/mapVerifier/hash'))));
    if (this.publish) return knownContract;
    if (this.method !== 'prepare' || !['init', 'action', 'destroy', 'ownedInit', 'ownedAction', 'ownedDestroy', 'keyedInit',
      'keyedAction', 'keyedRemove', 'keyedRetire'].includes(this.kind)) return false;
    for (const m of rq(x, this.creation ? 'creations' : 'actions')) {
      if (this.kind === rq(m, 'kind') && jeq(rq(call, 'method'), rq(m, 'name'))) this.meta = m;
    }
    const meta = this.meta;
    if (meta === null) return false;
    if (this.mapContract()) {
      const schema = this.owned ? this.definition(asText(rq(meta, 'owned'))) : this.codec.resolve(rq(x, 'state'));
      const names = [];
      for (const field of atp(schema, '/anyOf/0/fields') ?? []) {
        if (asText(path(field, '$ref')) === '#/definitions/' + MAP_TYPE) names.push(asText(rq(field, 'title')));
      }
      this.mapFields = names;
      // Not checked here (the reference verifies the map claims' proofs): fail closed.
      if (names.length) return false;
    }
    const p = path(meta, 'policies');
    const recipe = path(meta, 'recipe');
    const keyedInit = this.kind === 'init' && !isEmpty(path(x, 'keyed'));
    if (keyedInit || this.kind.startsWith('keyed')) {
      // A legacy description without the D11 tags and schemas cannot anchor a keyed transaction.
      let tagged = !isEmpty(path(x, 'keyed'));
      for (const t of Array.isArray(x.keyed) ? x.keyed : isObj(x.keyed) ? Object.values(x.keyed) : []) tagged &&= nonNull(t, 'typeId') && nonNull(t, 'link');
      this.keyed = tagged || this.kind.startsWith('keyed');
      if (!tagged) return false;
      if (asBool(path(meta, 'needsService')) || (this.kind === 'keyedRemove' && !asBool(path(meta, 'ctx')))) return false;
    }
    return knownContract
      && !asBool(path(p, 'batched'))
      && !asBool(path(p, 'into'))
      && !nonNull(p, 'admits')
      && !nonNull(p, 'migrateTo')
      && !asBool(path(p, 'receives'))
      && isEmpty(path(p, 'authorizedBy'))
      && !asBool(atp(meta, '/returns/deposits'))
      && (!asBool(path(meta, 'needsService')) || isObj(recipe))
      && isEmpty(path(recipe, 'reads'))
      && (!this.creation || !nonNull(p, 'quorum'))
      && (!asBool(atp(meta, '/returns/sends')) || nonNull(path(this.anchors, 'outputVerifier'), 'hash'));
  }

  inputsCheck(resolved) {
    const { anchors, policy, address, call } = this;
    for (const ref of this.inputs) {
      const u = rq(resolved, ref);
      const addr = asText(rq(u, 'address'));
      const amount = utxoValue(u);
      addValue(this.consumed, amount, 1);
      if (this.wallet(addr)) {
        this.walletRefs.add(ref);
        addValue(this.walletIn, amount, 1);
      } else if (this.keyed && address === addr) {
        // KeyedRules assigns target and predecessor by token, datum and ledger pointer.
      } else if (!this.keyed && !this.publish && !this.creation && this.spent === null && addr === address && amount.get(this.token) === 1n) {
        this.spent = ref;
        this.target = u;
        for (const [k, q] of amount) this.targetValue.set(k, q);
      } else this.test('scriptInput/foreign', false);
    }
    if (!this.publish && !this.creation && !this.keyed) this.test('scriptInput/target', this.spent !== null);
    const scripts = new Set();
    const inline = this.ledger.witnesses.get(7);
    if (inline) {
      for (const script of setOf(inline)) scripts.add(digest(224, hexToBytes('03' + bytesOf(script))));
    }
    const ovHash = textValue(atp(anchors, '/outputVerifier/hash'));
    const mvHash = textValue(atp(anchors, '/mapVerifier/hash'));
    this.test('referenceInput/witnessScript', [...scripts].every((h) => h === policy || h === ovHash || (this.mapContract() && h === mvHash)));
    let resolvedAll = true;
    for (const ref of strings(this.plan.referenceInputs)) {
      const u = path(resolved, ref);
      if (!isObj(u)) { resolvedAll = false; continue; }
      const addr = asText(rq(u, 'address'));
      const hash = textValue(path(u, 'reference_script_hash'));
      const verifier = path(anchors, 'outputVerifier');
      const holder = address === addr && policy === hash;
      let shared = addr === textValue(path(verifier, 'address')) && hash === textValue(path(verifier, 'hash')) && hash !== null;
      const map = path(anchors, 'mapVerifier');
      shared ||= this.mapContract() && addr === textValue(path(map, 'address')) && hash !== null && hash === textValue(path(map, 'hash'));
      const instance = address === addr && nonNull(call, 'instance') && (!this.keyed || asBool(atp(this.meta, '/recipe/shared')))
        && utxoValue(u).get(policy + asText(call.instance)) === 1n;
      this.test('referenceInput/allowed', !this.publish && (holder || shared || instance));
      if (holder || shared) scripts.add(hash);
    }
    if (!this.publish && resolvedAll) {
      this.test('referenceInput/scripts', scripts.has(policy) && (!this.usesOutputVerifier() || scripts.has(asText(atp(anchors, '/outputVerifier/hash'))))
        && (this.mapFields.length === 0 || scripts.has(asText(atp(anchors, '/mapVerifier/hash')))));
    }
  }

  usesOutputVerifier() {
    const ws = this.ledger.b.get(5);
    if (!ws) return false;
    for (let i = 0; i < ws.items.length; i += 2) if (bytesOf(ws.items[i]) === this.reward('outputVerifier')) return true;
    return false;
  }

  envelope() {
    const { plan, call, meta } = this;
    const upper = plan.validTo;
    const lower = plan.validFrom;
    const now = n(this.req, 'now');
    const ttl = n(this.req, 'ttlSlots');
    if (this.keyed) {
      const page = path(call, 'pageLimits');
      req(page === undefined || (Array.isArray(page) && page.length === 2 && isIntegral(page[0]) && isIntegral(page[1])));
      const start = page === undefined ? 60n : BigInt(page[0]);
      const validity = page === undefined ? 900n : BigInt(page[1]);
      const from = bi(lower);
      const end = bi(upper);
      this.test('keyedWindow/bounds', isIntegral(lower) && isIntegral(upper) && from >= now && from <= now + start && end > from
        && end <= from + ttl && end <= now + validity);
    } else {
      this.test('upperBound/window', isIntegral(upper) && bi(upper) > now && bi(upper) <= now + ttl && (lower === null || bi(upper) > bi(lower)));
    }
    if (!this.keyed && !this.publish && (asBool(path(meta, 'ctx')) || !isEmpty(atp(meta, '/policies/validBefore'))
      || !isEmpty(atp(meta, '/policies/validAfter')))) {
      this.test('lowerBound/context', isIntegral(lower) && bi(lower) >= now);
    }
    const required = strings(plan.requiredSigners);
    const named = new Set(strings(path(call, 'cosigners')));
    const allowed = new Set(named);
    allowed.add(this.caller);
    const quorum = !this.publish && nonNull(path(meta, 'policies'), 'quorum');
    const callerRequired = !this.publish && (quorum || asBool(path(meta, 'ctx')) || !isEmpty(atp(meta, '/policies/signedBy')));
    this.test('signer/allowed', required.every((k) => allowed.has(k)));
    this.test('signer/named', [...named].every((k) => required.includes(k)));
    this.test('signer/cosigners', quorum || named.size === 0);
    this.test('signer/caller', !callerRequired || required.includes(this.caller));
    this.test('signer/form', required.length === new Set(required).size && required.every((k) => /^[0-9a-f]{56}$/.test(k)));
    const witnesses = new Set(required);
    if (this.walletRefs.size || plan.collateral.length) witnesses.add(this.caller);
    this.witnesses = witnesses;
    const claims = strings(rq(this.prepared, 'signers'));
    this.test('planMismatch/signers', new Set(claims).size === witnesses.size && claims.every((k) => witnesses.has(k))
      && claims.length === witnesses.size);
    let mem = 0n;
    let steps = 0n;
    for (const r of plan.redeemers) {
      mem += n(r, 'mem');
      steps += n(r, 'steps');
      this.test('budget/units', n(r, 'mem') >= 0n && n(r, 'steps') >= 0n);
    }
    const pp = this.pp;
    this.test('budget/total', mem <= n(pp, 'max_tx_ex_mem') && steps <= n(pp, 'max_tx_ex_steps'));
    const [pmN, pmD] = fraction(asText(rq(pp, 'price_mem')));
    const [psN, psD] = fraction(asText(rq(pp, 'price_step')));
    const exFee = ceilDiv(mem * pmN * psD + steps * psN * pmD, pmD * psD);
    // Normative (dev-host-1.md rule 3): floor(1.2 x (min_fee_a x (size + 200) + min_fee_b + ceil(execution fee))) + 500,000.
    const cap = ((n(pp, 'min_fee_a') * BigInt(this.ledger.length + 200) + n(pp, 'min_fee_b') + exFee) * 12n) / 10n + 500_000n;
    const fee = n(plan, 'fee');
    this.test('fee/sign', fee >= 0n);
    this.test('fee/derived', fee <= cap);
    this.test('fee/max', fee <= n(this.v, 'maxFee'));
    this.collateral();
  }

  collateral() {
    const { plan } = this;
    const refs = strings(plan.collateral);
    const ret = plan.collateralReturn;
    const total = plan.totalCollateral;
    if (!refs.length && ret === null && total === null) {
      this.test('collateral/none', plan.redeemers.length === 0);
      return;
    }
    this.test('collateral/present', refs.length > 0 && isObj(ret) && total !== null && !this.publish);
    if (!isObj(ret) || total === null) return;
    const amounts = new Map();
    for (const ref of refs) {
      const u = rq(rq(this.v, 'resolved'), ref);
      this.test('collateral/owner', this.wallet(asText(rq(u, 'address'))));
      addValue(amounts, utxoValue(u), 1);
    }
    // wallet() also fixes the return's network: the exact payer address, or the caller's enterprise address on it.
    this.test('collateral/returnAddress', this.wallet(ret.address));
    this.test('collateral/returnClean', this.clean(ret));
    this.test('collateral/limit', n(plan, 'totalCollateral') >= 0n && n(plan, 'totalCollateral') <= 5_000_000n);
    const returned = valueOfPlan(ret.value);
    this.test('collateral/returnNonNegative', [...returned.values()].every((q) => q >= 0n));
    addValue(amounts, returned, -1);
    amounts.set('lovelace', (amounts.get('lovelace') ?? 0n) - n(plan, 'totalCollateral'));
    this.test('collateral/conserved', isZero(amounts));
  }

  prerequisiteKind() { return asText(rq(this.req, 'prerequisite')).split(':')[0]; }
  prerequisiteHash() { const s = asText(rq(this.req, 'prerequisite')); const i = s.indexOf(':'); req(i >= 0); return s.slice(i + 1); }

  certificates() {
    const certs = this.ledger.set(4);
    const registration = this.publish && ['stakeRegistration', 'verifier'].includes(this.prerequisiteKind());
    this.test('certificate/count', certs.length === (registration ? 1 : 0));
    const pp = this.pp;
    for (const cert of certs) {
      const k = uint(at(cert, 0));
      let money = 0n;
      if ([0, 11, 12, 13].includes(k)) money = k === 0 ? n(pp, 'key_deposit') : integer(cert.items[cert.items.length - 1]);
      else if (k === 1) money = -n(pp, 'key_deposit');
      else if (k === 7 || k === 16) money = integer(at(cert, 2));
      else if (k === 8 || k === 17) money = -integer(at(cert, 2));
      this.deposit += money;
      if (!registration) continue;
      const pure = (k === 0 && arrayOf(cert).length === 2) || (k === 7 && arrayOf(cert).length === 3);
      this.test('certificate/shape', pure && arrayOf(at(cert, 1)).length === 2 && uint(at(at(cert, 1), 0)) === 1
        && bytesOf(at(at(cert, 1), 1)) === this.prerequisiteHash());
      if (k === 7) this.test('deposit/registration', money === n(pp, 'key_deposit'));
    }
  }

  withdrawals() {
    const ws = this.ledger.b.get(5);
    const mapCall = this.mapFields.length > 0;
    if (!ws) {
      this.test('mapWithdrawal/withdrawal', !mapCall);
      return;
    }
    req(ws.major === 5);
    const reward = this.reward('outputVerifier');
    const mapReward = mapCall ? this.reward('mapVerifier') : null;
    this.test('withdrawal/allowed', !this.publish && ws.items.length <= (mapCall ? 4 : 2));
    const accounts = [];
    for (let i = 0; i < ws.items.length; i += 2) {
      const account = bytesOf(ws.items[i]);
      accounts.push(account);
      const coin = integer(ws.items[i + 1]);
      if (!mapCall || account === reward) {
        this.test('withdrawal/allowed', !this.publish && asBool(atp(this.meta, '/returns/sends')));
        this.test('withdrawal/account', account === reward);
        this.test('withdrawal/amount', coin === 0n);
      } else {
        this.test('mapWithdrawal/withdrawal', account === mapReward);
        this.test('mapWithdrawal/amount', coin === 0n);
      }
      this.consumed.set('lovelace', (this.consumed.get('lovelace') ?? 0n) + coin);
    }
    accounts.sort();
    this.rewards = accounts;
    if (mapCall) this.test('mapWithdrawal/withdrawal', accounts.includes(mapReward));
  }

  publication() {
    const kind = this.prerequisiteKind();
    const hash = this.prerequisiteHash();
    const { anchors, x, pp } = this;
    let verifier = path(anchors, 'outputVerifier');
    const map = this.mapContract() && hash === textValue(atp(anchors, '/mapVerifier/hash'));
    if (map) verifier = rq(anchors, 'mapVerifier');
    const shared = hash === textValue(path(verifier, 'hash'));
    let sends = false;
    let withdraw = false;
    for (const group of ['creations', 'actions', 'purposes']) {
      for (const m of Array.isArray(x[group]) ? x[group] : []) {
        sends ||= asBool(atp(m, '/returns/sends')) || asBool(atp(m, '/returns/deposits')) || asBool(atp(m, '/policies/receives'));
        withdraw ||= textValue(path(m, 'purpose')) === 'withdraw';
      }
    }
    const language = textValue(rq(shared ? verifier : anchors, 'language'));
    const holder = kind !== 'stakeRegistration';
    const registration = kind !== 'referenceScript';
    this.test('publicationShape/kind', ['stakeRegistration', 'referenceScript', 'verifier'].includes(kind)
      && (shared ? map || sends : hash === this.policy && kind !== 'verifier' && (!registration || withdraw)));
    const target = shared ? textValue(rq(verifier, 'address')) : this.address;
    const node = rq(this.v, 'node');
    if (holder) {
      const present = path(path(node, 'scriptsAt'), target);
      this.test('publicationPresence/holder', Array.isArray(present) && !strings(present).includes(hash));
    }
    if (registration) {
      const status = path(path(node, 'registrations'), hash);
      this.test('publicationPresence/registration', status === false || (kind !== 'verifier' && status === 'unknown'));
    }
    this.test('mint/publication', this.minted.size === 0);
    this.test('redeemer/publication', this.plan.redeemers.length === 0);
    let count = 0;
    for (let i = 0; i < this.ledger.outputs.length; i++) {
      const o = this.plan.outputs[i];
      if (!o.hasScript) {
        this.test('publicationShape/change', this.payer === o.address && this.clean(o));
        continue;
      }
      count++;
      const raw = this.ledger.outputs[i];
      const script = readCbor(hexToBytes(bytesOf(tagged(fieldsOf(raw).get(3), 24))));
      req(arrayOf(script).length === 2);
      const prefixed = hexToBytes('03' + bytesOf(at(script, 1)));
      this.test('publicationShape/holder', target === o.address && language === 'PlutusScriptV3' && uint(at(script, 0)) === 3
        && digest(224, prefixed) === hash && o.datum === null && o.datumHash === null && Object.keys(o.value).length === 1);
      const holderCoin = n(o.value, 'lovelace');
      const min = n(pp, 'coins_per_utxo_size') * BigInt(160 + raw.end - raw.start);
      this.test('holderLovelace/range', holderCoin >= min && holderCoin <= min + 1_000_000n);
    }
    this.test('publicationShape/count', count === (holder ? 1 : 0));
  }
}

// ------------------------------------------------------------------------------------------------- CallRules.java

/**
 * The CBOR rules of Plutus decodeData that the pinned JuLC decoder applies (julc-core MapPreservingCborDecoder,
 * PlutusDataCborDecoder) and a generic CBOR read does not: every byte string (a bytes value or a bignum's payload)
 * is definite of at most 64 bytes, or indefinite of such chunks; no text strings, floats or simple values; tags 2
 * and 3 only in their one-byte headers and around a byte string; constructors as tags 121-127, 1280-1400 or 102
 * [index, fields]. A constructor index beyond JavaScript's safe integers fails closed (the data model keeps a Number,
 * so two such indices could compare equal).
 */
function plutusRules(node) {
  switch (node.major) {
    case 0: case 1: return;
    case 2:
      if (node.arg >= 0n) req(node.arg <= 64n);
      else for (const chunk of node.items) req(chunk.major === 2 && chunk.arg >= 0n && chunk.arg <= 64n);
      return;
    case 4: node.items.forEach(plutusRules); return;
    case 5: node.items.forEach(plutusRules); return;
    case 6: {
      const tag = node.arg;
      const content = node.items[0];
      const header = node.src[node.start];
      if (tag === 2n || tag === 3n) {
        req(header === (tag === 2n ? 0xc2 : 0xc3) && content.major === 2);
        plutusRules(content);
        return;
      }
      req((tag >= 121n && tag <= 127n) || (tag >= 1280n && tag <= 1400n) || tag === 102n);
      req(content.major === 4);
      if (tag === 102n) {
        req(content.items.length === 2 && content.items[0].major === 0 && content.items[0].arg <= BigInt(Number.MAX_SAFE_INTEGER)
          && content.items[1].major === 4);
        content.items[1].items.forEach(plutusRules);
        return;
      }
      content.items.forEach(plutusRules);
      return;
    }
    default: req(false);
  }
}

/** Plutus data from hex, as the pinned decoder reads it: nesting, full consumption and its byte-string rules first. */
export function plutusData(hex) {
  req(typeof hex === 'string');
  const bytes = hexToBytes(hex);
  plutusRules(readCbor(bytes));
  return decodeData(bytes);
}
const data = plutusData;
const isConstr = (d) => d && 'constr' in d;
const refText = (r) => `${asText(rq(r, 'txId'))}#${asText(rq(r, 'index'))}`;
const within = (received, allowed) => [...received].every(([k, q]) => { const a = allowed.get(k) ?? 0n; return q <= (a > 0n ? a : 0n); });
const sortByLedger = (refs) => [...refs].sort((a, b) => (a.slice(0, 64) < b.slice(0, 64) ? -1 : a.slice(0, 64) > b.slice(0, 64) ? 1
  : Number(a.slice(65)) - Number(b.slice(65))));

class CallRules {
  constructor(c) {
    this.c = c;
    this.codec = c.codec;
    this.meta = c.meta;
    this.call = c.call;
    this.pay = path(c.call, 'pay');
    this.payment = BigInt(asText(path(this.pay, 'lovelace'), '0'));
    this.offer = new Map();
    for (const p of Array.isArray(path(this.pay, 'assets')) ? this.pay.assets : []) {
      for (const asset of rqi(p, 1)) {
        const unit = asText(rqi(p, 0)) + asText(rqi(asset, 0));
        this.offer.set(unit, (this.offer.get(unit) ?? 0n) + BigInt(asText(rqi(asset, 1))));
      }
    }
    if (c.owned) {
      const type = rq(this.meta, 'owned');
      let holding = null;
      for (const o of rq(c.x, 'owned')) if (jeq(type, rq(o, 'type'))) holding = rq(o, 'holding');
      req(holding !== null);
      this.datumSchema = holding;
    } else this.datumSchema = rq(c.x, 'state');
    this.claimed = new Set();
    this.seed = null;
    this.before = null;
    this.after = null;
    this.paymentBound = false;
    this.assetsBound = false;
  }

  decodeDatum(hex) { return this.codec.decode(this.datumSchema, data(hex)); }

  check() {
    const { c, meta, call } = this;
    if (c.spent !== null) {
      try {
        const decoded = this.decodeDatum(asText(rq(c.target, 'inline_datum')));
        if (c.owned) c.test('scriptInput/ownedInstance', jeq(rq(call, 'instance'), rq(decoded, 'instance')));
        const state = c.owned ? rq(decoded, 'value') : decoded;
        this.before = state;
        for (const f of atp(meta, '/policies/signedBy') ?? []) c.test('signer/signedBy', c.caller === asText(rq(state, asText(f))));
      } catch (e) {
        if (!(e instanceof Malformed) || true) c.test('scriptInput/datum', false);
      }
    }
    const sorted = sortByLedger(c.inputs);
    const byAction = asBool(atp(meta, '/returns/mint')) && !c.creation && !c.destroy && c.minted.size > 0;
    const counts = new Map();
    for (const r of c.plan.redeemers) {
      const tag = r.tag;
      const index = r.index;
      counts.set(tag, (counts.get(tag) ?? 0) + 1);
      if ((tag === 'spend' && !c.creation) || (tag === 'mint' && c.creation)) {
        c.test('pointer/method', index === (c.creation ? 0 : sorted.indexOf(c.spent)));
        this.methodRedeemer(r, tag, null);
      } else if (tag === 'mint' && (c.destroy || byAction)) {
        c.test('pointer/mint', index === 0);
        try {
          const decoded = this.codec.decode(this.schema('mint'), data(r.data));
          c.test('redeemer/mintRedeemer', c.destroy ? decoded.$variant === 'BurnInstance' && Object.keys(decoded).length === 1
            : decoded.$variant === 'ByAction' && (!has(decoded, 'by') || c.spent === refText(decoded.by)));
        } catch {
          c.test('redeemer/mintRedeemer', false);
        }
      } else if (tag === 'reward' && c.ledger.b.has(5) && (asBool(atp(meta, '/returns/sends')) || c.mapFields.length > 0)) {
        const outputs = c.reward('outputVerifier');
        c.test('pointer/reward', asBool(atp(meta, '/returns/sends')) && index === (c.mapFields.length === 0 ? 0 : c.rewards.indexOf(outputs)));
        this.outputClaims(r.data);
      } else c.test('redeemer/purpose', false);
    }
    const count = (t) => counts.get(t) ?? 0;
    c.test('redeemer/count', count('spend') === (c.creation ? 0 : 1) && count('mint') === (c.creation || c.destroy || byAction ? 1 : 0)
      && count('reward') === (c.mapFields.length ? c.rewards.length : c.ledger.b.has(5) && asBool(atp(meta, '/returns/sends')) ? 1 : 0));
    if (c.creation || c.destroy) {
      c.test('mint/instance', c.token !== null && c.minted.size === 1 && c.minted.get(c.token) === (c.creation ? 1n : -1n));
    } else {
      c.test('mint/action', c.minted.size === 0 || (byAction && [...c.minted.keys()].every((u) => u.startsWith(c.policy) && u !== c.token)));
    }
    this.outputs();
  }

  schema(purpose) {
    for (const validator of rq(this.c.description, 'validators')) {
      if (asText(rq(validator, 'title')).endsWith('.' + purpose)) return this.codec.resolve(atp(validator, '/redeemer/schema'));
    }
    throw new Malformed('no validator for purpose');
  }

  methodRedeemer(r, purpose, anchor) {
    const { c, meta, call } = this;
    try {
      const schema = this.schema(purpose);
      const constr = data(rq(r, 'data'));
      req(isConstr(constr));
      let alt = null;
      for (const a of rq(schema, 'anyOf')) if (BigInt(rq(a, 'index')) === BigInt(constr.constr)) alt = a;
      if (alt === null || !jeq(rq(alt, 'title'), atp(meta, '/redeemer/constructor')) || constr.constr !== Number(bi(atp(meta, '/redeemer/index')))
        || purpose !== asText(atp(meta, '/redeemer/purpose'))) {
        c.test('redeemer/constructor', false);
        return null;
      }
      const decoded = this.codec.decode(schema, constr);
      const args = new Set(rq(meta, 'args').map((a) => asText(rq(a, 'name'))));
      let index = 0;
      for (const f of rq(alt, 'fields')) {
        const name = asText(rq(f, 'title'));
        const field = constr.fields[index++];
        req(field !== undefined);
        if (args.delete(name)) {
          c.test('redeemer/argument', dataEqual(field, this.codec.encode(f, rq(rq(call, 'args'), name))));
        } else {
          switch (name) {
            case 'ctx': {
              const ctx = rq(decoded, name);
              c.test('redeemer/ctxCaller', c.caller === asText(rq(ctx, 'caller')));
              this.paymentBound = this.payment === n(ctx, 'received');
              c.test('redeemer/ctxReceived', this.paymentBound);
              break;
            }
            case 'assetsIn':
              this.assetsBound = dataEqual(field, this.codec.encode(f, has(this.pay, 'assets') ? this.pay.assets : []));
              c.test('redeemer/assetsIn', this.assetsBound);
              break;
            case 'events': break; // fully decoded by the anchored schema; the validator checks their values
            case 'anchor': c.test('redeemer/anchor', anchor !== null && anchor === refText(rq(decoded, name))); break;
            case 'instance': c.test('redeemer/instance', jeq(rq(call, 'instance'), rq(decoded, name))); break;
            case 'seed': {
              const seed = rq(decoded, 'seed');
              this.seed = refText(seed);
              const i = n(seed, 'index');
              req(i >= -INT_MAX - 1n && i <= INT_MAX);
              c.test('redeemer/seed', c.creation && c.walletRefs.has(refText(seed)) && i >= 0n && i <= 65535n);
              const hex = asText(rq(seed, 'txId')) + (i >= 0n ? i.toString(16).padStart(4, '0') : ((1n << 32n) + i).toString(16));
              const id = (c.owned ? '4f' : '') + digest(c.owned ? 224 : 256, hexToBytes(hex));
              c.token = c.policy + id;
              c.created = id;
              c.test('planMismatch/creationId', id === asText(rq(c.prepared, c.owned ? 'object' : 'instance')));
              break;
            }
            default: c.test('notVerifiable/field', false);
          }
        }
      }
      c.test('redeemer/args', args.size === 0);
      return decoded;
    } catch {
      c.test('redeemer/decode', false);
      return null;
    }
  }

  /** The output verifier's OutputClaims(claims, deposits) redeemer (ADR-0007 D5), by its fixed shape; no deposits. */
  outputClaims(hex) {
    const c = this.c;
    let d;
    try {
      d = data(hex);
      if (!isConstr(d) || d.constr !== 0 || d.fields.length !== 2 || !('list' in d.fields[1]) || d.fields[1].list.length) throw new Error('shape');
    } catch {
      c.test('redeemer/claimsShape', false);
      return;
    }
    if (!('list' in d.fields[0])) { c.test('redeemer/claimsShape', false); return; }
    let previous = -1n;
    for (const it of d.fields[0].list) {
      const by = it?.fields?.[1];
      if (!isConstr(it) || it.constr !== 0 || it.fields.length !== 2 || !('int' in it.fields[0]) || !isConstr(by) || by.constr !== 0
        || by.fields.length !== 2 || !('bytes' in by.fields[0]) || by.fields[0].bytes.length !== 32 || !('int' in by.fields[1])) {
        c.test('redeemer/claimsShape', false);
        return;
      }
      const output = it.fields[0].int;
      const ordered = output > previous && output < BigInt(c.ledger.outputs.length);
      c.test('redeemer/claimsOrder', ordered);
      c.test('redeemer/claimsBy', c.spent !== null && c.spent === `${bytesToHex(by.fields[0].bytes)}#${by.fields[1].int}`);
      if (!ordered) return;
      previous = output;
      this.claimed.add(Number(output));
    }
  }

  isTag(datum) {
    try {
      return refText(this.codec.decode(this.c.definition('org.julclang.ledger.TxOutRef'), data(datum))) === this.c.spent;
    } catch {
      return false;
    }
  }

  outputs() {
    const { c, meta, call } = this;
    let continuations = 0;
    const back = new Map();
    const third = new Map();
    const continuation = new Map();
    const tagged = new Map();
    const sends = new Map();
    const outputs = c.plan.outputs;
    for (let i = 0; i < outputs.length; i++) {
      const o = outputs[i];
      const amount = valueOfPlan(o.value);
      const addr = o.address;
      // F-C1: the continuation and third parties may hold another unit of the policy only as this call's own positive
      // mint; wallet outputs are exempt (the net-outflow bound limits what the wallet loses of any unit).
      if (!c.wallet(addr)) {
        for (const unit of amount.keys()) {
          if (unit.startsWith(c.policy) && unit !== c.token) {
            c.test('continuingOutput/otherToken', asBool(atp(meta, '/returns/mint')) && (c.minted.get(unit) ?? 0n) > 0n);
          }
        }
      }
      if (c.token !== null && amount.has(c.token)) {
        c.test('continuingOutput/thread', !c.destroy && addr === c.address && amount.get(c.token) === 1n);
      }
      if (c.wallet(addr)) {
        const claim = this.claimed.has(i);
        c.test('walletOutput/script', !o.hasScript && o.datumHash === null);
        if (o.datum !== null) {
          const ok = this.isTag(o.datum);
          c.test('walletOutput/tag', ok);
          c.test('walletOutput/claimedTag', !ok || !this.claimed.has(i));
          if (ok) addValue(tagged, amount, 1);
        } else if (!this.claimed.has(i)) c.test('walletOutput/change', c.payer === addr); // change stays at the payer
        if (claim) addValue(sends, amount, 1);
        addValue(back, amount, 1);
      } else if (addr === c.address && c.token !== null && amount.get(c.token) === 1n) {
        continuations++;
        addValue(continuation, amount, 1);
        c.test('continuingOutput/script', !o.hasScript);
        try {
          req(typeof o.datum === 'string');
          const d = this.decodeDatum(o.datum);
          this.after = c.owned ? rq(d, 'value') : d;
          c.test('continuingOutput/instance', !c.owned || jeq(rq(call, 'instance'), rq(d, 'instance')));
        } catch {
          c.test('continuingOutput/datum', false);
        }
      } else {
        // Rule 6 for every claimed output, whoever receives it: a claimed send carries no payout tag.
        if (this.claimed.has(i) && o.datum !== null) c.test('walletOutput/claimedTag', !this.isTag(o.datum));
        c.test('walletOutput/foreignKey', c.caller !== paymentKey(addr));
        addValue(third, amount, 1);
      }
    }
    c.test('continuingOutput/thread', continuations === (c.destroy ? 0 : 1));
    const net = new Map(c.walletIn);
    addValue(net, back, -1);
    const created = continuation.get('lovelace') ?? 0n;
    if (c.creation) c.test('deposit/creation', created <= this.payment + n(c.x, 'minAda'));
    const allowed = new Map(this.offer);
    allowed.set('lovelace', n(c.plan, 'fee') + (c.creation ? created : this.payment));
    c.test('netOutflow/wallet', within(net, allowed));
    // What the script releases: the target's value, less the continuation, plus mint, less what it pays the wallet; a
    // spend may also pay others out of the payment its redeemer binds.
    const released = new Map(c.targetValue);
    addValue(released, continuation, -1);
    addValue(released, c.minted, 1);
    if (!c.creation && c.spent !== null) {
      if (this.paymentBound) addValue(released, new Map([['lovelace', this.payment]]), 1);
      if (this.assetsBound) addValue(released, this.offer, 1);
    }
    addValue(released, tagged, -1);
    addValue(released, sends, -1);
    c.test('netOutflow/third', within(third, released));
  }
}

// ------------------------------------------------------------------------------------------------- KeyedRules.java

/** An output re-encoded as cardano-client-lib writes it (canonical CBOR), with an optional new datum and coin. */
function reencodeOutput(rawNode, { datum, coin } = {}) {
  const map = rawNode.major === 5;
  const f = map ? fieldsOf(rawNode) : new Map();
  const addr = map ? f.get(0) : at(rawNode, 0);
  const val = map ? f.get(1) : at(rawNode, 1);
  req(addr && val);
  let lovelace;
  const assets = new Map(); // policy -> [[name, qty]]
  if (val.major === 4) {
    lovelace = integer(at(val, 0));
    const m = at(val, 1);
    req(m.major === 5);
    for (let i = 0; i < m.items.length; i += 2) {
      const names = m.items[i + 1];
      req(names.major === 5);
      const list = [];
      for (let j = 0; j < names.items.length; j += 2) list.push([bytesOf(names.items[j]), integer(names.items[j + 1])]);
      assets.set(bytesOf(m.items[i]), list);
    }
  } else lovelace = integer(val);
  if (coin !== undefined) lovelace = coin;
  const valueBytes = assets.size
    ? cArray([cInt(lovelace), cMap([...assets].map(([p, list]) => [cBytes(p), cMap(list.map(([name, q]) => [cBytes(name), cInt(q)]))]))])
    : cInt(lovelace);
  let inline = null;
  let hash = null;
  if (f.has(2)) {
    const d = f.get(2);
    if (uint(at(d, 0)) === 1) inline = canonicalHex(plutusData(bytesOf(tagged(at(d, 1), 24))));
    else hash = bytesOf(at(d, 1));
  } else if (!map && rawNode.items.length === 3) hash = bytesOf(at(rawNode, 2));
  if (datum !== undefined) { inline = datum; hash = null; }
  const script = f.has(3) ? f.get(3) : null;
  if (inline !== null || script !== null) {
    const entries = [[cInt(0n), cBytes(bytesOf(addr))], [cInt(1n), valueBytes]];
    if (inline !== null) entries.push([cInt(2n), cArray([cInt(1n), [...head(6, 24), ...cBytes(inline)]])]);
    else if (hash !== null) entries.push([cInt(2n), cArray([cInt(0n), cBytes(hash)])]);
    if (script !== null) entries.push([cInt(3n), [...script.src.subarray(script.start, script.end)]]);
    return Uint8Array.from(cMap(entries));
  }
  const items = [cBytes(bytesOf(addr)), valueBytes];
  if (hash !== null) items.push(cBytes(hash));
  return Uint8Array.from(cArray(items));
}

class KeyedRules {
  constructor(c) {
    this.c = c;
    this.codec = c.codec;
    this.method = new CallRules(c);
    this.init = c.kind === 'init';
    this.insert = c.kind === 'keyedInit';
    this.remove = c.kind === 'keyedRemove';
    this.retire = c.kind === 'keyedRetire';
    this.minAda = n(c.x, 'minAda');
    this.pay = BigInt(asText(atp(c.call, '/pay/lovelace'), '0'));
    this.coins = n(c.pp, 'coins_per_utxo_size');
    this.contribution = this.pay;
    this.released = 0n;
    this.instance = textValue(path(c.call, 'instance'));
    this.type = null;
    for (const t of Array.isArray(c.x.keyed) ? c.x.keyed : []) if (jeq(path(t, 'simple'), path(c.meta, 'keyed'))) this.type = t;
    this.outputs = new Map();
    this.used = new Set();
    this.redeemers = new Map();
    this.order = sortByLedger(c.inputs);
    this.target = null;
    this.anchor = null;
    this.before = null;
    this.after = null;
    this.removed = false;
  }

  check() {
    const { c } = this;
    const designated = textValue(path(c.call, 'designated'));
    c.test('designatedInput/designated', c.walletRefs.has(designated) && designated === textValue(atp(c.call, '/operation/designated')));
    c.test('notVerifiable/keyedCall', this.coins > 0n && this.coins <= 20_000n && this.pay >= 0n
      && (this.pay === 0n || (!this.retire && asBool(path(c.meta, 'payable')))) && isEmpty(atp(c.call, '/pay/assets')));
    for (const r of c.plan.redeemers) this.redeemers.set(r.tag + r.index, r);
    const outs = c.plan.outputs;
    for (let i = 0; i < outs.length; i++) {
      const o = outs[i];
      for (const unit of Object.keys(o.value)) {
        if (!unit.startsWith(c.policy)) continue;
        const fresh = !this.outputs.has(unit);
        this.outputs.set(unit, i);
        c.test('continuingOutput/keyedOutput', fresh && c.address === o.address && Object.keys(o.value).length === 2
          && n(o.value, unit) === 1n && typeof o.datum === 'string' && o.datumHash === null && !o.hasScript);
      }
    }
    if (this.init) this.creation();
    else this.listCall();
    c.test('redeemer/keyedExtra', this.redeemers.size === 0);
    c.test('continuingOutput/keyedConsumed', this.used.size === this.outputs.size && [...this.used].every((u) => this.outputs.has(u)));
    this.walletOutputs();
  }

  take(purpose, index) {
    const r = this.redeemers.get(purpose + index);
    req(r !== undefined);
    this.redeemers.delete(purpose + index);
    return r;
  }
  unit(t, key) {
    return this.c.policy + '4b' + digest(224, hexToBytes(this.instance + asText(rq(t, 'typeId')) + key));
  }
  output(unit) {
    req(!this.used.has(unit) && this.outputs.has(unit));
    this.used.add(unit);
    return this.c.plan.outputs[this.outputs.get(unit)];
  }
  minimum(unit) {
    const { c } = this;
    const raw = c.ledger.outputs[this.outputs.get(unit)];
    // Re-encode the whole output, its value, datum wrapper and Plutus data: padding in host-supplied CBOR cannot buy a
    // larger top-up allowance.
    const datum = asText(rq(c.plan.outputs[this.outputs.get(unit)], 'datum'));
    c.test('continuingOutput/keyedCanonicalDatum', datum === canonicalHex(data(datum)));
    const canonical = reencodeOutput(raw);
    c.test('continuingOutput/keyedCanonicalOutput', rawOf(raw) === bytesToHex(canonical));
    return this.coins * BigInt(160 + canonical.length);
  }

  creation() {
    const { c } = this;
    c.test('scriptInput/keyedCreation', c.walletRefs.size === c.inputs.length);
    const decoded = this.method.methodRedeemer(this.take('mint', 0), 'mint', null);
    if (decoded === null || c.token === null) return;
    this.checkCtx(decoded);
    this.instance = c.token.slice(56);
    const minted = new Map([[c.token, 1n]]);
    const state = this.output(c.token);
    this.after = this.codec.decode(rq(c.x, 'state'), data(asText(rq(state, 'datum'))));
    this.minimum(c.token);
    this.contribution = n(state.value, 'lovelace');
    c.test('deposit/keyedState', this.contribution <= this.minAda + this.pay);
    for (const t of rq(c.x, 'keyed')) {
      const headUnit = this.unit(t, '');
      minted.set(headUnit, 1n);
      const o = this.output(headUnit);
      const d = this.codec.decode(rq(t, 'head'), data(asText(rq(o, 'datum'))));
      c.test('continuingOutput/keyedHead', this.instance === asText(rq(d, 'instance')) && asText(rq(d, 'next')) === '' && n(d, 'linkedAt') === 0n);
      const deposit = n(o.value, 'lovelace');
      const min = this.minimum(headUnit);
      c.test('deposit/keyedHead', deposit === (min > this.minAda ? min : this.minAda) && deposit - this.minAda <= MAX_TOP);
      this.contribution += deposit;
    }
    c.test('mint/keyedCreation', mapEquals(minted, c.minted));
  }

  node(ref, unit, hex, coin) {
    const datum = data(hex);
    req(isConstr(datum));
    let kind = null;
    for (const k of ['head', 'node', 'retired']) if (BigInt(rq(this.type, k + 'Tag')) === BigInt(datum.constr)) kind = k;
    req(kind !== null);
    const d = this.codec.decode(rq(this.type, kind), datum);
    const key = kind === 'head' ? '' : kind === 'node' ? asText(rq(rq(d, 'value'), asText(rq(this.type, 'key')))) : asText(rq(d, 'key'));
    this.c.test('keyedToken/unit', unit === this.unit(this.type, key));
    this.c.test('scriptInput/keyedInstance', this.instance === asText(rq(d, 'instance')));
    return { ref, unit, kind, key, datum: d, coin };
  }

  listCall() {
    const { c } = this;
    req(this.type !== null);
    for (const ref of c.inputs) {
      if (c.walletRefs.has(ref)) continue;
      const u = rq(rq(c.v, 'resolved'), ref);
      const amount = utxoValue(u);
      const coin = amount.get('lovelace');
      amount.delete('lovelace');
      req(coin !== undefined && amount.size === 1);
      const unit = [...amount.keys()][0];
      c.test('scriptInput/keyedInput', c.address === asText(rq(u, 'address')) && amount.get(unit) === 1n);
      const nd = this.node(ref, unit, asText(rq(u, 'inline_datum')), coin);
      if (!this.insert && nd.kind === 'node' && nd.key === asText(rq(c.call, 'object'))) {
        c.test('scriptInput/keyedTarget', this.target === null);
        this.target = nd;
      } else {
        c.test('scriptInput/keyedAnchor', this.anchor === null);
        this.anchor = nd;
      }
    }
    const { insert, remove, retire } = this;
    c.test('scriptInput/keyedRoles', (insert || remove) === (this.anchor !== null) && insert === (this.target === null));
    if (this.anchor) this.structuralRedeemer('spend', this.order.indexOf(this.anchor.ref), 'link');
    req(insert ? this.anchor !== null : this.target !== null);
    const call = insert ? this.method.methodRedeemer(this.take('mint', 0), 'mint', this.anchor.ref)
      : this.method.methodRedeemer(this.take('spend', this.order.indexOf(this.target.ref)), 'spend', null);
    if (call !== null) this.checkCtx(call);
    if (this.target) {
      for (const f of atp(c.meta, '/policies/signedBy') ?? []) {
        c.test('signer/keyedSignedBy', c.caller === asText(rq(rq(this.target.datum, 'value'), asText(f))));
      }
    }
    if (insert) {
      req(c.minted.size === 1);
      const token = [...c.minted.keys()][0];
      c.test('mint/keyedInsert', c.minted.get(token) === 1n);
      const nd = this.continuing(token);
      const anchor = this.anchor;
      c.test('continuingOutput/keyedInsert', nd.kind === 'node' && nd.key.length >= 2 && nd.key.length / 2 <= Number(bi(rq(this.type, 'maxKeyBytes')))
        && (!asBool(rq(this.type, 'signedKey')) || nd.key === c.caller)
        // The mint validator reads the key's signature from the body's required signers, not the payer's witness.
        && (!asBool(rq(this.type, 'signedKey')) || strings(c.plan.requiredSigners).includes(nd.key))
        && jeq(rq(nd.datum, 'next'), rq(anchor.datum, 'next'))
        && nd.key > anchor.key
        && (next(anchor) === '' || nd.key < next(anchor)));
      c.test('planMismatch/keyedObject', nd.key === textValue(path(c.prepared, 'object')));
      c.created = nd.key;
      this.after = path(nd.datum, 'value') ?? null;
      c.test('keyedLink/insertedAt', n(nd.datum, 'linkedAt') === this.time('validTo'));
      c.test('deposit/keyedInsert', nd.coin === this.minAda + this.pay);
      this.contribution = nd.coin;
      this.relink(nd.key);
    } else if (remove) {
      c.test('mint/keyedRemove', mapEquals(c.minted, new Map([[this.target.unit, -1n]])));
      this.before = path(this.target.datum, 'value') ?? null;
      this.removed = true;
      this.structuralRedeemer('mint', 0, 'remove');
      c.test('keyedLink/removedNext', next(this.anchor) === this.target.key);
      this.relink(next(this.target));
      this.released = this.target.coin + this.pay;
    } else {
      c.test('mint/keyedAction', c.minted.size === 0);
      const nd = this.continuing(this.target.unit);
      // The object's value before and after; a retired object keeps only its retired record.
      this.before = path(this.target.datum, 'value') ?? null;
      this.after = retire ? nd.datum : path(nd.datum, 'value') ?? null;
      c.test('continuingOutput/keyedUpdate', nd.kind === (retire ? 'retired' : 'node') && nd.key === this.target.key
        && next(nd) === next(this.target) && jeq(rq(nd.datum, 'linkedAt'), rq(this.target.datum, 'linkedAt')));
      if (retire) {
        this.contribution = this.topUp(nd, this.minAda);
        this.released = this.target.coin - this.minAda;
      } else this.released = this.target.coin + this.pay - nd.coin;
    }
  }

  structuralRedeemer(purpose, pointer, role) {
    const { c } = this;
    try {
      const raw = data(rq(this.take(purpose, pointer), 'data'));
      req(isConstr(raw));
      const spec = rq(this.type, role);
      const decoded = this.codec.decode(this.method.schema(purpose), raw);
      let ok = BigInt(raw.constr) === BigInt(rq(spec, 'index')) && jeq(rq(spec, 'constructor'), rq(decoded, '$variant'));
      // Link has no fields in the anchored schema, so a decoded Link has none.
      if (role !== 'link') ok &&= this.anchor.ref === refText(rq(decoded, 'anchor')) && this.target.ref === refText(rq(decoded, 'removed'));
      c.test('keyedRedeemer/structural', ok);
    } catch {
      c.test('keyedRedeemer/structural', false);
    }
  }
  continuing(unit) {
    const o = this.output(unit);
    this.minimum(unit);
    return this.node(null, unit, asText(rq(o, 'datum')), n(o.value, 'lovelace'));
  }
  time(bound) {
    const clock = rq(this.c.v, 'clock');
    return n(clock, 'zeroTimeMs') + (n(this.c.plan, bound) - n(clock, 'zeroSlot')) * n(clock, 'slotLengthMs');
  }
  topUp(nd, previous) {
    const top = nd.coin - previous;
    const ok = top >= 0n && (() => {
      let bound = this.minimum(nd.unit) - previous;
      if (bound < 0n) bound = 0n;
      if (bound > MAX_TOP) bound = MAX_TOP;
      return top <= bound;
    })();
    this.c.test('deposit/keyedTopUp', ok);
    return top > 0n ? top : 0n;
  }
  relink(nextKey) {
    const { c } = this;
    const anchor = this.anchor;
    const nd = this.continuing(anchor.unit);
    const lower = this.time('validFrom');
    const upper = this.time('validTo');
    const cooldown = n(this.type, 'linkCooldownMs');
    const bits = upper > 0n ? upper.toString(2).length : 0;
    c.test('keyedLink/relink', bits > 32 && bits <= 64 && upper > 0n && upper - lower <= n(this.type, 'linkWindowMs')
      && (cooldown === 0n || lower >= n(anchor.datum, 'linkedAt') + cooldown) && n(nd.datum, 'linkedAt') === upper && next(nd) === nextKey
      && nd.kind === anchor.kind && nd.key === anchor.key);
    // The value and every field after linkedAt compare byte for byte; only next, linkedAt's integer width and the
    // lovelace's width may grow.
    const raw = c.ledger.outputs[this.outputs.get(anchor.unit)];
    try {
      const before = data(asText(rq(rq(rq(c.v, 'resolved'), anchor.ref), 'inline_datum')));
      const after = data(asText(rq(c.plan.outputs[this.outputs.get(anchor.unit)], 'datum')));
      req(isConstr(before) && isConstr(after));
      c.test('keyedLink/fields', before.fields.length === after.fields.length);
      for (let i = 3; i < before.fields.length; i++) {
        c.test('keyedLink/fields', after.fields[i] !== undefined && canonicalHex(before.fields[i]) === canonicalHex(after.fields[i]));
      }
      const priorSize = reencodeOutput(raw, { datum: canonicalHex(before), coin: anchor.coin }).length;
      const growth = Math.max(0, Math.trunc((nextKey.length - next(anchor).length) / 2)) + 9 + 8;
      c.test('keyedLink/size', (raw.end - raw.start) <= priorSize + growth);
    } catch (e) {
      throw new Malformed('invalid predecessor output: ' + e.message);
    }
    this.contribution += this.topUp(nd, anchor.coin);
  }
  // The method's Ctx time window is not compared with the body's validity here: the validator enforces it on chain,
  // and the body's bounds are checked against the client's own window by the envelope rules.
  checkCtx(decoded) {
    const ctx = path(decoded, 'ctx');
    this.c.test('keyedInputValue/ctx', !this.remove || isObj(ctx));
    if (isObj(ctx)) {
      const expected = this.target === null ? 0n : this.remove ? this.target.coin : this.target.coin - this.minAda;
      this.c.test('keyedInputValue/balance', n(ctx, 'balance') === expected);
    }
  }

  walletOutputs() {
    const { c } = this;
    const net = new Map(c.walletIn);
    const third = new Map();
    const tagged = new Map();
    for (const o of c.plan.outputs) {
      const amount = valueOfPlan(o.value);
      if ([...amount.keys()].some((u) => this.used.has(u))) continue;
      const addr = o.address;
      if (c.wallet(addr)) {
        addValue(net, amount, -1);
        c.test('walletOutput/keyedScript', !o.hasScript && o.datumHash === null);
        if (o.datum !== null) {
          const tag = this.codec.decode(c.definition('org.julclang.ledger.TxOutRef'), data(o.datum));
          c.test('walletOutput/keyedTag', this.target !== null && this.target.ref === refText(tag));
          addValue(tagged, amount, 1);
        } else c.test('walletOutput/keyedChange', c.payer === addr); // rule 6: change stays at the payer
      } else {
        c.test('walletOutput/keyedForeignKey', c.caller !== paymentKey(addr));
        addValue(third, amount, 1);
      }
    }
    // The wallet may lose the fee and its contribution (payment and deposits); others only what the call releases.
    for (const [k, q] of net) {
      c.test('netOutflow/keyedWallet', q <= (k === 'lovelace' ? n(c.plan, 'fee') + this.contribution : 0n));
    }
    for (const [k, q] of third) {
      const free = k === 'lovelace' ? this.released : 0n;
      const bound = free - (tagged.get(k) ?? 0n);
      c.test('netOutflow/keyedThird', q <= (bound > 0n ? bound : 0n));
    }
  }
}
const next = (nd) => asText(rq(nd.datum, 'next'));
