// Validates an IR export (docs/reference/jsc-2-ir.md) in one pass before anything is evaluated (M1b review S3, F6,
// F7). A version-1 reader refuses what it does not know (a node, statement, field shape, operator, modifier, std row,
// type), every local is declared once before it is referenced, ids are safe integers, integer literals are decimal and
// bytes are lower-case hex. And it recomputes two decisions of the checker from their definitions, refusing an IR
// that disagrees: the checked parameters (semantics §4) and whether a method reads the transaction (§2).

import { ROWS } from './std.js';

const PRIMS = new Set(['Int', 'Bool', 'Bytes', 'Key', 'Hash']);
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
  IfValue: { cond: 'e', then: 'b', otherwise: 'b' },
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
};
export const STMTS = {
  Let: { local: 'l', value: 'e' },
  Require: { cond: 'e', message: 's' },
  Pay: { to: 'e', amount: 'e' },
  If: { cond: 'e', then: 'b', otherwise: 'b?', pays: 'z' },
  For: { var: 'l', list: 'e', body: 'b', pays: 'z' },
  Match: { scrutinee: 'e', arms: 'arms', pays: 'z' },
};
const MODS = set('payable', 'open', 'only', 'after', 'before');
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

  // ---- bodies: locals declared once, before use (document order, as IrJson writes and reads them)
  const locals = new Map();
  const decl = (l, where) => {
    keys(l, ['id', 'name', 'type', 'taint', 'at', 'fieldTaints'], where);
    nat(l.id, where);
    nat(l.taint, where);
    str(l.name, where);
    type(l.type, where);
    at(l.at, where);
    if (locals.has(l.id)) fail(where, `the local id ${l.id} is declared twice`);
    locals.set(l.id, l);
  };
  const block = (b, where) => {
    keys(b, ['stmts', 'result', 'at'], where);
    for (const s of b.stmts) stmt(s);
    if (b.result !== null) expr(b.result);
    at(b.at, where);
  };
  const arms = (as, where) => {
    for (const a of as) {
      keys(a, ['ctors', 'fields', 'binds', 'body', 'at'], where);
      a.ctors.forEach((c) => str(c, where));
      a.fields.forEach((c) => str(c, where));
      if (a.binds.length !== a.fields.length) fail(where, 'binds and fields differ in length');
      a.binds.forEach((l) => decl(l, where));
      block(a.body, where);
    }
  };
  const fields = (o, spec, where) => {
    for (const [k, kind] of Object.entries(spec)) {
      const v = o[k];
      if (kind instanceof Set) {
        if (!kind.has(v)) fail(where, `unknown ${k} ${JSON.stringify(v)}`);
      } else if (kind === 'e') expr(v);
      else if (kind === 'e?') { if (v !== null) expr(v); }
      else if (kind === 'es') { if (!Array.isArray(v)) fail(where, `${k} is not a list`); v.forEach(expr); }
      else if (kind === 'b') block(v, where);
      else if (kind === 'b?') { if (v !== null) block(v, where); }
      else if (kind === 'l') decl(v, where);
      else if (kind === 't') type(v, where);
      else if (kind === 's') str(v, where);
      else if (kind === 'z') { if (typeof v !== 'boolean') fail(where, `${k} is not a boolean`); }
      else if (kind === 'arms') arms(v, where);
      else if (kind === 'ref') { if (!locals.has(v)) fail(where, `a reference to the undeclared local ${v}`); }
      else if (kind !== 'lit') throw new Error(kind);
    }
  };
  function expr(e) {
    const spec = e && NODES[e.node];
    if (!spec) fail('expression', `unknown node ${JSON.stringify(e?.node)}`);
    const where = `${e.node} at ${e.at}`;
    keys(e, ['node', ...Object.keys(spec), 'type', 'taint', 'at'], where);
    // Children first, in the writer's order, so a declaration precedes its references.
    fields(e, spec, where);
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
    if (e.node === 'CallStd' && !(ROWS[e.module] ?? []).includes(e.name)) fail(where, `unknown std row ${e.module}.${e.name}`);
    if ((e.node === 'New' || e.node === 'GetField') && !shapes.has(e.record)) fail(where, `no record ${e.record}`);
    if (e.node === 'Variant' && !(enums.get(e.enum) ?? []).includes(e.variant)) fail(where, `no variant ${e.enum}.${e.variant}`);
  }
  function stmt(s) {
    const spec = s && STMTS[s.stmt];
    if (!spec) fail('statement', `unknown statement ${JSON.stringify(s?.stmt)}`);
    const where = `${s.stmt} at ${s.at}`;
    keys(s, ['stmt', ...Object.keys(spec), 'at'], where);
    fields(s, spec, where);
    at(s.at, where);
  }
  const fnNames = new Set();
  for (const f of p.fns) {
    keys(f, ['name', 'params', 'result', 'body', 'at'], 'fn');
    if (fnNames.has(f.name)) fail('fn', `two fns named ${f.name}`);
    fnNames.add(f.name);
    f.params.forEach((l) => decl(l, `fn ${f.name}`));
    type(f.result, `fn ${f.name}`);
    block(f.body, `fn ${f.name}`);
  }
  const methodNames = new Set();
  for (const m of p.methods) {
    const where = `method ${m.name}`;
    keys(m, ['kind', 'name', 'params', 'mods', 'checked', 'usesCtx', 'pays', 'result', 'body', 'at'], where);
    if (!KINDS.has(m.kind)) fail(where, `unknown kind ${m.kind}`);
    if (methodNames.has(m.name)) fail(where, 'two methods of that name');
    methodNames.add(m.name);
    m.params.forEach((l) => decl(l, where));
    for (const mod of m.mods) {
      keys(mod, ['name', 'field'], where);
      if (!MODS.has(mod.name)) fail(where, `unknown modifier ${mod.name}`);
    }
    if (m.result !== null) type(m.result, where);
    block(m.body, where);
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
  if (typeof t === 'string') return t === 'Key' || t === 'Hash';
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
