// @julclang/state-client's mpf.js (the map verifier's MPF proof checks, a port of julc-state-api's Mpf) against Aiken's
// merkle-patricia-forestry, an independent implementation: membership, absence, and the roots after an insert, an
// update and a delete, over random tries. Here because this package depends on Aiken's library (state-client does not);
// the verifier vectors' map claims are decided in state-client's hostcheck.test.js.

import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import test from 'node:test';
import { Trie } from '@aiken-lang/merkle-patricia-forestry';
import { has, miss, including, excluding, proofOf, verifyClaim, EMPTY_ROOT } from '../../state-client/src/mpf.js';
import { decodeData } from '@julclang/state-client';

const proof = async (trie, key, missing = false) => proofOf(decodeData((await trie.prove(key, missing)).toCBOR()));
const root = (trie) => (trie.hash ? Uint8Array.from(trie.hash) : EMPTY_ROOT);

test('membership, absence, insert, update and delete agree with Aiken\'s merkle-patricia-forestry', async () => {
  for (const size of [1, 2, 3, 5, 17, 60]) {
    const items = Array.from({ length: size }, (_, i) => ({ key: Buffer.from(`key-${size}-${i}-` + randomBytes(3).toString('hex')),
      value: Buffer.from(randomBytes(1 + (i % 9))) }));
    const trie = await Trie.fromList(items);
    const r = root(trie);
    for (const { key, value } of items.slice(0, 8)) {
      const p = await proof(trie, key);
      assert.ok(has(r, key, value, p), 'a present key');
      assert.ok(!has(r, key, Buffer.concat([value, Buffer.from([1])]), p), 'another value');
      // Delete: the root without the key.
      const smaller = await Trie.fromList(items.filter((x) => x.key !== key));
      assert.deepEqual(excluding(key, p), root(smaller), 'the root after a delete');
      // Update: the same position, another value.
      const updated = await Trie.fromList(items.map((x) => (x.key === key ? { key, value: Buffer.from('new') } : x)));
      assert.deepEqual(including(key, Buffer.from('new'), p), root(updated), 'the root after an update');
    }
    for (let i = 0; i < 6; i++) {
      const key = Buffer.from('absent-' + randomBytes(4).toString('hex'));
      const p = await proof(trie, key, true);
      assert.ok(miss(r, key, p), 'an absent key');
      const bigger = await Trie.fromList([...items, { key, value: Buffer.from('v') }]);
      assert.deepEqual(including(key, Buffer.from('v'), p), root(bigger), 'the root after an insert');
      assert.ok(!miss(r, items[0].key, await proof(trie, items[0].key)), 'a present key is not proven absent');
    }
  }
});

test('a claim: reads proven, writes applied in order to the new root; a forged step fails', async () => {
  const items = [{ key: Buffer.from('a'), value: Buffer.from('1') }, { key: Buffer.from('b'), value: Buffer.from('2') }];
  const trie = await Trie.fromList(items);
  const oldRoot = root(trie);
  const pa = await proof(trie, Buffer.from('a'));
  const after = await Trie.fromList([{ key: Buffer.from('a'), value: Buffer.from('9') }, items[1]]);
  const entry = (key, value) => ({ key: Buffer.from(key), present: value !== null, value: value === null ? new Uint8Array() : Buffer.from(value) });
  const claim = { oldRoot, newRoot: root(after), reads: [{ entry: entry('a', '1'), proof: pa }],
    writes: [{ before: entry('a', '1'), after: entry('a', '9'), proof: pa }] };
  assert.equal(verifyClaim(claim), true);
  assert.equal(verifyClaim({ ...claim, reads: [...claim.reads, ...claim.reads] }), false, 'a key read twice');
  assert.equal(verifyClaim({ ...claim, newRoot: oldRoot }), false, 'another new root');
  assert.equal(verifyClaim({ ...claim, reads: [{ entry: entry('a', '2'), proof: pa }] }), false, 'a read of another value');
  const absent = { ...claim, reads: [{ entry: entry('a', null), proof: pa }] };
  assert.equal(verifyClaim(absent), false, 'a present key is not read as absent with its own proof');
  const forged = pa.map((s) => (s.leaf ? { leaf: { ...s.leaf, skip: 70n } } : s.branch ? { branch: { ...s.branch, skip: 70n } } : { fork: { ...s.fork, skip: 70n } }));
  assert.throws(() => verifyClaim({ ...claim, reads: [{ entry: entry('a', '1'), proof: forged }] }), 'a step beyond the path');
});
