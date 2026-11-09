// The page's side of the engine worker (./worker.js): a prepare service, as httpService gives one, so a generated
// client (JoustContract) verifies, has the wallet sign and submits exactly as with a service over HTTP. This module has
// no dependencies: the page's bundle does not grow with the engine, which loads in the worker.

/**
 * A prepare service backed by the engine in {@code worker}.
 *
 * @param worker   a Worker running @julclang/joust-runtime/worker (a module worker)
 * @param options  what the worker's runtime needs: { engine: { url, integrity }, contracts, chain, clock, networkId,
 *                 ttlMs, deployments, validators?, projectId?, mirrorCache? } (see worker.js); `virtual:joust/network`
 *                 (@julclang/joust/vite) gives them all. URLs are relative to the page.
 * @returns { ready, prepare(request), mapValue(root, key, units), deployments(d), terminate() }: ready resolves
 *          once the engine has loaded
 */
export function engineWorker(worker, options) {
  let next = 0;
  let broken = null;
  const pending = new Map();
  const fail = (error) => {
    broken = error;
    for (const p of pending.values()) p.reject(error);
    pending.clear();
  };
  // The worker announces when it listens (worker.js): messages posted before that would be lost.
  let listening;
  const heard = new Promise((resolve) => { listening = resolve; });
  worker.addEventListener('message', ({ data }) => {
    if (data?.joust === 'listening') return listening();
    const p = pending.get(data?.id);
    if (!p) return;
    pending.delete(data.id);
    if (data.ok) p.resolve(data.value);
    else p.reject(new Error(data.error));
  });
  // A worker that cannot start (its module fails to load, or it runs out of memory) fires error, not a message.
  worker.addEventListener('error', (e) => {
    e.preventDefault?.();
    fail(new Error(`the engine worker failed: ${e.message || 'it could not start'}`));
  });
  const call = (op, args) => new Promise((resolve, reject) => {
    if (broken) return reject(broken);
    const id = ++next;
    pending.set(id, { resolve, reject });
    heard.then(() => worker.postMessage({ id, op, args }));
  });
  // URLs relative to the page, not to the worker's script (which a bundler puts elsewhere).
  const at = (u) => new URL(u, globalThis.location?.href).href;
  const engine = typeof options.engine === 'string' ? { url: options.engine } : options.engine;
  const ready = call('init', { ...options, chain: at(options.chain),
    engine: engine?.url ? { ...engine, url: at(engine.url) } : engine });
  ready.catch(() => {});   // reported through prepare; a page that never calls one does not see an unhandled rejection
  return {
    ready,
    async prepare(request) {
      await ready;
      return call('prepare', request);
    },
    async deployments(d) {
      await ready;
      return call('deployments', d);
    },
    /**
     * A MerkleMap value from the worker's mirror: hex, or null when {@code key} is absent. {@code units}: the instance's
     * token (policy + instance id), whose chain history rebuilds a map the mirror has not seen.
     */
    async mapValue(root, key, units = []) {
      await ready;
      return call('mapValue', { root, key, units });
    },
    terminate() {
      fail(new Error('the engine worker was terminated'));
      worker.terminate();
    },
  };
}
