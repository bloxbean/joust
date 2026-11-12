// The tagged JSON form of values, calls and answers in differential vectors (docs/reference/jsc-2-ir.md, "Values in
// vectors"). It needs no types: { "int": "…" }, true/false, { "bytes": hex }, [ … ], { "none": true }, { "some": v },
// { "rec": name, "fields": [ … ] }, { "map": [[k, v] …] } (in the given order), { "bigmap": [[k, v] …] }.

import { NONE, Some, some, Rec, MapV, BigMapV, Refused, Limit, hex, fromHex, keyCompare } from './values.js';

export function fromJson(j) {
  if (j === true || j === false) return j;
  if (Array.isArray(j)) return Object.freeze(j.map(fromJson));
  if (j && typeof j === 'object') {
    if ('int' in j) return BigInt(j.int);
    if ('bytes' in j) return fromHex(j.bytes);
    if ('none' in j) return NONE;
    if ('some' in j) return some(fromJson(j.some));
    if ('rec' in j) return new Rec(j.rec, j.fields.map(fromJson));
    if ('map' in j) return MapV.raw(j.map.map(([k, v]) => [fromHex(k.bytes), fromJson(v)]));
    if ('bigmap' in j) return BigMapV.of(new Map(j.bigmap.map(([k, v]) => [k.bytes, fromJson(v)])));
  }
  throw new TypeError(`not a vector value: ${JSON.stringify(j)}`);
}

export function toJson(v) {
  if (typeof v === 'boolean') return v;
  if (typeof v === 'bigint') return { int: v.toString() };
  if (v instanceof Uint8Array) return { bytes: hex(v) };
  if (Array.isArray(v)) return v.map(toJson);
  if (v === NONE) return { none: true };
  if (v instanceof Some) return { some: toJson(v.value) };
  if (v instanceof Rec) return { rec: v.type, fields: v.fields.map(toJson) };
  if (v instanceof MapV) return { map: v.keys.map((k, i) => [{ bytes: hex(k) }, toJson(v.values[i])]) };
  if (v instanceof BigMapV) {
    const keys = [...v.now.keys()].sort((a, b) => keyCompare(fromHex(a), fromHex(b)));
    return { bigmap: keys.map((k) => [{ bytes: k }, toJson(v.now.get(k))]) };
  }
  throw new TypeError(`not a value: ${v}`);
}

export function txFromJson(t) {
  if (t === null || t === undefined) return null;
  return {
    caller: fromHex(t.caller),
    received: BigInt(t.received),
    balance: BigInt(t.balance),
    validFrom: BigInt(t.validFrom),
    validTo: BigInt(t.validTo),
  };
}

/**
 * Runs one call in the JSON form. The answer is { ok: { value, payouts } }, { refused: message, class: "kind:detail" }
 * (semantics §2.5), or { limit: message } when this interpreter could not finish (§2.4: no answer).
 */
export function runCall(program, call) {
  try {
    const o = program.call(call.method, call.state == null ? null : fromJson(call.state), call.args.map(fromJson), txFromJson(call.tx));
    return {
      ok: {
        value: o.value === null ? null : toJson(o.value),
        payouts: o.payouts.map((p) => ({ to: hex(p.to), lovelace: p.lovelace.toString() })),
      },
    };
  } catch (e) {
    if (e instanceof Refused) return { refused: e.message, class: e.refusal };
    if (e instanceof Limit) return { limit: e.message };
    throw e;
  }
}
