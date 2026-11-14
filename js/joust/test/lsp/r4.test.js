// Review round r3 on #83 (C18): the store's collector and its users.
//   - a copy leased after the collector's last look at it is put back (the decision is made on the claimed copy);
//   - a lease taken after the collector claimed the copy fails, so its taker selects again;
//   - a load failure is "missing" from the failure itself, even if the copy was republished before it arrived.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, promises as fsp, readdirSync, utimesSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { isMissingEngine } from '../../src/lsp/host.js';
import { createStore } from '../../src/lsp/select.js';
import { tmp } from './client.js';

async function aged(store, engines, tag) {
  const c = await store.put(Buffer.from(`js ${tag}`), Buffer.from(`wasm ${tag}`));
  const old = new Date(Date.now() - 16 * 60_000);
  utimesSync(join(engines, c.id, '.used'), old, old);
  return c;
}

test('C18: a copy leased after the collector\'s last look is put back, not removed', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  let target;
  let looks = 0;
  let store;
  const fs = {
    ...fsp,
    stat: async (p) => {
      const st = await fsp.stat(p);
      // The collector's second look at the target's marker (its last before claiming it): a worker leases it now.
      if (target && String(p) === join(engines, target, '.used') && ++looks === 2) await store.lease(target);
      return st;
    },
  };
  store = createStore(storage, { maxCopies: 1, fs });
  const a = await aged(store, engines, 'a');
  target = a.id;
  await store.put(Buffer.from('js b'), Buffer.from('wasm b'));
  assert.ok(looks >= 2, `the collector looked ${looks} time(s)`);
  assert.ok(readdirSync(engines).includes(a.id), 'the leased copy is back in place');
  assert.ok(readdirSync(join(engines, a.id)).some((f) => f.startsWith('.lease-')));
});

test('C18: a lease taken while the collector holds the claimed copy fails; its taker selects again', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  let target;
  let store;
  let late;
  const fs = {
    ...fsp,
    rename: async (from, to) => {
      await fsp.rename(from, to);
      if (target && String(from) === join(engines, target)) late = await store.lease(target);   // right after the claim
    },
  };
  store = createStore(storage, { maxCopies: 1, fs });
  const a = await aged(store, engines, 'a');
  target = a.id;
  await store.put(Buffer.from('js b'), Buffer.from('wasm b'));
  assert.equal(late, null, 'no lease on a claimed copy');
  assert.ok(!readdirSync(engines).includes(a.id), 'the unleased, unused copy was removed');
});

test('C18: a load failure is "missing" from the failure itself, whatever the path holds when it arrives', () => {
  const dir = tmp();
  const file = join(dir, 'joust-toolchain.js');
  mkdirSync(dir, { recursive: true });
  writeFileSync(file, '// republished before the failure arrived');
  const engine = { file };
  // The loader's own file, or its .wasm, missing at load: missing, though the file exists again now.
  assert.equal(isMissingEngine({ ready: false, code: 'MODULE_NOT_FOUND', error: `Cannot find module '${file}'` }, engine), true);
  assert.equal(isMissingEngine({ ready: false, code: 'ENOENT', path: `${file}.wasm`, error: 'no such file' }, engine), true);
  // Anything else is the engine's own failure: a module the engine needs, or an error of its code.
  assert.equal(isMissingEngine({ ready: false, code: 'MODULE_NOT_FOUND', error: "Cannot find module 'left-pad'" }, engine), false);
  assert.equal(isMissingEngine({ ready: false, error: 'TypeError: boom' }, engine), false);
  assert.equal(isMissingEngine({ ready: true }, engine), false);
});
