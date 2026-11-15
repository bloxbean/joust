// ADR-0021 r10 §16 (M5e) E1–E3: the editor's joust.lock gate (ADR-0018 J12) against `joust build`, on the same inputs.
// For each vector, `joust build` (src/build.js, in process) and the server's build (joust/checkBuild, the on-save build's
// path) see one project; the J12 errors must be the same codes, names and hashes. The server never writes the lock (E2),
// reads it confined and fails closed (E3), and a lock that changes during a build ends its results.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { build } from '../../src/build.js';
import { lockGate } from '../../src/libraries.js';
import { ENGINE, HAS_ENGINE, startLsp, tmp, uriOf, VSCODE_CAPS } from './client.js';

const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine';
const quiet = () => {};
const FEES = 'jsc 1\nlibrary acme.fees\n\nfn fee(amount: Int, bps: Int): Int = amount * bps / 10_000\n';
const SHOP = `jsc 1
package app

import { fee } from "@acme/fees/fees.jsc"

contract Shop {
  state { owner: Key, price: Int }

  init(price: Int) {
    return { owner: caller, price }
  }

  action buy() payable {
    require(received >= price + fee(price, 100), "pay the price and the fee")
    return state
  }

  destroy close() only owner {
    pay(owner, balance)
  }
}
`;

/** A trusted npm project, its own compiler the real engine, an npm package shipping a .jsc library. */
function project() {
  const root = tmp('joust-lock-');
  mkdirSync(join(root, 'contracts', 'app'), { recursive: true });
  writeFileSync(join(root, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '0.0.0' } }));
  writeFileSync(join(root, 'joust.config.json'), '{ "contracts": "contracts" }');
  const pkg = join(root, 'node_modules', '@julclang', 'joust');
  mkdirSync(join(pkg, 'engine'), { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: '@julclang/joust', version: '9.9.9' }));
  for (const f of readdirSync(ENGINE).filter((x) => /^(joust-toolchain\.js(\.wasm)?|package\.json)$/.test(x))) copyFileSync(join(ENGINE, f), join(pkg, 'engine', f));
  mkdirSync(join(root, 'node_modules', '@acme', 'fees', 'joust-lib'), { recursive: true });
  writeFileSync(join(root, 'node_modules', '@acme', 'fees', 'package.json'), JSON.stringify({ name: '@acme/fees', version: '1.0.0' }));
  writeFileSync(join(root, 'node_modules', '@acme', 'fees', 'joust-lib', 'fees.jsc'), FEES);
  writeFileSync(join(root, 'contracts', 'app', 'Shop.jsc'), SHOP);
  return root;
}

/** `joust build`'s J12 lines, `{ codes: [...], lines }` (none: the build passed J12). */
async function cli(root) {
  const r = await build({ root, log: quiet });
  const lines = r.lockErrors ?? [];
  return { ok: r.ok, lines: lines.map((l) => l.replace(/^error\[JSC\d+\]: /, '')), codes: lines.map((l) => /^error\[(JSC\d+)\]/.exec(l)?.[1]) };
}

/** The server's J12 diagnostics of its build of the same project (joust/checkBuild runs runBuild, as a save does). */
async function editor(lsp, uri, version) {
  lsp.change(uri, version, SHOP + ' '.repeat(version));
  await lsp.diagnostics(uri, version, 60000);
  const r = await lsp.request('joust/checkBuild', { uri });
  assert.ok(!r.error, JSON.stringify(r));
  const p = [...lsp.published].reverse().find((x) => x.uri === uri && x.version === version);
  const lock = (p?.diagnostics ?? []).filter((d) => d.code === 'JSC0731' || d.code === 'JSC0732');
  return { ok: r.ok, lines: lock.map((d) => d.message), codes: lock.map((d) => d.code), diagnostics: lock };
}

test('E1, E2: the editor\'s J12 decision is joust build\'s, vector by vector; the lock is never written', { skip, timeout: 600_000 }, async () => {
  const root = project();
  const lockFile = join(root, 'joust.lock');
  // The committed ABI manifest, so joust build reaches J12 (its ABI gate comes first).
  await build({ root, acceptAbi: true, log: quiet });
  // The library's real pin, from an accepted lock.
  await build({ root, acceptLock: true, log: quiet });
  const good = readFileSync(lockFile, 'utf8');
  const sha = JSON.parse(good).libraries['@acme/fees/fees.jsc'];
  assert.match(sha, /^[0-9a-f]{64}$/);
  const outside = mkdtempSync(join(tmpdir(), 'joust-lock-outside-'));
  writeFileSync(join(outside, 'joust.lock'), good);
  const vectors = [
    ['no lock', () => rmSync(lockFile, { force: true }), ['JSC0731']],
    ['the exact pin', () => writeFileSync(lockFile, good), []],
    ['another hash', () => writeFileSync(lockFile, good.replace(sha, 'ab'.repeat(32))), ['JSC0732']],
    ['not JSON', () => writeFileSync(lockFile, '{ nope'), ['JSC0732']],
    ['not version 1', () => writeFileSync(lockFile, JSON.stringify({ lock: 2, libraries: {} })), ['JSC0732']],
    ['libraries null', () => writeFileSync(lockFile, JSON.stringify({ lock: 1, libraries: null })), ['JSC0732']],
    ['an unused pin besides', () => writeFileSync(lockFile, JSON.stringify({ lock: 1, libraries: { '@acme/fees/fees.jsc': sha, '@old/x/x.jsc': 'cd'.repeat(32) } })), []],
    ['the library changed after acceptance', () => {
      writeFileSync(lockFile, good);
      writeFileSync(join(root, 'node_modules', '@acme', 'fees', 'joust-lib', 'fees.jsc'), FEES.replace('10_000', '20_000'));
    }, ['JSC0732']],
  ];
  const lsp = await startLsp({ folders: [root], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: true }, hostOptions: {} });
  try {
    const uri = uriOf(join(root, 'contracts', 'app', 'Shop.jsc'));
    lsp.open(uri, SHOP);
    await lsp.until(() => lsp.statuses.find((s) => s.uri === uri && s.mode === 'project-matched'), 60000, 'project-matched');
    let version = 1;
    for (const [what, arrange, codes] of vectors) {
      arrange();
      const before = (() => { try { return [readFileSync(lockFile, 'utf8'), statSync(lockFile).mtimeMs]; } catch { return null; } })();
      const c = await cli(root);
      // joust build itself never changed the lock either (no --accept-lock).
      const e = await editor(lsp, uri, ++version);
      assert.deepEqual(c.codes, codes, `${what}: joust build ${JSON.stringify(c.lines)}`);
      assert.deepEqual(e.codes, c.codes, `${what}: the editor ${JSON.stringify(e.lines)} against joust build ${JSON.stringify(c.lines)}`);
      assert.deepEqual(e.lines, c.lines, `${what}: the same libraries, hashes and words`);
      if (codes.length) assert.equal(e.ok, false, `${what}: the editor's build fails`);
      // E2: the editor wrote nothing (the lock as it was, or still absent).
      const after = (() => { try { return [readFileSync(lockFile, 'utf8'), statSync(lockFile).mtimeMs]; } catch { return null; } })();
      assert.deepEqual(after, before, `${what}: joust.lock untouched`);
      // A library's error at the import that brought it in; a lock that cannot be read at the file's start.
      for (const d of e.diagnostics.filter((x) => !/cannot be read/.test(x.message))) assert.equal(d.range.start.line, SHOP.split('\n').findIndex((l) => l.startsWith('import')), `${what}: ${JSON.stringify(d)}`);
    }
    // E3: a lock that is a link out of the folder: joust build follows it (and passes), the editor fails closed.
    writeFileSync(join(root, 'node_modules', '@acme', 'fees', 'joust-lib', 'fees.jsc'), FEES);
    rmSync(lockFile, { force: true });
    symlinkSync(join(outside, 'joust.lock'), lockFile);
    assert.deepEqual((await cli(root)).codes, []);
    let e = await editor(lsp, uri, ++version);
    assert.deepEqual(e.codes, ['JSC0732']);
    assert.match(e.lines[0], /cannot be read \(it leads outside the workspace folder\)/);
    // A directory, a FIFO, a lock past the cap: cannot be read.
    rmSync(lockFile, { force: true });
    mkdirSync(lockFile);
    e = await editor(lsp, uri, ++version);
    assert.deepEqual(e.codes, ['JSC0732']);
    rmSync(lockFile, { recursive: true, force: true });
    if (process.platform !== 'win32') {
      execFileSync('mkfifo', [lockFile]);
      e = await editor(lsp, uri, ++version);
      assert.deepEqual(e.codes, ['JSC0732']);
      rmSync(lockFile, { force: true });
    }
    // (joust build reads it whole: a valid lock, so it passes; the editor reads at most LOCK_CAP bytes.)
    writeFileSync(lockFile, JSON.stringify({ ...JSON.parse(good), pad: 'x'.repeat(2 * 1024 * 1024) }));
    e = await editor(lsp, uri, ++version);
    assert.deepEqual(e.codes, ['JSC0732']);
    assert.match(e.lines[0], /larger than/);
    // And back to the exact pin: clean.
    writeFileSync(lockFile, good);
    e = await editor(lsp, uri, ++version);
    assert.deepEqual(e.codes, []);
  } finally {
    await lsp.stop();
  }
});

test('E3: a lock changed on disk while its build runs ends the build\'s results', { skip, timeout: 300_000 }, async () => {
  const root = project();
  const lockFile = join(root, 'joust.lock');
  await build({ root, acceptAbi: true, acceptLock: true, log: quiet });
  const good = readFileSync(lockFile, 'utf8');
  const lsp = await startLsp({ folders: [root], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: true }, hostOptions: {} });
  try {
    const uri = uriOf(join(root, 'contracts', 'app', 'Shop.jsc'));
    lsp.open(uri, SHOP);
    await lsp.until(() => lsp.statuses.find((s) => s.uri === uri && s.mode === 'project-matched'), 60000, 'project-matched');
    await lsp.diagnostics(uri, 1, 60000);
    const pending = lsp.request('joust/checkBuild', { uri });
    // While it builds: the lock goes, and the client says so.
    await new Promise((r) => setTimeout(r, 50));
    rmSync(lockFile);
    lsp.conn.sendNotification('workspace/didChangeWatchedFiles', { changes: [{ uri: uriOf(lockFile), type: 3 }] });
    const r = await pending;
    assert.match(r.error ?? '', /changed while it built/, JSON.stringify(r));
    // Asked again: the missing lock is reported.
    const again = await lsp.request('joust/checkBuild', { uri });
    assert.equal(again.ok, false);
    const p = [...lsp.published].reverse().find((x) => x.uri === uri);
    assert.ok(p.diagnostics.some((d) => d.code === 'JSC0731'), JSON.stringify(p.diagnostics));
    // Restored on disk: the next build is clean again (the lock is read each build, never cached).
    writeFileSync(lockFile, good);
    lsp.conn.sendNotification('workspace/didChangeWatchedFiles', { changes: [{ uri: uriOf(lockFile), type: 1 }] });
    const clean = await lsp.request('joust/checkBuild', { uri });
    assert.equal(clean.errors, 0, JSON.stringify(clean));
  } finally {
    await lsp.stop();
  }
});

test('#121 F4: --accept-lock rewrites a lock that cannot be read, even when nothing needs pinning', () => {
  const root = mkdtempSync(join(tmpdir(), 'joust-lock-f4-'));
  writeFileSync(join(root, 'joust.lock'), '{ not json');
  assert.match(lockGate({ generated: [], libraries: [], root, log: quiet })[0], /JSC0732.*cannot be read/);
  assert.deepEqual(lockGate({ generated: [], libraries: [], root, acceptLock: true, log: quiet }), []);
  assert.deepEqual(JSON.parse(readFileSync(join(root, 'joust.lock'), 'utf8')), { lock: 1, libraries: {} });
  assert.deepEqual(lockGate({ generated: [], libraries: [], root, log: quiet }), [], 'the advice works: the next build passes');
});

/** A project with no external library: a contract with no import, its ABI accepted, no joust.lock. */
async function plainProject() {
  const root = project();
  writeFileSync(join(root, 'contracts', 'app', 'Shop.jsc'), PLAIN);
  rmSync(join(root, 'node_modules', '@acme'), { recursive: true, force: true });
  await build({ root, acceptAbi: true, log: quiet });
  return root;
}
const PLAIN = SHOP.replace('import { fee } from "@acme/fees/fees.jsc"\n', '').replace(' + fee(price, 100)', '');

async function pendingThen(lsp, uri, change) {
  const pending = lsp.request('joust/checkBuild', { uri });
  await new Promise((r) => setTimeout(r, 50));
  change();
  return pending;
}

test('#121 C1: a lock that is a link, replaced while its build runs, ends the build (the logical path is the input)', { skip, timeout: 300_000 }, async () => {
  const root = project();
  const lockFile = join(root, 'joust.lock');
  await build({ root, acceptAbi: true, acceptLock: true, log: quiet });
  const good = readFileSync(lockFile, 'utf8');
  writeFileSync(join(root, 'pins.json'), good);
  rmSync(lockFile);
  symlinkSync('pins.json', lockFile);
  const lsp = await startLsp({ folders: [root], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: true }, hostOptions: {} });
  try {
    const uri = uriOf(join(root, 'contracts', 'app', 'Shop.jsc'));
    lsp.open(uri, SHOP);
    await lsp.until(() => lsp.statuses.find((s) => s.uri === uri && s.mode === 'project-matched'), 60000, 'project-matched');
    await lsp.diagnostics(uri, 1, 60000);
    assert.equal((await lsp.request('joust/checkBuild', { uri })).errors, 0, 'the linked lock pins the library');
    const r = await pendingThen(lsp, uri, () => {
      rmSync(lockFile);
      writeFileSync(lockFile, good.replace(/"[0-9a-f]{64}"/, `"${'ab'.repeat(32)}"`));
      lsp.conn.sendNotification('workspace/didChangeWatchedFiles', { changes: [{ uri: uriOf(lockFile), type: 2 }] });
    });
    assert.match(r.error ?? '', /changed while it built/, JSON.stringify(r));
    assert.equal((await lsp.request('joust/checkBuild', { uri })).ok, false, 'the next build reads the new lock');
  } finally {
    await lsp.stop();
  }
});

test('#121 C3: a missing lock that becomes unreadable while its build runs ends the build', { skip, timeout: 300_000 }, async () => {
  const root = await plainProject();
  const lockFile = join(root, 'joust.lock');
  rmSync(lockFile, { force: true });
  const lsp = await startLsp({ folders: [root], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: true }, hostOptions: {} });
  try {
    const uri = uriOf(join(root, 'contracts', 'app', 'Shop.jsc'));
    lsp.open(uri, PLAIN);
    await lsp.until(() => lsp.statuses.find((s) => s.uri === uri && s.mode === 'project-matched'), 60000, 'project-matched');
    await lsp.diagnostics(uri, 1, 60000);
    assert.equal((await lsp.request('joust/checkBuild', { uri })).errors, 0, 'no library, no lock: clean');
    const r = await pendingThen(lsp, uri, () => {
      mkdirSync(lockFile);
      lsp.conn.sendNotification('workspace/didChangeWatchedFiles', { changes: [{ uri: uriOf(lockFile), type: 1 }] });
    });
    assert.match(r.error ?? '', /changed while it built/, JSON.stringify(r));
    const next = await lsp.request('joust/checkBuild', { uri });
    assert.equal(next.ok, false, JSON.stringify(next));
  } finally {
    await lsp.stop();
  }
});

test('#121 F5: a java: library (an npm package\'s joust-lib/*.java): the editor\'s J12 decision is joust build\'s, at the java: import', { skip, timeout: 600_000 }, async () => {
  const REPO = join(new URL('../../../..', import.meta.url).pathname);
  const ALLOW = readFileSync(join(REPO, 'examples/compliance/src/main/java/com/example/compliance/AllowList.java'), 'utf8');
  const GUARDED = readFileSync(join(REPO, 'examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/Guarded.jsc'), 'utf8').replace('package org.julclang.state.examples.dsl', 'package app');
  const root = project();
  rmSync(join(root, 'contracts', 'app', 'Shop.jsc'));
  rmSync(join(root, 'node_modules', '@acme'), { recursive: true, force: true });
  const lib = join(root, 'node_modules', '@example', 'compliance', 'joust-lib', 'com', 'example', 'compliance');
  mkdirSync(lib, { recursive: true });
  writeFileSync(join(root, 'node_modules', '@example', 'compliance', 'package.json'), JSON.stringify({ name: '@example/compliance', version: '1.0.0' }));
  writeFileSync(join(lib, 'AllowList.java'), ALLOW);
  const file = join(root, 'contracts', 'app', 'Guarded.jsc');
  writeFileSync(file, GUARDED);
  const lockFile = join(root, 'joust.lock');
  await build({ root, acceptAbi: true, log: quiet });
  await build({ root, acceptLock: true, log: quiet });
  const good = readFileSync(lockFile, 'utf8');
  const name = 'java:com.example.compliance.AllowList';
  const sha = JSON.parse(good).libraries[name];
  assert.match(sha ?? '', /^[0-9a-f]{64}$/, good);
  const importLine = GUARDED.split('\n').findIndex((l) => l.startsWith('import') && l.includes(`"${name}"`));
  const lsp = await startLsp({ folders: [root], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: true }, hostOptions: {} });
  try {
    const uri = uriOf(file);
    lsp.open(uri, GUARDED);
    await lsp.until(() => lsp.statuses.find((s) => s.uri === uri && s.mode === 'project-matched'), 120000, 'project-matched');
    let version = 1;
    const editorOf = async () => {
      lsp.change(uri, ++version, GUARDED + ' '.repeat(version));
      await lsp.diagnostics(uri, version, 120000);
      const r = await lsp.request('joust/checkBuild', { uri });
      assert.ok(!r.error, JSON.stringify(r));
      const p = [...lsp.published].reverse().find((x) => x.uri === uri && x.version === version);
      return (p?.diagnostics ?? []).filter((d) => d.code === 'JSC0731' || d.code === 'JSC0732');
    };
    for (const [what, arrange, codes] of [
      ['not pinned', () => rmSync(lockFile, { force: true }), ['JSC0731']],
      ['pinned with its hash', () => writeFileSync(lockFile, good), []],
      ['pinned with another hash', () => writeFileSync(lockFile, good.replace(sha, 'cd'.repeat(32))), ['JSC0732']],
    ]) {
      arrange();
      const c = await cli(root);
      const e = await editorOf();
      assert.deepEqual(c.codes, codes, `${what}: joust build ${JSON.stringify(c.lines)}`);
      assert.deepEqual(e.map((d) => d.code), c.codes, what);
      assert.deepEqual(e.map((d) => d.message), c.lines, `${what}: the same name, hashes and words`);
      for (const d of e) {
        assert.equal(d.range.start.line, importLine, `${what}: at the java: import`);
        assert.ok(d.message.includes(name), d.message);
      }
    }
  } finally {
    await lsp.stop();
  }
});
