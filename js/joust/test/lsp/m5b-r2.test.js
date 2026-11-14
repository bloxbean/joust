// #99 round r2 over the protocol, with the real engine: renames refused while the file has an error (S4, C2) or when
// another file imports the renamed name (F2); a shorthand of one declaration renamed as one edit (C5); a large rename
// whose check leaves the protocol thread answering (S3); completion and hover right after didChange answer from that
// version, never an older model (C4); completion inside a method that does not parse (F1); a hostile file's errors
// capped (S2); Remove invisible characters… from the text, past the model's cap (S2).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { TextDocument } from 'vscode-languageserver-textdocument';
import { HAS_ENGINE, startLsp, tmp, uriOf, VSCODE_CAPS } from './client.js';

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
