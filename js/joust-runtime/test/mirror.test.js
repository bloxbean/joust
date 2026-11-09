// MerkleMap mirrors (ADR-0015 §13.12) without a chain: the recorded Token and Locker transactions' claims replayed
// into Aiken's MPF (each new root checked), proofs answered for the engine, and the states kept in a level cache.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { mapMirror, levelMirrorCache, claimsOf, EMPTY_ROOT } from '../src/mirror.js';
import { parseTx, decodeData, dataHex } from '@julclang/state-client';

const FIXTURES = new URL('../../../fixtures/m3/', import.meta.url).pathname;
const recorded = (group) => readdirSync(join(FIXTURES, group)).sort()
  .map((f) => JSON.parse(readFileSync(join(FIXTURES, group, f), 'utf8'))).filter((x) => x.cbor);
const claimsIn = (cbor) => parseTx(cbor).redeemers.filter((r) => r.tag === 'reward').flatMap((r) => claimsOf(r.data) ?? []);

test('recorded map claims replay into the mirror, and it proves what the engine checks', async () => {
  const mirror = mapMirror();
  let claims = 0;
  for (const group of ['token', 'locker']) {
    for (const x of recorded(group)) {
      claims += claimsIn(x.cbor).length;
      await mirror.stage(x.cbor);
    }
  }
  assert.ok(claims > 20);
  // Every claim's new root is now a state the mirror can prove against.
  for (const group of ['token', 'locker']) {
    for (const x of recorded(group)) for (const c of claimsIn(x.cbor)) assert.ok(mirror.states.has(c.newRoot), c.newRoot);
  }
  // A read answer: entry and proof, as the engine's SuppliedProver decodes them.
  const [root, state] = [...mirror.states].find(([, s]) => s.size > 1);
  const [key, value] = [...state][0];
  const a = decodeData(await mirror.answer(`read:${root}:${key}`));
  assert.equal(a.constr, 0);
  assert.equal(a.fields[0].fields[1].constr, 1, 'present');
  assert.equal(Buffer.from(a.fields[0].fields[2].bytes).toString('hex'), value);
  // A write answer: one proof per write, in order.
  const w = decodeData(await mirror.answer(`writes:${root}:${key}=-,${'ab'.repeat(28)}=01`));
  assert.equal(w.list.length, 2);
  await assert.rejects(mirror.answer(`read:${'11'.repeat(32)}:00`), /no MerkleMap with root/);
});

test('history with a claim that does not lead to its new root is refused', async () => {
  // A recorded map-verifier redeemer, with its first claim's new root altered: served as the instance's history.
  const x = recorded('token').find((y) => parseTx(y.cbor).redeemers.some((r) => r.tag === 'reward' && claimsOf(r.data)?.length));
  const red = parseTx(x.cbor).redeemers.find((r) => r.tag === 'reward' && claimsOf(r.data)?.length).data;
  const forged = structuredClone({ constr: red.constr, fields: red.fields });
  const strip = (d) => ('constr' in d ? { constr: d.constr, fields: d.fields.map(strip) } : 'list' in d ? { list: d.list.map(strip) }
    : 'map' in d ? { map: d.map.map(([k, v]) => [strip(k), strip(v)]) } : 'int' in d ? { int: d.int } : { bytes: d.bytes });
  const bad = strip(forged);
  bad.fields[0].list[0].fields[6] = { bytes: new Uint8Array(32).fill(0xee) };
  const honest = claimsOf(red)[0];
  const provider = { assetHistory: async () => ['tx'], redeemers: async () => [{ purpose: 'reward', data: dataHex(bad) }] };
  // The mirror knows the claim's old root (replayed from the recorded calls before it).
  const mirror = mapMirror({ provider });
  for (const y of recorded('token')) {
    if (y === x) break;
    await mirror.stage(y.cbor);
  }
  assert.ok(mirror.states.has(honest.oldRoot));
  await assert.rejects(mirror.sync('unit'), /does not lead to its new root/);
});

test('the level cache keeps the states across sessions (LevelDB in Node, IndexedDB in browsers)', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'joust-mirror-'));
  let cache = await levelMirrorCache(dir);
  const first = mapMirror({ cache });
  for (const x of recorded('token')) await first.stage(x.cbor);
  const roots = [...first.states.keys()].filter((r) => r !== EMPTY_ROOT);
  await cache.close();
  cache = await levelMirrorCache(dir);
  const second = mapMirror({ cache });
  assert.equal(second.states.size, 1, 'a new session starts with the empty map only');
  for (const r of roots) {
    for (const [k, v] of first.states.get(r)) assert.equal(await second.get(r, k), v, 'read back from the cache');
  }
  assert.equal(second.states.size, 1 + roots.length);
  await cache.close();
});
