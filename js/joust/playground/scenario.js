// Scenarios (ADR-0024 D10): a session's calls, exported as data and replayed in a fresh offline session. Plain
// functions, so Node tests run what the page runs.
//
//   { "scenario": 1, "build": { "fingerprint", "contracts": { name: { "spend": hash } } }, "identities": [labels],
//     "clock": { "zeroTimeMs", "slotLengthMs" }, "steps": [ { "as", "call", "expect" } | { "advanceMs" } ] }
// The clock is the offline ledger's (its origin and slot length): a replay runs on it, so an absolute time in a step (a
// deadline, an unlock time) means what it meant, whenever the scenario is imported.
//
// No key, mnemonic or transaction bytes are exported (I1). Identities are labels: each import makes new keys, so a value
// in a step that was an identity's key hash (or its address) is written as { "$identity": label, "as": "keyHash" |
// "address" }, and an instance, object or key a step created as { "$created": step index }; replay puts the new ones in.
// Positions and roles are kept: a map entry stays [key, value]. Only what the contract needs ordered is ordered again:
// a quorum's key list (an argument named by a method's @Quorum keys, and the cosigners) is written { "$sorted": [...] }
// and sorted with the new keys, and a map argument's entries go back to canonical key order (recanonical).

import { canonicalPairs } from './form.js';

export const SCENARIO_VERSION = 1;

/**
 * A step's call with identities and created ids made symbolic. {@code identities}: [{ label, keyHash, address }];
 * {@code sorted}: the argument names whose lists are a quorum's keys (they must stay strictly ascending).
 */
export function symbolic(call, identities, created, { sorted = [] } = {}) {
  const walk = (v, ordered = false) => {
    if (typeof v === 'string') {
      const id = identities.find((i) => i.keyHash === v || i.address === v);
      if (id) return { $identity: id.label, as: id.keyHash === v ? 'keyHash' : 'address' };
      const at = created.findIndex((c) => c !== null && c === v);
      if (at >= 0) return { $created: at };
      return v;
    }
    if (Array.isArray(v)) {
      const items = v.map((x) => walk(x));
      return ordered ? { $sorted: items } : items;
    }
    if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, walk(x)]));
    return v;
  };
  const out = walk(call);
  // A quorum's key lists: the named arguments, and the cosigners.
  for (const name of sorted) if (Array.isArray(call.args?.[name])) out.args[name] = walk(call.args[name], true);
  if (Array.isArray(call.cosigners)) out.cosigners = walk(call.cosigners, true);
  return out;
}

/** The argument names a contract's @Quorum policies name as key lists (from its description). */
export function quorumKeyNames(description) {
  const x = description?.['x-joust'] ?? {};
  return [...new Set([...(x.actions ?? []), ...(x.creations ?? [])].map((m) => m.policies?.quorum?.keys).filter((k) => typeof k === 'string'))];
}

/**
 * A replayed value with every map's entries in canonical key order again (the new identities' key hashes may order
 * differently), by the argument's form control (form.js classify); everything else as it is.
 */
export function recanonical(value, control) {
  if (value === null || value === undefined || !control) return value;
  switch (control.kind) {
    case 'map':
      return Array.isArray(value) ? canonicalPairs(control.key.kind, value.map(([k, v]) => [recanonical(k, control.key), recanonical(v, control.value)]), control.path) : value;
    case 'list':
      return Array.isArray(value) ? value.map((x) => recanonical(x, control.item)) : value;
    case 'optional':
      return recanonical(value, control.inner);
    case 'constructor': {
      if (typeof value !== 'object' || Array.isArray(value)) return value;
      const v = control.sum ? control.variants.find((x) => x.title === value.$variant) : control.variants[0];
      if (!v) return value;
      const out = { ...value };
      for (const f of v.fields) if (f.name in out) out[f.name] = recanonical(out[f.name], f.control);
      return out;
    }
    default:
      return value;
  }
}

/** The step's call for this session: symbols replaced by the new identities' and the replay's created ids. */
export function concrete(call, identities, created) {
  const walk = (v) => {
    if (Array.isArray(v)) return v.map(walk);
    if (v && typeof v === 'object') {
      if ('$sorted' in v) return walk(v.$sorted).sort();
      if ('$identity' in v) {
        const id = identities.find((i) => i.label === v.$identity);
        if (!id) throw new Error(`the scenario names an identity ${v.$identity} this session does not have`);
        return v.as === 'address' ? id.address : id.keyHash;
      }
      if ('$created' in v) {
        const c = created[v.$created];
        if (typeof c !== 'string') throw new Error(`step ${v.$created} created nothing this replay could refer to`);
        return c;
      }
      return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, walk(x)]));
    }
    return v;
  };
  return walk(call);
}

/** The outcome a step expects, from what it did: confirmed, or a refusal with its stage and code. */
export function outcomeOf(e) {
  return e ? { kind: 'refusal', stage: e.stage ?? null, code: e.code ?? null } : 'confirmed';
}
export const sameOutcome = (a, b) => (a === 'confirmed' || b === 'confirmed' ? a === b
  : a?.kind === b?.kind && a?.stage === b?.stage && a?.code === b?.code);

/** The scenario as exported: checked to hold no secret-like field before it leaves the page (I1). */
export function exportScenario({ fingerprint, contracts, identities, clock, steps }) {
  const s = { scenario: SCENARIO_VERSION, build: { fingerprint, contracts }, identities,
    clock: { zeroTimeMs: clock.zeroTimeMs, slotLengthMs: clock.slotLengthMs }, steps };
  const text = JSON.stringify(s, null, 2);
  if (/mnemonic|privateKey|xprv|ed25519_sk|"cbor"/i.test(text)) throw new Error('refusing to export a scenario that names a key or transaction bytes');
  return text;
}

/** A scenario read back, its shape checked; {@code current}: { fingerprint, contracts } of the session it goes to. */
export function importScenario(text, current) {
  let s;
  try {
    s = JSON.parse(text);
  } catch {
    throw new Error('not a scenario: not JSON');
  }
  if (!s || s.scenario !== SCENARIO_VERSION || !Array.isArray(s.steps) || !Array.isArray(s.identities)
    || !s.identities.every((l) => typeof l === 'string' && l.length > 0 && l.length <= 64)) {
    throw new Error(`not a scenario of version ${SCENARIO_VERSION}`);
  }
  const c = s.clock;
  if (c !== undefined && !(c && Number.isSafeInteger(c.zeroTimeMs) && c.zeroTimeMs >= 0 && Number.isSafeInteger(c.slotLengthMs)
    && c.slotLengthMs >= 1 && c.slotLengthMs <= 3_600_000)) {
    throw new Error('not a scenario: its clock is { zeroTimeMs, slotLengthMs }');
  }
  for (const [i, st] of s.steps.entries()) {
    const advance = Number.isSafeInteger(st?.advanceMs) && st.advanceMs > 0;
    const call = st && typeof st.as === 'string' && st.call && typeof st.call.contract === 'string' && typeof st.call.kind === 'string'
      && typeof st.call.method === 'string';
    if (!advance && !call) throw new Error(`step ${i}: neither a call nor an advance`);
    if (call && !s.identities.includes(st.as)) throw new Error(`step ${i}: ${st.as} is not one of the scenario's identities`);
  }
  // A build with another fingerprint is allowed, with a warning naming the contracts whose scripts differ.
  const warnings = [];
  if (s.build?.fingerprint !== current.fingerprint) {
    const differ = Object.entries(s.build?.contracts ?? {}).filter(([name, c]) => current.contracts[name]?.spend !== c?.spend).map(([n]) => n);
    warnings.push(`another build (fingerprint ${String(s.build?.fingerprint).slice(0, 12)}…, this session's ${current.fingerprint.slice(0, 12)}…)`
      + (differ.length ? `: the scripts of ${differ.join(', ')} differ` : ': the contracts\' scripts are the same'));
  }
  return { scenario: s, warnings };
}
