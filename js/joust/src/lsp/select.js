// ADR-0021 D3, D3a, D3b: which compiler analyses a file, and in which mode.
//
// - The protocol layer is always the server's own; what can come from the project is only its two engine files,
//   engine/joust-toolchain.js and its .wasm, of the project's @julclang/joust.
// - The project's engine is eligible only when: the workspace is trusted; the file is in a workspace folder; the
//   project (ADR-0017 D4's rules: joust.config.json, or a package.json depending on @julclang/joust) and its package
//   are inside that same folder, by the real paths of every step (inputs.js, segment comparison); a folder listed by a
//   .code-workspace file outside the file's own directory is not eligible unless a machine-scoped setting allows it;
//   and the engine reports the lang protocol this server speaks (an engine that predates M5a does not).
// - Both files are read once under D4b's rules, their bytes written to private storage named by their SHA-256, and the
//   worker loads that copy, which the workspace cannot change (no gap between check and load). The store keeps at most
//   a few copies, the least recently used not in use evicted first (security review S21).
// - Otherwise the bundled engine runs, in advisory mode, and the reason is said.
import { promises as realFs } from 'node:fs';
import { createHash } from 'node:crypto';
import { basename, dirname, join, relative, sep } from 'node:path';
import { folderOf, inside } from './paths.js';

export const LANG_PROTOCOL = 1;
export const ENGINE_JS = 'joust-toolchain.js';
export const ENGINE_WASM = 'joust-toolchain.js.wasm';
const JS_CAP = 8 * 1024 * 1024;
const WASM_CAP = 128 * 1024 * 1024;
const GRADLE_FILES = ['settings.gradle', 'settings.gradle.kts', 'build.gradle', 'build.gradle.kts'];
/** The org.julclang.joust plugin's version, as Groovy or Kotlin build files write it. Linear: one quantifier per gap. */
export const GRADLE_PLUGIN = /id[\s(]*["']org\.julclang\.joust["'][\s)]*version[\s(]*["']([^"'\s]{1,64})["']/;

const hex = (b) => createHash('sha256').update(b).digest('hex');

/** How long a lease protects a copy: a load takes seconds; a crashed holder's lease stops protecting after this. */
const LEASE_MS = 10 * 60 * 1000;

/** A collector's claim older than this is abandoned (C23): claims are held for milliseconds. */
const CLAIM_MS = 60 * 1000;

const alive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return e.code === 'EPERM';
  }
};

/** The id of an engine: the SHA-256 of its two files' SHA-256s. */
export const engineId = (jsHash, wasmHash) => hex(`${jsHash}\n${wasmHash}\n`);

/** The engine worker a compiler runs in: one per engine id (D2a's "per compiler version"). */
export const workerKey = (engine) => `engine:${engine.id}`;

/**
 * Private storage of checked engine copies: `<storage>/engines/<id>/`. Writes nothing anywhere else (L3).
 * - Several windows share the store (VS Code's global storage): a copy is in use when a live worker of this process uses
 *   it, or when any process touched its `.used` within `recentMs` (each worker start touches it); only copies in use by
 *   neither, beyond the `maxCopies` most recent, are evicted (security review S3, Codex C7 on #83).
 * - Installing is coalesced by digest within the process, and a copy another process published first is reused once its
 *   bytes check, never removed (Codex C5 on #83). A copy is immutable once published.
 */
export function createStore(storageDir, { maxCopies = 3, recentMs = 15 * 60 * 1000, fs = realFs } = {}) {
  const root = join(storageDir, 'engines');
  const installing = new Map();
  const valid = async (dir, jsHash, wasmHash) => {
    try {
      const [a, b] = await Promise.all([fs.readFile(join(dir, ENGINE_JS)), fs.readFile(join(dir, ENGINE_WASM))]);
      return hex(a) === jsHash && hex(b) === wasmHash;
    } catch {
      return false;
    }
  };
  const usedAt = async (n) => {
    try {
      return (await fs.stat(join(root, n, '.used'))).mtimeMs;
    } catch {
      return 0;
    }
  };
  /**
   * What a directory holds for digest `n`, proven by reading it (C33 on #83): `valid` (its bytes are `n`), `invalid`
   * (read, and they are not, or a file is missing from it), `absent` (no directory), or `unreadable` (a read failed for
   * another reason, EPERM, EACCES or EMFILE say: nothing is proven either way).
   */
  async function digestOf(dir, n) {
    let a;
    let b;
    try {
      [a, b] = await Promise.all([fs.readFile(join(dir, ENGINE_JS)), fs.readFile(join(dir, ENGINE_WASM))]);
    } catch (e) {
      if (e?.code !== 'ENOENT' && e?.code !== 'ENOTDIR') return 'unreadable';
      const st = await fs.stat(dir).then((x) => x, (x) => x);
      if (st?.code === 'ENOENT') return 'absent';
      return st?.isDirectory?.() ? 'invalid' : st?.code ? 'unreadable' : 'invalid';
    }
    return engineId(hex(a), hex(b)) === n ? 'valid' : 'invalid';
  }

  /**
   * A moved-aside destination left behind (C31, C33 on #83). It is deleted only when that is proven safe: its own bytes
   * read and fail their digest, or a valid copy of its digest is verified in place. A valid one with nothing of its
   * digest in place goes back; one facing a destination proven invalid replaces it (the invalid one moved aside, to be
   * proven and deleted in turn). Whatever cannot be read now is kept, for a later sweep.
   */
  async function recoverDamaged(dir, n) {
    const own = await digestOf(dir, n);
    if (own === 'unreadable') return;
    if (own !== 'valid') {
      await fs.rm(dir, { recursive: true, force: true }).catch(() => {});
      return;
    }
    const dest = join(root, n);
    const there = await digestOf(dest, n);
    if (there === 'valid') {
      await fs.rm(dir, { recursive: true, force: true }).catch(() => {});
      return;
    }
    if (there === 'unreadable') return;
    if (there === 'invalid') {
      const aside = join(root, `.damaged-${n}-${process.pid}-${Date.now()}-x`);
      if (!(await fs.rename(dest, aside).then(() => true, () => false))) return;
    }
    await fs.rename(dir, dest).catch(() => {});
  }

  /** Whether a claimed copy is still wanted: a live lease, a fresh use, or this server's own use. */
  async function wanted(aside, n, inUse = () => new Set()) {
    try {
      for (const f of await fs.readdir(aside)) {
        if (!f.startsWith('.lease-')) continue;
        const st = await fs.stat(join(aside, f)).catch(() => null);
        if (st && Date.now() - st.mtimeMs < LEASE_MS) return true;
      }
    } catch {
      return true;
    }
    let usedNow = 0;
    try {
      usedNow = (await fs.stat(join(aside, '.used'))).mtimeMs;
    } catch {
      usedNow = 0;
    }
    return inUse().has(n) || Date.now() - usedNow < recentMs;
  }

  /**
   * Puts a claimed copy back. Review C24 on #83: a failed rename deletes the claimed copy only when a valid copy of the
   * same digest is in place (another window republished it); on any other failure the claim is left for the sweep below
   * to put back later, never deleted.
   */
  async function putBack(aside, n) {
    // S14 on #83: a claim goes back under its digest name only if its bytes ARE that digest; one that is not is removed.
    let hashes;
    try {
      const [a, b] = await Promise.all([fs.readFile(join(aside, ENGINE_JS)), fs.readFile(join(aside, ENGINE_WASM))]);
      hashes = [hex(a), hex(b)];
    } catch {
      // C30 on #83: bytes that cannot be read now (EPERM, EACCES, EMFILE…) are not shown to be wrong: the claim is
      // kept, for a later sweep.
      return;
    }
    const [jsHash, wasmHash] = hashes;
    if (engineId(jsHash, wasmHash) !== n) {
      await fs.rm(aside, { recursive: true, force: true }).catch(() => {});
      return;
    }
    try {
      await fs.rename(aside, join(root, n));
      return;
    } catch {
      // fall through
    }
    if (await valid(join(root, n), jsHash, wasmHash)) {
      await fs.rm(aside, { recursive: true, force: true }).catch(() => {});
      return;
    }
    // C27 on #83: a destination that is there but not a valid copy never costs the claim its good bytes: it is moved
    // aside and the claim put in its place; any failure keeps the claim, for the sweep to retry.
    const damaged = join(root, `.damaged-${n}-${process.pid}-${Date.now()}`);
    try {
      await fs.rename(join(root, n), damaged);
    } catch {
      return;
    }
    // C31 on #83: what was moved aside is checked, not assumed damaged: another collector may have restored a valid copy
    // since the check above. A valid one goes straight back, and this claim, now redundant, goes.
    if (await valid(damaged, jsHash, wasmHash)) {
      try {
        await fs.rename(damaged, join(root, n));
        await fs.rm(aside, { recursive: true, force: true }).catch(() => {});
      } catch {
        // left for the sweep, which restores a valid moved-aside copy
      }
      return;
    }
    try {
      await fs.rename(aside, join(root, n));
    } catch {
      return;
    }
    await fs.rm(damaged, { recursive: true, force: true }).catch(() => {});
  }

  /**
   * Review C23 on #83: a claim a collector left behind (it crashed between its rename and its decision, or could not put
   * a copy back) is recovered once older than CLAIM_MS (a claim is normally held for milliseconds), unless its owner is a
   * live process on this machine and the claim is younger than LEASE_MS: put back when still wanted and no copy of its
   * digest is in place, removed otherwise.
   */
  async function sweep(names, inUse) {
    for (const c of names) {
      const m = /^\.evicted-([0-9a-f]{64})-(\d+)-(\d+)(?:-r[0-9a-z]+)?$/.exec(c);
      if (!m) continue;
      const [, n, pid, at] = m;
      const age = Date.now() - Number(at);
      if (age < CLAIM_MS) continue;
      if (age < LEASE_MS && Number(pid) !== process.pid && alive(Number(pid))) continue;
      // C31 on #83: the recovering collector first makes the claim its own, by an atomic rename to its own claim name;
      // of two collectors recovering one claim, one rename succeeds and the other skips it.
      const aside = join(root, `.evicted-${n}-${process.pid}-${Date.now()}-r${Math.random().toString(36).slice(2, 8)}`);
      try {
        await fs.rename(join(root, c), aside);
      } catch {
        continue;
      }
      // A wanted claim always goes through putBack, which keeps its good bytes unless a valid copy is in place (C27); an
      // unwanted one is removed.
      if (await wanted(aside, n, inUse)) await putBack(aside, n);
      else await fs.rm(aside, { recursive: true, force: true }).catch(() => {});
    }
  }

  async function evict(keep, inUse) {
    try {
      const all = await fs.readdir(root);
      await sweep(all.filter((n) => n.startsWith('.evicted-')), inUse);
      // A damaged destination moved aside (C27) and left by a crash: removed once past the claim bound.
      for (const d of all) {
        const m = /^\.damaged-([0-9a-f]{64})-\d+-(\d+)(?:-x)?$/.exec(d);
        if (!m || Date.now() - Number(m[2]) <= CLAIM_MS) continue;
        await recoverDamaged(join(root, d), m[1]);
      }
    } catch {
      // no store yet
    }
    let names;
    try {
      names = (await fs.readdir(root)).filter((n) => /^[0-9a-f]{64}$/.test(n));
    } catch {
      return;
    }
    const now = Date.now();
    const aged = [];
    for (const n of names) aged.push({ n, t: await usedAt(n) });
    aged.sort((x, y) => y.t - x.t);
    let kept = 0;
    for (const { n, t } of aged) {
      if (n === keep || inUse().has(n) || now - t < recentMs || kept < maxCopies) {
        kept++;
        continue;
      }
      // Review C12 on #83: the snapshot above may be stale (another put reused the copy, a worker started on it, in
      // this window or another). Right before removing, the copy's use is read again, and the copy is first renamed
      // aside, so a loader never sees it half deleted; a loader that still finds it gone selects again (host.js).
      if (inUse().has(n) || Date.now() - (await usedAt(n)) < recentMs) {
        kept++;
        continue;
      }
      // C18: claimed by an atomic rename first; only then are its leases and its use read, from the claimed directory,
      // where nobody can add a lease (a lease taken after the rename fails: its taker selects again). A leased or freshly
      // used copy is put back.
      const aside = join(root, `.evicted-${n}-${process.pid}-${Date.now()}`);
      try {
        await fs.rename(join(root, n), aside);
      } catch {
        continue;
      }
      if (await wanted(aside, n, inUse)) {   // C28 on #83: this server's live use counts here too
        kept++;
        await putBack(aside, n);
        continue;
      }
      await fs.rm(aside, { recursive: true, force: true }).catch(() => {});
    }
  }
  async function install(id, js, wasm, jsHash, wasmHash) {
    const dir = join(root, id);
    if (await valid(dir, jsHash, wasmHash)) return dir;
    await fs.mkdir(root, { recursive: true });
    const tmp = await fs.mkdtemp(join(root, '.tmp-'));
    try {
      await fs.writeFile(join(tmp, ENGINE_JS), js);
      await fs.writeFile(join(tmp, ENGINE_WASM), wasm);
      // The loader is CommonJS; this keeps any package.json above the storage from deciding how it loads.
      await fs.writeFile(join(tmp, 'package.json'), '{ "type": "commonjs" }\n');
      // S16 on #83: published already marked as used, so no collector can take it between the rename and put's touch.
      await fs.writeFile(join(tmp, '.used'), String(Date.now()));
      // Another window may publish, claim or put back this digest at any moment (CI's S13 churn found a put-back landing
      // between a check and the rename): the rename is retried, a valid copy in place is reused, and an invalid one is
      // moved aside (checked again there) rather than deleted.
      for (let attempt = 0; ; attempt++) {
        try {
          await fs.rename(tmp, dir);
          return dir;
        } catch (e) {
          if (await valid(dir, jsHash, wasmHash)) return dir;
          if (attempt >= 8) throw e;
          const there = await fs.stat(dir).then(() => true, () => false);
          if (there) {
            const damaged = join(root, `.damaged-${id}-${process.pid}-${Date.now()}`);
            await fs.rename(dir, damaged).catch(() => {});
            if (await valid(damaged, jsHash, wasmHash)) {
              // A valid copy after all (put back meanwhile): it goes straight back, and this install is not needed.
              if (await fs.rename(damaged, dir).then(() => true, () => false)) return dir;
            }
          }
          await new Promise((r) => setTimeout(r, 5 * (attempt + 1)));
        }
      }
    } finally {
      await fs.rm(tmp, { recursive: true, force: true }).catch(() => {});
    }
  }
  return {
    root,
    /** Writes (or reuses, after checking its bytes) the copy of an engine; `{ id, file }`. */
    async put(js, wasm, inUse = new Set()) {
      const jsHash = hex(js);
      const wasmHash = hex(wasm);
      const id = engineId(jsHash, wasmHash);
      let p = installing.get(id);
      if (!p) {
        p = install(id, js, wasm, jsHash, wasmHash).finally(() => installing.delete(id));
        installing.set(id, p);
      }
      let dir = await p;
      try {
        await fs.writeFile(join(dir, '.used'), String(Date.now()));
      } catch {
        // Claimed by a collector in another window between the install and the touch: install again (C18).
        dir = await install(id, js, wasm, jsHash, wasmHash);
        await fs.writeFile(join(dir, '.used'), String(Date.now()));
      }
      await evict(id, typeof inUse === 'function' ? inUse : () => inUse);
      return { id, file: join(dir, ENGINE_JS) };
    },
    /**
     * A lease on a copy while a worker loads it (C18): an async release function, or null when the copy is gone.
     * The collector puts back any copy holding a lease younger than LEASE_MS (a crashed holder's lease expires).
     */
    async lease(id) {
      if (!/^[0-9a-f]{64}$/.test(String(id))) return async () => {};
      const f = join(root, id, `.lease-${process.pid}-${createHash('sha256').update(String(Math.random()) + Date.now()).digest('hex').slice(0, 12)}`);
      try {
        await fs.writeFile(f, '', { flag: 'wx' });
      } catch {
        return null;
      }
      return () => fs.rm(f, { force: true }).catch(() => {});
    },
    /** Marks a copy as used now (a worker started on it); nothing for an engine that is not in the store. */
    async touch(id) {
      if (!/^[0-9a-f]{64}$/.test(String(id))) return;
      await fs.writeFile(join(root, id, '.used'), String(Date.now())).catch(() => {});
    },
  };
}

/**
 * The selector of a server. `folders`: the real paths of the workspace folders; `trusted`: the workspace is trusted
 * (other editors: only with --project-engine); `workspaceFileDir`: the real directory of a .code-workspace file, if
 * any; `allowedRoots`: real paths a machine-scoped setting allows beyond it.
 */
export function createSelector({ reader, fs = realFs, store, host, bundled, trusted = false, folders = [], workspaceFileDir = null,
  allowedRoots = [] }) {
  const cache = new Map();
  let generation = 0;

  // Workers are keyed by the compiler (its engine's id), not by project root: an M5a analysis is one file and holds no
  // project state, and keying by root made three roots on one compiler replace each other's workers (VS Code 1.109:
  // a replacement's load hung and the server died with SIGTRAP). Identical bytes share a worker, the bundled engine's
  // included. Per-root state (libraries) arrives with M5c.
  const advisory = (reason, extra = {}) => ({ key: workerKey(bundled), engine: bundled, source: 'bundled', mode: 'advisory', reason, ...extra });

  async function discover(folder, realFile) {
    // From the file's directory up to the folder: the first npm project root, or a Gradle build (ADR-0017 D4).
    const segs = relative(folder, dirname(realFile)).split(sep).filter(Boolean);
    for (let n = segs.length; n >= 0; n--) {
      const rel = segs.slice(0, n);
      const at = (f) => [...rel, f].join('/');
      if (await reader.isFile(folder, at('joust.config.json'))) return { kind: 'npm', rel };
      const pkg = await reader.readJson(folder, at('package.json'));
      if (pkg && ['dependencies', 'devDependencies'].some((k) => pkg[k] && typeof pkg[k] === 'object' && '@julclang/joust' in pkg[k])) {
        return { kind: 'npm', rel };
      }
      for (const g of GRADLE_FILES) if (await reader.isFile(folder, at(g))) return { kind: 'gradle', rel, file: g };
    }
    return undefined;
  }

  /** The project's @julclang/joust: from its root up to the folder, never above (D3b). */
  async function findPackage(folder, rootRel) {
    let outside = false;
    for (let n = rootRel.length; n >= 0; n--) {
      const rel = [...rootRel.slice(0, n), 'node_modules', '@julclang', 'joust'];
      const r = await reader.resolveIn(folder, [...rel, 'package.json'].join('/'));
      if (r.error === 'outside' || r.error === 'refused') outside = true;
      if (r.error) continue;
      const pkg = await reader.readJson(folder, [...rel, 'package.json'].join('/'));
      if (pkg) return { rel, version: typeof pkg.version === 'string' ? pkg.version : 'unknown' };
    }
    return { outside };
  }

  /** Whether an ancestor of the folder has a @julclang/joust (only to say so: it is never loaded, D3b). */
  async function aboveFolder(folder) {
    if (!trusted) return false;
    for (let d = dirname(folder); dirname(d) !== d; d = dirname(d)) {
      try {
        await fs.stat(join(d, 'node_modules', '@julclang', 'joust', 'package.json'));
        return true;
      } catch {
        // keep looking
      }
    }
    return false;
  }

  async function gradleVersion(folder, rel) {
    for (const g of ['build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts']) {
      const r = await reader.read(folder, [...rel, g].join('/'), { cap: 512 * 1024 });
      if (r.error) continue;
      // Read as text, never by running Gradle (D3).
      // One quantifier per gap, so the match is linear on any text (security review S1 on #83: the earlier pattern
      // backtracked quadratically on a long run of spaces, 154 s on the protocol thread for 512 KiB).
      const m = GRADLE_PLUGIN.exec(r.bytes.toString('utf8'));
      if (m) return m[1];
    }
    return null;
  }

  async function selectUncached(realFile) {
    const folder = folderOf(realFile, folders);
    if (!folder) return advisory(folders.length ? 'the file is outside every workspace folder' : 'no workspace folder is open');
    if (!trusted) return advisory('restricted mode: the workspace is not trusted', { folder });
    const project = await discover(folder, realFile);
    if (!project) {
      return advisory((await aboveFolder(folder)) ? 'compiler found outside the workspace folder' : 'no npm project for this file', { folder });
    }
    // Review C15 on #83: every selection past discovery carries its project root, the scope of its health (failures,
    // "unavailable"), advisory ones included; the folder is the scope only where no project is discovered.
    const root = join(folder, ...project.rel);
    const at = { folder, root };
    if (project.kind === 'gradle') {
      const v = await gradleVersion(folder, project.rel);
      // The integration with ADR-0018 M2c and M4: a Gradle project's .jsc sources (generateJsc's tree), whose files a
      // file's imports resolve to.
      const jsc = await reader.resolveIn(folder, 'src/main/jsc', { base: project.rel });
      return advisory(`Gradle project${v ? ` (plugin ${v})` : ''}: the bundled compiler, advisory`,
        { ...at, gradlePlugin: v, kind: 'gradle', ...(jsc.error ? {} : { contracts: jsc.path }) });
    }
    if (workspaceFileDir && !inside(folder, workspaceFileDir) && !allowedRoots.some((r) => inside(folder, r))) {
      return advisory('this folder lies outside the .code-workspace file\'s directory: its compiler is not loaded', at);
    }
    const pkg = await findPackage(folder, project.rel);
    if (!pkg.rel) {
      return advisory(pkg.outside || (await aboveFolder(folder)) ? 'compiler found outside the workspace folder'
        : 'the project\'s @julclang/joust is not installed', at);
    }
    const config = (await reader.readJson(folder, [...project.rel, 'joust.config.json'].join('/'))) ?? {};
    // Only `contracts` is taken from joust.config.json, under D4b's rules; never networks, URLs or out.
    const contractsText = typeof config.contracts === 'string' ? config.contracts : 'contracts';
    const contracts = await reader.resolveIn(folder, contractsText, { base: project.rel });
    const js = await reader.read(folder, [...pkg.rel, 'engine', ENGINE_JS].join('/'), { cap: JS_CAP });
    const wasm = await reader.read(folder, [...pkg.rel, 'engine', ENGINE_WASM].join('/'), { cap: WASM_CAP });
    if (js.error || wasm.error) {
      return advisory(js.error === 'outside' || wasm.error === 'outside' || js.error === 'refused' || wasm.error === 'refused'
        ? 'compiler found outside the workspace folder' : 'the project\'s compiler engine is missing or unreadable', at);
    }
    const copy = await store.put(js.bytes, wasm.bytes, () => host.inUse());
    const key = workerKey(copy);
    const engine = { file: copy.file, id: copy.id, source: 'project', packageVersion: pkg.version, storeRoot: store.root };
    const probe = await host.info(key, engine);
    // The copy vanished while it loaded (another window evicted it), or the probe's start was abandoned because its
    // slot was stopped meanwhile (C25): not cached, the next file selects again (C12).
    if (probe.missing) throw new Error('the engine copy was removed while it loaded');
    if (probe.displaced) throw new Error('the compiler\'s probe was interrupted');
    const info = probe.info;
    if (!probe.ready || !info || info.protocol !== LANG_PROTOCOL || !Array.isArray(info.features) || !info.features.includes('analyze')) {
      host.stop(key);
      return advisory(`the project's compiler (${pkg.version}) predates the language server's protocol: the bundled compiler runs`, at);
    }
    engine.version = info.joust ?? pkg.version;
    const inContracts = !contracts.error && inside(realFile, contracts.path);
    return {
      key, engine, source: 'project', folder, root, kind: 'npm', ...(contracts.error ? {} : { contracts: contracts.path }),
      mode: inContracts ? 'project-matched' : 'advisory',
      reason: inContracts ? 'the project\'s own compiler' : 'the file is not in the project\'s contracts directory',
    };
  }

  return {
    get generation() {
      return generation;
    },
    /** Forgets every selection (trust, folders, configuration or packages changed). */
    invalidate(next = {}) {
      generation++;
      cache.clear();
      if ('trusted' in next) trusted = next.trusted;
      if ('folders' in next) folders = next.folders;
    },
    get trusted() {
      return trusted;
    },
    /** The selection for a file (its real path, or null for a buffer that is not a file on disk). */
    async select(realFile) {
      if (!realFile) return advisory('the document is not a file on disk');
      const dir = dirname(realFile);
      const hit = cache.get(dir);
      if (hit) return hit;
      const gen = generation;
      // A selection that failed is not cached (C5): the next file tries again.
      const p = selectUncached(realFile).catch((e) => {
        if (cache.get(dir) === p && generation === gen) cache.delete(dir);
        return advisory(`the compiler could not be selected (${e?.message ?? e})`);
      });
      cache.set(dir, p);
      return p;
    },
    basename,
  };
}
