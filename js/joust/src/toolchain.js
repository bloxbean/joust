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
 * The default stack of the engine's build worker, in MB, and its floor (ADR-0018 J18, r12; review r1 F2, S4). Node's
 * main thread has about 1 MB, on which a cold engine stopped at about 250 else-if branches in a contract (JuLC's passes
 * recurse on the compiled term); ADR-0021 r3 measured 2,000 nesting levels at 4 MB and at least 4,000 at 8 MB.
 * JOUST_ENGINE_STACK_MB overrides it (engineLimits), never below 8.
 */
export const ENGINE_STACK_MB = 8;
/** The default heap limit of the build worker, in MB (JOUST_ENGINE_MEMORY_MB). */
export const ENGINE_MEMORY_MB = 4096;
/** The default time limit of one build, in seconds (JOUST_ENGINE_TIMEOUT_S). */
export const ENGINE_TIMEOUT_S = 600;

/**
 * Each limit: its variable, its key in `limits`, its default and its range (#74; #80 review S4, F3). The stack floor is
 * J18's 8 MB; the heap floor is one the engine builds a small contract in (it fails at 64 MB).
 */
export const ENGINE_LIMITS = [
  { env: 'JOUST_ENGINE_STACK_MB', key: 'stackMb', fallback: ENGINE_STACK_MB, min: 8, max: 1024 },
  { env: 'JOUST_ENGINE_MEMORY_MB', key: 'memoryMb', fallback: ENGINE_MEMORY_MB, min: 256, max: 65536 },
  { env: 'JOUST_ENGINE_TIMEOUT_S', key: 'timeoutS', fallback: ENGINE_TIMEOUT_S, min: 1, max: 86400 },
];

/**
 * The build worker's limits (#74, security S7 on #71; #80 review S4): JOUST_ENGINE_STACK_MB (8-1024),
 * JOUST_ENGINE_MEMORY_MB (256-65536) and JOUST_ENGINE_TIMEOUT_S (1-86400) from `env`, each a whole number (unset or
 * empty: the default), then `overrides` ({ stackMb, memoryMb, timeoutS }, a caller's), checked against the same ranges.
 * Anything else (abc, 0, -5, 1.5, out of range) is refused with its name, never ignored. An invalid environment
 * variable is refused even when `overrides` replaces it.
 */
export function engineLimits(env = process.env, overrides = {}) {
  const check = (name, v, { fallback, min, max }) => {
    if (v === undefined || v === '') return fallback;
    const s = String(v).trim();
    const n = /^[0-9]+$/.test(s) ? Number(s) : NaN;
    if (!Number.isSafeInteger(n) || n < min || n > max) {
      throw new Error(`${name}=${JSON.stringify(v)} is not a whole number from ${min} to ${max} (unset it for the default, ${fallback})`);
    }
    return n;
  };
  const out = {};
  for (const l of ENGINE_LIMITS) out[l.key] = check(l.env, env[l.env], l);
  for (const l of ENGINE_LIMITS) {
    if (overrides?.[l.key] !== undefined) out[l.key] = check(`limits.${l.key}`, overrides[l.key], l);
  }
  return out;
}

/** The Node heap flags this process passes to its workers (execArgv, NODE_OPTIONS), which override resourceLimits. */
export function inheritedHeapFlags(execArgv = process.execArgv, nodeOptions = process.env.NODE_OPTIONS ?? '') {
  const flag = /^--max[-_](old|semi)[-_]space[-_]size(=|$)|^--max[-_]heap[-_]size(=|$)/;
  return [...execArgv, ...nodeOptions.split(/\s+/)].filter((a) => flag.test(a));
}

/**
 * Why a worker whose heap limit is `heapLimitMb` does not have the configured `memoryMb`, or null. V8 adds the young
 * generation (48 MB on Node 22) to the old generation's limit; an inherited --max-old-space-size replaces both
 * (#80 review C2). Allowed: memoryMb up to memoryMb + 128.
 */
export function heapProblem(heapLimitMb, memoryMb, flags = inheritedHeapFlags()) {
  if (heapLimitMb >= memoryMb && heapLimitMb <= memoryMb + 128) return null;
  return `the toolchain engine's worker has a heap limit of ${Math.round(heapLimitMb)} MB, not the configured`
    + ` JOUST_ENGINE_MEMORY_MB=${memoryMb}` + (flags.length ? `: the inherited Node flag ${flags.join(' ')} (execArgv or NODE_OPTIONS)`
    + ' overrides it; remove the flag, or set JOUST_ENGINE_MEMORY_MB to its value' : '');
}

let current = null;
let seq = 0;
/** Every worker started and not yet exited. */
const live = new Set();

/** How many build workers are running (none left behind: #80 review S4). */
export function liveWorkers() {
  return live.size;
}

/** A worker with these limits; its builds; what ends them. */
function spawn(limits) {
  const pending = new Map();
  const worker = new Worker(new URL('./toolchain-worker.js', import.meta.url),
    { resourceLimits: { stackSizeMb: limits.stackMb, maxOldGenerationSizeMb: limits.memoryMb } });
  const w = { worker, pending, key: `${limits.stackMb}/${limits.memoryMb}`, dead: false, retiring: false };
  live.add(worker);
  worker.once('exit', () => live.delete(worker));
  const failAll = (e) => {
    if (w.dead) return;
    w.dead = true;
    if (current === w) current = null;
    clearTimeout(startup);
    for (const p of pending.values()) {
      clearTimeout(p.timer);
      p.reject(e);
    }
    pending.clear();
    worker.terminate();
  };
  w.failAll = failAll;
  // The worker reports its effective heap limit first: a build never runs under another limit than the configured one.
  let ready;
  w.ready = new Promise((resolve, reject) => { ready = { resolve, reject }; });
  w.ready.catch(() => {});
  const startup = setTimeout(() => failAll(new Error(`the toolchain engine's worker did not start within ${limits.timeoutS} s`)),
    limits.timeoutS * 1000);
  startup.unref();
  worker.unref();
  worker.on('message', (m) => {
    if (m.ready) {
      clearTimeout(startup);
      const why = heapProblem(m.heapLimitMb, limits.memoryMb);
      if (why) {
        ready.reject(new Error(why));
        failAll(new Error(why));
      } else ready.resolve();
      return;
    }
    const p = pending.get(m.id);
    if (!p) return;
    if (m.started) {
      // The time limit counts this build's run, from when the worker takes it, not its wait behind another (F3).
      p.timer = setTimeout(() => failAll(new Error(`the toolchain engine did not finish within ${p.timeoutS} s`
        + ' (JOUST_ENGINE_TIMEOUT_S): its worker was stopped')), p.timeoutS * 1000);
      p.timer.unref();
      return;
    }
    settle(w, m.id);
    if (m.error) p.reject(new Error(m.error));
    else p.resolve(m.result);
  });
  // Its heap limit reached (ERR_WORKER_OUT_OF_MEMORY) or any other failure: every pending build fails, never the process.
  worker.on('error', (e) => failAll(new Error(`the toolchain engine's worker failed: ${e?.message ?? e}`
    + (e?.code === 'ERR_WORKER_OUT_OF_MEMORY' ? ` (JOUST_ENGINE_MEMORY_MB=${limits.memoryMb})` : ''))));
  worker.on('exit', (code) => failAll(new Error(`the toolchain engine's worker exited (code ${code})`)));
  return w;
}

/** A build of `w` is over: forget it; an idle worker no longer holds the process, and a replaced one is stopped. */
function settle(w, id) {
  const p = w.pending.get(id);
  if (p) clearTimeout(p.timer);
  w.pending.delete(id);
  if (w.pending.size) return;
  if (w.retiring) {
    w.dead = true;
    w.worker.terminate();
  } else w.worker.unref();
}

/**
 * {@code engine.joust.build({ files, options })}, run in a worker with the engine's limits (engineLimits(), with
 * `limits` overriding the environment's, in the same ranges): a stack of stackMb (8 MB by default), so a cold engine
 * builds what the JVM builds; a heap of memoryMb, checked in the worker before any build runs; and timeoutS for each
 * build from when the worker starts it, after which the worker is stopped and its builds rejected. One worker per
 * process: a build with other stack or heap limits replaces it, and the replaced one is stopped once its builds settle.
 * A failure of the engine (its stack or heap exhausted, the time limit) is a rejected promise, never a crash. Invalid
 * limits throw before any build.
 */
export function buildInWorker({ files, options = {}, dir, limits } = {}) {
  const l = engineLimits(process.env, limits);
  const key = `${l.stackMb}/${l.memoryMb}`;
  if (!current || current.dead || current.key !== key) {
    if (current && !current.dead) {
      // Replaced while it may still run a build (#80 review S4): stopped once that build settles, never left behind.
      current.retiring = true;
      if (!current.pending.size) {
        current.dead = true;
        current.worker.terminate();
      }
    }
    current = spawn(l);
  }
  const w = current;
  const id = ++seq;
  return new Promise((resolve, reject) => {
    w.pending.set(id, { resolve, reject, timer: null, timeoutS: l.timeoutS });
    w.worker.ref();
    w.ready.then(() => {
      if (!w.pending.has(id)) return;
      try {
        w.worker.postMessage({ id, files, options, dir });
      } catch (e) {
        // Not sent (files that cannot be cloned, say): the build fails, and a worker left with nothing to do is stopped
        // rather than kept alive by its reference until a timer (#80 review S4).
        settle(w, id);
        if (!w.pending.size && !w.dead) {
          w.dead = true;
          if (current === w) current = null;
          w.worker.terminate();
        }
        reject(e);
      }
    }, () => {});
  });
}
