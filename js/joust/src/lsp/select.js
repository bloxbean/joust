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

const hex = (b) => createHash('sha256').update(b).digest('hex');

/** The id of an engine: the SHA-256 of its two files' SHA-256s. */
export const engineId = (jsHash, wasmHash) => hex(`${jsHash}\n${wasmHash}\n`);

/** The engine worker a compiler runs in: one per engine id (D2a's "per compiler version"). */
export const workerKey = (engine) => `engine:${engine.id}`;

/** Private storage of checked engine copies: `<storage>/engines/<id>/`. Writes nothing anywhere else (L3). */
export function createStore(storageDir, { maxCopies = 3, fs = realFs } = {}) {
  const root = join(storageDir, 'engines');
  async function evict(keep, inUse) {
    let names;
    try {
      names = (await fs.readdir(root)).filter((n) => /^[0-9a-f]{64}$/.test(n));
    } catch {
      return;
    }
    const aged = [];
    for (const n of names) {
      try {
        aged.push({ n, t: (await fs.stat(join(root, n, '.used'))).mtimeMs });
      } catch {
        aged.push({ n, t: 0 });
      }
    }
    aged.sort((a, b) => b.t - a.t);
    let kept = 0;
    for (const { n } of aged) {
      if (n === keep || inUse.has(n)) {
        kept++;
        continue;
      }
      if (kept < maxCopies) {
        kept++;
        continue;
      }
      await fs.rm(join(root, n), { recursive: true, force: true }).catch(() => {});
    }
  }
  return {
    root,
    /** Writes (or reuses, after checking its bytes) the copy of an engine; `{ id, file }`. */
    async put(js, wasm, inUse = new Set()) {
      const id = engineId(hex(js), hex(wasm));
      const dir = join(root, id);
      let ok = false;
      try {
        const [a, b] = await Promise.all([fs.readFile(join(dir, ENGINE_JS)), fs.readFile(join(dir, ENGINE_WASM))]);
        ok = hex(a) === hex(js) && hex(b) === hex(wasm);
      } catch {
        ok = false;
      }
      if (!ok) {
        await fs.mkdir(root, { recursive: true });
        const tmp = await fs.mkdtemp(join(root, '.tmp-'));
        await fs.writeFile(join(tmp, ENGINE_JS), js);
        await fs.writeFile(join(tmp, ENGINE_WASM), wasm);
        // The loader is CommonJS; this keeps any package.json above the storage from deciding how it loads.
        await fs.writeFile(join(tmp, 'package.json'), '{ "type": "commonjs" }\n');
        await fs.rm(dir, { recursive: true, force: true });
        await fs.rename(tmp, dir);
      }
      await fs.writeFile(join(dir, '.used'), String(Date.now()));
      await evict(id, inUse);
      return { id, file: join(dir, ENGINE_JS) };
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
      const m = /id\s*\(?\s*["']org\.julclang\.joust["']\s*\)?\s*version\s*\(?\s*["']([^"']+)["']/.exec(r.bytes.toString('utf8'));
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
    if (project.kind === 'gradle') {
      const v = await gradleVersion(folder, project.rel);
      return advisory(`Gradle project${v ? ` (plugin ${v})` : ''}: the bundled compiler, advisory`, { folder, gradlePlugin: v });
    }
    if (workspaceFileDir && !inside(folder, workspaceFileDir) && !allowedRoots.some((r) => inside(folder, r))) {
      return advisory('this folder lies outside the .code-workspace file\'s directory: its compiler is not loaded', { folder });
    }
    const pkg = await findPackage(folder, project.rel);
    if (!pkg.rel) {
      return advisory(pkg.outside || (await aboveFolder(folder)) ? 'compiler found outside the workspace folder'
        : 'the project\'s @julclang/joust is not installed', { folder });
    }
    const config = (await reader.readJson(folder, [...project.rel, 'joust.config.json'].join('/'))) ?? {};
    // Only `contracts` is taken from joust.config.json, under D4b's rules; never networks, URLs or out.
    const contractsText = typeof config.contracts === 'string' ? config.contracts : 'contracts';
    const contracts = await reader.resolveIn(folder, contractsText, { base: project.rel });
    const js = await reader.read(folder, [...pkg.rel, 'engine', ENGINE_JS].join('/'), { cap: JS_CAP });
    const wasm = await reader.read(folder, [...pkg.rel, 'engine', ENGINE_WASM].join('/'), { cap: WASM_CAP });
    if (js.error || wasm.error) {
      return advisory(js.error === 'outside' || wasm.error === 'outside' || js.error === 'refused' || wasm.error === 'refused'
        ? 'compiler found outside the workspace folder' : 'the project\'s compiler engine is missing or unreadable', { folder });
    }
    const copy = await store.put(js.bytes, wasm.bytes, host.inUse());
    const root = join(folder, ...project.rel);
    const key = workerKey(copy);
    const engine = { file: copy.file, id: copy.id, source: 'project', packageVersion: pkg.version };
    const probe = await host.info(key, engine);
    const info = probe.info;
    if (!probe.ready || !info || info.protocol !== LANG_PROTOCOL || !Array.isArray(info.features) || !info.features.includes('analyze')) {
      host.stop(key);
      return advisory(`the project's compiler (${pkg.version}) predates the language server's protocol: the bundled compiler runs`, { folder });
    }
    engine.version = info.joust ?? pkg.version;
    const inContracts = !contracts.error && inside(realFile, contracts.path);
    return {
      key, engine, source: 'project', folder, root,
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
      const p = selectUncached(realFile).catch((e) => advisory(`the compiler could not be selected (${e?.message ?? e})`));
      cache.set(dir, p);
      return p;
    },
    basename,
  };
}
