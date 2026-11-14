// The TextMate grammar (syntaxes/jsc.tmLanguage.json) tokenises the repository's .jsc files with the scopes the docs'
// highlighter (Shiki) and VS Code rely on (ADR-0018 D13).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import vsctm from 'vscode-textmate';
import oniguruma from 'vscode-oniguruma';

const require = createRequire(import.meta.url);
const here = (p) => fileURLToPath(new URL(p, import.meta.url));

const wasm = readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm'));
await oniguruma.loadWASM(wasm.buffer.slice(wasm.byteOffset, wasm.byteOffset + wasm.byteLength));
const registry = new vsctm.Registry({
  onigLib: Promise.resolve({
    createOnigScanner: (patterns) => new oniguruma.OnigScanner(patterns),
    createOnigString: (s) => new oniguruma.OnigString(s),
  }),
  loadGrammar: async (scope) => (scope === 'source.jsc'
    ? vsctm.parseRawGrammar(readFileSync(here('../syntaxes/jsc.tmLanguage.json'), 'utf8'), 'jsc.tmLanguage.json')
    : null),
});
const grammar = await registry.loadGrammar('source.jsc');

/** Every token of a source: { text, scopes (innermost last), line }. */
function tokens(source) {
  const out = [];
  let stack = vsctm.INITIAL;
  source.split('\n').forEach((line, i) => {
    const r = grammar.tokenizeLine(line, stack);
    for (const t of r.tokens) out.push({ text: line.slice(t.startIndex, t.endIndex), scopes: t.scopes, line: i + 1 });
    stack = r.ruleStack;
  });
  return out;
}

/** The innermost scope of the first token whose text is `text` (after `after` tokens with that text). */
function scopeOf(toks, text, nth = 0) {
  const found = toks.filter((t) => t.text === text);
  assert.ok(found[nth], `no token '${text}' (#${nth})`);
  return found[nth].scopes.at(-1);
}

const example = (name) => readFileSync(here(`../../../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/${name}`), 'utf8');

test('.jsc 2: the header, declarations, effects, calls, literals and comments', () => {
  const t = tokens(example('Ledger.jsc'));
  assert.equal(scopeOf(t, 'jsc'), 'keyword.other.directive.version.jsc');
  assert.equal(scopeOf(t, '2'), 'constant.numeric.version.jsc');
  assert.equal(scopeOf(t, 'package'), 'keyword.other.package.jsc');
  assert.equal(scopeOf(t, 'import'), 'keyword.control.import.jsc');
  assert.equal(scopeOf(t, 'length'), 'support.function.std.jsc');
  assert.equal(scopeOf(t, 'enum'), 'storage.type.jsc');
  assert.equal(scopeOf(t, 'Phase'), 'entity.name.type.jsc');
  assert.equal(scopeOf(t, 'contract'), 'storage.type.jsc');
  assert.equal(scopeOf(t, 'Ledger'), 'entity.name.type.jsc');
  assert.equal(scopeOf(t, 'state'), 'storage.type.state.jsc');
  assert.equal(scopeOf(t, 'BigMap'), 'support.type.primitive.jsc');
  assert.equal(scopeOf(t, 'action'), 'storage.type.function.jsc');
  assert.equal(scopeOf(t, 'pause'), 'entity.name.function.jsc');
  assert.equal(scopeOf(t, 'only'), 'storage.modifier.jsc');
  assert.equal(scopeOf(t, 'require'), 'keyword.other.effect.jsc');
  assert.equal(scopeOf(t, 'pay'), 'keyword.other.effect.jsc');
  assert.equal(scopeOf(t, 'match'), 'keyword.control.conditional.jsc');
  assert.equal(scopeOf(t, 'caller'), 'variable.language.jsc');
  assert.equal(scopeOf(t, 'balance'), 'variable.language.jsc');
  assert.equal(scopeOf(t, 'const'), 'storage.type.jsc');
  assert.equal(scopeOf(t, 'return'), 'keyword.control.flow.jsc');
  assert.equal(scopeOf(t, '=>'), 'keyword.operator.arrow.jsc');
  assert.equal(scopeOf(t, '...'), 'keyword.operator.spread.jsc');
  assert.equal(scopeOf(t, '??'), 'keyword.operator.coalesce.jsc');
  assert.equal(scopeOf(t, 'set'), 'entity.name.function.member.jsc');
  assert.equal(scopeOf(t, 'empty'), 'entity.name.function.member.jsc');
  assert.ok(t.some((x) => x.scopes.includes('comment.line.double-slash.jsc')));
  assert.ok(t.some((x) => x.scopes.includes('string.quoted.double.jsc')));
});

test('.jsc 2: ada, bytes, escapes, Option and the time prelude', () => {
  const t = tokens([
    'jsc 1', 'package p', '/* a block', '   comment */',
    'fn f(o: Option<Int>): Bytes = #00ff', 'contract C(minAda = 2 ada) {',
    '  state { n: Int }', '  init() { require(after(1) && n == 0, "say \\"hi\\"\\n"); return { n: Some(1) ?? None } }', '}',
  ].join('\n'));
  assert.equal(scopeOf(t, '#00ff'), 'constant.numeric.bytes.jsc');
  assert.equal(scopeOf(t, 'ada'), 'keyword.other.unit.ada.jsc');
  assert.equal(scopeOf(t, '\\"'), 'constant.character.escape.jsc');
  assert.equal(scopeOf(t, '\\n'), 'constant.character.escape.jsc');
  assert.equal(scopeOf(t, 'after'), 'support.function.builtin.jsc');
  assert.equal(scopeOf(t, 'Some'), 'support.function.builtin.jsc');
  assert.equal(scopeOf(t, 'None'), 'constant.language.option.jsc');
  assert.equal(scopeOf(t, 'Option'), 'support.type.primitive.jsc');
  assert.ok(t.filter((x) => x.line === 3 || x.line === 4).every((x) => x.scopes.includes('comment.block.jsc')));
});

test('Jar and Points: modifiers, views and map updates', () => {
  const t = tokens(example('Jar.jsc'));
  assert.equal(scopeOf(t, 'state'), 'storage.type.state.jsc');
  assert.equal(scopeOf(t, 'batched'), 'storage.modifier.jsc');
  assert.equal(scopeOf(t, 'payable'), 'storage.modifier.jsc');
  assert.equal(scopeOf(t, 'pay'), 'keyword.other.effect.jsc');
  assert.equal(scopeOf(t, 'received'), 'variable.language.jsc');
  assert.equal(scopeOf(t, 'view'), 'storage.type.function.jsc');
  assert.equal(scopeOf(t, 'reached'), 'entity.name.function.jsc');
  const points = tokens(example('Points.jsc'));
  assert.equal(scopeOf(points, 'const'), 'storage.type.jsc');
});
