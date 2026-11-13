// Review round r2 on #83: the cases its probes found, kept as tests.
//   C12  a copy reused while the collector runs is not deleted; a copy removed while a worker loads is "missing";
//   C13  a selection that resumes after a newer one never cancels the newer analysis;
//   C14  recovering one missing copy re-analyses the other open documents;
//   C15  sibling projects in one folder have separate health;
//   S10  a missing engine file backs off, then says so.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, readdirSync, readFileSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { promises as fsp } from 'node:fs';
import { join } from 'node:path';
import { EngineHost } from '../../src/lsp/host.js';
import { createStore, ENGINE_JS, ENGINE_WASM } from '../../src/lsp/select.js';
import { startLsp, STUB, tmp, uriOf } from './client.js';

const WORKER = new URL('../../src/lsp/worker.js', import.meta.url);
const FAST = { analysisMs: 600, graceMs: 200, backoffMs: 100, backoffMaxMs: 400 };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function stubVariant(dir, tag, bootMs = 0) {
  mkdirSync(dir, { recursive: true });
  let js = readFileSync(join(STUB, ENGINE_JS), 'utf8').replaceAll('test-stub', `test-stub-${tag}`);
  if (bootMs) js = js.replace('setTimeout(() => globalThis.onJulcReady(api), 0);', `setTimeout(() => globalThis.onJulcReady(api), ${bootMs});`);
  writeFileSync(join(dir, ENGINE_JS), js);
  copyFileSync(join(STUB, ENGINE_WASM), join(dir, ENGINE_WASM));
  copyFileSync(join(STUB, 'package.json'), join(dir, 'package.json'));
  return dir;
}

function project(root, engineFrom) {
  mkdirSync(join(root, 'contracts'), { recursive: true });
  writeFileSync(join(root, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '0.0.0' } }));
  writeFileSync(join(root, 'joust.config.json'), '{ "contracts": "contracts" }');
  const pkg = join(root, 'node_modules', '@julclang', 'joust');
  mkdirSync(join(pkg, 'engine'), { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: '@julclang/joust', version: '9.9.9' }));
  for (const f of [ENGINE_JS, ENGINE_WASM, 'package.json']) copyFileSync(join(engineFrom, f), join(pkg, 'engine', f));
  return pkg;
}

test('C12: a copy reused while the collector decides is kept (its use is read again right before removal)', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  let fresh;
  // The collector's first look at A's marker is old; A is reused right after that look, before it decides.
  const fs = {
    ...fsp,
    stat: async (p) => {
      const st = await fsp.stat(p);
      if (fresh && String(p) === join(engines, fresh, '.used') && st.mtimeMs < Date.now() - 60_000) {
        await fsp.writeFile(join(engines, fresh, '.used'), String(Date.now()));   // the reuse, as put/touch writes it
      }
      return st;
    },
  };
  const store = createStore(storage, { maxCopies: 1, fs });
  const a = await store.put(Buffer.from('js a'), Buffer.from('wasm a'));
  const old = new Date(Date.now() - 16 * 60_000);
  utimesSync(join(engines, a.id, '.used'), old, old);
  fresh = a.id;
  await store.put(Buffer.from('js b'), Buffer.from('wasm b'));
  assert.ok(readdirSync(engines).includes(a.id), 'the reused copy survived');
});

test('C12: a copy removed while its worker loads is "missing", not an engine failure, and nothing is quarantined', async () => {
  const dir = stubVariant(join(tmp(), 'gone'), 'gone');
  const engine = { file: join(dir, ENGINE_JS), id: 'id-gone' };
  // Removed the moment the worker is started, before it can require the file.
  const host = new EngineHost({ workerEntry: WORKER, onStart: () => rmSync(dir, { recursive: true, force: true }) });
  try {
    const r = await host.analyze('engine:id-gone', engine, { file: 'S.jsc', source: 'jsc 2 WARN' });
    assert.deepEqual(r, { missing: true });
    assert.equal(host.stats.failures, 0);
    assert.equal(host.quarantine.size, 0);
  } finally {
    host.dispose();
  }
});

test('C13, C20: a slow selection that resumes after a newer one never cancels it; the newer compiler is published', async () => {
  // The project is nested (initialization's probe of the folder's root does not discover it), so its slow compiler A
  // first loads when the document opens.
  const ws = tmp('joust-lsp-ws-');
  const pkg = project(join(ws, 'sub'), stubVariant(join(tmp(), 'a'), 'a', 1500));
  const lsp = await startLsp({ engineDir: STUB, folders: [ws], args: ['--project-engine'], hostOptions: { analysisMs: 5000 } });
  try {
    const uri = uriOf(join(ws, 'sub', 'contracts', 'C.jsc'));
    lsp.open(uri, 'jsc 2 WARN', 1);
    // Established, not assumed: A's worker exists and is still loading, so the selection waits for it.
    const loadingA = async () => (await lsp.stats()).slots.find((x) => x.alive && !x.booted && x.file.includes('engines'));
    let a;
    for (let k = 0; k < 200 && !(a = await loadingA()); k++) await sleep(10);
    assert.ok(a, 'A is loading');
    const b = stubVariant(join(tmp(), 'b'), 'b');
    for (const f of [ENGINE_JS, ENGINE_WASM]) copyFileSync(join(b, f), join(pkg, 'engine', f));
    lsp.conn.sendNotification('workspace/didChangeWatchedFiles', { changes: [{ uri: uriOf(join(pkg, 'engine', ENGINE_JS)), type: 2 }] });
    const p = await lsp.until(() => lsp.published.find((x) => x.uri === uri && x.diagnostics.length), 15000, 'a publication');
    // Published by B, and no later status names A.
    assert.match(p.diagnostics[0].source, /test-stub-b/);
    await sleep(2500);   // A has long finished loading
    const statuses = lsp.statuses.filter((x) => x.uri === uri);
    assert.equal(statuses[statuses.length - 1].compiler, 'test-stub-b', JSON.stringify(statuses.map((x) => x.compiler)));
    assert.ok(!lsp.published.some((x) => x.uri === uri && x.diagnostics.some((d) => /test-stub-a/.test(d.source))));
  } finally {
    await lsp.stop();
  }
});

test('C14: recovering a missing copy re-analyses the other open documents too', async () => {
  const base = tmp();
  const wa = join(base, 'a');
  const wb = join(base, 'b');
  project(wa, stubVariant(join(base, 'ea'), 'a'));
  project(wb, stubVariant(join(base, 'eb'), 'b'));
  const storage = tmp('joust-lsp-store-');
  const lsp = await startLsp({ engineDir: STUB, folders: [wa, wb], args: ['--project-engine'], storage,
    hostOptions: { analysisMs: 5000, idleMs: 300 } });
  try {
    const ua = uriOf(join(wa, 'contracts', 'A.jsc'));
    lsp.open(ua, 'jsc 2 WARN', 1);
    await lsp.diagnostics(ua, 1);
    await sleep(1000);   // A's worker is reaped as idle
    rmSync(join(storage, 'engines'), { recursive: true, force: true });   // another window evicted A's copy
    const ub = uriOf(join(wb, 'contracts', 'B.jsc'));
    lsp.open(ub, 'jsc 2 SLOW WARN', 1);
    await sleep(400);   // B's slow analysis runs
    lsp.change(ua, 2, 'jsc 2 WARN again');
    await lsp.diagnostics(ua, 2, 15000);
    const pb = await lsp.until(() => lsp.published.find((x) => x.uri === ub && x.diagnostics.some((d) => /stub warning/.test(d.message))),
      15000, 'B\'s diagnostics');
    assert.equal(pb.version, 1);
  } finally {
    await lsp.stop();
  }
});

test('C15: three failures in one Gradle project leave its sibling project in the same folder analysable', async () => {
  const ws = tmp('joust-lsp-ws-');
  for (const p of ['a', 'b']) {
    mkdirSync(join(ws, p, 'src', 'main', 'jsc'), { recursive: true });
    writeFileSync(join(ws, p, 'build.gradle'), "plugins { id 'org.julclang.joust' version '0.1.0' }\n");
  }
  const lsp = await startLsp({ engineDir: STUB, folders: [ws], args: ['--project-engine'], hostOptions: { ...FAST, analysisMs: 300 } });
  try {
    const hang = uriOf(join(ws, 'a', 'src', 'main', 'jsc', 'H.jsc'));
    lsp.open(hang, 'jsc 2 HANG 1', 1);
    await lsp.diagnostics(hang, 1);
    lsp.change(hang, 2, 'jsc 2 HANG 2');
    await lsp.diagnostics(hang, 2, 20000);
    lsp.change(hang, 3, 'jsc 2 HANG 3');
    await lsp.until(() => lsp.statuses.find((x) => x.uri === hang && x.state === 'unavailable'), 20000, 'a unavailable');
    const ok = uriOf(join(ws, 'b', 'src', 'main', 'jsc', 'Ok.jsc'));
    lsp.open(ok, 'jsc 2 WARN', 1);
    const p = await lsp.diagnostics(ok, 1, 20000);
    assert.match(p.diagnostics[0].message, /stub warning/);
    assert.equal((await lsp.request('joust/status', { uri: ok })).state, 'ok');
  } finally {
    await lsp.stop();
  }
});

test('S10: an engine file removed under a running server backs off, then says so', async () => {
  const engineDir = stubVariant(join(tmp(), 'bundled'), 'bundled');
  const dir = tmp();
  const lsp = await startLsp({ engineDir, folders: [dir] });
  try {
    const uri = uriOf(join(dir, 'C.jsc'));
    lsp.open(uri, 'jsc 2 WARN', 1);
    await lsp.diagnostics(uri, 1);
    rmSync(engineDir, { recursive: true, force: true });
    const before = lsp.statuses.length;
    await lsp.request('joust/restartAnalysis');
    await sleep(3000);
    assert.ok(lsp.statuses.length - before < 20, `${lsp.statuses.length - before} status notifications in 3 s`);
    await lsp.until(() => lsp.published.find((x) => x.uri === uri && x.diagnostics.some((d) => /engine file is missing/.test(d.message))),
      20000, 'the missing-engine diagnostic');
  } finally {
    await lsp.stop();
  }
});
