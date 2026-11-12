// The toolchain engine (ADR-0015 D1, D2): JOUST's processor and JuLC's compiler in Wasm (GraalVM Web Image). It
// compiles .java and .jsc contracts exactly as the Gradle build does (I1), with no JVM.
import { fileURLToPath } from 'node:url';
import { Worker } from 'node:worker_threads';
import { loadImage } from '@julclang/joust-runtime/load';

let loading;

export function loadToolchain({ dir } = {}) {
  if (!loading) {
    const where = dir ?? fileURLToPath(new URL('../engine/', import.meta.url));
    loading = loadImage(where.replace(/\/?$/, '/') + 'joust-toolchain.js');
  }
  return loading;
}

/**
 * The stack of the engine's build worker, in MB (ADR-0018 J18, r12; review r1 F2, S4). Node's main thread has about
 * 1 MB, on which a cold engine stopped at about 250 else-if branches in a contract (JuLC's passes recurse on the
 * compiled term); ADR-0021 r3 measured 2,000 nesting levels at 4 MB and at least 4,000 at 8 MB. JOUST_ENGINE_STACK_MB
 * overrides it.
 */
export const ENGINE_STACK_MB = Number(process.env.JOUST_ENGINE_STACK_MB ?? 8);

let worker;
let seq = 0;
const pending = new Map();

/**
 * {@code engine.joust.build({ files, options })}, run in a worker with ENGINE_STACK_MB of stack, so a cold engine builds
 * what the JVM builds; one worker per process, kept for later builds (joust dev). A failure of the engine (its stack
 * exhausted past the front end) is a rejected promise, never a crash.
 */
export function buildInWorker({ files, options = {}, dir } = {}) {
  if (!worker) {
    worker = new Worker(new URL('./toolchain-worker.js', import.meta.url), { resourceLimits: { stackSizeMb: ENGINE_STACK_MB } });
    worker.unref();
    worker.on('message', ({ id, result, error }) => {
      const p = pending.get(id);
      pending.delete(id);
      if (!pending.size) worker.unref();
      if (error) p.reject(new Error(error));
      else p.resolve(result);
    });
    worker.on('error', (e) => {
      for (const p of pending.values()) p.reject(e);
      pending.clear();
      worker = null;
    });
  }
  const id = ++seq;
  worker.ref();
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    worker.postMessage({ id, files, options, dir });
  });
}
