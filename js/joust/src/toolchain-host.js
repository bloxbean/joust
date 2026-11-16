// The toolchain engine's host process (#80 review C7, C8, C12, F10): `joust build` forks it without the command line's
// Node flags and with NODE_OPTIONS stripped of its heap flags (toolchain.js hostEnv; preloads stay), so no inherited
// --max-old-space-size can set the worker's old generation, and
// runs the build worker inside it with the configured resourceLimits. Before any build it measures the worker's heap
// (toolchain-heap.js) and reports; then it relays builds both ways. When the worker exits, so does the host.
import { Worker } from 'node:worker_threads';
import { HEAP_PROBE_DELTA_MB, probeHeapLimit } from './toolchain-heap.js';

const limits = JSON.parse(process.argv[2]);
const send = (m) => {
  if (process.connected) process.send(m);
};
const worker = new Worker(new URL('./toolchain-worker.js', import.meta.url),
  { resourceLimits: { stackSizeMb: limits.stackMb, maxOldGenerationSizeMb: limits.memoryMb } });
worker.on('message', (m) => {
  if (m.ready) {
    // Both probes settle (report, fail or exit) before the host is ready; the parent's startup timer runs until then.
    Promise.all([probeHeapLimit(limits.memoryMb + HEAP_PROBE_DELTA_MB), probeHeapLimit(limits.memoryMb - HEAP_PROBE_DELTA_MB)])
      .then(([upMb, downMb]) => send({ ready: true, heapLimitMb: m.heapLimitMb, upMb, downMb }),
        (e) => send({ hostError: `the toolchain engine's heap probe failed: ${e?.message ?? e}` }));
    return;
  }
  send(m);
});
worker.on('error', (e) => send({ workerError: { message: String(e?.message ?? e), code: e?.code ?? null } }));
worker.on('exit', (code) => {
  // The worker's error (if any) and its exit reach the parent before the host exits.
  if (process.connected) process.send({ workerExit: code }, () => process.exit(code));
  else process.exit(code);
});
process.on('message', (m) => worker.postMessage(m));
process.on('disconnect', () => process.exit(0));
