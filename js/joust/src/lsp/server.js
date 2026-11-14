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
import { fileURLToPath } from 'node:url';
import {
  CancellationTokenSource, CodeActionKind, CodeActionTriggerKind, TextDocumentSyncKind, TextDocuments,
} from 'vscode-languageserver/node';
import { TextDocument } from 'vscode-languageserver-textdocument';
import { EngineHost } from './host.js';
import { createReader } from './inputs.js';
import {
  diagnostics as toDiagnostics, documentSymbols, fixFor, foldingRanges, internalDiagnostic, parseModel, selectionRanges,
} from './model.js';
import { inside, localPath } from './paths.js';
import { createSelector, createStore, ENGINE_JS, ENGINE_WASM, engineId, workerKey } from './select.js';
import { createHash } from 'node:crypto';

/** D4: a file over this many characters is not analysed (one diagnostic says so). */
export const MAX_FILE_CHARS = 1024 * 1024;

/** The project files one analysis reads at most (its imports, transitively; the integration with ADR-0018 M2c, M4). */
export const MAX_PROJECT_FILES = 64;

export const MAX_PROJECT_CHARS = 8 * 1024 * 1024;
export const DEBOUNCE_MS = 150;
export const SERVER_NAME = 'joust-lsp';
const FIX_COMMAND = 'joust.applyFix';

const hash = (b) => createHash('sha256').update(b).digest('hex');

/** The advertised capabilities (D10's allow-list for M5a): nothing else is ever advertised. */
export function capabilities({ edits }) {
  return {
    // Open, change, close and save; no willSave and no willSaveWaitUntil (S18).
    textDocumentSync: { openClose: true, change: TextDocumentSyncKind.Incremental, save: { includeText: false } },
    documentSymbolProvider: true,
    foldingRangeProvider: true,
    selectionRangeProvider: true,
    // Code actions only to clients that apply edits through a verified, versioned path (D4a): the VS Code extension's
    // handleApplyEdit, or another client enabled by flag. Offered only on Invoked requests (D7, C8).
    ...(edits ? { codeActionProvider: { codeActionKinds: [CodeActionKind.QuickFix] }, executeCommandProvider: { commands: [FIX_COMMAND] } } : {}),
    experimental: { joust: { protocol: 1, modelVersion: 1 } },
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
  const reader = createReader();
  // uri -> the state of ONE open lifetime of the document (review C2 on #83): every asynchronous result checks that the
  // state it started with is still the current one, so a close and a reopen at the same version never mix lifetimes.
  const state = new Map();   // uri -> { lastPublished, model, modelVersion, modelGen, outlineModel, timer, cts, selection }
  const fixes = new Map();   // opaque single-use fix id -> { uri, st, version, gen, engineId, code, range, issued }
  let selector;
  let bundled;
  let isVscode = false;
  let edits = false;
  let unavailableShown = false;

  const log = (m) => connection.console.log(m);
  const missing = new Map();   // engine id -> consecutive missing-copy results (S10)

  /** Everything selected again: every open document re-analysed (C14), outstanding fixes invalid (C4). */
  function reselectAll(delay = 0) {
    selector.invalidate();
    fixes.clear();
    for (const uri of state.keys()) schedule(uri, delay);
  }

  connection.onInitialize(async (params) => {
    const io = params.initializationOptions ?? {};
    isVscode = io.client === 'vscode-joust';
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
      capabilities: capabilities({ edits }),
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
    connection.sendDiagnostics({ uri, version, diagnostics: diags });
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
   * The project files a document's imports reach (the integration of M5a with ADR-0018 M2c and M4; L1): the document's
   * module id under its project's contracts directory, and every .jsc file its relative imports (`import { … } from
   * "./fees.jsc"`, `import contract { … } from "./Oracle.jsc"`) and npm package imports (`"@acme/fees/fees.jsc"`, a
   * package's joust-lib/) reach, transitively, by the build's module ids, so the engine resolves them as the build does.
   * Read under D4b's input policy (inside the folder, links checked, at most MAX_FILE_CHARS each), an open document's
   * buffer before its file, at most MAX_PROJECT_FILES of them. null: the file is not in a project's contracts directory
   * (analysed on its own, as before). java: imports are not read: the build certifies their Java sources, which the
   * server does not (M5c).
   */
  async function projectOf(uri, text, sel) {
    if (!sel.contracts || !sel.folder) return null;
    const file = localFileOf(uri);
    const realFile = file ? await real(file) : null;
    if (!realFile || !inside(realFile, sel.contracts) || !realFile.endsWith('.jsc')) return null;
    const id = relative(sel.contracts, realFile).split(sep).join('/');
    const open = new Map();
    for (const d of documents.all()) {
      const p = localFileOf(d.uri);
      const r = p && d.uri !== uri ? await real(p) : null;
      if (r) open.set(r, d.getText());
    }
    const files = { [id]: text };
    let chars = text.length;
    const todo = [id];
    const PACKAGE = /^(@[a-z0-9][a-z0-9._~-]*\/)?[a-z0-9][a-z0-9._~-]*\/.+\.jsc$/;
    const pkgOf = (spec) => (spec.startsWith('@') ? spec.split('/').slice(0, 2).join('/') : spec.split('/')[0]);
    while (todo.length && Object.keys(files).length < MAX_PROJECT_FILES) {
      const at = todo.shift();
      for (const m of files[at].matchAll(/\bimport\s+(?:contract\s*)?\{[^}]*\}\s*from\s*"([^"\\\n]*)"/g)) {
        const spec = m[1];
        let target;
        let path;
        if (spec.startsWith('./') || spec.startsWith('../')) {
          if (at.startsWith('pkg:')) {
            // Inside a package, a relative import stays inside it (JscModules).
            const inner = at.slice(4);
            const pkg = pkgOf(inner);
            const rel = posix.normalize(posix.join(posix.dirname(inner.slice(pkg.length + 1)), spec));
            if (rel.startsWith('..')) continue;
            target = `pkg:${pkg}/${rel}`;
            path = join(sel.root ?? sel.contracts, 'node_modules', ...pkg.split('/'), 'joust-lib', ...rel.split('/'));
          } else {
            const rel = posix.normalize(posix.join(posix.dirname(at), spec));
            if (rel.startsWith('..') || !rel.endsWith('.jsc')) continue;
            target = rel;
            path = join(sel.contracts, ...rel.split('/'));
          }
        } else if (PACKAGE.test(spec) && !spec.split('/').some((x) => x === '.' || x === '..') && sel.root) {
          const pkg = pkgOf(spec);
          target = `pkg:${spec}`;
          path = join(sel.root, 'node_modules', ...pkg.split('/'), 'joust-lib', ...spec.slice(pkg.length + 1).split('/'));
        } else {
          continue;   // std/… and java:… are the engine's, not files
        }
        if (target in files) continue;
        const realPath = await real(path);
        let body = realPath ? open.get(realPath) : undefined;
        if (body === undefined) {
          const r = await reader.read(sel.folder, relative(sel.folder, path).split(sep).join('/'), { cap: MAX_FILE_CHARS });
          if (r.error) continue;   // missing or refused: the engine reports the import as the build does
          body = r.bytes.toString('utf8');
        }
        if (chars + body.length > MAX_PROJECT_CHARS) return { id, files };
        chars += body.length;
        files[target] = body;
        todo.push(target);
        if (Object.keys(files).length >= MAX_PROJECT_FILES) break;
      }
    }
    return { id, files };
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
    s.selection = sel;
    connection.sendNotification('joust/status', statusOf(uri, sel));
    const label = sourceLabel(sel);
    if (text.length > MAX_FILE_CHARS) {
      publish(uri, version, [internalDiagnostic(`analysis skipped: this file is larger than the language server reads (${MAX_FILE_CHARS} characters)`, label)]);
      return;
    }
    // A newer analysis of this document supersedes this one (D2a: cancellation, not failure).
    s.cts?.cancel();
    const cts = new CancellationTokenSource();
    s.cts = cts;
    const file = fileNameOf(uri);
    const project = await projectOf(uri, text, sel);
    if (state.get(uri) !== s || s.seq !== seq || documents.get(uri)?.version !== version) return;
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
      connection.sendNotification('joust/status', statusOf(uri, sel));
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
      connection.sendNotification('joust/status', statusOf(uri, sel));
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
    s.modelEngine = sel.engine.id;
    // C9: an accepted model replaces the outline even when it has no declarations (a v1 file, an emptied contract); a
    // file the front end stopped in early keeps the last outline only while its own model has none.
    if (model.declarations.length || model.accepted) s.outlineModel = model;
    publish(uri, version, toDiagnostics(model, label));
    settle(s);
    // The status says which version has a model (the extension's tests wait for it, F12).
    connection.sendNotification('joust/status', { ...statusOf(uri, sel), modelVersion: version });
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
  });
  documents.onDidChangeContent((e) => {
    if (!state.has(e.document.uri)) state.set(e.document.uri, { lastPublished: null });
    const s = state.get(e.document.uri);
    // An analysis of an older version still running is cancelled (after the grace period its worker is replaced).
    s.cts?.cancel();
    schedule(e.document.uri);
    // Another open .jsc file may import this one (a library, a contract it reads): analysed again after it, so its
    // diagnostics follow the buffer (the integration with ADR-0018 M2c and M4).
    if (e.document.uri.endsWith('.jsc')) for (const other of state.keys()) if (other !== e.document.uri) schedule(other, DEBOUNCE_MS * 2);
  });
  documents.onDidClose((e) => {
    const s = state.get(e.document.uri);
    if (s) {
      clearTimeout(s.timer);
      s.cts?.cancel();
    }
    state.delete(e.document.uri);
    if (s) settle(s);
    // A close ends the lifetime: its unexecuted fixes go, and executing ones fail their identity check (C2).
    for (const [id, f] of fixes) if (f.uri === e.document.uri) fixes.delete(id);
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
    if (!edits || p.command !== FIX_COMMAND) return { applied: false, reason: 'unknown command' };
    // S17: exactly one argument, an opaque id this server issued, used once; no edit text, no paths.
    if (!Array.isArray(p.arguments) || p.arguments.length !== 1 || typeof p.arguments[0] !== 'string') return { applied: false, reason: 'refused' };
    const id = p.arguments[0];
    const f = fixes.get(id);
    fixes.delete(id);
    if (!f || Date.now() - f.issued > 10 * 60 * 1000) return { applied: false, reason: 'unknown or used fix' };
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
    const sel = s.selection;
    const r = await host.analyze(sel.key, sel.engine, { file: fileNameOf(f.uri), source: fixed, scope: scopeOf(sel) }, token);
    if (!r.model) return { applied: false, reason: 'the fix could not be checked' };
    if (!current()) return { applied: false, reason: 'stale fix' };
    const after = parseModel(r.model);
    const at = (x) => x.line * 1e6 + x.col;
    const fixedAt = (d.range[0] + 1) * 1e6 + d.range[1] + 1;
    // M5a's model holds the first error only: the fix's diagnostic must be gone, and no error may come at or before its
    // position (one there is the fix's own doing, review S2 on #83: `let k` becoming `const k` that hides a state field);
    // a later error was hidden behind it, not added.
    const bad = after.diagnostics.some((x) => x.severity === 'error' && (x.internal || (x.code === d.code && x.range.join() === d.range.join())
      || at(x) <= fixedAt));
    if (bad) return { applied: false, reason: 'the fix did not check' };
    if (!current()) return { applied: false, reason: 'stale fix' };
    // D4a: applied by the client only through a versioned workspace/applyEdit (the extension's handleApplyEdit refuses
    // it if the document is closed or has another version).
    // S9, C19 on #83: the SHA-256 of the whole document text the fix was validated on goes along (joustExpected), and the
    // extension's handler compares it with the document right before applying: a close and reopen at a reused version
    // with other text anywhere (not only in the replaced range) refuses the edit.
    const expected = [{ uri: f.uri, sha256: hash(text) }];
    const res = await connection.workspace.applyEdit({ label: fix.title, edit: { documentChanges: [{ textDocument: { uri: f.uri, version: f.version },
      edits: fix.edits }] }, joustExpected: expected });
    return { applied: res.applied === true };
  });

  connection.onRequest('joust/status', async (p) => {
    const uri = p?.uri;
    if (uri && documents.get(uri)) return statusOf(uri, await selectionFor(uri));
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
  connection.onNotification('joust/trust', (p) => {
    if (!isVscode || p?.trusted !== true) return;
    selector.invalidate({ trusted: true });
    fixes.clear();
    for (const uri of state.keys()) schedule(uri, 0);
  });

  connection.onDidChangeWatchedFiles(() => {
    if (selector) reselectAll();
  });

  if (flags.testHooks) {
    connection.onRequest('joust/test/stats', () => ({
      ...host.stats, quarantined: host.quarantine.size, slots: [...host.slots.values()].map((s) => ({ key: s.key, ...host.status(s.key), alive: !!(s.worker && !s.worker.dead), booted: !!s.worker?.booted, file: s.engine.file, calls: s.worker?.calls ?? 0 })),
    }));
  }

  connection.onShutdown(() => host.dispose());
  connection.onExit(() => {
    host.dispose();
    process.exit(0);
  });

  // A failure in a handler is logged, never the end of the server (L9).
  process.on('unhandledRejection', (e) => log(`unhandled: ${e?.stack ?? e}`));
  documents.listen(connection);
  connection.listen();
  return { host, documents };
}
