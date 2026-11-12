// The toolchain engine's worker (ADR-0018 J18, r12; review r1 F2, S4): `joust build` runs the engine on a thread whose
// stack it sets (toolchain.js: ENGINE_STACK_MB), not on Node's main thread (about 1 MB), so a cold engine compiles what
// the JVM compiles. JuLC's own compiler passes recurse on the compiled term (bloxbean/julc#327):
// on 1 MB a cold engine stopped at about 250 else-if branches in a contract.
import { parentPort } from 'node:worker_threads';
import { loadToolchain } from './toolchain.js';

const plain = (r) => JSON.parse(JSON.stringify(r, (k, v) => (typeof v === 'bigint' ? Number(v) : v)));

parentPort.on('message', async ({ id, files, options, dir }) => {
  try {
    const engine = await loadToolchain({ dir });
    parentPort.postMessage({ id, result: plain(engine.joust.build({ files, options })) });
  } catch (e) {
    parentPort.postMessage({ id, error: String(e?.message ?? e) });
  }
});
