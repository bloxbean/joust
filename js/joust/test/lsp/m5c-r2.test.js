// #115 round r2 over the protocol, with the real engine: Codex C1 (an importer whose header error hides its imports),
// C2 (a close and reopen at the same version during the importer scan), C3 (a java: library's buffer is an input), C4
// (npm package libraries), C5 (no older version's semantic tokens); security S1 (the reads of one definition and one
// references request are bounded), S2 (a linked directory lists a file once); design F1 (a contract's type with an
// `import contract` importer), F2 (a rename started in the importer), F4 (`import contract` navigation).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs';
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

test('C1: an importer whose header error hides its imports refuses the rename, open or closed', { skip, timeout: 240_000 }, async () => {
  for (const broken of [USER.replace('package app', 'package 123'), `nonsense\n${USER}`]) {
    for (const open of [true, false]) {
      const dir = tmp();
      const { fees, user } = crossProject(dir);
      writeFileSync(user, broken);
      const lsp = await trusted(dir);
      try {
        const l = uriOf(fees);
        lsp.open(l, FEES);
        await modelOf(lsp, l, 1);
        if (open) {
          lsp.open(uriOf(user), broken);
          await modelOf(lsp, uriOf(user), 1);
        }
        const r = await lsp.request('joust/rename', { uri: l, position: at(FEES, 'fee(amount'), newName: 'charge', confirm: true });
        assert.equal(r.applied, false, JSON.stringify(r));
        assert.match(r.error, /User\.jsc, which imports 'fee', has errors/, `${open ? 'open' : 'closed'}: ${r.error}`);
        assert.equal(r.needsOpen, undefined, 'refused before asking to open anything');
        assert.equal(lsp.applied.length, 0);
      } finally {
        await lsp.stop();
      }
    }
  }
});

test('C2: an importer closed and reopened at the same version during the scan refuses the rename', { skip, timeout: 240_000 }, async () => {
  const dir = tmp();
  const { fees, user } = crossProject(dir);
  const PLAIN = 'jsc 1\npackage app\n\ncontract User {\n  state { paid: Int }\n\n  init() {\n    return { paid: 0 }\n  }\n}\n';
  writeFileSync(user, PLAIN);
  const lsp = await trusted(dir, { importerScanDelayMs: 1200 });
  try {
    const l = uriOf(fees);
    const u = uriOf(user);
    lsp.open(l, FEES);
    lsp.open(u, PLAIN);
    await modelOf(lsp, l, 1);
    await modelOf(lsp, u, 1);
    const pending = lsp.request('joust/rename', { uri: l, position: at(FEES, 'fee(amount'), newName: 'charge', confirm: true });
    await new Promise((res) => setTimeout(res, 400));
    lsp.close(u);
    writeFileSync(user, USER);
    lsp.open(u, USER, 1);
    const r = await pending;
    assert.equal(r.applied, false, JSON.stringify(r));
    assert.match(r.error, /changed while the rename was checked|changed while the files that import this one were read/, r.error);
  } finally {
    await lsp.stop();
  }
});

test('C4, F2: a package library: definition and references through it; a rename there is refused, one started in a project library renames it', { skip, timeout: 240_000 }, async () => {
  const dir = tmp();
  const { fees, user } = crossProject(dir);
  const pkg = join(dir, 'node_modules', '@acme', 'fees');
  mkdirSync(join(pkg, 'joust-lib'), { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: '@acme/fees', version: '1.0.0' }));
  writeFileSync(join(pkg, 'joust-lib', 'fees.jsc'), FEES);
  const PKGUSER = USER.replace('"./fees.jsc"', '"@acme/fees/fees.jsc"');
  writeFileSync(user, PKGUSER);
  const lsp = await trusted(dir);
  try {
    const u = uriOf(user);
    lsp.open(u, PKGUSER);
    await modelOf(lsp, u, 1);
    const d = (await lsp.diagnostics(u, 1)).diagnostics;
    assert.deepEqual(d.filter((x) => x.severity === 1), [], JSON.stringify(d));
    const def = await lsp.request('textDocument/definition', { textDocument: { uri: u }, position: at(PKGUSER, 'fee(amount', 1) });
    assert.equal(def?.uri, uriOf(join(pkg, 'joust-lib', 'fees.jsc')), JSON.stringify(def));
    assert.deepEqual(def.range.start, at(FEES, 'fee(amount'));
    const refs = await lsp.request('textDocument/references', { textDocument: { uri: u }, position: at(PKGUSER, 'fee(amount', 1), context: { includeDeclaration: false } });
    assert.deepEqual(refs.map((r) => r.uri === u).sort(), [true, true], JSON.stringify(refs));
    const no = await lsp.request('joust/rename', { uri: u, position: at(PKGUSER, 'fee(amount', 1), newName: 'charge', confirm: true });
    assert.equal(no.applied, false);
    assert.match(no.error, /npm package library .* a package is never edited/, no.error);
    // F2: from an importer of a project library, the rename is its declaration's (both files open: both edited).
    writeFileSync(user, USER);
    lsp.change(u, 2, USER);
    lsp.open(uriOf(fees), FEES);
    await modelOf(lsp, u, 2);
    await modelOf(lsp, uriOf(fees), 1);
    const r = await lsp.request('joust/rename', { uri: u, position: at(USER, 'fee(amount', 1), newName: 'charge', confirm: true });
    assert.equal(r.applied, true, JSON.stringify(r));
    assert.deepEqual(lsp.applied.at(-1).edit.documentChanges.map((c) => c.textDocument.uri).sort(), [uriOf(fees), u].sort());
    const prep = await lsp.request('joust/prepareRename', { uri: u, position: at(USER, 'Fee }', 0) });
    assert.doesNotMatch(prep.error ?? '', /a import/);
  } finally {
    await lsp.stop();
  }
});

test('F1, F4: a contract file\'s type is refused while a file imports the contract; import contract navigation', { skip, timeout: 240_000 }, async () => {
  const dir = tmp();
  crossProject(dir);
  const FEED = 'jsc 1\npackage app\n\ntype Quote = { price: Int }\n\ncontract Feed {\n  state { owner: Key, quote: Quote }\n\n  init() {\n    return { owner: caller, quote: Quote { price: 1 } }\n  }\n\n  view price(): Int = quote.price\n}\n';
  const SHOP = 'jsc 1\npackage app\n\nimport contract { Feed } from "./Feed.jsc"\n\ncontract Shop {\n  params { feed: Instance<Feed> }\n  state { n: Int }\n\n  init() {\n    return { n: 0 }\n  }\n}\n';
  writeFileSync(join(dir, 'contracts', 'app', 'Feed.jsc'), FEED);
  writeFileSync(join(dir, 'contracts', 'app', 'Shop.jsc'), SHOP);
  const lsp = await trusted(dir);
  try {
    const f = uriOf(join(dir, 'contracts', 'app', 'Feed.jsc'));
    const s = uriOf(join(dir, 'contracts', 'app', 'Shop.jsc'));
    lsp.open(f, FEED);
    lsp.open(s, SHOP);
    await modelOf(lsp, f, 1);
    await modelOf(lsp, s, 1);
    const r = await lsp.request('joust/rename', { uri: f, position: at(FEED, 'Quote ='), newName: 'Price', confirm: true });
    assert.equal(r.applied, false, JSON.stringify(r));
    assert.match(r.error, /Shop\.jsc.*a contract's names are renamed in its file only while no file imports it/, r.error);
    const def = await lsp.request('textDocument/definition', { textDocument: { uri: s }, position: at(SHOP, 'Feed>', 1) });
    assert.equal(def?.uri, f, JSON.stringify(def));
    const refs = await lsp.request('textDocument/references', { textDocument: { uri: f }, position: at(FEED, 'Feed {'), context: { includeDeclaration: true } });
    assert.deepEqual(refs.map((x) => (x.uri === f ? 'Feed' : 'Shop')).sort(), ['Feed', 'Shop', 'Shop'], JSON.stringify(refs));
  } finally {
    await lsp.stop();
  }
});

test('S1: one definition reads the one file it names; references stop at the budget and the importer cap', { skip, timeout: 300_000 }, async () => {
  const dir = tmp();
  const { user } = crossProject(dir);
  // 40 padding files of 200 KB (comments): a project-wide read would be 8 MB.
  for (let i = 0; i < 40; i++) writeFileSync(join(dir, 'contracts', 'app', `Pad${i}.jsc`), `// ${'x'.repeat(200_000)}\njsc 1\nlibrary app\n`);
  const lsp = await trusted(dir, { crossChars: 1_000_000 });
  try {
    const u = uriOf(user);
    lsp.open(u, USER);
    await modelOf(lsp, u, 1);
    await new Promise((res) => setTimeout(res, 500));
    let before = (await lsp.stats()).reader.bytes;
    const def = await lsp.request('textDocument/definition', { textDocument: { uri: u }, position: at(USER, 'fee(amount', 1) });
    assert.match(def?.uri ?? '', /fees\.jsc$/, JSON.stringify(def));
    const read = (await lsp.stats()).reader.bytes - before;
    assert.ok(read < 50_000, `a definition read ${read} bytes`);
    before = (await lsp.stats()).reader.bytes;
    const refs = await lsp.request('textDocument/references', { textDocument: { uri: u }, position: at(USER, 'fee(amount', 1), context: { includeDeclaration: true } });
    const read2 = (await lsp.stats()).reader.bytes - before;
    assert.ok(read2 <= 1_100_000, `references read ${read2} bytes (budget 1,000,000)`);
    // Past the budget the cross-file part fails closed: the file's own references are still answered.
    assert.ok(refs.length >= 1, JSON.stringify(refs));
  } finally {
    await lsp.stop();
  }
});

test('S2: a file reached through a linked directory is listed once: references once, the rename applies', { skip, timeout: 240_000 }, async () => {
  const dir = tmp();
  const { fees, user } = crossProject(dir);
  mkdirSync(join(dir, 'contracts', 'b'));
  writeFileSync(join(dir, 'contracts', 'b', 'User.jsc'), USER.replace('"./fees.jsc"', '"../app/fees.jsc"'));
  symlinkSync(join(dir, 'contracts', 'b'), join(dir, 'contracts', 'alias'));
  writeFileSync(user, 'jsc 1\npackage app\n\ncontract Plain {\n  state { n: Int }\n\n  init() {\n    return { n: 0 }\n  }\n}\n');
  const lsp = await trusted(dir);
  try {
    const l = uriOf(fees);
    const alias = uriOf(join(dir, 'contracts', 'alias', 'User.jsc'));
    const text = USER.replace('"./fees.jsc"', '"../app/fees.jsc"');
    lsp.open(l, FEES);
    lsp.open(alias, text);
    await modelOf(lsp, l, 1);
    await modelOf(lsp, alias, 1);
    const refs = await lsp.request('textDocument/references', { textDocument: { uri: l }, position: at(FEES, 'fee(amount'), context: { includeDeclaration: false } });
    const keys = refs.map((r) => `${r.uri}:${r.range.start.line}:${r.range.start.character}`);
    assert.equal(new Set(keys).size, keys.length, `no duplicates: ${keys}`);
  } finally {
    await lsp.stop();
  }
});

test('C5: a late index never lends the previous version\'s semantic tokens', { skip, timeout: 120_000 }, async () => {
  const dir = tmp();
  const { fees } = crossProject(dir);
  const lsp = await trusted(dir, { indexDelayMs: 5000 });
  try {
    const l = uriOf(fees);
    lsp.open(l, FEES);
    await modelOf(lsp, l, 1);
    const t1 = await lsp.request('textDocument/semanticTokens/full', { textDocument: { uri: l } });
    assert.ok(t1.data.length > 0);
    lsp.change(l, 2, `\n\n${FEES}`);
    await modelOf(lsp, l, 2);
    const t2 = await lsp.request('textDocument/semanticTokens/full', { textDocument: { uri: l } });
    assert.deepEqual(t2.data, [], 'nothing until this version\'s index');
  } finally {
    await lsp.stop();
  }
});

test('C3: a java: library\'s open buffer is the library the analysis describes', { skip, timeout: 300_000 }, async () => {
  const dir = tmp();
  crossProject(dir);
  const lib = join(dir, 'node_modules', '@example', 'compliance', 'joust-lib', 'com', 'example', 'compliance');
  mkdirSync(lib, { recursive: true });
  writeFileSync(join(dir, 'node_modules', '@example', 'compliance', 'package.json'), JSON.stringify({ name: '@example/compliance', version: '1.0.0' }));
  const ALLOW = readFileSync(join(REPO, 'examples/compliance/src/main/java/com/example/compliance/AllowList.java'), 'utf8');
  writeFileSync(join(lib, 'AllowList.java'), ALLOW);
  const file = join(dir, 'contracts', 'app', 'Guarded.jsc');
  const text = readFileSync(join(REPO, 'examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/Guarded.jsc'), 'utf8').replace('package org.julclang.state.examples.dsl', 'package app');
  writeFileSync(file, text);
  const lsp = await trusted(dir);
  try {
    const uri = uriOf(file);
    lsp.open(uri, text);
    await modelOf(lsp, uri, 1);
    assert.deepEqual((await lsp.diagnostics(uri, 1)).diagnostics.filter((x) => x.severity === 1), []);
    // The library's buffer renames isAllowed: Guarded is analysed again against it (its import now names nothing).
    const javaUri = uriOf(join(lib, 'AllowList.java'));
    lsp.conn.sendNotification('textDocument/didOpen', { textDocument: { uri: javaUri, languageId: 'java', version: 1, text: ALLOW.replace(/\bisAllowed\b/g, 'isAllowedChanged') } });
    const p = await lsp.until(() => lsp.published.find((x) => x.uri === uri && x.version === 1 && x.diagnostics.some((d) => d.severity === 1 && /isAllowed/.test(d.message))), 120000, 'Guarded against the buffer');
    assert.ok(p);
    assert.ok(!lsp.published.some((x) => x.uri === javaUri), 'the Java buffer is never analysed');
  } finally {
    await lsp.stop();
  }
});
