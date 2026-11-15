// ADR-0021 r9 §15 (M5d): `joust fmt [--check] [paths]`, through the engine's lang.format. Formats in place, writes only a
// file whose text changes, leaves a file that does not parse as it was (F4), and --check writes nothing.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const BIN = fileURLToPath(new URL('../bin/joust.js', import.meta.url));
const HAS_ENGINE = existsSync(fileURLToPath(new URL('../engine/joust-toolchain.js.wasm', import.meta.url)));
const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine';
const FORMATTED = 'jsc 1\npackage app\n\ncontract A {\n  state { n: Int }\n\n  init() {\n    return { n: 0 }\n  }\n}\n';
const run = (cwd, args) => spawnSync(process.execPath, [BIN, ...args], { cwd, encoding: 'utf8', timeout: 120_000, env: { ...process.env, JOUST_DEFERRED: '' } });

function project() {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'joust-fmt-')));
  mkdirSync(join(root, 'contracts', 'app'), { recursive: true });
  mkdirSync(join(root, 'node_modules', '@julclang'), { recursive: true });
  writeFileSync(join(root, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '1.0.0' } }));
  writeFileSync(join(root, 'joust.config.json'), '{ "contracts": "contracts" }');
  symlinkSync(fileURLToPath(new URL('..', import.meta.url)), join(root, 'node_modules', '@julclang', 'joust'));
  return root;
}

test('joust fmt: in place, --check, and a file that does not parse left as it was (F4)', { skip, timeout: 300_000 }, () => {
  const root = project();
  const a = join(root, 'contracts', 'app', 'A.jsc');
  const b = join(root, 'contracts', 'app', 'B.jsc');
  const bad = join(root, 'contracts', 'app', 'Bad.jsc');
  writeFileSync(a, FORMATTED.replace(/\n/g, '\r\n').replace('  state { n: Int }', '\tstate {  n: Int }'));
  writeFileSync(b, FORMATTED.replace('contract A', 'contract B'));
  const broken = 'jsc 1\npackage app\n\ncontract Bad {\n  state { n: Int \n';
  writeFileSync(bad, broken);
  const before = readFileSync(a, 'utf8');
  const bStamp = statSync(b).mtimeMs;

  let r = run(root, ['fmt', '--check']);
  assert.equal(r.status, 1, r.stdout + r.stderr);
  assert.match(r.stdout, /A\.jsc: not formatted/);
  assert.match(r.stderr, /Bad\.jsc:\d+:\d+: error\[JSC\d+\] .*\(not formatted\)/);
  assert.equal(readFileSync(a, 'utf8'), before, '--check writes nothing');

  r = run(root, ['fmt']);
  assert.equal(r.status, 1, r.stdout + r.stderr);
  assert.equal(readFileSync(a, 'utf8'), FORMATTED);
  assert.equal(statSync(b).mtimeMs, bStamp, 'a formatted file is not written');
  assert.equal(readFileSync(bad, 'utf8'), broken, 'F4: left as it was');
  assert.deepEqual(readdirSync(join(root, 'contracts', 'app')).filter((f) => f.endsWith('.fmt')), [], 'no temporary file left');

  r = run(root, ['fmt', '--check', 'contracts/app/A.jsc', 'contracts/app/B.jsc']);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /2 file\(s\), 0 not formatted, 0 refused/);
  r = run(root, ['fmt', 'contracts/app/Missing.jsc']);
  assert.equal(r.status, 2, r.stdout + r.stderr);
  r = run(root, ['fmt', '--bogus']);
  assert.equal(r.status, 2, r.stdout + r.stderr);
});
