// #121 S5: joust build --accept-lock and --accept-abi write joust.lock and ABI manifests only as regular files, never
// through a link (a link to a file outside the project would be overwritten; a dangling one would create a file there),
// through a temporary file renamed over the original, keeping its mode. Needs no engine.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chmodSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { lockGate } from '../src/libraries.js';
import { abiGate } from '../src/abi.js';

const quiet = () => {};
const tmp = (p) => realpathSync(mkdtempSync(join(tmpdir(), p)));
const FEES = [{ kind: 'jsc', name: 'pkg:@acme/fees/fees.jsc', sha256: 'ab'.repeat(32), external: true }];

test('S5: --accept-lock refuses a joust.lock that is a link (to an outside file, or dangling) and writes nothing through it', () => {
  const outside = tmp('joust-victim-');
  const victim = join(outside, 'dot-zshrc');
  writeFileSync(victim, 'export PATH=/usr/bin\n');
  // A link to an existing outside file.
  let root = tmp('joust-accept-');
  symlinkSync(victim, join(root, 'joust.lock'));
  let r = lockGate({ generated: [], libraries: FEES, root, acceptLock: true, log: quiet });
  assert.match(r[0] ?? '', /JSC0732.*is a link: replace the link with the file itself/);
  assert.equal(readFileSync(victim, 'utf8'), 'export PATH=/usr/bin\n', 'the outside file is untouched');
  assert.ok(lstatSync(join(root, 'joust.lock')).isSymbolicLink());
  // A dangling link to an outside path.
  root = tmp('joust-accept-');
  const ghost = join(outside, 'created-by-joust');
  symlinkSync(ghost, join(root, 'joust.lock'));
  r = lockGate({ generated: [], libraries: FEES, root, acceptLock: true, log: quiet });
  assert.match(r[0] ?? '', /JSC0732.*is a link/);
  assert.ok(!existsSync(ghost), 'nothing is created outside the project');
  // A directory: not a regular file.
  root = tmp('joust-accept-');
  mkdirSync(join(root, 'joust.lock'));
  r = lockGate({ generated: [], libraries: FEES, root, acceptLock: true, log: quiet });
  assert.match(r[0] ?? '', /JSC0732/);
});

test('S5: an accepted joust.lock replaces a regular file through a temporary file, keeping its mode', () => {
  const root = tmp('joust-accept-');
  const lock = join(root, 'joust.lock');
  writeFileSync(lock, '{ "lock": 1, "libraries": {} }\n');
  chmodSync(lock, 0o640);
  assert.deepEqual(lockGate({ generated: [], libraries: FEES, root, acceptLock: true, log: quiet }), []);
  assert.equal(JSON.parse(readFileSync(lock, 'utf8')).libraries['@acme/fees/fees.jsc'], 'ab'.repeat(32));
  assert.equal(statSync(lock).mode & 0o777, 0o640);
  assert.deepEqual(readdirSync(root).filter((f) => f.endsWith('.tmp')), []);
  // A new lock: a regular file, 0644.
  const fresh = tmp('joust-accept-');
  assert.deepEqual(lockGate({ generated: [], libraries: FEES, root: fresh, acceptLock: true, log: quiet }), []);
  assert.ok(lstatSync(join(fresh, 'joust.lock')).isFile());
});

test('S5: --accept-abi refuses a manifest that is a link, and writes nothing through it', () => {
  const root = tmp('joust-abi-');
  mkdirSync(join(root, 'contracts', 'app'), { recursive: true });
  const outside = tmp('joust-victim-');
  const victim = join(outside, 'x.json');
  writeFileSync(victim, 'keep');
  symlinkSync(victim, join(root, 'contracts', 'app', 'Shop.abi.json'));
  const g = { jscFile: 'app/Shop.jsc', javaFile: 'app/Shop.java', abi: '{"abi":1}\n' };
  const refused = new Set();
  const errors = abiGate({ generated: [g], srcDir: 'contracts', root, acceptAbi: true, log: quiet, refused });
  assert.match(errors.join('\n'), /JSC0002.*not accepted: the ABI manifest .*Shop\.abi\.json is a link/);
  assert.equal(readFileSync(victim, 'utf8'), 'keep');
  assert.ok(refused.has('app/Shop.java'));
});

test('#121 M2 (final pass): an accepted lock and manifest keep their mode exactly under a restrictive umask', () => {
  const was = process.umask(0o077);
  try {
    const root = tmp('joust-accept-');
    const lock = join(root, 'joust.lock');
    writeFileSync(lock, '{ "lock": 1, "libraries": {} }\n');
    chmodSync(lock, 0o664);
    assert.deepEqual(lockGate({ generated: [], libraries: FEES, root, acceptLock: true, log: quiet }), []);
    assert.equal(statSync(lock).mode & 0o777, 0o664);
    mkdirSync(join(root, 'contracts', 'app'), { recursive: true });
    const manifest = join(root, 'contracts', 'app', 'Shop.abi.json');
    writeFileSync(manifest, '{"abi":0}\n');
    chmodSync(manifest, 0o664);
    const g = { jscFile: 'app/Shop.jsc', javaFile: 'app/Shop.java', abi: '{"abi":1}\n' };
    assert.deepEqual(abiGate({ generated: [g], srcDir: 'contracts', root, acceptAbi: true, log: quiet, refused: new Set() }), []);
    assert.equal(readFileSync(manifest, 'utf8'), '{"abi":1}\n');
    assert.equal(statSync(manifest).mode & 0o777, 0o664);
  } finally {
    process.umask(was);
  }
});
