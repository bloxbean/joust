// The tagged JSON form of values, calls and answers in differential vectors (docs/reference/jsc-2-ir.md, "Values in
// vectors"). It needs no types: { "int": "…" }, true/false, { "bytes": hex }, [ … ], { "none": true }, { "some": v },
// { "rec": name, "fields": [ … ] }, { "map": [[k, v] …] } (in the given order), { "bigmap": [[k, v] …] }.

import { NONE, Some, some, Rec, MapV, BigMapV, Refused, Limit, hex, fromHex, keyCompare, ValueV, CredV, AddrV, StakeHash, StakePtr, InstV } from './values.js';

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
    // M2: { "value": [[policy, name, "q"]…] }, { "keyCred": hex }, { "scriptCred": hex },
    // { "address": [credential, null | { "stakeHash": credential } | { "stakePtr": ["s", "t", "c"] }] }.
    if ('value' in j) return valueFromJson(j);
    if ('keyCred' in j) return new CredV(false, fromHex(j.keyCred));
    if ('scriptCred' in j) return new CredV(true, fromHex(j.scriptCred));
    // M2c: { "instance": [policy hex, id hex] }, another contract's instance (a params field).
    if ('instance' in j) return new InstV(fromHex(j.instance[0]), fromHex(j.instance[1]));
    if ('address' in j) {
      const [pay, stake] = j.address;
      const s = stake === null ? NONE : 'stakeHash' in stake ? some(new StakeHash(fromJson(stake.stakeHash)))
        : some(new StakePtr(BigInt(stake.stakePtr[0]), BigInt(stake.stakePtr[1]), BigInt(stake.stakePtr[2])));
      return new AddrV(fromJson(pay), s);
    }
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
  if (v instanceof ValueV) return valueToJson(v);
  if (v instanceof CredV) return v.script ? { scriptCred: hex(v.hash) } : { keyCred: hex(v.hash) };
  if (v instanceof InstV) return { instance: [hex(v.policy), hex(v.id)] };
  if (v instanceof AddrV) {
    const s = v.stake === NONE ? null : v.stake.value instanceof StakeHash ? { stakeHash: toJson(v.stake.value.credential) }
      : { stakePtr: [v.stake.value.slot.toString(), v.stake.value.txIndex.toString(), v.stake.value.certIndex.toString()] };
    return { address: [toJson(v.payment), s] };
  }
  if (v instanceof BigMapV) {
    const keys = [...v.now.keys()].sort((a, b) => keyCompare(fromHex(a), fromHex(b)));
    return { bigmap: keys.map((k) => [{ bytes: k }, toJson(v.now.get(k))]) };
  }
  throw new TypeError(`not a value: ${v}`);
}

export function valueFromJson(j) {
  return new ValueV(j.value.map(([p, n, q]) => [fromHex(p) && p, fromHex(n) && n, BigInt(q)]));
}

export function valueToJson(v) {
  return { value: v.entries.map(([p, n, q]) => [p, n, q.toString()]) };
}

export function txFromJson(t) {
  if (t === null || t === undefined) return null;
  return {
    // M2: the native assets the call sees (absent: a call that reads none).
    funds: t.funds ? {
      received: valueFromJson(t.funds.received), held: valueFromJson(t.funds.held), policy: fromHex(t.funds.policy), self: fromHex(t.funds.self),
      // M2c: the deposits claimed on the call's output (a receiving action's), each its value and its memo (null: not read).
      deposits: (t.funds.deposits ?? []).map((d) => ({ value: valueFromJson(d.value), memo: d.memo === null || d.memo === undefined ? null : fromJson(d.memo) })),
    } : null,
    // M2c: the states of other contracts' instances the transaction references, by the params field naming each.
    reads: Object.fromEntries(Object.entries(t.reads ?? {}).map(([k, v]) => [k, fromJson(v)])),
    // M3a (§2.8): the instance's state an owned method reads (shared), a move's destination (into), an admits action's
    // arriving object; absent when the call has none.
    shared: t.shared == null ? null : fromJson(t.shared),
    into: t.into == null ? null : fromJson(t.into),
    arriving: t.arriving == null ? null : fromJson(t.arriving),
    // M3b (§2.9): a keyed insert's or removal's instance and the node it relinks (its anchor or predecessor).
    instance: t.instance == null ? null : fromHex(t.instance),
    anchor: t.anchor == null ? null : {
      instance: fromHex(t.anchor.instance), type: t.anchor.type, key: fromHex(t.anchor.key), next: fromHex(t.anchor.next), linkedAt: BigInt(t.anchor.linkedAt),
    },
    caller: fromHex(t.caller),
    received: BigInt(t.received),
    balance: BigInt(t.balance),
    validFrom: BigInt(t.validFrom),
    validTo: BigInt(t.validTo),
    // §2.6: the keys that signed the transaction; the caller alone when the vector does not say.
    signers: (t.signers ?? [t.caller]).map(fromHex),
    // M2b: the scripts that approve this call (authorized), none when the vector does not say.
    approvers: (t.approvers ?? []).map(fromHex),
  };
}

/**
 * Runs one call in the JSON form; with "transition": true, the transition alone (§2.6: without the modifiers, as the
 * generated Java and the transition program compute it). The answer is { ok: { value, payouts } }, { refused: message, class: "kind:detail" }
 * (semantics §2.5), or { limit: message } when this interpreter could not finish (§2.4: no answer).
 */
export function runCall(program, call) {
  try {
    // M2b: a contract with params is called as deployed with them (call.params, a Config record).
    const deployed = call.params == null ? program : program.withParams(fromJson(call.params));
    const run = call.transition === true ? deployed.transition.bind(deployed) : deployed.call.bind(deployed);
    const o = run(call.method, call.state == null ? null : fromJson(call.state), call.args.map(fromJson), txFromJson(call.tx));
    const ok = {
      value: o.value === null ? null : toJson(o.value),
      payouts: o.payouts.map((p) => ({ to: hex(p.to), lovelace: p.lovelace.toString() })),
    };
    // M2's effects, only when there are any (an answer of M1's contracts keeps its form).
    // M2c: a send's datum when it has one.
    if (o.sends.length > 0) {
      ok.sends = o.sends.map((x) => (x.datum === null || x.datum === undefined ? { to: toJson(x.to), value: valueToJson(x.value) }
        : { to: toJson(x.to), value: valueToJson(x.value), datum: toJson(x.datum) }));
    }
    if (o.mint.entries.length > 0) ok.mint = valueToJson(o.mint);
    if (o.accepted.entries.length > 0) ok.accepted = valueToJson(o.accepted);
    if (o.events.length > 0) ok.events = o.events.map(toJson);
    // M2c: the deposits the call makes, in source order.
    if ((o.deposits ?? []).length > 0) ok.deposits = o.deposits.map((d) => ({ target: toJson(d.target), value: valueToJson(d.value), memo: toJson(d.memo) }));
    return { ok };
  } catch (e) {
    if (e instanceof Refused) return { refused: e.message, class: e.refusal };
    if (e instanceof Limit) return { limit: e.message };
    throw e;
  }
}
