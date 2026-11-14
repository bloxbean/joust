// M5b's model lookups (features.js) on hand-written models: what the protocol layer answers from a model, independent
// of the engine (the engine's models are checked against hand-written markers in JscLangTest and in m5b.test.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  completion, definition, escapeMarkdown, hover, importEdit, invisibleOccurrences, normalise, references, renamePlan, renamePreserves,
  semanticTokens, TOKEN_MODIFIERS, TOKEN_TYPES,
} from '../../src/lsp/features.js';

// jsc 2
// package t
// contract C {
//   state { goal: Int, owner: Key }
//   action set(goal: Int) only owner { return { ...state, goal } }
// }
const model = () => normalise({
  modelVersion: 2, declarations: [
    { id: 0, kind: 'contract', name: 'C', parent: -1, nameRange: [2, 9, 2, 10], range: [2, 0, 5, 1], outline: true },
    { id: 1, kind: 'state', name: 'state', parent: 0, nameRange: [3, 2, 3, 7], range: [3, 2, 3, 34], outline: true },
    { id: 2, kind: 'stateField', name: 'goal', parent: 1, nameRange: [3, 10, 3, 14], range: [3, 10, 3, 19], type: 'Int', abi: true },
    { id: 3, kind: 'stateField', name: 'owner', parent: 1, nameRange: [3, 21, 3, 26], range: [3, 21, 3, 31], type: 'Key', abi: true },
    { id: 4, kind: 'action', name: 'set', parent: 0, nameRange: [4, 9, 4, 12], range: [4, 2, 4, 64], detail: 'action set(goal: Int) only owner',
      abi: true, context: { state: true, tx: true } },
    { id: 5, kind: 'param', name: 'goal', parent: 4, nameRange: [4, 13, 4, 17], range: [4, 13, 4, 22], type: 'Int', scope: [4, 34, 4, 64], abi: true },
  ],
  // `owner` in `only owner` refers to the state field; `goal` in `{ ...state, goal }` is a shorthand: the state field
  // goal (as a field of the State record) and the parameter goal (as the value).
  references: [[4, 29, 4, 34, 3, 0], [4, 56, 4, 60, 2, 1], [4, 56, 4, 60, 5, 1]],
  builtins: [[4, 47, 4, 52, 'state']],
  types: ['Int', 'State'],
  exprs: [[4, 56, 4, 60, 0, 0], [4, 43, 4, 62, 1, 0]],
  diagnostics: [],
});

test('definition and references follow the model\'s reference mapping; a shorthand names the value first', () => {
  const m = model();
  assert.deepEqual(definition(m, 'u', { line: 4, character: 30 }).range.start, { line: 3, character: 21 });
  const refs = references(m, 'u', { line: 3, character: 22 }, true).map((r) => r.range.start.character);
  assert.deepEqual(refs, [21, 29]);
  // On the shorthand: the parameter (the value) first.
  assert.deepEqual(definition(m, 'u', { line: 4, character: 57 }).range.start, { line: 4, character: 13 });
  assert.deepEqual(references(m, 'u', { line: 4, character: 14 }, false).map((r) => r.range.start.character), [56]);
});

test('hover: the declaration, the shorthand\'s other meaning, the ABI note, built-ins; workspace text escaped', () => {
  const m = model();
  const h = hover(m, { line: 4, character: 57 }, null).contents.value;
  assert.match(h, /goal: Int/);
  assert.match(h, /also the field `goal`/);
  assert.match(h, /ABI manifest/);
  assert.match(hover(m, { line: 4, character: 48 }, null).contents.value, /input state/);
  assert.equal(escapeMarkdown('[x](javascript:y) *b*'), '\\[x\\]\\(javascript:y\\) \\*b\\*');
});

test('hover: a time value says so (D11)', () => {
  const m = model();
  m.exprs[1] = [4, 43, 4, 62, 1, 2];
  assert.match(hover(m, { line: 4, character: 44 }, null).contents.value, /time value \(D11\)/);
});

test('semantic tokens: kinds from the declarations and references, readonly bindings, the legend', () => {
  const m = model();
  const { data } = semanticTokens(m);
  const toks = [];
  let line = 0;
  let col = 0;
  for (let i = 0; i < data.length; i += 5) {
    line += data[i];
    col = data[i] === 0 ? col + data[i + 1] : data[i + 1];
    toks.push({ line, col, len: data[i + 2], type: TOKEN_TYPES[data[i + 3]], mods: TOKEN_MODIFIERS.filter((_, k) => data[i + 4] & (1 << k)) });
  }
  const at = (l, c) => toks.find((t) => t.line === l && t.col === c);
  assert.equal(at(3, 10).type, 'property');
  assert.deepEqual(at(3, 10).mods, ['declaration']);
  assert.equal(at(4, 13).type, 'parameter');
  assert.deepEqual(at(4, 13).mods, ['declaration', 'readonly']);
  assert.equal(at(4, 29).type, 'property');
  assert.equal(at(4, 47).type, 'variable');
  assert.deepEqual(at(4, 47).mods, ['readonly', 'defaultLibrary']);
});

test('rename: a shorthand keeps its other meaning; ABI names are flagged; names are checked', () => {
  const m = model();
  const p = renamePlan(m, { line: 4, character: 14 }, 'target');
  assert.equal(p.abi, true);
  assert.deepEqual(p.edits.map((e) => [e.range.start.character, e.newText]), [[13, 'target'], [56, 'goal: target']]);
  const f = renamePlan(m, { line: 3, character: 11 }, 'aim');
  assert.deepEqual(f.edits.map((e) => [e.range.start.character, e.newText]), [[10, 'aim'], [56, 'aim: goal']]);
  assert.match(renamePlan(m, { line: 4, character: 14 }, '1x').error, /not a name/);
  assert.match(renamePlan(m, { line: 4, character: 14 }, 'match').error, /keyword/);
  assert.match(renamePlan(m, { line: 4, character: 48 }, 'x').error, /nothing to rename/);
});

test('renamePreserves: every reference resolves to the same declaration at its shifted place, and no diagnostic is added', () => {
  const before = model();
  const plan = renamePlan(before, { line: 4, character: 14 }, 'target');
  const after = model();
  // `goal` (4 characters) at 13 becomes `target` (6): references after it on line 4 move by 2; the shorthand at 56
  // becomes `goal: target` (its references stay at 56: the field and, at 62, the value).
  after.references = [[4, 31, 4, 36, 3, 0], [4, 58, 4, 62, 2, 0], [4, 64, 4, 70, 5, 0]];
  assert.deepEqual(renamePreserves(before, after, plan.edits), { ok: true });
  // A reference that now resolves elsewhere is refused.
  after.references[2] = [4, 64, 4, 70, 3, 0];
  assert.equal(renamePreserves(before, after, plan.edits).ok, false);
  const same = model();
  assert.deepEqual(renamePreserves(same, model(), []), { ok: true });
  const added = model();
  added.diagnostics = [{ severity: 'error', code: 'JSC0213', message: 'hides', range: [4, 13, 4, 19] }];
  assert.equal(renamePreserves(same, added, []).ok, false);
});

test('completion: scope, context-filtered built-ins, members after a dot, std imports with a command, modifiers, types', () => {
  const m = model();
  const text = 'jsc 2\npackage t\ncontract C {\n  state { goal: Int, owner: Key }\n  action set(goal: Int) only owner { return { ...state, goal } }\n}\n';
  const std = {
    rows: [{ module: 'std/crypto', name: 'sha2_256', signature: 'sha2_256(b: Bytes): Bytes' }, { module: 'prelude', name: 'min', signature: 'min(a: Int, b: Int): Int' }],
    types: ['Int', 'Bool', 'Bytes', 'Key', 'List', 'Map', 'Option'],
    members: { List: [{ name: 'length', kind: 'property', signature: 'Int' }] },
    modifiers: ['only', 'authorized', 'after', 'before', 'payable', 'open'],
  };
  const labels = (items) => items.map((i) => i.label);
  const inBody = completion(text, { line: 4, character: 44 }, m, std);
  for (const l of ['goal', 'owner', 'state', 'caller', 'min', 'Some', 'None', 'sha2_256']) assert.ok(labels(inBody).includes(l), l);
  const sha = inBody.find((i) => i.label === 'sha2_256');
  assert.deepEqual(sha.needsImport, { name: 'sha2_256', module: 'std/crypto' });
  assert.equal(sha.additionalTextEdits, undefined);
  // In a view: no transaction built-ins (JSC0415); in init: no state (JSC0414).
  m.declarations[4].context = { state: true, tx: false };
  assert.ok(!labels(completion(text, { line: 4, character: 44 }, m, std)).includes('caller'));
  m.declarations[4].context = { state: false, tx: true };
  const init = labels(completion(text, { line: 4, character: 44 }, m, std));
  assert.ok(!init.includes('state') && !init.includes('owner'));
  // After `state.`: the state fields.
  const dotText = text.replace('{ ...state, goal }', 'state.');
  m.declarations[4].context = { state: true, tx: true };
  assert.deepEqual(labels(completion(dotText, { line: 4, character: 50 }, m, std)), ['goal', 'owner']);
  // Inside an import's braces: the module's rows.
  const imp = 'jsc 2\npackage t\nimport { s } from "std/crypto"\n';
  assert.deepEqual(labels(completion(imp, { line: 2, character: 10 }, m, std)), ['sha2_256']);
  // After a method's parameters: the modifiers; in a type position: the types.
  const hdr = 'jsc 2\npackage t\ncontract C {\n  action set(goal: Int) \n';
  assert.ok(labels(completion(hdr, { line: 3, character: 24 }, m, std)).includes('payable'));
  const ty = 'jsc 2\npackage t\ncontract C {\n  action set(goal: \n';
  assert.ok(labels(completion(ty, { line: 3, character: 19 }, m, std)).includes('Key'));
  // A text edit replaces only the word being completed on the cursor's line.
  const typed = text.replace('{ ...state, goal }', 'go');
  const it = completion(typed, { line: 4, character: 46 }, m, std).find((i) => i.label === 'goal');
  assert.deepEqual(it.textEdit.range, { start: { line: 4, character: 44 }, end: { line: 4, character: 46 } });
});

test('importEdit: into the module\'s import, else a new line after the imports', () => {
  const a = 'jsc 2\npackage t\nimport { sha2_256 } from "std/crypto"\ncontract C {}\n';
  assert.deepEqual(importEdit(a, 'blake2b_256', 'std/crypto'), { range: { start: { line: 2, character: 17 }, end: { line: 2, character: 17 } }, newText: ', blake2b_256' });
  assert.equal(importEdit(a, 'sha2_256', 'std/crypto'), null);
  assert.deepEqual(importEdit(a, 'length', 'std/bytes').range.start, { line: 3, character: 0 });
  const b = 'jsc 2\npackage t\ncontract C {}\n';
  assert.deepEqual(importEdit(b, 'length', 'std/bytes'), { range: { start: { line: 2, character: 0 }, end: { line: 2, character: 0 } }, newText: 'import { length } from "std/bytes"\n' });
});

test('invisible characters: every JSC0145 and JSC0148 the model reports, with its code point', () => {
  const text = 'jsc 2\nconst s = "a​b\u0007"\n';
  const m = normalise({ declarations: [], diagnostics: [
    { severity: 'error', code: 'JSC0145', message: '', range: [1, 12, 1, 13] },
    { severity: 'error', code: 'JSC0148', message: '', range: [1, 14, 1, 15] },
    { severity: 'error', code: 'JSC0147', message: '', range: [1, 0, 1, 1] },
  ] });
  assert.deepEqual(invisibleOccurrences(m, text).map((o) => o.char), ['U+200B', 'U+0007']);
});
