// ADR-0021 D4b, D12 "Lexical rejection (S14)" and the file rules: with an instrumented filesystem recording every call,
// a path that is absolute, UNC, \\?\, \\.\, drive-relative or escaping with '..' is rejected with NO stat, realpath,
// open or readlink; a symlink to such a target is rejected after reading only the link itself; FIFOs, devices and
// oversized files are refused without blocking.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, promises as fsp, renameSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createReader, descriptorPath } from '../../src/lsp/inputs.js';
import { inside, lexicalSegments, localPath } from '../../src/lsp/paths.js';
import { tmp } from './client.js';

function recordingFs() {
  const calls = [];
  const wrap = (name) => (...a) => {
    calls.push([name, String(a[0])]);
    return fsp[name](...a);
  };
  return { calls, fs: { lstat: wrap('lstat'), readlink: wrap('readlink'), realpath: wrap('realpath'), stat: wrap('stat'), open: wrap('open') } };
}

const HOSTILE = ['/', '/etc/passwd', '\\\\host\\share\\x', '//host/share/x', '\\\\?\\C:\\x', '\\\\.\\pipe\\x', '\\\\.\\C:', 'C:x', 'C:\\x',
  'C:/x', '..', '../..', '../outside', 'a/../../b', 'a\\..\\..\\b', 'x\0y', '', 'a:stream',
  // Windows device names, with and without an extension (review S4 on #83).
  'NUL', 'COM1', 'CONIN$', 'contracts/AUX.jsc', 'nul.txt', 'lpt9', 'x/con'];

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

/**
 * Review C1 on #83: a file checked inside the folder, or one of its directories, swapped for a symlink to an outside file
 * between the check and the open. The reader's fs is the real one, with a hook that makes the swap at the worst moment.
 */
function swapping(hookOn, swap) {
  let done = false;
  const fire = async (name, p, r) => {
    if (!done && name === hookOn.name && String(p).endsWith(hookOn.suffix)) {
      done = true;
      swap();
    }
    return r;
  };
  const wrap = (name) => async (...a) => fire(name, a[0], await fsp[name](...a));
  return { lstat: wrap('lstat'), readlink: wrap('readlink'), realpath: wrap('realpath'), stat: wrap('stat'), open: wrap('open') };
}

test('a file swapped for a symlink to an outside file after the check is refused (C1, final component)', async () => {
  const top = tmp();
  const folder = join(top, 'repo');
  mkdirSync(folder);
  mkdirSync(join(top, 'out'));
  writeFileSync(join(folder, 'engine.js'), 'INSIDE');
  writeFileSync(join(top, 'out', 'engine.js'), 'OUTSIDE ENGINE BYTES');
  const swap = () => {
    rmSync(join(folder, 'engine.js'));
    symlinkSync(join(top, 'out', 'engine.js'), join(folder, 'engine.js'));
  };
  const r = await createReader(swapping({ name: 'realpath', suffix: 'engine.js' }, swap)).read(folder, 'engine.js');
  assert.equal(r.bytes, undefined, `read ${r.bytes}`);
  assert.ok(['outside', 'changed'].includes(r.error), JSON.stringify(r));
});

test('a file swapped out and back between the open and the re-check is refused (C1, by inode)', async () => {
  const top = tmp();
  const folder = join(top, 'repo');
  mkdirSync(folder);
  mkdirSync(join(top, 'out'));
  writeFileSync(join(folder, 'engine.js'), 'INSIDE');
  writeFileSync(join(top, 'out', 'engine.js'), 'OUTSIDE ENGINE BYTES');
  let phase = 0;
  const fs = {
    lstat: (...a) => fsp.lstat(...a), readlink: (...a) => fsp.readlink(...a), stat: (...a) => fsp.stat(...a),
    realpath: async (p) => {
      const r = await fsp.realpath(p);
      if (phase === 0 && String(p).endsWith('engine.js')) {
        phase = 1;
        renameSync(join(folder, 'engine.js'), join(top, 'kept.js'));
        symlinkSync(join(top, 'out', 'engine.js'), join(folder, 'engine.js'));
      }
      return r;
    },
    open: async (...a) => {
      const fh = await fsp.open(...a);
      if (phase === 1) {
        phase = 2;
        rmSync(join(folder, 'engine.js'));
        writeFileSync(join(folder, 'engine.js'), 'INSIDE AGAIN');   // a new file where the checked one was
      }
      return fh;
    },
  };
  const r = await createReader(fs).read(folder, 'engine.js');
  assert.deepEqual(r, { error: 'changed' });
});

test('a directory swapped for a symlink to an outside directory after the check is refused (C1, ancestor)', async () => {
  const top = tmp();
  const folder = join(top, 'repo');
  mkdirSync(join(folder, 'engine'), { recursive: true });
  mkdirSync(join(top, 'out'));
  writeFileSync(join(folder, 'engine', 'e.js'), 'INSIDE');
  writeFileSync(join(top, 'out', 'e.js'), 'OUTSIDE ENGINE BYTES');
  const swap = () => {
    renameSync(join(folder, 'engine'), join(top, 'kept'));
    symlinkSync(join(top, 'out'), join(folder, 'engine'));
  };
  const r = await createReader(swapping({ name: 'realpath', suffix: 'e.js' }, swap)).read(folder, 'engine/e.js');
  assert.equal(r.bytes, undefined, `read ${r.bytes}`);
  assert.ok(['outside', 'changed'].includes(r.error), JSON.stringify(r));
});

test('folder and document paths are checked as text before any filesystem call (S4)', () => {
  for (const p of ['//host/share/repo', '\\\\host\\share', '\\\\?\\C:\\x', 'relative/x', '']) {
    assert.equal(localPath(p), null, JSON.stringify(p));
  }
  if (process.platform !== 'win32') {
    assert.equal(localPath('/repo/contracts/C.jsc'), '/repo/contracts/C.jsc');
    // S11: device names are Windows' only in absolute paths: a POSIX directory may be called aux or con.
    assert.equal(localPath('/Users/me/aux/repo'), '/Users/me/aux/repo');
    assert.equal(localPath('/home/u/con/x.jsc'), '/home/u/con/x.jsc');
  } else {
    assert.equal(localPath('C:\\repo\\NUL\\x'), null);
  }
});

/**
 * Review C11 on #83: an attacker that times every swap: the file is a symlink to an outside file whenever the reader
 * follows the path to open or stat it, and the inside file whenever the reader resolves the path's real path. Every
 * filesystem result is real; only the timing is the attacker's.
 */
function adversary(folder, top, { anchored }) {
  const link = () => {
    rmSync(join(folder, 'engine.js'), { force: true });
    symlinkSync(join(top, 'out', 'engine.js'), join(folder, 'engine.js'));
  };
  const restore = () => {
    rmSync(join(folder, 'engine.js'), { force: true });
    writeFileSync(join(folder, 'engine.js'), 'INSIDE');
  };
  let opened = false;
  return {
    lstat: (...a) => fsp.lstat(...a), readlink: (...a) => fsp.readlink(...a),
    realpath: async (p) => {
      if (opened) restore();               // the post-open re-resolution sees the inside file...
      const r = await fsp.realpath(p);
      if (opened) link();                  // ...and the stat after it follows the swapped link again
      return r;
    },
    stat: (...a) => fsp.stat(...a),
    open: async (p, flags) => {
      link();                              // the open follows the link to the outside file
      const fh = await fsp.open(p, flags);
      opened = true;
      return fh;
    },
    ...(anchored ? { descriptorPath } : {}),
  };
}

function raceFolder() {
  const top = tmp();
  const folder = join(top, 'repo');
  mkdirSync(folder);
  mkdirSync(join(top, 'out'));
  writeFileSync(join(folder, 'engine.js'), 'INSIDE');
  writeFileSync(join(top, 'out', 'engine.js'), 'OUTSIDE ENGINE BYTES');
  return { top, folder };
}

test('C11: swaps timed around the re-resolution and the stat are refused where the OS names the descriptor (Linux)', async (t) => {
  if (process.platform !== 'linux') return t.skip('no descriptor path on this OS: the residual window is ADR-0021 §12 note 16');
  const { top, folder } = raceFolder();
  const r = await createReader(adversary(folder, top, { anchored: true })).read(folder, 'engine.js');
  assert.equal(r.bytes, undefined, `read ${r.bytes}`);
  assert.equal(r.error, 'outside');
});

test('C11: the reviewer\'s single swap between the re-resolution and the stat is refused on every OS', async () => {
  const { top, folder } = raceFolder();
  // The portable check (no descriptor path) runs before and after the read: one timed swap no longer suffices.
  const fs = adversary(folder, top, { anchored: false });
  let realpaths = 0;
  const once = { ...fs, realpath: async (p) => (++realpaths <= 2 ? fs.realpath(p) : fsp.realpath(p)) };
  const r = await createReader(once).read(folder, 'engine.js');
  assert.equal(r.bytes, undefined, `read ${r.bytes}`);
  assert.ok(['outside', 'changed'].includes(r.error), JSON.stringify(r));
});
