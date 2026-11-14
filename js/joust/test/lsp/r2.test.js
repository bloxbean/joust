// Review round r1 on #83: the cases the reviews' probes found, kept as tests.
//   C2  a close and a reopen at the same version never mixes lifetimes (stale analyses, stale fixes);
//   C3  more compilers than worker slots: no live analysis is dropped; a stop during startup is no engine failure;
//   C4  a fix checked under one selection is refused once the selection changed;
//   C5  concurrent selections of a new project all choose its compiler (installing coalesced);
//   C6  Restart analysis selects again, so replaced engine bytes are seen;
//   C7  the store bound holds with the real host's lifecycle;
//   S1  a hostile Gradle build file cannot stall selection;
//   S4  a folder URI with a host is ignored, not fatal;
//   S5  an unresolvable .code-workspace directory fails closed;
//   S7  "unavailable" is per project.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { EngineHost } from '../../src/lsp/host.js';
import { createReader } from '../../src/lsp/inputs.js';
import { createSelector, createStore, ENGINE_JS, ENGINE_WASM } from '../../src/lsp/select.js';
import { startLsp, STUB, tmp, uriOf, VSCODE_CAPS } from './client.js';

const WORKER = new URL('../../src/lsp/worker.js', import.meta.url);
const FAST = { analysisMs: 600, graceMs: 200, backoffMs: 100, backoffMaxMs: 400 };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** A copy of the stub engine whose bytes (and reported version) differ by `tag`. */
function stubVariant(dir, tag) {
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, ENGINE_JS), readFileSync(join(STUB, ENGINE_JS), 'utf8').replaceAll('test-stub', `test-stub-${tag}`));
  copyFileSync(join(STUB, ENGINE_WASM), join(dir, ENGINE_WASM));
  copyFileSync(join(STUB, 'package.json'), join(dir, 'package.json'));
  return dir;
}

function project(root, engineFrom = STUB) {
  mkdirSync(join(root, 'contracts'), { recursive: true });
  writeFileSync(join(root, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '0.0.0' } }));
  writeFileSync(join(root, 'joust.config.json'), '{ "contracts": "contracts" }');
  const pkg = join(root, 'node_modules', '@julclang', 'joust');
  mkdirSync(join(pkg, 'engine'), { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: '@julclang/joust', version: '9.9.9' }));
  for (const f of [ENGINE_JS, ENGINE_WASM, 'package.json']) copyFileSync(join(engineFrom, f), join(pkg, 'engine', f));
  return pkg;
}

const actions = (lsp, uri, diagnostics) => lsp.request('textDocument/codeAction', {
  textDocument: { uri }, range: { start: { line: 0, character: 0 }, end: { line: 9, character: 0 } }, context: { diagnostics, triggerKind: 1 },
});

test('C2: a slow analysis of a closed lifetime never publishes into the reopened document', async () => {
  const dir = tmp();
  const lsp = await startLsp({ engineDir: STUB, folders: [dir], hostOptions: { ...FAST, analysisMs: 5000 } });
  try {
    const uri = uriOf(join(dir, 'R.jsc'));
    lsp.open(uri, 'jsc 2 SLOW WARN', 1);
    await sleep(400);
    lsp.close(uri);
    lsp.open(uri, 'jsc 2 CLEAN', 1);
    await lsp.until(() => lsp.published.find((p) => p.uri === uri && p.version === 1 && p.diagnostics.length === 0), 10000, 'the new lifetime');
    await sleep(2000);
    assert.ok(!lsp.published.some((p) => p.uri === uri && p.diagnostics.some((d) => /stub warning/.test(d.message))),
      JSON.stringify(lsp.published));
  } finally {
    await lsp.stop();
  }
});

test('C2, C4: a fix is refused when its document was reopened, or its selection changed, while it was checked', async () => {
  const dir = tmp();
  for (const change of ['reopen', 'selection']) {
    const lsp = await startLsp({ engineDir: STUB, folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust' },
      hostOptions: { ...FAST, analysisMs: 5000 } });
    try {
      const uri = uriOf(join(dir, 'F.jsc'));
      lsp.open(uri, 'jsc 2 SLOWCHECK\n  let x = 1', 1);
      const p = await lsp.diagnostics(uri, 1);
      assert.equal(p.diagnostics[0].code, 'JSC0128');
      const [a] = await actions(lsp, uri, p.diagnostics);
      const pending = lsp.request('workspace/executeCommand', { command: 'joust.applyFix', arguments: a.command.arguments });
      await sleep(300);   // the fix's check (1.5 s) is running
      if (change === 'reopen') {
        lsp.close(uri);
        lsp.open(uri, 'completely different contents', 1);
      } else {
        lsp.conn.sendNotification('workspace/didChangeWatchedFiles', { changes: [{ uri: uriOf(join(dir, 'package.json')), type: 2 }] });
      }
      assert.deepEqual(await pending, { applied: false, reason: 'stale fix' }, change);
      assert.equal(lsp.applied.length, 0, change);
    } finally {
      await lsp.stop();
    }
  }
});

test('C6: Restart analysis selects again and loads replaced engine bytes', async () => {
  const ws = tmp('joust-lsp-ws-');
  const pkg = project(ws, stubVariant(join(tmp(), 'a'), 'a'));
  const lsp = await startLsp({ engineDir: STUB, folders: [ws], args: ['--project-engine'] });
  try {
    const uri = uriOf(join(ws, 'contracts', 'C.jsc'));
    lsp.open(uri, 'jsc 2 WARN', 1);
    await lsp.diagnostics(uri, 1);
    assert.equal((await lsp.request('joust/status', { uri })).compiler, 'test-stub-a');
    // Only the engine's bytes change; package.json does not.
    writeFileSync(join(pkg, 'engine', ENGINE_JS), readFileSync(join(STUB, ENGINE_JS), 'utf8').replaceAll('test-stub', 'test-stub-b'));
    await lsp.request('joust/restartAnalysis');
    const st = await lsp.request('joust/status', { uri });
    assert.equal(st.compiler, 'test-stub-b');
    assert.equal(st.mode, 'project-matched');
  } finally {
    await lsp.stop();
  }
});

test('C3: more compilers than worker slots: every analysis completes, with no failure', async () => {
  const base = tmp();
  const engines = ['x', 'y', 'z'].map((t) => ({ file: join(stubVariant(join(base, t), t), ENGINE_JS), id: `id-${t}` }));
  const host = new EngineHost({ workerEntry: WORKER, maxWorkers: 2, analysisMs: 10000 });
  try {
    const rs = await Promise.all(engines.map((e) => host.analyze(`engine:${e.id}`, e, { file: 'S.jsc', source: 'jsc 2 SLOW WARN' })));
    for (const r of rs) assert.ok(r.model, JSON.stringify(r));
    assert.equal(host.stats.failures, 0);
  } finally {
    host.dispose();
  }
});

test('C3, C16: Restart analysis while a worker is still loading gives exactly a displaced result, no failure', async () => {
  const base = tmp();
  // A stub that takes 800 ms to boot: the restart below lands while it loads, which the test establishes first.
  const dir = stubVariant(join(base, 'slow'), 'slow');
  writeFileSync(join(dir, ENGINE_JS), readFileSync(join(dir, ENGINE_JS), 'utf8').replace('setTimeout(() => globalThis.onJulcReady(api), 0);',
    'setTimeout(() => globalThis.onJulcReady(api), 800);'));
  const host = new EngineHost({ workerEntry: WORKER, analysisMs: 10000 });
  try {
    const fresh = { file: join(dir, ENGINE_JS), id: 'id-slow' };
    const p = host.analyze('engine:id-slow', fresh, { file: 'S.jsc', source: 'jsc 2 WARN' });
    const slot = () => host.slots.get('engine:id-slow');
    for (let k = 0; k < 100 && !slot()?.worker; k++) await sleep(10);
    assert.ok(slot()?.worker && !slot().worker.booted, 'the worker exists and is still loading');
    host.restart();
    assert.deepEqual(await p, { cancelled: true, displaced: true });
    assert.equal(host.stats.failures, 0);
    assert.equal(host.quarantine.size, 0);
  } finally {
    host.dispose();
  }
});

test('S7: three failures in one project leave the other projects on the same compiler analysable', async () => {
  const a = tmp('joust-lsp-a-');
  const b = tmp('joust-lsp-b-');
  const lsp = await startLsp({ engineDir: STUB, folders: [a, b], hostOptions: { ...FAST, analysisMs: 300 } });
  try {
    const hang = uriOf(join(a, 'H.jsc'));
    lsp.open(hang, 'jsc 2 HANG 1', 1);
    await lsp.diagnostics(hang, 1);
    lsp.change(hang, 2, 'jsc 2 HANG 2');
    await lsp.diagnostics(hang, 2, 20000);
    lsp.change(hang, 3, 'jsc 2 HANG 3');
    await lsp.until(() => lsp.statuses.find((x) => x.uri === hang && x.state === 'unavailable'), 20000, 'A unavailable');
    const ok = uriOf(join(b, 'Ok.jsc'));
    lsp.open(ok, 'jsc 2 WARN', 1);
    const p = await lsp.diagnostics(ok, 1, 20000);
    assert.match(p.diagnostics[0].message, /stub warning/);
    assert.equal((await lsp.request('joust/status', { uri: ok })).state, 'ok');
  } finally {
    await lsp.stop();
  }
});

test('S4: a workspace folder URI with a host is ignored, and the server starts', async () => {
  const dir = tmp();
  const lsp = await startLsp({ engineDir: STUB, workspaceFolders: [{ uri: 'file://attacker.example/share/repo', name: 'far' }, { uri: uriOf(dir), name: 'here' }] });
  try {
    assert.match(lsp.init.serverInfo.version, /bundled, advisory/);
    const uri = uriOf(join(dir, 'Ok.jsc'));
    lsp.open(uri, 'jsc 2 WARN', 1);
    await lsp.diagnostics(uri, 1);
    const far = 'file://attacker.example/share/repo/X.jsc';
    lsp.open(far, 'jsc 2 WARN', 1);
    await lsp.diagnostics(far, 1);
    assert.match((await lsp.request('joust/status', { uri: far })).reason, /not a file on disk/);
  } finally {
    await lsp.stop();
  }
});

test('S1: hostile Gradle build files cannot stall the selection (linear plugin regex)', async () => {
  const g = tmp('joust-lsp-g-');
  mkdirSync(join(g, 'src', 'main', 'jsc'), { recursive: true });
  for (const f of ['settings.gradle', 'settings.gradle.kts', 'build.gradle', 'build.gradle.kts']) {
    writeFileSync(join(g, f), `plugins {\n  id${' '.repeat(512 * 1024 - 20)}\n}\n`);
  }
  const host = new EngineHost({ workerEntry: WORKER });
  const bundled = { file: join(STUB, ENGINE_JS), id: 'bundled-stub', source: 'bundled', version: 'test-stub' };
  const sel = createSelector({ reader: createReader(), store: createStore(tmp()), host, bundled, trusted: true, folders: [g] });
  try {
    const t = Date.now();
    const s = await sel.select(join(g, 'src', 'main', 'jsc', 'C.jsc'));
    assert.match(s.reason, /Gradle project/);
    assert.ok(Date.now() - t < 2000, `${Date.now() - t} ms`);
  } finally {
    host.dispose();
  }
});

test('S5: a .code-workspace directory that does not resolve fails closed', async () => {
  const ws = tmp('joust-lsp-ws-');
  project(ws);
  const host = new EngineHost({ workerEntry: WORKER });
  const bundled = { file: join(STUB, ENGINE_JS), id: 'bundled-stub', source: 'bundled', version: 'test-stub' };
  const sel = createSelector({ reader: createReader(), store: createStore(tmp()), host, bundled, trusted: true, folders: [ws],
    workspaceFileDir: Symbol('unresolved') });
  try {
    const s = await sel.select(join(ws, 'contracts', 'C.jsc'));
    assert.equal(s.source, 'bundled');
    assert.match(s.reason, /\.code-workspace/);
  } finally {
    host.dispose();
  }
});

test('C5: concurrent selections of a new project all choose its compiler', async () => {
  const ws = tmp('joust-lsp-ws-');
  project(ws);
  for (let k = 0; k < 8; k++) mkdirSync(join(ws, 'contracts', `d${k}`), { recursive: true });
  const host = new EngineHost({ workerEntry: WORKER });
  const bundled = { file: join(STUB, 'nope.js'), id: 'other', source: 'bundled', version: 'x' };
  const sel = createSelector({ reader: createReader(), store: createStore(tmp()), host, bundled, trusted: true, folders: [ws] });
  try {
    const all = await Promise.all([...Array(8).keys()].map((k) => sel.select(join(ws, 'contracts', `d${k}`, 'C.jsc'))));
    assert.deepEqual(all.map((s) => s.source), Array(8).fill('project'), JSON.stringify(all.map((s) => s.reason)));
  } finally {
    host.dispose();
  }
});

test('C7: the store bound holds with the real host\'s lifecycle (copies of stopped workers are evictable)', async () => {
  const base = tmp();
  const storage = tmp('joust-lsp-store-');
  const store = createStore(storage, { maxCopies: 1, recentMs: 0 });
  const host = new EngineHost({ workerEntry: WORKER, maxWorkers: 2 });
  const bundled = { file: join(STUB, 'nope.js'), id: 'other', source: 'bundled', version: 'x' };
  try {
    for (const t of ['p', 'q', 'r', 's', 't', 'u']) {
      const ws = join(base, t);
      project(ws, stubVariant(join(base, `e-${t}`), t));
      const sel = createSelector({ reader: createReader(), store, host, bundled, trusted: true, folders: [ws] });
      const s = await sel.select(join(ws, 'contracts', 'C.jsc'));
      assert.equal(s.source, 'project', s.reason);
      const r = await host.analyze(s.key, s.engine, { file: 'C.jsc', source: 'jsc 2 WARN' });
      assert.ok(r.model, JSON.stringify(r));
    }
    const copies = readdirSync(join(storage, 'engines')).filter((n) => /^[0-9a-f]{64}$/.test(n));
    // At most the bound plus the copies live workers use (2).
    assert.ok(copies.length <= 3, `${copies.length} copies`);
  } finally {
    host.dispose();
  }
});
