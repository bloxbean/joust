// The toolchain engine (ADR-0015 D1, D2): JOUST's processor and JuLC's compiler in Wasm (GraalVM Web Image). It
// compiles .java and .jsc contracts exactly as the Gradle build does (I1), with no JVM.
import { fileURLToPath } from 'node:url';
import { Worker } from 'node:worker_threads';
import { loadImage } from '@julclang/joust-runtime/load';

let loading;

/**
 * The toolchain engine, loaded once per thread: `engine.joust.build({ files, options })` runs on the CALLER's thread.
 * JuLC's compiler passes recurse on the compiled term (bloxbean/julc#327), so a large contract needs a deep stack:
 * Node's main thread has about 1 MB, on which a cold engine fails at about 300 else-if branches. Build on a thread with
 * at least 8 MB of stack (ENGINE_STACK_MB): call buildInWorker, which does, or run this in a Worker whose
 * `resourceLimits.stackSizeMb` is at least 8 (issue #74, design F8).
 */
export function loadToolchain({ dir } = {}) {
  if (!loading) {
    const where = dir ?? fileURLToPath(new URL('../engine/', import.meta.url));
    loading = loadImage(where.replace(/\/?$/, '/') + 'joust-toolchain.js');
  }
  return loading;
}

/**
 * The default stack of the engine's build worker, in MB (ADR-0018 J18, r12; review r1 F2, S4). Node's main thread has
 * about 1 MB, on which a cold engine stopped at about 250 else-if branches in a contract (JuLC's passes recurse on the
 * compiled term); ADR-0021 r3 measured 2,000 nesting levels at 4 MB and at least 4,000 at 8 MB. JOUST_ENGINE_STACK_MB
 * overrides it (engineLimits).
 */
export const ENGINE_STACK_MB = 8;
/** The default heap limit of the build worker, in MB (JOUST_ENGINE_MEMORY_MB). */
export const ENGINE_MEMORY_MB = 4096;
/** The default time limit of one build, in seconds (JOUST_ENGINE_TIMEOUT_S). */
export const ENGINE_TIMEOUT_S = 600;

/**
 * The build worker's limits from the environment (issue #74, security S7 on #71): JOUST_ENGINE_STACK_MB (1-1024),
 * JOUST_ENGINE_MEMORY_MB (64-65536) and JOUST_ENGINE_TIMEOUT_S (1-86400), each a whole number; unset means the default.
 * Anything else (abc, 0, -5, 1.5) is refused with the variable's name, never ignored.
 */
export function engineLimits(env = process.env) {
  const read = (name, fallback, min, max) => {
    const v = env[name];
    if (v === undefined || v === '') return fallback;
    const s = String(v).trim();
    const n = /^[0-9]+$/.test(s) ? Number(s) : NaN;
    if (!Number.isSafeInteger(n) || n < min || n > max) {
      throw new Error(`${name}=${JSON.stringify(v)} is not a whole number from ${min} to ${max} (unset it for the default, ${fallback})`);
    }
    return n;
  };
  return {
    stackMb: read('JOUST_ENGINE_STACK_MB', ENGINE_STACK_MB, 1, 1024),
    memoryMb: read('JOUST_ENGINE_MEMORY_MB', ENGINE_MEMORY_MB, 64, 65536),
    timeoutS: read('JOUST_ENGINE_TIMEOUT_S', ENGINE_TIMEOUT_S, 1, 86400),
  };
}

let current = null;
let seq = 0;

/** A worker with these resource limits, its pending builds, and what ends them. */
function spawn(limits) {
  const pending = new Map();
  const worker = new Worker(new URL('./toolchain-worker.js', import.meta.url),
    { resourceLimits: { stackSizeMb: limits.stackMb, maxOldGenerationSizeMb: limits.memoryMb } });
  const w = { worker, pending, key: `${limits.stackMb}/${limits.memoryMb}`, dead: false };
  const failAll = (e) => {
    w.dead = true;
    if (current === w) current = null;
    for (const p of pending.values()) {
      clearTimeout(p.timer);
      p.reject(e);
    }
    pending.clear();
  };
  worker.unref();
  worker.on('message', ({ id, result, error }) => {
    const p = pending.get(id);
    if (!p) return;
    pending.delete(id);
    clearTimeout(p.timer);
    if (!pending.size) worker.unref();
    if (error) p.reject(new Error(error));
    else p.resolve(result);
  });
  // Its heap limit reached (ERR_WORKER_OUT_OF_MEMORY) or any other failure: every pending build fails, never the process.
  worker.on('error', (e) => failAll(new Error(`the toolchain engine's worker failed: ${e?.message ?? e}`
    + (e?.code === 'ERR_WORKER_OUT_OF_MEMORY' ? ` (JOUST_ENGINE_MEMORY_MB=${limits.memoryMb})` : ''))));
  worker.on('exit', (code) => failAll(new Error(`the toolchain engine's worker exited (code ${code})`)));
  return w;
}

/**
 * {@code engine.joust.build({ files, options })}, run in a worker with the engine's limits (engineLimits(), or
 * `limits` to override them): a stack of stackMb (8 MB by default), so a cold engine builds what the JVM builds; a heap
 * of at most memoryMb; and timeoutS for each build, after which the worker is stopped and the promise rejected. One
 * worker per process and set of limits, kept for later builds (joust dev). A failure of the engine (its stack or heap
 * exhausted, the time limit) is a rejected promise, never a crash. Invalid limits throw before any build.
 */
export function buildInWorker({ files, options = {}, dir, limits } = {}) {
  const l = { ...engineLimits(), ...(limits ?? {}) };
  if (!current || current.dead || current.key !== `${l.stackMb}/${l.memoryMb}`) {
    if (current && !current.dead && current.pending.size === 0) current.worker.terminate();
    current = spawn(l);
  }
  const w = current;
  const id = ++seq;
  w.worker.ref();
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      // A build past its time: the engine cannot be interrupted inside, so its worker is stopped (the next build starts
      // a new one); every build on it fails.
      const e = new Error(`the toolchain engine did not finish within ${l.timeoutS} s (JOUST_ENGINE_TIMEOUT_S): its worker was stopped`);
      for (const p of w.pending.values()) {
        clearTimeout(p.timer);
        p.reject(e);
      }
      w.pending.clear();
      w.dead = true;
      if (current === w) current = null;
      w.worker.terminate();
    }, l.timeoutS * 1000);
    timer.unref();
    w.pending.set(id, { resolve, reject, timer });
    w.worker.postMessage({ id, files, options, dir });
  });
}
