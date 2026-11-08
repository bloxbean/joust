// A prepare service backed by the runtime engine (ADR-0015 D4, D5, D7): the same interface as a JOUST prepare service
// over HTTP, so @julclang/state-client's JoustContract (and every generated client) verifies, has the wallet sign and
// submits exactly as before; only the builder moved into the page or the Node process.

import { loadEngine } from './engine.js';
import { emptySnapshot, fetchNeed } from './snapshot.js';

const big = (x) => BigInt(x);

/**
 * @param o.api        raw chain reads (blockfrostApi(url))
 * @param o.contracts  the contracts as `joust build` writes them: [{ description, script, transitions }]
 * @param o.clock      the chain's slot clock: { zeroSlot, zeroTimeMs, slotLengthMs }
 * @param o.networkId  0 (test networks) or 1 (mainnet)
 * @param o.ttlMs      how long a prepared transaction stays valid (default 120 s; a DevKit's horizon is minutes)
 * @param o.maxRounds  needs fetched before giving up (default 64)
 */
export function engineService(o) {
  const contracts = o.contracts.map((c) => ({
    description: typeof c.description === 'string' ? c.description : JSON.stringify(c.description),
    script: c.script, transitions: c.transitions,
  }));
  const rounds = [];

  async function run(call) {
    const engine = await loadEngine(o.engine);
    const snapshot = emptySnapshot();
    for (let round = 1; round <= (o.maxRounds ?? 64); round++) {
      const r = engine.joust.prepare({
        call: JSON.stringify(call), snapshot: JSON.stringify(snapshot), contracts,
        zeroSlot: big(o.clock.zeroSlot), zeroTimeMs: big(o.clock.zeroTimeMs), slotLengthMs: big(o.clock.slotLengthMs),
        ttlMs: big(o.ttlMs ?? 120_000), networkId: o.networkId ?? 0,
      });
      if (r.ok) {
        rounds.push(round);
        return r;
      }
      if (!r.needKind) throw new Error(r.error);
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
    async deployment(contract, address) {
      const r = await run({ contract, kind: 'deployment', address });
      return JSON.parse(r.plan);
    },

    /** The transaction publishing {@code contract}'s reference script, paid from {@code address}; null if published. */
    async publish(contract, address) {
      const r = await run({ contract, kind: 'publish', address });
      return r.cbor ? { cbor: r.cbor, txHash: r.txHash, signers: r.signers, plan: JSON.parse(r.plan) } : null;
    },

    /** How many engine runs each prepare took (needs fetched one at a time; ADR-0015 D5). */
    rounds,
  };
}
