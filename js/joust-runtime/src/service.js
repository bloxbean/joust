// A prepare service backed by the runtime engine (ADR-0015 D4, D5, D7): the same interface as a JOUST prepare service
// over HTTP, so @julclang/state-client's JoustContract (and every generated client) verifies, has the wallet sign and
// submits exactly as before; only the builder moved into the page or the Node process.

import { loadEngine } from './engine.js';
import { emptySnapshot, fetchNeed } from './snapshot.js';
import { memoryMapStore } from './maps.js';
import { readFileSync } from 'node:fs';

/** This runtime's version: contracts built by an incompatible toolchain are refused (ADR-0015 I7). */
export const RUNTIME_VERSION = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version;

/** The part of a version that must match: the major, or major.minor while the major is 0 (semver's 0.x rule). */
const compatibility = (v) => {
  const [major, minor] = String(v).split('.');
  return major === '0' ? `0.${minor}` : major;
};

/** I7: a contract's description format and the toolchain that built it must be ones this runtime reads. */
export function checkCompatible(c) {
  const d = typeof c.description === 'string' ? JSON.parse(c.description) : c.description;
  const title = d?.preamble?.title ?? '(a contract)';
  const v = d?.['x-joust']?.version;
  if (v !== 2) throw new Error(`${title}: description version ${v}; this runtime reads version 2 (rebuild with joust build)`);
  const built = c.built?.joust;
  if (built && compatibility(built) !== compatibility(RUNTIME_VERSION)) {
    throw new Error(`${title}: built by JOUST ${built}; this runtime is ${RUNTIME_VERSION} (rebuild with a matching @julclang/joust)`);
  }
}

const big = (x) => BigInt(x);

/**
 * @param o.api        raw chain reads (blockfrostApi(url))
 * @param o.contracts  the contracts as `joust build` writes them: [{ description, script, transitions }]
 * @param o.clock      the chain's slot clock: { zeroSlot, zeroTimeMs, slotLengthMs }
 * @param o.networkId  0 (test networks) or 1 (mainnet)
 * @param o.ttlMs      how long a prepared transaction stays valid (default 120 s; a DevKit's horizon is minutes)
 * @param o.maxRounds  needs fetched before giving up (default 64)
 * @param o.mapStore   the MerkleMap mirror's nodes (memoryMapStore() by default; fileMapStore(path) to keep them)
 * @param o.prefetch   async (call, snapshot) => void: fills what the call will surely read before the first run (the
 *                     up-front needs of ADR-0015 D5); the engine still asks for anything missing
 */
export function engineService(o) {
  o.contracts.forEach(checkCompatible);
  const contracts = o.contracts.map((c) => ({
    description: typeof c.description === 'string' ? c.description : JSON.stringify(c.description),
    script: c.script, transitions: c.transitions,
  }));
  const rounds = [];
  const maps = o.mapStore ?? memoryMapStore();

  async function run(call) {
    const engine = await loadEngine(o.engine);
    const snapshot = emptySnapshot();
    if (o.prefetch) await o.prefetch(call, snapshot);
    for (let round = 1; round <= (o.maxRounds ?? 64); round++) {
      const r = engine.joust.prepare({
        call: JSON.stringify(call), snapshot: JSON.stringify(snapshot), contracts,
        zeroSlot: big(o.clock.zeroSlot), zeroTimeMs: big(o.clock.zeroTimeMs), slotLengthMs: big(o.clock.slotLengthMs),
        ttlMs: big(o.ttlMs ?? 120_000), networkId: o.networkId ?? 0, mapNodes: maps.all(),
      });
      if (r.ok) {
        rounds.push(round);
        maps.putAll(r.mapNodes);   // the mirror's new nodes (the next call's map roots)
        return r;
      }
      if (!r.needKind) throw new Error(r.error);
      if (r.needKind === 'mapNode') {
        throw new Error(`the MerkleMap mirror has no node ${r.needKey}: this app did not build that map's history`
          + ' (keep the map store; rebuilding a mirror from the chain comes later, ADR-0015 D5)');
      }
      if (process.env.JOUST_DEBUG_NEEDS) console.error(`[joust] round ${round}: needs ${r.needKind} ${r.needKey}`);
      await fetchNeed(o.api, snapshot, r.needKind, r.needKey);
    }
    throw new Error(`prepare needed more than ${o.maxRounds ?? 64} chain reads`);
  }

  return {
    /** As a prepare service: { cbor, txHash, validTo, signers, plan, instance | object }. */
    async prepare(request) {
      const r = await run(request);
      const out = { cbor: r.cbor, txHash: r.txHash, validTo: Number(r.validTo), signers: r.signers, plan: r.plan ? JSON.parse(r.plan) : null };
      if (request.kind === 'init' && r.created) out.instance = r.created;
      if (request.kind === 'ownedInit' && r.created) out.object = r.created;
      return out;
    },

    /** What a page pins for {@code contract}: { contract, policy, address, networkId, referenceScripts }. */
    async deployment(contract, address, params) {
      const r = await run({ contract, kind: 'deployment', address, ...(params ? { params } : {}) });
      return JSON.parse(r.plan);
    },

    /** The transaction publishing {@code contract}'s reference script, paid from {@code address}; null if published. */
    async publish(contract, address, params) {
      const r = await run({ contract, kind: 'publish', address, ...(params ? { params } : {}) });
      return r.cbor ? { cbor: r.cbor, txHash: r.txHash, signers: r.signers, plan: JSON.parse(r.plan) } : null;
    },

    /** How many engine runs each prepare took (needs fetched one at a time; ADR-0015 D5). */
    rounds,
  };
}
