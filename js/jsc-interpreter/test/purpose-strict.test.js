// M3c (ADR-0010 D1, D2; ADR-0011 D4; K2, K39, K48): both IR readers refuse a program with script purposes or migration
// outside the checker's shapes, each mutant with the fragment the Java reader's message shares (JscV2M3cIrStrictTest
// reads the same list, test/mutants/purpose-mutants.json); the unmutated programs load.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { load } from '../src/interpreter.js';

const read = (n) => readFileSync(new URL(`./mutants/${n}`, import.meta.url), 'utf8');

test('the reader refuses every purpose and migration mutant (shared with the Java reader)', () => {
  const list = JSON.parse(read('purpose-mutants.json'));
  assert.ok(list.length >= 30, `${list.length} mutants`);
  for (const { file, message } of list) {
    assert.throws(() => load(read(file)), (e) => e.message.includes(message), `${file}: refused, mentioning '${message}'`);
  }
  // Review C8 (r3): the imported-library mutants' control (the library fn's result true) loads.
  assert.doesNotThrow(() => load(read('purpose-library-control.json')), 'the library control');
  for (const f of ['StakedVault', 'CounterV1', 'CounterV2']) {
    assert.doesNotThrow(() => load(readFileSync(new URL(`./fixtures/${f}.ir.json`, import.meta.url), 'utf8')), f);
  }
});
