// The toolchain engine's worker (ADR-0018 J18, r12; review r1 F2, S4): `joust build` runs the engine on a thread whose
// stack it sets (toolchain.js: ENGINE_STACK_MB), not on Node's main thread (about 1 MB), so a cold engine compiles what
// the JVM compiles. JuLC's own compiler passes recurse on the compiled term (bloxbean/julc#327):
// on 1 MB a cold engine stopped at about 250 else-if branches in a contract.
//
// On start it reports its effective heap limit, which toolchain.js checks against the configured one (#80 review C2:
// an inherited --max-old-space-size overrides resourceLimits). Builds run one at a time, in the order they arrive, and
// each says when it starts, so its time limit counts its own run, not its wait in the queue (#80 review F3).
import { parentPort } from 'node:worker_threads';
import { getHeapStatistics } from 'node:v8';
import { loadToolchain } from './toolchain.js';

const plain = (r) => JSON.parse(JSON.stringify(r, (k, v) => (typeof v === 'bigint' ? Number(v) : v)));

parentPort.postMessage({ ready: true, heapLimitMb: getHeapStatistics().heap_size_limit / 1048576 });

let queue = Promise.resolve();
parentPort.on('message', (m) => {
  queue = queue.then(() => run(m));
});

async function run({ id, files, options, dir, javaLibraries, packages }) {
  parentPort.postMessage({ id, started: true });
  try {
    const engine = await loadToolchain({ dir });
    // ADR-0018 D9, D14 (M4): the npm packages' libraries (.jsc, by <package>/<path>) and Java library sources.
    parentPort.postMessage({ id, result: plain(engine.joust.build({ files, options, javaLibraries: javaLibraries ?? {}, packages: packages ?? {} })) });
  } catch (e) {
    parentPort.postMessage({ id, error: String(e?.message ?? e) });
  }
}
