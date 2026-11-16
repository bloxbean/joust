// The build worker's heap, measured (#80 reviews C2, F5, C7, C8, C10). Used by the engine host (toolchain-host.js),
// which runs in a process of its own whose Node flags JOUST controls, and by the tests.
import { Worker } from 'node:worker_threads';

/** How much each probe worker's old generation differs from the build worker's. */
export const HEAP_PROBE_DELTA_MB = 64;

const REPORT = "require('node:worker_threads').parentPort.postMessage(require('node:v8').getHeapStatistics().heap_size_limit / 1048576)";

/**
 * The heap_size_limit, in MB, of a worker whose old generation is `oldMb`: it reports and exits. Rejects when the
 * probe fails or exits before it reports (#80 review C10); `code` replaces the probe's own, for tests.
 */
export function probeHeapLimit(oldMb, { code = REPORT } = {}) {
  return new Promise((resolve, reject) => {
    const w = new Worker(code, { eval: true, resourceLimits: { maxOldGenerationSizeMb: oldMb } });
    let answered = false;
    w.once('message', (v) => {
      answered = true;
      resolve(v);
      w.terminate();
    });
    w.once('error', reject);
    w.once('exit', (c) => {
      if (!answered) reject(new Error(`the heap probe exited (code ${c}) before it reported`));
    });
  });
}

/**
 * Why the build worker's old generation is not the configured `memoryMb`, or null; decided from measurements only, never
 * from flag text (#80 review C7, C8). The build worker (old generation memoryMb) and two probes (memoryMb + 64 and
 * memoryMb - 64) each report V8's heap_size_limit. Where resourceLimits holds, they step by exactly 64 MB, whatever the
 * young generation; where something else sets the old generation, they don't (an inherited --max-old-space-size makes
 * all three equal, whatever its value).
 */
export function heapProblem(heapLimitMb, upMb, downMb, memoryMb) {
  const step = (a, b) => Math.abs(a - b - HEAP_PROBE_DELTA_MB) <= 1;
  if (step(upMb, heapLimitMb) && step(heapLimitMb, downMb)) return null;
  return `the toolchain engine's worker does not have the configured heap (JOUST_ENGINE_MEMORY_MB=${memoryMb}): its old`
    + ` generation is set elsewhere (measured ${Math.round(downMb)}, ${Math.round(heapLimitMb)} and ${Math.round(upMb)} MB at`
    + ` ${memoryMb - HEAP_PROBE_DELTA_MB}, ${memoryMb} and ${memoryMb + HEAP_PROBE_DELTA_MB} MB)`;
}
