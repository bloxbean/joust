// Review round r6 on #83:
//   C29  a loader missing under a storage path holding a newline (and quotes, spaces) is a missing copy;
//   C30  a claim whose bytes cannot be read now is kept, and a later sweep restores it;
//   C31  two collectors recovering one abandoned claim leave one valid copy; a valid moved-aside copy is never deleted.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, promises as fsp, readdirSync, readFileSync, renameSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { EngineHost, isMissingEngine } from '../../src/lsp/host.js';
import { createStore, ENGINE_JS, ENGINE_WASM } from '../../src/lsp/select.js';
import { STUB, tmp } from './client.js';

const WORKER = new URL('../../src/lsp/worker.js', import.meta.url);
const stubBytes = (tag) => [Buffer.from(readFileSync(join(STUB, ENGINE_JS), 'utf8').replaceAll('test-stub', `test-stub-${tag}`)),
  readFileSync(join(STUB, ENGINE_WASM))];
const deadPid = () => Number(execFileSync(process.execPath, ['-e', 'process.stdout.write(String(process.pid))'], { encoding: 'utf8' }));

test('C29: a loader missing under a path with a newline, quotes and spaces is a missing copy, through a real worker', async (t) => {
  if (process.platform === 'win32') return t.skip('no newline in Windows paths');
  const dir = join(tmp(), "store\nsecond line O'Brien \"x\"", 'engines', 'e');
  mkdirSync(dir, { recursive: true });
  const file = join(dir, ENGINE_JS);
  // Node's real error for the missing loader: the header holds the newline; compared whole, it is the loader's.
  let error;
  try {
    createRequire(file)(file);
  } catch (e) {
    error = { ready: false, code: e.code, error: e.message, path: e.path };
  }
  assert.equal(isMissingEngine(error, { file }), true);
  // A missing dependency of a present loader under the same path is still an engine fault.
  for (const f of [ENGINE_JS, ENGINE_WASM, 'package.json']) copyFileSync(join(STUB, f), join(dir, f));
  // Through a real worker: the loader moved away after the host's existence check, before the worker starts.
  const host = new EngineHost({ workerEntry: WORKER, lease: async () => {
    renameSync(file, `${file}.away`);
    return () => {};
  } });
  try {
    const r = await host.analyze('engine:nl', { file, id: 'nl' }, { file: 'S.jsc', source: 'jsc 2 WARN' });
    assert.deepEqual(r, { missing: true });
    assert.equal(host.stats.failures, 0);
    assert.equal(host.quarantine.size, 0);
  } finally {
    host.dispose();
  }
});

/** A claim a crashed collector left (dead owner, 5 minutes old), leased. */
async function leftClaim(engines, store, tag) {
  const [js, wasm] = stubBytes(tag);
  const c = await store.put(js, wasm);
  await store.lease(c.id);
  const claim = `.evicted-${c.id}-${deadPid()}-${Date.now() - 5 * 60_000}`;
  renameSync(join(engines, c.id), join(engines, claim));
  return { id: c.id, claim, js };
}

test('C30: a claim whose bytes cannot be read now is kept; a later sweep restores it', async () => {
  for (const code of ['EPERM', 'EACCES', 'EMFILE']) {
    const storage = tmp('joust-lsp-store-');
    const engines = join(storage, 'engines');
    let fail = false;
    const fs = {
      ...fsp,
      readFile: async (p, ...a) => {
        if (fail && String(p).includes('.evicted-') && String(p).endsWith(ENGINE_JS)) {
          fail = false;
          throw Object.assign(new Error(`${code}: injected`), { code });
        }
        return fsp.readFile(p, ...a);
      },
    };
    const store = createStore(storage, { maxCopies: 10, recentMs: 0, fs });
    const { id, js } = await leftClaim(engines, store, `c30-${code}`);
    fail = true;
    const [j1, w1] = stubBytes(`c30-${code}-1`);
    await store.put(j1, w1);   // the sweep cannot read the claim: it is kept
    const kept = readdirSync(engines).filter((n) => n.startsWith(`.evicted-${id}-`));
    assert.equal(kept.length, 1, `${code}: the claim is kept`);
    assert.ok(!existsSync(join(engines, id)), `${code}: nothing restored yet`);
    // Later, past the claim bound again, the next sweep reads it and restores it.
    renameSync(join(engines, kept[0]), join(engines, `.evicted-${id}-${deadPid()}-${Date.now() - 5 * 60_000}`));
    const [j2, w2] = stubBytes(`c30-${code}-2`);
    await store.put(j2, w2);
    assert.equal(readFileSync(join(engines, id, ENGINE_JS), 'utf8'), js.toString(), `${code}: restored`);
  }
});

test('C31: two collectors recovering the same abandoned claim leave exactly one valid copy, its lease intact', async () => {
  for (let round = 0; round < 10; round++) {
    const storage = tmp('joust-lsp-store-');
    const engines = join(storage, 'engines');
    const a = createStore(storage, { maxCopies: 10, recentMs: 0 });
    const b = createStore(storage, { maxCopies: 10, recentMs: 0 });
    const { id, js } = await leftClaim(engines, a, `c31-${round}`);
    const [ja, wa] = stubBytes(`c31-${round}-a`);
    const [jb, wb] = stubBytes(`c31-${round}-b`);
    await Promise.all([a.put(ja, wa), b.put(jb, wb)]);   // both sweep the same claim at once
    const names = readdirSync(engines);
    assert.equal(readFileSync(join(engines, id, ENGINE_JS), 'utf8'), js.toString(), `round ${round}: the copy is in place`);
    assert.ok(readdirSync(join(engines, id)).some((f) => f.startsWith('.lease-')), `round ${round}: with its lease`);
    assert.deepEqual(names.filter((n) => n.startsWith('.evicted-') || n.startsWith('.damaged-')), [], `round ${round}: ${names}`);
  }
});

test('C31: a moved-aside copy that is valid is put back by the sweep, never deleted', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  const store = createStore(storage, { maxCopies: 10, recentMs: 0 });
  const [js, wasm] = stubBytes('c31-damaged');
  const c = await store.put(js, wasm);
  renameSync(join(engines, c.id), join(engines, `.damaged-${c.id}-${deadPid()}-${Date.now() - 5 * 60_000}`));
  const [jn, wn] = stubBytes('c31-next');
  await store.put(jn, wn);
  assert.equal(readFileSync(join(engines, c.id, ENGINE_JS), 'utf8'), js.toString());
});
