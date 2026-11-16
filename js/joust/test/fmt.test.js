// ADR-0021 r9 §15 (M5d): `joust fmt [--check] [paths]`, through the engine's lang.format. Formats in place, writes only a
// file whose text changes, leaves a file that does not parse as it was (F4), and --check writes nothing.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
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
  // #120 F5: a refused file is exit 2, over the unformatted file's 1.
  assert.equal(r.status, 2, r.stdout + r.stderr);
  assert.match(r.stdout, /A\.jsc: not formatted/);
  assert.match(r.stderr, /Bad\.jsc:\d+:\d+: error\[JSC\d+\] .*\(not formatted\)/);
  assert.equal(readFileSync(a, 'utf8'), before, '--check writes nothing');

  r = run(root, ['fmt']);
  assert.equal(r.status, 2, r.stdout + r.stderr);
  assert.equal(readFileSync(a, 'utf8'), FORMATTED);
  assert.equal(statSync(b).mtimeMs, bStamp, 'a formatted file is not written');
  assert.equal(readFileSync(bad, 'utf8'), broken, 'F4: left as it was');
  assert.deepEqual(readdirSync(join(root, 'contracts', 'app')).filter((f) => f.endsWith('.fmt')), [], 'no temporary file left');

  r = run(root, ['fmt', '--check', 'contracts/app/A.jsc', 'contracts/app/B.jsc']);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /2 file\(s\), 0 not formatted, 0 refused/);
  r = run(root, ['fmt', 'contracts/app/Missing.jsc']);
  assert.equal(r.status, 3, r.stdout + r.stderr);
  r = run(root, ['fmt', '--bogus']);
  assert.equal(r.status, 3, r.stdout + r.stderr);
  writeFileSync(a, FORMATTED.replace('    return', '      return'));
  r = run(root, ['fmt', '--check', 'contracts/app/A.jsc']);
  assert.equal(r.status, 1, 'not formatted, under --check: 1');
});

test('#120 F1: a file that is not valid UTF-8 is refused byte for byte; the others are formatted', { skip, timeout: 300_000 }, () => {
  const root = project();
  const bad = join(root, 'contracts', 'app', 'Lat.jsc');
  const latin1 = Buffer.from(FORMATTED.replace('  state', '// caf\u00e9 latin-1\n      state'), 'latin1');
  writeFileSync(bad, latin1);
  const str = join(root, 'contracts', 'app', 'Str.jsc');
  const latin1String = Buffer.from(FORMATTED.replace('contract A', 'contract S').replace('return { n: 0 }', 'require(true, "caf\u00e9")\n    return { n: 0 }'), 'latin1');
  writeFileSync(str, latin1String);
  const ok = join(root, 'contracts', 'app', 'Z.jsc');
  writeFileSync(ok, FORMATTED.replace('contract A', 'contract Z').replace('    return', '        return'));
  const r = run(root, ['fmt']);
  assert.equal(r.status, 2, r.stdout + r.stderr);
  assert.match(r.stderr, /Lat\.jsc: error: not valid UTF-8/);
  assert.deepEqual(readFileSync(bad), latin1);
  assert.deepEqual(readFileSync(str), latin1String);
  assert.equal(readFileSync(ok, 'utf8'), FORMATTED.replace('contract A', 'contract Z'));
});

test('#120 F2: a rewritten file keeps its mode; a read-only file is refused, never made writable', { skip, timeout: 300_000 }, () => {
  const root = project();
  const messy = FORMATTED.replace('    return', '        return');
  const files = { 'S.jsc': 0o644, 'G.jsc': 0o660, 'R.jsc': 0o444 };
  for (const [f, m] of Object.entries(files)) {
    writeFileSync(join(root, 'contracts', 'app', f), messy.replace('contract A', `contract ${f[0]}`));
    chmodSync(join(root, 'contracts', 'app', f), m);
  }
  const r = run(root, ['fmt']);
  assert.equal(r.status, 2, r.stdout + r.stderr);
  assert.match(r.stderr, /R\.jsc: error: read-only/);
  const mode = (f) => statSync(join(root, 'contracts', 'app', f)).mode & 0o777;
  assert.equal(mode('S.jsc'), 0o644);
  assert.equal(mode('G.jsc'), 0o660);
  assert.equal(mode('R.jsc'), 0o444);
  assert.equal(readFileSync(join(root, 'contracts', 'app', 'S.jsc'), 'utf8'), FORMATTED.replace('contract A', 'contract S'));
  assert.equal(readFileSync(join(root, 'contracts', 'app', 'R.jsc'), 'utf8'), messy.replace('contract A', 'contract R'));
  // --check reports it as not formatted (1), without refusing it.
  const c = run(root, ['fmt', '--check', 'contracts/app/R.jsc']);
  assert.equal(c.status, 1, c.stdout + c.stderr);
});

test('#120 S3: the default target only inside the project; named files anywhere', { skip, timeout: 300_000 }, () => {
  const root = project();
  const outside = realpathSync(mkdtempSync(join(tmpdir(), 'joust-fmt-outside-')));
  const victim = join(outside, 'V.jsc');
  const messy = FORMATTED.replace('    return', '        return');
  writeFileSync(victim, messy);
  writeFileSync(join(root, 'joust.config.json'), JSON.stringify({ contracts: outside }));
  let r = run(root, ['fmt']);
  assert.equal(r.status, 3, r.stdout + r.stderr);
  assert.match(r.stderr, /outside the project/);
  assert.equal(readFileSync(victim, 'utf8'), messy);
  writeFileSync(join(root, 'joust.config.json'), JSON.stringify({ contracts: '../' + outside.split('/').pop() }));
  r = run(root, ['fmt']);
  assert.equal(r.status, 3, r.stdout + r.stderr);
  // Named on the command line, it is the user's choice.
  r = run(root, ['fmt', victim]);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.equal(readFileSync(victim, 'utf8'), FORMATTED);
});

test('#120 C3: a file is formatted with its imports (a library\'s enum); an undeclared constructor is refused', { skip, timeout: 300_000 }, () => {
  const root = project();
  writeFileSync(join(root, 'contracts', 'app', 'fees.jsc'), 'jsc 1\nlibrary app\n\nenum Tier { Basic, Gold { since: Int } }\n');
  const shop = 'jsc 1\npackage app\n\nimport { Tier } from "./fees.jsc"\n\ncontract S {\n  state { t: Tier }\n\n  init() {\n    return { t: Gold { since: 1 } }\n  }\n}\n';
  writeFileSync(join(root, 'contracts', 'app', 'S.jsc'), shop.replace('    return', '      return'));
  const nope = 'jsc 1\npackage app\n\nimport { Tier } from "./fees.jsc"\n\ncontract N {\n  state { n: Int }\n\n  init() {\n      return Nope { n: 0 }\n  }\n}\n';
  writeFileSync(join(root, 'contracts', 'app', 'N.jsc'), nope);
  const r = run(root, ['fmt']);
  assert.equal(r.status, 2, r.stdout + r.stderr);
  assert.match(r.stderr, /N\.jsc:\d+:\d+: error\[JSC0129\]/);
  assert.equal(readFileSync(join(root, 'contracts', 'app', 'S.jsc'), 'utf8'), shop);
  assert.equal(readFileSync(join(root, 'contracts', 'app', 'N.jsc'), 'utf8'), nope);
  const c = run(root, ['fmt', '--check', 'contracts/app/N.jsc']);
  assert.equal(c.status, 2, 'a refused file under --check: 2');
});

// #120 F9 (C5): the same bytes as JscMainFmtTest.aByteOrderMarkStays (the two CLIs agree).
const BOM_MESSY = '﻿jsc 1\npackage t\n\ncontract C {\nstate {n: Int}\n\n  init() {\n        return {n: 0}\n  }\n}\n';
const BOM_FORMATTED = '﻿jsc 1\npackage t\n\ncontract C {\n  state { n: Int }\n\n  init() {\n    return { n: 0 }\n  }\n}\n';

test('#120 F9: a byte order mark stays, byte for byte as jsc --fmt writes it', { skip, timeout: 300_000 }, () => {
  const root = project();
  const f = join(root, 'contracts', 'app', 'C.jsc');
  writeFileSync(f, Buffer.from(BOM_MESSY, 'utf8'));
  let r = run(root, ['fmt']);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  const out = readFileSync(f);
  assert.deepEqual([...out.subarray(0, 3)], [0xef, 0xbb, 0xbf]);
  assert.deepEqual(out, Buffer.from(BOM_FORMATTED, 'utf8'));
  r = run(root, ['fmt', '--check']);
  assert.equal(r.status, 0, 'formatted: nothing more to do');
});

test('#120 F10: a named file finds its module root as the build does; outside one, the refusal names the import', { skip, timeout: 300_000 }, () => {
  const root = project();
  const tiers = 'jsc 1\nlibrary lib\n\nenum Tier { Basic, Gold { since: Int } }\n';
  const c = 'jsc 1\npackage app\n\nimport { Tier } from "../lib/tiers.jsc"\n\ncontract C {\n  state { t: Tier }\n\n  init() {\n    return { t: Gold { since: 1 } }\n  }\n}\n';
  // Under the configured contracts directory, named from elsewhere: contracts/lib and contracts/app.
  mkdirSync(join(root, 'contracts', 'lib'), { recursive: true });
  writeFileSync(join(root, 'contracts', 'lib', 'tiers.jsc'), tiers);
  writeFileSync(join(root, 'contracts', 'app', 'C.jsc'), c.replace('    return', '      return'));
  let r = run(join(root, 'contracts', 'app'), ['fmt', 'C.jsc']);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.equal(readFileSync(join(root, 'contracts', 'app', 'C.jsc'), 'utf8'), c);
  // A Gradle-style source set outside it: src/main/jsc is the module root.
  const set = join(root, 'other', 'src', 'main', 'jsc');
  mkdirSync(join(set, 'lib'), { recursive: true });
  mkdirSync(join(set, 'app'), { recursive: true });
  writeFileSync(join(set, 'lib', 'tiers.jsc'), tiers);
  writeFileSync(join(set, 'app', 'C.jsc'), c.replace('    return', '      return'));
  r = run(root, ['fmt', join(set, 'app', 'C.jsc')]);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.equal(readFileSync(join(set, 'app', 'C.jsc'), 'utf8'), c);
  // Neither: the import cannot be read from the file's directory, and the error names it.
  const loose = join(root, 'loose');
  mkdirSync(join(loose, 'app'), { recursive: true });
  mkdirSync(join(loose, 'lib'), { recursive: true });
  writeFileSync(join(loose, 'lib', 'tiers.jsc'), tiers);
  writeFileSync(join(loose, 'app', 'C.jsc'), c);
  r = run(root, ['fmt', join(loose, 'app', 'C.jsc')]);
  assert.equal(r.status, 2, r.stdout + r.stderr);
  assert.match(r.stderr, /the import "\.\.\/lib\/tiers\.jsc" cannot be read here/);
});

test('#120 S5: an unreadable directory never ends the run', { skip, timeout: 300_000 }, () => {
  const root = project();
  const a = join(root, 'contracts', 'app', 'A.jsc');
  writeFileSync(a, FORMATTED.replace('    return', '        return'));
  const locked = join(root, 'contracts', 'app', 'locked');
  mkdirSync(locked);
  writeFileSync(join(locked, 'L.jsc'), FORMATTED);
  chmodSync(locked, 0);
  try {
    // A named file (its imports' walk meets the directory): formatted.
    let r = run(root, ['fmt', 'contracts/app/A.jsc']);
    assert.equal(r.status, 0, r.stdout + r.stderr);
    assert.equal(readFileSync(a, 'utf8'), FORMATTED);
    // The default target: the readable files formatted, the directory reported (exit 2).
    writeFileSync(a, FORMATTED.replace('    return', '        return'));
    r = run(root, ['fmt']);
    assert.equal(r.status, 2, r.stdout + r.stderr);
    assert.match(r.stderr, /locked: error: cannot be read/);
    assert.equal(readFileSync(a, 'utf8'), FORMATTED);
  } finally {
    chmodSync(locked, 0o755);
  }
});
