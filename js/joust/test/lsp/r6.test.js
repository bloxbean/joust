// Review round r5 on #83:
//   C25  a start whose slot is stopped while its lease is pending is abandoned: no worker, lease released;
//        the real two-project sequence on a pre-M5a compiler leaves no worker alive;
//   C26  a missing loader under a storage path holding quotes and spaces is a missing copy (Node's real error);
//   C27  a wanted claim never yields its good bytes to a destination that is there but invalid;
//   C28  the collector's final check counts this server's live use;
//   S13  a copy's directory gone at load (require's lstat) is a missing copy; a cross-window churn causes no failure;
//   S14  a claim whose bytes are not its digest is never put back under that name.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, promises as fsp, readdirSync, readFileSync, renameSync, utimesSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { EngineHost, isMissingEngine } from '../../src/lsp/host.js';
import { createReader } from '../../src/lsp/inputs.js';
import { createSelector, createStore, ENGINE_JS, ENGINE_WASM } from '../../src/lsp/select.js';
import { OLD, STUB, tmp } from './client.js';

const WORKER = new URL('../../src/lsp/worker.js', import.meta.url);
const CHURN = fileURLToPath(new URL('./fixtures/churn.mjs', import.meta.url));
const stubBytes = (tag) => [Buffer.from(readFileSync(join(STUB, ENGINE_JS), 'utf8').replaceAll('test-stub', `test-stub-${tag}`)),
  readFileSync(join(STUB, ENGINE_WASM))];
const deadPid = () => Number(execFileSync(process.execPath, ['-e', 'process.stdout.write(String(process.pid))'], { encoding: 'utf8' }));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

test('C25: a start whose slot is stopped while its lease is pending starts no worker and releases its lease', async () => {
  const store = createStore(tmp('joust-lsp-store-'));
  const [js, wasm] = stubBytes('p');
  const c = await store.put(js, wasm);
  let gate;
  let released = 0;
  const host = new EngineHost({
    workerEntry: WORKER,
    lease: async (e) => {
      await new Promise((r) => { gate = r; });
      const rel = await store.lease(e.id);
      return () => { released++; return rel(); };
    },
  });
  try {
    const p = host.info(`engine:${c.id}`, { file: c.file, id: c.id });
    for (let k = 0; k < 100 && !gate; k++) await sleep(5);
    host.stop(`engine:${c.id}`);   // e.g. the selector rejecting the compiler for another project
    gate();
    const r = await p;
    assert.equal(r.ready, false);
    assert.equal(r.displaced, true);
    assert.equal(host.stats.started, 0, 'no worker was started');
    assert.equal(released, 1, 'the lease was released');
    assert.equal(host.slots.size, 0);
  } finally {
    host.dispose();
  }
});

function oldProject(root) {
  mkdirSync(join(root, 'contracts'), { recursive: true });
  writeFileSync(join(root, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '0.0.0' } }));
  writeFileSync(join(root, 'joust.config.json'), '{ "contracts": "contracts" }');
  const pkg = join(root, 'node_modules', '@julclang', 'joust');
  mkdirSync(join(pkg, 'engine'), { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: '@julclang/joust', version: '0.0.1' }));
  for (const f of [ENGINE_JS, ENGINE_WASM, 'package.json']) copyFileSync(join(OLD, f), join(pkg, 'engine', f));
}

/** `p`, or a failure naming `what` after `ms` (a stuck await fails the test instead of cancelling the file). */
const within = (p, what, ms = 15000) => Promise.race([p, new Promise((_, no) => setTimeout(() => no(new Error(`${what}: no answer in ${ms} ms`)), ms))]);

test('C25, F11: two projects probing one pre-M5a compiler at once leave no worker running, whichever arrives first', async () => {
  // Deterministic (reviews F11, C32, S15 on #83): both probes are held at their leases, in whichever order they arrive;
  // then the first is let go (it loads the compiler, rejects it and stops the slot), then the second (its start is
  // abandoned). Every await is bounded.
  // Twice: the arrival order is the scheduler's, and the test holds whichever arrives first.
  for (const order of ['round 1', 'round 2']) {
    const a = tmp('joust-lsp-a-');
    const b = tmp('joust-lsp-b-');
    oldProject(a);
    oldProject(b);
    const store = createStore(tmp('joust-lsp-store-'));
    const held = [];
    let arrived;
    const both = new Promise((r) => { arrived = r; });
    const host = new EngineHost({
      workerEntry: WORKER,
      lease: async (e) => {
        await new Promise((go) => {
          held.push(go);
          if (held.length === 2) arrived();
        });
        return store.lease(e.id);
      },
    });
    const bundled = { file: join(STUB, ENGINE_JS), id: 'bundled-stub', source: 'bundled', version: 'test-stub' };
    const sa = createSelector({ reader: createReader(), store, host, bundled, trusted: true, folders: [a] });
    const sb = createSelector({ reader: createReader(), store, host, bundled, trusted: true, folders: [b] });
    try {
      const pa = sa.select(join(a, 'contracts', 'C.jsc'));
      const pb = sb.select(join(b, 'contracts', 'C.jsc'));
      await within(both, `${order}: both probes at their leases`);
      // Let the first arrival go; it rejects the compiler and stops the slot while the second still waits.
      held[0]();
      const firstDone = await within(Promise.race([pa.then(() => 'a'), pb.then(() => 'b')]), `${order}: the first selection`);
      held[1]();
      const [ra, rb] = await within(Promise.all([pa, pb]), `${order}: both selections`);
      assert.ok(['a', 'b'].includes(firstDone));
      assert.equal(ra.source, 'bundled', ra.reason);
      assert.equal(rb.source, 'bundled', rb.reason);
      const live = [...host.slots.values()].filter((s) => s.worker && !s.worker.dead);
      assert.equal(live.length, 0, `${order}: no worker of the rejected compiler is alive`);
      assert.equal(host.stats.started, 1, `${order}: one worker was started (the second start was abandoned)`);
      assert.equal(host.stats.started, host.stats.terminated, `${order}: started ${host.stats.started}, terminated ${host.stats.terminated}`);
    } finally {
      for (const go of held) go();
      host.dispose();
    }
  }
});

test('C26: a loader missing under a storage path with quotes and spaces is a missing copy (Node\'s real error)', async () => {
  const dir = join(tmp(), "O'Brien's \"store\" x", 'engines', 'e');
  mkdirSync(dir, { recursive: true });
  const file = join(dir, ENGINE_JS);
  let error;
  try {
    createRequire(file)(file);
  } catch (e) {
    error = { ready: false, code: e.code, error: e.message, path: e.path };
  }
  assert.equal(error.code, 'MODULE_NOT_FOUND');
  assert.equal(isMissingEngine(error, { file }), true);
  // Through a real worker: the lease hook moves the loader away after the host's existence check.
  for (const f of [ENGINE_JS, ENGINE_WASM, 'package.json']) copyFileSync(join(STUB, f), join(dir, f));
  const host = new EngineHost({ workerEntry: WORKER, lease: async () => {
    renameSync(file, `${file}.away`);
    return () => {};
  } });
  try {
    const r = await host.analyze('engine:q', { file, id: 'q' }, { file: 'S.jsc', source: 'jsc 1 WARN' });
    assert.deepEqual(r, { missing: true });
    assert.equal(host.stats.failures, 0);
    assert.equal(host.quarantine.size, 0);
  } finally {
    host.dispose();
  }
});

/** A claim a crashed collector left (dead owner, 5 minutes old), leased, of a fresh copy. */
async function leftClaim(engines, store, tag) {
  const [js, wasm] = stubBytes(tag);
  const c = await store.put(js, wasm);
  await store.lease(c.id);
  const claim = `.evicted-${c.id}-${deadPid()}-${Date.now() - 5 * 60_000}`;
  renameSync(join(engines, c.id), join(engines, claim));
  return { id: c.id, claim, js, wasm };
}

test('C27: a wanted claim replaces a destination that is there but invalid; a valid one stays', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  const store = createStore(storage, { maxCopies: 10, recentMs: 0 });
  for (const destination of ['invalid', 'valid', 'absent']) {
    const { id, claim, js, wasm } = await leftClaim(engines, store, `c27-${destination}`);
    if (destination !== 'absent') {
      mkdirSync(join(engines, id));
      writeFileSync(join(engines, id, ENGINE_JS), destination === 'valid' ? js : 'BROKEN');
      writeFileSync(join(engines, id, ENGINE_WASM), wasm);
    }
    const [jn, wn] = stubBytes(`next-${destination}`);
    await store.put(jn, wn);   // a later collection sweeps
    assert.equal(readFileSync(join(engines, id, ENGINE_JS), 'utf8'), js.toString(), `${destination}: the good bytes are in place`);
    assert.ok(!readdirSync(engines).includes(claim), `${destination}: the claim is resolved`);
  }
});

test('S14: a claim whose bytes are not its digest is removed, never put back under that name', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  const store = createStore(storage, { maxCopies: 10, recentMs: 0 });
  const { id, claim } = await leftClaim(engines, store, 's14');
  writeFileSync(join(engines, claim, ENGINE_JS), 'TAMPERED, not the digest this copy is named after');
  const [jn, wn] = stubBytes('s14-next');
  await store.put(jn, wn);
  const names = readdirSync(engines);
  assert.ok(!names.includes(id), 'nothing under the digest name');
  assert.ok(!names.includes(claim), 'the tampered claim is removed');
});

test('C28: the collector\'s final check, on the claimed copy, counts this server\'s live use', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  let claimed = false;
  const fs = {
    ...fsp,
    rename: async (from, to) => {
      await fsp.rename(from, to);
      if (String(to).includes('.evicted-')) claimed = true;
    },
  };
  const store = createStore(storage, { maxCopies: 1, fs });
  const [js, wasm] = stubBytes('c28');
  const a = await store.put(js, wasm);
  const old = new Date(Date.now() - 16 * 60_000);
  utimesSync(join(engines, a.id, '.used'), old, old);
  // A worker of this server starts on A only once A is claimed: its use is known to the host, not yet on disk.
  const inUse = () => (claimed ? new Set([a.id]) : new Set());
  const [jb, wb] = stubBytes('c28-b');
  await store.put(jb, wb, inUse);
  assert.ok(existsSync(join(engines, a.id, ENGINE_JS)), 'A, in use by this server, is back in place');
});

test('S13: a copy\'s directory missing at load is a missing copy; above the store root it is not', () => {
  const root = join(tmp(), 'engines');
  const file = join(root, 'a'.repeat(64), ENGINE_JS);
  const engine = { file, storeRoot: root };
  const at = (p, code = 'ENOENT') => isMissingEngine({ ready: false, code, path: p, error: `${code}: lstat '${p}'` }, engine);
  assert.equal(at(dirname(file)), true, 'the copy\'s directory');
  assert.equal(at(dirname(file), 'ENOTDIR'), true);
  assert.equal(at(root), true, 'the store root itself');
  assert.equal(at(dirname(root)), false, 'above the store root');
  assert.equal(at('/'), false);
  assert.equal(at(join(root, 'b'.repeat(64))), false, 'another copy\'s directory');
  // Outside a store, only the loader's own directory.
  assert.equal(isMissingEngine({ ready: false, code: 'ENOENT', path: dirname(file) }, { file }), true);
  assert.equal(isMissingEngine({ ready: false, code: 'ENOENT', path: root }, { file }), false);
});

test('S13: another window churning the shared store while this one reloads its engine causes no failure', async () => {
  const storage = tmp('joust-lsp-store-');
  const store = createStore(storage, { maxCopies: 1, recentMs: 0 });
  const [js, wasm] = stubBytes('s13');
  let c = await store.put(js, wasm);
  const churner = new Promise((resolve, reject) => {
    execFile(process.execPath, [CHURN, storage, STUB, '20000'], (e, out) => (e ? reject(e) : resolve(Number(out))));
  });
  const host = new EngineHost({ workerEntry: WORKER, lease: (e) => store.lease(e.id), analysisMs: 10000 });
  const tally = {};
  try {
    for (let k = 0; k < 150; k++) {
      host.restart();   // every round loads the engine afresh, as a restarted or long-idle worker does
      const r = await host.analyze(`engine:${c.id}`, { file: c.file, id: c.id, storeRoot: store.root }, { file: 'S.jsc', source: `jsc 1 WARN ${k}` });
      const kind = r.model ? 'model' : r.missing ? 'missing' : r.cancelled ? 'cancelled' : `internal:${r.internal?.kind}:${r.internal?.message}`;
      tally[kind] = (tally[kind] ?? 0) + 1;
      if (r.missing) c = await store.put(js, wasm);   // what the server does: select again, republishing the copy
    }
    const churned = await churner;
    assert.ok(churned > 10, `the other window installed ${churned} copies`);
    assert.equal(host.stats.failures, 0, JSON.stringify(tally));
    assert.ok(tally.model > 0, JSON.stringify(tally));
    console.log(`[S13 stress] ${JSON.stringify(tally)}, other window ${churned} installs`);
  } finally {
    host.dispose();
  }
});
