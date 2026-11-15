// ADR-0021 M5c (r8) over the protocol, with the real engine as a trusted project's own compiler: definition and
// references across .jsc files (a library's fn and type, `import contract`), project-wide rename (L6 across files).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { TextDocument } from 'vscode-languageserver-textdocument';
import { ENGINE, HAS_ENGINE, startLsp, tmp, uriOf, VSCODE_CAPS } from './client.js';

const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine';
const at = (text, needle, delta = 0) => {
  const off = text.indexOf(needle);
  assert.ok(off >= 0, needle);
  return TextDocument.create('x', 'jsc', 1, text).positionAt(off + delta);
};
const modelOf = (lsp, uri, v) => lsp.until(() => lsp.statuses.find((s) => s.uri === uri && s.modelVersion >= v), 30000, `the model of v${v}`);

export const FEES = `jsc 1
library app

type Fee = { bps: Int }

fn fee(amount: Int, f: Fee): Int = amount * f.bps / 10000
`;

export const USER = `jsc 1
package app

import { fee, Fee } from "./fees.jsc"

contract User {
  state { paid: Int }

  init() {
    return { paid: 0 }
  }

  action pay(amount: Int) {
    const f = Fee { bps: 30 }
    return { paid: paid + fee(amount, f) }
  }
}
`;

/** A trusted npm project with the real engine as its compiler: fees.jsc and User.jsc under contracts/app. */
export function crossProject(root) {
  mkdirSync(join(root, 'contracts', 'app'), { recursive: true });
  writeFileSync(join(root, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '0.0.0' } }));
  writeFileSync(join(root, 'joust.config.json'), '{ "contracts": "contracts" }');
  const pkg = join(root, 'node_modules', '@julclang', 'joust');
  mkdirSync(join(pkg, 'engine'), { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: '@julclang/joust', version: '9.9.9' }));
  for (const f of readdirSync(ENGINE).filter((x) => /^(joust-toolchain\.js(\.wasm)?|package\.json)$/.test(x))) copyFileSync(join(ENGINE, f), join(pkg, 'engine', f));
  writeFileSync(join(root, 'contracts', 'app', 'fees.jsc'), FEES);
  writeFileSync(join(root, 'contracts', 'app', 'User.jsc'), USER);
  return { fees: join(root, 'contracts', 'app', 'fees.jsc'), user: join(root, 'contracts', 'app', 'User.jsc') };
}

const trusted = (dir) => startLsp({ folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: true }, hostOptions: {} });

test('definition goes from an imported name to its declaration in the library; references come back from it', { skip, timeout: 120_000 }, async () => {
  const dir = tmp();
  const { fees, user } = crossProject(dir);
  const lsp = await trusted(dir);
  try {
    const u = uriOf(user);
    const l = uriOf(fees);
    lsp.open(u, USER);
    await modelOf(lsp, u, 1);
    const d = (await lsp.diagnostics(u, 1)).diagnostics;
    assert.deepEqual(d.filter((x) => x.severity === 1), [], JSON.stringify(d));
    // The use of fee in pay, and the type in its constructor: to fees.jsc.
    const def = await lsp.request('textDocument/definition', { textDocument: { uri: u }, position: at(USER, 'fee(amount', 1) });
    assert.equal(def?.uri, l, JSON.stringify(def));
    assert.deepEqual(def.range.start, at(FEES, 'fee(amount'));
    const ty = await lsp.request('textDocument/definition', { textDocument: { uri: u }, position: at(USER, 'Fee { bps', 1) });
    assert.equal(ty?.uri, l, JSON.stringify(ty));
    assert.deepEqual(ty.range.start, at(FEES, 'Fee = '));
    // References of fee from the importer (fees.jsc is not open): its declaration, the import's name and the use.
    const refs = await lsp.request('textDocument/references', { textDocument: { uri: u }, position: at(USER, 'fee(amount', 1), context: { includeDeclaration: true } });
    const where = refs.map((r) => `${r.uri === l ? 'fees' : r.uri === u ? 'User' : r.uri}:${r.range.start.line}:${r.range.start.character}`).sort();
    const p = (t, s, d = 0) => `${t === FEES ? 'fees' : 'User'}:${at(t, s, d).line}:${at(t, s, d).character}`;
    assert.deepEqual(where, [p(FEES, 'fee(amount'), p(USER, 'fee, Fee'), p(USER, 'fee(amount')].sort(), JSON.stringify(refs));
    // And from the library itself, once open.
    lsp.open(l, FEES);
    await modelOf(lsp, l, 1);
    const back = await lsp.request('textDocument/references', { textDocument: { uri: l }, position: at(FEES, 'Fee = '), context: { includeDeclaration: false } });
    const backWhere = back.map((r) => `${r.uri === l ? 'fees' : 'User'}:${r.range.start.line}:${r.range.start.character}`).sort();
    assert.deepEqual(backWhere, [p(FEES, 'Fee): Int'), p(USER, 'Fee } from'), p(USER, 'Fee { bps')].sort(), JSON.stringify(back));
  } finally {
    await lsp.stop();
  }
});

test('rename across files: a library fn and type, in the library and its importer; closed files named first (L6)', { skip, timeout: 180_000 }, async () => {
  const dir = tmp();
  const { fees, user } = crossProject(dir);
  const lsp = await trusted(dir);
  try {
    const u = uriOf(user);
    const l = uriOf(fees);
    lsp.open(l, FEES);
    await modelOf(lsp, l, 1);
    // User.jsc is not open: the rename names it.
    const closed = await lsp.request('joust/rename', { uri: l, position: at(FEES, 'fee(amount'), newName: 'charge', confirm: true });
    assert.equal(closed.applied, false, JSON.stringify(closed));
    assert.deepEqual(closed.needsOpen, [u]);
    lsp.open(u, USER);
    await modelOf(lsp, u, 1);
    const r = await lsp.request('joust/rename', { uri: l, position: at(FEES, 'fee(amount'), newName: 'charge', confirm: true });
    assert.equal(r.applied, true, JSON.stringify(r));
    const change = lsp.applied.at(-1);
    const by = new Map(change.edit.documentChanges.map((c) => [c.textDocument.uri, c]));
    assert.deepEqual([...by.keys()].sort(), [l, u].sort());
    assert.equal(by.get(l).textDocument.version, 1);
    assert.equal(by.get(u).textDocument.version, 1);
    assert.deepEqual(change.joustExpected.map((x) => x.uri).sort(), [l, u].sort());
    const feesAfter = TextDocument.applyEdits(TextDocument.create(l, 'jsc', 1, FEES), by.get(l).edits);
    const userAfter = TextDocument.applyEdits(TextDocument.create(u, 'jsc', 1, USER), by.get(u).edits);
    assert.match(feesAfter, /fn charge\(amount/);
    assert.match(userAfter, /import \{ charge, Fee \}/);
    assert.match(userAfter, /charge\(amount, f\)/);
    assert.ok(!/\bfee\b/.test(userAfter), userAfter);
    // A type: the importer's datum could hold it, so it warns first (D7), then renames both files.
    lsp.change(l, 2, feesAfter);
    lsp.change(u, 2, userAfter);
    await modelOf(lsp, l, 2);
    await modelOf(lsp, u, 2);
    const w = await lsp.request('joust/rename', { uri: l, position: at(feesAfter, 'Fee = '), newName: 'Charge' });
    assert.equal(w.needsConfirmation, true, JSON.stringify(w));
    const t = await lsp.request('joust/rename', { uri: l, position: at(feesAfter, 'Fee = '), newName: 'Charge', confirm: true });
    assert.equal(t.applied, true, JSON.stringify(t));
    const tc = new Map(lsp.applied.at(-1).edit.documentChanges.map((c) => [c.textDocument.uri, c]));
    const user3 = TextDocument.applyEdits(TextDocument.create(u, 'jsc', 2, userAfter), tc.get(u).edits);
    assert.match(user3, /import \{ charge, Charge \}/);
    assert.match(user3, /Charge \{ bps: 30 \}/);
    // A name the importer already declares is refused (the renamed importer would not check).
    lsp.change(u, 3, user3);
    lsp.change(l, 3, TextDocument.applyEdits(TextDocument.create(l, 'jsc', 2, feesAfter), tc.get(l).edits));
    await modelOf(lsp, u, 3);
    await modelOf(lsp, l, 3);
    const lib3 = TextDocument.applyEdits(TextDocument.create(l, 'jsc', 2, feesAfter), tc.get(l).edits);
    const no = await lsp.request('joust/rename', { uri: l, position: at(lib3, 'charge(amount'), newName: 'paid', confirm: true });
    assert.equal(no.applied, false, JSON.stringify(no));
    assert.match(no.error, /User\.jsc/);
  } finally {
    await lsp.stop();
  }
});

const REPO = join(new URL('../../../..', import.meta.url).pathname);
const GUARDED = (await import('node:fs')).readFileSync(join(REPO, 'examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/Guarded.jsc'), 'utf8');
const ALLOW = (await import('node:fs')).readFileSync(join(REPO, 'examples/compliance/src/main/java/com/example/compliance/AllowList.java'), 'utf8');

test('java: libraries: described once (cached by their sources\' SHA-256), the import resolves, definition opens the library source read-only', { skip, timeout: 300_000 }, async () => {
  const dir = tmp();
  crossProject(dir);
  const lib = join(dir, 'node_modules', '@example', 'compliance', 'joust-lib', 'com', 'example', 'compliance');
  mkdirSync(lib, { recursive: true });
  writeFileSync(join(dir, 'node_modules', '@example', 'compliance', 'package.json'), JSON.stringify({ name: '@example/compliance', version: '1.0.0' }));
  writeFileSync(join(lib, 'AllowList.java'), ALLOW);
  const file = join(dir, 'contracts', 'app', 'Guarded.jsc');
  const text = GUARDED.replace('package org.julclang.state.examples.dsl', 'package app');
  writeFileSync(file, text);
  const storage = tmp('joust-lsp-store-');
  const lsp = await startLsp({ folders: [dir], storage, capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: true }, hostOptions: {} });
  try {
    const uri = uriOf(file);
    lsp.open(uri, text);
    await modelOf(lsp, uri, 1);
    const status = lsp.statuses.filter((x) => x.uri === uri && x.mode).at(-1);
    assert.equal(status.mode, 'project-matched', JSON.stringify(status));
    const d = (await lsp.diagnostics(uri, 1)).diagnostics;
    assert.deepEqual(d.filter((x) => x.severity === 1), [], JSON.stringify(d));
    // The cache: one file in the server's private storage, keyed by the engine and the sources' SHA-256.
    const cached = readdirSync(join(storage, 'java-signatures'));
    assert.equal(cached.length, 1, JSON.stringify(cached));
    // The M5c row's entry gate: javac attribution's cost in the engine (the server logs it once per set of sources).
    console.log(`[java] ${lsp.logs.map((l) => /described the project's Java libraries in \d+ ms/.exec(l)?.[0]).find(Boolean) ?? 'not logged'}`);
    // Definition of isAllowed: the library's source, read-only, at the fn's name.
    const def = await lsp.request('textDocument/definition', { textDocument: { uri }, position: at(text, 'isAllowed(list', 1) });
    assert.match(def?.uri ?? '', /^joust-lib:\/[A-Za-z0-9_-]+\/com\/example\/compliance\/AllowList\.java$/, JSON.stringify(def));
    const id = def.uri.split('/')[1];
    const content = await lsp.request('joust/libraryContent', { id });
    assert.equal(content.text, ALLOW);
    const line = ALLOW.split('\n')[def.range.start.line];
    assert.equal(line.slice(def.range.start.character, def.range.end.character), 'isAllowed', line);
    // A record: Entry.
    const rec = await lsp.request('textDocument/definition', { textDocument: { uri }, position: at(text, 'Entry, isAllowed', 1) });
    assert.match(rec?.uri ?? '', /AllowList\.java$/, JSON.stringify(rec));
    assert.equal((await lsp.request('joust/libraryContent', { id: 'nope' })).error.includes('gone'), true);
  } finally {
    await lsp.stop();
  }
  // A new server, the same storage: the descriptions come from the cache (no attribution logged).
  const again = await startLsp({ folders: [dir], storage, capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: true }, hostOptions: {} });
  try {
    const uri = uriOf(file);
    again.open(uri, text);
    await modelOf(again, uri, 1);
    const d = (await again.diagnostics(uri, 1)).diagnostics;
    assert.deepEqual(d.filter((x) => x.severity === 1), [], JSON.stringify(d));
    assert.ok(!again.logs.some((l) => /described the project's Java libraries/.test(l)), 'from the cache');
  } finally {
    await again.stop();
  }
});
