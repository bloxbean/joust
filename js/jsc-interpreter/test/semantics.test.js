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
  refuses((t) => t.replace('"name": "only"', '"name": "batched"'), /usesCtx is false, but the method reads the transaction/);
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
