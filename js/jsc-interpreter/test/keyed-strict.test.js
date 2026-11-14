// M3b (ADR-0016 D1, K2): both IR readers refuse a keyed program outside the checker's shapes, each mutant with the
// fragment the Java reader's message shares (JscV2M3bIrStrictTest reads the same list); a keyed action that changes its
// key is valid IR, refused by the interpreter's call (keyed:fixed), as the validator refuses its transaction.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { load } from '../src/interpreter.js';
import { fromJson, txFromJson } from '../src/json.js';

const read = (n) => readFileSync(new URL(`./mutants/${n}`, import.meta.url), 'utf8');

test('the reader refuses every keyed mutant (shared with the Java reader)', () => {
  const list = JSON.parse(read('keyed-mutants.json'));
  assert.ok(list.length >= 25, `${list.length} mutants`);
  for (const { file, message } of list) {
    assert.throws(() => load(read(file)), (e) => e.message.includes(message), `${file}: refused, mentioning '${message}'`);
  }
  // the unmutated program loads
  assert.doesNotThrow(() => load(readFileSync(new URL('./fixtures/Registry.ir.json', import.meta.url), 'utf8')));
});

test('a keyed action that changes its key is refused by the call (keyed:fixed), its transition computes it', () => {
  const p = load(read('keyed-key-changed.json'));
  const ann = 'a1'.repeat(28);
  const state = fromJson({ rec: 'Ballot', fields: [{ bytes: ann }, { int: '3' }] });
  const tx = txFromJson({ caller: ann, received: '0', balance: '0', validFrom: '1800000000000', validTo: '1800000120000' });
  assert.throws(() => p.call('change', state, [fromJson({ int: '7' })], tx), (e) => e.refusal === 'keyed:fixed');
  const t = p.transition('change', state, [fromJson({ int: '7' })], tx);
  assert.equal(t.value.fields[1], 7n);
});
