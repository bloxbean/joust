// #130 round r1 (Codex X1–X3): ADR-0021 M5's index, rename and member completion over ADR-0018 M3's owned objects, on
// this commit's engine: an owned method's bare names and modifiers name its object's fields (X1), M3's header references
// are recorded (keyed by, init migrate from params.f, owned qualifiers, admits; X2), and `state.` in an owned method and
// an arriving object complete their owned type's fields (X3).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import v8 from 'node:v8';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { TextDocument } from 'vscode-languageserver-textdocument';
import { completion, completionModel, normalise, renamePlan, renamePreserves } from '../../src/lsp/features.js';
import { hasExnref } from '../../src/lsp/worker.js';
import { ENGINE, HAS_ENGINE } from './client.js';
import { load, Rec, fromHex } from '../../../jsc-interpreter/src/index.js';

const skip = !HAS_ENGINE && 'needs the toolchain engine in js/joust/engine';
const DSL = fileURLToPath(new URL('../../../../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/', import.meta.url));
let loaded = null;
function engine() {
  if (loaded) return loaded;
  if (!hasExnref()) v8.setFlagsFromString('--experimental-wasm-exnref');
  const file = join(ENGINE, 'joust-toolchain.js');
  loaded = new Promise((resolve) => {
    globalThis.onJulcReady = (api) => resolve(api);
    createRequire(file)(file);
  });
  return loaded;
}
const PKG = 'org/julclang/state/examples/dsl/';
const files = () => {
  const out = {};
  for (const f of ['Keys.jsc', 'CounterV1.jsc', 'CounterV2.jsc', 'Bank.jsc', 'Guild.jsc']) out[PKG + f] = readFileSync(join(DSL, f), 'utf8');
  return out;
};
const pos = (text, needle, after = 0) => {
  const doc = TextDocument.create('x', 'jsc', 1, text);
  const k = text.indexOf(needle, after);
  assert.ok(k >= 0, needle);
  return doc.positionAt(k);
};
const apply = (text, edits) => TextDocument.applyEdits(TextDocument.create('x', 'jsc', 1, text), edits);

// Codex's X1 reproduction: an instance field and an owned field share a name.
const OVERLAP = 'jsc 1\npackage test\ncontract C {\n  state { n: Int }\n  owned Item { owner: Key, n: Int, count: Int }\n'
  + '  init() { return { n: 0 } }\n  owned init Item.open() { return { owner: caller, n: 0, count: 100 } }\n'
  + '  owned action Item.inc() only owner { return { ...state, n: n + 1 } }\n'
  + '  owned destroy Item.close() only owner { pay(owner, balance) }\n}\n';

test('X1: renaming an instance field leaves an owned action\'s same-named field, and its transition, unchanged', { skip }, async () => {
  const api = await engine();
  const analyse = (source, id) => normalise(JSON.parse(api.lang.analyze({ file: 'C.jsc', source, id, files: { [id]: source } }).model));
  const before = analyse(OVERLAP, 'test/C.jsc');
  assert.ok(before.accepted, JSON.stringify(before.diagnostics));
  const plan = renamePlan(before, pos(OVERLAP, 'n: Int }'), 'count');
  assert.ok(!plan.error, plan.error);
  const after = apply(OVERLAP, plan.edits);
  // Only the instance's field is renamed: the owned action still reads and writes its object's n.
  assert.match(after, /state \{ count: Int \}/);
  assert.match(after, /owned action Item\.inc\(\) only owner \{ return \{ \.\.\.state, n: n \+ 1 \} \}/);
  assert.match(after, /init\(\) \{ return \{ count: 0 \} \}/);
  const model = analyse(after, 'test/C.jsc');
  assert.ok(model.accepted, JSON.stringify(model.diagnostics));
  assert.deepEqual(renamePreserves(before, model, plan.edits), { ok: true });
  // The owned transition, before and after, through the reference interpreter.
  const run = (src) => {
    const r = api.joust.build({ files: { 'test/C.jsc': src }, options: {} });
    assert.ok(r.ok, JSON.stringify(r.diagnostics));
    const p = load(r.generated.find((g) => g.jscFile.endsWith('C.jsc')).ir);
    const owner = fromHex('0a'.repeat(28));
    return p.call('inc', new Rec('Item', [owner, 0n, 100n]), [], { caller: owner, received: 0n, balance: 2_000_000n, validFrom: 0n, validTo: 1n, signers: [owner] }).value;
  };
  const was = run(OVERLAP);
  const is = run(after);
  assert.deepEqual(is.fields.slice(1), [1n, 100n]);
  assert.deepEqual(is.fields, was.fields);
  // The wrong plan (the owned action's n renamed too) is what the old index made: preservation refuses it now.
  const wrong = after.replace('n: n + 1', 'n: count + 1');
  const wm = analyse(wrong, 'test/C.jsc');
  assert.ok(wm.accepted);
  const edits = [...plan.edits, { range: { start: pos(after, 'n + 1'), end: { ...pos(after, 'n + 1'), character: pos(after, 'n + 1').character + 1 } }, newText: 'count' }];
  assert.equal(renamePreserves(before, wm, edits).ok, false);
});

test('X1: an owned method\'s bare names and modifiers refer to its object\'s fields (Bank)', { skip }, async () => {
  const api = await engine();
  const fs = files();
  const id = PKG + 'Bank.jsc';
  const m = normalise(JSON.parse(api.lang.analyze({ file: 'Bank.jsc', source: fs[id], id, files: fs }).model));
  const saved = m.declarations.find((d) => d.kind === 'field' && d.name === 'saved');
  const refs = m.references.filter((r) => r[4] === saved.id);
  assert.ok(refs.length >= 2, `${refs.length} references to Account.saved`);
  const plan = renamePlan(m, { line: saved.nameRange[0], character: saved.nameRange[1] }, 'kept');
  assert.ok(!plan.error, plan.error);
  const after = apply(fs[id], plan.edits);
  const am = normalise(JSON.parse(api.lang.analyze({ file: 'Bank.jsc', source: after, id, files: { ...fs, [id]: after } }).model));
  assert.ok(!am.diagnostics.some((d) => d.severity === 'error'), JSON.stringify(am.diagnostics));
  assert.deepEqual(renamePreserves(m, am, plan.edits), { ok: true });
});

test('X2: keyed by <field> and init migrate from params.<field> rename with their fields; owned types are navigable', { skip }, async () => {
  const api = await engine();
  const fs = files();
  const check = (file, find, newName, expect) => {
    const id = PKG + file;
    const m = normalise(JSON.parse(api.lang.analyze({ file, source: fs[id], id, files: fs }).model));
    const d = find(m);
    assert.ok(d, `${file}: the declaration`);
    const plan = renamePlan(m, { line: d.nameRange[0], character: d.nameRange[1] }, newName);
    assert.ok(!plan.error, `${file}: ${plan.error}`);
    const after = apply(fs[id], plan.edits);
    assert.match(after, expect, file);
    const am = normalise(JSON.parse(api.lang.analyze({ file, source: after, id, files: { ...fs, [id]: after } }).model));
    assert.ok(!am.diagnostics.some((x) => x.severity === 'error'), `${file}: ${JSON.stringify(am.diagnostics)}`);
    assert.deepEqual(renamePreserves(m, am, plan.edits), { ok: true }, file);
  };
  check('Keys.jsc', (m) => {
    const seat = m.declarations.find((d) => d.kind === 'owned' && d.name === 'Seat');
    return m.declarations.find((d) => d.kind === 'field' && d.parent === seat.id && d.name === 'code');
  }, 'renamedField', /keyed by renamedField/);
  check('CounterV2.jsc', (m) => m.declarations.find((d) => d.kind === 'field' && d.name === 'previous'), 'newPolicy', /init migrate from params\.newPolicy/);
  // An owned type: its qualifiers (owned methods, invariants by type) and admits name it.
  const id = PKG + 'Guild.jsc';
  const m = normalise(JSON.parse(api.lang.analyze({ file: 'Guild.jsc', source: fs[id], id, files: fs }).model));
  const hero = m.declarations.find((d) => d.kind === 'owned' && d.name === 'Hero');
  const uses = m.references.filter((r) => r[4] === hero.id).length;
  const qualifiers = (fs[id].match(/\b(owned (init|action|destroy) Hero\.|invariant Hero\.|admits Hero\b)/g) ?? []).length;
  assert.ok(qualifiers > 0 && uses >= qualifiers, `${uses} references to Hero, ${qualifiers} qualifiers and admits`);
  // Renaming the owned type renames every qualifier, admits and type use, and nothing else changes meaning.
  const plan = renamePlan(m, { line: hero.nameRange[0], character: hero.nameRange[1] }, 'Champion');
  assert.ok(!plan.error && plan.abi, plan.error);
  const after = apply(fs[id], plan.edits);
  assert.equal((after.match(/\bHero\b/g) ?? []).length, 0);
  const am = normalise(JSON.parse(api.lang.analyze({ file: 'Guild.jsc', source: after, id, files: { ...fs, [id]: after } }).model));
  assert.ok(!am.diagnostics.some((x) => x.severity === 'error'), JSON.stringify(am.diagnostics));
  assert.deepEqual(renamePreserves(m, am, plan.edits), { ok: true });
  // An owned method, a handler and init migrate are refused.
  const inc = m.declarations.find((d) => d.kind === 'ownedAction');
  assert.match(renamePlan(m, { line: inc.nameRange[0], character: inc.nameRange[1] }, 'other').error, /cannot be renamed/);
});

test('X3: state. in owned and keyed methods, and an arriving object, complete their owned type\'s fields', { skip }, async () => {
  const api = await engine();
  const std = JSON.parse(api.lang.std({}).json);
  const fs = files();
  const labels = (items) => items.map((i) => i.label).sort();
  for (const [file, method, want, not] of [
    ['Bank.jsc', 'owned action Account.deposit', ['owner', 'saved'], ['admin', 'paused']],
    ['Keys.jsc', 'owned action Seat.write', ['code', 'holder', 'note'], ['admin']],
  ]) {
    const id = PKG + file;
    const src = fs[id];
    const m = normalise(JSON.parse(api.lang.analyze({ file, source: src, id, files: fs }).model));
    const at = src.indexOf('{', src.indexOf(method)) + 1;
    // Fresh: an accepted file with `state.x` in the body; reconciled: `state.` typed into it.
    const body = src.slice(0, at) + ' const probeQ = state.' + want[0] + '\n' + src.slice(at);
    const bm = normalise(JSON.parse(api.lang.analyze({ file, source: body, id, files: { ...fs, [id]: body } }).model));
    const p = pos(body, 'state.' + want[0], at);
    const dot = { line: p.line, character: p.character + 'state.'.length };
    const fresh = labels(completion(body, dot, bm, std));
    const typed = src.slice(0, at) + ' state.\n' + src.slice(at);
    const tm = normalise(JSON.parse(api.lang.analyze({ file, source: typed, id, files: { ...fs, [id]: typed } }).model));
    const tp = pos(typed, 'state.', at);
    const reconciled = labels(completion(typed, { line: tp.line, character: tp.character + 'state.'.length }, completionModel(tm, m, src, typed), std));
    for (const [what, got] of [['fresh', fresh], ['reconciled', reconciled]]) {
      for (const l of want) assert.ok(got.includes(l), `${file} ${what}: ${l} in ${got}`);
      for (const l of not) assert.ok(!got.includes(l), `${file} ${what}: ${l} not in ${got}`);
    }
  }
  // An arriving object (admits Hero's second parameter) is a Hero.
  const id = PKG + 'Guild.jsc';
  const src = fs[id];
  const m = normalise(JSON.parse(api.lang.analyze({ file: 'Guild.jsc', source: src, id, files: fs }).model));
  const admit = /action (\w+)\((\w+): Bytes, (\w+): Hero\)[^{]*admits Hero[^{]*\{/.exec(src);
  assert.ok(admit, 'an admits action with its arriving Hero');
  const at = admit.index + admit[0].length;
  const typed = src.slice(0, at) + ' ' + admit[3] + '.\n' + src.slice(at);
  const tm = normalise(JSON.parse(api.lang.analyze({ file: 'Guild.jsc', source: typed, id, files: { ...fs, [id]: typed } }).model));
  const tp = pos(typed, admit[3] + '.', at);
  const got = labels(completion(typed, { line: tp.line, character: tp.character + admit[3].length + 1 }, completionModel(tm, m, src, typed), std));
  const heroFields = m.declarations.filter((d) => d.kind === 'field' && d.parent === m.declarations.find((x) => x.kind === 'owned' && x.name === 'Hero').id).map((d) => d.name);
  for (const f of heroFields) assert.ok(got.includes(f), `the arriving Hero's ${f} in ${got}`);
});
