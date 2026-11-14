// Review round r8 on #83:
//   S16  install publishes a copy already marked as used: no collector can take it before put's touch;
//   C33  a moved-aside copy is deleted only when proven safe (its bytes fail their digest, or a valid copy is in place);
//   C34  the C31 races, made deterministic: each test fails with its protection removed.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, promises as fsp, readdirSync, readFileSync, renameSync } from 'node:fs';
import { basename, join } from 'node:path';
import { createStore, ENGINE_JS, ENGINE_WASM } from '../../src/lsp/select.js';
import { STUB, tmp } from './client.js';

const stubBytes = (tag) => [Buffer.from(readFileSync(join(STUB, ENGINE_JS), 'utf8').replaceAll('test-stub', `test-stub-${tag}`)),
  readFileSync(join(STUB, ENGINE_WASM))];
const deadPid = () => Number(execFileSync(process.execPath, ['-e', 'process.stdout.write(String(process.pid))'], { encoding: 'utf8' }));
const HEX = /^[0-9a-f]{64}$/;

test('S16: a copy is published already marked as used; a collector running right after the rename leaves it', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  // B: another window that keeps no copies beyond the fresh ones, and records what it claims.
  const claimed = [];
  const b = createStore(storage, { maxCopies: 0, recentMs: 60_000, fs: { ...fsp,
    rename: async (from, to) => {
      if (HEX.test(basename(String(from))) && basename(String(to)).startsWith('.evicted-')) claimed.push(basename(String(from)));
      return fsp.rename(from, to);
    } } });
  let seen;
  let published = false;
  const a = createStore(storage, { maxCopies: 10, recentMs: 0, fs: { ...fsp,
    rename: async (from, to) => {
      const r = await fsp.rename(from, to);
      if (!published && basename(String(from)).startsWith('.tmp-') && HEX.test(basename(String(to)))) {
        published = true;
        seen = existsSync(join(String(to), '.used'));
        const [jb, wb] = stubBytes('s16-b');
        await b.put(jb, wb);   // B collects between A's rename and A's touch
      }
      return r;
    } } });
  const [ja, wa] = stubBytes('s16-a');
  const c = await a.put(ja, wa);
  assert.equal(published, true);
  assert.equal(seen, true, 'the copy carries .used when it appears');
  assert.deepEqual(claimed.filter((n) => n === c.id), [], 'B never claimed the fresh copy');
  assert.ok(existsSync(c.file));
});

/** A copy moved aside as `.damaged-` by a crashed collector 5 minutes ago; `{ id, name, js }`. */
async function leftDamaged(engines, store, tag) {
  const [js, wasm] = stubBytes(tag);
  const c = await store.put(js, wasm);
  const name = `.damaged-${c.id}-${deadPid()}-${Date.now() - 5 * 60_000}`;
  renameSync(join(engines, c.id), join(engines, name));
  return { id: c.id, name, js, wasm };
}
const damagedOf = (engines, id) => readdirSync(engines).filter((n) => n.startsWith(`.damaged-${id}-`));

test('C33: a moved-aside copy whose bytes cannot be read now is kept; a later sweep restores it', async () => {
  for (const code of ['EPERM', 'EACCES', 'EMFILE']) {
    const storage = tmp('joust-lsp-store-');
    const engines = join(storage, 'engines');
    let fail = false;
    const fs = { ...fsp,
      readFile: async (p, ...a) => {
        if (fail && String(p).includes('.damaged-')) throw Object.assign(new Error(`${code}: injected`), { code });
        return fsp.readFile(p, ...a);
      } };
    const store = createStore(storage, { maxCopies: 10, recentMs: 0, fs });
    const { id, name, js } = await leftDamaged(engines, store, `c33-${code}`);
    fail = true;
    const [j1, w1] = stubBytes(`c33-${code}-1`);
    await store.put(j1, w1);
    assert.deepEqual(damagedOf(engines, id), [name], `${code}: kept`);
    assert.ok(!existsSync(join(engines, id)), `${code}: nothing restored yet`);
    fail = false;
    const [j2, w2] = stubBytes(`c33-${code}-2`);
    await store.put(j2, w2);
    assert.equal(readFileSync(join(engines, id, ENGINE_JS), 'utf8'), js.toString(), `${code}: restored`);
    assert.deepEqual(damagedOf(engines, id), []);
  }
});

test('C33: a valid moved-aside copy facing an invalid destination restores the good bytes under its digest', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  const store = createStore(storage, { maxCopies: 10, recentMs: 0 });
  const { id, js } = await leftDamaged(engines, store, 'c33-dest');
  // An invalid copy sits under the digest name (wrong loader bytes).
  const [other, wasm] = stubBytes('c33-other');
  await fsp.mkdir(join(engines, id));
  await fsp.writeFile(join(engines, id, ENGINE_JS), other);
  await fsp.writeFile(join(engines, id, ENGINE_WASM), wasm);
  const [j1, w1] = stubBytes('c33-dest-1');
  await store.put(j1, w1);
  assert.equal(readFileSync(join(engines, id, ENGINE_JS), 'utf8'), js.toString(), 'the good bytes are in place');
  // The invalid one was moved aside in turn; once past the bound, the sweep proves it invalid and removes it.
  const aside = damagedOf(engines, id);
  assert.equal(aside.length, 1);
  assert.equal(readFileSync(join(engines, aside[0], ENGINE_JS), 'utf8'), other.toString());
  renameSync(join(engines, aside[0]), join(engines, `.damaged-${id}-${deadPid()}-${Date.now() - 5 * 60_000}`));
  const [j2, w2] = stubBytes('c33-dest-2');
  await store.put(j2, w2);
  assert.deepEqual(damagedOf(engines, id), []);
  assert.equal(readFileSync(join(engines, id, ENGINE_JS), 'utf8'), js.toString());
});

test('C33: a valid moved-aside copy is kept while the destination cannot be read; removed once a valid one is proven', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  let fail = false;
  const fs = { ...fsp,
    readFile: async (p, ...a) => {
      if (fail && !String(p).includes('.damaged-') && String(p).endsWith(ENGINE_WASM)) {
        throw Object.assign(new Error('EACCES: injected'), { code: 'EACCES' });
      }
      return fsp.readFile(p, ...a);
    } };
  const store = createStore(storage, { maxCopies: 10, recentMs: 0, fs });
  const { id, name, js, wasm } = await leftDamaged(engines, store, 'c33-unread');
  await fsp.mkdir(join(engines, id));
  await fsp.writeFile(join(engines, id, ENGINE_JS), js);
  await fsp.writeFile(join(engines, id, ENGINE_WASM), wasm);
  fail = true;
  const [j1, w1] = stubBytes('c33-unread-1');
  await store.put(j1, w1).catch(() => {});
  assert.deepEqual(damagedOf(engines, id), [name], 'kept while nothing is proven');
  fail = false;
  const [j2, w2] = stubBytes('c33-unread-2');
  await store.put(j2, w2);
  assert.deepEqual(damagedOf(engines, id), [], 'removed: a valid copy is in place');
  assert.equal(readFileSync(join(engines, id, ENGINE_JS), 'utf8'), js.toString());
});

test('C33: a moved-aside copy whose bytes fail their digest is removed', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  const store = createStore(storage, { maxCopies: 10, recentMs: 0 });
  const { id, name } = await leftDamaged(engines, store, 'c33-bad');
  await fsp.writeFile(join(engines, name, ENGINE_JS), 'tampered');
  const [j1, w1] = stubBytes('c33-bad-1');
  await store.put(j1, w1);
  assert.deepEqual(damagedOf(engines, id), []);
  assert.ok(!existsSync(join(engines, id)));
});

test('C34 (C31 ownership): a second collector never acts on a claim the first is recovering', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  const b = createStore(storage, { maxCopies: 10, recentMs: 0 });   // B: no use of this engine
  let gated = 0;
  const a = createStore(storage, { maxCopies: 10, recentMs: 0, fs: { ...fsp,
    rename: async (from, to) => {
      // A is between hashing its claim and putting it back: B sweeps now.
      if (!gated && basename(String(from)).startsWith('.evicted-') && HEX.test(basename(String(to)))) {
        gated++;
        const [jb, wb] = stubBytes('c34-own-b');
        await b.put(jb, wb);
      }
      return fsp.rename(from, to);
    } } });
  // An abandoned claim with no lease (its crashed collector held none), wanted only by A, whose server uses it now.
  const [js, wasm] = stubBytes('c34-own');
  const c = await a.put(js, wasm);
  renameSync(join(engines, c.id), join(engines, `.evicted-${c.id}-${deadPid()}-${Date.now() - 5 * 60_000}`));
  const [ja, wa] = stubBytes('c34-own-a');
  await a.put(ja, wa, new Set([c.id]));
  assert.equal(gated, 1, 'A reached its put-back');
  // Without ownership B would see the abandoned claim too, find it unwanted (B does not use it) and delete it under A.
  assert.equal(readFileSync(join(engines, c.id, ENGINE_JS), 'utf8'), js.toString(), 'the copy A uses is back');
  assert.deepEqual(readdirSync(engines).filter((n) => n.startsWith('.evicted-')), []);
});

test('C34 (C31 post-move check): a valid copy restored between the check and the move is put back, never deleted', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  const [js, wasm] = stubBytes('c34-move');
  let id;
  let injected = 0;
  const store = createStore(storage, { maxCopies: 10, recentMs: 0, fs: { ...fsp,
    rename: async (from, to) => {
      if (!injected && id && String(from) === join(engines, id) && basename(String(to)).startsWith('.damaged-')) {
        // Another window put back a valid copy, leased by its worker, right after this collector found the destination
        // invalid and right before it moves the destination aside.
        injected++;
        await fsp.rm(join(engines, id), { recursive: true, force: true });
        await fsp.mkdir(join(engines, id));
        await fsp.writeFile(join(engines, id, ENGINE_JS), js);
        await fsp.writeFile(join(engines, id, ENGINE_WASM), wasm);
        await fsp.writeFile(join(engines, id, '.lease-B'), '');
      }
      return fsp.rename(from, to);
    } } });
  const c = await store.put(js, wasm);
  id = c.id;
  await store.lease(c.id);
  renameSync(join(engines, c.id), join(engines, `.evicted-${c.id}-${deadPid()}-${Date.now() - 5 * 60_000}`));
  // An invalid copy under the digest name: the put-back finds the destination there and invalid.
  await fsp.mkdir(join(engines, c.id));
  await fsp.writeFile(join(engines, c.id, ENGINE_JS), 'broken');
  await fsp.writeFile(join(engines, c.id, ENGINE_WASM), wasm);
  const [j1, w1] = stubBytes('c34-move-1');
  await store.put(j1, w1);
  assert.equal(injected, 1, 'the put-back moved the destination aside');
  assert.ok(existsSync(join(engines, c.id, '.lease-B')), 'the copy restored meanwhile, with its lease, is in place');
  assert.equal(readFileSync(join(engines, c.id, ENGINE_JS), 'utf8'), js.toString());
  assert.deepEqual(readdirSync(engines).filter((n) => n.startsWith('.evicted-') || n.startsWith('.damaged-')), []);
});
