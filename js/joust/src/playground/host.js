// The playground's dev-host client (ADR-0024 D1): it launches the project's dev host (ADR-0022, protocol 1; the npm host
// here, the JVM host of a Gradle project through gradle.js) over stdio and is its client in every respect the protocol
// names: framing, one call at a time, the reverse `chain/read` answered by the session's chain, a hard client deadline,
// and the launch checks of an npm project (docs/protocol/dev-host-1.md, "Launch and framing" and "npm host").
//
// The host is a child process; its exit ends the session (the protocol treats exit as loss of state). A protocol
// violation by the host (an unsolicited or second response, a reverse request outside a call, a malformed frame)
// stops it, as the protocol requires of a client.

import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, realpathSync, statSync } from 'node:fs';
import { isAbsolute, join, relative, sep } from 'node:path';
import { encode, frameReader } from '../host/frames.js';

/** A host's error answer: `kind` and `code` are the protocol's stable fields (never branch on the message). */
export class HostError extends Error {
  constructor({ code, message, data } = {}) {
    super(data?.message ?? message ?? 'host error');
    this.rpc = code;
    this.kind = data?.kind ?? 'internal';
    this.code = data?.code ?? 'internal';
    this.sites = data?.sites ?? [];
    this.retryAtSlot = data?.retryAtSlot ?? null;
  }
  toJSON() {
    return { kind: this.kind, code: this.code, message: this.message, sites: this.sites, retryAtSlot: this.retryAtSlot };
  }
}

const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const within = (root, path) => {
  const rel = relative(root, path);
  return !(isAbsolute(rel) || rel === '..' || rel.startsWith('..' + sep));
};

// The host's JavaScript closure and the runtime's (protocol, npm host): with the build and the engine, the files the
// npm fingerprint covers.
const HOST_FILES = ['package.json', 'bin/joust-host.js',
  ...['frames', 'guard', 'launch', 'resolve', 'transport', 'worker'].map((n) => `src/host/${n}.js`)];
const RUNTIME_FILES = ['package.json', 'src/service.js', 'src/host-service.js', 'src/exnref.js',
  'engine/joust-runtime.js', 'engine/joust-runtime.js.wasm'];

// Large files (the engine) are hashed once per (real path, size, mtime).
const hashCache = new Map();
function hashFile(path) {
  const st = statSync(path);
  const key = `${path}\0${st.size}\0${st.mtimeMs}`;
  const hit = hashCache.get(key);
  if (hit) return hit;
  const h = sha(readFileSync(path));
  hashCache.set(key, h);
  return h;
}

/**
 * The npm fingerprint of a project (protocol, "npm host"): one directory entry at ordinal 0 over the build, the engine
 * and both closures, each by its real path relative to the project root. Returns the digest and the exact bytes of
 * build/joust/contracts.json it hashed (the session pins them: the page's descriptions and anchors come from these
 * bytes, and the host's initialize accepts only the same digest).
 */
export function npmFingerprint(root) {
  const real = realpathSync(root);
  const files = [];
  const add = (path) => {
    const r = realpathSync(path);
    if (!within(real, r)) throw Object.assign(new Error(`${path} is outside the project`), { code: 'outsideProject' });
    files.push(r);
  };
  const contractsPath = join(real, 'build/joust/contracts.json');
  const contracts = readFileSync(contractsPath);
  add(contractsPath);
  for (const f of HOST_FILES) add(join(real, 'node_modules/@julclang/joust', f));
  for (const f of RUNTIME_FILES) add(join(real, 'node_modules/@julclang/joust-runtime', f));
  const lines = files.map((p) => ({ rel: relative(real, p).split(sep).join('/'), p }))
    .sort((a, b) => Buffer.compare(Buffer.from(a.rel), Buffer.from(b.rel)))
    .map(({ rel, p }) => `${p === contractsPath ? sha(contracts) : hashFile(p)} 0 dir ${rel}\n`);
  return { digest: sha(lines.join('')), contracts, root: real };
}

/** The D3 environment allow-list. */
function hostEnv() {
  const env = {};
  for (const [k, v] of Object.entries(process.env)) {
    if (['PATH', 'HOME', 'LANG'].includes(k) || k.startsWith('LC_')
      || (process.platform === 'win32' && ['USERPROFILE', 'SystemRoot', 'TEMP', 'TMP'].includes(k))) env[k] = v;
  }
  return env;
}

/**
 * Launches the project-local npm host (`node node_modules/@julclang/joust/bin/joust-host.js --stdio`) at the trusted
 * project root, after checking the real paths of the package and the entry are inside the project.
 *
 * @param o.root       the project root
 * @param o.onExit     (reason) => void, once, when the host exits or is stopped
 * @param o.deadlineMs the client's hard deadline per call (default 120 s; the host's own callDeadlineMs is shorter)
 */
export function launchNpmHost({ root, onExit = () => {}, deadlineMs = 120_000, spawnHost = spawn } = {}) {
  const real = realpathSync(root);
  const entry = realpathSync(join(real, 'node_modules/@julclang/joust/bin/joust-host.js'));
  if (!within(real, entry)) throw new Error('the project\'s @julclang/joust is outside the project: install it in the project');
  return launchHost({ command: process.execPath, args: ['node_modules/@julclang/joust/bin/joust-host.js', '--stdio'], cwd: real,
    onExit, deadlineMs, spawnHost });
}

/**
 * A dev host started as {@code command args} in {@code cwd} with the D3 environment allow-list, spoken to over stdio
 * (both hosts: the npm host above, the JVM host of a Gradle project in gradle.js).
 */
export function launchHost({ command, args, cwd, onExit = () => {}, deadlineMs = 120_000, spawnHost = spawn }) {
  const child = spawnHost(command, args, { cwd, env: hostEnv(), stdio: ['pipe', 'pipe', 'pipe'] });
  let diagnostics = '';
  let ended = null;
  let nextId = 0;
  let current = null;        // { id, resolve, reject, read, timer, answered }
  let queue = Promise.resolve();

  const end = (reason) => {
    if (ended) return;
    ended = reason;
    reader.clear();
    if (current) {
      clearTimeout(current.timer);
      current.reject(new HostError({ data: { kind: 'internal', code: 'transport', message: `the dev host ended: ${reason}` } }));
      current = null;
    }
    if (child.exitCode === null && !child.signalCode) child.kill('SIGKILL');
    onExit(reason);
  };
  const violation = (what) => end(`protocol violation: ${what}`);

  const send = (msg) => {
    if (ended) return;
    const frame = encode(JSON.stringify(msg));
    if (!frame) return violation('an outgoing frame over the size limit');
    child.stdin.write(frame);
  };

  async function answerRead(msg) {
    const call = current;
    let result;
    try {
      result = await call.read(msg.params ?? {});
    } catch (e) {
      result = { tip: e.tip ?? null, failed: { message: String(e.message ?? e).slice(0, 500) } };
    }
    // A read the host abandoned (cancelled, timed out) or of a call that ended is not answered.
    if (current !== call || ended) return;
    send({ jsonrpc: '2.0', id: msg.id, result });
  }

  const reader = frameReader((msg) => {
    if ('method' in msg) {
      if (msg.method !== 'chain/read' || typeof msg.id !== 'string') return violation(`an unexpected request ${msg.method}`);
      if (!current) return violation('a reverse request outside a call');
      answerRead(msg);
      return;
    }
    if (!current || current.id !== msg.id || current.answered) return violation('an unsolicited response');
    current.answered = true;
    const c = current;
    clearTimeout(c.timer);
    current = null;
    if ('error' in msg) c.reject(new HostError(msg.error));
    else c.resolve(msg.result);
  }, (why) => (why === null ? end('end of output') : violation(String(why))));

  child.stdout.on('data', (b) => {
    try {
      reader.chunk(b);
    } catch (e) {
      violation(e.message);
    }
  });
  child.stdout.on('end', () => {
    try {
      reader.end?.();
    } catch {
      // the exit below reports it
    }
  });
  child.stderr.on('data', (b) => {
    diagnostics = (diagnostics + b).slice(-8192);
  });
  child.on('error', (e) => end(`could not start: ${e.message}`));
  child.on('exit', (code, signal) => end(`exited (${signal ?? code})${diagnostics ? ': ' + diagnostics.trim().split('\n').at(-1) : ''}`));
  child.stdin.on('error', () => {});

  /** One forward call; reverse reads are answered by {@code read(params)} (a reverse answer object). */
  function call(method, params = {}, { read } = {}) {
    const run = queue.then(() => new Promise((resolve, reject) => {
      if (ended) return reject(new HostError({ data: { kind: 'internal', code: 'transport', message: `the dev host ended: ${ended}` } }));
      const id = ++nextId;
      current = { id, resolve, reject, answered: false,
        read: read ?? (async () => ({ unsupported: true })),
        timer: setTimeout(() => {
          // The hard client timeout: cancel, then give the host the protocol's two seconds before stopping it.
          send({ jsonrpc: '2.0', method: '$/cancelRequest', params: { id } });
          setTimeout(() => { if (current?.id === id) end('the call outlived the client deadline'); }, 2_500).unref?.();
        }, deadlineMs) };
      current.timer.unref?.();
      send({ jsonrpc: '2.0', id, method, params });
    }));
    queue = run.catch(() => {});
    return run;
  }

  return {
    call,
    get alive() {
      return !ended;
    },
    get ended() {
      return ended;
    },
    get diagnostics() {
      return diagnostics;
    },
    get pid() {
      return child.pid;
    },
    /** Asks the host to shut down, then stops it. */
    async close() {
      if (ended) return;
      try {
        await Promise.race([call('shutdown', {}), new Promise((r) => setTimeout(r, 2_000).unref?.())]);
      } catch {
        // stopping anyway
      }
      if (ended) return;
      const exited = new Promise((r) => child.once('exit', r));
      child.stdin.end(encode(JSON.stringify({ jsonrpc: '2.0', method: 'exit' })));
      const kill = setTimeout(() => { if (child.exitCode === null && !child.signalCode) child.kill('SIGKILL'); }, 1_000);
      await exited;
      clearTimeout(kill);
      end('closed');
    },
  };
}

/**
 * The build an npm project's session runs (ADR-0024 D2): opening it pins the bytes the npm fingerprint covers; the
 * session asks `stale()` before every prepare (I6). gradle.js's gradleSource has the same shape.
 *
 * open() → { kind, root, digest, snapshot, entries: [{ description (JSON text), script }], stale(), launch(o), dispose() }
 */
export function npmSource(root, { launch = launchNpmHost } = {}) {
  return {
    kind: 'npm',
    async open() {
      const fp = npmFingerprint(root);
      return {
        kind: 'npm', root: fp.root, digest: fp.digest, snapshot: fp.root,
        // The runtime engine files the fingerprint covers, by hash: the server takes the shared verifiers' scripts from
        // its loaded engine only when that engine is these bytes (ADR-0024 M4).
        engineFiles: Object.fromEntries(['joust-runtime.js', 'joust-runtime.js.wasm'].map((f) =>
          [f, hashFile(realpathSync(join(fp.root, 'node_modules/@julclang/joust-runtime/engine', f)))])),
        entries: JSON.parse(fp.contracts.toString('utf8')),
        stale() {
          try {
            return npmFingerprint(fp.root).digest !== fp.digest;
          } catch {
            return true;
          }
        },
        launch: (o) => launch({ root: fp.root, ...o }),
        dispose() {},
      };
    },
  };
}
