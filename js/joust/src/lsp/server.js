// The .jsc language server's protocol layer (ADR-0021 D2): vscode-languageserver 11.0.0, pinned exactly. It keeps the
// open buffers, debounces, and answers from the document model; it holds no engine (every engine runs in a worker,
// host.js). M5a: diagnostics as you type (the first fatal error, or the warnings), document symbols, folding and
// selection ranges, versioned diagnostics (D4a, L8), modes and engine selection (D3, D3a, D3b, select.js), the input
// policy (D4b, inputs.js), and, for clients that apply edits through a verified path, two quick fixes applied only
// through a versioned workspace/applyEdit (D4a, D7).
import { randomBytes } from 'node:crypto';
import { promises as fsp, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { basename, dirname, isAbsolute, join } from 'node:path';
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
import { inside } from './paths.js';
import { createSelector, createStore, ENGINE_JS, ENGINE_WASM, engineId, workerKey } from './select.js';
import { createHash } from 'node:crypto';

/** D4: a file over this many characters is not analysed (one diagnostic says so). */
export const MAX_FILE_CHARS = 1024 * 1024;
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
  const host = new EngineHost({ workerEntry, ...hostOptions });
  const reader = createReader();
  const state = new Map();   // uri -> { lastPublished, model, outlineModel, timer, cts, selection }
  const fixes = new Map();   // opaque single-use fix id -> { uri, version, code, range, issued }
  let selector;
  let bundled;
  let isVscode = false;
  let edits = false;
  let unavailableShown = false;

  const log = (m) => connection.console.log(m);

  connection.onInitialize(async (params) => {
    const io = params.initializationOptions ?? {};
    isVscode = io.client === 'vscode-joust';
    // Other editors have no workspace trust: their project's engine loads only with --project-engine (D3b).
    const trusted = isVscode ? io.trusted === true : flags.projectEngine === true;
    const ws = params.capabilities?.workspace ?? {};
    const transactional = ['transactional', 'textOnlyTransactional'].includes(ws.workspaceEdit?.failureHandling);
    edits = (isVscode || flags.enableEdits === true) && ws.applyEdit === true && ws.workspaceEdit?.documentChanges === true && transactional;

    const folderPaths = (params.workspaceFolders ?? []).map((f) => f.uri).filter((u) => u.startsWith('file:')).map((u) => fileURLToPath(u));
    const folders = (await Promise.all(folderPaths.map(real))).filter(Boolean);
    const wsFile = typeof io.workspaceFile === 'string' && isAbsolute(io.workspaceFile) ? await real(dirname(io.workspaceFile)) : null;
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

    selector = createSelector({
      reader, store: storage ? createStore(storage) : { put: async () => { throw new Error('no private storage outside the workspace'); } },
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
    if (!uri.startsWith('file:')) return selector.select(null);
    return selector.select(await real(fileURLToPath(uri)));
  }

  function statusOf(uri, sel) {
    const h = host.status(sel.key);
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
    s.timer = setTimeout(() => analyse(uri).catch((e) => log(`analysis of ${uri} failed: ${e?.stack ?? e}`)), delay);
  }

  async function analyse(uri) {
    const doc = documents.get(uri);
    const s = state.get(uri);
    if (!doc || !s) return;
    const version = doc.version;
    const text = doc.getText();
    const gen = selector.generation;
    const sel = await selectionFor(uri);
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
    const file = uri.startsWith('file:') ? basename(fileURLToPath(uri)) : 'Untitled.jsc';
    const r = await host.analyze(sel.key, sel.engine, { file, source: text }, cts.token);
    if (s.cts === cts) s.cts = undefined;
    // D4a: a result is published only if nothing it read changed since: the buffer's version and the selection.
    const now = documents.get(uri);
    if (!now || now.version !== version || selector.generation !== gen || r.cancelled) return;
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
    s.model = model;
    s.modelVersion = version;
    if (model.declarations.length) s.outlineModel = model;
    publish(uri, version, toDiagnostics(model, label));
  }

  documents.onDidOpen((e) => {
    state.set(e.document.uri, { lastPublished: null });
    schedule(e.document.uri, 0);
  });
  documents.onDidChangeContent((e) => {
    if (!state.has(e.document.uri)) state.set(e.document.uri, { lastPublished: null });
    const s = state.get(e.document.uri);
    // An analysis of an older version still running is cancelled (after the grace period its worker is replaced).
    s.cts?.cancel();
    schedule(e.document.uri);
  });
  documents.onDidClose((e) => {
    const s = state.get(e.document.uri);
    if (s) {
      clearTimeout(s.timer);
      s.cts?.cancel();
    }
    state.delete(e.document.uri);
    for (const [id, f] of fixes) if (f.uri === e.document.uri) fixes.delete(id);
    connection.sendDiagnostics({ uri: e.document.uri, diagnostics: [] });
  });

  const modelOf = (uri) => {
    const s = state.get(uri);
    return s?.outlineModel ?? s?.model;
  };

  connection.onDocumentSymbol((p) => {
    const m = modelOf(p.textDocument.uri);
    return m ? documentSymbols(m) : [];
  });
  connection.onFoldingRanges((p) => {
    const m = modelOf(p.textDocument.uri);
    return m ? foldingRanges(m) : [];
  });
  connection.onSelectionRanges((p) => {
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
    if (!doc || !s?.model || s.modelVersion !== doc.version) return [];
    const out = [];
    for (const d of s.model.diagnostics) {
      if (d.severity !== 'error' || d.internal) continue;
      const r = d.range;
      if (r[0] > p.range.end.line || r[2] < p.range.start.line) continue;
      const fix = fixFor(doc.getText(), d, doc.getText({ start: { line: r[0], character: 0 }, end: { line: r[0] + 1, character: 0 } }));
      if (!fix) continue;
      const id = randomBytes(18).toString('base64url');
      fixes.set(id, { uri: p.textDocument.uri, version: doc.version, code: d.code, range: r, issued: Date.now() });
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
    if (!doc || doc.version !== f.version || !s?.model || s.modelVersion !== f.version) return { applied: false, reason: 'stale fix' };
    // Recomputed on the current snapshot, then re-checked by the engine (L6: the diagnostic goes and none is added).
    const d = s.model.diagnostics.find((x) => x.code === f.code && x.range.join() === f.range.join());
    if (!d) return { applied: false, reason: 'stale fix' };
    const text = doc.getText();
    const fix = fixFor(text, d, doc.getText({ start: { line: d.range[0], character: 0 }, end: { line: d.range[0] + 1, character: 0 } }));
    if (!fix) return { applied: false, reason: 'stale fix' };
    const fixed = TextDocument.applyEdits(doc, fix.edits);
    const r = await host.analyze(s.selection.key, s.selection.engine, { file: f.uri.startsWith('file:') ? basename(fileURLToPath(f.uri)) : 'Untitled.jsc', source: fixed }, token);
    if (!r.model) return { applied: false, reason: 'the fix could not be checked' };
    const after = parseModel(r.model);
    const at = (x) => x.line * 1e6 + x.col;
    const fixedAt = (d.range[0] + 1) * 1e6 + d.range[1] + 1;
    // M5a's model holds the first error only: the fix's diagnostic must be gone, and no error may come before it (any
    // later error was hidden behind it, not added).
    const bad = after.diagnostics.some((x) => x.severity === 'error' && (x.internal || (x.code === d.code && x.range.join() === d.range.join())
      || at(x) < fixedAt));
    if (bad) return { applied: false, reason: 'the fix did not check' };
    const still = documents.get(f.uri);
    if (!still || still.version !== f.version) return { applied: false, reason: 'stale fix' };
    // D4a: applied by the client only through a versioned workspace/applyEdit (the extension's handleApplyEdit refuses
    // it if the document is closed or has another version).
    const res = await connection.workspace.applyEdit({ label: fix.title, edit: { documentChanges: [{ textDocument: { uri: f.uri, version: f.version }, edits: fix.edits }] } });
    return { applied: res.applied === true };
  });

  connection.onRequest('joust/status', async (p) => {
    const uri = p?.uri;
    if (uri && documents.get(uri)) return statusOf(uri, await selectionFor(uri));
    return { server: serverVersion, compiler: bundled?.version, source: 'bundled', mode: 'advisory', trusted: selector?.trusted ?? false, state: 'ok' };
  });

  connection.onRequest('joust/restartAnalysis', () => {
    host.restart();
    unavailableShown = false;
    for (const uri of state.keys()) schedule(uri, 0);
    return { restarted: true };
  });

  // Trust granted mid-session (D3): select again, restart the workers on the project's engine when now eligible.
  connection.onNotification('joust/trust', (p) => {
    if (!isVscode || p?.trusted !== true) return;
    selector.invalidate({ trusted: true });
    for (const uri of state.keys()) schedule(uri, 0);
  });

  connection.onDidChangeWatchedFiles(() => {
    selector?.invalidate();
    for (const uri of state.keys()) schedule(uri, 0);
  });

  if (flags.testHooks) {
    connection.onRequest('joust/test/stats', () => ({
      ...host.stats, quarantined: host.quarantine.size, slots: [...host.slots.values()].map((s) => ({ key: s.key, ...host.status(s.key), alive: !!(s.worker && !s.worker.dead), calls: s.worker?.calls ?? 0 })),
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
