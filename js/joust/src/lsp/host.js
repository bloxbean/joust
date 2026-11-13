// ADR-0021 D2a: engine hosting. Every engine runs in its own worker_thread; the protocol thread holds none.
//   - resource limits on every engine worker: stackSizeMb 4 and maxOldGenerationSizeMb 256 (an analysis worker; the
//     image uses WasmGC, so Java objects live in V8's heap);
//   - a watchdog per call (2 s for analysis), which terminates the worker; a restart is always a new worker;
//   - cancellation is not failure: a call cancelled and still running after a grace period (1 s) ends its worker, which
//     counts toward no limit and quarantines nothing;
//   - quarantine: an input (SHA-256 of the contents + the engine) whose analysis timed out, ran out of memory, crashed the
//     worker or failed inside the engine is not analysed again; the next edit waits a backoff (1 s, doubling to 30 s
//     while failures repeat);
//   - restart limits: three worker failures (cancellations excluded) within five minutes for one key make it
//     "analysis unavailable" until restart();
//   - at most two analysis workers at once (least recently used stopped first), each stopped after 10 minutes idle.
//
// Why stackSizeMb 4 (and not the 8 MB of joust build's worker): the analysis runs only the front end, whose nesting cap
// (ADR-0018 J18, M1e) keeps its depth fixed; julc-state-toolchain/src/test/js/lang-in-node.mjs analyses every construct at
// the cap on a cold engine on a 0.5 MB worker stack, 8 times below this one, and on Node's main thread. M1e's 8 MB
// finding is about JuLC's compiler passes in a build (bloxbean/julc#327), which an analysis worker never runs; a build
// worker (M5b) takes joust build's 8 MB.
import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { dirname, resolve, sep } from 'node:path';
import { Worker } from 'node:worker_threads';

export const ANALYSIS_STACK_MB = 4;
export const ANALYSIS_HEAP_MB = 256;

export const DEFAULTS = {
  stackSizeMb: ANALYSIS_STACK_MB,
  maxOldGenerationSizeMb: ANALYSIS_HEAP_MB,
  analysisMs: 2000,
  loadMs: 30000,
  graceMs: 1000,
  backoffMs: 1000,
  backoffMaxMs: 30000,
  maxFailures: 3,
  failureWindowMs: 5 * 60 * 1000,
  maxWorkers: 2,
  idleMs: 10 * 60 * 1000,
};

export const sha256 = (s) => createHash('sha256').update(s).digest('hex');

/**
 * Whether a failed load is the engine's own files missing (a store copy evicted or not yet republished), from the failing
 * request itself (reviews C18, C22, S12 on #83): `MODULE_NOT_FOUND` whose REQUESTED module is the loader (the first line
 * of Node's message, `Cannot find module '<request>'`; never the "Require stack" after it, which names the engine when a
 * dependency of it is missing), or `ENOENT` whose path is exactly the loader or its .wasm. Nothing else, and never the
 * path's existence when the failure arrives: a missing dependency, or any other error, is an engine fault.
 */
export function isMissingEngine(r, engine) {
  if (r.ready) return false;
  // C29 on #83: the worker resolves the loader's own request before loading it; its failure is the structural answer.
  if (r.rootMissing === true && r.code === 'MODULE_NOT_FOUND') return true;
  const files = [engine.file, `${engine.file}.wasm`];
  if (r.code === 'MODULE_NOT_FOUND') {
    // Otherwise (the loader vanished between resolving and loading it): Node's exact header for the loader's request,
    // compared whole and followed by the end of the message or by the require stack. A path may hold quotes, spaces
    // and newlines (C26, C29); nothing is split or parsed out of it.
    const msg = String(r.error ?? '');
    return files.some((f) => {
      const header = `Cannot find module '${f}'`;
      return msg === header || msg.startsWith(`${header}\nRequire stack:`);
    });
  }
  if (r.code === 'ENOENT' || r.code === 'ENOTDIR') {
    if (files.includes(r.path)) return true;
    // S13 on #83: require lstats each directory on the way to the loader; a copy renamed aside fails there, on the
    // copy's own directory. A directory between the store's root (or, outside a store, the loader's directory) and the
    // loader counts too.
    const top = resolve(engine.storeRoot ?? dirname(engine.file));
    const p = typeof r.path === 'string' ? resolve(r.path) : null;
    return !!p && engine.file.startsWith(p + sep) && (p === top || p.startsWith(top + sep));
  }
  return false;
}

/** One engine worker: load, then one call at a time (the protocol thread serialises per key). */
class EngineWorker {
  constructor(engine, opts) {
    this.engine = engine;
    this.opts = opts;
    this.pending = new Map();
    this.seq = 0;
    this.dead = false;
    this.calls = 0;
    this.worker = new Worker(opts.workerEntry, {
      workerData: { role: 'joust-engine', engineFile: engine.file },
      resourceLimits: { stackSizeMb: opts.stackSizeMb, maxOldGenerationSizeMb: opts.maxOldGenerationSizeMb },
      // The engine prints nothing the protocol may see: stdout is the JSON-RPC channel of a stdio server.
      stdout: true,
      stderr: true,
    });
    this.worker.stdout.resume();
    this.worker.stderr.resume();
    this.ready = new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.kill('load-timeout');
        resolve({ ready: false, error: 'the engine did not load in time' });
      }, opts.loadMs);
      this.worker.once('message', (m) => {
        this.booted = true;
        clearTimeout(timer);
        resolve(m);
        this.worker.on('message', (r) => this.answer(r));
      });
      this.worker.once('error', (e) => {
        clearTimeout(timer);
        resolve({ ready: false, error: String(e?.message ?? e), code: e?.code, path: e?.path });
      });
    });
    this.worker.on('error', (e) => this.died(e?.code === 'ERR_WORKER_OUT_OF_MEMORY' ? 'memory' : 'crash', String(e?.message ?? e)));
    this.worker.on('exit', () => this.died('crash', 'the engine worker exited'));
  }

  answer(r) {
    const p = this.pending.get(r?.id);
    if (!p) return;
    this.pending.delete(r.id);
    clearTimeout(p.timer);
    p.resolve(r);
  }

  died(kind, message) {
    if (this.dead) return;
    this.dead = true;
    for (const p of this.pending.values()) {
      clearTimeout(p.timer);
      p.resolve({ failure: this.killedFor ?? kind, message: this.killedFor ? undefined : message });
    }
    this.pending.clear();
  }

  kill(reason) {
    if (this.dead) return;
    if (process.env.JOUST_LSP_DEBUG) process.stderr.write(`[joust-lsp] terminating the worker of ${this.engine.id.slice(0, 12)} (${reason}, booted ${!!this.booted})\n`);
    this.killedFor = reason;
    // A worker still booting is terminated once it has booted (or failed to): terminating Electron's worker while its
    // node_init runs ends the whole process with SIGTRAP (seen on VS Code 1.109's extension host). It is dead to its
    // callers at once either way.
    // A load that times out (30 s) is still terminated at once: deferring would keep a hung worker forever (§12 note 11).
    if (this.booted || reason === 'load-timeout') this.worker.terminate().catch(() => {});
    else this.ready.then(() => this.worker.terminate().catch(() => {}));
    this.died(reason);
  }

  /** Posts a call; resolves with the worker's answer, or `{ failure: 'time'|'memory'|'crash'|'cancelled' }`. */
  call(msg, timeoutMs, token) {
    if (this.dead) return Promise.resolve({ failure: this.killedFor ?? 'crash', message: 'the engine worker is gone' });
    const id = ++this.seq;
    this.calls++;
    return new Promise((resolve) => {
      const p = { resolve };
      p.timer = setTimeout(() => this.kill('time'), timeoutMs);
      this.pending.set(id, p);
      if (token) {
        const onCancel = () => setTimeout(() => {
          if (this.pending.has(id)) this.kill('cancelled');
        }, this.opts.graceMs);
        if (token.isCancellationRequested) onCancel();
        else token.onCancellationRequested?.(onCancel);
      }
      this.worker.postMessage({ ...msg, id });
    });
  }
}

/**
 * The engine workers of a server. A worker is keyed by compiler (`engine:<id>`, §12 note 11); the failures, backoff and
 * "analysis unavailable" state are kept per (worker key, scope), a scope being a project root (review S7 on #83): a
 * hostile file in one project stops analysis of that project only, while the projects on one compiler share its worker.
 */
export class EngineHost {
  constructor(opts) {
    this.opts = { ...DEFAULTS, ...opts };
    if (!this.opts.workerEntry) throw new Error('EngineHost needs workerEntry');
    this.slots = new Map();      // key -> { key, engine, worker, lastUsed, busy, queue }
    this.health = new Map();     // key|scope -> { failures: [], backoffUntil, backoffMs, unavailable }
    this.quarantine = new Map(); // sha256(source) + engine id -> the failure
    this.waiters = [];
    this.starting = 0;           // acquisitions holding a reserved place while they await their lease (C21)
    this.stats = { started: 0, terminated: 0, calls: 0, cancelled: 0, failures: 0, displaced: 0 };
    this.now = this.opts.now ?? (() => Date.now());
    this.idleTimer = setInterval(() => this.reapIdle(), Math.min(60_000, this.opts.idleMs)).unref();
  }

  slot(key, engine) {
    let s = this.slots.get(key);
    if (s && s.engine.id !== engine.id) {
      this.stop(key);
      s = undefined;
    }
    if (!s) {
      s = { key, engine, worker: null, lastUsed: this.now(), busy: 0, queue: Promise.resolve() };
      this.slots.set(key, s);
    }
    return s;
  }

  healthOf(key, scope = '') {
    const k = `${key}|${scope}`;
    let h = this.health.get(k);
    if (!h) {
      h = { failures: [], backoffUntil: 0, backoffMs: 0, unavailable: false };
      this.health.set(k, h);
    }
    return h;
  }

  /** Wakes every acquisition waiting for a worker to become idle. */
  wake() {
    const w = this.waiters;
    this.waiters = [];
    for (const r of w) r();
  }

  /**
   * The slot's worker, started if needed. At most maxWorkers are alive; to admit another, only an IDLE worker (booted,
   * no call running or queued for it) is stopped, the least recently used first; when every live worker is busy, the
   * acquisition waits for one to finish (review C3 on #83: a loading or busy worker is never evicted to admit another).
   * `{ missing: true }` when the engine's file is gone (a store copy evicted by another window): select again, no failure.
   */
  async acquire(s) {
    for (;;) {
      if (s.worker && !s.worker.dead) return s.worker;
      if (!existsSync(s.engine.file)) return { missing: true };
      const alive = [...this.slots.values()].filter((o) => o !== s && o.worker && !o.worker.dead);
      // C21 on #83: starts in progress (awaiting their lease) count against the bound too.
      if (alive.length + this.starting >= this.opts.maxWorkers) {
        const idle = alive.filter((o) => o.busy === 0 && o.worker.booted && o.worker.pending.size === 0).sort((a, b) => a.lastUsed - b.lastUsed);
        if (!idle.length || alive.length - 1 + this.starting >= this.opts.maxWorkers) {
          await new Promise((r) => this.waiters.push(r));
          continue;
        }
        this.stopWorker(idle[0]);
      }
      // C18: a lease on the engine's store copy, held until the worker has loaded it; the collector never removes a
      // leased copy. No lease (null): the copy is gone, select again.
      // The place is reserved before the await (C21): no other acquisition can take it meanwhile.
      this.starting++;
      let release;
      try {
        release = this.opts.lease ? await this.opts.lease(s.engine) : () => {};
      } finally {
        this.starting--;
      }
      if (!release) {
        this.wake();
        return { missing: true };
      }
      // C25 on #83: the slot may have been stopped (or the host disposed) while the lease was awaited; a start for a
      // slot no longer tracked would be an orphan no bound, reaper or dispose could see. Abandoned: lease released.
      if (this.disposed || s.stopped || this.slots.get(s.key) !== s) {
        release();
        this.wake();
        return { displaced: true };
      }
      if (s.worker && !s.worker.dead) {
        release();
        this.wake();
        return s.worker;
      }
      // The reservation ended at the await's end; with no await since, the place is still ours: start now.
      if (process.env.JOUST_LSP_DEBUG) process.stderr.write(`[joust-lsp] starting a worker for ${s.key}\n`);
      s.worker = new EngineWorker(s.engine, this.opts);
      s.worker.ready.then(() => {
        release();
        this.wake();
      });
      this.stats.started++;
      this.opts.onStart?.(s.engine);
      return s.worker;
    }
  }

  /** Loads the key's engine (if needed) and returns its `lang.info` (null for an engine without the lang entries). */
  async info(key, engine) {
    const s = this.slot(key, engine);
    s.busy++;
    try {
      const w = await this.acquire(s);
      if (w.missing) return { ready: false, missing: true, error: 'the engine file is missing' };
      if (w.displaced) return { ready: false, displaced: true, error: 'the start was abandoned (its worker slot was stopped)' };
      const r = await w.ready;
      if (isMissingEngine(r, engine)) return { ready: false, missing: true, error: 'the engine file is missing' };
      if (!r.ready) return { ready: false, error: r.error };
      return { ready: true, info: r.info, version: r.version };
    } finally {
      s.busy--;
      this.wake();
    }
  }

  status(key, scope = '') {
    const s = this.slots.get(key);
    const h = this.health.get(`${key}|${scope}`);
    if (h?.unavailable) return { state: 'unavailable', failures: h.failures.length };
    if (h && h.backoffUntil > this.now()) return { state: 'backoff', until: h.backoffUntil };
    return { state: s ? 'ok' : 'idle' };
  }

  /**
   * Analyses one file on the key's engine for `scope` (its project root): `{ model }` (the JSON text),
   * `{ internal: { kind, message } }` (the call failed: kind time, memory, crash, failed or too-large; the input is
   * quarantined), `{ cancelled: true }` (cancelled, or displaced by a stop: `displaced`), `{ unavailable: true }`,
   * `{ retryAt }` (in backoff) or `{ missing: true }` (the engine's file is gone).
   */
  analyze(key, engine, { file, source, scope = '' }, token) {
    const s = this.slot(key, engine);
    const h = this.healthOf(key, scope);
    const run = async () => {
      if (h.unavailable) return { unavailable: true };
      const q = this.quarantine.get(sha256(source) + '|' + engine.id);
      if (q) return { internal: q, quarantined: true };
      if (h.backoffUntil > this.now()) return { retryAt: h.backoffUntil };
      if (token?.isCancellationRequested) return { cancelled: true };
      s.lastUsed = this.now();
      s.busy++;
      try {
        const w = await this.acquire(s);
        if (w.missing) return { missing: true };
        if (w.displaced) return this.displaced();
        const ready = await w.ready;
        // A worker stopped while it loaded (Restart analysis, or evicted) is no engine fault: requeue, never quarantine.
        if (w.dead && (w.killedFor === 'stopped' || w.killedFor === 'cancelled')) return this.displaced();
        // A store copy removed while the worker loaded it (another window evicted it) is no engine fault: select again.
        if (isMissingEngine(ready, s.engine)) {
          this.stopWorker(s);
          return { missing: true };
        }
        if (!ready.ready) return this.failed(h, s, source, { kind: 'crash', message: `the engine did not load: ${ready.error}` }, true);
        this.stats.calls++;
        const r = await w.call({ op: 'analyze', file, source }, this.opts.analysisMs, token);
        if (r.failure === 'stopped') return this.displaced();
        if (r.failure === 'cancelled') {
          this.stats.cancelled++;
          this.stats.terminated++;
          return { cancelled: true };
        }
        if (r.failure) {
          if (r.failure === 'time') this.stats.terminated++;
          const message = r.failure === 'time' ? `analysis stopped: time limit (${this.opts.analysisMs} ms)`
            : r.failure === 'memory' ? `analysis stopped: memory limit (${this.opts.maxOldGenerationSizeMb} MB)`
              : `analysis stopped: the engine failed (${r.message ?? r.failure})`;
          return this.failed(h, s, source, { kind: r.failure, message }, true);
        }
        if (r.failed) return this.failed(h, s, source, { kind: 'failed', message: `analysis stopped: internal error (${r.failed})` }, false);
        if (r.tooLarge) return this.failed(h, s, source, { kind: 'too-large', message: 'analysis stopped: the document model is too large' }, false);
        h.backoffMs = 0;
        return { model: r.model };
      } finally {
        s.busy--;
        this.wake();
      }
    };
    // One call at a time per key.
    const p = s.queue.then(run, run);
    s.queue = p.catch(() => {});
    return p;
  }

  displaced() {
    this.stats.displaced++;
    return { cancelled: true, displaced: true };
  }

  failed(h, s, source, failure, workerFailure) {
    this.stats.failures++;
    this.quarantine.set(sha256(source) + '|' + s.engine.id, failure);
    h.backoffMs = h.backoffMs ? Math.min(h.backoffMs * 2, this.opts.backoffMaxMs) : this.opts.backoffMs;
    h.backoffUntil = this.now() + h.backoffMs;
    if (workerFailure) {
      const t = this.now();
      h.failures = h.failures.filter((x) => t - x < this.opts.failureWindowMs);
      h.failures.push(t);
      if (h.failures.length >= this.opts.maxFailures) h.unavailable = true;
    }
    return { internal: failure };
  }

  /** "Restart analysis": a new worker for every key; limits, backoff and quarantine cleared. */
  restart() {
    for (const s of this.slots.values()) this.stopWorker(s);
    this.health.clear();
    this.quarantine.clear();
  }

  stopWorker(s) {
    if (s.worker && !s.worker.dead) {
      s.worker.kill('stopped');
      this.stats.terminated++;
    }
    s.worker = null;
    this.wake();
  }

  stop(key) {
    const s = this.slots.get(key);
    if (s) {
      s.stopped = true;
      this.stopWorker(s);
    }
    this.slots.delete(key);
  }

  reapIdle() {
    const t = this.now();
    for (const s of this.slots.values()) if (s.worker && s.busy === 0 && t - s.lastUsed > this.opts.idleMs) this.stopWorker(s);
  }

  /** The engine ids a live (loading or loaded) worker of this server uses: the store never evicts their copies (C7). */
  inUse() {
    return new Set([...this.slots.values()].filter((s) => (s.worker && !s.worker.dead) || s.busy > 0).map((s) => s.engine.id));
  }

  dispose() {
    this.disposed = true;
    clearInterval(this.idleTimer);
    for (const s of this.slots.values()) this.stopWorker(s);
    this.slots.clear();
  }
}
