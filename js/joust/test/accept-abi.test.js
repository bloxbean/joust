// ADR-0021 D7a (ADR-0018 D12 as amended): `joust abi diff <Contract>` shows the committed manifest and the new one with
// their SHA-256; `joust build --accept-abi=<Contract>@<committed>..<new>` accepts that one contract only, and refuses
// when the source changed since the diff or the committed manifest changed (a git pull). Needs the toolchain engine.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const BIN = fileURLToPath(new URL('../bin/joust.js', import.meta.url));
const HAS_ENGINE = existsSync(fileURLToPath(new URL('../engine/joust-toolchain.js.wasm', import.meta.url)));
const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine';

const contract = (name, fields) => `jsc 2
package app

contract ${name} {
  state { owner: Key${fields} }

  init() {
    return { owner: caller${fields.replace(/: Int/g, ': 0')} }
  }

  destroy close() only owner {
    pay(owner, balance)
  }
}
`;

function project() {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'joust-abi-')));
  mkdirSync(join(root, 'contracts', 'app'), { recursive: true });
  mkdirSync(join(root, 'node_modules', '@julclang'), { recursive: true });
  writeFileSync(join(root, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '1.0.0' } }));
  writeFileSync(join(root, 'joust.config.json'), '{ "contracts": "contracts" }');
  symlinkSync(fileURLToPath(new URL('..', import.meta.url)), join(root, 'node_modules', '@julclang', 'joust'));
  writeFileSync(join(root, 'contracts', 'app', 'A.jsc'), contract('A', ', n: Int'));
  writeFileSync(join(root, 'contracts', 'app', 'B.jsc'), contract('B', ', m: Int'));
  return root;
}

const run = (cwd, args) => spawnSync(process.execPath, [BIN, ...args], { cwd, encoding: 'utf8', timeout: 240_000, env: { ...process.env, JOUST_DEFERRED: '' } });
const manifest = (root, c) => join(root, 'contracts', 'app', `${c}.abi.json`);

function diff(root, c) {
  const out = join(root, `${c}-diff.json`);
  const r = run(root, ['abi', 'diff', c, '--out', out]);
  assert.equal(r.status, 0, r.stderr + r.stdout);
  return JSON.parse(readFileSync(out, 'utf8'));
}

test('abi diff, then a bound acceptance: only that contract, only while both manifests are the reviewed ones', { skip, timeout: 900_000 }, () => {
  const root = project();
  // Nothing committed: the diff says none, and A's acceptance creates A's manifest only.
  let d = diff(root, 'A');
  assert.equal(d.committed.sha256, 'none');
  assert.match(d.now.sha256, /^[0-9a-f]{64}$/);
  let r = run(root, ['build', `--accept-abi=A@${d.committed.sha256}..${d.now.sha256}`]);
  assert.equal(r.status, 1, 'B has no manifest: the build fails for it');
  assert.equal(readFileSync(manifest(root, 'A'), 'utf8'), d.now.text);
  assert.equal(existsSync(manifest(root, 'B')), false, 'B is not accepted by A\'s acceptance');
  assert.match(r.stderr, /B\.jsc:1:1: error\[JSC0003\]/);
  const b = diff(root, 'B');
  assert.equal(run(root, ['build', `--accept-abi=B@none..${b.now.sha256}`]).status, 0);
  // A changes; the source changes again after the diff: refused, nothing written.
  writeFileSync(join(root, 'contracts', 'app', 'A.jsc'), contract('A', ', n: Int, k: Int'));
  d = diff(root, 'A');
  assert.notEqual(d.committed.sha256, d.now.sha256);
  writeFileSync(join(root, 'contracts', 'app', 'A.jsc'), contract('A', ', n: Int, j: Int, k: Int'));
  const kept = readFileSync(manifest(root, 'A'), 'utf8');
  r = run(root, ['build', `--accept-abi=A@${d.committed.sha256}..${d.now.sha256}`]);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /not accepted: the manifest the build computes now/);
  assert.equal(readFileSync(manifest(root, 'A'), 'utf8'), kept);
  // The committed manifest changes under the review (a git pull): refused.
  d = diff(root, 'A');
  writeFileSync(manifest(root, 'A'), `${kept.trimEnd()}\n\n`);
  r = run(root, ['build', `--accept-abi=A@${d.committed.sha256}..${d.now.sha256}`]);
  assert.equal(r.status, 1);
  assert.match(r.stderr, /not accepted: the committed manifest/);
  // Reviewed again: accepted.
  d = diff(root, 'A');
  r = run(root, ['build', `--accept-abi=A@${d.committed.sha256}..${d.now.sha256}`]);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(readFileSync(manifest(root, 'A'), 'utf8'), d.now.text);
  // A malformed acceptance is a usage error; an unknown contract is not one of the build's.
  assert.equal(run(root, ['build', '--accept-abi=A@x..y']).status, 2);
  assert.match(run(root, ['abi', 'diff', 'Nope']).stderr, /no \.jsc 2 contract Nope/);
});
