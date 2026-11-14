// #99 round r2 over the protocol, with the real engine: renames refused while the file has an error (S4, C2) or when
// another file imports the renamed name (F2); a shorthand of one declaration renamed as one edit (C5); a large rename
// whose check leaves the protocol thread answering (S3); completion and hover right after didChange answer from that
// version, never an older model (C4); completion inside a method that does not parse (F1); a hostile file's errors
// capped (S2); Remove invisible characters… from the text, past the model's cap (S2); builds that read the npm
// packages' libraries (C6), stay in one buffer lifetime (C7) and coalesce (F7).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { TextDocument } from 'vscode-languageserver-textdocument';
import { ENGINE, HAS_ENGINE, startLsp, tmp, uriOf, VSCODE_CAPS } from './client.js';

const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine';
const vscode = (dir, extra = {}) => startLsp({ folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: false, ...extra } });
const at = (text, needle, delta = 0) => {
  const off = text.indexOf(needle);
  assert.ok(off >= 0, needle);
  return TextDocument.create('x', 'jsc', 1, text).positionAt(off + delta);
};
const modelOf = (lsp, uri, v) => lsp.until(() => lsp.statuses.find((s) => s.uri === uri && s.modelVersion >= v), 30000, `the model of v${v}`);
const apply = (text, edits) => TextDocument.applyEdits(TextDocument.create('x', 'jsc', 1, text), edits);

const COUNT = `jsc 2
package app

contract Count {
  state { count: Int }

  init() {
    return { count: 0 }
  }

  action bump(by: Int) {
    require(by > 0, "positive")
    return { count: count + by }
  }

  action same() {
    return { count }
  }

  view current(): Int = count
}
`;

test('S4, C2: no rename while the file has an error (a dropped member, a method the checker stopped in)', { skip }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'Count.jsc'));
    // C2's shape: a method the checker stops in at an unknown name; S4's: a member the parser drops (a missing ')').
    for (const [v, text] of [[1, COUNT.replace('require(by > 0, "positive")', 'require(nope > 0, "positive")')],
      [2, COUNT.replace('require(by > 0, "positive")', 'require(by > 0, "positive"')]]) {
      if (v === 1) lsp.open(uri, text);
      else lsp.change(uri, v, text);
      await modelOf(lsp, uri, v);
      for (const [needle, name] of [['by: Int', 'delta'], ['count: Int', 'total']]) {
        const r = await lsp.request('joust/rename', { uri, position: at(text, needle), newName: name, confirm: true });
        assert.equal(r.applied, false, JSON.stringify(r));
        assert.match(r.error, /the file has errors: a rename needs a file that checks/);
        const prep = await lsp.request('joust/prepareRename', { uri, position: at(text, needle) });
        assert.match(prep.error ?? '', /the file has errors/, JSON.stringify(prep));
      }
    }
    assert.equal(lsp.applied.length, 0, 'nothing applied');
    // Repaired: the rename applies, and the repaired-then-renamed text checks (every use renamed).
    lsp.change(uri, 3, COUNT);
    await modelOf(lsp, uri, 3);
    const ok = await lsp.request('joust/rename', { uri, position: at(COUNT, 'by: Int'), newName: 'delta', confirm: true });
    assert.equal(ok.applied, true, JSON.stringify(ok));
    const renamed = apply(COUNT, lsp.applied.at(-1).edit.documentChanges[0].edits);
    assert.ok(!/\bby\b/.test(renamed), renamed);
  } finally {
    await lsp.stop();
  }
});

test('C5: a state field used as its own shorthand is one edit, renamed whole', { skip }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'Count.jsc'));
    lsp.open(uri, COUNT);
    await modelOf(lsp, uri, 1);
    const r = await lsp.request('joust/rename', { uri, position: at(COUNT, 'count: Int'), newName: 'total', confirm: true });
    assert.equal(r.applied, true, JSON.stringify(r));
    const edits = lsp.applied.at(-1).edit.documentChanges[0].edits;
    const keys = edits.map((e) => `${e.range.start.line}:${e.range.start.character}`);
    assert.equal(new Set(keys).size, keys.length, `no two edits of one token: ${keys}`);
    const renamed = apply(COUNT, edits);
    assert.match(renamed, /return \{ total \}/);
    assert.ok(!/\bcount\b/.test(renamed), renamed);
    lsp.change(uri, 2, renamed);
    const d = (await lsp.diagnostics(uri, 2)).diagnostics;
    assert.deepEqual(d.filter((x) => x.severity === 1), [], JSON.stringify(d));
  } finally {
    await lsp.stop();
  }
});

test('F2: a name another file imports is not renamed in one file; a local one is', { skip }, async () => {
  const dir = tmp();
  mkdirSync(join(dir, 'contracts', 'app'), { recursive: true });
  writeFileSync(join(dir, 'joust.config.json'), '{ "contracts": "contracts" }');
  const LIB = 'jsc 2\nlibrary app\n\ntype Fee = { bps: Int }\n\nfn fee(amount: Int, f: Fee): Int = amount * f.bps / 10000\n';
  const USER = `jsc 2
package app

import { fee, Fee } from "./fees.jsc"

contract User {
  state { paid: Int }

  init() {
    return { paid: 0 }
  }

  action pay(amount: Int) {
    return { paid: paid + fee(amount, Fee { bps: 30 }) }
  }
}
`;
  writeFileSync(join(dir, 'contracts', 'app', 'fees.jsc'), LIB);
  writeFileSync(join(dir, 'contracts', 'app', 'User.jsc'), USER);
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'contracts', 'app', 'fees.jsc'));
    lsp.open(uri, LIB);
    await modelOf(lsp, uri, 1);
    for (const [needle, name] of [['fee(amount', 'charge'], ['Fee = ', 'Charge'], ['bps: Int', 'basis']]) {
      const r = await lsp.request('joust/rename', { uri, position: at(LIB, needle), newName: name, confirm: true });
      assert.equal(r.applied, false, `${needle}: ${JSON.stringify(r)}`);
      assert.match(r.error, /can be named by the files that import this one \((contracts\/)?app\/User\.jsc\).*M5c/, r.error);
    }
    // A fn's parameter is the file's own.
    const local = await lsp.request('joust/rename', { uri, position: at(LIB, 'amount: Int'), newName: 'value', confirm: true });
    assert.equal(local.applied, true, JSON.stringify(local));
    assert.equal(lsp.applied.length, 1);
    // A file nobody imports: its state field renames (with the ABI warning). (User.jsc itself has errors here: in
    // Restricted Mode its import is not read, M5c.)
    writeFileSync(join(dir, 'contracts', 'app', 'Count.jsc'), COUNT);
    const u = uriOf(join(dir, 'contracts', 'app', 'Count.jsc'));
    lsp.open(u, COUNT);
    await modelOf(lsp, u, 1);
    const w = await lsp.request('joust/rename', { uri: u, position: at(COUNT, 'count: Int'), newName: 'total' });
    assert.equal(w.needsConfirmation, true, JSON.stringify(w));
  } finally {
    await lsp.stop();
  }
});

test('S3: a rename of 8,000 references is checked without stalling the protocol thread', { skip, timeout: 240_000 }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const k = 2000;
    const body = Array.from({ length: k }, (_, i) => `    require(by + by + by + by > ${i}, "m")`).join('\n');
    const text = COUNT.replace('    require(by > 0, "positive")', body);
    const uri = uriOf(join(dir, 'Count.jsc'));
    lsp.open(uri, text);
    await modelOf(lsp, uri, 1);
    const t0 = Date.now();
    let worst = 0;
    let pings = 0;
    let done = false;
    const rename = lsp.request('joust/rename', { uri, position: at(text, 'by: Int'), newName: 'delta', confirm: true }).finally(() => { done = true; });
    while (!done) {
      const s = Date.now();
      await lsp.request('joust/status', { uri });
      worst = Math.max(worst, Date.now() - s);
      pings++;
      await new Promise((r) => setTimeout(r, 20));
    }
    const r = await rename;
    console.log(`S3: ${k * 4 + 1} references renamed in ${Date.now() - t0} ms; ${pings} status pings, the slowest ${worst} ms`);
    assert.equal(r.applied, true, JSON.stringify(r).slice(0, 400));
    assert.equal(lsp.applied.at(-1).edit.documentChanges[0].edits.length, k * 4 + 2);
    assert.ok(worst < 1000, `the protocol thread answered within 1 s (slowest ${worst} ms)`);
  } finally {
    await lsp.stop();
  }
});

test('C4: completion and hover right after didChange answer from that version, not the last model', { skip }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'Count.jsc'));
    const v1 = COUNT.replace('require(by > 0, "positive")', 'const oldLocal = by\n    require(oldLocal > 0, "positive")');
    lsp.open(uri, v1);
    await modelOf(lsp, uri, 1);
    await lsp.request('textDocument/completion', { textDocument: { uri }, position: at(v1, 'oldLocal > 0') });   // the std catalogue, warm
    const v2 = v1.replaceAll('oldLocal', 'newLocal');
    lsp.change(uri, 2, v2);
    const [items, hov] = await Promise.all([
      lsp.request('textDocument/completion', { textDocument: { uri }, position: at(v2, 'newLocal > 0') }),
      lsp.request('textDocument/hover', { textDocument: { uri }, position: at(v2, 'newLocal > 0', 1) }),
    ]);
    const labels = items.items.map((i) => i.label);
    assert.ok(labels.includes('newLocal'), JSON.stringify(labels));
    assert.ok(!labels.includes('oldLocal'), 'no name of the old version');
    assert.match(JSON.stringify(hov), /newLocal/);
    assert.doesNotMatch(JSON.stringify(hov), /oldLocal/);
  } finally {
    await lsp.stop();
  }
});

test('F1: completion inside a method that does not parse keeps its context (state, caller) and its locals', { skip }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'Count.jsc'));
    lsp.open(uri, COUNT);
    await modelOf(lsp, uri, 1);
    // Typing an unfinished call in bump: the member does not parse now.
    const broken = COUNT.replace('    require(by > 0, "positive")', '    require(by > 0, "positive")\n    require(');
    lsp.change(uri, 2, broken);
    const items = (await lsp.request('textDocument/completion', { textDocument: { uri }, position: at(broken, 'require(\n', 8) })).items.map((i) => i.label);
    for (const l of ['by', 'count', 'state', 'caller']) assert.ok(items.includes(l), `${l} in ${JSON.stringify(items)}`);
    // In the view (no transaction): no caller, even while bump is broken.
    const inView = (await lsp.request('textDocument/completion', { textDocument: { uri }, position: at(broken, '= count\n', 2) })).items.map((i) => i.label);
    assert.ok(inView.includes('count') && !inView.includes('caller') && !inView.includes('by'), JSON.stringify(inView));
  } finally {
    await lsp.stop();
  }
});

test('S2: a hostile file\'s errors are capped, it analyses quickly, and a valid file beside it keeps its analysis', { skip, timeout: 240_000 }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const head = 'jsc 2\npackage app\n\ncontract C {\n  state { n: Int }\n\n  init() {\n    return { n: 0 }\n  }\n';
    const shapes = {
      unknown: head + Array.from({ length: 400 }, (_, i) => `  action a${i}() {\n    require(nope > 0, "x")\n    return state\n  }\n`).join('') + '}\n',
      unclosed: head + Array.from({ length: 1500 }, (_, i) => `  action a${i}( {\n    return state\n  }\n`).join('') + '}\n',
      ats: head + '  @\n'.repeat(6000) + '}\n',
      invisible: head + `  // ${'​'.repeat(20000)}\n}\n`,
    };
    for (const [name, text] of Object.entries(shapes)) {
      const uri = uriOf(join(dir, `${name}.jsc`));
      const t = Date.now();
      lsp.open(uri, text);
      const d = (await lsp.diagnostics(uri, 1, 30000)).diagnostics;
      const ms = Date.now() - t;
      console.log(`S2 ${name}: ${text.length} chars, ${d.length} diagnostics published in ${ms} ms`);
      assert.ok(d.length <= 201, `${name}: ${d.length} published`);
      assert.ok(d.some((x) => x.severity === 1 && x.code), `${name}: its errors`);
      assert.ok(!d.some((x) => /time limit|analysis stopped/.test(x.message)), `${name}: ${d.map((x) => x.message).slice(0, 2)}`);
      if (d.filter((x) => x.severity === 1).length >= 100) assert.ok(d.some((x) => x.severity === 3 && /More errors follow/.test(x.message)), name);
    }
    const ok = uriOf(join(dir, 'Counter.jsc'));
    lsp.open(ok, COUNT);
    const d = (await lsp.diagnostics(ok, 1, 30000)).diagnostics;
    assert.deepEqual(d.filter((x) => x.severity === 1), []);
    const st = await lsp.request('joust/status', { uri: ok });
    assert.equal(st.state, 'ok', JSON.stringify(st));
    // Remove invisible characters… finds every one from the text, past the model's 100 errors.
    const inv = await lsp.request('joust/invisible', { uri: uriOf(join(dir, 'invisible.jsc')) });
    assert.equal(inv.occurrences.length, 10000, JSON.stringify(inv).slice(0, 200));
    assert.equal(inv.more, 10000);
    assert.equal(inv.occurrences[0].char, 'U+200B');
  } finally {
    await lsp.stop();
  }
});

// ------------------------------------------------------------------ builds on save (C6, C7, F7)

const SHOP = `jsc 2
package app

import { fee } from "@acme/fees/fees.jsc"

contract Shop {
  state { owner: Key, total: Int }

  init() {
    return { owner: caller, total: 0 }
  }

  action add(sum: Int) only owner {
    require(fee(sum) >= 0, "never negative")
    return { ...state, total: total + sum }
  }

  destroy close() only owner {
    pay(owner, balance)
  }
}
`;

/** A trusted npm project with the real engine as its compiler and an npm package shipping a .jsc library (C6). */
function libraryProject(root) {
  mkdirSync(join(root, 'contracts', 'app'), { recursive: true });
  writeFileSync(join(root, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '0.0.0', '@acme/fees': '1.0.0' } }));
  writeFileSync(join(root, 'joust.config.json'), '{ "contracts": "contracts" }');
  const pkg = join(root, 'node_modules', '@julclang', 'joust');
  mkdirSync(join(pkg, 'engine'), { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: '@julclang/joust', version: '9.9.9' }));
  for (const f of readdirSync(ENGINE).filter((x) => /^(joust-toolchain\.js(\.wasm)?|package\.json)$/.test(x))) copyFileSync(join(ENGINE, f), join(pkg, 'engine', f));
  const fees = join(root, 'node_modules', '@acme', 'fees');
  mkdirSync(join(fees, 'joust-lib'), { recursive: true });
  writeFileSync(join(fees, 'package.json'), JSON.stringify({ name: '@acme/fees', version: '1.0.0' }));
  writeFileSync(join(fees, 'joust-lib', 'fees.jsc'), 'jsc 2\nlibrary acme.fees\n\nfn fee(amount: Int): Int = amount / 100\n');
  writeFileSync(join(root, 'contracts', 'app', 'Shop.jsc'), SHOP);
  return join(root, 'contracts', 'app', 'Shop.jsc');
}

const trusted = (dir) => startLsp({ folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: true }, hostOptions: {} });

test('C6: the build reads the npm packages\' libraries as joust build does, and a failed build always says why', { skip, timeout: 300_000 }, async () => {
  const dir = tmp();
  const uri = uriOf(libraryProject(dir));
  const lsp = await trusted(dir);
  try {
    lsp.open(uri, SHOP);
    await modelOf(lsp, uri, 1);
    const d = (await lsp.diagnostics(uri, 1)).diagnostics;
    assert.deepEqual(d.filter((x) => x.severity === 1), [], JSON.stringify(d));
    const c = await lsp.request('joust/checkBuild', { uri });
    // It builds (one contract); only the missing manifest (JSC0003) is left, shown.
    assert.equal(c.contracts, 1, JSON.stringify(c));
    assert.equal(c.errors, 1, JSON.stringify(c));
    const p = await lsp.until(() => lsp.published.find((x) => x.uri === uri && x.diagnostics.some((y) => y.code === 'JSC0003')), 60000, 'JSC0003');
    assert.ok(p);
    // A package library that does not check: the build fails, and says so (never zero errors on a failure).
    writeFileSync(join(dir, 'node_modules', '@acme', 'fees', 'joust-lib', 'fees.jsc'), 'jsc 2\nlibrary acme.fees\n\nfn fee(amount: Int): Int = amount / nope\n');
    const f = await lsp.request('joust/checkBuild', { uri });
    assert.equal(f.ok, false, JSON.stringify(f));
    assert.ok(f.errors >= 1, JSON.stringify(f));
  } finally {
    await lsp.stop();
  }
});

test('C7: a build does not cross a close and a reopen at the same version; a close withdraws its results', { skip, timeout: 300_000 }, async () => {
  const dir = tmp();
  const uri = uriOf(libraryProject(dir));
  const lsp = await trusted(dir);
  try {
    lsp.open(uri, SHOP);
    await modelOf(lsp, uri, 1);
    const before = (await lsp.stats()).builds.calls;
    const pending = lsp.request('joust/checkBuild', { uri });
    // Its snapshot is taken and the build is running (a cold build worker: seconds), then the buffer closes and reopens.
    for (let i = 0; i < 200 && (await lsp.stats()).builds.calls === before; i++) await new Promise((res) => setTimeout(res, 10));
    lsp.close(uri);
    const NEW = SHOP.replace('"never negative"', '"never below zero"');
    lsp.open(uri, NEW, 1);
    const r = await pending;
    assert.match(r.error ?? '', /changed while it built|cancelled/, JSON.stringify(r));
    await modelOf(lsp, uri, 1);
    await new Promise((res) => setTimeout(res, 500));
    // Nothing of the old build on the new lifetime.
    const last = lsp.published.filter((x) => x.uri === uri).at(-1);
    assert.ok(!last.diagnostics.some((x) => /in memory/.test(x.source)), JSON.stringify(last));
    // A finished build's results go with the close.
    const done = await lsp.request('joust/checkBuild', { uri });
    assert.equal(done.contracts, 1, JSON.stringify(done));
    await lsp.until(() => lsp.published.filter((x) => x.uri === uri).at(-1)?.diagnostics.some((y) => y.code === 'JSC0003'), 60000, 'the build shown');
    const n = lsp.published.length;
    lsp.close(uri);
    await lsp.until(() => lsp.published.slice(n).some((x) => x.uri === uri && x.diagnostics.length === 0), 10000, 'cleared on close');
  } finally {
    await lsp.stop();
  }
});

test('F7: saves coalesce: one build in flight, the latest pending; a changed input cancels the one in flight', { skip, timeout: 300_000 }, async () => {
  const dir = tmp();
  const uri = uriOf(libraryProject(dir));
  const lsp = await trusted(dir);
  try {
    lsp.open(uri, SHOP);
    await modelOf(lsp, uri, 1);
    const before = (await lsp.stats()).builds.calls;
    // Six change-and-save cycles in quick succession.
    for (let v = 2; v <= 7; v++) {
      lsp.change(uri, v, SHOP.replace('"never negative"', `"never negative ${v}"`));
      lsp.conn.sendNotification('textDocument/didSave', { textDocument: { uri } });
      await new Promise((res) => setTimeout(res, 50));
    }
    const p = await lsp.until(() => lsp.published.find((x) => x.uri === uri && x.version === 7 && x.diagnostics.some((d) => d.code === 'JSC0003')), 200000, 'the last version\'s build');
    assert.ok(p);
    await new Promise((res) => setTimeout(res, 1000));
    const calls = (await lsp.stats()).builds.calls - before;
    console.log(`F7: 6 saves ran ${calls} builds`);
    assert.ok(calls <= 3, `${calls} builds for 6 saves`);
  } finally {
    await lsp.stop();
  }
});

// ------------------------------------------------------------------ round r3 (#99 r2: Codex C8, C9, C11, C12, C13)

test('C8: a declaration renamed before its uses: completion offers the new name, not the old one', { skip }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'Count.jsc'));
    const v1 = COUNT.replace('require(by > 0, "positive")', 'const oldLocal = by + 1\n    require(oldLocal > 0, "positive")');
    lsp.open(uri, v1);
    await modelOf(lsp, uri, 1);
    const v2 = v1.replace('const oldLocal', 'const newLocal');
    lsp.change(uri, 2, v2);
    const d = (await lsp.diagnostics(uri, 2)).diagnostics;
    assert.ok(d.some((x) => /oldLocal/.test(x.message)), JSON.stringify(d));
    const r = await lsp.request('textDocument/completion', { textDocument: { uri }, position: at(v2, 'oldLocal > 0') });
    const labels = r.items.map((i) => i.label);
    assert.ok(labels.includes('newLocal'), JSON.stringify(labels));
    assert.ok(!labels.includes('oldLocal'), 'the old name is gone');
    assert.ok(labels.includes('by') && labels.includes('count'), JSON.stringify(labels));
    assert.equal(r.isIncomplete, true, 'not from a clean model: asked again as typing goes on');
  } finally {
    await lsp.stop();
  }
});

test('C11: an open importer not on disk yet keeps a library\'s name from being renamed', { skip }, async () => {
  const dir = tmp();
  mkdirSync(join(dir, 'contracts', 'app'), { recursive: true });
  writeFileSync(join(dir, 'joust.config.json'), '{ "contracts": "contracts" }');
  const LIB = 'jsc 2\nlibrary app\n\nfn fee(amount: Int): Int = amount / 100\n';
  writeFileSync(join(dir, 'contracts', 'app', 'fees.jsc'), LIB);
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'contracts', 'app', 'fees.jsc'));
    lsp.open(uri, LIB);
    await modelOf(lsp, uri, 1);
    // User.jsc exists only as an open buffer.
    const user = uriOf(join(dir, 'contracts', 'app', 'User.jsc'));
    lsp.open(user, 'jsc 2\npackage app\n\nimport { fee } from "./fees.jsc"\n\ncontract User {\n  state { n: Int }\n\n  init() {\n    return { n: fee(100) }\n  }\n}\n');
    const r = await lsp.request('joust/rename', { uri, position: at(LIB, 'fee(amount'), newName: 'charge', confirm: true });
    assert.equal(r.applied, false, JSON.stringify(r));
    assert.match(r.error, /import this one \((contracts\/)?app\/User\.jsc\)/, r.error);
    assert.equal(lsp.applied.length, 0);
    // Closed: nothing imports it any more, and the rename applies.
    lsp.close(user);
    const ok = await lsp.request('joust/rename', { uri, position: at(LIB, 'fee(amount'), newName: 'charge', confirm: true });
    assert.equal(ok.applied, true, JSON.stringify(ok));
  } finally {
    await lsp.stop();
  }
});

const OTHER = COUNT.replace('contract Count', 'contract Other');

test('C9: a file opened during a build, with other text, gets nothing from the build (nor do the files built with it)', { skip, timeout: 300_000 }, async () => {
  const dir = tmp();
  const uri = uriOf(libraryProject(dir));
  writeFileSync(join(dir, 'contracts', 'app', 'Other.jsc'), OTHER);
  const lsp = await trusted(dir);
  try {
    lsp.open(uri, SHOP);
    await modelOf(lsp, uri, 1);
    const before = (await lsp.stats()).builds.calls;
    const pending = lsp.request('joust/checkBuild', { uri });
    for (let i = 0; i < 300 && (await lsp.stats()).builds.calls === before; i++) await new Promise((res) => setTimeout(res, 10));
    const other = uriOf(join(dir, 'contracts', 'app', 'Other.jsc'));
    const NEW = OTHER.replace('"positive"', '"strictly positive"');
    lsp.open(other, NEW);
    const r = await pending;
    assert.match(r.error ?? '', /changed while it built|cancelled/, JSON.stringify(r));
    await modelOf(lsp, other, 1);
    await new Promise((res) => setTimeout(res, 500));
    for (const u of [uri, other]) {
      assert.ok(!lsp.published.filter((x) => x.uri === u).some((x) => x.diagnostics.some((d) => /in memory/.test(d.source))), u);
    }
    // Opened with the text on disk, a build's results reach it (its disk text is what was built).
    lsp.close(other);
    lsp.open(other, OTHER);
    await modelOf(lsp, other, 1);
    const done = await lsp.request('joust/checkBuild', { uri });
    assert.equal(done.contracts, 2, JSON.stringify(done));
  } finally {
    await lsp.stop();
  }
});

test('C12: saves in two projects of one window each get their build', { skip, timeout: 300_000 }, async () => {
  const dirs = [tmp(), tmp(), tmp()];
  const uris = dirs.map((d) => uriOf(libraryProject(d)));
  const lsp = await startLsp({ folders: dirs, capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: true }, hostOptions: {} });
  try {
    for (const u of uris) {
      lsp.open(u, SHOP);
      await modelOf(lsp, u, 1);
    }
    const before = (await lsp.stats()).builds.calls;
    lsp.conn.sendNotification('textDocument/didSave', { textDocument: { uri: uris[0] } });
    for (let i = 0; i < 300 && (await lsp.stats()).builds.calls === before; i++) await new Promise((res) => setTimeout(res, 10));
    lsp.conn.sendNotification('textDocument/didSave', { textDocument: { uri: uris[1] } });
    lsp.conn.sendNotification('textDocument/didSave', { textDocument: { uri: uris[2] } });
    for (const u of uris) {
      await lsp.until(() => lsp.published.find((x) => x.uri === u && x.diagnostics.some((d) => d.code === 'JSC0003')), 200000, `the build of ${u}`);
    }
  } finally {
    await lsp.stop();
  }
});

test('C13: the snapshot budget is debited while reading: a file past it is not read', { skip, timeout: 300_000 }, async () => {
  const dir = tmp();
  const uri = uriOf(libraryProject(dir));
  const lib = join(dir, 'node_modules', '@acme', 'fees', 'joust-lib');
  writeFileSync(join(lib, 'a-pad.jsc'), `jsc 2\nlibrary acme.pad\n\n// ${'x'.repeat(5000)}\n`);
  writeFileSync(join(lib, 'b-pad.jsc'), `jsc 2\nlibrary acme.pad2\n\n// ${'x'.repeat(5000)}\n`);
  writeFileSync(join(lib, 'zz-too-large.jsc'), `// ${'x'.repeat(3 * 1024 * 1024)}\n`);
  const lsp = await startLsp({ folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: true }, hostOptions: { buildChars: 8000 } });
  try {
    lsp.open(uri, SHOP);
    await modelOf(lsp, uri, 1);
    const r = await lsp.request('joust/checkBuild', { uri });
    assert.match(r.error ?? '', /larger than the language server builds \(8000 characters\)/, JSON.stringify(r));
    assert.doesNotMatch(r.error, /zz-too-large/);
  } finally {
    await lsp.stop();
  }
});
