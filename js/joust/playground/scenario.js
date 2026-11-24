// Scenarios (ADR-0024 D10): a session's calls, exported as data and replayed in a fresh offline session. Plain
// functions, so Node tests run what the page runs.
//
//   { "scenario": 1, "build": { "fingerprint", "contracts": { name: { "spend": hash } } }, "identities": [labels],
//     "clock": { "slotLengthMs" }, "steps": [ { "as", "call", "expect" } | { "advanceMs" } ] }
//
// No key, mnemonic or transaction bytes are exported (I1). Identities are labels: each import makes new keys, so a value
// in a step that was an identity's key hash (or its address) is written as { "$identity": label, "as": "keyHash" |
// "address" }, and an instance, object or key a step created as { "$created": step index }; replay puts the new ones in.
// A strictly ascending list of key hashes is written { "$sorted": [...] } and sorted again with the new keys.

export const SCENARIO_VERSION = 1;

/** A step's call with identities and created ids made symbolic. {@code identities}: [{ label, keyHash, address }]. */
export function symbolic(call, identities, created) {
  const walk = (v) => {
    if (typeof v === 'string') {
      const id = identities.find((i) => i.keyHash === v || i.address === v);
      if (id) return { $identity: id.label, as: id.keyHash === v ? 'keyHash' : 'address' };
      const at = created.findIndex((c) => c !== null && c === v);
      if (at >= 0) return { $created: at };
      return v;
    }
    if (Array.isArray(v)) {
      const items = v.map(walk);
      // A strictly ascending list of identities' key hashes (a @Quorum's members, say) stays ascending with new keys.
      const keys = items.every((x) => x && typeof x === 'object' && x.as === 'keyHash' && '$identity' in x);
      const ascending = v.every((x, i) => i === 0 || (typeof x === 'string' && v[i - 1] < x));
      return keys && ascending && items.length > 1 ? { $sorted: items } : items;
    }
    if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, walk(x)]));
    return v;
  };
  return walk(call);
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
  const s = { scenario: SCENARIO_VERSION, build: { fingerprint, contracts }, identities, clock: { slotLengthMs: clock.slotLengthMs }, steps };
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
