// Issue #100 (#95's CI, run 43576433657, r6.test.js S13): another window's collector took a copy between put's install
// and its touch of .used, and again after put's one reinstall (ENOENT on the retry's write). No retry closes that window
// every time, so (#100):
//   - a copy put publishes carries .used staged inside it, landing with the rename: put does not touch it again;
//   - a copy that was there is touched; if it was collected before the touch, put fails with ENGINE_COLLECTED (no
//     reinstall), and the selection, which caches no failure, is made again by its caller with backoff.
// The collection is injected deterministically before the touch.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, promises as fsp, readFileSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { createStore, ENGINE_JS, ENGINE_WASM } from '../../src/lsp/select.js';
import { STUB, tmp } from './client.js';

const bytes = (tag) => [Buffer.from(readFileSync(join(STUB, ENGINE_JS), 'utf8').replaceAll('test-stub', `test-stub-${tag}`)),
  readFileSync(join(STUB, ENGINE_WASM))];
const HEX = /^[0-9a-f]{64}$/;

/** A store that records every touch of a published copy's .used, and can collect the copy right before one. */
function store(storage, { collect = 0 } = {}) {
  let left = collect;
  const touches = [];
  const fs = { ...fsp,
    writeFile: async (p, ...a) => {
      const dir = dirname(String(p));
      if (basename(String(p)) === '.used' && HEX.test(basename(dir))) {
        touches.push(dir);
        if (left > 0) {
          left--;
          // Another window's collector: the copy is claimed and removed right before this touch.
          await fsp.rm(dir, { recursive: true, force: true });
        }
      }
      return fsp.writeFile(p, ...a);
    } };
  return { s: createStore(storage, { maxCopies: 10, recentMs: 0, fs }), touches };
}

test('#100: a copy put publishes lands with its .used (staged); put does not touch it again', async () => {
  const storage = tmp('joust-lsp-store-');
  const { s, touches } = store(storage, { collect: 5 });
  const c = await s.put(...bytes('r9-fresh'));
  assert.deepEqual(touches, [], 'no touch of the published copy (nothing to race)');
  assert.ok(existsSync(c.file));
  assert.ok(existsSync(join(dirname(c.file), '.used')));
});

test('#100: a copy that was there, collected before its touch, is missing: ENGINE_COLLECTED, no reinstall; selecting again works', async () => {
  const storage = tmp('joust-lsp-store-');
  const [js, wasm] = bytes('r9-reused');
  // Published by one window ...
  const first = await store(storage).s.put(js, wasm);
  // ... reused by another, whose touch finds it collected.
  const other = store(storage, { collect: 1 });
  await assert.rejects(other.s.put(js, wasm), (e) => e.code === 'ENGINE_COLLECTED');
  assert.equal(other.touches.length, 1, 'one touch, no reinstall and no second touch');
  assert.equal(existsSync(first.file), false);
  // The caller selects again (the selector caches no failure): the copy is installed afresh.
  const again = await other.s.put(js, wasm);
  assert.ok(existsSync(again.file));
  // Any other error on the touch is thrown as it is.
  const eacces = createStore(tmp('joust-lsp-store-'), { maxCopies: 10, recentMs: 0, fs: { ...fsp,
    writeFile: async (p, ...a) => {
      if (basename(String(p)) === '.used' && HEX.test(basename(dirname(String(p))))) throw Object.assign(new Error('EACCES: injected'), { code: 'EACCES' });
      return fsp.writeFile(p, ...a);
    } } });
  await eacces.put(js, wasm);   // published: not touched
  await assert.rejects(eacces.put(js, wasm), (e) => e.code === 'EACCES');
});
