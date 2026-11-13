// ADR-0021 D4b, D12 "Lexical rejection (S14)" and the file rules: with an instrumented filesystem recording every call,
// a path that is absolute, UNC, \\?\, \\.\, drive-relative or escaping with '..' is rejected with NO stat, realpath,
// open or readlink; a symlink to such a target is rejected after reading only the link itself; FIFOs, devices and
// oversized files are refused without blocking.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, promises as fsp, symlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createReader } from '../../src/lsp/inputs.js';
import { inside, lexicalSegments } from '../../src/lsp/paths.js';
import { tmp } from './client.js';

function recordingFs() {
  const calls = [];
  const wrap = (name) => (...a) => {
    calls.push([name, String(a[0])]);
    return fsp[name](...a);
  };
  return { calls, fs: { lstat: wrap('lstat'), readlink: wrap('readlink'), realpath: wrap('realpath'), open: wrap('open') } };
}

const HOSTILE = ['/', '/etc/passwd', '\\\\host\\share\\x', '//host/share/x', '\\\\?\\C:\\x', '\\\\.\\pipe\\x', '\\\\.\\C:', 'C:x', 'C:\\x',
  'C:/x', '..', '../..', '../outside', 'a/../../b', 'a\\..\\..\\b', 'x\0y', '', 'a:stream'];

test('hostile paths are refused as text, before any filesystem call (S14)', async () => {
  const folder = tmp();
  const { calls, fs } = recordingFs();
  const reader = createReader(fs);
  for (const p of HOSTILE) {
    assert.equal(lexicalSegments(p), null, JSON.stringify(p));
    assert.deepEqual(await reader.resolveIn(folder, p), { error: 'refused' }, JSON.stringify(p));
    assert.deepEqual(await reader.read(folder, p), { error: 'refused' }, JSON.stringify(p));
  }
  assert.deepEqual(calls, [], 'no filesystem call for a refused path');
  // '..' may climb within its base (a project root inside the folder), never above the folder.
  assert.deepEqual(lexicalSegments('../contracts', ['pkg', 'app']), ['pkg', 'contracts']);
  assert.equal(lexicalSegments('../../../x', ['pkg', 'app']), null);
});

test('a symlink to a hostile target is refused after reading only the link (S14)', async () => {
  const folder = tmp();
  mkdirSync(join(folder, 'contracts'));
  symlinkSync('/', join(folder, 'contracts', 'root'));
  symlinkSync('../../..', join(folder, 'contracts', 'up'));
  symlinkSync('/dev/zero', join(folder, 'Zero.jsc'));
  for (const [rel, link] of [['contracts/root/etc/passwd', 'contracts/root'], ['contracts/up/x', 'contracts/up'], ['Zero.jsc', 'Zero.jsc']]) {
    const { calls, fs } = recordingFs();
    const r = await createReader(fs).read(folder, rel);
    assert.deepEqual(r, { error: 'refused' }, rel);
    const touched = calls.map(([, p]) => p);
    for (const p of touched) assert.ok(inside(p, folder) && !p.startsWith('/dev') && !p.startsWith('/etc'), `${rel}: touched ${p}`);
    assert.deepEqual(calls.filter(([n]) => n === 'readlink').map(([, p]) => p), [join(folder, link)]);
    assert.ok(!calls.some(([n]) => n === 'open' || n === 'realpath'), `${rel}: ${JSON.stringify(calls)}`);
  }
});

test('a symlink inside the folder is followed; the real path must stay inside (segment comparison)', async () => {
  const folder = tmp();
  mkdirSync(join(folder, 'a'));
  writeFileSync(join(folder, 'a', 'x.json'), '{"ok":true}');
  symlinkSync('a', join(folder, 'b'));
  const reader = createReader();
  assert.deepEqual(await reader.readJson(folder, 'b/x.json'), { ok: true });
  // repo and repo-evil: a string prefix, not a containing folder.
  const parent = tmp();
  mkdirSync(join(parent, 'repo'));
  mkdirSync(join(parent, 'repo-evil'));
  assert.equal(inside(join(parent, 'repo-evil', 'x'), join(parent, 'repo')), false);
  assert.equal(inside(join(parent, 'repo', 'x'), join(parent, 'repo')), true);
  assert.equal(inside(join(parent, 'repo'), join(parent, 'repo')), true);
});

test('FIFOs, directories and oversized files are refused, and a FIFO never blocks the open', async (t) => {
  const folder = tmp();
  writeFileSync(join(folder, 'big.json'), 'x'.repeat(70 * 1024));
  mkdirSync(join(folder, 'dir.json'));
  const reader = createReader();
  assert.deepEqual(await reader.read(folder, 'big.json'), { error: 'too-large' });
  assert.deepEqual(await reader.read(folder, 'dir.json'), { error: 'not-a-file' });
  if (process.platform === 'win32') return t.skip('no FIFOs');
  execFileSync('mkfifo', [join(folder, 'fifo.jsc')]);
  const started = Date.now();
  assert.deepEqual(await reader.read(folder, 'fifo.jsc'), { error: 'not-a-file' });
  assert.ok(Date.now() - started < 2000);
});
