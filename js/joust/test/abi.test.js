// ADR-0018 D12 (review F1, S3): the ABI gate of joust build and joust dev. Needs nothing but Node.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { abiGate, firstDifference } from '../src/abi.js';
import { build } from '../src/build.js';

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
  assert.match(errors[0], /^app\/Counter\.jsc:1:1: error\[JSC0003\]: no ABI manifest/);
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
  assert.match(errors[0], /^app\/Counter\.jsc:1:1: error\[JSC0002\]: .* differs from the committed one at line 3: /);
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

test('a refused manifest names its Java in `refused`; an accepted or equal one does not', () => {
  const root = project();
  const refused = new Set();
  abiGate({ generated: generated(abi(1)), srcDir: 'contracts', root, log: () => {}, refused });
  assert.deepEqual([...refused], ['app/Counter.java']);
  const none = new Set();
  abiGate({ generated: generated(abi(1)), srcDir: 'contracts', root, acceptAbi: true, log: () => {}, refused: none });
  abiGate({ generated: generated(abi(1)), srcDir: 'contracts', root, log: () => {}, refused: none });
  assert.deepEqual([...none], []);
});

// Security S9 (review r3): joust build writes no Java whose manifest was refused, and leaves none from an earlier build.
test('joust build: Java whose manifest is refused is not on disk', async () => {
  const root = project();
  const pkg = join('org', 'julclang', 'state', 'examples', 'dsl');
  const jsc = readFileSync(new URL('../../../examples/dsl/src/main/jsc/' + pkg + '/Counter2.jsc', import.meta.url), 'utf8');
  mkdirSync(join(root, 'contracts', pkg), { recursive: true });
  writeFileSync(join(root, 'contracts', pkg, 'Counter2.jsc'), jsc);
  const java = join(root, 'build', 'joust', 'generated', pkg, 'Counter2.java');
  mkdirSync(join(java, '..'), { recursive: true });
  writeFileSync(java, '// from an earlier build');
  const quiet = { root, log: () => {} };
  const error = console.error;
  console.error = () => {};
  try {
    const missing = await build(quiet);
    assert.equal(missing.ok, false);
    assert.match(missing.abiErrors[0], /^\S+\.jsc:1:1: error\[JSC0003\]/);
    assert.equal(existsSync(java), false, 'no Java for a missing manifest, not even an earlier build\'s');
    const accepted = await build({ ...quiet, acceptAbi: true });   // the control: an accepted manifest writes the Java
    assert.equal(accepted.ok, true);
    assert.match(readFileSync(java, 'utf8'), /Counter2/);
    // ADR-0018 D12 (r12): no message in the script names the file or a line; contracts.json carries the message map
    // that turns each one back into a .jsc position (the runtime shows it with a refusal).
    assert.doesNotMatch(readFileSync(java, 'utf8'), /"Counter2\.jsc:/);
    const bundle = JSON.parse(readFileSync(join(root, 'build', 'joust', 'contracts.json'), 'utf8'));
    const { messages } = bundle.find((c) => JSON.parse(c.description).preamble.title.includes('Counter2')) ?? {};
    assert.equal(messages?.source, join(pkg, 'Counter2.jsc'));
    const keys = Object.keys(messages.messages).filter((m) => m.startsWith('#'));
    assert.ok(keys.length > 0, 'generated keys in the map');
    for (const k of keys) assert.match(k, /^#(input|pay):(spend|mint|intent)\d+\.\d+$/);
    for (const sites of Object.values(messages.messages)) for (const s of sites) assert.ok(s.line > 0 && s.col > 0);
    const manifest = join(root, 'contracts', pkg, 'Counter2.abi.json');
    writeFileSync(manifest, readFileSync(manifest, 'utf8').replace('"Increment"', '"Bump"'));
    const changed = await build(quiet);
    assert.equal(changed.ok, false);
    assert.match(changed.abiErrors[0], /^\S+\.jsc:1:1: error\[JSC0002\]/);
    assert.equal(existsSync(java), false, 'no Java for a changed manifest');
  } finally {
    console.error = error;
  }
});

test('firstDifference', () => {
  assert.equal(firstDifference('a\nb\n', 'a\nc\n'), 'line 2: committed b, now c');
  assert.equal(firstDifference('a\n', 'a\nb'), 'line 2: committed , now b');
  assert.equal(firstDifference('{ "by" }\n]\n', '{ "by" },\n{ "ctx" }\n]\n'), 'line 2: committed ], now { "ctx" }');
});
