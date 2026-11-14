// The .jsc language server's protocol layer (ADR-0021 D2): vscode-languageserver 11.0.0, pinned exactly. It keeps the
// open buffers, debounces, and answers from the document model; it holds no engine (every engine runs in a worker,
// host.js). M5a: diagnostics as you type (the first fatal error, or the warnings), document symbols, folding and
// selection ranges, versioned diagnostics (D4a, L8), modes and engine selection (D3, D3a, D3b, select.js), the input
// policy (D4b, inputs.js), and, for clients that apply edits through a verified path, two quick fixes applied only
// through a versioned workspace/applyEdit (D4a, D7).
import { randomBytes } from 'node:crypto';
import { promises as fsp, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { basename, dirname, isAbsolute, join, posix, relative, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  CancellationTokenSource, CodeActionKind, CodeActionTriggerKind, TextDocumentSyncKind, TextDocuments,
} from 'vscode-languageserver/node';
import { TextDocument } from 'vscode-languageserver-textdocument';
import { EngineHost } from './host.js';
import { createReader } from './inputs.js';
import { scanImports } from './imports.js';
import {
  diagnostics as toDiagnostics, MAX_PUBLISHED, documentSymbols, fixFor, foldingRanges, internalDiagnostic, MODEL_VERSION, parseModel, selectionRanges,
} from './model.js';
import {
  completion, definition, hover, importEdit, reconcile, invisibleOccurrences, references, renamePlan, renamePreserves, semanticTokens, TOKEN_MODIFIERS,
  TOKEN_TYPES,
} from './features.js';
import { inside, localPath } from './paths.js';
import { createSelector, createStore, ENGINE_JS, ENGINE_WASM, engineId, workerKey } from './select.js';
import { createHash } from 'node:crypto';

/** D4: a file over this many characters is not analysed (one diagnostic says so). */
export const MAX_FILE_CHARS = 1024 * 1024;
/** The invisible characters one Remove invisible characters… lists and removes at most (S2 on #99). */
export const MAX_INVISIBLE = 10_000;

/** The project files one analysis reads at most (its imports, transitively; the integration with ADR-0018 M2c, M4). */
export const MAX_PROJECT_FILES = 64;

export const MAX_PROJECT_CHARS = 8 * 1024 * 1024;

/** The import specifiers read from one project file at most (#98 S1): MAX_PROJECT_FILES × 2. */
export const MAX_IMPORTS_PER_FILE = 128;
export const DEBOUNCE_MS = 150;
/** D7: Show generated Java runs the lowering on request only, with its own cap (§12 note 10's latency budget). */
export const LOWER_MS = 10000;
/** D2a, D4: the build worker's limits (joust build's ENGINE_STACK_MB; the build heap of §1), its cap and idle stop. */
export const BUILD_STACK_MB = 8;
export const BUILD_HEAP_MB = 384;
export const BUILD_MS = 120000;
export const BUILD_IDLE_MS = 5 * 60 * 1000;
/** D4: at most this many source files in a build snapshot. */
export const MAX_BUILD_FILES = 500;
/** S7 on #99: at most this many characters in a build snapshot (sources and libraries). */
export const MAX_BUILD_CHARS = 16 * 1024 * 1024;
/** D4: a script over this many bytes is reported on save (a transaction holds at most 16 KB, scripts included). */
export const SCRIPT_WARN_BYTES = 14 * 1024;
export const SERVER_NAME = 'joust-lsp';
const FIX_COMMAND = 'joust.applyFix';
const IMPORT_COMMAND = 'joust.addImport';
/** Opaque ids (fixes, imports, generated documents, invisible-character removals) expire after this. */
const ID_MS = 10 * 60 * 1000;

/**
 * L6 for an edit that should add nothing: every diagnostic of `after` (the edited snapshot's model) is one `before` had,
 * by severity, code and line (lines shifted by the edits' added or removed line ends), except `gone` (the fixed one),
 * which must not be in `after`. `{ ok }`, or `{ ok: false, reason }`.
 */
export function addsNoDiagnostic(before, after, edits, gone = null) {
  const shifts = edits.map((e) => ({ end: e.range.end, delta: (e.newText.match(/\n/g) ?? []).length - (e.range.end.line - e.range.start.line) }));
  // A position at or after an edit's end moves by the line ends the edit adds or removes.
  const lineAfter = (l, c) => l + shifts.filter((x) => l > x.end.line || (l === x.end.line && c >= x.end.character)).reduce((a, x) => a + x.delta, 0);
  const key = (sev, code, line) => `${sev}|${code}|${line}`;
  const had = new Map();
  for (const d of before.diagnostics) {
    if (gone && d.code === gone.code && d.range.join() === gone.range.join()) continue;
    const k = key(d.severity, d.code, lineAfter(d.range[0], d.range[1]));
    had.set(k, (had.get(k) ?? 0) + 1);
  }
  for (const d of after.diagnostics) {
    if (d.internal) return { ok: false, reason: d.message };
    const k = key(d.severity, d.code, d.range[0]);
    const n = had.get(k) ?? 0;
    if (!n) return { ok: false, reason: `it would add ${d.code ?? 'a diagnostic'} at ${d.range[0] + 1}:${d.range[1] + 1}: ${d.message}` };
    had.set(k, n - 1);
  }
  return { ok: true };
}

const hash = (b) => createHash('sha256').update(b).digest('hex');

/**
 * The advertised capabilities (D10's allow-list): nothing else is ever advertised. M5b adds hover, definition,
 * references and semantic tokens to every client; completion, code actions and their commands only to clients that apply
 * edits through a verified, versioned path (D4a: the VS Code extension's handleApplyEdit, or another client enabled by
 * flag); `textDocument/rename` only to such another client (VS Code renames through JOUST: Rename Symbol).
 */
export function capabilities({ edits, rename = false }) {
  return {
    // Open, change, close and save; no willSave and no willSaveWaitUntil (S18).
    textDocumentSync: { openClose: true, change: TextDocumentSyncKind.Incremental, save: { includeText: false } },
    documentSymbolProvider: true,
    foldingRangeProvider: true,
    selectionRangeProvider: true,
    hoverProvider: true,
    definitionProvider: true,
    referencesProvider: true,
    semanticTokensProvider: { legend: { tokenTypes: TOKEN_TYPES, tokenModifiers: TOKEN_MODIFIERS }, full: true },
    // Code actions offered only on Invoked requests (D7, C8).
    ...(edits ? {
      codeActionProvider: { codeActionKinds: [CodeActionKind.QuickFix] },
      executeCommandProvider: { commands: [FIX_COMMAND, IMPORT_COMMAND] },
      completionProvider: { triggerCharacters: ['.'] },
    } : {}),
    ...(edits && rename ? { renameProvider: true } : {}),
    experimental: { joust: { protocol: 1, modelVersion: MODEL_VERSION } },
  };
}

function defaultStorage() {
  const base = process.env.XDG_CACHE_HOME && isAbsolute(process.env.XDG_CACHE_HOME) ? process.env.XDG_CACHE_HOME : join(homedir(), '.cache');
  return join(base, 'joust-lsp');
}

/** Marks a .code-workspace file whose directory does not resolve: every folder fails the rule (S5). */
const UNRESOLVED = Symbol('unresolved');

/** The local filesystem path of a `file:` URI, checked as text first (S4), or null: never a host, device or UNC path. */
function localFileOf(uri) {
  if (typeof uri !== 'string' || !uri.startsWith('file:')) return null;
  let u;
  try {
    u = new URL(uri);
  } catch {
    return null;
  }
  if (u.host !== '' && u.host !== 'localhost') return null;
  let p;
  try {
    p = fileURLToPath(u);
  } catch {
    return null;
  }
  return localPath(p);
}

const fileNameOf = (uri) => {
  const p = localFileOf(uri);
  return p ? basename(p) : 'Untitled.jsc';
};

async function real(p) {
  try {
    return await fsp.realpath(p);
  } catch {
    return null;
  }
}

/**
 * Starts the server on `connection`. `engineDir`: the bundled engine's directory; `workerEntry`: the module a worker
 * runs (worker.js, or the bundle itself); `flags`: projectEngine (other editors: load a trusted project's engine),
 * enableEdits (other editors: quick fixes), testHooks; `hostOptions`: EngineHost overrides (tests).
 */
export function startServer({ connection, engineDir, workerEntry, storageDir: storageOverride, flags = {}, hostOptions = {}, version }) {
  const serverVersion = version ?? JSON.parse(readFileSync(new URL('../../package.json', import.meta.url), 'utf8')).version;
  const documents = new TextDocuments(TextDocument);
  let store;
  // Each worker start marks its engine copy as used, on disk, so other windows sharing the store keep it (S3).
  const host = new EngineHost({
    workerEntry, ...hostOptions, onStart: (engine) => store?.touch?.(engine.id),
    // C18: a worker loading a store copy holds a lease on it; engines outside the store (the bundled one) need none.
    lease: (engine) => (store?.root && engine.file.startsWith(store.root + sep) ? store.lease(engine.id) : () => {}),
  });
  // D2a, D4 (M5b): the build worker, started on the first save or Check build, stopped after 5 idle minutes: joust
  // build's 8 MB stack (ENGINE_STACK_MB) and a 384 MB heap, a 120 s cap, at most one per window.
  const buildHost = new EngineHost({
    workerEntry, stackSizeMb: BUILD_STACK_MB, maxOldGenerationSizeMb: BUILD_HEAP_MB, analysisMs: BUILD_MS, maxWorkers: 1, idleMs: BUILD_IDLE_MS,
    ...(hostOptions.build ?? {}), onStart: (engine) => store?.touch?.(engine.id),
    lease: (engine) => (store?.root && engine.file.startsWith(store.root + sep) ? store.lease(engine.id) : () => {}),
  });
  const reader = createReader();
  // uri -> the state of ONE open lifetime of the document (review C2 on #83): every asynchronous result checks that the
  // state it started with is still the current one, so a close and a reopen at the same version never mix lifetimes.
  const state = new Map();   // uri -> { lastPublished, model, modelVersion, modelGen, outlineModel, timer, cts, selection }
  const fixes = new Map();   // opaque single-use fix id -> { uri, st, version, gen, engineId, code, range, issued }
  const imports = new Map(); // opaque single-use import id -> { uri, st, gen, name, module, issued }
  const generatedDocs = new Map(); // opaque id -> { text, issued } (the Show generated Java documents, D4b)
  const removals = new Map(); // opaque single-use id -> { uri, st, version, gen, ranges, issued }
  const stdCache = new Map(); // engine id -> Promise<catalogue | null>
  let selector;
  let bundled;
  let isVscode = false;
  let edits = false;
  let unavailableShown = false;
  // Q7: builds on save are on in trusted project-matched npm projects unless the client turns them off.
  let buildOnSave = true;

  const log = (m) => connection.console.log(m);
  const missing = new Map();   // engine id -> consecutive missing-copy results (S10)

  /** Everything selected again: every open document re-analysed (C14), outstanding fixes invalid (C4). */
  function reselectAll(delay = 0) {
    selector.invalidate();
    fixes.clear();
    imports.clear();
    removals.clear();
    for (const uri of state.keys()) schedule(uri, delay);
  }

  connection.onInitialize(async (params) => {
    const io = params.initializationOptions ?? {};
    isVscode = io.client === 'vscode-joust';
    if (io.buildOnSave === false) buildOnSave = false;
    // Other editors have no workspace trust: their project's engine loads only with --project-engine (D3b).
    const trusted = isVscode ? io.trusted === true : flags.projectEngine === true;
    const ws = params.capabilities?.workspace ?? {};
    const transactional = ['transactional', 'textOnlyTransactional'].includes(ws.workspaceEdit?.failureHandling);
    edits = (isVscode || flags.enableEdits === true) && ws.applyEdit === true && ws.workspaceEdit?.documentChanges === true && transactional;

    // S4: folder paths are checked as text before any filesystem call; a folder with a host (UNC), a device or extended
    // path, or a URI that is not a local file, is treated as absent.
    const folderPaths = (params.workspaceFolders ?? []).map((f) => localFileOf(f.uri)).filter(Boolean);
    const folders = (await Promise.all(folderPaths.map(real))).filter(Boolean);
    // S5: a .code-workspace file the client names whose directory does not resolve fails closed: no folder outside it
    // (none, then) is eligible.
    let wsFile = null;
    if (io.workspaceFile !== undefined && io.workspaceFile !== null) {
      const f = localPath(io.workspaceFile);
      wsFile = (f && await real(dirname(f))) ?? UNRESOLVED;
    }
    const allowedRoots = (await Promise.all((Array.isArray(io.allowedRoots) ? io.allowedRoots : [])
      .filter((p) => typeof p === 'string' && isAbsolute(p)).map(real))).filter(Boolean);
    // The checked engine copies go to the client's private storage, which must lie outside every workspace folder (L3).
    let storage = typeof io.storageDir === 'string' && isAbsolute(io.storageDir) && isVscode ? io.storageDir : storageOverride ?? defaultStorage();
    const storageReal = await real(storage) ?? storage;
    if (folders.some((f) => inside(storageReal, f))) storage = null;

    const jsBytes = await fsp.readFile(join(engineDir, ENGINE_JS));
    const wasmBytes = await fsp.readFile(join(engineDir, ENGINE_WASM));
    bundled = { file: join(engineDir, ENGINE_JS), id: engineId(hash(jsBytes), hash(wasmBytes)), source: 'bundled' };
    const probe = await host.info(workerKey(bundled), bundled);
    bundled.version = probe.info?.joust ?? probe.version ?? 'unknown';
    if (!probe.ready || !probe.info) log(`the bundled engine has no lang entries: ${probe.error ?? 'no lang.info'}`);

    store = storage ? createStore(storage) : { put: async () => { throw new Error('no private storage outside the workspace'); } };
    selector = createSelector({
      reader, store,
      host, bundled, trusted, folders, workspaceFileDir: wsFile, allowedRoots,
    });
    // L2: serverInfo names the compiler and the mode (of the first folder; each file's is in joust/status).
    let first = { source: 'bundled', engine: bundled, mode: 'advisory', reason: trusted ? 'no folder' : 'restricted' };
    if (folders.length) first = await selector.select(join(folders[0], '.joust-probe.jsc')).catch(() => first);
    return {
      capabilities: capabilities({ edits, rename: !isVscode && edits }),
      // The project's compiler is project-matched for the project's contracts (a file elsewhere is advisory: joust/status).
      serverInfo: { name: SERVER_NAME, version: `${serverVersion}; compiler JOUST ${first.engine.version} (${first.source}, `
        + `${first.source === 'project' ? 'project-matched' : 'advisory'})` },
    };
  });

  const sourceLabel = (sel) => `joust (${sel.source} ${sel.engine.version}, ${sel.mode})`;

  async function selectionFor(uri) {
    const p = localFileOf(uri);
    if (!p) return selector.select(null);
    // A buffer not saved yet: its directory's real path, and its name.
    const r = (await real(p)) ?? (await real(dirname(p)).then((d) => (d ? join(d, basename(p)) : null)));
    return selector.select(r);
  }

  const scopeOf = (sel) => sel.root ?? sel.folder ?? '';

  function statusOf(uri, sel) {
    const h = host.status(sel.key, scopeOf(sel));
    return {
      uri, compiler: sel.engine.version, source: sel.source, mode: sel.mode, reason: sel.reason, trusted: selector.trusted,
      state: h.state === 'unavailable' ? 'unavailable' : 'ok', server: serverVersion,
    };
  }

  function publish(uri, version, diags) {
    const s = state.get(uri);
    if (!s) return;
    // L8: never publish for a version older than one already published (the client ignores the version, D4a).
    if (s.lastPublished != null && version < s.lastPublished) return;
    s.lastPublished = version;
    s.lastDiagnostics = diags;
    // D4 (M5b): a build's diagnostics belong to the version they were computed for; withdrawn once a newer one exists.
    const extra = s.build && s.build.version === version && s.build.gen === selector.generation ? s.build.diagnostics : [];
    connection.sendDiagnostics({ uri, version, diagnostics: [...diags, ...extra].slice(0, MAX_PUBLISHED + 1) });
  }

  function schedule(uri, delay = DEBOUNCE_MS) {
    const s = state.get(uri);
    if (!s) return;
    clearTimeout(s.timer);
    s.timer = setTimeout(() => {
      s.timer = null;
      s.running = (s.running ?? 0) + 1;
      analyse(uri).catch((e) => log(`analysis of ${uri} failed: ${e?.stack ?? e}`)).finally(() => {
        s.running--;
        settle(s);
      });
    }, delay);
  }

  /** Wakes the requests waiting for this lifetime's model (F12). */
  function settle(s) {
    for (const w of (s.waiters ?? []).splice(0)) w();
  }

  /**
   * Review F12 on #83: an outline, folding or selection-range request that arrives while the document's current version
   * has no model yet, and an analysis of it is pending, waits for that analysis (bounded by the analysis cap). VS Code
   * caches the outline per document version, and an early empty answer would stay until the text changed.
   */
  async function waitModel(uri) {
    const end = Date.now() + (host.opts.analysisMs ?? 2000) + DEBOUNCE_MS;
    for (;;) {
      const s = state.get(uri);
      const doc = documents.get(uri);
      if (!s || !doc || (s.model && s.modelVersion === doc.version) || (!s.timer && !s.running)) return;
      const left = end - Date.now();
      if (left <= 0) return;
      await new Promise((r) => {
        (s.waiters ??= []).push(r);
        setTimeout(r, left);
      });
    }
  }

  /**
   * The project files a document's imports reach (ADR-0021 r7a; the .jsc 2 integration, #98): the document's module id
   * under its project's contracts directory, and every .jsc file its relative imports (`import { … } from "./fees.jsc"`,
   * `import contract { … } from "./Oracle.jsc"`) and npm package imports (`"@acme/fees/fees.jsc"`, a package's joust-lib/)
   * reach, transitively, by the build's module ids, so the engine resolves them as the build does.
   *   - The imports are read by scanImports (imports.js): one linear pass by the lexer's rules, comments and strings
   *     skipped (#98 S1, C3), at most MAX_IMPORTS_PER_FILE specifiers a file.
   *   - Each target is confined first (D4b; #98 S2): its path is resolved inside the folder by the reader, which never
   *     resolves a path outside it; an open buffer is used only for that confined path.
   *   - The build's walk rules apply (#92 S12, #98 S3): in an npm project no node_modules or dot-directory segment, and
   *     no link anywhere on the path (the build follows none); in a Gradle project no directory link (Files.walk follows
   *     none). A package library's path follows no link either (libraries.js).
   *   - Bounds: MAX_PROJECT_FILES files, MAX_PROJECT_CHARS characters, MAX_FILE_CHARS each, open buffers included (#98
   *     C8). A bound reached makes the analysis advisory, with its reason (#98 F4).
   *   - A java: import makes the analysis advisory (D3a): the build certifies the library's Java sources, which the
   *     server does not read (M5c).
   * `{ id, files, deps, advisory }` (deps: the real paths read, for rescheduling importers), or null: the file is not in a
   * project's contracts directory (analysed on its own, as before).
   */
  async function projectOf(uri, text, sel) {
    if (!sel.contracts || !sel.folder) return null;
    const file = localFileOf(uri);
    if (!file || !inside(file, sel.folder)) return null;
    const self = await reader.resolveIn(sel.folder, relative(sel.folder, file).split(sep).join('/'));
    if (self.error || !inside(self.path, sel.contracts) || !self.path.endsWith('.jsc')) return null;
    const id = relative(sel.contracts, self.path).split(sep).join('/');
    // The open buffers, by their confined real paths only.
    const open = new Map();
    for (const d of documents.all()) {
      if (d.uri === uri) continue;
      const p = localFileOf(d.uri);
      if (!p || !inside(p, sel.folder)) continue;
      const r = await reader.resolveIn(sel.folder, relative(sel.folder, p).split(sep).join('/'));
      if (!r.error) open.set(r.path, d.getText());
    }
    const files = { [id]: text };
    const deps = new Set();
    let chars = text.length;
    let advisory = null;
    const todo = [id];
    const npm = sel.kind !== 'gradle';
    const PACKAGE = /^(@[a-z0-9][a-z0-9._~-]*\/)?[a-z0-9][a-z0-9._~-]*\/[^\\]+\.jsc$/;
    const pkgOf = (spec) => (spec.startsWith('@') ? spec.split('/').slice(0, 2).join('/') : spec.split('/')[0]);
    const unsafe = (segs) => segs.some((x) => x === '' || x === '.' || x === '..');
    /** `path` (absolute, lexical) resolved inside the folder by D4b's reader, or null; never resolved when outside. */
    const confined = async (path) => {
      if (!inside(path, sel.folder)) return null;
      const r = await reader.resolveIn(sel.folder, relative(sel.folder, path).split(sep).join('/'));
      return r.error ? null : r.path;
    };
    /**
     * The target's real path, confined and under each build's walk rules (#98 C12), or null:
     *   - 'none' (npm project files, as sources()): no link at any segment below `base` (the contracts directory);
     *   - 'files' (Gradle, as Files.walk): no directory link below `base`; the file itself may be a link (confined);
     *   - 'package' (npm package libraries, as libraries.js): the package root (node_modules/<pkg>) may be a link,
     *     resolved inside the folder; nothing below its joust-lib/ is.
     */
    const locate = async (path, { links, base, pkgRoot }) => {
      const r = await confined(path);
      if (!r) return null;
      if (links === 'none') return r === path ? r : null;
      if (links === 'files') {
        const parent = await confined(dirname(path));
        return parent === dirname(path) || (parent && parent === join(base, relative(base, dirname(path)))) ? r : null;
      }
      // 'package'
      const root = await confined(pkgRoot);
      if (!root) return null;
      const below = relative(pkgRoot, path).split(sep);
      return r === join(root, ...below) ? r : null;
    };
    while (todo.length) {
      const at = todo.shift();
      const scanned = scanImports(files[at], MAX_IMPORTS_PER_FILE);
      if (scanned.truncated) advisory ??= `${at} has more than ${MAX_IMPORTS_PER_FILE} imports, more than the language server reads: analysed in part`;
      for (const { spec } of scanned.imports) {
        // #98 S7: as JscModules.join, a specifier with a backslash or '//' names no module: never joined into a path.
        if (spec.includes('\\') || spec.includes('//')) continue;
        if (spec.startsWith('java:')) {
          advisory ??= `a java: import (${spec}): the build certifies the Java library's sources, which the language server does not read yet (M5c)`;
          continue;
        }
        let target;
        let path;
        let links;
        let pkgRoot;
        if (spec.startsWith('./') || spec.startsWith('../')) {
          if (at.startsWith('pkg:')) {
            // Inside a package, a relative import stays inside it (JscModules).
            const inner = at.slice(4);
            const pkg = pkgOf(inner);
            const rel = posix.normalize(posix.join(posix.dirname(inner.slice(pkg.length + 1)), spec));
            if (rel.startsWith('..') || unsafe(rel.split('/'))) continue;
            target = `pkg:${pkg}/${rel}`;
            pkgRoot = join(sel.root ?? sel.contracts, 'node_modules', ...pkg.split('/'));
            path = join(pkgRoot, 'joust-lib', ...rel.split('/'));
            links = 'package';
          } else {
            const rel = posix.normalize(posix.join(posix.dirname(at), spec));
            const segs = rel.split('/');
            if (rel.startsWith('..') || !rel.endsWith('.jsc') || unsafe(segs)) continue;
            // The npm build's sources() skips node_modules and dot-directories (#92 S12): such a file is no project file.
            if (npm && segs.slice(0, -1).some((x) => x === 'node_modules' || x.startsWith('.'))) continue;
            target = rel;
            path = join(sel.contracts, ...segs);
            links = npm ? 'none' : 'files';
          }
        } else if (PACKAGE.test(spec) && !unsafe(spec.split('/')) && sel.root && npm) {
          const pkg = pkgOf(spec);
          target = `pkg:${spec}`;
          pkgRoot = join(sel.root, 'node_modules', ...pkg.split('/'));
          path = join(pkgRoot, 'joust-lib', ...spec.slice(pkg.length + 1).split('/'));
          links = 'package';
        } else {
          continue;   // std/… is the engine's; anything else is the engine's error, as in the build
        }
        if (target in files) continue;
        // #98 C9, N1: every path looked for is an edge, read or not (missing, refused, over a bound): a file that appears,
        // shrinks or comes back later reschedules this importer.
        deps.add(path);
        if (Object.keys(files).length >= MAX_PROJECT_FILES) {
          advisory ??= `the file's imports reach more than ${MAX_PROJECT_FILES} files, more than the language server reads: analysed in part`;
          break;
        }
        const realPath = await locate(path, { links, base: sel.contracts, pkgRoot });
        if (!realPath) continue;   // missing or refused: the engine reports the import as the build does
        deps.add(realPath);
        let body = open.get(realPath);
        if (body === undefined) {
          const r = await reader.read(sel.folder, relative(sel.folder, realPath).split(sep).join('/'), { cap: MAX_FILE_CHARS });
          if (r.error) {
            if (r.error === 'too-large') advisory ??= `${target} is larger than the language server reads (${MAX_FILE_CHARS} bytes): analysed in part`;
            continue;
          }
          body = r.bytes.toString('utf8');
        }
        if (Buffer.byteLength(body, 'utf8') > MAX_FILE_CHARS) {
          advisory ??= `${target} is larger than the language server reads (${MAX_FILE_CHARS} bytes): analysed in part`;
          continue;
        }
        if (chars + body.length > MAX_PROJECT_CHARS) {
          advisory ??= `the file's imports reach more than ${MAX_PROJECT_CHARS} characters, more than the language server reads: analysed in part`;
          break;
        }
        chars += body.length;
        files[target] = body;
        todo.push(target);
      }
    }
    return { id, files, deps, advisory };
  }

  /** Every open document whose last analysis read `realPath` (it imports it, directly or not): analysed again. */
  function rescheduleImporters(paths, delay = DEBOUNCE_MS * 2, except = null) {
    const ps = (Array.isArray(paths) ? paths : [paths]).filter(Boolean);
    if (!ps.length) return;
    for (const [uri, st] of state) if (uri !== except && ps.some((x) => st.deps?.has(x))) schedule(uri, delay);
  }

  /** The confined real path of a document's file, or null. */
  async function realPathOf(uri, sel) {
    const p = localFileOf(uri);
    const folder = sel?.folder;
    if (!p || !folder || !inside(p, folder)) return null;
    const r = await reader.resolveIn(folder, relative(folder, p).split(sep).join('/'));
    return r.error ? null : r.path;
  }

  async function analyse(uri) {
    const doc = documents.get(uri);
    const s = state.get(uri);
    if (!doc || !s) return;
    const version = doc.version;
    const text = doc.getText();
    const gen = selector.generation;
    // Review C13 on #83: each analysis has a sequence number in its lifetime; one that a newer analysis superseded
    // while it selected never touches the state, the status or the newer analysis.
    const seq = s.seq = (s.seq ?? 0) + 1;
    const sel = await selectionFor(uri);
    if (state.get(uri) !== s || s.seq !== seq || documents.get(uri)?.version !== version) return;
    if (selector.generation !== gen) {
      schedule(uri, 0);
      return;
    }
    // The project files its imports reach (r7a); a java: import or a bound reached makes it advisory (D3a; #98 F3, F4).
    // #98 C14: the per-file bound is in UTF-8 bytes, for the document, its open imports and their files on disk alike.
    const tooLarge = Buffer.byteLength(text, 'utf8') > MAX_FILE_CHARS;
    const project = tooLarge ? null : await projectOf(uri, text, sel);
    if (state.get(uri) !== s || s.seq !== seq || documents.get(uri)?.version !== version) return;
    const eff = project?.advisory && sel.mode !== 'advisory' ? { ...sel, mode: 'advisory', reason: project.advisory } : sel;
    s.selection = eff;
    s.deps = project?.deps ?? new Set();
    s.realPath = await realPathOf(uri, sel);
    connection.sendNotification('joust/status', statusOf(uri, eff));
    const label = sourceLabel(eff);
    if (tooLarge) {
      publish(uri, version, [internalDiagnostic(`analysis skipped: this file is larger than the language server reads (${MAX_FILE_CHARS} bytes)`, label)]);
      return;
    }
    // A newer analysis of this document supersedes this one (D2a: cancellation, not failure).
    s.cts?.cancel();
    const cts = new CancellationTokenSource();
    s.cts = cts;
    const file = fileNameOf(uri);
    const r = await host.analyze(sel.key, sel.engine, { file, source: text, scope: scopeOf(sel), ...(project ? { moduleId: project.id, files: project.files } : {}) }, cts.token);
    if (s.cts === cts) s.cts = undefined;
    // D4a: a result is published only if nothing it read changed since: the open lifetime (C2), the buffer's version and
    // the selection.
    const now = documents.get(uri);
    if (state.get(uri) !== s || !now || now.version !== version || s.seq !== seq) return;
    // The selection changed while it ran (C14 on #83): analysed again under the new one, never just dropped.
    if (selector.generation !== gen) {
      schedule(uri, 0);
      return;
    }
    if (r.missing) {
      // The engine copy was evicted (by another window sharing the store): select again for EVERY open document (C14),
      // no failure; with a backoff, and after five misses in a row the file says so instead of retrying (S10).
      const m = missing.get(sel.engine.id) ?? { count: 0 };
      m.count++;
      missing.set(sel.engine.id, m);
      if (m.count > 5) {
        publish(uri, version, [internalDiagnostic(`analysis stopped: the compiler's engine file is missing (${sel.engine.file}); reinstall it, then`
          + ' Restart analysis', label)]);
        return;
      }
      reselectAll(Math.min(250 * 2 ** (m.count - 1), 8000));
      return;
    }
    if (r.cancelled) {
      // Displaced by a stop (eviction, Restart analysis) while still current: analyse again (C3).
      if (r.displaced) schedule(uri, 0);
      return;
    }
    if (r.retryAt) {
      schedule(uri, Math.max(10, r.retryAt - Date.now()));
      return;
    }
    if (r.unavailable) {
      const stale = (s.lastDiagnostics ?? []).map((d) => ({ ...d, message: d.message.endsWith('(stale: JOUST analysis unavailable)') ? d.message
        : `${d.message} (stale: JOUST analysis unavailable)` }));
      connection.sendDiagnostics({ uri, version: s.lastPublished ?? version, diagnostics: stale });
      connection.sendNotification('joust/status', statusOf(uri, eff));
      if (!isVscode && !unavailableShown) {
        unavailableShown = true;
        // A notification, not a request: nothing to wait for, and nothing to fail if the client cannot answer one.
        connection.sendNotification('window/showMessage', { type: 2, message: 'JOUST analysis unavailable: the engine failed repeatedly.'
          + ' Restart the language server to retry.' });
      }
      return;
    }
    if (r.internal) {
      publish(uri, version, [internalDiagnostic(r.internal.message, label)]);
      connection.sendNotification('joust/status', statusOf(uri, eff));
      return;
    }
    let model;
    try {
      model = parseModel(r.model);
    } catch (e) {
      publish(uri, version, [internalDiagnostic(`analysis stopped: ${e.message}`, label)]);
      return;
    }
    missing.delete(sel.engine.id);
    s.model = model;
    s.modelVersion = version;
    s.modelGen = gen;
    // C4, F1 on #99: the last model without an error, with its text: completion maps it onto a newer text it has no
    // complete model of (features.reconcile keeps only what the edit did not touch).
    if (!model.diagnostics.some((d) => d.severity === 'error')) {
      s.goodModel = model;
      s.goodText = text;
      s.goodGen = gen;
    }
    s.modelEngine = sel.engine.id;
    // C9: an accepted model replaces the outline even when it has no declarations (a v1 file, an emptied contract); a
    // file the front end stopped in early keeps the last outline only while its own model has none.
    if (model.declarations.length || model.accepted) s.outlineModel = model;
    // M5b: a file whose imports from other files the language server does not read yet (M5c) is analysed advisory for
    // them: the build checks those imports (L1 holds for snapshots the server reads in full).
    const imports = Array.isArray(model.advisory) && model.advisory.includes('imports');
    publish(uri, version, toDiagnostics(model, imports && sel.mode === 'project-matched' ? `${label.slice(0, -1)}; imports from other files advisory)` : label));
    settle(s);
    // The status says which version has a model (the extension's tests wait for it, F12).
    // #98 C10: the effective selection (a java: import or a bound reached keeps the file advisory, with its reason).
    connection.sendNotification('joust/status', { ...statusOf(uri, eff), modelVersion: version });
    // A selection that failed (not cached) is tried again, a few times, without waiting for an edit (#83 r6 design nit).
    if (/^the compiler could not be selected/.test(sel.reason ?? '') && (s.selectRetries = (s.selectRetries ?? 0) + 1) <= 3) {
      schedule(uri, 1000 * s.selectRetries);
    } else if (!/^the compiler could not be selected/.test(sel.reason ?? '')) {
      s.selectRetries = 0;
    }
  }

  documents.onDidOpen((e) => {
    const old = state.get(e.document.uri);
    if (old) {
      clearTimeout(old.timer);
      settle(old);
    }
    state.set(e.document.uri, { lastPublished: null });
    schedule(e.document.uri, 0);
    // A buffer opened for a file an importer looked for (perhaps missing on disk) is now what it reads (#98 N1).
    rescheduleImporters([localFileOf(e.document.uri)], DEBOUNCE_MS * 2, e.document.uri);
  });
  documents.onDidChangeContent((e) => {
    if (!state.has(e.document.uri)) state.set(e.document.uri, { lastPublished: null });
    const s = state.get(e.document.uri);
    // An analysis of an older version still running is cancelled (after the grace period its worker is replaced).
    s.cts?.cancel();
    // A newer generation: every build result is withdrawn (L8), this document's at its next publish, the others' now
    // (their snapshot read this buffer).
    if (s.build) s.build = null;
    withdrawBuilds(e.document.uri);
    schedule(e.document.uri);
    // The open files that import this one (a library, a contract they read) are analysed again after it, and only they
    // (#98 F4): their last analysis read its path.
    rescheduleImporters([s.realPath, localFileOf(e.document.uri)], DEBOUNCE_MS * 2, e.document.uri);
  });
  documents.onDidClose((e) => {
    const s = state.get(e.document.uri);
    // Its importers read its buffer: they are analysed again with its file on disk (#98 F3d).
    rescheduleImporters([s?.realPath, localFileOf(e.document.uri)], DEBOUNCE_MS * 2, e.document.uri);
    // C7 on #99: a build that read this buffer no longer describes anything open: withdrawn, and one in flight cancelled.
    withdrawBuilds(e.document.uri);
    if (s) {
      clearTimeout(s.timer);
      s.cts?.cancel();
    }
    state.delete(e.document.uri);
    if (s) settle(s);
    // A close ends the lifetime: its unexecuted fixes go, and executing ones fail their identity check (C2).
    for (const [id, f] of fixes) if (f.uri === e.document.uri) fixes.delete(id);
    for (const [id, f] of imports) if (f.uri === e.document.uri) imports.delete(id);
    for (const [id, f] of removals) if (f.uri === e.document.uri) removals.delete(id);
    connection.sendDiagnostics({ uri: e.document.uri, diagnostics: [] });
  });

  const modelOf = (uri) => {
    const s = state.get(uri);
    return s?.outlineModel ?? s?.model;
  };

  connection.onDocumentSymbol(async (p) => {
    await waitModel(p.textDocument.uri);
    const m = modelOf(p.textDocument.uri);
    return m ? documentSymbols(m) : [];
  });
  connection.onFoldingRanges(async (p) => {
    await waitModel(p.textDocument.uri);
    const m = modelOf(p.textDocument.uri);
    return m ? foldingRanges(m) : [];
  });
  connection.onSelectionRanges(async (p) => {
    await waitModel(p.textDocument.uri);
    const doc = documents.get(p.textDocument.uri);
    const m = modelOf(p.textDocument.uri);
    if (!doc || !m) return [];
    const last = doc.positionAt(doc.getText().length);
    return selectionRanges(m, p.positions, last.line, last.character);
  });

  // D7, C8: code actions only for an explicit request (Invoked: the Quick Fix command); every Automatic request, which is
  // how VS Code asks on save whatever editor.codeActionsOnSave says, gets none. Actions carry no edit: only the command
  // joust.applyFix with one opaque, single-use id issued here.
  connection.onCodeAction((p) => {
    if (process.env.JOUST_LSP_DEBUG) log(`codeAction ${p.textDocument.uri} trigger ${p.context?.triggerKind} only ${JSON.stringify(p.context?.only)}`);
    if (!edits || p.context?.triggerKind !== CodeActionTriggerKind.Invoked) return [];
    const doc = documents.get(p.textDocument.uri);
    const s = state.get(p.textDocument.uri);
    // C4: only from a model of the current text AND the current selection generation.
    if (!doc || !s?.model || s.modelVersion !== doc.version || s.modelGen !== selector.generation) return [];
    const out = [];
    for (const d of s.model.diagnostics) {
      if (d.severity !== 'error' || d.internal) continue;
      const r = d.range;
      if (r[0] > p.range.end.line || r[2] < p.range.start.line) continue;
      const fix = fixFor(doc.getText(), d, doc.getText({ start: { line: r[0], character: 0 }, end: { line: r[0] + 1, character: 0 } }));
      if (!fix) continue;
      const id = randomBytes(18).toString('base64url');
      fixes.set(id, { uri: p.textDocument.uri, st: s, version: doc.version, gen: s.modelGen, engineId: s.modelEngine, code: d.code, range: r,
        issued: Date.now() });
      const lsp = toDiagnostics({ diagnostics: [d] }, sourceLabel(s.selection))[0];
      out.push({ title: fix.title, kind: CodeActionKind.QuickFix, diagnostics: [lsp], command: { title: fix.title, command: FIX_COMMAND, arguments: [id] } });
    }
    return out;
  });

  connection.onExecuteCommand(async (p, token) => {
    if (!edits || (p.command !== FIX_COMMAND && p.command !== IMPORT_COMMAND)) return { applied: false, reason: 'unknown command' };
    // S17: exactly one argument, an opaque id this server issued, used once; no edit text, no paths.
    if (!Array.isArray(p.arguments) || p.arguments.length !== 1 || typeof p.arguments[0] !== 'string') return { applied: false, reason: 'refused' };
    return p.command === IMPORT_COMMAND ? addImport(p.arguments[0], token) : applyFix(p.arguments[0], token);
  });

  /** Analyses `source` as the file `uri` with its current selection; the parsed model, or null. */
  async function analyseText(uri, sel, source, token) {
    // With the project's files the document's imports reach, as its analysis has them (else an edit's check would see
    // the file alone and refuse it for its imports).
    const project = await projectOf(uri, source, sel);
    const r = await host.analyze(sel.key, sel.engine, { file: fileNameOf(uri), source, scope: scopeOf(sel),
      ...(project ? { moduleId: project.id, files: project.files } : {}) }, token);
    if (!r.model) return null;
    try {
      return parseModel(r.model);
    } catch {
      return null;
    }
  }

  async function applyFix(id, token) {
    const f = fixes.get(id);
    fixes.delete(id);
    if (!f || Date.now() - f.issued > ID_MS) return { applied: false, reason: 'unknown or used fix' };
    const doc = documents.get(f.uri);
    const s = state.get(f.uri);
    // The same open lifetime (C2), text version and selection generation (C4) as when the fix was offered.
    const current = () => state.get(f.uri) === f.st && documents.get(f.uri)?.version === f.version && selector.generation === f.gen
      && f.st.selection?.engine.id === f.engineId;
    if (!doc || s !== f.st || !current() || !s.model || s.modelVersion !== f.version) return { applied: false, reason: 'stale fix' };
    // Recomputed on the current snapshot, then re-checked by the engine (L6: the diagnostic goes and none is added).
    const d = s.model.diagnostics.find((x) => x.code === f.code && x.range.join() === f.range.join());
    if (!d) return { applied: false, reason: 'stale fix' };
    const text = doc.getText();
    const fix = fixFor(text, d, doc.getText({ start: { line: d.range[0], character: 0 }, end: { line: d.range[0] + 1, character: 0 } }));
    if (!fix) return { applied: false, reason: 'stale fix' };
    const fixed = TextDocument.applyEdits(doc, fix.edits);
    const after = await analyseText(f.uri, s.selection, fixed, token);
    if (!after) return { applied: false, reason: 'the fix could not be checked' };
    if (!current()) return { applied: false, reason: 'stale fix' };
    // M5b's full L6 (recovery reports every error): every diagnostic after the fix is one the file had (by code and line,
    // lines shifted by the fix), and the fixed one is gone. A model without recovery (an M5a engine) holds the first error
    // only, so there the M5a rule stays: no error at or before the fix's position.
    const recovered = s.model.modelVersion >= 2;
    const fixedAt = (d.range[0] + 1) * 1e6 + d.range[1] + 1;
    const at = (x) => x.line * 1e6 + x.col;
    const bad = recovered ? !addsNoDiagnostic(s.model, after, fix.edits, d).ok
      : after.diagnostics.some((x) => x.severity === 'error' && (x.internal || (x.code === d.code && x.range.join() === d.range.join()) || at(x) <= fixedAt));
    if (bad) return { applied: false, reason: 'the fix did not check' };
    if (!current()) return { applied: false, reason: 'stale fix' };
    return applyVerified(f.uri, f.version, text, fix.edits, fix.title);
  }

  /**
   * D4a: an edit is applied by the client only through a versioned workspace/applyEdit (the extension's handleApplyEdit
   * refuses it if the document is closed or has another version). S9, C19 on #83: the SHA-256 of the whole document text
   * the edit was validated on goes along (joustExpected), and the extension's handler compares it with the document right
   * before applying.
   */
  async function applyVerified(uri, version, text, editsToApply, label) {
    const expected = [{ uri, sha256: hash(text) }];
    const res = await connection.workspace.applyEdit({ label, edit: { documentChanges: [{ textDocument: { uri, version },
      edits: editsToApply }] }, joustExpected: expected });
    return { applied: res.applied === true };
  }

  /**
   * joust.addImport (D7, D4a): the import a completion item needs, added after the item was inserted. The id binds the
   * document's open lifetime and the selection generation; the edit is computed on the text at execution, re-checked
   * (it adds no diagnostic), and bound to that text's version.
   */
  async function addImport(id, token) {
    const imp = imports.get(id);
    imports.delete(id);
    if (!imp || Date.now() - imp.issued > ID_MS) return { applied: false, reason: 'unknown or used import' };
    const doc = documents.get(imp.uri);
    if (!doc || state.get(imp.uri) !== imp.st || selector.generation !== imp.gen) return { applied: false, reason: 'stale import' };
    const version = doc.version;
    const text = doc.getText();
    const e = importEdit(text, imp.name, imp.module);
    if (!e) return { applied: false, reason: 'already imported' };
    const sel = imp.st.selection;
    const current = () => state.get(imp.uri) === imp.st && documents.get(imp.uri)?.version === version && selector.generation === imp.gen;
    const before = await analyseText(imp.uri, sel, text, token);
    const after = before && await analyseText(imp.uri, sel, TextDocument.applyEdits(doc, [e]), token);
    if (!before || !after) return { applied: false, reason: 'the import could not be checked' };
    if (!current()) return { applied: false, reason: 'stale import' };
    // Every JSC0704 for this name goes; nothing else may change.
    const keep = { ...before, diagnostics: before.diagnostics.filter((d) => !(d.code === 'JSC0704' && d.message.startsWith(`${imp.name} is in ${imp.module}:`))) };
    if (!addsNoDiagnostic(keep, after, [e]).ok) return { applied: false, reason: 'the import did not check' };
    return applyVerified(imp.uri, version, text, [e], `Import ${imp.name} from "${imp.module}"`);
  }

  // ------------------------------------------------------------------ M5b: lookups in the model (D7)

  /** The model of the current text and selection, or null (edits are computed only from one, D4a). */
  function freshModel(uri) {
    const doc = documents.get(uri);
    const s = state.get(uri);
    if (!doc || !s?.model || s.modelVersion !== doc.version || s.modelGen !== selector.generation) return null;
    return { doc, s, model: s.model };
  }

  /** The engine's std catalogue ({ rows, types, members, modifiers }), once per engine; null for an engine without one. */
  function stdOf(sel) {
    if (!sel) return Promise.resolve(null);
    let p = stdCache.get(sel.engine.id);
    if (!p) {
      p = host.request(sel.key, sel.engine, { op: 'std' }, { scope: scopeOf(sel), input: 'std', what: 'the std catalogue' }).then((r) => {
        if (r.model === null) return null;   // an engine without lang.std: nothing to ask again
        if (typeof r.model !== 'string') {
          stdCache.delete(sel.engine.id);
          return null;
        }
        return JSON.parse(r.model);
      }).catch(() => {
        stdCache.delete(sel.engine.id);
        return null;
      });
      stdCache.set(sel.engine.id, p);
    }
    return p;
  }

  // C4 on #99: hover, definition, references and completion answer from the model of the version they are asked about
  // (waiting for its analysis, bounded as waitModel is), never from an older one: names and ranges move with every edit.
  async function currentModel(uri) {
    await waitModel(uri);
    return freshModel(uri);
  }
  connection.onHover(async (p) => {
    const f = await currentModel(p.textDocument.uri);
    if (!f) return null;
    const std = await stdOf(f.s.selection);
    return freshModel(p.textDocument.uri)?.model === f.model ? hover(f.model, p.position, std) : null;
  });
  connection.onDefinition(async (p) => {
    const f = await currentModel(p.textDocument.uri);
    return f ? definition(f.model, p.textDocument.uri, p.position) : null;
  });
  connection.onReferences(async (p) => {
    const f = await currentModel(p.textDocument.uri);
    return f ? references(f.model, p.textDocument.uri, p.position, p.context?.includeDeclaration === true) : [];
  });
  connection.languages.semanticTokens.on(async (p) => {
    await waitModel(p.textDocument.uri);
    const m = state.get(p.textDocument.uri)?.model;
    return m ? semanticTokens(m) : { data: [] };
  });

  // D7, D4a (C13): computed for the version the server holds when the request arrives and dropped if a newer one has
  // arrived; text edits replace only the word being completed; an import an item needs comes from its joust.addImport
  // command (an opaque, single-use id), never from additionalTextEdits.
  connection.onCompletion(async (p) => {
    if (!edits) return null;
    const uri = p.textDocument.uri;
    const doc = documents.get(uri);
    const s = state.get(uri);
    if (!doc || !s) return null;
    const version = doc.version;
    // C4 on #99: the model of this version (waited for); without one, no name of the file is offered (only keywords,
    // built-ins and std), and the list says it is incomplete.
    const fresh = await currentModel(uri);
    const std = (await stdOf(s.selection)) ?? {};
    const now = documents.get(uri);
    if (!now || now.version !== version || state.get(uri) !== s) return null;
    const current = freshModel(uri);
    const usable = fresh && current && current.model === fresh.model ? fresh.model : null;
    // A model with an error (a member recovery dropped or stopped checking, the usual state while typing) or none: the
    // last model without one, reconciled with this text (C4, F1).
    const good = s.goodModel && s.goodGen === selector.generation && (!usable || usable.diagnostics.some((d) => d.severity === 'error'))
      ? reconcile(s.goodModel, s.goodText, now.getText()) : null;
    // The previous list's import ids go: only the latest list's items can still be chosen.
    for (const [id, x] of imports) if (x.uri === uri || Date.now() - x.issued > ID_MS) imports.delete(id);
    const model = good ?? usable ?? { declarations: [], references: [], builtins: [], exprs: [], types: [], diagnostics: [] };
    const items = completion(now.getText(), p.position, model, std).map((it) => {
      if (!it.needsImport) return it;
      const { needsImport, ...rest } = it;
      const id = randomBytes(18).toString('base64url');
      imports.set(id, { uri, st: s, gen: selector.generation, ...needsImport, issued: Date.now() });
      return { ...rest, command: { title: `Import ${needsImport.name}`, command: IMPORT_COMMAND, arguments: [id] } };
    });
    return { isIncomplete: !usable && !good, items };
  });

  // ------------------------------------------------------------------ M5b: rename (D7, L6)

  /** The rename at `position`, validated on the current snapshot: `{ plan, version, text, st }` or `{ error }`. */
  async function validatedRename(uri, position, newName, token) {
    const f = freshModel(uri);
    if (!f) return { error: 'the file changed or is still being analysed: try again' };
    if (f.model.modelVersion < 2) return { error: 'this compiler predates rename (JOUST M5b)' };
    const plan = renamePlan(f.model, position, newName);
    if (plan.error) return plan;
    // F2 on #99: a name other files can see is renamed only when no project file imports this one (renaming across
    // files comes with M5c's project index).
    if (plan.visible) {
      const imp = await importersOf(uri, f.s.selection ?? await selectionFor(uri));
      if (imp.error) return { error: `'${plan.decl.name}' can be named by other files, and ${imp.error}: rename it by hand (renaming across files comes with M5c)` };
      if (imp.importers.length) {
        return { error: `'${plan.decl.name}' can be named by the files that import this one (${imp.importers.slice(0, 3).join(', ')}${imp.importers.length > 3 ? ', …' : ''}):`
          + ' renaming across files comes with M5c; rename it by hand' };
      }
    }
    const version = f.doc.version;
    const text = f.doc.getText();
    const after = await analyseText(uri, f.s.selection, TextDocument.applyEdits(f.doc, plan.edits), token);
    if (!after) return { error: 'the rename could not be checked' };
    const ok = renamePreserves(f.model, after, plan.edits);
    if (!ok.ok) return { error: ok.reason };
    if (!freshModel(uri) || documents.get(uri)?.version !== version) return { error: 'the file changed: try again' };
    return { plan, version, text, st: f.s };
  }

  const abiWarning = (d) => `'${d.name}' is recorded in the ABI manifest: renaming it changes the manifest, the generated client and the`
    + ' description (the script does not change), and the next build\'s ABI gate asks you to accept the new manifest (Accept ABI…).';

  // VS Code: JOUST: Rename Symbol sends this; the server validates and applies through workspace/applyEdit (D4a).
  connection.onRequest('joust/rename', async (p, token) => {
    if (!edits) return { applied: false, error: 'edits are off for this client' };
    if (!p || typeof p.uri !== 'string' || typeof p.newName !== 'string' || !p.position) return { applied: false, error: 'refused' };
    const r = await validatedRename(p.uri, p.position, p.newName, token);
    if (r.error) return { applied: false, error: r.error };
    if (r.plan.abi && p.confirm !== true) return { applied: false, needsConfirmation: true, warning: abiWarning(r.plan.decl) };
    return applyVerified(p.uri, r.version, r.text, r.plan.edits, `Rename '${r.plan.decl.name}' to '${p.newName}'`);
  });
  connection.onRequest('joust/prepareRename', (p) => {
    const f = p && freshModel(p.uri);
    if (!f) return { error: 'the file changed or is still being analysed: try again' };
    const plan = renamePlan(f.model, p.position, '__joust_probe__');
    return plan.error && !plan.decl ? { error: plan.error } : { name: plan.decl.name, kind: plan.decl.kind, abi: plan.abi };
  });

  // Other editors with edits enabled (flag): the standard rename, versioned (D4a); an ABI-recorded name is refused there,
  // since no warning can come first.
  // S8 on #99: registered whatever the client (isVscode is known only at initialize); VS Code gets no rename capability,
  // and the handler refuses there too.
  {
    connection.onRenameRequest(async (p, token) => {
      if (!edits || isVscode) return null;
      const r = await validatedRename(p.textDocument.uri, p.position, p.newName, token);
      if (r.error) throw new Error(r.error);
      if (r.plan.abi) throw new Error(`${abiWarning(r.plan.decl)} Rename it in VS Code (with its warning), or by hand.`);
      return { documentChanges: [{ textDocument: { uri: p.textDocument.uri, version: r.version }, edits: r.plan.edits }] };
    });
  }

  // ------------------------------------------------------------------ M5b: Remove invisible characters… (D7)

  // Lists every invisible or control character (JSC0145, JSC0148) of the current text; the extension shows them in a modal
  // and only on confirmation sends joust/removeInvisible with the single-use id, which deletes exactly those characters
  // from exactly that version.
  connection.onRequest('joust/invisible', (p) => {
    const f = p && freshModel(p.uri);
    if (!f) return { error: 'the file changed or is still being analysed: try again' };
    const all = invisibleOccurrences(f.model, f.doc.getText());
    if (!all.length) return { occurrences: [] };
    // Bounded (S2 on #99): the first MAX_INVISIBLE, the count of the rest said; a second run removes them.
    const occurrences = all.slice(0, MAX_INVISIBLE);
    for (const [k, x] of removals) if (x.uri === p.uri || Date.now() - x.issued > ID_MS) removals.delete(k);
    const id = randomBytes(18).toString('base64url');
    removals.set(id, { uri: p.uri, st: f.s, version: f.doc.version, gen: selector.generation, ranges: occurrences.map((o) => o.range), issued: Date.now() });
    return { id, version: f.doc.version, occurrences: occurrences.map(({ range, ...o }) => o), more: all.length - occurrences.length };
  });
  connection.onRequest('joust/removeInvisible', async (p) => {
    if (!edits) return { applied: false, error: 'edits are off for this client' };
    const x = p && typeof p.id === 'string' && removals.get(p.id);
    if (p && typeof p.id === 'string') removals.delete(p.id);
    if (!x || Date.now() - x.issued > ID_MS) return { applied: false, error: 'unknown or used id' };
    const doc = documents.get(x.uri);
    if (!doc || state.get(x.uri) !== x.st || doc.version !== x.version || selector.generation !== x.gen) return { applied: false, error: 'the file changed' };
    const del = x.ranges.map((r) => ({ range: { start: { line: r[0], character: r[1] }, end: { line: r[2], character: r[3] } }, newText: '' }));
    return applyVerified(x.uri, x.version, doc.getText(), del, 'Remove invisible characters');
  });

  // ------------------------------------------------------------------ M5b: Show generated Java (D7)

  // On request only (§12 note 10): the current text lowered by the file's compiler (lang.lower), kept under an opaque id
  // and served as a read-only document (joust-gen:, D4b), with the Java line the source map gives for the cursor.
  connection.onRequest('joust/generatedJava', async (p, token) => {
    const doc = p && documents.get(p.uri);
    const s = p && state.get(p.uri);
    if (!doc || !s) return { error: 'the file is not open' };
    const sel = s.selection ?? await selectionFor(p.uri);
    const text = doc.getText();
    if (text.length > MAX_FILE_CHARS) return { error: 'the file is larger than the language server reads' };
    const project = await projectOf(p.uri, text, sel);
    const r = await host.request(sel.key, sel.engine, { op: 'lower', file: fileNameOf(p.uri), source: text, ...(project ? { moduleId: project.id, files: project.files } : {}) },
      { scope: scopeOf(sel), input: project ? JSON.stringify([text, project.id, Object.entries(project.files).sort()]) : text, what: 'the lowering',
        timeoutMs: LOWER_MS }, token);
    if (!r.model) return { error: r.internal?.message ?? (r.cancelled ? 'cancelled' : 'the lowering is unavailable') };
    const g = JSON.parse(r.model);
    if (!g.ok) return { error: g.error ?? 'the file does not lower', diagnostic: g.diagnostic ?? null };
    const keep = [...generatedDocs.entries()].sort((a, b) => b[1].issued - a[1].issued).slice(19);
    for (const [k] of keep) generatedDocs.delete(k);
    const id = randomBytes(18).toString('base64url');
    generatedDocs.set(id, { text: g.java, issued: Date.now(), javaFile: g.javaFile });
    const want = (p.position?.line ?? 0) + 1;
    // The Java line of the nearest mapped .jsc line at or before the cursor's.
    let line = 0;
    let best = -1;
    for (const [jscLine, javaLine] of g.lines ?? []) {
      if (jscLine <= want && jscLine > best) {
        best = jscLine;
        line = javaLine - 1;
      }
    }
    return { id, javaFile: g.javaFile, line: Math.max(0, line), contract: g.contract };
  });
  connection.onRequest('joust/generatedContent', (p) => {
    const g = p && typeof p.id === 'string' && generatedDocs.get(p.id);
    return g ? { text: g.text, javaFile: g.javaFile } : { error: 'this generated document is gone: show it again' };
  });

  // ------------------------------------------------------------------ M5b: builds on save, in memory (D4)

  let buildSeq = 0;
  const builtFrom = new Map();   // uri of an open document -> the build seq that read it

  const inFlight = new Map();   // build seq -> { uris (the open buffers its snapshot read), cts }

  /** Withdraws every build result whose snapshot read `uri` (it changed); a build in flight that read it is cancelled (F7). */
  function withdrawBuilds(uri) {
    for (const b of inFlight.values()) if (b.uris.has(uri)) b.cts?.cancel();
    const seq = builtFrom.get(uri);
    if (seq === undefined) return;
    builtFrom.delete(uri);
    for (const [u, st] of state) {
      if (st.build && st.build.seq === seq) {
        st.build = null;
        if (u !== uri && st.lastPublished != null) connection.sendDiagnostics({ uri: u, version: st.lastPublished, diagnostics: st.lastDiagnostics ?? [] });
      }
    }
  }

  /**
   * The sources a build of `sel` reads: every .jsc and .java under the project's contracts directory (D4b: confined,
   * regular files, capped, no directory symlinks), each open buffer in place of its file. `{ files, versions, dir }`
   * (versions: uri -> version of every open buffer read) or `{ error }`. Without a contracts directory (advisory, or
   * restricted mode), the one file `uri`.
   */
  async function buildSnapshot(uri, sel) {
    const files = {};
    const versions = new Map();
    // C7 on #99: each open buffer's lifetime (its state object) with its version: a close and a reopen at the same
    // version is another buffer.
    const states = new Map();
    let chars = 0;
    const digest = createHash('sha256');
    const keep = (f, text) => {
      chars += text.length;
      digest.update(`${f.length}:${f}${text.length}:`).update(text);
    };
    const openByPath = new Map();
    for (const d of documents.all()) {
      const p = localFileOf(d.uri);
      if (p) openByPath.set((await real(p)) ?? p, d);
    }
    if (!sel.contracts || !sel.folder || !sel.contractsRel) {
      const doc = documents.get(uri);
      if (!doc) return { error: 'the file is not open' };
      files[fileNameOf(uri)] = doc.getText();
      versions.set(uri, doc.version);
      states.set(uri, state.get(uri));
      keep(fileNameOf(uri), doc.getText());
      return { files, versions, states, dir: null, packages: {}, javaLibraries: {}, digest: digest.digest('hex') };
    }
    const rel = sel.contractsRel.join('/');
    const listed = await reader.list(sel.folder, rel, { max: MAX_BUILD_FILES, kind: sel.kind === 'gradle' ? 'gradle' : 'npm' });
    if (listed.error) return { error: listed.error === 'too-many' ? `more than ${MAX_BUILD_FILES} source files` : `the contracts directory cannot be read (${listed.error})` };
    for (const f of listed.files) {
      const full = join(listed.path, ...f.split('/'));
      const open = openByPath.get(full);
      if (open) {
        files[f] = open.getText();
        versions.set(open.uri, open.version);
        states.set(open.uri, state.get(open.uri));
      } else {
        const r = await reader.read(sel.folder, `${rel}/${f}`, { cap: MAX_FILE_CHARS * 2 });
        if (r.error) return { error: `${f} cannot be read (${r.error})` };
        files[f] = r.bytes.toString('utf8');
      }
      keep(f, files[f]);
      // S7 on #99: the snapshot's total is bounded too.
      if (chars > MAX_BUILD_CHARS) return { error: `the project's sources are larger than the language server builds (${MAX_BUILD_CHARS} characters)` };
    }
    // C6 on #99: the npm packages' libraries (joust-lib/: .jsc by <package>/<path>, Java sources by path), as joust
    // build passes them (libraries.js), read confined: no link, inside the folder, under the same bounds.
    const libs = sel.kind === 'gradle' ? { packages: {}, javaLibraries: {} } : await buildLibraries(sel);
    if (libs.error) return { error: libs.error };
    for (const [k, v] of [...Object.entries(libs.packages), ...Object.entries(libs.javaLibraries)]) {
      keep(`lib:${k}`, v);
      if (chars > MAX_BUILD_CHARS) return { error: `the project's sources and libraries are larger than the language server builds (${MAX_BUILD_CHARS} characters)` };
    }
    return { files, versions, states, dir: listed.path, packages: libs.packages, javaLibraries: libs.javaLibraries, digest: digest.digest('hex') };
  }

  /**
   * C6 on #99: the libraries the project's npm packages ship under joust-lib/ (libraries.js libraryPackages' rules: every
   * package of node_modules, scoped ones included, its joust-lib/'s .jsc and .java files), read through the confined
   * reader: a package root may be a link (confined); a joust-lib/ or anything below it reached by a link is left out
   * (as projectOf's package imports). `{ packages, javaLibraries }` or `{ error }` (one Java class shipped twice: JSC0709; a bound).
   */
  async function buildLibraries(sel) {
    const packages = {};
    const javaLibraries = {};
    const javaFrom = {};
    if (!sel.root || !inside(sel.root, sel.folder)) return { packages, javaLibraries };
    const rootRel = relative(sel.folder, sel.root).split(sep).join('/') || '.';
    const dirs = async (rel) => {
      const r = await reader.resolveIn(sel.folder, rel);
      if (r.error) return [];
      try {
        // A package may be a link (resolved confined later); a scope (@x) is a directory, as libraries.js reads them.
        return (await fsp.readdir(r.path, { withFileTypes: true }))
          .filter((e) => !e.name.startsWith('.') && (e.isDirectory() || (e.isSymbolicLink() && !e.name.startsWith('@')))).map((e) => e.name).sort();
      } catch {
        return [];
      }
    };
    const names = [];
    for (const n of await dirs(`${rootRel}/node_modules`)) {
      if (n.startsWith('@')) for (const m of await dirs(`${rootRel}/node_modules/${n}`)) names.push(`${n}/${m}`);
      else names.push(n);
    }
    let count = 0;
    for (const name of names) {
      // As libraries.js and projectOf's package imports (#98 C12): the package root may be a link, resolved inside the
      // folder; joust-lib/ and nothing below it may be one.
      const pkgRel = `${rootRel}/node_modules/${name}`;
      const libRel = `${pkgRel}/joust-lib`;
      const root = await reader.resolveIn(sel.folder, pkgRel);
      const lib = await reader.resolveIn(sel.folder, libRel);
      if (root.error || lib.error || lib.path !== join(root.path, 'joust-lib')) continue;   // missing, or a link
      const listed = await reader.list(sel.folder, libRel, { max: MAX_BUILD_FILES, kind: 'gradle' });
      if (listed.error) return { error: listed.error === 'too-many' ? `the package ${name} ships more than ${MAX_BUILD_FILES} library files` : `${name}/joust-lib cannot be read` };
      for (const f of listed.files) {
        if (++count > MAX_BUILD_FILES) return { error: `the npm packages ship more than ${MAX_BUILD_FILES} library files` };
        const r = await reader.read(sel.folder, `${libRel}/${f}`, { cap: MAX_FILE_CHARS * 2 });
        if (r.error) return { error: `${name}/joust-lib/${f} cannot be read (${r.error})` };
        const text = r.bytes.toString('utf8');
        if (f.endsWith('.jsc')) packages[`${name}/${f}`] = text;
        else if (f.endsWith('.java')) {
          if (f in javaLibraries) return { error: `error[JSC0709]: the npm packages ${javaFrom[f]} and ${name} both ship joust-lib/${f}: one Java class twice` };
          javaLibraries[f] = text;
          javaFrom[f] = name;
        }
      }
    }
    return { packages, javaLibraries };
  }

  /**
   * F2 on #99: the project files whose imports name the file `uri` (relative imports resolved as projectOf does; the
   * project's sources, or the folder's without a known project, listed by the build's walk, open buffers in place of
   * their files, each read bounded): `{ importers }` (paths), or `{ error }` when they cannot all be read (too many
   * files, a file too large).
   */
  async function importersOf(uri, sel) {
    if (!sel?.folder) return { error: 'the file is outside every workspace folder' };
    // The project's contracts directory, or (no project known: a bare folder, Restricted Mode) the whole folder.
    const project = sel.contracts && sel.contractsRel;
    const base = project ? sel.contracts : sel.folder;
    // A buffer not on disk yet is named by its own path (importers name it so).
    const own = localFileOf(uri);
    const self = (await realPathOf(uri, sel)) ?? (own && inside(own, sel.folder) ? own : null);
    if (!self || !inside(self, base)) return { error: 'the file is outside its project\'s contracts directory' };
    const id = relative(base, self).split(sep).join('/');
    const rel = project ? sel.contractsRel.join('/') : '.';
    const listed = await reader.list(sel.folder, rel, { exts: ['.jsc'], max: MAX_BUILD_FILES, kind: sel.kind === 'gradle' ? 'gradle' : 'npm' });
    if (listed.error) return { error: listed.error === 'too-many' ? `the project has more than ${MAX_BUILD_FILES} source files` : 'the contracts directory cannot be read' };
    const open = new Map();
    for (const d of documents.all()) {
      const p = await realPathOf(d.uri, sel);
      if (p) open.set(p, d.getText());
    }
    const importers = [];
    for (const f of listed.files) {
      if (f === id) continue;
      const full = join(listed.path, ...f.split('/'));
      let text = open.get(full);
      if (text === undefined) {
        const r = await reader.read(sel.folder, `${rel}/${f}`, { cap: MAX_FILE_CHARS });
        if (r.error) return { error: `${f} cannot be read (${r.error})` };
        text = r.bytes.toString('utf8');
      }
      const scanned = scanImports(text, MAX_IMPORTS_PER_FILE);
      if (scanned.truncated) return { error: `${f} has more imports than the language server reads` };
      for (const { spec } of scanned.imports) {
        if (!spec.startsWith('./') && !spec.startsWith('../')) continue;
        if (posix.normalize(posix.join(posix.dirname(f), spec)) === id) {
          importers.push(f);
          break;
        }
      }
    }
    return { importers };
  }

  /** `file://` URI of a build diagnostic's file (relative to the snapshot's directory), or null. */
  const uriOfBuilt = (dir, f, fallback) => {
    if (!dir) return fallback;
    if (typeof f !== 'string' || !f || f.includes('..')) return null;
    return pathToFileURL(join(dir, ...f.replace(/\\/g, '/').split('/'))).toString();
  };

  /**
   * One build of the snapshot on the build worker (writes nothing: no build/joust/, no generated Java, no manifest):
   * the later stages' diagnostics (processor, JuLC, the ABI comparison with each committed manifest, script size), each
   * published with its document's version, and dropped when anything the snapshot read changed meanwhile (D4a).
   */
  async function runBuild(uri, sel, engine, token) {
    const seq = ++buildSeq;
    const gen = selector.generation;
    const snap = await buildSnapshot(uri, sel);
    if (snap.error) return { error: snap.error };
    if (token?.isCancellationRequested) return { error: 'cancelled' };
    inFlight.set(seq, { uris: new Set(snap.versions.keys()), cts: token?.cts });
    let r;
    try {
      r = await buildHost.request(`build:${engine.id}`, engine,
        { op: 'build', files: snap.files, options: {}, packages: snap.packages, javaLibraries: snap.javaLibraries },
        { scope: sel.root ?? sel.folder ?? '', input: snap.digest, what: 'the build', timeoutMs: BUILD_MS }, token);
    } finally {
      inFlight.delete(seq);
    }
    // C7 on #99: every buffer the snapshot read is still the same lifetime at the same version.
    const stale = () => selector.generation !== gen
      || [...snap.versions].some(([u, v]) => documents.get(u)?.version !== v || state.get(u) !== snap.states.get(u));
    if (!r.model) return { error: r.internal?.message ?? (r.cancelled ? 'cancelled' : r.unavailable ? 'builds are unavailable' : 'the build did not run') };
    if (stale()) return { error: 'the sources changed while it built: not shown' };
    const result = JSON.parse(r.model);
    const label = `joust build (${sel.source} ${engine.version}, in memory)`;
    const byUri = new Map();
    const add = (u, d) => {
      if (!u) return;
      if (!byUri.has(u)) byUri.set(u, []);
      byUri.get(u).push(d);
    };
    let errors = 0;
    for (const d of result.diagnostics ?? []) {
      const line = Math.max(0, Number(d.line ?? 1) - 1);
      const col = Math.max(0, Number(d.col ?? 1) - 1);
      const target = uriOfBuilt(snap.dir, d.file, uri);
      // The front end's own diagnostics are the analysis's already, where that document's analysis shows the same code on
      // the same line (C6 on #99: a front-end error the analysis does not show, a library the analysis did not read say,
      // is the build's explanation, and is shown).
      const frontEnd = d.source === 'jsc' || (d.code && /^JSC0[1-9]/.test(d.code) && d.source !== 'julc' && d.source !== 'javac');
      if (frontEnd && (state.get(target)?.lastDiagnostics ?? []).some((x) => x.code === d.code && x.range?.start?.line === line)) continue;
      if (d.severity === 'error') errors++;
      add(target ?? uri, { range: { start: { line, character: col }, end: { line, character: col + 1 } },
        severity: d.severity === 'error' ? 1 : d.severity === 'warning' ? 2 : 3, ...(d.code ? { code: d.code } : {}), source: label,
        message: `${d.message}${d.source ? ` (${d.source})` : ''}` });
    }
    // The ABI comparison with the committed <Contract>.abi.json beside each .jsc file (JSC0002, JSC0003); never accepted here.
    for (const g of result.generated ?? []) {
      if (!g.abi || !snap.dir) continue;
      const name = g.javaFile.split('/').pop().replace(/\.java$/, '');
      const jscDir = g.jscFile.includes('/') ? g.jscFile.slice(0, g.jscFile.lastIndexOf('/') + 1) : '';
      const committed = await reader.read(sel.folder, `${sel.contractsRel.join('/')}/${jscDir}${name}.abi.json`, { cap: 4 * 1024 * 1024 });
      const old = committed.error ? null : committed.bytes.toString('utf8');
      if (old === g.abi) continue;
      errors++;
      add(uriOfBuilt(snap.dir, g.jscFile, uri), { range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } }, severity: 1,
        code: old === null ? 'JSC0003' : 'JSC0002', source: label,
        message: old === null ? `no ABI manifest ${name}.abi.json: the encoding of the datum and redeemers must be committed; create it with Accept ABI… (joust build --accept-abi), review it and commit it`
          : `the ABI manifest differs from the committed ${name}.abi.json: a datum or redeemer encoding changed, which strands deployed instances; if it is intended, run Accept ABI… and commit the new manifest` });
    }
    for (const c of result.contracts ?? []) {
      if (c.scriptSizeBytes > SCRIPT_WARN_BYTES) {
        const g = (result.generated ?? []).find((x) => x.javaFile === `${c.packageName.replaceAll('.', '/')}/${c.name}.java`);
        add(g ? uriOfBuilt(snap.dir, g.jscFile, uri) : uri, { range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } }, severity: 2,
          source: label, message: `${c.name}'s script is ${c.scriptSizeBytes} bytes: a transaction holds at most 16 KB, scripts included (deploy it as a reference script)` });
      }
    }
    // A failed build always says why (C6): with no error of its own to show, it says so where it was asked.
    if (result.ok !== true && errors === 0) {
      errors++;
      const first = (result.diagnostics ?? []).find((d) => d.severity === 'error');
      add(uri, { range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } }, severity: 1, source: label,
        message: `the build failed${first ? `: ${first.file ? `${first.file}:${first.line ?? 1}: ` : ''}${first.message}` : ' without a diagnostic'}` });
    }
    if (stale()) return { error: 'the sources changed while it built: not shown' };
    // Published for every open document the snapshot read (or the build named), each with the version it read.
    const targets = new Set([...snap.versions.keys(), ...byUri.keys()]);
    for (const u of targets) {
      const st = state.get(u);
      const doc = documents.get(u);
      if (!st || !doc) continue;
      st.build = { seq, gen, version: snap.versions.get(u) ?? doc.version, diagnostics: byUri.get(u) ?? [] };
      builtFrom.set(u, seq);
      if (st.lastPublished === st.build.version) {
        connection.sendDiagnostics({ uri: u, version: st.lastPublished, diagnostics: [...(st.lastDiagnostics ?? []), ...st.build.diagnostics].slice(0, MAX_PUBLISHED + 1) });
      }
    }
    return { ok: result.ok === true && errors === 0, errors, contracts: (result.contracts ?? []).length };
  }

  // D4, D10, Q7: on save, in trusted project-matched npm projects only (a user setting can turn it off).
  // F7 on #99: coalesced: one build in flight and at most one pending (the latest save's); a build whose inputs change
  // while it runs is cancelled (withdrawBuilds).
  let saveRunning = false;
  let savePending = null;
  async function buildOnSaveLoop(next) {
    savePending = next;
    if (saveRunning) return;
    saveRunning = true;
    try {
      while (savePending) {
        const { uri, sel } = savePending;
        savePending = null;
        if (!state.has(uri)) continue;
        const cts = new CancellationTokenSource();
        const token = Object.assign(cts.token, { cts });
        await runBuild(uri, sel, sel.engine, token).catch((err) => log(`build on save failed: ${err?.stack ?? err}`));
        cts.dispose();
      }
    } finally {
      saveRunning = false;
    }
  }
  documents.onDidSave((e) => {
    const s = state.get(e.document.uri);
    const sel = s?.selection;
    if (!sel || buildOnSave === false || !selector.trusted || sel.source !== 'project' || sel.mode !== 'project-matched' || !sel.contracts) return;
    buildOnSaveLoop({ uri: e.document.uri, sel });
  });

  // JOUST: Check build (in memory): one build on request, on the bundled engine unless the file is project-matched, in any
  // mode, under the same caps (D4, D10).
  connection.onRequest('joust/checkBuild', async (p, token) => {
    const s = p && state.get(p.uri);
    if (!s) return { error: 'the file is not open' };
    const sel = s.selection ?? await selectionFor(p.uri);
    const engine = sel.source === 'project' && sel.mode === 'project-matched' ? sel.engine : bundled;
    return runBuild(p.uri, sel, engine, token);
  });

  connection.onRequest('joust/status', async (p) => {
    const uri = p?.uri;
    if (uri && documents.get(uri)) {
      // #98 C10: the current analysis's effective selection when it is for the current selection; else selected now.
      const sel = await selectionFor(uri);
      const st = state.get(uri);
      const eff = st?.selection && st.selection.key === sel.key && st.selection.engine?.id === sel.engine?.id ? st.selection : sel;
      return statusOf(uri, eff);
    }
    return { server: serverVersion, compiler: bundled?.version, source: 'bundled', mode: 'advisory', trusted: selector?.trusted ?? false, state: 'ok' };
  });

  connection.onRequest('joust/restartAnalysis', () => {
    // C6: a restart also selects the compiler again, re-reading and re-checking the project's engine bytes.
    host.restart();
    missing.clear();
    unavailableShown = false;
    reselectAll();
    return { restarted: true };
  });

  // Trust granted mid-session (D3): select again, restart the workers on the project's engine when now eligible.
  // F9 on #99: the extension forwards a change of joust.buildOnSave.
  connection.onNotification('joust/settings', (p) => {
    if (isVscode && typeof p?.buildOnSave === 'boolean') buildOnSave = p.buildOnSave;
  });
  connection.onNotification('joust/trust', (p) => {
    if (!isVscode || p?.trusted !== true) return;
    selector.invalidate({ trusted: true });
    fixes.clear();
    for (const uri of state.keys()) schedule(uri, 0);
  });

  // A watched file changed. A .jsc file (a checkout, an external edit; the extension watches **/*.jsc): the open files
  // whose last analysis read it are analysed again (#98 F3d). Anything else (a package.json, joust.config.json, an
  // engine): everything is selected again, as before.
  connection.onDidChangeWatchedFiles(async (p) => {
    const changes = p?.changes ?? [];
    const jsc = changes.filter((ch) => /\.jsc$/.test(ch.uri));
    if (changes.length > jsc.length || !changes.length) {
      if (selector) reselectAll();
      return;
    }
    for (const ch of jsc) {
      const f = localFileOf(ch.uri);
      if (!f) continue;
      // #98 N2: a path the client sends is resolved only inside a workspace folder an open file was selected in.
      const folder = [...state.values()].map((st) => st.selection?.folder).find((d) => d && inside(f, d));
      if (!folder) continue;
      const r = await reader.resolveIn(folder, relative(folder, f).split(sep).join('/'));
      rescheduleImporters([f, r.error ? null : r.path]);
    }
  });

  if (flags.testHooks) {
    connection.onRequest('joust/test/stats', () => ({
      ...host.stats, builds: { ...buildHost.stats }, quarantined: host.quarantine.size, slots: [...host.slots.values()].map((s) => ({ key: s.key, ...host.status(s.key), alive: !!(s.worker && !s.worker.dead), booted: !!s.worker?.booted, file: s.engine.file, calls: s.worker?.calls ?? 0 })),
    }));
  }

  connection.onShutdown(() => {
    host.dispose();
    buildHost.dispose();
  });
  connection.onExit(() => {
    host.dispose();
    buildHost.dispose();
    process.exit(0);
  });

  // A failure in a handler is logged, never the end of the server (L9).
  process.on('unhandledRejection', (e) => log(`unhandled: ${e?.stack ?? e}`));
  documents.listen(connection);
  connection.listen();
  return { host, documents };
}
