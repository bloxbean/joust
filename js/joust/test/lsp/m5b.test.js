// ADR-0021 M5b over the protocol, with the real engine: hover, definition, references, semantic tokens, completion and
// joust.addImport, JOUST: Rename Symbol (joust/rename, L6), the JSC0704 quick fix, Remove invisible characters…,
// Show generated Java, recovered diagnostics (L1 recovered), and builds on save (D4: in memory, the ABI comparison,
// withdrawn by a newer version, never in restricted mode).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { TextDocument } from 'vscode-languageserver-textdocument';
import { ENGINE, HAS_ENGINE, startLsp, tmp, uriOf, VSCODE_CAPS } from './client.js';

const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine';

export const SHOP = `jsc 2
package app

type Line = { qty: Int, price: Int }

fn cost(l: Line): Int = l.qty * l.price

contract Shop {
  state { owner: Key, total: Int }

  init() {
    return { owner: caller, total: 0 }
  }

  action add(lines: List<Line>) only owner {
    const sum = lines.fold(0, (acc, x) => acc + cost(x))
    require(sum >= 0, "never negative")
    return { ...state, total: total + sum }
  }

  view current(): Int = total

  destroy close() only owner {
    pay(owner, balance)
  }
}
`;

const vscode = (dir, extra = {}) => startLsp({ folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: false, ...extra } });
const at = (text, needle, delta = 0) => {
  const off = text.indexOf(needle);
  assert.ok(off >= 0, needle);
  const doc = TextDocument.create('x', 'jsc', 1, text);
  return doc.positionAt(off + delta);
};
const accepted = (lsp, uri, v) => lsp.until(() => lsp.statuses.find((s) => s.uri === uri && s.modelVersion >= v), 20000, `the model of v${v}`);

test('hover, definition, references and semantic tokens come from the model (D7)', { skip }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'Shop.jsc'));
    lsp.open(uri, SHOP);
    await accepted(lsp, uri, 1);
    const d = (await lsp.diagnostics(uri, 1)).diagnostics;
    assert.deepEqual(d.filter((x) => x.severity === 1), []);
    // The state field `total` in `total + sum`: hover, definition at its declaration, references.
    const use = at(SHOP, 'total: total + sum', 7);
    const h = await lsp.request('textDocument/hover', { textDocument: { uri }, position: use });
    assert.match(h.contents.value, /total: Int/);
    assert.match(h.contents.value, /state field/);
    assert.match(h.contents.value, /ABI manifest/);
    const def = await lsp.request('textDocument/definition', { textDocument: { uri }, position: use });
    assert.deepEqual(def.range.start, at(SHOP, 'total: Int'));
    const refs = await lsp.request('textDocument/references', { textDocument: { uri }, position: use, context: { includeDeclaration: true } });
    assert.ok(refs.length >= 4, JSON.stringify(refs));
    // A std-free built-in: caller.
    const c = await lsp.request('textDocument/hover', { textDocument: { uri }, position: at(SHOP, 'caller', 1) });
    assert.match(c.contents.value, /caller: Key/);
    // A lambda parameter's definition.
    const x = await lsp.request('textDocument/definition', { textDocument: { uri }, position: at(SHOP, 'cost(x)', 5) });
    assert.deepEqual(x.range.start, at(SHOP, 'x) =>'));
    // The type of an expression: hover on `fold`'s call.
    const t = await lsp.request('textDocument/hover', { textDocument: { uri }, position: at(SHOP, 'lines.fold', 0) });
    assert.match(t.contents.value, /List<Line>/);
    const tokens = await lsp.request('textDocument/semanticTokens/full', { textDocument: { uri } });
    assert.ok(tokens.data.length > 50 && tokens.data.length % 5 === 0, tokens.data.length);
  } finally {
    await lsp.stop();
  }
});

test('recovered diagnostics: every error at once, the warnings of what checked (D5, L1 recovered)', { skip }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'Shop.jsc'));
    const broken = SHOP.replace('total: total + sum', 'total: total + nope').replace('view current(): Int = total', 'view current(): Int = )');
    lsp.open(uri, broken);
    const d = (await lsp.diagnostics(uri, 1)).diagnostics;
    const codes = d.filter((x) => x.severity === 1).map((x) => x.code);
    assert.ok(codes.includes('JSC0217') && codes.includes('JSC0143'), JSON.stringify(codes));
  } finally {
    await lsp.stop();
  }
});

test('completion: what is in scope, nothing the checker refuses there, std rows with joust.addImport (D7, D4a)', { skip }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'Shop.jsc'));
    lsp.open(uri, SHOP);
    await accepted(lsp, uri, 1);
    // At the start of `sum >= 0` in add: locals, state fields, built-ins, fns.
    const items = (await lsp.request('textDocument/completion', { textDocument: { uri }, position: at(SHOP, 'sum >= 0') })).items;
    const labels = items.map((i) => i.label);
    for (const l of ['sum', 'lines', 'owner', 'total', 'state', 'caller', 'cost', 'min']) assert.ok(labels.includes(l), l);
    for (const i of items) assert.equal(i.additionalTextEdits, undefined, i.label);
    // In the view: no transaction built-ins (JSC0415).
    const inView = (await lsp.request('textDocument/completion', { textDocument: { uri }, position: at(SHOP, '= total\n', 2) })).items.map((i) => i.label);
    assert.ok(inView.includes('total') && !inView.includes('caller') && !inView.includes('sum'), JSON.stringify(inView));
    // A std row not imported carries the addImport command, which adds the import through workspace/applyEdit.
    // A new list replaces the last one's import ids: the item is taken from a fresh list.
    const fresh = (await lsp.request('textDocument/completion', { textDocument: { uri }, position: at(SHOP, 'sum >= 0') })).items;
    const sha = fresh.find((i) => i.label === 'sha2_256');
    assert.equal(sha.command.command, 'joust.addImport');
    assert.equal(sha.command.arguments.length, 1);
    // As VS Code does: the item's text edit first (a new version), then its command.
    const doc = TextDocument.create(uri, 'jsc', 1, SHOP);
    const inserted = TextDocument.applyEdits(doc, [{ ...sha.textEdit, newText: 'sha2_256(#00) != #01 && ' }]);
    lsp.change(uri, 2, inserted);
    const r = await lsp.request('workspace/executeCommand', { command: 'joust.addImport', arguments: sha.command.arguments });
    assert.equal(r.applied, true, JSON.stringify(r));
    const edit = lsp.applied.at(-1);
    assert.deepEqual(edit.edit.documentChanges[0].textDocument, { uri, version: 2 });
    assert.equal(edit.edit.documentChanges[0].edits[0].newText, 'import { sha2_256 } from "std/crypto"\n');
    assert.equal(edit.joustExpected[0].uri, uri);
    // Single use.
    assert.equal((await lsp.request('workspace/executeCommand', { command: 'joust.addImport', arguments: sha.command.arguments })).applied, false);
    // After a dot: a record's fields.
    const dotted = SHOP.replace('l.qty * l.price', 'l. * l.price');
    lsp.change(uri, 3, dotted);
    const fields = (await lsp.request('textDocument/completion', { textDocument: { uri }, position: at(dotted, 'l. *', 2) })).items.map((i) => i.label);
    assert.deepEqual(fields, ['qty', 'price']);
  } finally {
    await lsp.stop();
  }
});

test('JOUST: Rename Symbol: validated on the snapshot, applied through workspace/applyEdit; refusals; ABI names warn (D7, L6)', { skip }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'Shop.jsc'));
    lsp.open(uri, SHOP);
    await accepted(lsp, uri, 1);
    // A const: renamed everywhere.
    const r = await lsp.request('joust/rename', { uri, position: at(SHOP, 'sum = lines'), newName: 'subtotal' });
    assert.equal(r.applied, true, JSON.stringify(r));
    const e = lsp.applied.at(-1).edit.documentChanges[0];
    assert.deepEqual(e.textDocument, { uri, version: 1 });
    assert.equal(e.edits.length, 3);
    assert.ok(e.edits.every((x) => x.newText === 'subtotal'));
    // Shadowing a state field is refused (JSC0213, the re-analysis), as is a keyword and a non-name.
    for (const [name, why] of [['owner', /adds a diagnostic/], ['match', /keyword/], ['9x', /not a name/]]) {
      const no = await lsp.request('joust/rename', { uri, position: at(SHOP, 'sum = lines'), newName: name });
      assert.equal(no.applied, false, name);
      assert.match(no.error, why, `${name}: ${no.error}`);
    }
    // A state field is recorded in the ABI manifest: a warning first, then applied on confirmation; the shorthand-free
    // references all change.
    const w = await lsp.request('joust/rename', { uri, position: at(SHOP, 'total: Int'), newName: 'sum2' });
    assert.equal(w.needsConfirmation, true, JSON.stringify(w));
    assert.match(w.warning, /ABI manifest/);
    const n = lsp.applied.length;
    const ok = await lsp.request('joust/rename', { uri, position: at(SHOP, 'total: Int'), newName: 'sum2', confirm: true });
    assert.equal(ok.applied, true, JSON.stringify(ok));
    assert.equal(lsp.applied.length, n + 1);
    // A changed buffer: the rename is refused until the new version has a model.
    lsp.change(uri, 2, SHOP.replace('"never negative"', '"never below zero"'));
    const stale = await lsp.request('joust/rename', { uri, position: at(SHOP, 'sum = lines'), newName: 'subtotal' });
    assert.equal(stale.applied, false);
  } finally {
    await lsp.stop();
  }
});

test('the JSC0704 quick fix imports the std row (Invoked only), re-checked (L6)', { skip }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'Shop.jsc'));
    const text = SHOP.replace('require(sum >= 0, "never negative")', 'require(sha2_256(#00) != #01, "never")');
    lsp.open(uri, text);
    const d = (await lsp.diagnostics(uri, 1)).diagnostics;
    const e = d.find((x) => x.code === 'JSC0704');
    assert.ok(e, JSON.stringify(d));
    const actions = await lsp.request('textDocument/codeAction', { textDocument: { uri }, range: e.range, context: { diagnostics: [e], triggerKind: 1 } });
    const fix = actions.find((a) => a.title.includes('sha2_256'));
    assert.ok(fix, JSON.stringify(actions));
    const r = await lsp.request('workspace/executeCommand', { command: fix.command.command, arguments: fix.command.arguments });
    assert.equal(r.applied, true);
    assert.equal(lsp.applied.at(-1).edit.documentChanges[0].edits[0].newText, 'import { sha2_256 } from "std/crypto"\n');
  } finally {
    await lsp.stop();
  }
});

test('Remove invisible characters…: lists every JSC0145/JSC0148, removes exactly those from exactly that version (D7)', { skip }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'Shop.jsc'));
    const text = SHOP.replace('"never negative"', '"never​ neg\u0007ative"').replace('// x', '');
    lsp.open(uri, text);
    await accepted(lsp, uri, 1);
    const r = await lsp.request('joust/invisible', { uri });
    assert.deepEqual(r.occurrences.map((o) => `${o.code} ${o.char}`), ['JSC0145 U+200B', 'JSC0148 U+0007']);
    const done = await lsp.request('joust/removeInvisible', { id: r.id });
    assert.equal(done.applied, true);
    const edits = lsp.applied.at(-1).edit.documentChanges[0].edits;
    assert.equal(edits.length, 2);
    assert.ok(edits.every((x) => x.newText === ''));
    // Single use, and bound to the version.
    assert.equal((await lsp.request('joust/removeInvisible', { id: r.id })).applied, false);
  } finally {
    await lsp.stop();
  }
});

test('Show generated Java: the lowering on request, served by an opaque id, at the cursor\'s Java line (D7, D4b)', { skip }, async () => {
  const dir = tmp();
  const lsp = await vscode(dir);
  try {
    const uri = uriOf(join(dir, 'Shop.jsc'));
    lsp.open(uri, SHOP);
    await accepted(lsp, uri, 1);
    const g = await lsp.request('joust/generatedJava', { uri, position: at(SHOP, 'action add') });
    assert.equal(g.javaFile, 'app/Shop.java');
    const c = await lsp.request('joust/generatedContent', { id: g.id });
    assert.match(c.text, /@StateContract/);
    assert.match(c.text.split('\n')[g.line + 1] ?? '', /@Action|add/);
    assert.match((await lsp.request('joust/generatedContent', { id: 'nope' })).error, /gone/);
  } finally {
    await lsp.stop();
  }
});

/** A trusted npm project with the real engine as its own compiler, and its committed ABI manifest. */
export function realProject(root) {
  mkdirSync(join(root, 'contracts', 'app'), { recursive: true });
  writeFileSync(join(root, 'package.json'), JSON.stringify({ dependencies: { '@julclang/joust': '0.0.0' } }));
  writeFileSync(join(root, 'joust.config.json'), '{ "contracts": "contracts" }');
  const pkg = join(root, 'node_modules', '@julclang', 'joust');
  mkdirSync(join(pkg, 'engine'), { recursive: true });
  writeFileSync(join(pkg, 'package.json'), JSON.stringify({ name: '@julclang/joust', version: '9.9.9' }));
  for (const f of readdirSync(ENGINE).filter((x) => /^(joust-toolchain\.js(\.wasm)?|package\.json)$/.test(x))) copyFileSync(join(ENGINE, f), join(pkg, 'engine', f));
  writeFileSync(join(root, 'contracts', 'app', 'Shop.jsc'), SHOP);
  return join(root, 'contracts', 'app', 'Shop.jsc');
}

test('builds on save: in memory, the ABI comparison (JSC0003), withdrawn by a newer version; none in restricted mode (D4)', { skip, timeout: 240000 }, async () => {
  const dir = tmp();
  const file = realProject(dir);
  const uri = uriOf(file);
  const before = readdirSync(dir).sort();
  const lsp = await startLsp({ folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: true }, hostOptions: {} });
  try {
    lsp.open(uri, SHOP);
    await accepted(lsp, uri, 1);
    assert.equal(lsp.statuses.find((s) => s.uri === uri && s.mode).mode, 'project-matched');
    lsp.conn.sendNotification('textDocument/didSave', { textDocument: { uri } });
    // No manifest committed: the build's JSC0003 joins the analysis's diagnostics, for this version.
    const p = await lsp.until(() => lsp.published.find((x) => x.uri === uri && x.diagnostics.some((d) => d.code === 'JSC0003')), 200000, 'the build\'s JSC0003');
    assert.equal(p.version, 1);
    assert.match(p.diagnostics.find((d) => d.code === 'JSC0003').source, /in memory/);
    // A newer version withdraws it.
    lsp.change(uri, 2, SHOP.replace('"never negative"', '"never below zero"'));
    const q = await lsp.diagnostics(uri, 2);
    assert.ok(!q.diagnostics.some((d) => d.code === 'JSC0003'), JSON.stringify(q.diagnostics));
    // Nothing written into the project (L3).
    assert.deepEqual(readdirSync(dir).sort(), before);
    assert.deepEqual(readdirSync(join(dir, 'contracts', 'app')), ['Shop.jsc']);
  } finally {
    await lsp.stop();
  }
  // Restricted mode: no build on save, the same file.
  const r = await startLsp({ folders: [dir], capabilities: VSCODE_CAPS, initOptions: { client: 'vscode-joust', trusted: false } });
  try {
    r.open(uri, SHOP);
    await r.until(() => r.statuses.find((s) => s.uri === uri && s.modelVersion >= 1), 20000);
    r.conn.sendNotification('textDocument/didSave', { textDocument: { uri } });
    await new Promise((res) => setTimeout(res, 3000));
    assert.ok(!r.published.some((x) => x.diagnostics.some((d) => d.code === 'JSC0003')));
    // Check build (in memory) runs on request, on the bundled engine.
    const c = await r.request('joust/checkBuild', { uri });
    assert.equal(c.ok, true, JSON.stringify(c));
    assert.equal(c.contracts, 1);
  } finally {
    await r.stop();
  }
});
