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
        resolve({ ready: false, error: String(e?.message ?? e), code: e?.code });
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
    if (this.booted || reason === 'load-timeout') this.worker.terminate().catch(() => {});
    else this.ready.then(() => this.worker.terminate().catch(() => {}));
    this.died(reason);
  }

  /** Posts a call; resolves with the worker's answer, or `{ failure: 'time'|'memory'|'crash'|'cancelled' }`. */
  call(msg, timeoutMs, token) {
    if (this.dead) return Promise.resolve({ failure: 'crash', message: 'the engine worker is gone' });
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

/** The engine workers of a server, by key (a project root and compiler, or "bundled"). */
export class EngineHost {
  constructor(opts) {
    this.opts = { ...DEFAULTS, ...opts };
    if (!this.opts.workerEntry) throw new Error('EngineHost needs workerEntry');
    this.slots = new Map();      // key -> { worker, engine, lastUsed, failures: [], backoffUntil, backoffMs, unavailable, queue }
    this.quarantine = new Map(); // sha256(source) + engine id -> the failure
    this.stats = { started: 0, terminated: 0, calls: 0, cancelled: 0, failures: 0 };
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
      s = { key, engine, worker: null, lastUsed: this.now(), failures: [], backoffUntil: 0, backoffMs: 0, unavailable: false, queue: Promise.resolve() };
      this.slots.set(key, s);
    }
    return s;
  }

  workerFor(s) {
    if (s.worker && !s.worker.dead) return s.worker;
    // At most maxWorkers alive: stop the least recently used other one.
    const alive = [...this.slots.values()].filter((o) => o !== s && o.worker && !o.worker.dead);
    if (alive.length >= this.opts.maxWorkers) {
      alive.sort((a, b) => a.lastUsed - b.lastUsed);
      for (const o of alive.slice(0, alive.length - this.opts.maxWorkers + 1)) this.stopWorker(o);
    }
    if (process.env.JOUST_LSP_DEBUG) process.stderr.write(`[joust-lsp] starting a worker for ${s.key}\n`);
    s.worker = new EngineWorker(s.engine, this.opts);
    this.stats.started++;
    return s.worker;
  }

  /** Loads the key's engine (if needed) and returns its `lang.info` (null for an engine without the lang entries). */
  async info(key, engine) {
    const s = this.slot(key, engine);
    const w = this.workerFor(s);
    const r = await w.ready;
    if (!r.ready) return { ready: false, error: r.error };
    return { ready: true, info: r.info, version: r.version };
  }

  status(key) {
    const s = this.slots.get(key);
    if (!s) return { state: 'idle' };
    if (s.unavailable) return { state: 'unavailable', failures: s.failures.length };
    if (s.backoffUntil > this.now()) return { state: 'backoff', until: s.backoffUntil };
    return { state: 'ok' };
  }

  /**
   * Analyses one file on the key's engine: `{ model }` (the JSON text), `{ internal: { kind, message } }` (the call
   * failed: kind time, memory, crash, failed or too-large; the input is quarantined), `{ cancelled: true }`,
   * `{ unavailable: true }`, or `{ retryAt }` (in backoff).
   */
  analyze(key, engine, { file, source }, token) {
    const s = this.slot(key, engine);
    const run = async () => {
      if (s.unavailable) return { unavailable: true };
      const q = this.quarantine.get(sha256(source) + '|' + engine.id);
      if (q) return { internal: q, quarantined: true };
      if (s.backoffUntil > this.now()) return { retryAt: s.backoffUntil };
      if (token?.isCancellationRequested) return { cancelled: true };
      s.lastUsed = this.now();
      const w = this.workerFor(s);
      const ready = await w.ready;
      if (!ready.ready) return this.failed(s, source, { kind: 'crash', message: `the engine did not load: ${ready.error}` }, true);
      this.stats.calls++;
      const r = await w.call({ op: 'analyze', file, source }, this.opts.analysisMs, token);
      if (r.failure === 'cancelled' || r.failure === 'stopped') {
        this.stats.cancelled++;
        if (r.failure === 'cancelled') this.stats.terminated++;
        return { cancelled: true };
      }
      if (r.failure) {
        if (r.failure === 'time') this.stats.terminated++;
        const message = r.failure === 'time' ? `analysis stopped: time limit (${this.opts.analysisMs} ms)`
          : r.failure === 'memory' ? `analysis stopped: memory limit (${this.opts.maxOldGenerationSizeMb} MB)`
            : `analysis stopped: the engine failed (${r.message ?? r.failure})`;
        return this.failed(s, source, { kind: r.failure, message }, true);
      }
      if (r.failed) return this.failed(s, source, { kind: 'failed', message: `analysis stopped: internal error (${r.failed})` }, false);
      if (r.tooLarge) return this.failed(s, source, { kind: 'too-large', message: 'analysis stopped: the document model is too large' }, false);
      s.backoffMs = 0;
      return { model: r.model };
    };
    // One call at a time per key.
    const p = s.queue.then(run, run);
    s.queue = p.catch(() => {});
    return p;
  }

  failed(s, source, failure, workerFailure) {
    this.stats.failures++;
    this.quarantine.set(sha256(source) + '|' + s.engine.id, failure);
    s.backoffMs = s.backoffMs ? Math.min(s.backoffMs * 2, this.opts.backoffMaxMs) : this.opts.backoffMs;
    s.backoffUntil = this.now() + s.backoffMs;
    if (workerFailure) {
      const t = this.now();
      s.failures = s.failures.filter((x) => t - x < this.opts.failureWindowMs);
      s.failures.push(t);
      if (s.failures.length >= this.opts.maxFailures) {
        s.unavailable = true;
        this.stopWorker(s);
      }
    }
    return { internal: failure };
  }

  /** "Restart analysis": a new worker for every key, limits, backoff and quarantine cleared. */
  restart() {
    for (const s of this.slots.values()) {
      this.stopWorker(s);
      s.failures = [];
      s.unavailable = false;
      s.backoffMs = 0;
      s.backoffUntil = 0;
    }
    this.quarantine.clear();
  }

  stopWorker(s) {
    if (s.worker && !s.worker.dead) {
      s.worker.kill('stopped');
      this.stats.terminated++;
    }
    s.worker = null;
  }

  stop(key) {
    const s = this.slots.get(key);
    if (s) this.stopWorker(s);
    this.slots.delete(key);
  }

  reapIdle() {
    const t = this.now();
    for (const s of this.slots.values()) if (s.worker && t - s.lastUsed > this.opts.idleMs) this.stopWorker(s);
  }

  /** Engine ids some live worker uses (the engine store keeps their copies). */
  inUse() {
    return new Set([...this.slots.values()].map((s) => s.engine.id));
  }

  dispose() {
    clearInterval(this.idleTimer);
    for (const s of this.slots.values()) this.stopWorker(s);
    this.slots.clear();
  }
}
