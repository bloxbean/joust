// Review round r4 on #83:
//   C21  concurrent acquisitions through the real, asynchronous lease never exceed the worker bound (the peak counted);
//   C22  a present engine whose dependency is missing is an engine fault, not a missing copy (Node's real error);
//   C23  a claim a crashed collector left behind is recovered: put back when still leased, removed otherwise;
//   C24  a failed put-back with no replacement in place keeps the claimed copy, which the sweep restores later.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, promises as fsp, readdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { EngineHost, isMissingEngine } from '../../src/lsp/host.js';
import { createStore, ENGINE_JS, ENGINE_WASM } from '../../src/lsp/select.js';
import { STUB, tmp } from './client.js';

const WORKER = new URL('../../src/lsp/worker.js', import.meta.url);
const stubBytes = (tag) => [Buffer.from(readFileSync(join(STUB, ENGINE_JS), 'utf8').replaceAll('test-stub', `test-stub-${tag}`)),
  readFileSync(join(STUB, ENGINE_WASM))];

/** A pid that is certainly not running: a child process that has exited. */
const deadPid = () => Number(execFileSync(process.execPath, ['-e', 'process.stdout.write(String(process.pid))'], { encoding: 'utf8' }));

test('C21: five distinct compilers acquired at once through the store\'s lease never run more than two workers', async () => {
  const store = createStore(tmp('joust-lsp-store-'), { maxCopies: 10 });
  const engines = [];
  for (const t of ['a', 'b', 'c', 'd', 'e']) {
    const [js, wasm] = stubBytes(t);
    const c = await store.put(js, wasm);
    engines.push({ file: c.file, id: c.id });
  }
  let peak = 0;
  const host = new EngineHost({
    workerEntry: WORKER, maxWorkers: 2, lease: (e) => store.lease(e.id),
    onStart: () => {
      peak = Math.max(peak, [...host.slots.values()].filter((s) => s.worker && !s.worker.dead).length);
    },
  });
  try {
    const rs = await Promise.all(engines.map((e) => host.info(`engine:${e.id}`, e)));
    assert.deepEqual(rs.map((r) => r.ready), [true, true, true, true, true]);
    assert.ok(peak <= 2, `peak ${peak} live workers`);
    assert.equal(host.stats.started, 5);
  } finally {
    host.dispose();
  }
});

test('C22: a missing dependency of a present engine is an engine fault; only the engine\'s own files missing are a missing copy', async () => {
  const dir = tmp();
  const file = join(dir, ENGINE_JS);
  writeFileSync(join(dir, 'package.json'), '{ "type": "commonjs" }\n');
  writeFileSync(file, "require('joust-r5-missing-dependency');\n");
  copyFileSync(join(STUB, ENGINE_WASM), join(dir, ENGINE_WASM));
  const real = (f) => {
    try {
      createRequire(f)(f);
    } catch (e) {
      return { ready: false, code: e.code, error: e.message, path: e.path };
    }
    return { ready: true };
  };
  // Node's own errors: the dependency's message carries a "Require stack" that names the engine; it is no missing copy.
  const dependency = real(file);
  assert.equal(dependency.code, 'MODULE_NOT_FOUND');
  assert.match(dependency.error, /Require stack/);
  assert.match(dependency.error, new RegExp(file.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')));
  assert.equal(isMissingEngine(dependency, { file }), false);
  const gone = join(tmp(), ENGINE_JS);
  assert.equal(isMissingEngine(real(gone), { file: gone }), true);
  // ENOENT only when its path is exactly the loader or its .wasm; a TypeError never, whatever the path holds.
  assert.equal(isMissingEngine({ ready: false, code: 'ENOENT', path: `${file}.wasm` }, { file }), true);
  assert.equal(isMissingEngine({ ready: false, code: 'ENOENT', path: join(dir, 'other') }, { file }), false);
  assert.equal(isMissingEngine({ ready: false, error: 'TypeError: boom' }, { file: gone }), false);
  // Through a real worker: an engine fault (counted, quarantined), never a missing copy.
  const host = new EngineHost({ workerEntry: WORKER });
  try {
    const r = await host.analyze('engine:broken', { file, id: 'broken' }, { file: 'S.jsc', source: 'jsc 2 WARN' });
    assert.equal(r.missing, undefined, JSON.stringify(r));
    assert.equal(r.internal?.kind, 'crash');
    assert.equal(host.stats.failures, 1);
  } finally {
    host.dispose();
  }
});

async function claimedAged(engines, store, tag, { lease }) {
  const [js, wasm] = stubBytes(tag);
  const c = await store.put(js, wasm);
  if (lease) await store.lease(c.id);
  const claim = `.evicted-${c.id}-${deadPid()}-${Date.now() - 5 * 60_000}`;   // a crashed collector's, 5 minutes old
  renameSync(join(engines, c.id), join(engines, claim));
  return { id: c.id, claim };
}

test('C23: a claim a crashed collector left is recovered: put back while leased, removed otherwise', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  const store = createStore(storage, { maxCopies: 10, recentMs: 0 });
  const kept = await claimedAged(engines, store, 'kept', { lease: true });
  const dropped = await claimedAged(engines, store, 'dropped', { lease: false });
  const [js, wasm] = stubBytes('next');
  await store.put(js, wasm);   // any later collection sweeps
  const names = readdirSync(engines);
  assert.ok(names.includes(kept.id) && !names.includes(kept.claim), 'the leased claim is back in place');
  assert.ok(!names.includes(dropped.id) && !names.includes(dropped.claim), 'the unleased claim is removed');
});

test('C24: a put-back that fails with no replacement in place keeps the claimed copy; a later sweep restores it', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  let failRestore = true;
  const fs = {
    ...fsp,
    rename: async (from, to) => {
      if (failRestore && String(from).includes('.evicted-') && !String(to).includes('.evicted-')) {
        throw Object.assign(new Error('EPERM: operation not permitted'), { code: 'EPERM' });
      }
      return fsp.rename(from, to);
    },
  };
  const store = createStore(storage, { maxCopies: 1, recentMs: 0, fs });
  const [js, wasm] = stubBytes('leased');
  const a = await store.put(js, wasm);
  await store.lease(a.id);
  const [jb, wb] = stubBytes('other');
  await store.put(jb, wb);   // the collector claims A, finds its lease, and cannot put it back
  const claims = readdirSync(engines).filter((n) => n.startsWith(`.evicted-${a.id}-`));
  assert.equal(claims.length, 1, 'the leased copy is kept, claimed');
  assert.ok(existsSync(join(engines, claims[0], ENGINE_JS)));
  // Later (the claim abandoned past its bound), the next collection puts it back.
  failRestore = false;
  const old = claims[0].replace(/-(\d+)$/, `-${Date.now() - 5 * 60_000}`);
  renameSync(join(engines, claims[0]), join(engines, old));
  const [jc, wc] = stubBytes('third');
  await store.put(jc, wc);
  assert.ok(readdirSync(engines).includes(a.id), 'restored');
});
