// #115 round r3 over the protocol, with the real engine: each a reproduction of its finding that fails without its fix.
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

test('F2: prepareRename on a use in the importer names the library\'s declaration', { skip, timeout: 120_000 }, async () => {
  const dir = tmp();
  const { fees, user } = crossProject(dir);
  const lsp = await trusted(dir);
  try {
    const u = uriOf(user);
    lsp.open(u, USER);
    await modelOf(lsp, u, 1);
    const prep = await lsp.request('joust/prepareRename', { uri: u, position: at(USER, 'fee(amount', 1) });
    assert.deepEqual({ name: prep.name, kind: prep.kind, in: prep.in }, { name: 'fee', kind: 'fn', in: uriOf(fees) }, JSON.stringify(prep));
    const ty = await lsp.request('joust/prepareRename', { uri: u, position: at(USER, 'Fee }', 0) });
    assert.equal(ty.name, 'Fee', JSON.stringify(ty));
  } finally {
    await lsp.stop();
  }
});

test('C6: a clean importer of another name of the library does not block the rename (open and closed)', { skip, timeout: 240_000 }, async () => {
  for (const open of [true, false]) {
    const dir = tmp();
    const { fees, user } = crossProject(dir);
    const OTHER = USER.replace('import { fee, Fee }', 'import { Fee }').replace('fee(amount, f)', 'amount * f.bps');
    writeFileSync(user, OTHER);
    const lsp = await trusted(dir);
    try {
      const l = uriOf(fees);
      lsp.open(l, FEES);
      await modelOf(lsp, l, 1);
      if (open) {
        lsp.open(uriOf(user), OTHER);
        await modelOf(lsp, uriOf(user), 1);
        assert.deepEqual((await lsp.diagnostics(uriOf(user), 1)).diagnostics.filter((d) => d.severity === 1), []);
      }
      const r = await lsp.request('joust/rename', { uri: l, position: at(FEES, 'fee(amount'), newName: 'charge', confirm: true });
      assert.equal(r.applied, true, `${open ? 'open' : 'closed'}: ${JSON.stringify(r)}`);
      assert.deepEqual(lsp.applied.at(-1).edit.documentChanges.map((c) => c.textDocument.uri), [l]);
    } finally {
      await lsp.stop();
    }
  }
});

test('C7: an importer\'s cached error goes when its other dependency is fixed on disk', { skip, timeout: 240_000 }, async () => {
  const dir = tmp();
  const { fees, user } = crossProject(dir);
  const other = join(dir, 'contracts', 'app', 'other.jsc');
  writeFileSync(other, 'jsc 1\nlibrary app\n\nfn wrong(x: Int): Int = x\n');
  const WITH = USER.replace('import { fee, Fee } from "./fees.jsc"', 'import { fee, Fee } from "./fees.jsc"\nimport { bonus } from "./other.jsc"').replace('fee(amount, f)', 'fee(amount, f) + bonus(1)');
  writeFileSync(user, WITH);
  const lsp = await trusted(dir);
  try {
    const l = uriOf(fees);
    lsp.open(l, FEES);
    await modelOf(lsp, l, 1);
    const first = await lsp.request('joust/rename', { uri: l, position: at(FEES, 'fee(amount'), newName: 'charge', confirm: true });
    assert.match(first.error ?? '', /User\.jsc, which imports 'fee', has errors/, JSON.stringify(first));
    writeFileSync(other, 'jsc 1\nlibrary app\n\nfn bonus(x: Int): Int = x\n');
    lsp.conn.sendNotification('workspace/didChangeWatchedFiles', { changes: [{ uri: uriOf(other), type: 2 }] });
    const second = await lsp.request('joust/rename', { uri: l, position: at(FEES, 'fee(amount'), newName: 'charge', confirm: true });
    assert.deepEqual(second.needsOpen, [uriOf(user)], JSON.stringify(second));
  } finally {
    await lsp.stop();
  }
});

test('C8: Restart analysis reads the packages\' libraries again', { skip, timeout: 300_000 }, async () => {
  const dir = tmp();
  const { lib, guarded } = javaProject(dir);
  const lsp = await trusted(dir);
  try {
    const uri = uriOf(guarded);
    lsp.open(uri, GUARDED);
    await modelOf(lsp, uri, 1);
    assert.deepEqual((await lsp.diagnostics(uri, 1)).diagnostics.filter((d) => d.severity === 1), []);
    const n = lsp.published.length;
    writeFileSync(lib, ALLOW.replace(/\bisAllowed\b/g, 'isPermitted'));
    await lsp.request('joust/restartAnalysis');
    const p = await lsp.until(() => lsp.published.slice(n).find((x) => x.uri === uri && x.diagnostics.some((d) => d.severity === 1)), 120000, 'Guarded against the new library');
    assert.ok(p.diagnostics.some((d) => /isAllowed/.test(d.message)), JSON.stringify(p.diagnostics));
  } finally {
    await lsp.stop();
  }
});

test('C9: a Java library that appears on disk refreshes its importer', { skip, timeout: 300_000 }, async () => {
  const dir = tmp();
  const { lib, guarded } = javaProject(dir, { withSource: false });
  const lsp = await trusted(dir);
  try {
    const uri = uriOf(guarded);
    lsp.open(uri, GUARDED);
    await modelOf(lsp, uri, 1);
    const n = lsp.published.length;
    writeFileSync(lib, ALLOW);
    lsp.conn.sendNotification('workspace/didChangeWatchedFiles', { changes: [{ uri: uriOf(lib), type: 1 }] });
    const p = await lsp.until(() => lsp.published.slice(n).find((x) => x.uri === uri && !x.diagnostics.some((d) => d.severity === 1)), 120000, 'Guarded with the library');
    assert.ok(p);
  } finally {
    await lsp.stop();
  }
});

test('C10: a package\'s library opened directly is never edited', { skip, timeout: 120_000 }, async () => {
  const dir = tmp();
  crossProject(dir);
  const pkg = join(dir, 'node_modules', '@acme', 'fees', 'joust-lib');
  mkdirSync(pkg, { recursive: true });
  writeFileSync(join(dir, 'node_modules', '@acme', 'fees', 'package.json'), JSON.stringify({ name: '@acme/fees', version: '1.0.0' }));
  writeFileSync(join(pkg, 'fees.jsc'), FEES);
  const lsp = await trusted(dir);
  try {
    const p = uriOf(join(pkg, 'fees.jsc'));
    lsp.open(p, FEES);
    await modelOf(lsp, p, 1);
    const r = await lsp.request('joust/rename', { uri: p, position: at(FEES, 'amount: Int'), newName: 'value', confirm: true });
    assert.equal(r.applied, false, JSON.stringify(r));
    assert.match(r.error, /a package is never edited/);
    const prep = await lsp.request('joust/prepareRename', { uri: p, position: at(FEES, 'amount: Int') });
    assert.match(prep.error ?? '', /a package is never edited/);
    assert.equal(lsp.applied.length, 0);
  } finally {
    await lsp.stop();
  }
});

test('C12: references stop at the location budget, the declaring file\'s own included', { skip, timeout: 240_000 }, async () => {
  const dir = tmp();
  const { fees } = crossProject(dir);
  const many = `${FEES}\n${Array.from({ length: 10_005 }, (_, i) => `fn g${i}(x: Int, f: Fee): Int = fee(x, f)\n`).join('')}`;
  writeFileSync(fees, many);
  const lsp = await trusted(dir);
  try {
    const l = uriOf(fees);
    lsp.open(l, many);
    await modelOf(lsp, l, 1);
    const refs = await lsp.request('textDocument/references', { textDocument: { uri: l }, position: at(many, 'fee(amount'), context: { includeDeclaration: true } });
    assert.ok(refs.length <= 10_000, `${refs.length} locations`);
    const r = await lsp.request('joust/rename', { uri: l, position: at(many, 'fee(amount'), newName: 'charge', confirm: true });
    assert.equal(r.applied, false, JSON.stringify(r).slice(0, 300));
  } finally {
    await lsp.stop();
  }
});

test('C13, F10: a record opens at javac\'s place (a comment naming it before); Java buffers come by notification', { skip, timeout: 300_000 }, async () => {
  const dir = tmp();
  const { lib, guarded } = javaProject(dir);
  const COMMENTED = ALLOW.replace(/^(\s*)(public )?record Entry/m, '$1// record Entry is documented here, not declared.\n$1$2record Entry');
  writeFileSync(lib, COMMENTED);
  const lsp = await trusted(dir);
  try {
    const uri = uriOf(guarded);
    lsp.open(uri, GUARDED);
    await modelOf(lsp, uri, 1);
    const def = await lsp.request('textDocument/definition', { textDocument: { uri }, position: at(GUARDED, 'Entry, isAllowed', 1) });
    const text = (await lsp.request('joust/libraryContent', { id: def.uri.split('/')[1] })).text;
    const line = text.split('\n')[def.range.start.line];
    assert.ok(!line.includes('//'), `not the comment: ${line}`);
    assert.equal(line.slice(def.range.start.character, def.range.end.character), 'Entry');
    // F10: the extension's notification carries a Java buffer: Guarded is analysed against it.
    const n = lsp.published.length;
    lsp.conn.sendNotification('joust/javaInput', { uri: uriOf(lib), text: COMMENTED.replace(/\bisAllowed\b/g, 'isAllowedChanged') });
    const p = await lsp.until(() => lsp.published.slice(n).find((x) => x.uri === uri && x.diagnostics.some((d) => d.severity === 1)), 120000, 'Guarded against the Java buffer');
    assert.ok(p.diagnostics.some((d) => /isAllowed/.test(d.message)));
  } finally {
    await lsp.stop();
  }
});
