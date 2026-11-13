// Validates an IR export (docs/reference/jsc-2-ir.md) in one pass before anything is evaluated (M1b review S3, F6,
// F7; r3: C4, S7). A version-1 reader refuses what it does not know (a node, statement, field shape, operator, modifier,
// std row, type). Every local is declared once, and referenced only where it is in scope: a method's or fn's
// parameters in its body, a const after its initializer to the end of its block, a loop, lambda or arm binding in its
// body. Every fn call names a fn with that many parameters, every field read names a field of its record, every record,
// variant and std call has its arity. Ids are safe integers, integer literals decimal, bytes lower-case hex. And it
// recomputes two decisions of the checker from their definitions, refusing an IR that disagrees: the checked
// parameters (semantics §4) and whether a method reads the transaction (§2).

import { ROWS } from './std.js';

const PRIMS = new Set(['Int', 'Bool', 'Bytes', 'Key', 'Hash', 'Value', 'Address', 'Credential']);
const INT_RE = /^-?(0|[1-9][0-9]*)$/;
const HEX_RE = /^(?:[0-9a-f]{2})*$/;
const IDENT = /^[A-Za-z_][A-Za-z0-9_]*$/;

// Each node's own fields: e expression, e? expression or null, es expressions, b block, b? block or null, l a local's
// declaration, t a type, s a string, z a boolean, arms match arms, and a Set for a string from a fixed list.
const set = (...xs) => new Set(xs);
export const NODES = {
  Lit: { value: 'lit' },
  LocalRef: { local: 'ref' },
  StateRef: {},
  StateField: { field: 's' },
  CtxRef: { name: set('caller', 'received', 'balance', 'validFrom', 'validTo') },
  NoneLit: {},
  SomeOf: { value: 'e' },
  GetField: { target: 'e', record: 's', field: 's' },
  New: { record: 's', values: 'es' },
  CallFn: { fn: 's', args: 'es' },
  CallStd: { module: 's', name: 's', args: 'es' },
  Prelude: { op: set('min', 'max', 'abs', 'asKey', 'asHash'), args: 'es' },
  TimePred: { after: 'z', threshold: 'e' },
  ListLit: { items: 'es', rest: 'e?' },
  Neg: { operand: 'e' },
  Not: { operand: 'e' },
  Arith: { op: set('+', '-', '*', '/', '%'), left: 'e', right: 'e' },
  Compare: { op: set('<', '<=', '>', '>='), left: 'e', right: 'e' },
  Equals: { negate: 'z', left: 'e', right: 'e', operand: 't' },
  Logic: { op: set('&&', '||'), left: 'e', right: 'e' },
  Cond: { cond: 'e', then: 'e', otherwise: 'e' },
  Coalesce: { option: 'e', default: 'e' },
  BlockValue: { block: 'b' },
  IfValue: { branches: 'branches', otherwise: 'b' },
  ListProp: { op: set('length', 'isEmpty', 'head', 'sum', 'reverse'), list: 'e' },
  Concat: { left: 'e', right: 'e' },
  Contains: { list: 'e', value: 'e', elem: 't' },
  Hof: { kind: set('any', 'all', 'find', 'filter', 'map'), list: 'e', x: 'l', body: 'b' },
  Fold: { list: 'e', init: 'e', acc: 'l', x: 'l', body: 'b' },
  Variant: { enum: 's', variant: 's', values: 'es' },
  Match: { scrutinee: 'e', arms: 'arms' },
  MapEmpty: {},
  MapOp: { op: set('get', 'has', 'set', 'delete', 'size', 'keys', 'values', 'isEmpty'), map: 'e', args: 'es' },
  MapHof: { kind: set('any', 'all'), map: 'e', k: 'l', v: 'l', body: 'b' },
  MapFold: { map: 'e', init: 'e', acc: 'l', k: 'l', v: 'l', body: 'b' },
  BigEmpty: {},
  BigOp: { op: set('get', 'has', 'at', 'set', 'delete', 'isEmpty'), map: 'e', args: 'es' },
  Range: { from: 'e', to: 'e' },
  ListArg: { op: set('take', 'drop'), list: 'e', arg: 'e' },
  // M2: funds, and the operations on values, credentials and addresses (additive in version 1).
  FundsRef: { field: set('received', 'held', 'policy', 'self') },
  LedgerOp: { op: set('none', 'lovelace', 'asset', 'add', 'sub', 'lovelaceOf', 'of', 'covers', 'keyCred', 'scriptCred', 'wallet',
    'walletStaked', 'script', 'scriptStaked', 'payment'), args: 'es' },
};
// Fields are validated in this order: an initializer or collection before the binding it introduces (review C4).
export const STMTS = {
  Let: { value: 'e', local: 'let' },
  Require: { cond: 'e', message: 's' },
  Pay: { to: 'e', amount: 'e' },
  Send: { to: 'e', value: 'e' },
  Mint: { burn: 'z', name: 'e', qty: 'e' },
  Accept: { value: 'e' },
  If: { branches: 'branches', otherwise: 'b?', pays: 'z' },
  For: { list: 'e', var: 'l', body: 'b', pays: 'z' },
  Match: { scrutinee: 'e', arms: 'arms', pays: 'z' },
};
// batched is M1c's (#67): known here so that its IR loads; like every modifier but open and only, it makes a method
// read the transaction (review F11).
const MODS = set('payable', 'open', 'only', 'after', 'before', 'batched');
// Argument counts of the std rows, the prelude and the map operations (review S7).
const STD_ARITY = {
  sha2_256: 1, sha3_256: 1, blake2b_256: 1, blake2b_224: 1, verifyEd25519: 3, length: 1, concat: 2, slice: 3, take: 2, drop: 2,
  byteAt: 2, toInt: 1, toHex: 1, div: 2, mod: 2, pow: 2, expMod: 3, sign: 1, andBytes: 3, orBytes: 3, xorBytes: 3, complement: 1,
  readBit: 2, shift: 2, rotate: 2, countSetBits: 1, findFirstSetBit: 1, last: 1, nth: 2, indexOf: 2, elapsedSince: 1,
  elapsedSinceAtMost: 1, windowStart: 0, windowEnd: 0, isEmpty: 1, withoutLovelace: 1, scale: 2, paymentKey: 1, paymentScript: 1,
};
const LEDGER_ARITY = {
  none: 0, lovelace: 1, asset: 3, add: 2, sub: 2, lovelaceOf: 1, of: 3, covers: 2, keyCred: 1, scriptCred: 1, wallet: 1, walletStaked: 2,
  script: 1, scriptStaked: 2, payment: 1,
};
// A credential's constructors (M2), matched like an enum's variants: KeyCred { key }, ScriptCred { hash }.
const CRED_FIELDS = { KeyCred: 'key', ScriptCred: 'hash' };
const PRELUDE_ARITY = { min: 2, max: 2, abs: 1, asKey: 1, asHash: 1 };
const MAP_ARITY = { get: 1, has: 1, set: 2, delete: 1, size: 0, keys: 0, values: 0, isEmpty: 0, at: 1 };
const KINDS = set('init', 'action', 'destroy', 'view');

export function validate(p) {
  const fail = (where, what) => {
    throw new Error(`invalid IR (${where}): ${what}`);
  };
  const keys = (o, allowed, where) => {
    if (!o || typeof o !== 'object' || Array.isArray(o)) fail(where, 'not an object');
    for (const k of Object.keys(o)) if (!allowed.includes(k)) fail(where, `unknown field "${k}"`);
    for (const k of allowed) if (!(k in o) && !(k === 'fieldTaints' || k === 'field')) fail(where, `missing field "${k}"`);
  };
  const str = (v, where) => {
    if (typeof v !== 'string') fail(where, 'not a string');
    return v;
  };
  const at = (v, where) => {
    if (!Array.isArray(v) || v.length !== 2 || !v.every((n) => Number.isSafeInteger(n) && n >= 0)) fail(where, 'a bad position');
  };
  const nat = (v, where) => {
    if (!Number.isSafeInteger(v) || v < 0) fail(where, 'not a non-negative integer');
  };

  // ---- top level and declarations
  keys(p, ['ir', 'language', 'sourceSha256', 'package', 'contract', 'source', 'options', 'records', 'enums', 'state', 'timeFields',
    'recursiveFns', 'fns', 'methods', 'warnings'], 'program');
  if (p.ir !== 1 || typeof p.ir !== 'number') fail('program', `"ir" must be exactly 1, not ${JSON.stringify(p.ir)}`);
  if (p.language !== 'jsc 2') fail('program', `not a .jsc 2 program: ${p.language}`);
  if (typeof p.sourceSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(p.sourceSha256)) fail('program', 'sourceSha256 must be 64 hex digits');
  for (const k of ['package', 'contract', 'source']) str(p[k], k);
  for (const o of p.options) {
    keys(o, ['name', 'value'], 'option');
    if (!INT_RE.test(str(o.value, 'option'))) fail('option', `not an integer: ${o.value}`);
  }
  const shapes = new Map();
  const record = (r, where) => {
    keys(r, ['name', 'fields', 'at'], where);
    if (!IDENT.test(str(r.name, where))) fail(where, `a bad name ${r.name}`);
    if (shapes.has(r.name)) fail(where, `two declarations named ${r.name} (records, variants and the state share one namespace)`);
    for (const f of r.fields) keys(f, ['name', 'type'], `${r.name}'s field`);
    shapes.set(r.name, r.fields);
    at(r.at, where);
  };
  for (const r of p.records) record(r, 'record');
  const enums = new Map();
  for (const e of p.enums) {
    keys(e, ['name', 'variants', 'at'], 'enum');
    if (enums.has(e.name) || shapes.has(e.name)) fail('enum', `two declarations named ${e.name}`);
    enums.set(e.name, e.variants.map((v) => v.name));
    for (const v of e.variants) record(v, `variant of ${e.name}`);
  }
  if (p.state.name !== 'State') fail('state', 'the state record is named State');
  record(p.state, 'state');
  const type = (t, where) => {
    if (typeof t === 'string') {
      if (!PRIMS.has(t)) fail(where, `unknown type ${t}`);
      return;
    }
    if (!t || typeof t !== 'object' || Object.keys(t).length !== 1) fail(where, `a bad type ${JSON.stringify(t)}`);
    const [k, v] = Object.entries(t)[0];
    if (k === 'list' || k === 'option') type(v, where);
    else if (k === 'map' || k === 'bigmap') {
      if (!Array.isArray(v) || v.length !== 2) fail(where, `a bad ${k} type`);
      type(v[0], where);
      type(v[1], where);
    } else if (k === 'record') {
      if (!shapes.has(v) || [...enums.values()].some((vs) => vs.includes(v))) fail(where, `no record ${v}`);
    } else if (k === 'enum') {
      if (!enums.has(v)) fail(where, `no enum ${v}`);
    } else fail(where, `unknown type ${k}`);
  };
  for (const [name, fields] of shapes) for (const f of fields) type(f.type, `${name}.${f.name}`);
  for (const k of ['timeFields', 'recursiveFns']) for (const x of p[k]) str(x, k);
  for (const w of p.warnings) keys(w, ['line', 'col', 'code', 'message'], 'warning');

  // ---- bodies: locals declared once (ids unique in the program), referenced only in scope (review S7)
  const locals = new Map();
  const decl = (l, where, scope) => {
    keys(l, ['id', 'name', 'type', 'taint', 'at', 'fieldTaints'], where);
    nat(l.id, where);
    nat(l.taint, where);
    str(l.name, where);
    type(l.type, where);
    at(l.at, where);
    if (locals.has(l.id)) fail(where, `the local id ${l.id} is declared twice`);
    locals.set(l.id, l);
    scope.add(l.id);
  };
  /** A block's statements in order in a scope of its own (a const is in scope from its statement on). */
  const block = (b, where, outer) => {
    keys(b, ['stmts', 'result', 'at'], where);
    const scope = new Set(outer);
    for (const st of b.stmts) stmt(st, scope);
    if (b.result !== null) expr(b.result, scope);
    at(b.at, where);
  };
  const arms = (as, where, scope, scrutineeType) => {
    for (const a of as) {
      keys(a, ['ctors', 'fields', 'binds', 'body', 'at'], where);
      a.ctors.forEach((c) => str(c, where));
      a.fields.forEach((c) => str(c, where));
      if (a.binds.length !== a.fields.length) fail(where, 'binds and fields differ in length');
      if (a.binds.length > 0 && a.ctors.length !== 1) fail(where, 'only a single-constructor arm binds');
      for (const c of a.ctors) {
        if (c === 'Some' || c === 'None' || c in CRED_FIELDS) continue;
        if (!(shapes.has(c) && [...enums.values()].some((vs) => vs.includes(c)))) fail(where, `no variant ${c}`);
      }
      for (const f of a.fields) {
        const c = a.ctors[0];
        const known = c === 'Some' ? f === 'value' : c in CRED_FIELDS ? f === CRED_FIELDS[c] : (shapes.get(c) ?? []).some((x) => x.name === f);
        if (!known) fail(where, `${c} has no field ${f}`);
      }
      const inner = new Set(scope);
      a.binds.forEach((l) => decl(l, where, inner));
      block(a.body, where, inner);
    }
  };
  /** An if's or else-if chain's tests (M1e flattened the chain, J18): at least one; each a condition and its arm. */
  const branches = (bs, where, scope) => {
    if (!Array.isArray(bs) || bs.length === 0) fail(where, 'an if has at least one branch');
    for (const b of bs) {
      keys(b, ['cond', 'then', 'at'], where);
      expr(b.cond, scope);
      bool(b.cond, where, 'a branch condition');
      block(b.then, where, scope);
      at(b.at, where);
    }
  };
  /** A condition or a boolean operand is of type Bool (review S3 on #76): the interpreter never tests truthiness. */
  const bool = (e, where, what) => {
    if (e.type !== 'Bool') fail(where, `${what} is of type ${JSON.stringify(e.type)}, not Bool`);
  };
  /** A node's own fields; bindings it introduces (a loop variable, lambda parameters) are in scope in its blocks only. */
  const fields = (o, spec, where, scope) => {
    const inner = new Set(scope);
    for (const [k, kind] of Object.entries(spec)) {
      const v = o[k];
      if (kind instanceof Set) {
        if (!kind.has(v)) fail(where, `unknown ${k} ${JSON.stringify(v)}`);
      } else if (kind === 'e') expr(v, scope);
      else if (kind === 'e?') { if (v !== null) expr(v, scope); }
      else if (kind === 'es') { if (!Array.isArray(v)) fail(where, `${k} is not a list`); v.forEach((x) => expr(x, scope)); }
      else if (kind === 'b') block(v, where, inner);
      else if (kind === 'b?') { if (v !== null) block(v, where, inner); }
      else if (kind === 'l') decl(v, where, inner);
      else if (kind === 'let') decl(v, where, scope);   // a const: in scope for the rest of its block
      else if (kind === 't') type(v, where);
      else if (kind === 's') str(v, where);
      else if (kind === 'z') { if (typeof v !== 'boolean') fail(where, `${k} is not a boolean`); }
      else if (kind === 'arms') arms(v, where, scope);
      else if (kind === 'branches') branches(v, where, scope);
      else if (kind === 'ref') {
        if (!locals.has(v)) fail(where, `a reference to the undeclared local ${v}`);
        if (!scope.has(v)) fail(where, `a reference to the local ${v} (${locals.get(v).name}) outside its scope`);
      } else if (kind !== 'lit') throw new Error(kind);
    }
  };
  const fnArity = new Map(p.fns.map((f) => [f.name, Array.isArray(f.params) ? f.params.length : -1]));
  const arity = (where, what, got, want) => {
    if (got !== want) fail(where, `${what} takes ${want} argument(s), not ${got}`);
  };
  function expr(e, scope) {
    const spec = e && NODES[e.node];
    if (!spec) fail('expression', `unknown node ${JSON.stringify(e?.node)}`);
    const where = `${e.node} at ${e.at}`;
    keys(e, ['node', ...Object.keys(spec), 'type', 'taint', 'at'], where);
    fields(e, spec, where, scope);
    type(e.type, where);
    nat(e.taint, where);
    at(e.at, where);
    if (e.node === 'Lit') {
      const v = e.value;
      if (e.type === 'Bool') { if (typeof v !== 'boolean') fail(where, 'a Bool literal is a boolean'); }
      else if (e.type === 'Int') { if (typeof v !== 'string' || !INT_RE.test(v)) fail(where, `not a decimal integer: ${JSON.stringify(v)}`); }
      else if (['Bytes', 'Key', 'Hash'].includes(e.type)) { if (typeof v !== 'string' || !HEX_RE.test(v)) fail(where, 'not lower-case hex'); }
      else fail(where, `a literal of type ${JSON.stringify(e.type)}`);
    }
    if (e.node === 'Cond') bool(e.cond, where, 'a condition');
    if (e.node === 'Logic') { bool(e.left, where, 'an operand'); bool(e.right, where, 'an operand'); }
    if (e.node === 'Not') bool(e.operand, where, 'an operand');
    if (e.node === 'CallStd' && !(ROWS[e.module] ?? []).includes(e.name)) fail(where, `unknown std row ${e.module}.${e.name}`);
    if ((e.node === 'New' || e.node === 'GetField') && !shapes.has(e.record)) fail(where, `no record ${e.record}`);
    if (e.node === 'Variant' && !(enums.get(e.enum) ?? []).includes(e.variant)) fail(where, `no variant ${e.enum}.${e.variant}`);
    // Review S7: references and arities.
    if (e.node === 'CallFn') {
      if (!fnArity.has(e.fn)) fail(where, `no fn ${e.fn}`);
      arity(where, `fn ${e.fn}`, e.args.length, fnArity.get(e.fn));
    }
    if (e.node === 'CallStd') arity(where, e.name, e.args.length, STD_ARITY[e.name]);
    if (e.node === 'LedgerOp') arity(where, e.op, e.args.length, LEDGER_ARITY[e.op]);
    if (e.node === 'Prelude') arity(where, e.op, e.args.length, PRELUDE_ARITY[e.op]);
    if (e.node === 'MapOp' || e.node === 'BigOp') arity(where, `${e.node}.${e.op}`, e.args.length, MAP_ARITY[e.op]);
    if (e.node === 'New') arity(where, `the record ${e.record}`, e.values.length, shapes.get(e.record).length);
    if (e.node === 'Variant') arity(where, `the variant ${e.variant}`, e.values.length, shapes.get(e.variant).length);
    if (e.node === 'GetField' && !shapes.get(e.record).some((f) => f.name === e.field)) fail(where, `${e.record} has no field ${e.field}`);
    if (e.node === 'StateField' && !shapes.get('State').some((f) => f.name === e.field)) fail(where, `the state has no field ${e.field}`);
  }
  function stmt(s, scope) {
    const spec = s && STMTS[s.stmt];
    if (!spec) fail('statement', `unknown statement ${JSON.stringify(s?.stmt)}`);
    const where = `${s.stmt} at ${s.at}`;
    keys(s, ['stmt', ...Object.keys(spec), 'at'], where);
    fields(s, spec, where, scope);
    if (s.stmt === 'Require') bool(s.cond, where, 'a require condition');
    at(s.at, where);
  }
  const fnNames = new Set();
  for (const f of p.fns) {
    keys(f, ['name', 'params', 'result', 'body', 'at'], 'fn');
    if (fnNames.has(f.name)) fail('fn', `two fns named ${f.name}`);
    fnNames.add(f.name);
    const scope = new Set();   // a fn sees its parameters only
    f.params.forEach((l) => decl(l, `fn ${f.name}`, scope));
    type(f.result, `fn ${f.name}`);
    block(f.body, `fn ${f.name}`, scope);
  }
  const methodNames = new Set();
  for (const m of p.methods) {
    const where = `method ${m.name}`;
    keys(m, ['kind', 'name', 'params', 'mods', 'checked', 'usesCtx', 'pays', 'result', 'body', 'at'], where);
    if (!KINDS.has(m.kind)) fail(where, `unknown kind ${m.kind}`);
    if (methodNames.has(m.name)) fail(where, 'two methods of that name');
    methodNames.add(m.name);
    const scope = new Set();
    m.params.forEach((l) => decl(l, where, scope));
    for (const mod of m.mods) {
      keys(mod, ['name', 'field'], where);
      if (!MODS.has(mod.name)) fail(where, `unknown modifier ${mod.name}`);
    }
    if (m.result !== null) type(m.result, where);
    block(m.body, where, scope);
    // F7: the checker's two decisions, recomputed from their definitions.
    const checked = m.kind === 'view' ? [] : m.params.filter((l) => needsCheck(l.type, shapes, enums)).map((l) => l.id);
    if (JSON.stringify(checked) !== JSON.stringify(m.checked)) {
      fail(where, `checked is ${JSON.stringify(m.checked)}, but the parameters whose types hold a Key, a Hash or a Map are ${JSON.stringify(checked)}`);
    }
    const usesCtx = m.kind === 'destroy' || (m.kind !== 'view'
      && (m.mods.some((x) => x.name !== 'open' && x.name !== 'only') || readsTx(m.body)));
    if (usesCtx !== m.usesCtx) fail(where, `usesCtx is ${m.usesCtx}, but the method ${usesCtx ? 'reads' : 'does not read'} the transaction`);
  }
  for (const n of p.recursiveFns) if (!fnNames.has(n)) fail('recursiveFns', `no fn ${n}`);
}

/** §4: a type holds a Key, a Hash or a Map somewhere (records and variants through their fields). */
function needsCheck(t, shapes, enums) {
  if (typeof t === 'string') return t === 'Key' || t === 'Hash' || t === 'Address' || t === 'Credential';
  const [k, v] = Object.entries(t)[0];
  if (k === 'list' || k === 'option') return needsCheck(v, shapes, enums);
  if (k === 'map') return true;
  if (k === 'bigmap') return false;
  if (k === 'record') return shapes.get(v).some((f) => needsCheck(f.type, shapes, enums));
  return enums.get(v).some((variant) => shapes.get(variant).some((f) => needsCheck(f.type, shapes, enums)));
}

/** Whether a method body names the transaction: caller, received, balance, the window, after/before or std/time. */
function readsTx(node) {
  if (Array.isArray(node)) return node.some(readsTx);
  if (!node || typeof node !== 'object') return false;
  if (node.node === 'CtxRef' || node.node === 'TimePred' || (node.node === 'CallStd' && node.module === 'std/time')) return true;
  return Object.values(node).some(readsTx);
}
