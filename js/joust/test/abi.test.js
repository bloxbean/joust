// ADR-0018 D12 (review F1, S3): the ABI gate of joust build and joust dev. Needs nothing but Node.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { abiDiff, abiGate, firstDifference, parseBoundAcceptance, sha256 } from '../src/abi.js';
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

// ADR-0021 D7a (ADR-0018 D12 amended): --accept-abi=<Contract>@<committed>..<new>, bound to the manifests reviewed.
test('a bound acceptance accepts the one contract when both manifests are the reviewed ones', () => {
  const root = project();
  writeFileSync(manifest(root), abi(1));
  const two = [{ jscFile: 'app/Counter.jsc', javaFile: 'app/Counter.java', abi: abi(2) }, { jscFile: 'app/Vault.jsc', javaFile: 'app/Vault.java', abi: abi(3) }];
  const d = abiDiff({ generated: two, srcDir: 'contracts', root, contract: 'Counter' });
  assert.equal(d.committed.sha256, sha256(abi(1)));
  assert.equal(d.now.sha256, sha256(abi(2)));
  assert.equal(d.committed.text, abi(1));
  const bound = parseBoundAcceptance(`Counter@${d.committed.sha256}..${d.now.sha256}`);
  const errors = abiGate({ generated: two, srcDir: 'contracts', root, acceptAbi: bound, log: () => {} });
  assert.equal(readFileSync(manifest(root), 'utf8'), abi(2), 'Counter accepted');
  // Vault's missing manifest is not accepted by Counter's acceptance.
  assert.equal(errors.length, 1);
  assert.match(errors[0], /JSC0003.*Vault/);
  assert.equal(existsSync(join(root, 'contracts', 'app', 'Vault.abi.json')), false);
});

test('a bound acceptance is refused when the committed manifest changed (a git pull) or the source changed since the diff', () => {
  const root = project();
  writeFileSync(manifest(root), abi(1));
  const g = (n) => [{ jscFile: 'app/Counter.jsc', javaFile: 'app/Counter.java', abi: abi(n) }];
  const d = abiDiff({ generated: g(2), srcDir: 'contracts', root, contract: 'Counter' });
  const bound = parseBoundAcceptance(`Counter@${d.committed.sha256}..${d.now.sha256}`);
  // The source changed: the build now computes another manifest.
  const refused = new Set();
  let errors = abiGate({ generated: g(3), srcDir: 'contracts', root, acceptAbi: bound, log: () => {}, refused });
  assert.match(errors[0], /not accepted: the manifest the build computes now/);
  assert.equal(readFileSync(manifest(root), 'utf8'), abi(1));
  assert.ok(refused.has('app/Counter.java'));
  // The committed manifest changed under the review.
  writeFileSync(manifest(root), abi(4));
  errors = abiGate({ generated: g(2), srcDir: 'contracts', root, acceptAbi: bound, log: () => {} });
  assert.match(errors[0], /not accepted: the committed manifest/);
  assert.equal(readFileSync(manifest(root), 'utf8'), abi(4));
  // No manifest committed yet: `none` binds that.
  rmSync(manifest(root));
  const fresh = abiDiff({ generated: g(2), srcDir: 'contracts', root, contract: 'Counter' });
  assert.equal(fresh.committed.sha256, 'none');
  assert.deepEqual(abiGate({ generated: g(2), srcDir: 'contracts', root, acceptAbi: parseBoundAcceptance(`Counter@none..${fresh.now.sha256}`), log: () => {} }), []);
  assert.equal(readFileSync(manifest(root), 'utf8'), abi(2));
  assert.equal(parseBoundAcceptance('Counter@abc..def'), null);
  assert.equal(parseBoundAcceptance('../x@none..' + 'a'.repeat(64)), null);
  assert.match(abiDiff({ generated: g(2), srcDir: 'contracts', root, contract: 'Nope' }).error, /no \.jsc 2 contract Nope/);
});

// #99 S5, F5: a contract is its package plus name. A simple name two contracts share is refused; the qualified one works.
test('the bound acceptance and the diff name a contract by package; an ambiguous simple name accepts nothing', () => {
  const root = project();
  mkdirSync(join(root, 'contracts', 'other'), { recursive: true });
  const two = [{ jscFile: 'app/Counter.jsc', javaFile: 'app/Counter.java', abi: abi(2) },
    { jscFile: 'other/Counter.jsc', javaFile: 'other/Counter.java', abi: abi(3) }];
  const amb = abiDiff({ generated: two, srcDir: 'contracts', root, contract: 'Counter' });
  assert.match(amb.error, /Counter is ambiguous: app\.Counter \(app\/Counter\.jsc\), other\.Counter \(other\/Counter\.jsc\)/);
  const d = abiDiff({ generated: two, srcDir: 'contracts', root, contract: 'other.Counter' });
  assert.equal(d.contract, 'other.Counter');
  assert.equal(d.file, 'other/Counter.jsc');
  assert.equal(d.path, join('contracts', 'other', 'Counter.abi.json'));
  const errors = abiGate({ generated: two, srcDir: 'contracts', root, acceptAbi: parseBoundAcceptance(`Counter@none..${d.now.sha256}`), log: () => {} });
  assert.match(errors[0], /--accept-abi=Counter@…: Counter is ambiguous.*nothing accepted/);
  assert.equal(existsSync(join(root, 'contracts', 'other', 'Counter.abi.json')), false);
  assert.equal(existsSync(manifest(root)), false);
  const ok = abiGate({ generated: two, srcDir: 'contracts', root, acceptAbi: parseBoundAcceptance(`other.Counter@none..${d.now.sha256}`), log: () => {} });
  assert.equal(readFileSync(join(root, 'contracts', 'other', 'Counter.abi.json'), 'utf8'), abi(3));
  assert.equal(existsSync(manifest(root)), false, 'app.Counter is not accepted');
  assert.equal(ok.length, 1);
  assert.match(ok[0], /JSC0003.*app\/Counter/);
  // A unique simple name still works, and resolves to the qualified contract.
  assert.equal(abiDiff({ generated: [two[0]], srcDir: 'contracts', root, contract: 'Counter' }).contract, 'app.Counter');
  assert.equal(parseBoundAcceptance('app..Counter@none..' + 'a'.repeat(64)), null);
});
