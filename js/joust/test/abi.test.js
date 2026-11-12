// ADR-0018 D12 (review F1, S3): the ABI gate of joust build and joust dev. Needs nothing but Node.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { abiGate, firstDifference } from '../src/abi.js';

const abi = (n) => `{\n  "abi": 2,\n  "datum": { "name": "State", "constr": 0, "fields": [${'x'.repeat(n)}] }\n}\n`;

function project() {
  const root = mkdtempSync(join(tmpdir(), 'joust-abi-'));
  mkdirSync(join(root, 'contracts', 'app'), { recursive: true });
  return root;
}

const generated = (text) => [{ jscFile: 'app/Counter.jsc', javaFile: 'app/Counter.java', abi: text },
  { jscFile: 'app/Old.jsc', javaFile: 'app/Old.java', abi: null }];   // a v1 file: no manifest
const manifest = (root) => join(root, 'contracts', 'app', 'Counter.abi.json');

test('equal manifest: the build passes and nothing is written', () => {
  const root = project();
  writeFileSync(manifest(root), abi(1));
  const logs = [];
  assert.deepEqual(abiGate({ generated: generated(abi(1)), srcDir: 'contracts', root, log: (l) => logs.push(l) }), []);
  assert.deepEqual(logs, []);
});

test('a missing manifest fails (JSC0003) unless accepted, which creates it', () => {
  const root = project();
  const errors = abiGate({ generated: generated(abi(1)), srcDir: 'contracts', root, log: () => {} });
  assert.equal(errors.length, 1);
  assert.match(errors[0], /^error\[JSC0003\]: app\/Counter\.jsc: no ABI manifest/);
  assert.match(errors[0], /joust build --accept-abi/);
  assert.equal(existsSync(manifest(root)), false, 'nothing written without --accept-abi');
  const logs = [];
  assert.deepEqual(abiGate({ generated: generated(abi(1)), srcDir: 'contracts', root, acceptAbi: true, log: (l) => logs.push(l) }), []);
  assert.equal(readFileSync(manifest(root), 'utf8'), abi(1));
  assert.match(logs[0], /created the ABI manifest/);
});

test('a different manifest fails (JSC0002) naming the first differing line, unless accepted', () => {
  const root = project();
  writeFileSync(manifest(root), abi(1));
  const errors = abiGate({ generated: generated(abi(2)), srcDir: 'contracts', root, acceptAbi: false, log: () => {} });
  assert.equal(errors.length, 1);
  assert.match(errors[0], /^error\[JSC0002\]: app\/Counter\.jsc: .* differs from the committed one at line 3: /);
  assert.equal(readFileSync(manifest(root), 'utf8'), abi(1), 'the committed manifest is kept');
  assert.deepEqual(abiGate({ generated: generated(abi(2)), srcDir: 'contracts', root, acceptAbi: true, log: () => {} }), []);
  assert.equal(readFileSync(manifest(root), 'utf8'), abi(2));
});

test('only a true acceptAbi accepts: a string "false", undefined or 0 does not', () => {
  for (const flag of ['false', undefined, 0, null]) {
    const root = project();
    writeFileSync(manifest(root), abi(1));
    assert.equal(abiGate({ generated: generated(abi(2)), srcDir: 'contracts', root, acceptAbi: flag, log: () => {} }).length, 1);
  }
});

test('firstDifference', () => {
  assert.equal(firstDifference('a\nb\n', 'a\nc\n'), 'line 2: committed b, now c');
  assert.equal(firstDifference('a\n', 'a\nb'), 'line 2: committed , now b');
  assert.equal(firstDifference('{ "by" }\n]\n', '{ "by" },\n{ "ctx" }\n]\n'), 'line 2: committed ], now { "ctx" }');
});
