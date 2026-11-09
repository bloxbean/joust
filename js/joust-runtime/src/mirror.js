// MerkleMap mirrors (ADR-0015 §13.12): the off-chain Merkle Patricia Forestry behind each map, kept with Aiken's MPF
// (@aiken-lang/merkle-patricia-forestry). The engine asks for proofs (needs "mapProof"); the mirror answers them, and
// the engine checks every one against the root the chain holds. A mirror is found by its root (a proof depends only on
// the map's content), and rebuilt from the chain when missing: the claims in the instance's map-verifier redeemers,
// replayed from the empty root, each new root checked. The history source is not trusted: anything wrong stops
// matching a root.

import { Trie } from '@aiken-lang/merkle-patricia-forestry';
// MPF works on Node Buffers; imported, not assumed global (a page gets the browser version, as MPF itself does).
import { Buffer } from 'node:buffer';
import { parseTx, decodeData, dataHex, Data, hexToBytes, bytesToHex } from '@julclang/state-client';

export const EMPTY_ROOT = '00'.repeat(32);

const rootOf = (trie) => (trie.hash ? bytesToHex(trie.hash) : EMPTY_ROOT);
const buf = (hex) => Buffer.from(hex, 'hex');
const entryData = (key, value) => Data.constr(0, [Data.bytes(hexToBytes(key)), Data.constr(value === undefined ? 0 : 1, []),
  Data.bytes(value === undefined ? new Uint8Array() : hexToBytes(value))]);
const entryOf = (d) => ({ key: bytesToHex(d.fields[0].bytes), present: d.fields[1].constr === 1, value: bytesToHex(d.fields[2].bytes) });

/** The MapClaims a map-verifier redeemer carries, or null when the data is something else. */
export function claimsOf(d) {
  if (!('constr' in d) || d.constr !== 0 || d.fields.length !== 1 || !('list' in d.fields[0])) return null;
  const out = [];
  for (const c of d.fields[0].list) {
    if (!('constr' in c) || c.fields.length !== 7 || !('list' in c.fields[5])) return null;
    const f = c.fields;
    out.push({
      oldRoot: bytesToHex(f[3].bytes),
      newRoot: bytesToHex(f[6].bytes),
      writes: f[5].list.map((w) => ({ before: entryOf(w.fields[0]), after: entryOf(w.fields[1]) })),
    });
  }
  return out;
}

/** A persistent cache for mirrors over a level database (LevelDB in Node, IndexedDB in browsers): states by root. */
export async function levelMirrorCache(location) {
  const { Level } = await import('level');
  const db = new Level(location, { valueEncoding: 'json' });
  await db.open();
  return {
    async state(root) {
      try {
        return new Map(await db.get('root:' + root));
      } catch {
        return null;
      }
    },
    async putState(root, state) {
      await db.put('root:' + root, [...state]);
    },
    async seen(unit) {
      try {
        return new Set(await db.get('seen:' + unit));
      } catch {
        return new Set();
      }
    },
    async putSeen(unit, set) {
      await db.put('seen:' + unit, [...set]);
    },
    close: () => db.close(),
  };
}

/**
 * The mirrors of every map this runtime works with.
 *
 * @param o.provider  a chain provider with assetHistory and redeemers (rebuilding from the chain), optional
 * @param o.cache     a persistent cache (levelMirrorCache), optional: in memory otherwise
 */
export function mapMirror({ provider, cache } = {}) {
  const states = new Map([[EMPTY_ROOT, new Map()]]);   // root -> Map(key hex -> value hex)
  const seen = new Map();                               // unit -> Set(tx hash) already replayed

  async function stateAt(root) {
    if (states.has(root)) return states.get(root);
    const s = cache ? await cache.state(root) : null;
    if (s) states.set(root, s);
    return s;
  }

  const trieOf = (state) => Trie.fromList([...state].map(([k, v]) => ({ key: buf(k), value: buf(v) })));

  /** Applies every claim whose old root is a known state (in any order), checking each new root; returns how many. */
  async function apply(claims) {
    let applied = 0;
    const pending = [...claims];
    for (let progress = true; progress && pending.length;) {
      progress = false;
      for (let i = 0; i < pending.length; i++) {
        const c = pending[i];
        const before = await stateAt(c.oldRoot);
        if (!before) continue;
        pending.splice(i--, 1);
        progress = true;
        if (await stateAt(c.newRoot)) continue;
        const state = new Map(before);
        const trie = await trieOf(state);
        for (const w of c.writes) {
          if (w.before.present) await trie.delete(buf(w.before.key));
          if (w.after.present) await trie.insert(buf(w.after.key), buf(w.after.value));
          if (w.after.present) state.set(w.after.key, w.after.value);
          else state.delete(w.after.key);
        }
        if (rootOf(trie) !== c.newRoot) throw new Error(`a map claim does not lead to its new root ${c.newRoot}: refused`);
        states.set(c.newRoot, state);
        if (cache) await cache.putState(c.newRoot, state);
        applied++;
      }
    }
    return applied;
  }

  /** Replays the history of {@code unit} (its map-verifier redeemers) not replayed yet. */
  async function sync(unit) {
    if (!provider?.assetHistory || !provider?.redeemers) return 0;
    const done = seen.get(unit) ?? (cache ? await cache.seen(unit) : new Set());
    seen.set(unit, done);
    const claims = [];
    const fresh = [];
    for (const tx of await provider.assetHistory(unit)) {
      if (done.has(tx)) continue;
      fresh.push(tx);
      for (const r of await provider.redeemers(tx)) {
        if (r.purpose !== 'reward') continue;
        let d;
        try {
          d = decodeData(r.data);
        } catch {
          continue;
        }
        claims.push(...(claimsOf(d) ?? []));
      }
    }
    const n = await apply(claims);
    for (const tx of fresh) done.add(tx);
    if (cache) await cache.putSeen(unit, done);
    return n;
  }

  return {
    states,

    /** The value of {@code key} (hex) in the map with {@code root}, or undefined: a page reads a map without the engine. */
    async get(root, key) {
      return (await stateAt(root))?.get(key);
    },

    /**
     * The answer to an engine need ({@code read:<root>:<key>} or {@code writes:<root>:<k>=<v|->,...}), as hex Plutus
     * data. {@code units}: the instance (or object) tokens the call touches, whose history rebuilds a missing map.
     */
    async answer(need, units = []) {
      const [kind, root, rest] = [need.slice(0, need.indexOf(':')), need.split(':')[1], need.split(':').slice(2).join(':')];
      let state = await stateAt(root);
      if (!state) {
        for (const u of units) await sync(u);
        state = await stateAt(root);
      }
      if (!state) throw new Error(`no MerkleMap with root ${root} in the chain history of ${units.join(', ') || 'the call'}`);
      const trie = await trieOf(state);
      const proofData = async (key, present) => decodeData((await trie.prove(buf(key), !present)).toCBOR().toString('hex'));
      if (kind === 'read') {
        const value = state.get(rest);
        return dataHex(Data.constr(0, [entryData(rest, value), await proofData(rest, value !== undefined)]));
      }
      if (kind !== 'writes') throw new Error('unknown map need ' + need);
      // Each proof against the root the previous write led to: applied in order to a copy of the map.
      const items = [];
      const scratch = new Map(state);
      for (const w of rest.split(',')) {
        const [key, v] = w.split('=');
        const before = scratch.get(key);
        items.push(Data.constr(0, [entryData(key, before), await proofData(key, before !== undefined)]));
        if (before !== undefined) await trie.delete(buf(key));
        if (v !== '-') await trie.insert(buf(key), buf(v));
        if (v === '-') scratch.delete(key);
        else scratch.set(key, v);
      }
      return dataHex(Data.list(items));
    },

    /** Records the map states a prepared transaction leads to (its claims), so the next call finds them. */
    async stage(txHex) {
      const claims = [];
      for (const r of parseTx(txHex).redeemers) if (r.tag === 'reward') claims.push(...(claimsOf(r.data) ?? []));
      return apply(claims);
    },

    sync,
  };
}
