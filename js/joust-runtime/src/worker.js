// The runtime engine in a page's Web Worker (ADR-0015 M4). Importing this module in a worker makes the worker serve
// engineWorker (./page.js): the page prepares its own transactions, with no prepare service. The worker holds no keys
// and is not trusted either: the page verifies every transaction it returns before the wallet signs (D7).
//
// In the app (any bundler that understands new URL(..., import.meta.url)):
//   page/engine.worker.js:  import '@julclang/joust-runtime/worker';
//   page/main.js:           const service = engineWorker(new Worker(new URL('./engine.worker.js', import.meta.url),
//                             { type: 'module' }), { engine, contracts, chain, clock, networkId, deployments });
//
// MerkleMap mirrors are kept in IndexedDB (levelMirrorCache, through `level`'s browser build): a reload, or the next
// visit, does not rebuild a map from the chain's history again.

import { blockfrostProvider } from '@julclang/state-client';
import { joustRuntime } from './index.js';
import { loadEngine } from '#engine';
import { mapMirror, levelMirrorCache } from './mirror.js';

let runtime = null;

const ops = {
  /**
   * @param o.engine       where joust-runtime.js is served (its .wasm beside it)
   * @param o.contracts    contracts.json as joust build writes it
   * @param o.validators   validators.json (optional)
   * @param o.chain        a Blockfrost-style chain API, absolute or relative to the page (a proxy that keeps the
   *                       project id off the page, such as joust dev's /chain)
   * @param o.projectId    a Blockfrost project id, only for a chain API that needs one and may see it
   * @param o.clock        { zeroSlot, zeroTimeMs, slotLengthMs }
   * @param o.networkId    0 or 1
   * @param o.ttlMs        how long a prepared transaction stays valid
   * @param o.deployments  { [contract]: deployment }
   * @param o.mirrorCache  the IndexedDB database for MerkleMap mirrors (default "joust-mirrors"); false: in memory
   */
  async init(o) {
    const engine = { url: new URL(o.engine, self.location.href).href };
    await loadEngine(engine);   // started now: the first call does not wait for 20 MB of Wasm
    const provider = blockfrostProvider(new URL(o.chain, self.location.href).href, { projectId: o.projectId });
    let cache;
    if (o.mirrorCache !== false) {
      try {
        cache = await levelMirrorCache(o.mirrorCache ?? 'joust-mirrors');
      } catch (e) {
        console.warn(`[joust] no IndexedDB for MerkleMap mirrors (${e.message ?? e}): keeping them in memory`);
      }
    }
    runtime = joustRuntime({ provider, contracts: o.contracts, validators: o.validators, clock: o.clock,
      networkId: o.networkId ?? 0, ttlMs: o.ttlMs, deployments: o.deployments, engine,
      mapMirror: mapMirror({ provider, cache }) });
    return { ready: true };
  },

  /** As a prepare service (httpService's prepare): { cbor, txHash, validTo, signers, plan, instance | object }. */
  prepare(request) {
    if (!runtime) throw new Error('the engine worker is not initialised');
    return runtime.service.prepare(request);
  },

  /** New deployments (after a redeploy), for the calls that follow. */
  deployments(d) {
    if (!runtime) throw new Error('the engine worker is not initialised');
    Object.assign(runtime.deployments, d);
    return { ok: true };
  },
};

if (typeof WorkerGlobalScope !== 'undefined' && self instanceof WorkerGlobalScope) {
  self.addEventListener('message', async ({ data }) => {
    const { id, op, args } = data ?? {};
    if (!ops[op]) return self.postMessage({ id, ok: false, error: `no operation ${op}` });
    try {
      self.postMessage({ id, ok: true, value: await ops[op](args) });
    } catch (e) {
      self.postMessage({ id, ok: false, error: String(e?.message ?? e) });
    }
  });
  // A module worker starts taking messages before a module with top-level await (libsodium's, through
  // @julclang/state-client) has run this one: a message posted then is lost. So the page waits for this announcement.
  self.postMessage({ joust: 'listening' });
}
