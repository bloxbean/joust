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
  completion, completionModel, definition, hover, importEdit, occurrences, symbolAt, invisibleOccurrences, references, renamePlan, renamePreserves, semanticTokens, TOKEN_MODIFIERS,
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
/** M5d: how long a formatting request may take in the engine (F6's bound is 60 ms warm; this is the watchdog's cap). */
export const FORMAT_MS = 5000;
/** D2a, D4: the build worker's limits (joust build's ENGINE_STACK_MB; the build heap of §1), its cap and idle stop. */
export const BUILD_STACK_MB = 8;
export const BUILD_HEAP_MB = 384;
export const BUILD_MS = 120000;
export const BUILD_IDLE_MS = 5 * 60 * 1000;
/** D4: at most this many source files in a build snapshot. */
export const MAX_BUILD_FILES = 500;
/** S7 on #99: at most this many characters in a build snapshot (sources and libraries). */
export const MAX_BUILD_CHARS = 16 * 1024 * 1024;
/** S1 on #115: navigation and rename across files read at most this much of the project, analyse at most this many importers, keep at most this many locations. */
export const MAX_CROSS_CHARS = 16 * 1024 * 1024;
export const MAX_CROSS_IMPORTERS = 64;
export const MAX_LOCATIONS = 10000;
export const MAX_OCCURRENCE_CACHE = 64;
/** M5c: the cap on describing a project's Java libraries (javac attribution in the engine). */
export const JAVA_LIBRARIES_MS = 60000;
/** S5 on #115: the java: signature cache's bounds (entries in memory, files on disk, the bytes of one entry). */
export const MAX_SIGNATURE_ENTRIES = 16;
export const MAX_SIGNATURE_FILES = 32;
export const MAX_SIGNATURE_BYTES = 4 * 1024 * 1024;
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
 * flag), and from M5d document and range formatting to the same clients; `textDocument/rename` only to such another client
 * (VS Code renames through JOUST: Rename Symbol).
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
    // M5d (ADR-0021 r9 §15): document and range formatting, to the same clients as completion (D4a: they reject a
    // response once the text changed after it was sent).
    ...(edits ? { documentFormattingProvider: true, documentRangeFormattingProvider: true } : {}),
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
  let signatureDir = null;
  let lifetimes = 0;   // C2 on #115: each open lifetime's serial (a close and a reopen at the same version is another)
  /** The open documents' identities: uri, version and lifetime (C16 on #99, C2 on #115). */
  const openRevision = () => JSON.stringify(documents.all().map((d) => [d.uri, d.version, state.get(d.uri)?.lifetime ?? 0]).sort());   // M5c: the java: library signature cache (outside every workspace folder, as the engines)
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
    // C8, F11, S6 on #115: a reselection (a package.json change, an npm install, Restart analysis) reads the packages'
    // libraries again; C7: and every cached importer analysis goes.
    libraryMemo.clear();
    occurrenceCache.clear();
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

    signatureDir = storage ? join(storage, 'java-signatures') : null;
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
   *   - A java: import (M5c, ADR-0021 r8): an npm project's packages' Java library sources are described once and cached
   *     (javaLibrariesOf), and the analysis takes the descriptions; in a Gradle project (joustLibrary jars) it is advisory.
   * `{ id, files, deps, advisory }` (deps: the real paths read, for rescheduling importers), or null: the file is not in a
   * project's contracts directory (analysed on its own, as before).
   */
  async function projectOf(uri, text, sel, previous = new Map(), overrides = null) {
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
    const resolved = new Map(previous);   // lexical path -> what it resolved to (this analysis, else an earlier one)
    let chars = text.length;
    let advisory = null;
    const todo = [id];
    const javaImports = new Set();
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
      // #110 C16/S8: only node_modules/<pkg> or node_modules/@scope/<pkg> may be a link, not a scope directory: as
      // libraryPackages() (packagesOf) does, the scope entry itself is lstat'ed, so a node_modules that is a link
      // stays allowed (#111 r1 S2).
      if (basename(dirname(pkgRoot)).startsWith('@')) {
        const scope = await fsp.lstat(dirname(pkgRoot)).catch(() => null);
        if (!scope || !scope.isDirectory()) return null;
      }
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
          // M5c (ADR-0021 r8): an npm project's java: libraries are read from its packages (joust-lib/*.java) and
          // described once (javaLibrariesOf); a Gradle project's come from its joustLibrary jars, which the server does
          // not read: advisory there.
          javaImports.add(spec.slice('java:'.length));
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
        // #110 C15: what this path resolved to last time stays an edge while it does not resolve now (a linked
        // dependency whose target is gone): its target coming back reschedules this importer.
        if (previous.has(path)) deps.add(previous.get(path));
        if (Object.keys(files).length >= MAX_PROJECT_FILES) {
          advisory ??= `the file's imports reach more than ${MAX_PROJECT_FILES} files, more than the language server reads: analysed in part`;
          break;
        }
        const realPath = await locate(path, { links, base: sel.contracts, pkgRoot });
        if (!realPath) continue;   // missing or refused: the engine reports the import as the build does
        deps.add(realPath);
        resolved.set(path, realPath);
        // M5c: a cross-file rename checks each file against the others' renamed texts (by real path).
        let body = overrides?.get(realPath) ?? open.get(realPath);
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
    let javaLibraries = null;
    let javaSources = null;
    if (javaImports.size) {
      const j = npm ? await javaLibrariesOf(sel) : { error: 'the Java libraries of a Gradle project come from its joustLibrary jars, which the language server does not read' };
      if (j.error) advisory ??= `a java: import (${[...javaImports][0]}): ${j.error}`;
      else {
        javaLibraries = j.json;
        javaSources = j.sources;
        // C3 on #115: the library sources read are deps: a change of one analyses this file again.
        for (const path of j.paths ?? []) deps.add(path);
      }
    }
    return { id, files, deps, advisory, resolved, javaLibraries, javaSources, javaImports: javaImports.size > 0 };
  }

  /**
   * M5c (ADR-0021 r8): the descriptions of an npm project's java: libraries (its packages' joust-lib/*.java, read as the
   * build reads them, confined): javac's attribution and certification run on the engine once per set of sources, and
   * the result is cached by the SHA-256 of the bytes read (in memory and in the server's private storage, outside every
   * workspace folder), re-verified on use (the entry's own digest; the key is the sources' content). `{ json, sources,
   * key }` or `{ error }`.
   */
  async function javaLibrariesOf(sel) {
    // C3 on #115: open buffers of the library sources are what is described (as the build reads them); the paths read
    // are the analysis's deps. F7, S5: the read is kept until a library source changes (a buffer, a watched file).
    const memoKey = sel.root ?? '';
    let libs = libraryMemo.get(memoKey);
    // C8 on #115: kept only while every file it read is unchanged on disk (size and mtime, checked on use).
    if (libs && (!(await sameFiles(libs.stamps)) || JSON.stringify(await libraryListing(sel)) !== libs.listing)) {
      libraryMemo.delete(memoKey);
      libs = null;
    }
    if (!libs) {
      const openByPath = await openBuffers();
      const inputs = { openByPath, disk: new Map(), versions: new Map(), states: new Map() };
      libs = await buildLibraries(sel, MAX_PROJECT_CHARS, inputs);
      if (!libs.error) {
        libs.paths = [...inputs.disk.keys()];
        libs.stamps = await stampsOf(libs.paths);
        // C15 (#119): and the packages' library files as listed (an addition or a removal, with no watcher event).
        libs.listing = JSON.stringify(await libraryListing(sel));
        libraryMemo.set(memoKey, libs);
        while (libraryMemo.size > 8) libraryMemo.delete(libraryMemo.keys().next().value);
      }
    }
    if (libs.error) return { error: libs.error };
    const sources = libs.javaLibraries;
    if (!Object.keys(sources).length) return { error: 'no package of the project ships Java library sources (joust-lib/*.java)' };
    const key = createHash('sha256');
    for (const [k, v] of Object.entries(sources).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))) key.update(`${k.length}:${k}${v.length}:`).update(v);
    const k = key.digest('hex');
    const engineKey = sel.engine?.id ?? 'engine';
    const memo = javaSignatures.get(`${engineKey}|${k}`);
    if (memo) {
      javaSignatures.delete(`${engineKey}|${k}`);
      javaSignatures.set(`${engineKey}|${k}`, memo);
      return { json: memo, sources, key: k, paths: libs.paths };
    }
    const file = signatureDir ? join(signatureDir, `${engineKey}-${k}.json`) : null;
    if (file) {
      try {
        // S5 on #115: an entry is read only up to MAX_SIGNATURE_BYTES; its digest detects corruption (the storage is the
        // server's own, outside every workspace folder).
        const st = await fsp.stat(file);
        const entry = st.isFile() && st.size <= MAX_SIGNATURE_BYTES ? JSON.parse(await fsp.readFile(file, 'utf8')) : null;
        if (entry && entry.key === k && typeof entry.json === 'string' && entry.sha256 === hash(entry.json)) {
          rememberSignatures(`${engineKey}|${k}`, entry.json);
          return { json: entry.json, sources, key: k, paths: libs.paths };
        }
      } catch {
        // not cached yet, or unreadable: described again
      }
    }
    const t = Date.now();
    const r = await host.request(sel.key, sel.engine, { op: 'javaLibraries', sources }, { scope: scopeOf(sel), input: `java|${k}`,
      what: 'the Java libraries\' description', timeoutMs: JAVA_LIBRARIES_MS });
    if (!r.model) return { error: r.internal?.message ?? 'the engine could not describe them' };
    let parsed;
    try {
      parsed = JSON.parse(r.model);
    } catch {
      return { error: 'the engine\'s description did not parse' };
    }
    if (parsed?.ok === false || typeof parsed?.json !== 'string') return { error: parsed?.error ?? 'this engine cannot describe Java libraries (an older JOUST)' };
    rememberSignatures(`${engineKey}|${k}`, parsed.json);
    log(`described the project's Java libraries in ${Date.now() - t} ms (javac attribution in the engine)`);
    if (file && parsed.json.length <= MAX_SIGNATURE_BYTES) {
      try {
        await fsp.mkdir(signatureDir, { recursive: true });
        await fsp.writeFile(file, JSON.stringify({ key: k, json: parsed.json, sha256: hash(parsed.json) }));
        await pruneSignatures();
      } catch {
        // the cache is an optimisation
      }
    }
    return { json: parsed.json, sources, key: k, paths: libs.paths };
  }
  const javaSignatures = new Map();   // engine id | sources' SHA-256 -> descriptions (JSON), LRU (S5 on #115)
  const libraryMemo = new Map();
  /**
   * C15 (#119): the packages' joust-lib/ files as buildLibraries lists them (the same confined walk, nothing read): a
   * file added or removed changes it.
   */
  async function libraryListing(sel) {
    if (!sel.root || !inside(sel.root, sel.folder)) return [];
    const rootRel = relative(sel.folder, sel.root).split(sep).join('/') || '.';
    const out = [];
    const dirs = async (rel) => {
      const r = await reader.resolveIn(sel.folder, rel);
      if (r.error) return [];
      try {
        return (await fsp.readdir(r.path, { withFileTypes: true }))
          .filter((e) => !e.name.startsWith('.') && (e.isDirectory() || (e.isSymbolicLink() && !e.name.startsWith('@')))).map((e) => e.name).sort();
      } catch {
        return [];
      }
    };
    for (const n of await dirs(`${rootRel}/node_modules`)) {
      const names = n.startsWith('@') ? (await dirs(`${rootRel}/node_modules/${n}`)).map((m) => `${n}/${m}`) : [n];
      for (const name of names) {
        const listed = await reader.list(sel.folder, `${rootRel}/node_modules/${name}/joust-lib`, { max: MAX_BUILD_FILES, kind: 'gradle' });
        if (!listed.error) for (const f of listed.files) out.push(`${name}/${f}`);
        if (out.length > MAX_BUILD_FILES) return out;
      }
    }
    return out;
  }

  /** (size, mtime) of each path, read once (an lstat: the paths are the confined real paths the reader read). */
  async function stampsOf(paths) {
    const out = [];
    for (const p of paths) {
      try {
        const st = await fsp.lstat(p);
        out.push([p, st.size, st.mtimeMs]);
      } catch {
        out.push([p, -1, -1]);
      }
    }
    return out;
  }
  async function sameFiles(stamps) {
    for (const [p, size, mtime] of stamps ?? []) {
      try {
        const st = await fsp.lstat(p);
        if (st.size !== size || st.mtimeMs !== mtime) return false;
      } catch {
        if (size !== -1) return false;
      }
    }
    return true;
  }      // project root -> its packages' library read (C3, F7 on #115), until one changes
  function rememberSignatures(key, json) {
    javaSignatures.delete(key);
    javaSignatures.set(key, json);
    while (javaSignatures.size > MAX_SIGNATURE_ENTRIES) javaSignatures.delete(javaSignatures.keys().next().value);
  }
  /** S5 on #115: the disk cache keeps its MAX_SIGNATURE_FILES most recent entries. */
  async function pruneSignatures() {
    const names = (await fsp.readdir(signatureDir)).filter((n) => /^[0-9a-z_-]+\.json$/i.test(n));
    if (names.length <= MAX_SIGNATURE_FILES) return;
    const dated = [];
    for (const n of names) {
      try {
        dated.push([(await fsp.stat(join(signatureDir, n))).mtimeMs, n]);
      } catch {
        // gone meanwhile
      }
    }
    dated.sort((a, b) => b[0] - a[0]);
    for (const [, n] of dated.slice(MAX_SIGNATURE_FILES)) await fsp.rm(join(signatureDir, n), { force: true });
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
    const project = tooLarge ? null : await projectOf(uri, text, sel, s.resolved);
    if (state.get(uri) !== s || s.seq !== seq || documents.get(uri)?.version !== version) return;
    const eff = project?.advisory && sel.mode !== 'advisory' ? { ...sel, mode: 'advisory', reason: project.advisory } : sel;
    s.selection = eff;
    s.deps = project?.deps ?? new Set();
    s.resolved = project?.resolved ?? s.resolved;
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
    // ADR-0021 r8 (M5c): the diagnostics model first (no index), then the index behind its own entry (requestIndex).
    const inputs = { file, source: text, scope: scopeOf(sel), ...(project ? { moduleId: project.id, files: project.files } : {}),
      ...(project?.javaLibraries ? { javaLibraries: project.javaLibraries } : {}) };
    s.javaSources = project?.javaSources ?? null;
    s.javaLibraries = project?.javaLibraries ?? null;
    s.javaImports = project?.javaImports === true;
    const r = await host.analyze(sel.key, sel.engine, { ...inputs, index: false }, cts.token);
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
    // An engine without lang.index (or index: false) gave the full model: it is the index too.
    const full = r.model.includes(',"references":[');
    if (full) setIndex(s, model, version, gen, text);
    else requestIndex(uri, s, seq, version, gen, sel, inputs);
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

  /** C3 on #115: a java: library's source buffer (the extension sends joust-lib/ .java files): an input, never analysed. */
  const isJavaInput = (uri) => /\/joust-lib\/.*\.java$/.test(uri);
  // F10 on #115: the extension sends joust-lib/ Java buffers by notification (no language feature applies to them).
  const javaBuffers = new Map();   // uri -> text
  connection.onNotification('joust/javaInput', (p) => {
    if (!p || typeof p.uri !== 'string' || !isJavaInput(p.uri) || !localFileOf(p.uri)) return;
    if (p.closed === true) javaBuffers.delete(p.uri);
    else if (typeof p.text === 'string' && p.text.length <= MAX_FILE_CHARS * 2) javaBuffers.set(p.uri, p.text);
    else return;
    javaInputChanged(p.uri, p.closed === true ? null : p.text);
  });
  /** The open buffers by real path: documents, and the Java library buffers the extension sends (pseudo documents). */
  async function openBuffers() {
    const out = new Map();
    for (const d of documents.all()) {
      const p = localFileOf(d.uri);
      if (p) out.set((await real(p)) ?? p, d);
    }
    for (const [uri, text] of javaBuffers) {
      const p = localFileOf(uri);
      if (p) out.set((await real(p)) ?? p, { uri, version: 0, pseudo: true, getText: () => text });
    }
    return out;
  }
  function javaInputChanged(uri, text) {
    libraryMemo.clear();
    occurrenceCache.clear();
    // C17 (#119): an importer whose library was missing has no edge to it: every file with a java: import again.
    for (const [u, st] of state) if (st.javaImports) schedule(u, DEBOUNCE_MS * 2);
    rescheduleImporters([localFileOf(uri)], DEBOUNCE_MS * 2, uri);
    invalidateInput(localFileOf(uri), text).catch(() => {});
  }
  documents.onDidOpen((e) => {
    if (isJavaInput(e.document.uri)) return javaInputChanged(e.document.uri, e.document.getText());
    const old = state.get(e.document.uri);
    if (old) {
      clearTimeout(old.timer);
      settle(old);
    }
    state.set(e.document.uri, { lastPublished: null, lifetime: ++lifetimes });
    schedule(e.document.uri, 0);
    withdrawDisk(e.document.uri, e.document.getText()).catch(() => {});
    // A buffer opened for a file an importer looked for (perhaps missing on disk) is now what it reads (#98 N1).
    rescheduleImporters([localFileOf(e.document.uri)], DEBOUNCE_MS * 2, e.document.uri);
  });
  documents.onDidChangeContent((e) => {
    if (isJavaInput(e.document.uri)) return javaInputChanged(e.document.uri, e.document.getText());
    // C7 on #115: a cached importer analysis read other files too: any change of a project file drops them.
    occurrenceCache.clear();
    if (!state.has(e.document.uri)) state.set(e.document.uri, { lastPublished: null, lifetime: ++lifetimes });
    const s = state.get(e.document.uri);
    // An analysis of an older version still running is cancelled (after the grace period its worker is replaced).
    s.cts?.cancel();
    // A newer generation: every build result is withdrawn (L8), this document's at its next publish, the others' now
    // (their snapshot read this buffer).
    if (s.build) s.build = null;
    withdrawBuilds(e.document.uri);
    // C14 (#114): by path too, for a build that read this file from disk or as another buffer.
    invalidateInput(localFileOf(e.document.uri), e.document.getText()).catch(() => {});
    schedule(e.document.uri);
    // The open files that import this one (a library, a contract they read) are analysed again after it, and only they
    // (#98 F4): their last analysis read its path.
    rescheduleImporters([s.realPath, localFileOf(e.document.uri)], DEBOUNCE_MS * 2, e.document.uri);
  });
  documents.onDidClose((e) => {
    if (isJavaInput(e.document.uri)) return javaInputChanged(e.document.uri, null);
    // C14 (#119): a closed buffer's file is read from disk now: a cached importer analysis that read the buffer goes.
    occurrenceCache.clear();
    const s = state.get(e.document.uri);
    // Its importers read its buffer: they are analysed again with its file on disk (#98 F3d).
    rescheduleImporters([s?.realPath, localFileOf(e.document.uri)], DEBOUNCE_MS * 2, e.document.uri);
    // C7 on #99: a build that read this buffer no longer describes anything open: withdrawn, and one in flight cancelled.
    withdrawBuilds(e.document.uri);
    invalidateInput(localFileOf(e.document.uri), null).catch(() => {});
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
    // The index model's declarations (locals too, ADR-0021 r8); the diagnostics model's outline without it.
    await waitIndex(p.textDocument.uri);
    const doc = documents.get(p.textDocument.uri);
    const m = freshModel(p.textDocument.uri)?.model ?? modelOf(p.textDocument.uri);
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
  /** The full model (with the index) of `version`: what navigation, completion, rename and semantic tokens read. */
  function setIndex(s, model, version, gen, text) {
    s.indexModel = model;
    s.indexVersion = version;
    s.indexGen = gen;
    // C4, F1 on #99: the last model without an error, with its text: completion maps it onto a newer text it has no
    // complete model of (features.reconcile keeps only what the edit did not touch).
    if (!model.diagnostics.some((d) => d.severity === 'error')) {
      s.goodModel = model;
      s.goodText = text;
      s.goodGen = gen;
    }
    for (const w of (s.indexWaiters ?? []).splice(0)) w();
  }

  /**
   * ADR-0021 r8 (M5c): the index of the version just published, on the same worker (the engine keeps the analysis of
   * its last diagnostics model, so this builds the index alone). Superseded by a newer version, it is dropped.
   */
  async function requestIndex(uri, s, seq, version, gen, sel, inputs) {
    const cts = new CancellationTokenSource();
    s.indexCts?.cancel();
    s.indexCts = cts;
    // Tests only: an index later than its diagnostics (C5 on #115).
    if (Number.isInteger(hostOptions.indexDelayMs) && version > 1) await new Promise((res) => setTimeout(res, hostOptions.indexDelayMs));
    const r = await host.analyze(sel.key, sel.engine, { ...inputs, op: 'index' }, cts.token);
    if (s.indexCts === cts) s.indexCts = undefined;
    if (state.get(uri) !== s || s.seq !== seq || documents.get(uri)?.version !== version || selector.generation !== gen || !r.model) {
      for (const w of (s.indexWaiters ?? []).splice(0)) w();
      return;
    }
    try {
      setIndex(s, parseModel(r.model), version, gen, inputs.source);
    } catch {
      for (const w of (s.indexWaiters ?? []).splice(0)) w();
    }
  }

  /** Waits (bounded) for the index of the document's current version; see waitModel. */
  async function waitIndex(uri) {
    await waitModel(uri);
    const end = Date.now() + (host.opts.analysisMs ?? 2000);
    for (;;) {
      const s = state.get(uri);
      const doc = documents.get(uri);
      if (!s || !doc || (s.indexModel && s.indexVersion === doc.version) || s.modelVersion !== doc.version) return;
      const left = end - Date.now();
      if (left <= 0) return;
      await new Promise((res) => {
        (s.indexWaiters ??= []).push(res);
        setTimeout(res, left);
      });
    }
  }

  async function analyseText(uri, sel, source, token, overrides = null) {
    // With the project's files the document's imports reach, as its analysis has them (else an edit's check would see
    // the file alone and refuse it for its imports).
    const project = await projectOf(uri, source, sel, new Map(), overrides);
    const r = await host.analyze(sel.key, sel.engine, { file: fileNameOf(uri), source, scope: scopeOf(sel),
      ...(project ? { moduleId: project.id, files: project.files } : {}), ...(project?.javaLibraries ? { javaLibraries: project.javaLibraries } : {}) }, token);
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
  /** Several documents' edits as one verified, versioned workspace edit (M5c's rename across files). */
  async function applyVerifiedMany(files, label) {
    const res = await connection.workspace.applyEdit({ label, edit: { documentChanges: files.map((x) => ({ textDocument: { uri: x.uri, version: x.version },
      edits: x.edits })) }, joustExpected: files.map((x) => ({ uri: x.uri, sha256: hash(x.text) })) });
    return { applied: res.applied === true, files: files.length };
  }

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
  /** The index model of the document's current version (ADR-0021 r8: navigation, completion, rename read it), or null. */
  function freshModel(uri) {
    const doc = documents.get(uri);
    const s = state.get(uri);
    if (!doc || !s?.indexModel || s.indexVersion !== doc.version || s.indexGen !== selector.generation) return null;
    return { doc, s, model: s.indexModel };
  }

  /** freshModel, after waiting (bounded) for the current version's index. */
  async function indexedModel(uri) {
    await waitIndex(uri);
    return freshModel(uri);
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
    return indexedModel(uri);
  }
  connection.onHover(async (p) => {
    const f = await currentModel(p.textDocument.uri);
    if (!f) return null;
    const std = await stdOf(f.s.selection);
    return freshModel(p.textDocument.uri)?.model === f.model ? hover(f.model, p.position, std) : null;
  });
  // M5c (ADR-0021 r8): a name imported from another .jsc file (a library's, `import contract`) goes to its declaration
  // there; references of a declaration other files can import include theirs.
  connection.onDefinition(async (p, token) => {
    const f = await currentModel(p.textDocument.uri);
    if (!f) return null;
    const sym = symbolAt(f.model, p.position);
    if (sym?.decl?.kind === 'import' && String(sym.decl.detail ?? '').startsWith('java:')) {
      const j = javaTarget(f.s, sym.decl);
      if (j) return j;
    }
    if (sym?.decl?.kind === 'import') {
      const t = await importTarget(p.textDocument.uri, f.model, sym.decl, token);
      // C10 on #115: a package's library opens read-only (a joust-lib: document), never as its writable file.
      if (t) return { uri: t.src.pkg ? libraryDocUri(t.src.text, t.src.id.slice(4)) : t.uri, range: lspRangeOf(t.decl.nameRange) };
    }
    return definition(f.model, p.textDocument.uri, p.position);
  });
  connection.onReferences(async (p, token) => {
    const uri = p.textDocument.uri;
    const f = await currentModel(uri);
    if (!f) return [];
    const include = p.context?.includeDeclaration === true;
    const sym = symbolAt(f.model, p.position);
    let at = null;
    if (sym?.decl?.kind === 'import') {
      const t = await importTarget(uri, f.model, sym.decl, token);
      if (t) at = { uri: t.uri, model: t.model, decl: t.decl, opts: { targetId: t.src.id, targetText: t.src.text, from: uri } };
    } else if (sym?.decl && IMPORTABLE.has(sym.decl.kind) && sym.decl.parent < 0) {
      at = { uri, model: f.model, decl: sym.decl, opts: { targetText: f.doc.getText() } };
    }
    if (!at) return references(f.model, uri, p.position, include);
    const all = await occurrencesAcross(at.uri, at.model, at.decl, token, at.opts);
    const list = all.occurrences ?? all.local ?? [];
    // C10 on #115: a package library's own locations are read-only documents too.
    const pkgUri = at.opts.targetId?.startsWith('pkg:') ? libraryDocUri(at.opts.targetText, at.opts.targetId.slice(4)) : null;
    return list.filter((o) => include || !o.decl).map((o) => ({ uri: pkgUri && o.uri === at.uri ? pkgUri : o.uri, range: lspRangeOf(o.range) }));
  });
  connection.languages.semanticTokens.on(async (p) => {
    await waitIndex(p.textDocument.uri);
    // C5 on #115: only the index of this version (a failed or late index never lends an older version's tokens).
    const m = freshModel(p.textDocument.uri)?.model;
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
    // C8: the current model's own declarations first; the good model's only where the current one lacks them.
    const good = s.goodModel && s.goodGen === selector.generation && (!usable || usable.diagnostics.some((d) => d.severity === 'error'))
      ? completionModel(usable, s.goodModel, s.goodText, now.getText()) : null;
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
    // Not from a clean model of this version: the client asks again as typing goes on.
    return { isIncomplete: !usable || !!good || usable.diagnostics.some((d) => d.severity === 'error'), items };
  });

  // ------------------------------------------------------------------ M5d: formatting (§15, D4a, L7)

  /**
   * The formatter's answer for the open document `uri` at the version it has now: `{ doc, version, f }` with the engine's
   * `{ ok, text, edits }` (F4: `ok` false for a file that does not parse), or null (not open, too large, no engine with
   * lang.format, cancelled, or a newer version arrived meanwhile: D4a's rule, nothing for a stale request).
   */
  async function formatted(uri, token) {
    const doc = documents.get(uri);
    const s = state.get(uri);
    if (!doc || !s) return null;
    const version = doc.version;
    const text = doc.getText();
    if (text.length > MAX_FILE_CHARS) return null;
    const sel = s.selection ?? await selectionFor(uri);
    const r = await host.request(sel.key, sel.engine, { op: 'format', source: text },
      { scope: scopeOf(sel), input: text, what: 'the formatting', timeoutMs: FORMAT_MS }, token);
    const now = documents.get(uri);
    if (!r.model || !now || now.version !== version || token?.isCancellationRequested) return null;
    let f;
    try {
      f = JSON.parse(r.model);
    } catch {
      return null;
    }
    return f && f.ok === true && typeof f.text === 'string' ? { doc: now, version, f } : null;
  }

  // One edit of the whole document (F6), none when it is formatted already; a file that does not parse gets none (F4).
  connection.onDocumentFormatting(async (p, token) => {
    if (!edits) return null;
    const x = await formatted(p.textDocument.uri, token);
    if (!x) return null;
    const text = x.doc.getText();
    if (x.f.text === text) return [];
    return [{ range: { start: { line: 0, character: 0 }, end: x.doc.positionAt(text.length) }, newText: x.f.text }];
  });

  // The formatter's edits that end inside the range: the whitespace before each item in it (its line's indentation
  // included) and the comments in it, never a line outside it.
  connection.onDocumentRangeFormatting(async (p, token) => {
    if (!edits) return null;
    const x = await formatted(p.textDocument.uri, token);
    if (!x) return null;
    const from = x.doc.offsetAt(p.range.start);
    const to = x.doc.offsetAt(p.range.end);
    const out = [];
    for (const e of Array.isArray(x.f.edits) ? x.f.edits : []) {
      const [start, end, newText] = e;
      if (!Number.isInteger(start) || !Number.isInteger(end) || typeof newText !== 'string' || start > end) return null;
      if (end < from || end > to) continue;
      out.push({ range: { start: x.doc.positionAt(start), end: x.doc.positionAt(end) }, newText });
    }
    return out;
  });

  // ------------------------------------------------------------------ M5b: rename (D7, L6)

  /** The rename at `position`, validated on the current snapshot: `{ plan, version, text, st }` or `{ error }`. */
  async function validatedRename(uri, position, newName, token) {
    const f = await indexedModel(uri);
    if (!f) return { error: 'the file changed or is still being analysed: try again' };
    if (f.model.modelVersion < 2) return { error: 'this compiler predates rename (JOUST M5b)' };
    // C10 on #115: a package's library (under node_modules), or a file outside the project's contracts, is never edited.
    const ro = await readOnlyReason(uri, f.s.selection);
    if (ro) return { error: ro };
    // F2 on #115: a rename started on an imported name (its import, or a use) is the rename of its declaration, in the
    // file the import names: that file must be open too (D4a), and a package's library is never edited.
    const sym = symbolAt(f.model, position);
    if (sym?.decl?.kind === 'import' && !(f.model.diagnostics.some((d) => d.severity === 'error'))) {
      if (String(sym.decl.detail ?? '').startsWith('java:') || String(sym.decl.detail ?? '').startsWith('std/')) {
        return { error: `'${sym.decl.name}' comes from ${sym.decl.detail}, which the language server does not edit` };
      }
      const t = await importTarget(uri, f.model, sym.decl, token);
      if (!t) return { error: `'${sym.decl.name}' is declared in ${sym.decl.detail}, which the language server cannot read: rename it there` };
      if (t.src.pkg) return { error: `'${sym.decl.name}' is declared in the npm package library ${sym.decl.detail}: a package is never edited` };
      if (!t.src.doc) return { needsOpen: [t.uri], error: `the rename edits ${basename(fileURLToPath(t.uri))}, which is not open` };
      return validatedRename(t.uri, { line: t.decl.nameRange[0], character: t.decl.nameRange[1] }, newName, token);
    }
    const plan = renamePlan(f.model, position, newName);
    if (plan.error) return plan;
    const version = f.doc.version;
    const text = f.doc.getText();
    const after = await analyseText(uri, f.s.selection, TextDocument.applyEdits(f.doc, plan.edits), token);
    if (!after) return { error: 'the rename could not be checked' };
    const ok = renamePreserves(f.model, after, plan.edits);
    if (!ok.ok) return { error: ok.reason };
    if (!freshModel(uri) || documents.get(uri)?.version !== version) return { error: 'the file changed: try again' };
    // F2 on #99: a name other files can see is renamed only when no file imports this one (renaming across files comes
    // with M5c's project index). C11 (r2): checked last, on the buffers open now, just before the edit is applied.
    // M5c (ADR-0021 r8): a library's fn, type, enum or const is renamed across the project (open files only).
    // F1 on #115: a library's only; a contract file's types stay under F2's refusal while a file imports the contract.
    if (plan.visible && RENAMABLE_ACROSS.has(plan.decl.kind) && plan.decl.parent < 0 && isLibrary(f.model)) {
      return renameAcross(uri, f, plan, newName, version, text, token);
    }
    if (plan.visible) {
      // C16 (#114): the scan is bound to the open buffers it read: if any opens, closes or changes meanwhile, refused.
      const before = openRevision();
      const imp = await importersOf(uri, f.s.selection ?? await selectionFor(uri), token);
      if (openRevision() !== before) return { error: 'an open file changed while the files that import this one were read: try again' };
      if (imp.error) return { error: `'${plan.decl.name}' can be named by other files, and ${imp.error}: rename it by hand (renaming across files comes with M5c)` };
      if (imp.importers.length) {
        return { error: `'${plan.decl.name}' can be named by the files that import this one (${imp.importers.slice(0, 3).join(', ')}${imp.importers.length > 3 ? ', …' : ''}):`
          + ` ${isLibrary(f.model) ? 'a library\'s variants and fields are renamed in their file only' : 'a contract\'s names are renamed in its file only while no file imports it'}; rename it by hand` };
      }
      if (!freshModel(uri) || documents.get(uri)?.version !== version) return { error: 'the file changed: try again' };
    }
    return { plan, version, text, st: f.s };
  }

  const RENAMABLE_ACROSS = new Set(['fn', 'type', 'enum']);

  /**
   * M5c (ADR-0021 r8; L6 across files): the rename of a library's top-level declaration in its file and in every file
   * that imports it (the import's name and each use through it). Every file it edits must be open (D4a: versioned,
   * open files only: `{ needsOpen }` names the rest); each is re-analysed against the others' renamed texts and must add
   * no diagnostic and keep every reference to the same declaration; bound to the open buffers it read.
   */
  async function renameAcross(uri, f, plan, newName, version, text, token) {
    const before = openRevision();
    const occ = await occurrencesAcross(uri, f.model, plan.decl, token, { targetText: text });
    if (occ.error) return { error: `'${plan.decl.name}' can be named by other files, and ${occ.error}: rename it by hand` };
    if (occ.truncated) return { error: `'${plan.decl.name}' has more than ${MAX_LOCATIONS} occurrences, more than a rename edits` };
    // C1 on #115: every importer the lexical scan found, before anything else: one with an error (a header error drops its
    // imports) may hold uses its model does not show: refused. C6: a clean one without an import of the name is skipped.
    for (const imp of occ.importers) {
      // C6 on #115: a clean importer that imports other names of the library has nothing to rename.
      if (imp.errors) {
        return { error: `${basename(fileURLToPath(imp.src.uri))}, which imports '${plan.decl.name}', has errors: a rename needs every file it edits to check; fix them first` };
      }
    }
    const byUri = new Map([[uri, { uri, version, text, model: f.model, edits: plan.edits }]]);
    for (const o of occ.occurrences) {
      if (o.uri === uri) continue;
      if (!byUri.has(o.uri)) byUri.set(o.uri, { uri: o.uri, src: o.src, edits: [] });
      byUri.get(o.uri).edits.push({ range: lspRangeOf(o.range), newText: newName });
    }
    const files = [...byUri.values()];
    const closed = files.filter((x) => !documents.get(x.uri)).map((x) => x.uri);
    if (closed.length) return { needsOpen: closed, error: `the rename edits files that are not open: ${closed.map((u) => basename(fileURLToPath(u))).join(', ')}` };
    for (const x of files) {
      const doc = documents.get(x.uri);
      x.version = doc.version;
      x.text = doc.getText();
      if (x.src && x.src.text !== x.text) return { error: 'a file changed while the rename was planned: try again' };
      if (!x.model) {
        const m = freshModel(x.uri);
        if (!m || m.doc.getText() !== x.text) return { error: 'a file changed while the rename was planned: try again' };
        x.model = m.model;
      }
      x.after = TextDocument.applyEdits(doc, x.edits);
    }
    // Each file's real path -> its renamed text: every re-analysis reads the others' renamed texts.
    const overrides = new Map();
    for (const x of files) {
      const p = localFileOf(x.uri);
      if (p) overrides.set((await real(p)) ?? p, x.after);
    }
    const sel = f.s.selection;
    for (const x of files) {
      if (token?.isCancellationRequested) return { error: 'cancelled' };
      const after = await analyseText(x.uri, sel, x.after, token, overrides);
      if (!after) return { error: `the rename could not be checked in ${basename(fileURLToPath(x.uri))}` };
      const ok = renamePreserves(x.model, after, x.edits);
      if (!ok.ok) return { error: `${basename(fileURLToPath(x.uri))}: ${ok.reason}` };
    }
    if (openRevision() !== before) return { error: 'an open file changed while the rename was checked: try again' };
    for (const x of files) if (documents.get(x.uri)?.version !== x.version) return { error: 'the file changed: try again' };
    // A type or enum a contract imports can be in its datum or redeemers: its manifest may change too (D7's warning).
    const abi = plan.abi || (files.length > 1 && (plan.decl.kind === 'type' || plan.decl.kind === 'enum'));
    return { plan: { ...plan, abi }, version, text, st: f.s, files };
  }

  /** C10 on #115: why the file `uri` is never edited (a package's, or outside the project's contracts), or null. */
  async function readOnlyReason(uri, sel) {
    const p = localFileOf(uri);
    if (!p) return null;
    const r = (await realPathOf(uri, sel)) ?? p;
    if (r.split(sep).includes('node_modules')) return 'this file is an npm package\'s library: a package is never edited';
    if (sel?.contracts && !inside(r, sel.contracts) && sel.folder && inside(r, sel.folder) && /\.jsc$/.test(r)) {
      return 'this file is outside the project\'s contracts directory: the language server does not edit it';
    }
    return null;
  }

  /** A library file (no contract declaration). */
  const isLibrary = (model) => !model.declarations.some((d) => d.kind === 'contract');

  const abiWarning = (d) => `'${d.name}' is recorded in the ABI manifest: renaming it changes the manifest, the generated client and the`
    + ' description (the script does not change), and the next build\'s ABI gate asks you to accept the new manifest (Accept ABI…).';

  // VS Code: JOUST: Rename Symbol sends this; the server validates and applies through workspace/applyEdit (D4a).
  connection.onRequest('joust/rename', async (p, token) => {
    if (!edits) return { applied: false, error: 'edits are off for this client' };
    if (!p || typeof p.uri !== 'string' || typeof p.newName !== 'string' || !p.position) return { applied: false, error: 'refused' };
    const r = await validatedRename(p.uri, p.position, p.newName, token);
    if (r.error) return { applied: false, error: r.error, ...(r.needsOpen ? { needsOpen: r.needsOpen } : {}) };
    if (r.plan.abi && p.confirm !== true) return { applied: false, needsConfirmation: true, warning: abiWarning(r.plan.decl) };
    if (r.files) return applyVerifiedMany(r.files, `Rename '${r.plan.decl.name}' to '${p.newName}'`);
    return applyVerified(p.uri, r.version, r.text, r.plan.edits, `Rename '${r.plan.decl.name}' to '${p.newName}'`);
  });
  connection.onRequest('joust/prepareRename', async (p, token) => {
    const f = p && await indexedModel(p.uri);
    if (!f) return { error: 'the file changed or is still being analysed: try again' };
    const ro = await readOnlyReason(p.uri, f.s.selection);
    if (ro) return { error: ro };
    // F2 on #115: on an imported name, the declaration it names (as joust/rename does); its file may be closed: the
    // rename then names it (needsOpen).
    const sym = symbolAt(f.model, p.position);
    if (sym?.decl?.kind === 'import' && !f.model.diagnostics.some((d) => d.severity === 'error')) {
      const detail = String(sym.decl.detail ?? '');
      if (detail.startsWith('java:') || detail.startsWith('std/')) return { error: `'${sym.decl.name}' comes from ${detail}, which the language server does not edit` };
      const t = await importTarget(p.uri, f.model, sym.decl, token);
      if (!t) return { error: `'${sym.decl.name}' is declared in ${detail}, which the language server cannot read: rename it there` };
      if (t.src.pkg) return { error: `'${sym.decl.name}' is declared in the npm package library ${detail}: a package is never edited` };
      const tp = renamePlan(t.model, { line: t.decl.nameRange[0], character: t.decl.nameRange[1] }, '__joust_probe__');
      if (tp.error && !tp.decl) return { error: tp.error };
      // F14 (#119): no ABI flag here: only joust/rename knows the importers that decide it (its needsConfirmation).
      return { name: t.decl.name, kind: t.decl.kind, in: t.uri };
    }
    const plan = renamePlan(f.model, p.position, '__joust_probe__');
    return plan.error && !plan.decl ? { error: plan.error } : { name: plan.decl.name, kind: plan.decl.kind };
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
      if (r.files) return { documentChanges: r.files.map((x) => ({ textDocument: { uri: x.uri, version: x.version }, edits: x.edits })) };
      return { documentChanges: [{ textDocument: { uri: p.textDocument.uri, version: r.version }, edits: r.plan.edits }] };
    });
  }

  // ------------------------------------------------------------------ M5b: Remove invisible characters… (D7)

  // Lists every invisible or control character (JSC0145, JSC0148) of the current text; the extension shows them in a modal
  // and only on confirmation sends joust/removeInvisible with the single-use id, which deletes exactly those characters
  // from exactly that version.
  connection.onRequest('joust/invisible', async (p) => {
    const f = p && await indexedModel(p.uri);
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
  /** S1 on #115: at most this many characters read for a cross-file request (a test may lower it). */
  const crossChars = Number.isInteger(hostOptions.crossChars) ? hostOptions.crossChars : MAX_CROSS_CHARS;
  /** S7, C13: at most this many characters in a build snapshot (a test may lower it). */
  const buildChars = Number.isInteger(hostOptions.buildChars) ? hostOptions.buildChars : MAX_BUILD_CHARS;
  const builtFrom = new Map();   // uri of an open document -> the build seq that read it

  const inFlight = new Map();   // build seq -> { uris (the open buffers its snapshot read), cts }

  const builtDisk = new Map();   // build seq -> its snapshot's files read from disk (real path -> SHA-256), while shown

  /**
   * C9 (r2), C14 (#114): a file a build read changed (a buffer opened on it with other text, edited, closed, or the file
   * changed on disk: `text` null, unknown): that build's results end, shown or in flight, whatever document they are on.
   */
  async function invalidateInput(path, text, { resolve = true } = {}) {
    // S3 on #115: a path the reader refused is never resolved (D4b rule 1): it is no build input either.
    const r = path && (resolve ? (await real(path)) ?? path : path);
    if (!r) return;
    const h = text == null ? null : hash(text);
    const differs = (inputs) => inputs && inputs.has(r) && inputs.get(r) !== h;
    for (const b of inFlight.values()) {
      if (differs(b.disk)) {
        b.flight.stale = true;
        b.cts?.cancel();
      }
    }
    for (const [seq, inputs] of builtDisk) {
      if (!differs(inputs)) continue;
      builtDisk.delete(seq);
      for (const [u, st] of state) {
        if (st.build && st.build.seq === seq) {
          st.build = null;
          if (st.lastPublished != null) connection.sendDiagnostics({ uri: u, version: st.lastPublished, diagnostics: st.lastDiagnostics ?? [] });
        }
      }
    }
  }

  async function withdrawDisk(uri, text) {
    return invalidateInput(localFileOf(uri), text);
  }

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
    // C9 (r2), C14 and C17 (#114): every file the snapshot read, open buffer or disk, project source or package library:
    // its real path -> the SHA-256 of the text built. Any later change of one (a buffer opened, edited or closed, a
    // watched file changed) ends the build's results.
    const disk = new Map();
    const overBudget = `the project's sources and libraries are larger than the language server builds (${buildChars} characters)`;
    let chars = 0;
    const digest = createHash('sha256');
    const keep = (f, text) => {
      chars += text.length;
      digest.update(`${f.length}:${f}${text.length}:`).update(text);
    };
    const openByPath = await openBuffers();
    if (!sel.contracts || !sel.folder || !sel.contractsRel) {
      const doc = documents.get(uri);
      if (!doc) return { error: 'the file is not open' };
      files[fileNameOf(uri)] = doc.getText();
      versions.set(uri, doc.version);
      states.set(uri, state.get(uri));
      keep(fileNameOf(uri), doc.getText());
      const p = localFileOf(uri);
      if (p) disk.set((await real(p)) ?? p, hash(doc.getText()));
      return { files, versions, states, disk, dir: null, packages: {}, javaLibraries: {}, digest: digest.digest('hex') };
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
        disk.set(full, hash(files[f]));
        if (chars + files[f].length > buildChars) return { error: overBudget };
      } else {
        // C13 on #99 r2: the total is debited before anything is kept; a file past what is left is not read.
        const left = buildChars - chars;
        const r = await reader.read(sel.folder, `${rel}/${f}`, { cap: Math.min(MAX_FILE_CHARS * 2, left) });
        if (r.error === 'too-large' && left < MAX_FILE_CHARS * 2) return { error: overBudget };
        if (r.error) return { error: `${f} cannot be read (${r.error})` };
        files[f] = r.bytes.toString('utf8');
        // C9 on #99 r2: a file read from disk is an input too: a buffer opened on it later with other text makes the
        // snapshot stale.
        disk.set(full, hash(files[f]));
      }
      keep(f, files[f]);
      if (chars > buildChars) return { error: overBudget };
    }
    // C6 on #99: the npm packages' libraries (joust-lib/: .jsc by <package>/<path>, Java sources by path), as joust
    // build passes them (libraries.js), read confined, within what is left of the same budget (C13).
    const libs = sel.kind === 'gradle' ? { packages: {}, javaLibraries: {} }
      : await buildLibraries(sel, buildChars - chars, { openByPath, disk, versions, states });
    if (libs.error) return { error: libs.error };
    for (const [k, v] of [...Object.entries(libs.packages), ...Object.entries(libs.javaLibraries)]) keep(`lib:${k}`, v);
    if (chars > buildChars) return { error: overBudget };
    return { files, versions, states, disk, dir: listed.path, packages: libs.packages, javaLibraries: libs.javaLibraries, digest: digest.digest('hex') };
  }

  /**
   * C6 on #99: the libraries the project's npm packages ship under joust-lib/ (libraries.js libraryPackages' rules: every
   * package of node_modules, scoped ones included, its joust-lib/'s .jsc and .java files), read through the confined
   * reader: a package root may be a link (confined); a joust-lib/ or anything below it reached by a link is left out
   * (as projectOf's package imports). `{ packages, javaLibraries }` or `{ error }` (one Java class shipped twice: JSC0709; a bound).
   */
  async function buildLibraries(sel, budget = buildChars, inputs = null) {
    let left = budget;
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
        // C17 (#114): an open buffer of the library is what the build reads (as the analysis does), recorded with its
        // lifetime and version; else the file, recorded by its hash.
        const full = join(listed.path, ...f.split('/'));
        const open = inputs?.openByPath.get(full);
        let text;
        if (open) {
          text = open.getText();
          // A pseudo document (a Java buffer) has no version: its text's hash is what a later change is checked against.
          if (!open.pseudo) {
            inputs.versions.set(open.uri, open.version);
            inputs.states.set(open.uri, state.get(open.uri));
          }
        } else {
          // C13 on #99 r2: debited before it is kept; a file past what is left of the budget is not read at all.
          const r = await reader.read(sel.folder, `${libRel}/${f}`, { cap: Math.max(0, Math.min(MAX_FILE_CHARS * 2, left)) });
          if (r.error === 'too-large' && left < MAX_FILE_CHARS * 2) {
            return { error: `the project's sources and libraries are larger than the language server builds (${buildChars} characters)` };
          }
          if (r.error) return { error: `${name}/joust-lib/${f} cannot be read (${r.error})` };
          text = r.bytes.toString('utf8');
        }
        inputs?.disk.set(full, hash(text));
        left -= text.length;
        if (left < 0) return { error: `the project's sources and libraries are larger than the language server builds (${buildChars} characters)` };
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
  async function importersOf(uri, sel, token) {
    const p = await projectImporters(uri, sel, null, token);
    if (p.error) return p;
    return { importers: p.importers.map((x) => x.id) };
  }

  /** A relative specifier's module id from the file `fromId`, or null (#98 S7: no backslash, no '//'). */
  function resolveRelative(fromId, spec) {
    if (typeof spec !== 'string' || !(spec.startsWith('./') || spec.startsWith('../')) || spec.includes('\\') || spec.includes('//')) return null;
    const id = posix.normalize(posix.join(posix.dirname(fromId), spec));
    return id.startsWith('..') ? null : id;
  }

  const PACKAGE_SPEC = /^(@[a-z0-9][a-z0-9._~-]*\/)?[a-z0-9][a-z0-9._~-]*\/[^\\]+\.jsc$/;

  /**
   * The module id an import names from the file `fromId`, as the build's JscModules names it: a project file's path
   * under the contracts directory, or `pkg:<specifier>` for an npm package's library (C4 on #115); null otherwise.
   */
  function moduleOf(fromId, spec) {
    if (typeof spec !== 'string' || spec.includes('\\') || spec.includes('//')) return null;
    if (spec.startsWith('./') || spec.startsWith('../')) {
      if (fromId.startsWith('pkg:')) {
        const inner = fromId.slice(4);
        const pkg = inner.startsWith('@') ? inner.split('/').slice(0, 2).join('/') : inner.split('/')[0];
        const rel = posix.normalize(posix.join(posix.dirname(inner.slice(pkg.length + 1)), spec));
        return rel.startsWith('..') ? null : `pkg:${pkg}/${rel}`;
      }
      return resolveRelative(fromId, spec);
    }
    return PACKAGE_SPEC.test(spec) && !spec.split('/').some((x) => x === '' || x === '.' || x === '..') ? `pkg:${spec}` : null;
  }

  /** The module id of an open document (a project file's path under the contracts directory), or `{ error }`. */
  async function moduleIdOf(uri, sel) {
    if (!sel?.folder) return { error: 'the file is outside every workspace folder' };
    const project = sel.contracts && sel.contractsRel;
    const base = project ? sel.contracts : sel.folder;
    const own = localFileOf(uri);
    const self = (await realPathOf(uri, sel)) ?? (own && inside(own, sel.folder) && await missingOnDisk(sel, own) ? own : null);
    if (!self || !inside(self, base)) return { error: 'the file is outside its project\'s contracts directory' };
    return { id: relative(base, self).split(sep).join('/'), base, project };
  }

  /** S2 on #115: a path lexically in the folder that the reader reports missing (not reached through a link). */
  async function missingOnDisk(sel, path) {
    const r = await reader.resolveIn(sel.folder, relative(sel.folder, path).split(sep).join('/'));
    return r.error === 'missing';
  }

  /**
   * F2, C11 on #99; S1, S2 on #115: the project's files that import the module `targetId` (the file `uri`'s own when
   * null), with their texts: the build's walk over the contracts directory (or the whole folder without a known
   * project), open buffers in place of their files, and open buffers of files not on disk yet (only those: a buffer
   * reached through a link is its real file's or none). Each file's imports are scanned as it is read and only the
   * importers' texts are kept; the reads are debited from MAX_BUILD_CHARS before they are made; a cancelled request
   * stops between files. `{ id, base, importers: [{ id, uri, text, doc }] }` or `{ error }`.
   */
  async function projectImporters(uri, sel, targetId, token) {
    const me = await moduleIdOf(uri, sel);
    if (me.error) return me;
    const target = targetId ?? me.id;
    const rel = me.project ? sel.contractsRel.join('/') : '.';
    const listed = await reader.list(sel.folder, rel, { exts: ['.jsc'], max: MAX_BUILD_FILES, kind: sel.kind === 'gradle' ? 'gradle' : 'npm' });
    if (listed.error) return { error: listed.error === 'too-many' ? `the project has more than ${MAX_BUILD_FILES} source files` : 'the contracts directory cannot be read' };
    const open = new Map();
    for (const d of documents.all()) {
      const p = await realPathOf(d.uri, sel);
      if (p) open.set(p, d);
    }
    // Tests only: a pause after the open buffers are read, so a test can change one meanwhile (C16 on #99, C2 on #115).
    if (Number.isInteger(hostOptions.importerScanDelayMs)) await new Promise((res) => setTimeout(res, hostOptions.importerScanDelayMs));
    let left = crossChars;
    const importers = [];
    const seen = new Set();
    const consider = (id, uri2, text, doc) => {
      if (seen.has(uri2)) return null;
      seen.add(uri2);
      const scanned = scanImports(text, MAX_IMPORTS_PER_FILE);
      if (scanned.truncated) return `${id} has more imports than the language server reads`;
      if (id !== target && scanned.imports.some(({ spec }) => moduleOf(id, spec) === target)) importers.push({ id, uri: uri2, text, doc });
      return null;
    };
    for (const f of listed.files) {
      if (token?.isCancellationRequested) return { error: 'cancelled' };
      const full = join(listed.path, ...f.split('/'));
      const doc = open.get(full);
      let text = doc?.getText();
      if (text === undefined) {
        if (left <= 0) return { error: `the project's sources are larger than the language server reads for this (${crossChars} characters)` };
        const r = await reader.read(sel.folder, `${rel}/${f}`, { cap: Math.min(MAX_FILE_CHARS, left) });
        if (r.error === 'too-large' && left < MAX_FILE_CHARS) return { error: `the project's sources are larger than the language server reads for this (${crossChars} characters)` };
        if (r.error) return { error: `${f} cannot be read (${r.error})` };
        text = r.bytes.toString('utf8');
        left -= text.length;
      }
      const e = consider(f, doc?.uri ?? pathToFileURL(full).toString(), text, doc ?? null);
      if (e) return { error: e };
    }
    // C11 on #99, S2 on #115: open buffers of files that are not on disk yet, under the same walk rules.
    const listedSet = new Set(listed.files);
    for (const d of documents.all()) {
      const p = localFileOf(d.uri);
      if (!p || !p.endsWith('.jsc') || !inside(p, me.base)) continue;
      const f = relative(me.base, p).split(sep).join('/');
      if (listedSet.has(f) || !(await missingOnDisk(sel, p))) continue;
      if (sel.kind !== 'gradle' && f.split('/').slice(0, -1).some((x) => x === 'node_modules' || x.startsWith('.'))) continue;
      if (Buffer.byteLength(d.getText(), 'utf8') > MAX_FILE_CHARS) return { error: `${f} is larger than the language server reads` };
      const e = consider(f, d.uri, d.getText(), d);
      if (e) return { error: e };
    }
    return { id: me.id, base: me.base, importers };
  }

  // ------------------------------------------------------------------ M5c: across files (ADR-0021 r8)

  /**
   * M5c (ADR-0021 r8): a name a java: import brings goes to its declaration in the library's on-chain sources, a
   * read-only document (joust-lib:/<id>/<path>, served from memory by an opaque id: joust/libraryContent). A fn is
   * placed where javac's attribution put its name; a record by its declaration in the library's file.
   */
  function javaTarget(s, imp) {
    if (typeof s?.javaLibraries !== 'string' || !s.javaSources) return null;
    let libs;
    try {
      libs = JSON.parse(s.javaLibraries).libraries ?? [];
    } catch {
      return null;
    }
    const fqcn = String(imp.detail).slice('java:'.length);
    const lib = libs.find((l) => l.fqcn === fqcn);
    if (!lib) return null;
    let path = null;
    let line = 0;
    let col = 0;
    const fn = (lib.fns ?? []).find((x) => x.name === imp.name);
    if (fn && typeof fn.file === 'string' && fn.line > 0) {
      path = fn.file;
      line = fn.line - 1;
      col = fn.col - 1;
    } else {
      const rec = (lib.records ?? []).find((r) => r.simpleName === imp.name);
      const file = `${fqcn.replace(/\./g, '/')}.java`;
      const text = s.javaSources[file];
      // C13 on #115: javac's place of the record, when the engine gives one; else (an older engine) the text.
      if (rec && typeof rec.file === 'string' && rec.line > 0) {
        path = rec.file;
        line = rec.line - 1;
        col = rec.col - 1;
      } else if (rec && typeof text === 'string') {
        const m = new RegExp(`\\brecord\\s+${imp.name}\\b`).exec(text);
        if (m) {
          path = file;
          const before = text.slice(0, m.index + m[0].length - imp.name.length);
          line = before.split('\n').length - 1;
          col = before.length - before.lastIndexOf('\n') - 1;
        }
      }
    }
    const text = path && s.javaSources[path];
    if (typeof text !== 'string') return null;
    return { uri: libraryDocUri(text, path), range: { start: { line, character: col }, end: { line, character: col + imp.name.length } } };
  }

  /** A read-only library document's URI (joust-lib:/<opaque id>/<path>), its text kept in memory (20 at most). */
  function libraryDocUri(text, path) {
    for (const [k] of [...libraryDocs.entries()].sort((a, b) => b[1].issued - a[1].issued).slice(19)) libraryDocs.delete(k);
    const id = randomBytes(18).toString('base64url');
    libraryDocs.set(id, { text, path, issued: Date.now() });
    return `joust-lib:/${id}/${path}`;
  }
  const libraryDocs = new Map();   // opaque id -> { text, path, issued }: the read-only java: library documents
  connection.onRequest('joust/libraryContent', (p) => {
    const d = p && typeof p.id === 'string' && libraryDocs.get(p.id);
    return d ? { text: d.text, path: d.path } : { error: 'this library document is gone: go to its definition again' };
  });

  const lspRangeOf = (r) => ({ start: { line: r[0], character: r[1] }, end: { line: r[2], character: r[3] } });

  /** The kinds another file can name through an import (a library's top-level declarations, a contract). */
  // A file declares no top-level const (the grammar's top level: import, type, enum, fn, contract).
  const IMPORTABLE = new Set(['fn', 'type', 'enum', 'contract']);

  /** The index model of a source: its open buffer's (current version), else its text analysed with the project. */
  async function indexOfSource(src, sel, token) {
    if (src.doc) {
      const f = await indexedModel(src.doc.uri);
      if (f && f.doc.getText() === src.text) return f.model;
    }
    return analyseText(src.uri, sel, src.text, token);
  }

  /**
   * S1 on #115: the one file an import names, read alone (never the project): a project file by its module id under the
   * contracts directory, or (C4) an npm package's library under node_modules/<package>/joust-lib/ (the package root may
   * be a confined link, nothing below joust-lib/ may be, as the build's walk). `{ id, uri, text, doc, pkg }` or null.
   */
  async function targetSource(uri, sel, spec) {
    const me = await moduleIdOf(uri, sel);
    if (me.error) return null;
    const id = moduleOf(me.id, spec);
    if (!id) return null;
    let path;
    if (id.startsWith('pkg:')) {
      if (!sel.root || sel.kind === 'gradle') return null;
      const inner = id.slice(4);
      const pkg = inner.startsWith('@') ? inner.split('/').slice(0, 2).join('/') : inner.split('/')[0];
      const rootRel = relative(sel.folder, join(sel.root, 'node_modules', ...pkg.split('/'))).split(sep).join('/');
      const root = await reader.resolveIn(sel.folder, rootRel);
      if (root.error) return null;
      const rest = inner.slice(pkg.length + 1).split('/');
      const real2 = await reader.resolveIn(sel.folder, `${rootRel}/joust-lib/${rest.join('/')}`);
      if (real2.error || real2.path !== join(root.path, 'joust-lib', ...rest)) return null;
      path = real2.path;
    } else {
      path = join(me.base, ...id.split('/'));
    }
    for (const d of documents.all()) {
      if ((await realPathOf(d.uri, sel)) === path) return { id, uri: d.uri, text: d.getText(), doc: d, pkg: id.startsWith('pkg:') };
    }
    const r = await reader.read(sel.folder, relative(sel.folder, path).split(sep).join('/'), { cap: MAX_FILE_CHARS });
    if (r.error) return null;
    return { id, uri: pathToFileURL(path).toString(), text: r.bytes.toString('utf8'), doc: null, pkg: id.startsWith('pkg:') };
  }

  /** The declaration an import names in the file it imports: `{ uri, decl, model, src }`, or null. */
  async function importTarget(uri, model, imp, token) {
    const s = state.get(uri);
    const sel = s?.selection ?? await selectionFor(uri);
    const src = await targetSource(uri, sel, imp.detail);
    if (!src || token?.isCancellationRequested) return null;
    const m = await indexOfSource(src, sel, token);
    if (!m) return null;
    const decl = topLevel(m, imp.name);
    return decl ? { uri: src.uri, decl, model: m, src } : null;
  }

  /** A file's top-level declaration named `name` that another file can import, or null. */
  function topLevel(model, name) {
    // A library's top-level type, enum or fn, or a file's contract (`import contract { C }`; JSC0708: a contract
    // exports nothing else).
    return model.declarations.find((d) => d.name === name && IMPORTABLE.has(d.kind) && d.parent < 0) ?? null;
  }

  /**
   * S1 on #115: an importer's occurrences of the import of `name`, from its model (an open buffer's current index; else
   * its text analysed with the project, cached by the engine, its text and the target's text for the request burst).
   * Ranges only: `{ ranges: [[l, c, el, ec, decl?]], errors, imports }` (imports: how many of its import declarations
   * name it; C1 on #115: zero means its model cannot show its uses).
   */
  async function importerOccurrences(src, sel, name, targetId, targetText, token) {
    const key = `${sel.engine?.id}|${hash(src.text)}|${hash(targetText ?? '')}|${name}|${targetId}`;
    if (!src.doc && occurrenceCache.has(key)) {
      const v = occurrenceCache.get(key);
      occurrenceCache.delete(key);
      occurrenceCache.set(key, v);
      return v;
    }
    const m = await indexOfSource(src, sel, token);
    if (!m) return null;
    const out = { ranges: [], errors: m.diagnostics.some((d) => d.severity === 'error'), imports: 0, model: src.doc ? m : null };
    for (const imp of m.declarations) {
      if (imp.kind !== 'import' || imp.name !== name || moduleOf(src.id, imp.detail) !== targetId) continue;
      out.imports++;
      for (const o of occurrences(m, imp.id)) {
        // C12, S7 on #115: an entry keeps at most MAX_LOCATIONS ranges (and says it was cut).
        if (out.ranges.length >= MAX_LOCATIONS) {
          out.truncated = true;
          break;
        }
        out.ranges.push([...o.range, o.decl === true]);
      }
    }
    if (!src.doc) {
      occurrenceCache.set(key, out);
      while (occurrenceCache.size > MAX_OCCURRENCE_CACHE) occurrenceCache.delete(occurrenceCache.keys().next().value);
    }
    return out;
  }
  const occurrenceCache = new Map();   // engine | texts' SHA-256 | name | target -> an importer's occurrences (LRU)

  /**
   * Every occurrence across the project of `decl`, a top-level declaration of the module `targetId` (the file `uri`'s
   * own when null; model `model`): its own file's, and in each file that imports it, the import's name and every use
   * through it. S1 on #115: at most MAX_CROSS_IMPORTERS importers are analysed (past it, an error), at most
   * MAX_LOCATIONS occurrences kept, the token checked between files, ranges kept (not models: an open importer's model
   * is its state's). `{ occurrences: [{ uri, range, decl }], importers: [{ src, errors, imports, model }], truncated }`
   * or `{ error }`.
   */
  async function occurrencesAcross(uri, model, decl, token, { targetId = null, targetText = null, from = uri } = {}) {
    const s = state.get(from);
    const sel = s?.selection ?? await selectionFor(from);
    // C12 on #115: the location budget holds during collection: the file's own occurrences count too.
    const own = occurrences(model, decl.id);
    const out = own.slice(0, MAX_LOCATIONS).map((o) => ({ uri, range: o.range, decl: o.decl === true }));
    if (own.length > MAX_LOCATIONS) return { occurrences: out, importers: [], truncated: true };
    const p = await projectImporters(from, sel, targetId, token);
    if (p.error) return { error: p.error, local: out };
    if (p.importers.length > MAX_CROSS_IMPORTERS) {
      return { error: `more than ${MAX_CROSS_IMPORTERS} files import it, more than the language server reads for this`, local: out };
    }
    const importers = [];
    let truncated = false;
    for (const src of p.importers) {
      if (token?.isCancellationRequested) return { error: 'cancelled', local: out };
      const occ = await importerOccurrences(src, sel, decl.name, targetId ?? p.id, targetText, token);
      if (!occ) return { error: `${src.id} could not be analysed`, local: out };
      importers.push({ src, errors: occ.errors, imports: occ.imports, model: occ.model });
      if (occ.truncated) truncated = true;
      for (const r of occ.ranges) {
        if (out.length >= MAX_LOCATIONS) {
          truncated = true;
          break;
        }
        // An importer's import is a reference to the declaration, not a declaration of it.
        out.push({ uri: src.uri, range: r.slice(0, 4), decl: false, src });
      }
      // S7 on #115: past the cap, nothing more can be returned: the scan stops.
      if (truncated) break;
    }
    return { occurrences: out, importers, truncated };
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
    const flight = { stale: false };
    inFlight.set(seq, { uris: new Set(snap.versions.keys()), disk: snap.disk, cts: token?.cts, flight });
    let r;
    try {
      r = await buildHost.request(`build:${engine.id}`, engine,
        { op: 'build', files: snap.files, options: {}, packages: snap.packages, javaLibraries: snap.javaLibraries },
        { scope: sel.root ?? sel.folder ?? '', input: snap.digest, what: 'the build', timeoutMs: BUILD_MS }, token);
    } finally {
      inFlight.delete(seq);
    }
    // C7 on #99: every buffer the snapshot read is still the same lifetime at the same version. C9 (r2): and no file it
    // read from disk is now open with other text (a buffer opened during the build).
    const openOnDisk = async () => {
      for (const d of documents.all()) {
        if (snap.versions.has(d.uri)) continue;
        const p = localFileOf(d.uri);
        const r = p && ((await real(p)) ?? p);
        if (r && snap.disk.has(r) && snap.disk.get(r) !== hash(d.getText())) return true;
      }
      return false;
    };
    const stale = async () => flight.stale || selector.generation !== gen
      || [...snap.versions].some(([u, v]) => documents.get(u)?.version !== v || state.get(u) !== snap.states.get(u))
      || await openOnDisk();
    if (!r.model) return { error: r.internal?.message ?? (r.cancelled ? 'cancelled' : r.unavailable ? 'builds are unavailable' : 'the build did not run') };
    if (await stale()) return { error: 'the sources changed while it built: not shown' };
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
    if (await stale()) return { error: 'the sources changed while it built: not shown' };
    // Published for every open document the snapshot read (or the build named), each with the version it read.
    const targets = new Set([...snap.versions.keys(), ...byUri.keys()]);
    for (const u of targets) {
      const st = state.get(u);
      const doc = documents.get(u);
      if (!st || !doc) continue;
      // C9 (r2): a document gets the build's results only for the buffer the snapshot read (its lifetime and version), or,
      // read from disk, while its buffer still holds exactly that text; never the recipient's own version instead.
      let version = snap.versions.get(u);
      if (version === undefined) {
        const p = localFileOf(u);
        const r = p && ((await real(p)) ?? p);
        if (!r || !snap.disk.has(r) || snap.disk.get(r) !== hash(doc.getText())) continue;
        version = doc.version;
      } else if (snap.states.get(u) !== st) continue;
      st.build = { seq, gen, version, diagnostics: byUri.get(u) ?? [] };
      builtDisk.set(seq, snap.disk);
      builtFrom.set(u, seq);
      if (st.lastPublished === st.build.version) {
        connection.sendDiagnostics({ uri: u, version: st.lastPublished, diagnostics: [...(st.lastDiagnostics ?? []), ...st.build.diagnostics].slice(0, MAX_PUBLISHED + 1) });
      }
    }
    // Only the snapshots still shown are kept.
    const shown = new Set([...state.values()].map((st) => st.build?.seq).filter((x) => x !== undefined));
    for (const k of builtDisk.keys()) if (!shown.has(k)) builtDisk.delete(k);
    return { ok: result.ok === true && errors === 0, errors, contracts: (result.contracts ?? []).length };
  }

  // D4, D10, Q7: on save, in trusted project-matched npm projects only (a user setting can turn it off).
  // F7 on #99: coalesced: one build in flight and at most one pending per project (the latest save's); a build whose
  // inputs change while it runs is cancelled (withdrawBuilds).
  // C12 on #99 r2: per project (its root, else its contracts directory): a save replaces only its own project's pending
  // build; each project with a pending save gets its build, in the order saved.
  let saveRunning = false;
  const savePending = new Map();   // project -> { uri, sel }: the latest save of each
  async function buildOnSaveLoop(next) {
    const key = next.sel.root ?? next.sel.contracts ?? next.uri;
    savePending.delete(key);
    savePending.set(key, next);
    if (saveRunning) return;
    saveRunning = true;
    try {
      while (savePending.size) {
        const [key0, { uri, sel }] = savePending.entries().next().value;
        savePending.delete(key0);
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
    // C7 on #115: a file on disk changed: every cached importer analysis goes.
    occurrenceCache.clear();
    // C3 on #115: a java: library source changed on disk: its importers again, and the builds that read it end.
    const java = changes.filter((ch) => isJavaInput(ch.uri));
    if (java.length) {
      libraryMemo.clear();
      // C9 on #115: a library source that appears or goes has no edge yet: every file with a java: import again.
      for (const [u, st] of state) if (st.javaImports) schedule(u, DEBOUNCE_MS * 2);
      for (const ch of java) {
        const f = localFileOf(ch.uri);
        if (!f || documents.get(ch.uri)) continue;
        const folder = [...state.values()].map((st) => st.selection?.folder).find((d) => d && inside(f, d));
        if (!folder) continue;
        const r = await reader.resolveIn(folder, relative(folder, f).split(sep).join('/'));
        rescheduleImporters([f, r.error ? null : r.path]);
        const now = r.error ? null : await reader.read(folder, relative(folder, f).split(sep).join('/'), { cap: MAX_FILE_CHARS * 2 });
        await invalidateInput(r.error ? f : r.path, now && !now.error ? now.bytes.toString('utf8') : null, { resolve: !r.error });
      }
    }
    const jsc = changes.filter((ch) => /\.jsc$/.test(ch.uri));
    if (changes.length > jsc.length + java.length || !changes.length) {
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
      // C14 (#114): a build that read other text from the file no longer describes it (a save writes the text the build
      // on save read: the same hash, nothing ends).
      const now = r.error ? null : await reader.read(folder, relative(folder, f).split(sep).join('/'), { cap: MAX_FILE_CHARS * 2 });
      await invalidateInput(r.error ? f : r.path, now && !now.error ? now.bytes.toString('utf8') : null, { resolve: !r.error });
    }
  });

  if (flags.testHooks) {
    connection.onRequest('joust/test/stats', () => ({
      ...host.stats, builds: { ...buildHost.stats }, reader: { ...reader.stats }, quarantined: host.quarantine.size, slots: [...host.slots.values()].map((s) => ({ key: s.key, ...host.status(s.key), alive: !!(s.worker && !s.worker.dead), booted: !!s.worker?.booted, file: s.engine.file, calls: s.worker?.calls ?? 0 })),
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
