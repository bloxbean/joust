// A JSON-RPC client that starts `joust-lsp --stdio` and drives it as an editor would (ADR-0021 D12, protocol tests).
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createMessageConnection, StreamMessageReader, StreamMessageWriter } from 'vscode-jsonrpc/node';

export const BIN = fileURLToPath(new URL('../../bin/joust-lsp.js', import.meta.url));
export const ENGINE = fileURLToPath(new URL('../../engine/', import.meta.url));
export const STUB = fileURLToPath(new URL('./fixtures/stub-engine/', import.meta.url));
export const OLD = fileURLToPath(new URL('./fixtures/old-engine/', import.meta.url));
export const HAS_ENGINE = existsSync(join(ENGINE, 'joust-toolchain.js.wasm'));

export const tmp = (p = 'joust-lsp-') => realpathSync(mkdtempSync(join(tmpdir(), p)));
export const uriOf = (p) => pathToFileURL(p).href;

/** The capabilities VS Code's client (vscode-languageclient 11) declares for the parts the server looks at. */
export const VSCODE_CAPS = {
  workspace: { applyEdit: true, workspaceEdit: { documentChanges: true, failureHandling: 'textOnlyTransactional' } },
  textDocument: { publishDiagnostics: { versionSupport: false } },
};

export async function startLsp({ engineDir = ENGINE, storage = tmp('joust-lsp-store-'), args = [], hostOptions, initOptions,
  capabilities = {}, folders = [], workspaceFolders, applyEdit } = {}) {
  const env = { ...process.env, JOUST_LSP_TEST_HOOKS: '1', ...(hostOptions ? { JOUST_LSP_HOST_OPTIONS: JSON.stringify(hostOptions) } : {}) };
  const child = spawn(process.execPath, [BIN, '--stdio', '--engine-dir', engineDir, '--storage', storage, ...args], { env, stdio: ['pipe', 'pipe', 'pipe'] });
  let stderr = '';
  child.stderr.on('data', (d) => { stderr += d; });
  let stopping = false;
  // A server that dies on its own is a test failure worth seeing: its stderr goes to the test's.
  child.once('exit', (code, signal) => { if (!stopping) console.error(`joust-lsp exited (${code ?? signal}):\n${stderr.slice(-4000)}`); });
  const conn = createMessageConnection(new StreamMessageReader(child.stdout), new StreamMessageWriter(child.stdin));
  const published = [];
  const statuses = [];
  const waiters = new Set();
  const notify = () => { for (const w of [...waiters]) w(); };
  conn.onNotification('textDocument/publishDiagnostics', (p) => { published.push(p); notify(); });
  conn.onNotification('joust/status', (p) => { statuses.push(p); notify(); });
  conn.onNotification('window/logMessage', () => {});
  conn.onNotification('window/showMessage', (p) => { statuses.push({ shown: p.message }); notify(); });
  const applied = [];
  conn.onRequest('workspace/applyEdit', (p) => { applied.push(p); return applyEdit ? applyEdit(p) : { applied: true }; });
  conn.onRequest('client/registerCapability', () => null);
  conn.onRequest('workspace/configuration', () => []);
  conn.listen();
  const init = await conn.sendRequest('initialize', {
    processId: process.pid, rootUri: folders[0] ? uriOf(folders[0]) : null, capabilities,
    workspaceFolders: workspaceFolders ?? folders.map((f) => ({ uri: uriOf(f), name: f })), initializationOptions: initOptions,
  });
  conn.sendNotification('initialized', {});

  const until = (pred, ms = 15000, what = 'a condition') => new Promise((resolve, reject) => {
    const check = () => {
      const v = pred();
      if (v) {
        waiters.delete(check);
        clearTimeout(t);
        resolve(v);
      }
    };
    const t = setTimeout(() => { waiters.delete(check); reject(new Error(`timed out waiting for ${what}; stderr: ${stderr.slice(-2000)}`)); }, ms);
    waiters.add(check);
    check();
  });
  const lsp = {
    conn, child, init, published, statuses, applied, storage,
    get stderr() { return stderr; },
    until,
    open(uri, text, version = 1) {
      conn.sendNotification('textDocument/didOpen', { textDocument: { uri, languageId: 'jsc', version, text } });
    },
    change(uri, version, text) {
      conn.sendNotification('textDocument/didChange', { textDocument: { uri, version }, contentChanges: [{ text }] });
    },
    close(uri) {
      conn.sendNotification('textDocument/didClose', { textDocument: { uri } });
    },
    /** The first diagnostics published for `uri` at `version` (or later). */
    diagnostics(uri, version, ms) {
      return until(() => published.find((p) => p.uri === uri && p.version >= version), ms, `diagnostics of ${uri} v${version}`);
    },
    request: (m, p) => conn.sendRequest(m, p),
    stats: () => conn.sendRequest('joust/test/stats'),
    async stop() {
      stopping = true;
      try {
        await Promise.race([conn.sendRequest('shutdown'), new Promise((r) => setTimeout(r, 3000))]);
        conn.sendNotification('exit');
      } catch {
        // already gone
      }
      await new Promise((r) => { if (child.exitCode !== null) r(); else { child.once('exit', r); setTimeout(() => { child.kill('SIGKILL'); r(); }, 3000); } });
      conn.dispose();
    },
  };
  return lsp;
}

export const COUNTER = `jsc 1
package app

contract Counter(maxDatumBytes = 128) {
  state { count: Int, owner: Key }

  init() {
    return { count: 0, owner: caller }
  }

  action increment(by: Int) {
    require(by > 0, "increment must be positive")
    return { ...state, count: count + by }
  }

  destroy close() only owner {
    pay(owner, balance)
  }

  view count(): Int = count
}
`;
