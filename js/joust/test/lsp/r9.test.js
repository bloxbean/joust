// #95's CI (run 43576433657, r6.test.js S13): another window's collector took the copy between put's install and its
// touch of .used (ENOENT), and could again after the reinstall. put treats ENOENT or ENOTDIR on the touch as "collected"
// and installs again (bounded); any other error is thrown. The collection is injected deterministically.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, promises as fsp, readFileSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { createStore, ENGINE_JS, ENGINE_WASM, MAX_REINSTALLS } from '../../src/lsp/select.js';
import { STUB, tmp } from './client.js';

const bytes = (tag) => [Buffer.from(readFileSync(join(STUB, ENGINE_JS), 'utf8').replaceAll('test-stub', `test-stub-${tag}`)),
  readFileSync(join(STUB, ENGINE_WASM))];
const HEX = /^[0-9a-f]{64}$/;

/** A store whose touches of a published copy's .used see the copy collected first, `times` times. */
function collecting(storage, times) {
  let left = times;
  let collected = 0;
  const fs = { ...fsp,
    writeFile: async (p, ...a) => {
      const dir = dirname(String(p));
      if (basename(String(p)) === '.used' && HEX.test(basename(dir)) && left > 0) {
        left--;
        collected++;
        // Another window's collector: the copy is claimed and removed right before this touch.
        await fsp.rm(dir, { recursive: true, force: true });
      }
      return fsp.writeFile(p, ...a);
    } };
  return { store: createStore(storage, { maxCopies: 10, recentMs: 0, fs }), collected: () => collected };
}

test('#95: a copy collected between the install and the touch, once and again after the reinstall, is installed again', async () => {
  for (const times of [1, 2, 3]) {
    const storage = tmp('joust-lsp-store-');
    const { store, collected } = collecting(storage, times);
    const [js, wasm] = bytes(`r9-${times}`);
    const c = await store.put(js, wasm);
    assert.equal(collected(), times);
    assert.ok(existsSync(c.file), `${times}: the copy is there`);
    assert.ok(existsSync(join(dirname(c.file), '.used')), `${times}: and marked used`);
  }
});

test('#95: past the bound, or on another error, the touch\'s error is thrown', async () => {
  const storage = tmp('joust-lsp-store-');
  const { store } = collecting(storage, MAX_REINSTALLS + 5);
  const [js, wasm] = bytes('r9-bound');
  await assert.rejects(store.put(js, wasm), (e) => e.code === 'ENOENT');
  const other = createStore(tmp('joust-lsp-store-'), { maxCopies: 10, recentMs: 0, fs: { ...fsp,
    writeFile: async (p, ...a) => {
      if (basename(String(p)) === '.used' && HEX.test(basename(dirname(String(p))))) throw Object.assign(new Error('EACCES: injected'), { code: 'EACCES' });
      return fsp.writeFile(p, ...a);
    } } });
  await assert.rejects(other.put(...bytes('r9-eacces')), (e) => e.code === 'EACCES');
});
