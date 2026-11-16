// #119 (Codex C14, C15, C17 and design F14 on #115 r3) over the protocol, with the real engine: each a reproduction that fails without its fix.
// Design F2 (prepareRename from a use), Codex C6 (a clean importer of other names), C7 (a cached importer error after
// its other dependency changes), C8 (Restart re-reads the packages' libraries), C9 (a Java library that appears), C10
// (a package's library is never edited), C12 (the location budget counts the file's own), C13 (a record by javac's
// place), F10 (Java buffers by notification).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { TextDocument } from 'vscode-languageserver-textdocument';
import { HAS_ENGINE, startLsp, tmp, uriOf, VSCODE_CAPS } from './client.js';
import { FEES, USER, crossProject } from './m5c-fixtures.js';

const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine';
const at = (text, needle, delta = 0) => {
  const off = text.indexOf(needle);
  assert.ok(off >= 0, needle);
  return TextDocument.create('x', 'jsc', 1, text).positionAt(off + delta);
};
const modelOf = (lsp, uri, v) => lsp.until(() => lsp.statuses.find((s) => s.uri === uri && s.modelVersion >= v), 60000, `the model of v${v}`);
const trusted = (dir, hostOptions = {}) => startLsp({ folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: true }, hostOptions });
const REPO = join(new URL('../../../..', import.meta.url).pathname);
const ALLOW = readFileSync(join(REPO, 'examples/compliance/src/main/java/com/example/compliance/AllowList.java'), 'utf8');
const GUARDED = readFileSync(join(REPO, 'examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/Guarded.jsc'), 'utf8').replace('package org.julclang.state.examples.dsl', 'package app');

function javaProject(dir, { withSource = true } = {}) {
  crossProject(dir);
  const lib = join(dir, 'node_modules', '@example', 'compliance', 'joust-lib', 'com', 'example', 'compliance');
  mkdirSync(lib, { recursive: true });
  writeFileSync(join(dir, 'node_modules', '@example', 'compliance', 'package.json'), JSON.stringify({ name: '@example/compliance', version: '1.0.0' }));
  if (withSource) writeFileSync(join(lib, 'AllowList.java'), ALLOW);
  writeFileSync(join(dir, 'contracts', 'app', 'Guarded.jsc'), GUARDED);
  return { lib: join(lib, 'AllowList.java'), guarded: join(dir, 'contracts', 'app', 'Guarded.jsc') };
}


test('C14: closing a dependency\'s buffer drops the importer\'s cached error', { skip, timeout: 240_000 }, async () => {
  const dir = tmp();
  const { fees, user } = crossProject(dir);
  const other = join(dir, 'contracts', 'app', 'other.jsc');
  writeFileSync(other, 'jsc 1\nlibrary app\n\nfn bonus(x: Int): Int = x\n');
  const WITH = USER.replace('import { fee, Fee } from "./fees.jsc"', 'import { fee, Fee } from "./fees.jsc"\nimport { bonus } from "./other.jsc"').replace('fee(amount, f)', 'fee(amount, f) + bonus(1)');
  writeFileSync(user, WITH);
  const lsp = await trusted(dir);
  try {
    const l = uriOf(fees);
    lsp.open(l, FEES);
    lsp.open(uriOf(other), 'jsc 1\nlibrary app\n\nfn wrong(x: Int): Int = x\n');
    await modelOf(lsp, l, 1);
    await modelOf(lsp, uriOf(other), 1);
    const first = await lsp.request('joust/rename', { uri: l, position: at(FEES, 'fee(amount'), newName: 'charge', confirm: true });
    assert.match(first.error ?? '', /User\.jsc, which imports 'fee', has errors/, JSON.stringify(first));
    lsp.close(uriOf(other));   // the unsaved buffer is discarded: other.jsc on disk exports bonus
    await new Promise((res) => setTimeout(res, 300));
    const second = await lsp.request('joust/rename', { uri: l, position: at(FEES, 'fee(amount'), newName: 'charge', confirm: true });
    assert.deepEqual(second.needsOpen, [uriOf(user)], JSON.stringify(second));
  } finally {
    await lsp.stop();
  }
});

test('C15: a Java library file added with no watcher event is found by the next analysis', { skip, timeout: 300_000 }, async () => {
  const dir = tmp();
  const { lib, guarded } = javaProject(dir, { withSource: false });
  const lsp = await trusted(dir);
  try {
    const uri = uriOf(guarded);
    lsp.open(uri, GUARDED);
    await modelOf(lsp, uri, 1);
    assert.ok((await lsp.diagnostics(uri, 1)).diagnostics.some((d) => d.code === 'JSC0720'));
    writeFileSync(lib, ALLOW);   // no notification
    lsp.change(uri, 2, `${GUARDED}\n`);
    const p = await lsp.until(() => lsp.published.find((x) => x.uri === uri && x.version === 2), 120000, 'v2');
    assert.deepEqual(p.diagnostics.filter((d) => d.severity === 1), [], JSON.stringify(p.diagnostics));
  } finally {
    await lsp.stop();
  }
});

test('C17: a Java input notification for a missing library refreshes its importer', { skip, timeout: 300_000 }, async () => {
  const dir = tmp();
  const { lib, guarded } = javaProject(dir, { withSource: false });
  const lsp = await trusted(dir);
  try {
    const uri = uriOf(guarded);
    lsp.open(uri, GUARDED);
    await modelOf(lsp, uri, 1);
    const n = lsp.published.length;
    writeFileSync(lib, ALLOW);
    lsp.conn.sendNotification('joust/javaInput', { uri: uriOf(lib), text: ALLOW });
    const p = await lsp.until(() => lsp.published.slice(n).find((x) => x.uri === uri && !x.diagnostics.some((d) => d.severity === 1)), 120000, 'Guarded refreshed');
    assert.ok(p);
  } finally {
    await lsp.stop();
  }
});

test('F14: prepareRename gives no ABI flag (only rename knows the importers that decide it)', { skip, timeout: 120_000 }, async () => {
  const dir = tmp();
  const { fees, user } = crossProject(dir);
  const lsp = await trusted(dir);
  try {
    lsp.open(uriOf(fees), FEES);
    lsp.open(uriOf(user), USER);
    await modelOf(lsp, uriOf(fees), 1);
    await modelOf(lsp, uriOf(user), 1);
    const p1 = await lsp.request('joust/prepareRename', { uri: uriOf(fees), position: at(FEES, 'Fee =') });
    const p2 = await lsp.request('joust/prepareRename', { uri: uriOf(user), position: at(USER, 'Fee {', 1) });
    for (const p of [p1, p2]) {
      assert.equal(p.name, 'Fee', JSON.stringify(p));
      assert.equal('abi' in p, false, JSON.stringify(p));
    }
    const r = await lsp.request('joust/rename', { uri: uriOf(fees), position: at(FEES, 'Fee ='), newName: 'Charge' });
    assert.equal(r.needsConfirmation, true, JSON.stringify(r));
  } finally {
    await lsp.stop();
  }
});
