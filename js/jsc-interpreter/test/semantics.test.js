// The hand-written vectors of docs/reference/jsc-2-semantics.md (ADR-0018 D15), section by section. Each expected value
// is stated here from the document, not recorded from an implementation. Two kinds: expressions built as IR nodes in
// this file, and calls of the example contracts through their IR (test/fixtures, the current exports: JscV2IrTest
// fails when they are stale).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { load, Refused, Limit, NONE, Some, some, Rec, MapV, BigMapV, hex, fromHex, toJson, runCall } from '../src/index.js';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const fixture = (name) => load(readFileSync(new URL(`./fixtures/${name}.ir.json`, import.meta.url), 'utf8'));

// ------------------------------------------------------------------ IR built by hand

const at = [1, 1];
const INT = 'Int', BOOL = 'Bool', BYTES = 'Bytes';
const lit = (v) => (typeof v === 'bigint' ? { node: 'Lit', value: v.toString(), type: INT, taint: 0, at }
  : typeof v === 'boolean' ? { node: 'Lit', value: v, type: BOOL, taint: 0, at }
    : { node: 'Lit', value: v, type: BYTES, taint: 0, at });   // a hex string
const b = (h) => lit(h);
const node = (kind, fields, type) => ({ node: kind, ...fields, type, taint: 0, at });
const arith = (op, l, r) => node('Arith', { op, left: l, right: r }, INT);
const cmp = (op, l, r) => node('Compare', { op, left: l, right: r }, BOOL);
const eq = (l, r, operand) => node('Equals', { negate: false, left: l, right: r, operand }, BOOL);
const std = (module, name, args, type) => node('CallStd', { module, name, args }, type);
const list = (items, elem) => node('ListLit', { items, rest: null }, { list: elem });
const someOf = (v, t) => node('SomeOf', { value: v }, { option: t });
const none = (t) => node('NoneLit', {}, { option: t });

/** A program with the given records, enums and fns, and no methods: for evaluating expressions. */
function program({ records = [], enums = [], fns = [], libraries = [] } = {}) {
  return load(JSON.stringify({
    ir: 2, language: 'jsc 2', sourceSha256: '0'.repeat(64), package: 't', contract: 'T', source: 'T.jsc', options: [], records, enums,
    state: { name: 'State', fields: [], at }, timeFields: [], recursiveFns: [], libraries, javaLibraries: [], fns, methods: [], warnings: [],
  }));
}

const P = program();
const TX = { caller: fromHex('0a'.repeat(28)), received: 0n, balance: 0n, validFrom: 1000n, validTo: 2000n };
const ev = (e, p = P) => p.evalExpr(e, { tx: TX });
const refused = (e, p = P) => assert.throws(() => ev(e, p), Refused);

// ------------------------------------------------------------------ §1.1 and §3.3: two orders of byte strings

test('§1.1 canonical key order is length first; §3.3 < on Bytes is lexicographic', () => {
  assert.equal(ev(cmp('<', b('01'), b('0100'))), true);
  assert.equal(ev(cmp('<', b('0100'), b('02'))), true);
  assert.equal(ev(cmp('<', b('7f'), b('80'))), true, 'unsigned');
  assert.equal(ev(cmp('<', b('ff'), b('0000'))), false);
  assert.equal(ev(cmp('<=', b(''), b(''))), true);
  // In a map, #ff comes before #0000 (shorter first), and #0001 before #0100.
  const m = MapV.EMPTY.set(fromHex('0100'), 1n).set(fromHex('0000'), 2n).set(fromHex('ff'), 3n).set(fromHex('0001'), 4n);
  assert.deepEqual(m.keys.map(hex), ['ff', '0000', '0001', '0100']);
  assert.deepEqual(m.set(fromHex('0000'), 9n).values, [3n, 9n, 4n, 1n], 'set replaces in place');
  assert.equal(m.delete(fromHex('aa')), m, 'deleting an absent key gives the same map');
});

// ------------------------------------------------------------------ §3.2 integers

test('§3.2 / and % truncate toward zero; a zero divisor fails', () => {
  const rows = [[7n, 3n, 2n, 1n], [-7n, 3n, -2n, -1n], [7n, -3n, -2n, 1n], [-7n, -3n, 2n, -1n], [0n, 5n, 0n, 0n], [-1n, 2n, 0n, -1n]];
  for (const [a, d, q, r] of rows) {
    assert.equal(ev(arith('/', lit(a), lit(d))), q, `${a} / ${d}`);
    assert.equal(ev(arith('%', lit(a), lit(d))), r, `${a} % ${d}`);
  }
  refused(arith('/', lit(7n), lit(0n)));
  refused(arith('%', lit(7n), lit(0n)));
  const big = 2n ** 130n + 12345n;
  assert.equal(ev(arith('*', lit(big), lit(big))), big * big, 'unbounded');
  assert.equal(ev(node('Neg', { operand: lit(-big) }, INT)), big);
  assert.equal(ev(node('Prelude', { op: 'abs', args: [lit(-3n)] }, INT)), 3n);
  assert.equal(ev(node('Prelude', { op: 'min', args: [lit(-3n), lit(2n)] }, INT)), -3n);
  assert.equal(ev(node('Prelude', { op: 'max', args: [lit(-3n), lit(2n)] }, INT)), 2n);
});

// ------------------------------------------------------------------ §2.1 laziness

test('§2.1 only the lazy positions decide what runs', () => {
  const boom = eq(arith('/', lit(1n), lit(0n)), lit(1n), INT);   // fails when evaluated
  assert.equal(ev(node('Logic', { op: '&&', left: lit(false), right: boom }, BOOL)), false);
  assert.equal(ev(node('Logic', { op: '||', left: lit(true), right: boom }, BOOL)), true);
  refused(node('Logic', { op: '&&', left: lit(true), right: boom }, BOOL));
  assert.equal(ev(node('Cond', { cond: lit(true), then: lit(1n), otherwise: arith('/', lit(1n), lit(0n)) }, INT)), 1n);
  refused(node('Cond', { cond: lit(false), then: lit(1n), otherwise: arith('/', lit(1n), lit(0n)) }, INT));
  assert.equal(ev(node('Coalesce', { option: someOf(lit(4n), INT), default: arith('/', lit(1n), lit(0n)) }, INT)), 4n);
  refused(node('Coalesce', { option: none(INT), default: arith('/', lit(1n), lit(0n)) }, INT));
  // A strict position always runs: a failing element of a list literal fails, even if nothing reads it.
  refused(node('ListProp', { op: 'isEmpty', list: list([arith('/', lit(1n), lit(0n))], INT) }, BOOL));
});

// ------------------------------------------------------------------ §3.4 equality

const SHARE = { name: 'Share', fields: [{ name: 'to', type: 'Key' }, { name: 'bps', type: INT }], at };
const PHASE = {
  name: 'Phase', at,
  variants: [{ name: 'Open', fields: [], at }, { name: 'Closed', fields: [{ name: 'day', type: INT }], at }],
};
const RE = program({ records: [SHARE], enums: [PHASE] });

test('§3.4 equality is structural at the operand type, Booleans by value', () => {
  const P2 = RE;
  assert.equal(P2.same([true, false], [true, false], { list: BOOL }), true);
  assert.equal(P2.same([true], [true, false], { list: BOOL }), false);
  assert.equal(P2.same(NONE, some(false), { option: BOOL }), false, 'Some(false) differs from None');
  assert.equal(P2.same(some(fromHex('01')), some(fromHex('01')), { option: BYTES }), true, 'by content, distinct arrays');
  const share = (k, n) => new Rec('Share', [fromHex(k.repeat(28)), n]);
  assert.equal(P2.same(share('a1', 1n), share('a1', 1n), { record: 'Share' }), true);
  assert.equal(P2.same(share('a1', 1n), share('a1', 2n), { record: 'Share' }), false);
  assert.equal(P2.same(new Rec('Open', []), new Rec('Open', []), { enum: 'Phase' }), true);
  assert.equal(P2.same(new Rec('Open', []), new Rec('Closed', [1n]), { enum: 'Phase' }), false);
  assert.equal(P2.same(new Rec('Closed', [1n]), new Rec('Closed', [2n]), { enum: 'Phase' }), false);
  const m1 = MapV.EMPTY.set(fromHex('01'), 1n), m2 = MapV.EMPTY.set(fromHex('01'), 1n);
  assert.equal(P2.same(m1, m2, { map: [BYTES, INT] }), true);
  assert.equal(P2.same(m1, m2.set(fromHex('02'), 1n), { map: [BYTES, INT] }), false);
  // contains and indexOf use the same equality.
  assert.equal(ev(node('Contains', { list: list([b('0102'), b('03')], BYTES), value: b('03'), elem: BYTES }, BOOL)), true);
  assert.deepEqual(ev(std('std/list', 'indexOf', [list([lit(5n), lit(6n), lit(6n)], INT), lit(6n)], { option: INT })), some(1n));
});

// ------------------------------------------------------------------ §3.5 match

test('§3.5 match selects the arm of the constructor and binds its fields', () => {
  const x = { id: 1, name: 'x', type: INT, taint: 0, at };
  const arms = [
    { ctors: ['Some'], fields: ['value'], binds: [x], body: { stmts: [], result: arith('+', node('LocalRef', { local: 1 }, INT), lit(1n)), at }, at },
    { ctors: ['None'], fields: [], binds: [], body: { stmts: [], result: lit(0n), at }, at },
  ];
  assert.equal(ev(node('Match', { scrutinee: someOf(lit(41n), INT), arms }, INT)), 42n);
  assert.equal(ev(node('Match', { scrutinee: none(INT), arms }, INT)), 0n);
  // An enum: the `_` arm lists every remaining variant.
  const d = { id: 2, name: 'd', type: INT, taint: 0, at };
  const enumArms = [
    { ctors: ['Closed'], fields: ['day'], binds: [d], body: { stmts: [], result: node('LocalRef', { local: 2 }, INT), at }, at },
    { ctors: ['Open'], fields: [], binds: [], body: { stmts: [], result: lit(-1n), at }, at },
  ];
  const closed = node('Variant', { enum: 'Phase', variant: 'Closed', values: [lit(9n)] }, { enum: 'Phase' });
  assert.equal(ev(node('Match', { scrutinee: closed, arms: enumArms }, INT), RE), 9n);
  // Through a contract: Ledger's phaseCode is 0 when open, 1 + length(reason) when paused, 100 + day when closed.
  const ledger = fixture('Ledger');
  const state = (phase) => new Rec('State', [fromHex('0a'.repeat(28)), phase, MapV.EMPTY, BigMapV.EMPTY, 0n]);
  assert.equal(ledger.call('phaseCode', state(new Rec('Open', [])), [], null).value, 0n);
  assert.equal(ledger.call('phaseCode', state(new Rec('Paused', [fromHex('aabbcc')])), [], null).value, 4n);
  assert.equal(ledger.call('phaseCode', state(new Rec('Closed', [7n, fromHex('0a'.repeat(28))])), [], null).value, 107n);
});

// ------------------------------------------------------------------ §3.6 lists and ranges

test('§3.6 lists: take and drop clamp and refuse a negative count; ranges are ascending and may be empty', () => {
  const xs = list([lit(1n), lit(2n), lit(3n)], INT);
  assert.deepEqual(ev(node('ListArg', { op: 'take', list: xs, arg: lit(5n) }, { list: INT })), [1n, 2n, 3n]);
  assert.deepEqual(ev(node('ListArg', { op: 'drop', list: xs, arg: lit(2n) }, { list: INT })), [3n]);
  refused(node('ListArg', { op: 'take', list: xs, arg: lit(-1n) }, { list: INT }));
  refused(node('ListArg', { op: 'drop', list: xs, arg: lit(-1n) }, { list: INT }));
  assert.deepEqual(ev(node('ListProp', { op: 'head', list: list([], INT) }, { option: INT })), NONE);
  assert.equal(ev(node('ListProp', { op: 'sum', list: xs }, INT)), 6n);
  assert.deepEqual(ev(node('ListProp', { op: 'reverse', list: xs }, { list: INT })), [3n, 2n, 1n]);
  // Through Shelf: squares(4) = 0 + 1 + 4 + 9 = 14; evens(7) = [0, 2, 4, 6]; an empty range.
  const shelf = fixture('Shelf');
  const s = new Rec('State', [fromHex('0a'.repeat(28)), MapV.EMPTY, BigMapV.EMPTY, NONE]);
  assert.equal(shelf.call('squares', s, [4n], null).value, 14n);
  assert.deepEqual(shelf.call('evens', s, [7n], null).value, [0n, 2n, 4n, 6n]);
  assert.deepEqual(shelf.call('evens', s, [-3n], null).value, []);
  assert.equal(shelf.call('squares', s, [0n], null).value, 0n);
});

// ------------------------------------------------------------------ §3.7 maps and §4 checked inputs

test('§3.7 Map.set refuses a key over 64 bytes; §4 a map input must be canonical, unique and of 28-byte keys', () => {
  const set = (k) => node('MapOp', { op: 'set', map: node('MapEmpty', {}, { map: [BYTES, INT] }), args: [b(k), lit(1n)] }, { map: [BYTES, INT] });
  assert.equal(ev(set('ab'.repeat(64))).keys.length, 1);
  refused(set('ab'.repeat(65)));
  const ledger = fixture('Ledger');
  const ann = fromHex('a1'.repeat(28)), ben = fromHex('b1'.repeat(28));
  const tx = { ...TX, caller: fromHex('0a'.repeat(28)) };
  const ok = ledger.call('init', null, [MapV.raw([[ann, 100n], [ben, 50n]])], tx);
  assert.deepEqual(ok.value.fields[2].keys, [ann, ben]);
  for (const bad of [
    MapV.raw([[ben, 1n], [ann, 1n]]),                // out of order
    MapV.raw([[ann, 1n], [ann, 2n]]),                // a duplicate key
    MapV.raw([[fromHex('00'.repeat(27)), 1n]]),      // a 27-byte Key
    MapV.raw([[ann, 0n]]),                           // well formed, but the body's require refuses it
  ]) {
    assert.throws(() => ledger.call('init', null, [bad], tx), Refused);
  }
  // A record holding a Key: Pledges' shares.
  const pledges = fixture('Pledges');
  const share = (k, n) => new Rec('Share', [k, n]);
  assert.throws(() => pledges.call('init', null, [10_000_000n, 1n, [share(fromHex('00'.repeat(27)), 10_000n)], fromHex('010203')], tx), Refused);
});

// ------------------------------------------------------------------ §3.8 BigMaps

test('§3.8 BigMaps: absence vs empty bytes, 0 removes, and the 8,192-byte bound on stored integers', () => {
  const shelf = fixture('Shelf');
  const owner = fromHex('0a'.repeat(28));
  const s = new Rec('State', [owner, MapV.EMPTY, BigMapV.EMPTY, NONE]);
  const k = fromHex('6b');
  assert.deepEqual(shelf.call('noteOf', s, [k], null).value, NONE, 'absent');
  const written = shelf.call('note', s, [k, new Uint8Array(0)], { ...TX, caller: owner }).value;
  assert.deepEqual(toJson(written.fields[2]), { bigmap: [[{ bytes: '6b' }, { bytes: '' }]] }, 'present and empty');
  const stored = new Rec('State', [owner, MapV.EMPTY, BigMapV.of(written.fields[2].now), NONE]);
  assert.deepEqual(shelf.call('noteOf', stored, [k], null).value, some(new Uint8Array(0)));
  assert.throws(() => shelf.call('forget', s, [k], { ...TX, caller: owner }), Refused, 'no such note');
  assert.equal(shelf.call('forget', stored, [k], { ...TX, caller: owner }).value.fields[2].now.size, 0);
  // Ledger: a transfer of everything sets the sender's balance to 0, which removes it.
  const ledger = fixture('Ledger');
  const ann = fromHex('a1'.repeat(28)), ben = fromHex('b1'.repeat(28));
  const st = new Rec('State', [owner, new Rec('Open', []), MapV.EMPTY, BigMapV.of(new Map([[hex(ann), 5n]])), 5n]);
  const next = ledger.call('transfer', st, [ben, 5n], { ...TX, caller: ann }).value;
  assert.deepEqual(toJson(next.fields[3]), { bigmap: [[{ bytes: hex(ben) }, { int: '5' }]] });
  // A self-transfer reads its own debit through the chain: the balance is kept.
  const self = ledger.call('transfer', st, [ann, 2n], { ...TX, caller: ann }).value;
  assert.deepEqual(toJson(self.fields[3]), { bigmap: [[{ bytes: hex(ann) }, { int: '5' }]] });
  // Bounds: 2^65536 − 1 is stored (8,192 bytes), 2^65536 is refused, also when the write is only read through.
  const bounds = fixture('Bounds');
  const b0 = new Rec('State', [owner, BigMapV.EMPTY]);
  assert.equal(bounds.call('write', b0, [64n, 1n], null).value.fields[1].now.get(hex(new TextEncoder().encode('k'))), 2n ** 65536n - 1n);
  assert.throws(() => bounds.call('write', b0, [64n, 0n], null), Refused);
  assert.throws(() => bounds.call('discarded', b0, [64n, 0n], null), Refused);
  assert.equal(bounds.call('discarded', b0, [63n, 0n], null).value.fields[1].now.get(hex(new TextEncoder().encode('k'))), 1n);
  // isEmpty is "empty before the call": Ledger's settle refuses while tokens remain.
  assert.throws(() => ledger.call('settle', st, [], { ...TX, balance: 10n }), Refused);
});

// ------------------------------------------------------------------ §2.3 payouts and §2 modifiers

test('§2.3 pay evaluates its recipient, then its amount; skips 0; refuses a negative amount', () => {
  const v = fixture('Vectors');
  const ann = fromHex('a1'.repeat(28)), owner = fromHex('0a'.repeat(28));
  const s = new Rec('State', [owner, new Uint8Array(0), 0n, 0n, 0n, 0n, [], NONE]);
  const tx = { ...TX, balance: 10_000_000n };
  assert.deepEqual(v.call('give', s, [ann, 1_000_000n], tx).payouts, [{ to: ann, lovelace: 1_000_000n }]);
  assert.deepEqual(v.call('give', s, [ann, 0n], tx).payouts, [], 'zero is skipped');
  assert.throws(() => v.call('give', s, [ann, -1n], tx), Refused);
  assert.throws(() => v.call('give', s, [fromHex('00'), 0n], tx), Refused, 'a failing recipient fails even for 0');
  // Ledger's settle pays in source order: half to the closer, the rest to the admin (5,000,000 / 5,000,001 of 10,000,001).
  const ledger = fixture('Ledger');
  const closed = new Rec('State', [owner, new Rec('Closed', [3n, ann]), MapV.EMPTY, BigMapV.EMPTY, 0n]);
  assert.deepEqual(ledger.call('settle', closed, [], { ...TX, caller: owner, balance: 10_000_001n }).payouts,
    [{ to: ann, lovelace: 5_000_000n }, { to: owner, lovelace: 5_000_001n }]);
  // Modifiers are the validator's: Counter2's reset is `only owner`, and the call itself does not check it.
  const counter = fixture('Counter2');
  assert.deepEqual(counter.call('reset', new Rec('State', [7n, owner]), [], { ...TX, caller: owner }).value.fields, [0n, owner]);
});

// ------------------------------------------------------------------ §3.9 fns

test('§3.9 a fn sees only its parameters, and may call itself', () => {
  const n = { id: 1, name: 'n', type: INT, taint: 0, at };
  const nRef = node('LocalRef', { local: 1 }, INT);
  const fact = {
    name: 'fact', params: [n], result: INT, at,
    body: {
      stmts: [], at,
      result: node('Cond', {
        cond: cmp('<=', nRef, lit(1n)), then: lit(1n),
        otherwise: arith('*', nRef, node('CallFn', { fn: 'fact', args: [arith('-', nRef, lit(1n))] }, INT)),
      }, INT),
    },
  };
  const p = program({ fns: [fact] });
  assert.equal(p.callFn('fact', [20n]), 2432902008176640000n);
  assert.equal(ev(node('CallFn', { fn: 'fact', args: [lit(25n)] }, INT), p), 15511210043330985984000000n);
  // §2.4: deeper than this thread's stack is no answer (a Limit), never a refusal.
  assert.throws(() => p.callFn('fact', [10n ** 7n]), (e) => e instanceof Limit && !(e instanceof Refused));
});

/** A view go(n) = down(n), with fn down(n) = n <= 0 ? 0 : 1 + down(n - 1): the security review's S1 contract, as IR. */
function deepProgram() {
  const n = { id: 1, name: 'n', type: INT, taint: 0, at };
  const ref = node('LocalRef', { local: 1 }, INT);
  const down = {
    name: 'down', params: [n], result: INT, at,
    body: { stmts: [], at, result: node('Cond', { cond: cmp('<=', ref, lit(0n)), then: lit(0n),
      otherwise: arith('+', lit(1n), node('CallFn', { fn: 'down', args: [arith('-', ref, lit(1n))] }, INT)) }, INT) },
  };
  const k = { id: 2, name: 'k', type: INT, taint: 0, at };
  const go = { kind: 'view', name: 'go', params: [k], mods: [], checked: [], usesCtx: false, pays: false, result: INT, at,
    body: { stmts: [], at, result: node('CallFn', { fn: 'down', args: [node('LocalRef', { local: 2 }, INT)] }, INT) } };
  return JSON.stringify({
    ir: 2, language: 'jsc 2', sourceSha256: '0'.repeat(64), package: 't', contract: 'Deep', source: 'Deep.jsc', options: [], records: [],
    enums: [], state: { name: 'State', fields: [], at }, timeFields: [], recursiveFns: ['down'], libraries: [], javaLibraries: [], fns: [down], methods: [go], warnings: [],
  });
}

test('§2.4 recursion the budget allows gets an answer: run-vectors evaluates on a deep stack', () => {
  const text = deepProgram();
  const p = load(text);
  assert.equal(p.call('go', new Rec('State', []), [400n], null).value, 400n);
  // The security review's repro: the chain accepts go(3000) (cpu 1,989,363,435, mem 7,827,245). Node's default stack
  // does not reach it, which is a Limit here; the runner's 512 MiB worker stack does.
  assert.throws(() => p.call('go', new Rec('State', []), [3000n], null), Limit);
  const dir = mkdtempSync(join(tmpdir(), 'jsc-deep-'));
  writeFileSync(join(dir, 'Deep.ir.json'), text);
  const calls = [3000n, 20000n].map((v) => ({ method: 'go', state: { rec: 'State', fields: [] }, args: [{ int: v.toString() }], tx: null }));
  writeFileSync(join(dir, 'calls.json'), JSON.stringify(calls));
  execFileSync(process.execPath, [new URL('../bin/run-vectors.js', import.meta.url).pathname, join(dir, 'Deep.ir.json'), join(dir, 'calls.json'),
    join(dir, 'answers.json')]);
  const answers = JSON.parse(readFileSync(join(dir, 'answers.json'), 'utf8'));
  assert.deepEqual(answers.map((a) => a.ok?.value), [{ int: '3000' }, { int: '20000' }]);
});

test('§2.5 a refusal has a class: require with its message, input with the parameter, domain with the operation', () => {
  const classOf = (f) => {
    try {
      f();
    } catch (e) {
      if (e instanceof Refused) return e.refusal;
      throw e;
    }
    return 'ok';
  };
  assert.equal(classOf(() => ev(arith('/', lit(1n), lit(0n)))), 'domain:/');
  assert.equal(classOf(() => ev(arith('%', lit(1n), lit(0n)))), 'domain:%');
  assert.equal(classOf(() => ev(std('std/bytes', 'slice', [b('01'), lit(-1n), lit(0n)], BYTES))), 'domain:slice');
  assert.equal(classOf(() => ev(std('std/bytes', 'byteAt', [b('01'), lit(1n)], INT))), 'domain:byteAt');
  assert.equal(classOf(() => ev(node('ListArg', { op: 'take', list: list([], INT), arg: lit(-1n) }, { list: INT }))), 'domain:List.take');
  assert.equal(classOf(() => ev(node('Prelude', { op: 'asKey', args: [b('01')] }, 'Key'))), 'domain:asKey');
  const counter = fixture('Counter2');
  const s = new Rec('State', [1n, fromHex('0a'.repeat(28))]);
  assert.equal(classOf(() => counter.call('increment', s, [0n], null)), 'require:13:5:increment must be positive');
  const ledger = fixture('Ledger');
  assert.equal(classOf(() => ledger.call('init', null, [MapV.raw([[fromHex('00'), 1n]])], TX)), 'input:limits');
  const v = fixture('Vectors');
  const vs = new Rec('State', [fromHex('0a'.repeat(28)), new Uint8Array(0), 0n, 0n, 0n, 0n, [], NONE]);
  assert.equal(classOf(() => v.call('give', vs, [fromHex('a1'.repeat(28)), -1n], TX)), 'domain:pay');
  assert.deepEqual(runCall(counter, { method: 'increment', state: { rec: 'State', fields: [{ int: '1' }, { bytes: '0a'.repeat(28) }] },
    args: [{ int: '0' }], tx: null }), { refused: 'increment must be positive', class: 'require:13:5:increment must be positive' });
});

// ------------------------------------------------------------------ §3.10 std rows at their domain edges

test('§3.10 std/bytes', () => {
  const s = (name, args, type = BYTES) => std('std/bytes', name, args, type);
  assert.equal(hex(ev(s('slice', [b('010203'), lit(1n), lit(5n)]))), '0203', 'clamped');
  assert.equal(hex(ev(s('slice', [b('010203'), lit(2n ** 31n - 1n), lit(1n)]))), '');
  refused(s('slice', [b('010203'), lit(-1n), lit(1n)]));
  refused(s('slice', [b('010203'), lit(0n), lit(2n ** 31n)]));
  assert.equal(ev(s('byteAt', [b('ff'), lit(0n)], INT)), 255n);
  refused(s('byteAt', [b('010203'), lit(3n)], INT));
  assert.equal(hex(ev(s('take', [b('010203'), lit(9n)]))), '010203');
  assert.equal(hex(ev(s('drop', [b('010203'), lit(9n)]))), '');
  refused(s('drop', [b('010203'), lit(2n ** 31n)]));
  assert.equal(ev(s('toInt', [b('')], INT)), 0n);
  assert.equal(ev(s('toInt', [b('0100')], INT)), 256n);
  assert.equal(new TextDecoder().decode(ev(s('toHex', [b('0aff')]))), '0aff');
  assert.equal(hex(ev(s('concat', [b('01'), b('0203')]))), '010203');
  assert.equal(ev(s('length', [b('010203')], INT)), 3n);
});

test('§3.10 std/math: floor div and mod, pow and expMod domains', () => {
  const m = (name, args) => std('std/math', name, args, INT);
  const rows = [[7n, 3n, 2n, 1n], [-7n, 3n, -3n, 2n], [7n, -3n, -3n, -2n], [-7n, -3n, 2n, -1n], [6n, 3n, 2n, 0n], [-6n, 3n, -2n, 0n]];
  for (const [a, d, q, r] of rows) {
    assert.equal(ev(m('div', [lit(a), lit(d)])), q, `div(${a}, ${d})`);
    assert.equal(ev(m('mod', [lit(a), lit(d)])), r, `mod(${a}, ${d})`);
  }
  refused(m('div', [lit(1n), lit(0n)]));
  refused(m('mod', [lit(1n), lit(0n)]));
  assert.equal(ev(m('pow', [lit(0n), lit(0n)])), 1n);
  assert.equal(ev(m('pow', [lit(2n), lit(1024n)])), 2n ** 1024n);
  refused(m('pow', [lit(2n), lit(1025n)]));
  refused(m('pow', [lit(2n), lit(-1n)]));
  assert.equal(ev(m('expMod', [lit(-2n), lit(3n), lit(5n)])), 2n, '(-8) mod 5');
  assert.equal(ev(m('expMod', [lit(7n), lit(0n), lit(1n)])), 0n);
  refused(m('expMod', [lit(2n), lit(-1n), lit(5n)]));
  refused(m('expMod', [lit(2n), lit(1n), lit(0n)]));
  assert.deepEqual([-5n, 0n, 9n].map((x) => ev(m('sign', [lit(x)]))), [-1n, 0n, 1n]);
});

test('§3.10 std/bits: bit 0 is the last byte\'s lowest; padding copies the longer tail', () => {
  const s = (name, args, type = BYTES) => std('std/bits', name, args, type);
  assert.equal(hex(ev(s('andBytes', [lit(true), b('0f0f'), b('ff')]))), '0f0f');
  assert.equal(hex(ev(s('andBytes', [lit(false), b('0f0f'), b('ff')]))), '0f');
  assert.equal(hex(ev(s('orBytes', [lit(true), b('01'), b('10ff')]))), '11ff');
  assert.equal(hex(ev(s('xorBytes', [lit(false), b('ff00'), b('0f')]))), 'f0');
  assert.equal(hex(ev(s('complement', [b('0f')]))), 'f0');
  assert.equal(ev(s('readBit', [b('0001'), lit(0n)], BOOL)), true);
  assert.equal(ev(s('readBit', [b('0100'), lit(8n)], BOOL)), true);
  refused(s('readBit', [b('01'), lit(8n)], BOOL));
  assert.equal(hex(ev(s('shift', [b('0180'), lit(1n)]))), '0300');
  assert.equal(hex(ev(s('shift', [b('0180'), lit(-8n)]))), '0001');
  assert.equal(hex(ev(s('shift', [b('ffff'), lit(16n)]))), '0000');
  assert.equal(hex(ev(s('rotate', [b('8001'), lit(1n)]))), '0003');
  assert.equal(hex(ev(s('rotate', [b('8001'), lit(-1n)]))), 'c000');
  assert.equal(hex(ev(s('rotate', [b(''), lit(5n)]))), '');
  refused(s('shift', [b('01'), lit(-(2n ** 31n))]));
  refused(s('rotate', [b('01'), lit(2n ** 31n)]));
  assert.equal(ev(s('countSetBits', [b('ff01')], INT)), 9n);
  assert.equal(ev(s('findFirstSetBit', [b('0000')], INT)), -1n);
  assert.equal(ev(s('findFirstSetBit', [b('0100')], INT)), 8n);
});

test('§3.10 std/list and std/time', () => {
  const xs = list([lit(4n), lit(5n)], INT);
  assert.deepEqual(ev(std('std/list', 'last', [xs], { option: INT })), some(5n));
  assert.deepEqual(ev(std('std/list', 'last', [list([], INT)], { option: INT })), NONE);
  assert.deepEqual(ev(std('std/list', 'nth', [xs, lit(1n)], { option: INT })), some(5n));
  assert.deepEqual(ev(std('std/list', 'nth', [xs, lit(2n)], { option: INT })), NONE);
  refused(std('std/list', 'nth', [xs, lit(-1n)], { option: INT }));
  // The window is [1000, 2000].
  assert.equal(ev(std('std/time', 'elapsedSince', [lit(400n)], INT)), 600n);
  assert.equal(ev(std('std/time', 'elapsedSince', [lit(5000n)], INT)), 0n);
  assert.equal(ev(std('std/time', 'elapsedSinceAtMost', [lit(400n)], INT)), 1600n);
  assert.equal(ev(std('std/time', 'windowStart', [], INT)), 1000n);
  assert.equal(ev(std('std/time', 'windowEnd', [], INT)), 2000n);
  // §3.9 after(t): the whole window at or after t; before(t): it ends by t.
  const pred = (after, t) => ev(node('TimePred', { after, threshold: lit(t) }, BOOL));
  assert.deepEqual([pred(true, 1000n), pred(true, 1001n), pred(false, 2000n), pred(false, 1999n)], [true, false, true, false]);
});

test('§3.10, §3.11 std/crypto: lengths fail, then libsodium answers', () => {
  const v = (k, m, s) => std('std/crypto', 'verifyEd25519', [b(k), b(m), b(s)], BOOL);
  const key = 'd75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a';
  const sig = 'e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b';
  assert.equal(ev(v(key, '', sig)), true);
  assert.equal(ev(v(key, '00', sig)), false);
  refused(v(key.slice(2), '', sig));
  refused(v(key, '', sig.slice(2)));
  assert.equal(hex(ev(std('std/crypto', 'blake2b_224', [b('')], BYTES))), '836cc68931c2e4e3e838602eca1902591d216837bafddfe6f0c8cb07');
  assert.equal(hex(ev(std('std/crypto', 'sha2_256', [b('616263')], BYTES))), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
});

test('§3.9 asKey and asHash: exactly 28 bytes', () => {
  assert.equal(ev(node('Prelude', { op: 'asKey', args: [b('11'.repeat(28))] }, 'Key')).length, 28);
  refused(node('Prelude', { op: 'asHash', args: [b('11'.repeat(27))] }, 'Hash'));
  refused(node('Prelude', { op: 'asKey', args: [b('11'.repeat(29))] }, 'Key'));
});

// ------------------------------------------------------------------ the format

test('a reader refuses another IR version, an unknown std row, and keeps answers in the vector form', () => {
  const text = readFileSync(new URL('./fixtures/Counter2.ir.json', import.meta.url), 'utf8');
  assert.throws(() => load(text.replace('"ir": 2,', '"ir": 1,')), /"ir" must be exactly 2/);
  const stdBytes = readFileSync(new URL('./fixtures/StdBytes.ir.json', import.meta.url), 'utf8');
  assert.throws(() => load(stdBytes.replace('"name": "sha2_256"', '"name": "keccak_256"')), /unknown std row std\/crypto.keccak_256/);
  const counter = load(text);
  const answer = runCall(counter, { method: 'increment', state: { rec: 'State', fields: [{ int: '1' }, { bytes: '0a'.repeat(28) }] },
    args: [{ int: '2' }], tx: null });
  assert.deepEqual(answer, { ok: { value: { rec: 'State', fields: [{ int: '3' }, { bytes: '0a'.repeat(28) }] }, payouts: [] } });
  assert.deepEqual(runCall(counter, { method: 'increment', state: answer.ok.value, args: [{ int: '0' }], tx: null }),
    { refused: 'increment must be positive', class: 'require:13:5:increment must be positive' });
  assert.ok(new Some(1n) instanceof Some);
});

test('a reader refuses up front what it does not know or cannot trust (review S3, F6, F7)', () => {
  const text = readFileSync(new URL('./fixtures/Pledges.ir.json', import.meta.url), 'utf8');
  load(text);
  const refuses = (edit, why) => assert.throws(() => load(edit(text)), why);
  refuses((t) => t.replace('"ir": 2,', '"ir": 2.0,'), /not a plain integer/);
  refuses((t) => t.replace('"ir": 2,', '"ir": 2, "ir": 2,'), /duplicate key "ir"/);
  refuses((t) => t.replace('"checked": [3]', '"checked": [3], "checked": []'), /duplicate key "checked"/);
  refuses((t) => t.replace('"checked": [3]', '"checked": []'), /checked is \[\], but the parameters whose types hold a Key, a Hash or a Map are \[3\]/);
  refuses((t) => t.replace('"node": "Compare"', '"node": "Bogus"'), /unknown node "Bogus"/);
  refuses((t) => t.replace('"local": 3,', '"local": 9007199254740993,'), /outside the safe range/);
  refuses((t) => t.replace(/"node": "Lit", "value": "(\d+)", "type": "Int"/, '"node": "Lit", "value": "0x10", "type": "Int"'), /not a decimal integer/);
  refuses((t) => t.replace(/"value": "(\d+)" }/, '"value": "0x10" }'), /option\): not an integer/);
  refuses((t) => t.replace('"usesCtx": true', '"usesCtx": false'), /usesCtx is false, but the method reads the transaction/);
  refuses((t) => t.replace('"name": "only"', '"name": "sealed"'), /unknown modifier sealed/);
  // F11: M1c's batched is known (a version-1 reader loads #67's IR), and like every modifier but open and only it
  // makes a method read the transaction.
  refuses((t) => t.replace('{ "name": "only", "field": "owner" }', '{ "name": "batched" }'), /usesCtx is false, but the method reads the transaction/);
  // C4, S7: an initializer that reads its own binding; a local out of its scope; a fn, field or arity that is not there.
  const withSelfRef = (t) => {
    const p = JSON.parse(t);
    const reassign = p.methods.find((m) => m.name === 'reassign');
    const st = { stmt: 'Let', local: { id: 9000, name: 'self', type: 'Int', taint: 0, at: [1, 1] },
      value: { node: 'LocalRef', local: 9000, type: 'Int', taint: 0, at: [1, 1] }, at: [1, 1] };
    reassign.body.stmts.unshift(st);
    return JSON.stringify(p);
  };
  refuses(withSelfRef, /undeclared local 9000/);
  const outOfScope = (t) => {
    const p = JSON.parse(t);
    const [first, second] = p.methods.filter((m) => m.params.length > 0);
    const ref = { node: 'LocalRef', local: first.params[0].id, type: first.params[0].type, taint: 0, at: [1, 1] };
    second.body.stmts.unshift({ stmt: 'Require', cond: { node: 'Equals', negate: false, left: ref, right: ref, operand: first.params[0].type,
      type: 'Bool', taint: 0, at: [1, 1] }, message: 'x', at: [1, 1] });
    return JSON.stringify(p);
  };
  refuses(outOfScope, /outside its scope/);
  refuses((t) => t.replace('"fn": "total"', '"fn": "nosuch"'), /no fn nosuch/);
  const extraArg = (t) => {
    const p = JSON.parse(t);
    const visit = (n) => {
      if (Array.isArray(n)) n.forEach(visit);
      else if (n && typeof n === 'object') {
        if (n.node === 'CallFn') n.args.push({ node: 'Lit', value: '1', type: 'Int', taint: 0, at: [1, 1] });
        Object.values(n).forEach(visit);
      }
    };
    visit(p.methods);
    return JSON.stringify(p);
  };
  refuses(extraArg, /fn total takes 1 argument\(s\), not 2/);
  refuses((t) => t.replace(/"node": "StateField", "field": "goal"/, '"node": "StateField", "field": "gaol"'), /the state has no field gaol/);
  refuses((t) => t.replace('"sourceSha256": "', '"sourceSha256": "x'), /sourceSha256/);
  assert.throws(() => load(JSON.parse(text)), TypeError, 'load takes the text, so it can refuse duplicates');
});

// ------------------------------------------------------------------ §2.2 else-if chains (M1e), every export loads (M1 merge)

test('§2.2 an else-if chain runs the first branch whose condition holds, else the else block', () => {
  const branches = fixture('Branches');
  const owner = fromHex('0a'.repeat(28)), to = fromHex('a1'.repeat(28));
  const s = new Rec('State', [owner, 0n]);
  const tx = { ...TX, caller: owner };
  const n = (method, args) => branches.call(method, s, args, tx).value.fields[1];
  // A value chain of twelve branches, one with statements (x < 70: y = 2x, then y - x).
  assert.deepEqual([5n, 15n, 65n, 75n, 119n, 120n, 1000n].map((x) => n('grade', [x])), [0n, 1n, 65n, 7n, 11n, 12n, 12n]);
  // A recursive chain of nine branches: steps(x) is x up to 8, then 8.
  assert.deepEqual([0n, 3n, 8n, 9n, 40n].map((x) => n('walk', [x])), [0n, 3n, 8n, 8n, 8n]);
  // A paying statement chain: only the taken branch pays; the third branch's require refuses 5.
  const pays = (x) => branches.call('settle', s, [to, x], tx).payouts.map((p) => [hex(p.to), p.lovelace]);
  assert.deepEqual(pays(0n), [[hex(to), 1_000_000n]]);
  assert.deepEqual(pays(1n), [[hex(to), 2_000_000n], [hex(owner), 1_000_000n]]);
  assert.deepEqual(pays(7n), [[hex(to), 7_000_000n]]);
  assert.deepEqual(pays(10n), [[hex(owner), 3_000_000n]]);
  assert.throws(() => pays(5n), (e) => e instanceof Refused && e.message === 'five is refused');
});

test('every exported example loads: the M1c ports (Pool with batched), Branches (flat else-if chains) and the rest', () => {
  const dir = new URL('./fixtures/', import.meta.url);
  const names = readdirSync(dir).filter((f) => f.endsWith('.ir.json')).map((f) => f.replace('.ir.json', ''));
  for (const c of ['Vault', 'Escrow', 'Crowdfund', 'Token', 'Pool', 'Branches']) assert.ok(names.includes(c), c);
  for (const name of names) assert.doesNotThrow(() => fixture(name), name);
});

// ------------------------------------------------------------------ §2.6 modifiers (M1 integration, review F1)

test('§2.6 only, after and before refuse a call whose transaction breaks them, in source order; transition() skips them', () => {
  const vault = fixture('Vault');
  const owner = fromHex('0a'.repeat(28)), ann = fromHex('a1'.repeat(28));
  const s = new Rec('State', [owner, 1500n, 10_000_000n]);   // unlockAt 1500
  const tx = (o) => ({ ...TX, balance: 10_000_000n, ...o });
  const refusal = (f) => { try { f(); } catch (e) { if (e instanceof Refused) return e.refusal; throw e; } return 'accepted'; };
  // withdraw is 'only owner after unlockAt': signed by the owner, from 1500 on.
  assert.equal(refusal(() => vault.call('withdraw', s, [1_000_000n], tx({ caller: owner, validFrom: 1500n, validTo: 2000n }))), 'accepted');
  assert.equal(refusal(() => vault.call('withdraw', s, [1_000_000n], tx({ caller: ann, validFrom: 1500n, validTo: 2000n }))), 'modifier:only owner');
  // The owner among several signers; the caller's own key is not enough when it is not the owner's.
  assert.equal(refusal(() => vault.call('withdraw', s, [1_000_000n], tx({ caller: ann, signers: [ann, owner], validFrom: 1500n, validTo: 2000n }))), 'accepted');
  assert.equal(refusal(() => vault.call('withdraw', s, [1_000_000n], tx({ caller: owner, validFrom: 1499n, validTo: 2000n }))), 'modifier:after unlockAt');
  // Source order: 'only owner' is checked before 'after unlockAt', and both before the body's require.
  assert.equal(refusal(() => vault.call('withdraw', s, [0n], tx({ caller: ann, validFrom: 1000n, validTo: 2000n }))), 'modifier:only owner');
  assert.match(refusal(() => vault.call('withdraw', s, [0n], tx({ caller: owner, validFrom: 1500n, validTo: 2000n }))), /^require:/);
  // The transition alone (the JVM method's and the transition program's view) does not see the modifiers.
  assert.equal(vault.transition('withdraw', s, [1_000_000n], tx({ caller: ann, validFrom: 0n, validTo: 1n })).value.fields[2], 9_000_000n);
  // before: Escrow-like bounds through a hand-edited copy of Vault (after -> before).
  const text = readFileSync(new URL('./fixtures/Vault.ir.json', import.meta.url), 'utf8');
  const before = load(text.replaceAll('"name": "after"', '"name": "before"'));
  assert.equal(refusal(() => before.call('withdraw', s, [1_000_000n], tx({ caller: owner, validFrom: 0n, validTo: 1500n }))), 'accepted');
  assert.equal(refusal(() => before.call('withdraw', s, [1_000_000n], tx({ caller: owner, validFrom: 0n, validTo: 1501n }))), 'modifier:before unlockAt');
  // A modifier needs the transaction: a call without one is a caller's error, not a refusal.
  assert.throws(() => vault.call('withdraw', s, [1_000_000n], null), (e) => !(e instanceof Refused) && /needs the transaction/.test(e.message));
});

test('the reader refuses a malformed branches list and a condition that is not a Bool (review F2, S3 on #76)', () => {
  const text = readFileSync(new URL('./fixtures/Branches.ir.json', import.meta.url), 'utf8');
  load(text);
  const edit = (f) => () => { const p = JSON.parse(text); f(p); return load(JSON.stringify(p)); };
  const firstIf = (p, node) => {
    let found = null;
    const walk = (o) => {
      if (found || !o || typeof o !== 'object') return;
      if ((node === 'If' && o.stmt === 'If') || (node === 'IfValue' && o.node === 'IfValue')) { found = o; return; }
      Object.values(o).forEach(walk);
    };
    walk(p.methods);
    return found;
  };
  for (const node of ['If', 'IfValue']) {
    assert.throws(edit((p) => { firstIf(p, node).branches = []; }), /at least one branch/, node);
    assert.throws(edit((p) => { firstIf(p, node).branches = {}; }), /at least one branch/, node);
    assert.throws(edit((p) => { firstIf(p, node).branches[0].extra = 1; }), /unknown field "extra"|extra/, node);
    assert.throws(edit((p) => { delete firstIf(p, node).branches[0].at; }), /at/, node);
    assert.throws(edit((p) => { const b = firstIf(p, node).branches[0]; firstIf(p, node).cond = b.cond; }), /cond/, node);
    assert.throws(edit((p) => { firstIf(p, node).branches[0].cond = { node: 'Lit', value: '0', type: 'Int', taint: 0, at: [1, 1] }; }),
      /branch condition is of type "Int", not Bool/, node);
  }
  assert.throws(edit((p) => { firstIf(p, 'IfValue').otherwise = null; }), Error);
});

// ------------------------------------------------------------------ M1 hardening: modifier shapes (S5) and the caller (S6)

test('the reader refuses a modifier the checker would not emit: fields, kinds, duplicates (review S5 on #76)', () => {
  const edit = (name, f) => () => {
    const p = JSON.parse(readFileSync(new URL(`./fixtures/${name}.ir.json`, import.meta.url), 'utf8'));
    f(p, (n) => p.methods.find((m) => m.name === n));
    return load(JSON.stringify(p));
  };
  assert.doesNotThrow(edit('Pool', () => {}));
  assert.doesNotThrow(edit('Vault', () => {}));
  // 'only' with no field: loaded and skipped before, so Pool's withdraw was accepted unsigned.
  assert.throws(edit('Pool', (p, m) => { m('withdraw').mods[0].field = null; }), /only names a state field, not null/);
  assert.throws(edit('Pool', (p, m) => { delete m('withdraw').mods[0].field; }), /only names a state field, not null/);
  assert.throws(edit('Vault', (p, m) => { m('withdraw').mods[1].field = null; }), /after names a state field, not null/);
  // A field that is not there, or of the wrong type.
  assert.throws(edit('Pool', (p, m) => { m('withdraw').mods[0].field = 'nobody'; }), /only nobody: the state has no field nobody/);
  assert.throws(edit('Pool', (p, m) => { m('withdraw').mods[0].field = 'donated'; }), /only donated: donated is of type "Int", not Key/);
  assert.throws(edit('Vault', (p, m) => { m('withdraw').mods[1].field = 'owner'; }), /after owner: owner is of type "Key", not Int/);
  assert.throws(edit('Vault', (p, m) => { m('withdraw').mods[1] = { name: 'before', field: 'owner' }; }), /before owner: owner is of type "Key", not Int/);
  // Duplicates; an init's and a view's modifiers; a payable destroy; 'payable' and 'open' with a field.
  assert.throws(edit('Pool', (p, m) => { m('withdraw').mods.push({ name: 'only', field: 'admin' }); }), /the modifier only is given twice/);
  assert.throws(edit('Vault', (p, m) => { m('init').mods.push({ name: 'only', field: 'owner' }); }), /an init takes only payable, not only/);
  assert.throws(edit('Vault', (p, m) => { m('total').mods.push({ name: 'open' }); }), /a view takes no modifiers, not open/);
  assert.throws(edit('Vault', (p, m) => { m('close').mods.push({ name: 'payable' }); }), /a destroy cannot be payable/);
  assert.throws(edit('Vault', (p, m) => { m('deposit').mods[0].field = 'owner'; }), /unknown field "field"/);
  assert.throws(edit('Pool', (p, m) => { m('buy').mods[2].field = 'admin'; }), /unknown field "field"/);
  // batched: plain or 'batched only', on an action, never with only/after/before.
  assert.throws(edit('Pool', (p, m) => { m('close').mods.push({ name: 'batched', field: 'only' }); }), /only an action can be batched, not a destroy/);
  assert.throws(edit('Pool', (p, m) => { m('init').mods.push({ name: 'batched' }); }), /an init takes only payable, not batched/);
  assert.throws(edit('Pool', (p, m) => { m('donate').mods[1].field = 'admin'; }), /batched is plain or "batched only", not "admin"/);
  assert.throws(edit('Pool', (p, m) => { m('donate').mods.push({ name: 'only', field: 'admin' }); }), /a batched action cannot use only/);
  assert.throws(edit('Vault', (p, m) => { m('withdraw').mods.push({ name: 'batched' }); }), /a batched action cannot use only/);
  // The interpreter never skips one either: a program whose modifier lost its field (past the reader) is an error.
  const pool = fixture('Pool');
  pool.methods.get('withdraw').mods[0].field = null;
  const admin = fromHex('0a'.repeat(28));
  const s = new Rec('State', [admin, 0n, 0n, 1n, 1n]);
  assert.throws(() => pool.call('withdraw', s, [0n], { ...TX, caller: admin, balance: 0n }), (e) => !(e instanceof Refused) && /without a state field/.test(e.message));
});

test('§2 the caller is among the signers: a τ whose caller did not sign is the caller\'s error (review S6 on #76)', () => {
  const vault = fixture('Vault');
  const owner = fromHex('0a'.repeat(28)), ann = fromHex('a1'.repeat(28));
  const s = new Rec('State', [owner, 1500n, 10_000_000n]);
  const tx = (o) => ({ ...TX, balance: 10_000_000n, validFrom: 1500n, validTo: 2000n, ...o });
  // Ann calls, the owner signs and Ann does not: not an answer ('only owner' holds, but the caller never signed).
  for (const f of ['call', 'transition']) {
    assert.throws(() => vault[f]('withdraw', s, [1_000_000n], tx({ caller: ann, signers: [owner] })),
      (e) => !(e instanceof Refused) && /caller is not among its signers/.test(e.message), f);
    assert.throws(() => vault[f]('withdraw', s, [1_000_000n], tx({ caller: ann, signers: [] })), (e) => !(e instanceof Refused), f);
  }
  // Both sign: accepted; the caller alone (no signers given: [caller]): refused by 'only owner'.
  assert.equal(vault.call('withdraw', s, [1_000_000n], tx({ caller: ann, signers: [ann, owner] })).value.fields[2], 9_000_000n);
  assert.throws(() => vault.call('withdraw', s, [1_000_000n], tx({ caller: ann })), (e) => e instanceof Refused && e.refusal === 'modifier:only owner');
  // The JSON entry (run-vectors) says the same.
  const call = (signers) => runCall(vault, { method: 'withdraw', state: { rec: 'State', fields: [{ bytes: '0a'.repeat(28) }, { int: '1500' }, { int: '10000000' }] },
    args: [{ int: '1000000' }], tx: { caller: 'a1'.repeat(28), received: '0', balance: '10000000', validFrom: '1500', validTo: '2000', signers } });
  assert.ok(call(['a1'.repeat(28), '0a'.repeat(28)]).ok);
  assert.throws(() => call(['0a'.repeat(28)]), /caller is not among its signers/);
});

test('the reader recomputes pays and requires a paying method to say who may call it; no batched action with BigMap state (#80 review S3)', () => {
  const edit = (name, f) => () => {
    const p = JSON.parse(readFileSync(new URL(`./fixtures/${name}.ir.json`, import.meta.url), 'utf8'));
    f(p, (n) => p.methods.find((m) => m.name === n));
    return load(JSON.stringify(p));
  };
  // Deleting the audience of a paying method: before, Pool's withdraw then accepted a stranger alone.
  assert.throws(edit('Pool', (p, m) => { m('withdraw').mods = []; }), /it pays, sends, mints, burns or deposits, so it says who may call it \(only f, open, quorum or authorized\)/);
  assert.throws(edit('Pool', (p, m) => { m('buy').mods = m('buy').mods.filter((x) => x.name !== 'open'); }), /so it says who may call it/);
  assert.throws(edit('Counter2', (p, m) => { m('close').mods = []; }), /so it says who may call it/);
  // pays as written must be what the body says, on the method and on each if, for and match statement.
  assert.throws(edit('Pool', (p, m) => { m('withdraw').pays = false; }), /pays is false, but the body pays/);
  assert.throws(edit('Counter2', (p, m) => { m('increment').pays = true; }), /pays is true, but the body does not pay/);
  assert.throws(edit('Branches', (p, m) => {
    const walk = (o) => (o && typeof o === 'object' ? (o.stmt === 'If' && o.pays === true ? o : Object.values(o).map(walk).find(Boolean)) : null);
    walk(m('settle').body).pays = false;
  }), /If at .*: pays is false, but its blocks pay/);
  // JSC0419: no batched action in a contract with BigMap state.
  assert.throws(edit('Ledger', (p, m) => { m('transfer').mods.push({ name: 'batched' }); }), /a contract with BigMap state has no batched action/);
  assert.doesNotThrow(edit('Pool', () => {}));
  assert.doesNotThrow(edit('Ledger', () => {}));
});

test('the reader refuses a pay under an expression or in a fn (JSC0413), and a destroy that pays nothing (JSC0409) (#80 review C4, S7, F7)', () => {
  const mutant = (n) => readFileSync(new URL(`./mutants/${n}.json`, import.meta.url), 'utf8');
  // Before, the first loaded and a stranger alone got Pool's payout: pays was recomputed from statement blocks only.
  assert.throws(() => load(mutant('pay-in-value-block')), /method withdraw\): a pay inside an expression or a fn, where the checker allows none \(JSC0413/);
  assert.throws(() => load(mutant('pay-in-value-if')), /method withdraw\): a pay inside an expression or a fn/);
  assert.throws(() => load(mutant('pay-in-fn')), /fn total\): a pay inside an expression or a fn/);
  assert.throws(() => load(mutant('destroy-without-pay')), /method close\): a destroy pays out what the instance holds: it has no pay/);
  // #98 review F1: every effect statement, not only a pay (send, mint, deposit), as the Java reader refuses them.
  assert.throws(() => load(mutant('send-in-value-block')), /method give\): a send inside an expression or a fn/);
  assert.throws(() => load(mutant('send-in-fn')), /fn total\): a send inside an expression or a fn/);
  assert.throws(() => load(mutant('mint-in-fn')), /fn tokens\): a mint inside an expression or a fn/);
  assert.throws(() => load(mutant('deposit-in-lambda')), /method sell\): a deposit inside an expression or a fn/);
});

// ------------------------------------------------------------------ M2a: values, credentials, addresses, effects

const VALUE = 'Value';
const ledger = (op, args, type) => node('LedgerOp', { op, args }, type);
const POL = '70'.repeat(28), Q = '71'.repeat(28);
const vjson = (...entries) => ({ value: entries.map(([p, n, q]) => [p, n, String(q)]) });

test('§1.3 a value keeps the ledger order (a prefix first, then bytewise) and drops zero sums; §3.12 its operations', () => {
  const p = program();
  // (POL, "b") and (POL, "aa"): bytewise "aa" < "b", although "b" is shorter (the ledger's order, not §1.1's).
  const v = p.evalExpr(ledger('add', [ledger('asset', [b(POL), b('62'), lit(1n)], VALUE), ledger('asset', [b(POL), b('6161'), lit(2n)], VALUE)], VALUE));
  assert.deepEqual(toJson(v), vjson([POL, '6161', 2n], [POL, '62', 1n]));
  // A zero sum leaves no entry; 0 builds the empty value.
  const z = p.evalExpr(ledger('sub', [ledger('asset', [b(POL), b('62'), lit(3n)], VALUE), ledger('asset', [b(POL), b('62'), lit(3n)], VALUE)], VALUE));
  assert.deepEqual(toJson(z), vjson());
  assert.deepEqual(toJson(p.evalExpr(ledger('lovelace', [lit(0n)], VALUE))), vjson());
  // Quantities stay in [-2^127, 2^127 - 1]: building, adding, subtracting (−(−2^127) is out) and scaling fail outside it.
  const MAX = (1n << 127n) - 1n, MIN = -(1n << 127n);
  const fails = (e, cls) => assert.throws(() => p.evalExpr(e), (x) => x instanceof Refused && x.refusal === cls);
  fails(ledger('lovelace', [lit(MAX + 1n)], VALUE), 'domain:Value.lovelace');
  fails(ledger('asset', [b(POL), b('00'.repeat(33)), lit(1n)], VALUE), 'domain:Value.asset');
  assert.deepEqual(toJson(p.evalExpr(ledger('asset', [b(POL), b('00'.repeat(33)), lit(0n)], VALUE))), vjson());   // 0: no check, as insertCoin
  fails(ledger('add', [ledger('lovelace', [lit(MAX)], VALUE), ledger('lovelace', [lit(1n)], VALUE)], VALUE), 'domain:Value +');
  fails(ledger('sub', [ledger('none', [], VALUE), ledger('lovelace', [lit(MIN)], VALUE)], VALUE), 'domain:Value -');
  fails(std('std/value', 'scale', [lit(2n), ledger('lovelace', [lit(MAX)], VALUE)], VALUE), 'domain:scale');
  assert.deepEqual(toJson(p.evalExpr(std('std/value', 'scale', [lit(0n), ledger('lovelace', [lit(MAX)], VALUE)], VALUE))), vjson());
  // covers fails on a negative quantity, either side; of and lovelace read 0 when absent.
  fails(ledger('covers', [ledger('lovelace', [lit(-1n)], VALUE), ledger('none', [], VALUE)], BOOL), 'domain:covers');
  assert.equal(p.evalExpr(ledger('covers', [ledger('asset', [b(POL), b('01'), lit(5n)], VALUE), ledger('asset', [b(POL), b('01'), lit(5n)], VALUE)], BOOL)), true);
  assert.equal(p.evalExpr(ledger('covers', [ledger('asset', [b(POL), b('01'), lit(5n)], VALUE), ledger('asset', [b(Q), b('01'), lit(1n)], VALUE)], BOOL)), false);
  assert.equal(p.evalExpr(ledger('of', [ledger('none', [], VALUE), b(POL), b('01')], INT)), 0n);
  assert.equal(p.evalExpr(eq(ledger('asset', [b(POL), b('01'), lit(1n)], VALUE), ledger('asset', [b(POL), b('01'), lit(1n)], VALUE), VALUE)), true);
});

test('§1.4 credentials and addresses compare by kind and bytes; §4 a 28-byte hash wherever one crosses', () => {
  const p = program();
  const k = 'aa'.repeat(28);
  const addr = (op, ...args) => ledger(op, args, 'Address');
  assert.equal(p.evalExpr(eq(addr('wallet', b(k)), addr('wallet', b(k)), 'Address')), true);
  assert.equal(p.evalExpr(eq(addr('wallet', b(k)), addr('script', b(k)), 'Address')), false);   // the kind differs
  assert.equal(p.evalExpr(eq(addr('wallet', b(k)), addr('walletStaked', b(k), b(k)), 'Address')), false);   // the staking part differs
  assert.equal(p.evalExpr(eq(ledger('payment', [addr('scriptStaked', b(k), b(k))], 'Credential'), ledger('scriptCred', [b(k)], 'Credential'),
    'Credential')), true);
  assert.equal(p.wellFormed(p.evalExpr(addr('wallet', b('aa'.repeat(27)))), 'Address'), false);
  assert.equal(p.wellFormed(p.evalExpr(addr('walletStaked', b(k), b('aa'.repeat(29)))), 'Address'), false);
  assert.equal(p.wellFormed(p.evalExpr(ledger('keyCred', [b(k)], 'Credential')), 'Credential'), true);
  assert.deepEqual(toJson(p.evalExpr(std('std/address', 'paymentScript', [addr('script', b(k))], { option: 'Hash' }))), { some: { bytes: k } });
  assert.deepEqual(toJson(p.evalExpr(std('std/address', 'paymentKey', [addr('script', b(k))], { option: 'Key' }))), { none: true });
});

test('§5 effects: sends in order, the net mint, the accepted sum, and what was accepted checked last (Coins)', () => {
  const coins = fixture('Coins');
  const policy = '52'.repeat(28), label = '0d'.repeat(28), self = '03'.repeat(32), caller = '0a'.repeat(28);
  const state = { rec: 'State', fields: [{ bytes: caller }, { address: [{ keyCred: 'b1'.repeat(28) }, null] }, { int: '5' }, { bytes: label }] };
  const tx = (received, held) => ({ caller, received: '0', balance: '3000000', validFrom: '0', validTo: '1', funds: { received, held, policy, self } });
  const call = (method, args, t) => runCall(coins, { method, state, args, tx: t });
  // redeem: accept 2 receipts, burn 3 then mint 1 (net −2), send 2 tokens to the beneficiary.
  const ok = call('redeem', [{ bytes: POL }, { bytes: '484f534b59' }, { int: '2' }], tx(vjson([policy, label, 2n]), vjson([POL, '484f534b59', 9n])));
  assert.deepEqual(ok.ok.mint, vjson([policy, label, -2n]));
  assert.deepEqual(ok.ok.accepted, vjson([policy, label, 2n]));
  assert.deepEqual(ok.ok.sends, [{ to: { address: [{ keyCred: 'b1'.repeat(28) }, null] }, value: vjson([POL, '484f534b59', 2n]) }]);
  // Offering other assets than those accepted: refused last, with the class domain:accepted.
  const off = call('redeem', [{ bytes: POL }, { bytes: '484f534b59' }, { int: '2' }], tx(vjson([policy, label, 1n]), vjson([POL, '484f534b59', 9n])));
  assert.equal(off.class, 'domain:accepted');
  // A reserved name of the own policy cannot be accepted (§5.5); another policy's 32-byte name can.
  const reserved = call('probe', [{ bytes: POL }, { bytes: '01' }, { int: '1' }, { int: '1' }], tx(vjson([policy, '4f' + '00'.repeat(28), 1n]), vjson()));
  assert.equal(reserved.class, 'domain:accept');
  const foreign = call('probe', [{ bytes: POL }, { bytes: '01' }, { int: '1' }, { int: '1' }], tx(vjson([Q, '00'.repeat(32), 1n]), vjson()));
  assert.ok('ok' in foreign, JSON.stringify(foreign));
  // A method that moves native assets needs the funds in its transaction (a caller's error, not a refusal).
  assert.throws(() => runCall(coins, { method: 'probe', state, args: [{ bytes: POL }, { bytes: '01' }, { int: '1' }, { int: '1' }],
    tx: { caller, received: '0', balance: '0', validFrom: '0', validTo: '1' } }), /needs them/);
});

test('§2.6 quorum and authorized, §2.7 the output checks, §5.6 events and params (Treasury)', () => {
  const treasury = fixture('Treasury');
  const A = '11'.repeat(28), B = '22'.repeat(28), C = '33'.repeat(28), BOB = 'b0'.repeat(28), G = '6a'.repeat(28);
  const keys = (...ks) => ks.map((k) => ({ bytes: k }));
  const params = { rec: 'Config', fields: [{ scriptCred: G }, { int: '100000000' }] };
  const state = (council, q, frozen = false) => ({ rec: 'State', fields: [keys(...council), { int: String(q) }, frozen] });
  const tx = (signers, approvers = []) => ({ caller: signers[0] ?? A, received: '0', balance: '500000000', validFrom: '0', validTo: '1', signers, approvers });
  const call = (method, s, args, t, transition = false) => runCall(treasury, { method, state: s, args, tx: t, params, transition });
  const council = state([A, B, C], 2);
  // quorum: two distinct members of three sign; one does not reach it, nor does a stranger, nor one key signing twice.
  const paid = call('pay', council, [{ bytes: BOB }, { int: '10000000' }], tx([A, C]));
  assert.deepEqual(paid.ok.payouts, [{ to: BOB, lovelace: '10000000' }]);
  assert.deepEqual(paid.ok.events, [{ rec: 'Paid', fields: [{ bytes: BOB }, { int: '10000000' }] }]);
  for (const signers of [[A], [A, BOB], [A, A]]) {
    assert.equal(call('pay', council, [{ bytes: BOB }, { int: '1' }], tx(signers)).class, 'modifier:quorum council quorum');
  }
  // The transition alone (§2.6) leaves the modifier to the validator.
  assert.ok('ok' in call('pay', council, [{ bytes: BOB }, { int: '1' }], tx([A]), true));
  // params.maxPayout bounds a payment; a call needs the params when the contract reads them.
  assert.match(call('pay', council, [{ bytes: BOB }, { int: '100000001' }], tx([A, B])).class, /^require:/);
  assert.throws(() => runCall(treasury, { method: 'pay', state: council, args: [{ bytes: BOB }, { int: '1' }], tx: tx([A, B]) }), /supply them/);
  // authorized params.guardian: the guardian script approves (a key signing with its hash does not).
  assert.deepEqual(call('freeze', council, [true], tx([A], [G])).ok.value, state([A, B, C], 2, true));
  assert.equal(call('freeze', council, [true], tx([G])).class, 'modifier:authorized params.guardian');
  // §2.7: the next state's quorum is well formed (sorted, distinct, 28-byte keys; 1 <= threshold <= keys), then the
  // invariants hold; the transition alone computes the state anyway.
  assert.equal(call('rotate', council, [keys(A, A, B), { int: '3' }], tx([A, B])).class, 'invariant:quorum council quorum');
  assert.equal(call('rotate', council, [keys(B, A), { int: '1' }], tx([A, B])).class, 'invariant:quorum council quorum');
  assert.ok('ok' in call('rotate', council, [keys(B, A), { int: '1' }], tx([A, B]), true));
  assert.deepEqual(call('rotate', council, [keys(A, B), { int: '2' }], tx([A, B])).ok.events,
    [{ rec: 'CouncilChanged', fields: [{ int: '2' }, { int: '2' }] }]);
  // At creation too: an init whose council is malformed is refused.
  const init = (council, q) => runCall(treasury, { method: 'init', state: null, args: [keys(...council), { int: String(q) }], tx: tx([A]), params });
  assert.ok('ok' in init([A, B, C], 2));
  assert.equal(init([A, B], 3).class, 'invariant:quorum council quorum');
});

test('§2.7 step 1: a params quorum is checked at creation; a failing invariant is the invariant\'s refusal (#84 review F3, S2)', () => {
  const council = fixture('Council');
  const K1 = '41'.repeat(28), K2 = '42'.repeat(28), K3 = '43'.repeat(28), A = '11'.repeat(28), B = '22'.repeat(28);
  const keys = (...ks) => ks.map((k) => ({ bytes: k }));
  const tx = { caller: A, received: '0', balance: '0', validFrom: '0', validTo: '1' };
  const init = (board, q) => runCall(council, { method: 'init', state: null, args: [keys(A, B), { int: '1' }, { keyCred: '4e'.repeat(28) }], tx,
    params: { rec: 'Config', fields: [board, { int: String(q) }, { keyCred: 'ad'.repeat(28) }, { int: '50000000' }] } });
  assert.ok('ok' in init(keys(K1, K2, K3), 2));
  const cls = 'invariant:quorum params.board params.boardQuorum';
  for (const [board, q] of [[keys(K2, K1, K3), 2], [keys(K1, K1, K3), 2], [keys(K1, K2, K3), 0], [keys(K1, K2, K3), -1],
    [keys(K1, K2, K3), 4], [[], 1]]) {
    assert.equal(init(board, q).class, cls, JSON.stringify([board, q]));
  }
  // M2c (review r3, C7): the params are a checked input of the init: a malformed key is refused before the invariant.
  assert.equal(init(keys('00'.repeat(27), K2, K3), 2).class, 'input:params');
  // The transition alone (§2.6) computes the creation; the output check is the validator's.
  assert.ok('ok' in runCall(council, { method: 'init', state: null, args: [keys(A, B), { int: '1' }, { keyCred: '4e'.repeat(28) }], tx,
    params: { rec: 'Config', fields: [keys(K2, K1, K3), { int: '2' }, { keyCred: 'ad'.repeat(28) }, { int: '50000000' }] }, transition: true }));
  // An invariant that fails (a division by zero) refuses with its own class, not domain:/.
  const ratio = fixture('Ratio');
  const s = { rec: 'State', fields: [{ int: '0' }, { int: '1' }] };
  assert.equal(runCall(ratio, { method: 'set', state: s, args: [{ int: '5' }, { int: '0' }], tx: null }).class, 'invariant:ratio');
  assert.ok('ok' in runCall(ratio, { method: 'set', state: s, args: [{ int: '5' }, { int: '0' }], tx: null, transition: true }));
  assert.ok('ok' in runCall(ratio, { method: 'set', state: s, args: [{ int: '5' }, { int: '2' }], tx: null }));
});

// ------------------------------------------------------------------ M2c: composition (§4, §5.1, §5.7; ADR-0011)

test('M2c reads: a Kiosk buy reads the oracle state; stale refused by its require (before), a malformed state input:price', () => {
  const kiosk = fixture('Kiosk');
  const ORACLE = { instance: ['0c'.repeat(28), '0d'.repeat(32)] };
  const params = { rec: 'Config', fields: [ORACLE, { int: '600000' }] };
  const owner = '0a'.repeat(28), buyer = 'b0'.repeat(28);
  const state = { rec: 'State', fields: [{ bytes: owner }, { int: '0' }] };
  const oracle = (publisher, price, at) => ({ rec: 'Oracle.State', fields: [{ bytes: publisher }, { int: String(price) }, { int: String(at) }] });
  const tx = (received, read) => ({ caller: buyer, received: String(received), balance: '0', validFrom: '1000000', validTo: '1120000',
    reads: { oracle: read } });
  const buy = (qty, received, read) => runCall(kiosk, { method: 'buy', state, args: [{ int: String(qty) }], tx: tx(received, read), params });
  // Fresh: posted 60 s before the window; the whole window ends within 600 s of it. Paid exactly qty * price.
  const fresh = oracle('9b'.repeat(28), 3000000, 940000);
  assert.deepEqual(buy(2, 6000000, fresh).ok.value, { rec: 'State', fields: [{ bytes: owner }, { int: '2' }] });
  assert.equal(buy(2, 5999999, fresh).class, 'require:22:5:pay exactly qty * price');
  // Stale: posted 700 s before; before(updatedAt + maxAge) does not hold for the window.
  assert.equal(buy(2, 6000000, oracle('9b'.repeat(28), 3000000, 300000)).class, 'require:21:5:stale price');
  // A read state is a checked input (§4): a 27-byte publisher key is refused before the body.
  assert.equal(buy(0, 0, oracle('9b'.repeat(27), 3000000, 940000)).class, 'input:price');
  // No state for the read: no answer from the vector (an error of the call, not a refusal).
  assert.throws(() => runCall(kiosk, { method: 'buy', state, args: [{ int: '1' }], tx: { ...tx(0, fresh), reads: {} }, params }), /reads params.oracle/);
});

test('M2c deposits: a Stall sell deposits its fee into params.pot, the memo the caller; Pot collect sums what it receives', () => {
  const stall = fixture('Stall');
  const POT = { instance: ['70'.repeat(28), '71'.repeat(32)] };
  const params = { rec: 'Config', fields: [POT, { int: '500' }] };
  const owner = '0a'.repeat(28), buyer = 'b1'.repeat(28);
  const state = { rec: 'State', fields: [{ bytes: owner }, { int: '0' }] };
  const sell = (price, received) => runCall(stall, { method: 'sell', state, args: [{ int: String(price) }],
    tx: { caller: buyer, received: String(received), balance: '0', validFrom: '0', validTo: '1' }, params });
  const ok = sell(10000000, 10000000).ok;
  assert.deepEqual(ok.value, { rec: 'State', fields: [{ bytes: owner }, { int: '9500000' }] });
  assert.deepEqual(ok.deposits, [{ target: POT, value: { value: [['', '', '500000']] }, memo: { bytes: buyer } }]);
  assert.equal(sell(1000, 1000).class, 'require:20:5:pay exactly the price, at least 2 ADA');

  const pot = fixture('Pot');
  const potState = { rec: 'State', fields: [{ bytes: owner }, { int: '0' }, { int: '0' }] };
  const funds = (deposits) => ({ received: { value: [] }, held: { value: [] }, policy: '70'.repeat(28), self: '71'.repeat(32), deposits });
  const collect = (deposits) => runCall(pot, { method: 'collect', state: potState, args: [],
    tx: { caller: buyer, received: '0', balance: '0', validFrom: '0', validTo: '1', funds: funds(deposits) } });
  const two = [{ value: { value: [['', '', '500000']] }, memo: null }, { value: { value: [['', '', '250000'], ['ab'.repeat(28), '01', '3']] }, memo: null }];
  assert.deepEqual(collect(two).ok.value, { rec: 'State', fields: [{ bytes: owner }, { int: '750000' }, { int: '2' }] });
  assert.equal(collect([]).class, 'require:17:5:nothing deposited');
});

test('M2c deposits: a deposit is not empty, strictly positive, and holds no reserved-kind name (domain:deposit)', () => {
  const tipper = fixture('Tipper');
  const JAR = { instance: ['70'.repeat(28), '71'.repeat(32)] };
  const params = { rec: 'Config', fields: [JAR, { int: '100000000' }] };
  const owner = '0a'.repeat(28), alice = 'a1'.repeat(28), policy = '5e'.repeat(28);
  const state = { rec: 'State', fields: [{ bytes: owner }, { int: '0' }, { bytes: '00' }] };
  const jar = { rec: 'Tips.State', fields: [{ bytes: owner }, { map: [] }, { value: [] }, { none: true }] };
  const tip = (amount, received, offered) => runCall(tipper, { method: 'tip', state, args: [{ bytes: alice }, { bytes: '7a' }, { int: String(amount) }],
    tx: { caller: alice, received: String(received), balance: '0', validFrom: '0', validTo: '1', reads: { jar },
      funds: { received: { value: offered }, held: { value: [] }, policy, self: '5f'.repeat(32) } }, params });
  // Lovelace and native assets offered: both deposited, with a Note memo; the assets accepted first.
  const P = 'cc'.repeat(28);
  const ok = tip(1000000, 1000000, [[P, '00ff', '1'], [P, '01', '2']]).ok;
  assert.deepEqual(ok.accepted, { value: [[P, '00ff', '1'], [P, '01', '2']] });
  assert.deepEqual(ok.deposits, [{ target: JAR, value: { value: [['', '', '1000000'], [P, '00ff', '1'], [P, '01', '2']] },
    memo: { rec: 'Note', fields: [{ bytes: alice }, { bytes: '7a' }] } }]);
  // A reserved-kind token name (32 bytes) under any policy cannot be deposited.
  assert.equal(tip(1000000, 1000000, [[P, '11'.repeat(32), '1']]).class, 'domain:deposit');
  assert.equal(tip(1000000, 1000000, [[P, '4f' + '11'.repeat(28), '1']]).class, 'domain:deposit');
  // A 29-byte name not starting 0x4F or 0x49 is fine.
  assert.ok('ok' in tip(1000000, 1000000, [[P, '4b' + '11'.repeat(28), '1']]));
  // The jar read: a credits map out of canonical order is a malformed read (§4).
  const bad = { rec: 'Tips.State', fields: [{ bytes: owner }, { map: [[{ bytes: 'b2'.repeat(28) }, { int: '1' }], [{ bytes: 'a2'.repeat(28) }, { int: '1' }]] },
    { value: [] }, { none: true }] };
  assert.equal(runCall(tipper, { method: 'tip', state, args: [{ bytes: alice }, { bytes: '7a' }, { int: '1' }],
    tx: { caller: alice, received: '1', balance: '0', validFrom: '0', validTo: '1', reads: { jar: bad },
      funds: { received: { value: [] }, held: { value: [] }, policy, self: '5f'.repeat(32) } }, params }).class, 'input:jar');
});

test('M2c deposits: an empty or a negative deposit value is refused (domain:deposit), on a hand-built program', () => {
  const POL = 'ab'.repeat(28);
  const inst = { name: 'Other', package: 't', state: { name: 'Other.State', fields: [{ name: 'n', type: 'Int' }], at }, records: [], enums: [],
    timeFields: [], receives: [null], bigMaps: false };
  const p = (valueExpr) => load(JSON.stringify({
    ir: 2, language: 'jsc 2', sourceSha256: '0'.repeat(64), package: 't', contract: 'T', source: 'T.jsc', options: [], records: [], enums: [],
    state: { name: 'State', fields: [{ name: 'n', type: 'Int' }], at },
    params: { name: 'Config', fields: [{ name: 'other', type: { instance: 'Other' } }], at },
    contracts: [inst], timeFields: [], recursiveFns: [], libraries: [], javaLibraries: [], fns: [], warnings: [],
    methods: [{ kind: 'action', name: 'give', params: [], mods: [{ name: 'open' }], checked: [], usesCtx: false, pays: false, result: { record: 'State' },
      body: { stmts: [{ stmt: 'Deposit', target: node('ParamsRef', { field: 'other' }, { instance: 'Other' }), value: valueExpr, memo: lit(1n), at }],
        result: node('StateRef', {}, { record: 'State' }), at }, at }],
  }));
  const params = { rec: 'Config', fields: [{ instance: [POL, 'cd'.repeat(32)] }] };
  const give = (prog) => runCall(prog, { method: 'give', state: { rec: 'State', fields: [{ int: '0' }] }, args: [], tx: null, params });
  const none = node('LedgerOp', { op: 'none', args: [] }, 'Value');
  const lovelace = (q) => node('LedgerOp', { op: 'lovelace', args: [lit(q)] }, 'Value');
  assert.equal(give(p(none)).class, 'domain:deposit');
  assert.equal(give(p(lovelace(-1n))).class, 'domain:deposit');
  assert.deepEqual(give(p(lovelace(5n))).ok.deposits, [{ target: { instance: [POL, 'cd'.repeat(32)] }, value: { value: [['', '', '5']] }, memo: { int: '1' } }]);
});

test('M2c receives T: Tips credit decodes Note memos and credits each beneficiary; a malformed memo is input:deposits', () => {
  const tips = fixture('Tips');
  const owner = '0a'.repeat(28), alice = 'a1'.repeat(28), bob = 'b2'.repeat(28), P = 'cc'.repeat(28);
  const state = { rec: 'State', fields: [{ bytes: owner }, { map: [] }, { value: [] }, { none: true }] };
  const note = (to, tag) => ({ rec: 'Note', fields: [{ bytes: to }, { bytes: tag }] });
  const credit = (deposits) => runCall(tips, { method: 'credit', state, args: [],
    tx: { caller: owner, received: '0', balance: '0', validFrom: '0', validTo: '1',
      funds: { received: { value: [] }, held: { value: [] }, policy: '70'.repeat(28), self: '71'.repeat(32), deposits } } });
  const ok = credit([
    { value: { value: [['', '', '3000000'], [P, '01', '1']] }, memo: note(bob, '01') },
    { value: { value: [['', '', '2000000']] }, memo: note(alice, '02') },
    { value: { value: [['', '', '1000000']] }, memo: note(bob, '03') },
  ]).ok;
  // Keys in canonical order; bob credited twice; the assets taken; the last tip records the caller and the total.
  assert.deepEqual(ok.value, { rec: 'State', fields: [
    { bytes: owner },
    { map: [[{ bytes: alice }, { int: '2000000' }], [{ bytes: bob }, { int: '4000000' }]] },
    { value: [[P, '01', '1']] },
    { some: { rec: 'Last', fields: [{ bytes: owner }, { value: [['', '', '6000000'], [P, '01', '1']] }] } },
  ] });
  assert.equal(credit([{ value: { value: [['', '', '1']] }, memo: note('a1'.repeat(27), '01') }]).class, 'input:deposits');
  assert.equal(credit([{ value: { value: [['', '', '1']] }, memo: null }]).class, 'input:deposits');
  assert.equal(credit([]).class, 'require:21:5:nothing deposited');
});

test('M2c send datums: Tipper refund sends a record datum, and an enum datum for a bonus', () => {
  const tipper = fixture('Tipper');
  const params = { rec: 'Config', fields: [{ instance: ['70'.repeat(28), '71'.repeat(32)] }, { int: '100' }] };
  const owner = '0a'.repeat(28), alice = 'a1'.repeat(28), policy = '5e'.repeat(28);
  const state = { rec: 'State', fields: [{ bytes: owner }, { int: '10000000' }, { bytes: '00' }] };
  const refund = (amount, bonus) => runCall(tipper, { method: 'refund', state, args: [{ bytes: alice }, { int: String(amount) }, bonus],
    tx: { caller: owner, received: '0', balance: '0', validFrom: '0', validTo: '1',
      funds: { received: { value: [] }, held: { value: [] }, policy, self: '5f'.repeat(32) } }, params });
  const to = { address: [{ keyCred: alice }, null] };
  const ok = refund(3000000, true).ok;
  assert.deepEqual(ok.sends, [
    { to, value: { value: [['', '', '3000000']] }, datum: { rec: 'Receipt', fields: [{ bytes: alice }, { int: '3000000' }] } },
    { to, value: { value: [['', '', '2000000']] }, datum: { rec: 'Bonus', fields: [{ int: '2' }] } },
  ]);
  assert.deepEqual(ok.value.fields[1], { int: '7000000' });
  assert.equal(refund(3000000, false).ok.sends.length, 1);
  assert.equal(refund(0, false).class, 'require:34:5:refund out of range');
});

test('M2c IR: the reader refuses an unknown field of an imported contract, a read and a receives modifier', () => {
  const text = readFileSync(new URL('./fixtures/Tipper.ir.json', import.meta.url), 'utf8');
  const j = JSON.parse(text);
  const bad1 = structuredClone(j);
  bad1.contracts[0].extra = 1;
  assert.throws(() => load(JSON.stringify(bad1)), /unknown field "extra"/);
  const bad2 = structuredClone(j);
  bad2.methods.find((m) => m.reads).reads[0].extra = 1;
  assert.throws(() => load(JSON.stringify(bad2)), /unknown field "extra"/);
  const tips = JSON.parse(readFileSync(new URL('./fixtures/Tips.ir.json', import.meta.url), 'utf8'));
  tips.methods.find((m) => m.name === 'credit').mods[0].field = 'x';
  assert.throws(() => load(JSON.stringify(tips)), /unknown field "field"/);
});

test('integration of #80 with M2: the strict modifier shapes cover quorum, authorized and receives, and every effect needs an audience', () => {
  const edit = (name, f) => () => {
    const p = JSON.parse(readFileSync(new URL(`./fixtures/${name}.ir.json`, import.meta.url), 'utf8'));
    f(p, (n) => p.methods.find((m) => m.name === n));
    return load(JSON.stringify(p));
  };
  for (const n of ['Treasury', 'Council', 'Tips', 'Coins', 'Dex', 'Tipper']) assert.doesNotThrow(edit(n, () => {}), n);
  // quorum: keys and threshold, both state fields or both params fields, of their types; no field beside them.
  assert.throws(edit('Treasury', (p, m) => { m('pay').mods[0].threshold = 'params.guardian'; }), /both state fields or both params fields/);
  assert.throws(edit('Treasury', (p, m) => { m('pay').mods[0].keys = 'quorum'; }), /quorum is not \{"list":"Key"\}/);
  assert.throws(edit('Treasury', (p, m) => { m('pay').mods[0].field = 'council'; }), /unknown field "field"/);
  assert.throws(edit('Treasury', (p, m) => { delete m('pay').mods[0].threshold; }), /missing field "threshold"/);
  // authorized: a Credential field; never with keys.
  assert.throws(edit('Treasury', (p, m) => { m('freeze').mods[0].field = 'council'; }), /council is not "Credential"/);
  assert.throws(edit('Treasury', (p, m) => { m('freeze').mods[0].keys = 'council'; }), /unknown field "keys"/);
  // An init takes payable only; a batched action has no quorum, authorized or receives.
  assert.throws(edit('Treasury', (p, m) => { m('init').mods.push({ name: 'quorum', keys: 'council', threshold: 'quorum' }); }), /an init takes only payable, not quorum/);
  assert.throws(edit('Treasury', (p, m) => { m('rotate').mods.push({ name: 'batched' }); }), /a batched action cannot use quorum/);
  assert.throws(edit('Tips', (p, m) => { m('credit').mods.push({ name: 'batched' }); }), /a batched action cannot use receives/);
  assert.throws(edit('Tips', (p, m) => { m('close').mods.push({ name: 'receives' }); }), /only an action receives/);
  assert.throws(edit('Tips', (p, m) => { m('credit').mods.push({ name: 'receives' }); }), /the modifier receives is given twice/);
  // A method that only sends (no pay) still says who may call it (JSC0408); quorum is an audience.
  assert.throws(edit('Coins', (p, m) => { m('forge').mods = []; }), /so it says who may call it/);
  assert.throws(edit('Treasury', (p, m) => { m('pay').mods = []; }), /so it says who may call it/);
});

// ADR-0018 D9 (M4b): a java: library's body has no reference semantics here. A call that reaches one gets no answer (a
// Limit, §2.4), never a refusal; the program's other code is evaluated as usual. The fixture is Guarded.jsc's export
// (julc-state-toolchain's JscV2JavaLibraryTest writes it), with CallJava nodes and the library's records.
test('§3.9 a java: library call has no answer; the rest of the program has one', () => {
  const guarded = load(readFileSync(new URL('./fixtures/java/Guarded.ir.json', import.meta.url), 'utf8'));
  const ann = fromHex('a1'.repeat(28));
  const s = new Rec('State', [fromHex('0a'.repeat(28)), [new Rec('Entry', [ann, 5n])], 0n]);
  assert.throws(() => guarded.call('limit', s, [ann], null), (e) => e instanceof Limit && /no reference semantics/.test(e.message));
  assert.equal(guarded.call('firstLimit', s, [], null).value, 5n);
  // A CallJava naming a fn the program did not import is refused at load.
  const text = readFileSync(new URL('./fixtures/java/Guarded.ir.json', import.meta.url), 'utf8');
  assert.throws(() => load(text.replace('"fn": "limitOf"', '"fn": "nowhere"')), /no fn nowhere imported/);
});

// The integration of M2 and M4: one IR format 2. Joined.ir.json (JscV2IrTest writes it) carries M2b's params and
// invariants, M2c's contracts, reads and a receives type, and M4's libraries; it loads, and an unknown field added to any
// one of its objects is refused, as the Java reader refuses it.
test('format 2 joined: params, invariants, contracts, reads, receives T and libraries load; an unknown field anywhere is refused', () => {
  const text = readFileSync(new URL('./fixtures/joined/Joined.ir.json', import.meta.url), 'utf8');
  const p = JSON.parse(text);
  for (const k of ['params', 'invariants', 'contracts', 'libraries', 'javaLibraries']) assert.ok(k in p, k);
  assert.equal(p.libraries[0].library, 'org.julclang.state.examples.dsl.Fees');
  assert.ok(p.methods.find((m) => m.name === 'look').reads.length === 1);
  assert.ok(p.methods.find((m) => m.name === 'take').mods.find((x) => x.name === 'receives').type);
  const joined = load(text);
  assert.ok(joined);
  // Every object of the export, each in its own copy with an extra field (types and a local's fieldTaints are maps of
  // their own kind, not objects with fields).
  const TYPE_KEYS = new Set(['list', 'option', 'record', 'enum', 'map', 'bigmap', 'instance', 'deposit']);
  const paths = [];
  const walk = (o, path) => {
    if (Array.isArray(o)) o.forEach((x, i) => walk(x, [...path, i]));
    else if (o && typeof o === 'object') {
      const keys = Object.keys(o);
      const isType = keys.length === 1 && TYPE_KEYS.has(keys[0]);
      if (isType || path.at(-1) === 'fieldTaints') return;
      paths.push(path);
      for (const k of keys) walk(o[k], [...path, k]);
    }
  };
  walk(p, []);
  const dropped = [];
  for (const path of paths) {
    const copy = structuredClone(p);
    let o = copy;
    for (const k of path) o = o[k];
    o.futureCheck = false;
    try {
      load(JSON.stringify(copy));
      dropped.push(path.join('.'));
    } catch (e) {
      assert.match(e.message, /futureCheck/, path.join('.'));
    }
  }
  assert.deepEqual(dropped, [], 'objects whose unknown field the reader took');
  assert.ok(paths.length > 100, `objects checked: ${paths.length}`);
  for (const k of ['params', 'invariants', 'contracts', 'libraries']) assert.ok(paths.some((x) => x[0] === k), `${k} was mutated`);
  // An absent params is not written: null is refused.
  for (const k of ['params', 'invariants', 'contracts']) {
    const q = JSON.parse(text);
    q[k] = null;
    assert.throws(() => load(JSON.stringify(q)), /is null/, k);
  }
});

// #98 review F2, C5: the strictness sweep over a program with javaLibraries and CallJava (Guarded.ir.json): an unknown
// field added to any one of its objects is refused, the java: fns' parameter and result types included.
test('format 2, java: libraries: an unknown field in any object of Guarded\'s export is refused', () => {
  const text = readFileSync(new URL('./fixtures/java/Guarded.ir.json', import.meta.url), 'utf8');
  const p = JSON.parse(text);
  assert.ok(p.javaLibraries.length > 0 && text.includes('"CallJava"'));
  const paths = [];
  // Every object, type objects included ({ "list": T } with a field more is refused too), but a local's fieldTaints,
  // a map of its own kind.
  const walk = (o, path) => {
    if (Array.isArray(o)) o.forEach((x, i) => walk(x, [...path, i]));
    else if (o && typeof o === 'object') {
      if (path.at(-1) === 'fieldTaints') return;
      paths.push(path);
      for (const k of Object.keys(o)) walk(o[k], [...path, k]);
    }
  };
  walk(p, []);
  const taken = [];
  for (const path of paths) {
    const copy = structuredClone(p);
    let o = copy;
    for (const k of path) o = o[k];
    o.futureCheck = false;
    try {
      load(JSON.stringify(copy));
      taken.push(path.join('.'));
    } catch (e) {
      assert.match(e.message, /futureCheck/, path.join('.'));
    }
  }
  assert.deepEqual(taken, [], 'objects whose unknown field the reader took');
  // A type object inside a java: fn's signature, too (the review's case: a result { record: Entry } with a field more).
  for (const f of p.javaLibraries[0].fns) {
    const copy = structuredClone(p);
    const g = copy.javaLibraries[0].fns.find((x) => x.name === f.name);
    const t = typeof g.result === 'object' ? g.result : null;
    if (!t) continue;
    t.futureField = true;
    assert.throws(() => load(JSON.stringify(copy)), /a bad type/, f.name);
  }
  assert.ok(paths.length > 100, `objects checked: ${paths.length}`);
});

// #98 review S5: params.f names the program's params only, never a record of another name space called Config.
test('a quorum over params.f in a contract without params is refused, even with a record named Config', () => {
  const p = JSON.parse(readFileSync(new URL('./fixtures/Counter2.ir.json', import.meta.url), 'utf8'));
  p.records.push({ name: 'Config', fields: [{ name: 'k', type: { list: 'Key' } }, { name: 't', type: 'Int' }], at: [1, 1] });
  p.methods.find((m) => m.name === 'increment').mods = [{ name: 'quorum', keys: 'params.k', threshold: 'params.t' }];
  assert.throws(() => load(JSON.stringify(p)), /params.k: the contract has no params/);
});

// #98 review C6: a present null in a modifier is malformed in both readers, never read as an absent field.
test('a modifier\'s present null is refused, never normalised away (Joined, Treasury, Tips, Pool)', () => {
  const fix = (n) => JSON.parse(readFileSync(new URL(`./fixtures/${n}.ir.json`, import.meta.url), 'utf8'));
  const refused = (p, re) => assert.throws(() => load(JSON.stringify(p)), re);
  let p = fix('joined/Joined');
  p.methods.find((m) => m.name === 'take').mods[0].type = null;
  refused(p, /a bad type null/);
  p = fix('Treasury');
  p.methods.find((m) => m.name === 'pay').mods[0].field = null;
  refused(p, /unknown field "field"/);
  p = fix('Tips');
  p.methods.find((m) => m.name === 'claim').mods[0].field = null;
  refused(p, /unknown field "field"/);
  p = fix('Pool');
  const b = p.methods.find((m) => m.mods.some((x) => x.name === 'batched' && !('field' in x)));
  b.mods.find((x) => x.name === 'batched').field = null;
  refused(p, /batched names no field or "only", not null/);
});

// #98 review C11: a duplicate java: fn cannot hide a malformed signature: listed twice is refused, in both readers.
test('a java: fn listed twice is refused, whichever copy is malformed', () => {
  const text = readFileSync(new URL('./fixtures/java/Guarded.ir.json', import.meta.url), 'utf8');
  for (const bad of [(f) => { f.result = { record: 'Nonexistent', futureField: true }; }, (f) => { f.params = [{ list: 'Int', futureField: true }]; }]) {
    const p = JSON.parse(text);
    const fns = p.javaLibraries[0].fns;
    const copy = structuredClone(fns[0]);
    bad(copy);
    fns.unshift(copy);
    assert.throws(() => load(JSON.stringify(p)), /listed twice|a bad type/);
  }
});

// #110 C17: a java: library listed twice is refused, in both readers (JscV2IrTest.aJavaLibraryListedTwice_isRefused).
test('a java: library listed twice is refused', () => {
  const p = JSON.parse(readFileSync(new URL('./fixtures/java/Guarded.ir.json', import.meta.url), 'utf8'));
  p.javaLibraries.push(structuredClone(p.javaLibraries[0]));
  assert.throws(() => load(JSON.stringify(p)), /listed twice/);
});
