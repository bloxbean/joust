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
// M2c: an imported contract's type, qualified by the contract (Oracle.State, Tips.Note).
const QUALIFIED = /^[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*$/;

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
  CallJava: { library: 's', fn: 's', args: 'es' },
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
  FundsRef: { field: set('received', 'held', 'policy', 'self', 'deposits') },
  LedgerOp: { op: set('none', 'lovelace', 'asset', 'add', 'sub', 'lovelaceOf', 'of', 'covers', 'keyCred', 'scriptCred', 'wallet',
    'walletStaked', 'script', 'scriptStaked', 'payment'), args: 'es' },
  // M2b: a field of the deploy-time params (additive in version 1).
  ParamsRef: { field: 's' },
  // M2c: a deposit's value, or its memo (a receiving action's funds.deposits elements).
  DepositField: { target: 'e', field: set('value', 'memo') },
};
// Fields are validated in this order: an initializer or collection before the binding it introduces (review C4).
export const STMTS = {
  Let: { value: 'e', local: 'let' },
  Require: { cond: 'e', message: 's' },
  Pay: { to: 'e', amount: 'e' },
  Send: { to: 'e', value: 'e' },
  Mint: { burn: 'z', name: 'e', qty: 'e' },
  Accept: { value: 'e' },
  Emit: { event: 'e' },
  // M2c: deposit(target, value, memo); Send's datum is optional (validated by SEND_DATUM when present).
  Deposit: { target: 'e', value: 'e', memo: 'e' },
  If: { branches: 'branches', otherwise: 'b?', pays: 'z' },
  For: { list: 'e', var: 'l', body: 'b', pays: 'z' },
  Match: { scrutinee: 'e', arms: 'arms', pays: 'z' },
};
// batched is M1c's (#67): known here so that its IR loads; like every modifier but open and only, it makes a method
// read the transaction (review F11).
const MODS = set('payable', 'open', 'only', 'after', 'before', 'batched', 'quorum', 'authorized', 'receives', 'admits');
// M2b: quorum and authorized read the signatories and withdrawals in the validator, so like open and only they do not
// make a method read the transaction (Ctx).
const NO_CTX = set('open', 'only', 'quorum', 'authorized');
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
// M3a (ADR-0003, ADR-0012): an owned method's kinds.
const KINDS = set('init', 'action', 'destroy', 'view', 'ownedInit', 'ownedAction', 'ownedDestroy');
// #101 review F2: the parts of M3a the checker produces so far (1: owned objects of one type with shared reads; 2: native
// assets in owned methods, payable owned destroys; 3: moves and invariants by type), as the Java reader's OWNED_STAGE.
const OWNED_STAGE = 1;

/** Whether raw IR holds a statement or expression node of one of the names, at any depth. */
function hasNode(json, names) {
  if (Array.isArray(json)) return json.some((v) => hasNode(v, names));
  if (json !== null && typeof json === 'object') {
    if (names.includes(json.stmt) || names.includes(json.node)) return true;
    return Object.values(json).some((v) => hasNode(v, names));
  }
  return false;
}

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
  // M2b (additive): "params" and "invariants" appear only when the contract declares them.
  // M2c (additive): "contracts" appears only when the contract imports another.
  // M3a (additive): "owned" appears only when the contract declares owned types.
  const optional = ['params', 'invariants', 'contracts', 'owned'].filter((k) => k in p);
  for (const k of optional) if (p[k] === null || typeof p[k] !== 'object') fail('program', `"${k}" is ${JSON.stringify(p[k])}: an absent ${k} is not written`);
  keys(p, ['ir', 'language', 'sourceSha256', 'package', 'contract', 'source', 'options', 'records', 'enums', 'state', 'timeFields',
    'recursiveFns', 'libraries', 'javaLibraries', 'fns', 'methods', 'warnings', ...optional], 'program');
  if (p.ir !== 2 || typeof p.ir !== 'number') fail('program', `"ir" must be exactly 2, not ${JSON.stringify(p.ir)}`);
  // Version 2 (ADR-0018 M4): the .jsc libraries the program imports, each with its declarations. A build's names are one
  // namespace: a library's records, enums and fns join the program's, and no name is declared twice across them.
  if (!Array.isArray(p.libraries)) fail('program', 'libraries is a list');
  for (const l of p.libraries) {
    keys(l, ['library', 'source', 'sourceSha256', 'records', 'enums', 'fns'], 'library');
    if (!/^([A-Za-z_][A-Za-z0-9_]*\.)+[A-Z][A-Za-z0-9]*$/.test(str(l.library, 'library'))) fail('library', `a bad class name ${l.library}`);
    str(l.source, 'library');
    if (typeof l.sourceSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(l.sourceSha256)) fail('library', 'sourceSha256 must be 64 hex digits');
  }
  // The certified Java libraries it imports (M4b): their records join the namespace (fields only when verified), their
  // imported fns' signatures are listed; their bodies are not here, and evaluating a call of one has no answer (§2.4).
  if (!Array.isArray(p.javaLibraries)) fail('program', 'javaLibraries is a list');
  const javaFns = new Map();
  for (const j of p.javaLibraries) {
    keys(j, ['class', 'sourceSha256', 'records', 'fns'], 'java library');
    if (!/^([A-Za-z_][A-Za-z0-9_]*\.)+[A-Za-z_][A-Za-z0-9_]*$/.test(str(j.class, 'java library'))) fail('java library', `a bad class name ${j.class}`);
    if (typeof j.sourceSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(j.sourceSha256)) fail('java library', 'sourceSha256 must be 64 hex digits');
    for (const r of j.records) {
      keys(r, ['name', 'fields', 'at', 'owner', 'import', 'javaName', 'verified'], 'java record');
      if (typeof r.verified !== 'boolean') fail('java record', 'verified is a boolean');
    }
    for (const f of j.fns) {
      keys(f, ['name', 'params', 'result'], 'java fn');
      if (javaFns.has(`${j.class}.${f.name}`)) fail('java fn', `java:${j.class}.${f.name} is listed twice`);
      javaFns.set(`${j.class}.${f.name}`, f);
    }
  }
  const allRecords = [...p.libraries.flatMap((l) => l.records), ...p.javaLibraries.flatMap((j) => j.records.map(({ name, fields, at }) => ({ name, fields, at }))),
    ...p.records];
  const allEnums = [...p.libraries.flatMap((l) => l.enums), ...p.enums];
  const allFns = [...p.libraries.flatMap((l) => l.fns), ...p.fns];
  if (p.language !== 'jsc 2') fail('program', `not a .jsc 2 program: ${p.language}`);
  if (typeof p.sourceSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(p.sourceSha256)) fail('program', 'sourceSha256 must be 64 hex digits');
  for (const k of ['package', 'contract', 'source']) str(p[k], k);
  for (const o of p.options) {
    keys(o, ['name', 'value'], 'option');
    if (!INT_RE.test(str(o.value, 'option'))) fail('option', `not an integer: ${o.value}`);
  }
  const shapes = new Map();
  const record = (r, where, qualified = false) => {
    keys(r, ['name', 'fields', 'at'], where);
    if (!(qualified ? QUALIFIED : IDENT).test(str(r.name, where))) fail(where, `a bad name ${r.name}`);
    if (shapes.has(r.name)) fail(where, `two declarations named ${r.name} (records, variants and the state share one namespace)`);
    for (const f of r.fields) keys(f, ['name', 'type'], `${r.name}'s field`);
    shapes.set(r.name, r.fields);
    at(r.at, where);
  };
  for (const r of allRecords) record(r, 'record');
  const enums = new Map();
  for (const e of allEnums) {
    keys(e, ['name', 'variants', 'at'], 'enum');
    if (enums.has(e.name) || shapes.has(e.name)) fail('enum', `two declarations named ${e.name}`);
    enums.set(e.name, e.variants.map((v) => v.name));
    for (const v of e.variants) record(v, `variant of ${e.name}`);
  }
  // M2c: each imported contract's state and types, under its qualified names; checked like the contract's own.
  const contracts = new Map();
  if ('contracts' in p) {
    if (!Array.isArray(p.contracts)) fail('contracts', 'not a list');
    for (const c of p.contracts) {
      const where = `imported contract ${c?.name}`;
      keys(c, ['name', 'package', 'state', 'records', 'enums', 'timeFields', 'receives', 'bigMaps'], where);
      if (!IDENT.test(str(c.name, where)) || contracts.has(c.name)) fail(where, 'a bad or repeated name');
      str(c.package, where);
      const q = (n) => typeof n === 'string' && n.startsWith(`${c.name}.`) && QUALIFIED.test(n);
      if (c.state.name !== `${c.name}.State`) fail(where, `its state is named ${c.name}.State`);
      record(c.state, where, true);
      for (const r of c.records) {
        if (!q(r.name)) fail(where, `a type not qualified by ${c.name}: ${r.name}`);
        record(r, where, true);
      }
      for (const e of c.enums) {
        keys(e, ['name', 'variants', 'at'], where);
        if (!q(e.name) || enums.has(e.name) || shapes.has(e.name)) fail(where, `a bad enum name ${e.name}`);
        enums.set(e.name, e.variants.map((v) => v.name));
        for (const v of e.variants) {
          if (!q(v.name)) fail(where, `a variant not qualified by ${c.name}: ${v.name}`);
          record(v, where, true);
        }
        at(e.at, where);
      }
      for (const x of c.timeFields) str(x, where);
      if (!Array.isArray(c.receives)) fail(where, 'receives is not a list');
      if (typeof c.bigMaps !== 'boolean') fail(where, 'bigMaps is not a boolean');
      contracts.set(c.name, c);
    }
  }
  if (p.state.name !== 'State') fail('state', 'the state record is named State');
  record(p.state, 'state');
  if ('params' in p) {
    if (p.params.name !== 'Config') fail('params', 'the params record is named Config');
    record(p.params, 'params');
  }
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
    } else if (k === 'instance') {
      // M2c: an imported contract's instance.
      if (!contracts.has(v)) fail(where, `no imported contract ${v}`);
    } else if (k === 'deposit') {
      // M2c: a deposit a receiving action sees; its memo's type, or null when it reads none.
      if (v !== null) type(v, where);
    } else fail(where, `unknown type ${k}`);
  };
  for (const c of contracts.values()) for (const t of c.receives) if (t !== null) type(t, `imported contract ${c.name}`);
  // #98 review F2, C5: a java: library fn's signature: a name, a list of parameter types and a result type, each a type
  // of the namespace (the Java reader reads them with type()).
  // #98 review C11: every entry of every library's list (a duplicate cannot hide a malformed one: it is refused).
  for (const j of p.javaLibraries) {
    for (const f of j.fns) {
      const k = `${j.class}.${f?.name}`;
      str(f.name, `java fn ${k}`);
      if (!Array.isArray(f.params)) fail(`java fn ${k}`, 'params is a list of types');
      for (const t of f.params) type(t, `java fn ${k}`);
      type(f.result, `java fn ${k}`);
    }
  }
  for (const [name, fields] of shapes) for (const f of fields) type(f.type, `${name}.${f.name}`);
  for (const k of ['timeFields', 'recursiveFns']) for (const x of p[k]) str(x, k);
  for (const w of p.warnings) keys(w, ['line', 'col', 'code', 'message'], 'warning');

  /** M3a: the record a body's StateRef and StateField read: State, or an owned method's (or invariant's) type. */
  let inputRec = 'State';

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
  /** M2b: a quorum's or authorized's field, a state field or params.f, of the type its check reads. */
  const modField = (ref, want, where, input = 'State') => {
    const params = typeof ref === 'string' && ref.startsWith('params.');
    // #98 review S5: params.f names the program's params, never another record that happens to be named Config.
    if (params && !('params' in p)) fail(where, `${ref}: the contract has no params`);
    const fs = (params ? p.params.fields : shapes.get(input)) ?? [];
    const f = fs.find((x) => x.name === (params ? ref.slice(7) : ref));
    if (!f) fail(where, `no field ${JSON.stringify(ref)}`);
    if (JSON.stringify(f.type) !== JSON.stringify(want)) fail(where, `${ref} is not ${JSON.stringify(want)}`);
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
  const fnArity = new Map(allFns.map((f) => [f.name, Array.isArray(f.params) ? f.params.length : -1]));
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
    if (e.node === 'CallJava') {
      const f = javaFns.get(`${e.library}.${e.fn}`);
      if (!f) fail(where, `no fn ${e.fn} imported from java:${e.library}`);
      arity(where, `java:${e.library}.${e.fn}`, e.args.length, f.params.length);
    }
    if (e.node === 'CallStd') arity(where, e.name, e.args.length, STD_ARITY[e.name]);
    if (e.node === 'LedgerOp') arity(where, e.op, e.args.length, LEDGER_ARITY[e.op]);
    if (e.node === 'Prelude') arity(where, e.op, e.args.length, PRELUDE_ARITY[e.op]);
    if (e.node === 'MapOp' || e.node === 'BigOp') arity(where, `${e.node}.${e.op}`, e.args.length, MAP_ARITY[e.op]);
    if (e.node === 'New') arity(where, `the record ${e.record}`, e.values.length, shapes.get(e.record).length);
    if (e.node === 'Variant') arity(where, `the variant ${e.variant}`, e.values.length, shapes.get(e.variant).length);
    if (e.node === 'GetField' && !shapes.get(e.record).some((f) => f.name === e.field)) fail(where, `${e.record} has no field ${e.field}`);
    // M3a: in an owned method or an owned type's invariant, the input is the object.
    if (e.node === 'StateField' && !shapes.get(inputRec).some((f) => f.name === e.field)) fail(where, `the ${inputRec === 'State' ? 'state' : inputRec} has no field ${e.field}`);
    if (e.node === 'ParamsRef' && !(p.params?.fields ?? []).some((f) => f.name === e.field)) fail(where, `the params have no field ${e.field}`);
  }
  function stmt(s, scope) {
    // M2c: a send with a datum.
    const spec = s && s.stmt === 'Send' && 'datum' in s ? { ...STMTS.Send, datum: 'e' } : s && STMTS[s.stmt];
    if (!spec) fail('statement', `unknown statement ${JSON.stringify(s?.stmt)}`);
    const where = `${s.stmt} at ${s.at}`;
    keys(s, ['stmt', ...Object.keys(spec), 'at'], where);
    fields(s, spec, where, scope);
    if (s.stmt === 'Require') bool(s.cond, where, 'a require condition');
    // #80 review S3: an if's, a for's or a match's pays is what its blocks say.
    if (s.stmt === 'If' || s.stmt === 'For' || s.stmt === 'Match') {
      if (s.pays !== paysIn({ stmts: [{ ...s, pays: undefined }] })) fail(where, `pays is ${s.pays}, but its blocks ${s.pays ? 'do not pay' : 'pay'}`);
    }
    if (s.stmt === 'Emit' && JSON.stringify(s.event.type) !== JSON.stringify({ enum: 'Event' })) fail(where, 'an emitted value is an Event');
    at(s.at, where);
  }
  const fnNames = new Set();
  for (const f of allFns) {
    keys(f, ['name', 'params', 'result', 'body', 'at'], 'fn');
    if (fnNames.has(f.name)) fail('fn', `two fns named ${f.name}`);
    fnNames.add(f.name);
    const scope = new Set();   // a fn sees its parameters only
    f.params.forEach((l) => decl(l, `fn ${f.name}`, scope));
    type(f.result, `fn ${f.name}`);
    block(f.body, `fn ${f.name}`, scope);
    noPayInExpressions(f.body, true, (what) => fail(`fn ${f.name}`, what));   // a fn never pays (JSC0413)
  }
  const methodNames = new Set();
  const stateFields = new Map(p.state.fields.map((f) => [f.name, f.type]));
  // M3a: the owned types, each a record of "records", none twice.
  const owned = p.owned ?? [];
  if ('owned' in p) {
    if (!Array.isArray(p.owned) || p.owned.length === 0) fail('owned', 'a non-empty list, absent when there are none');
    for (const o of p.owned) if (!p.records.some((r) => r.name === o) || p.owned.indexOf(o) !== p.owned.lastIndexOf(o)) fail('owned', `${o} is not one record`);
  }
  const ownedKind = (k) => k === 'ownedInit' || k === 'ownedAction' || k === 'ownedDestroy';
  for (const m of p.methods) {
    const where = `method ${m.name}`;
    // M2c (additive): "reads" appears only when the method reads another contract; M3a: "on", "shared", "into" and
    // "arriving" only for owned methods, moves and admits actions.
    const extra = ['reads', 'on', 'shared', 'into', 'arriving'].filter((k) => k in m);
    keys(m, ['kind', 'name', 'params', 'mods', 'checked', ...extra, 'usesCtx', 'pays', 'result', 'body', 'at'], where);
    if (!Array.isArray(m.mods)) fail(where, 'mods is not a list');
    if (!KINDS.has(m.kind)) fail(where, `unknown kind ${m.kind}`);
    if (methodNames.has(m.name)) fail(where, 'two methods of that name');
    methodNames.add(m.name);
    if (ownedKind(m.kind) !== ('on' in m) || ('on' in m && !owned.includes(m.on))) fail(where, 'an owned method, and only one, names an owned type (on)');
    if (('shared' in m && !ownedKind(m.kind)) || ('into' in m && m.kind !== 'ownedAction')) fail(where, 'shared is an owned method\'s, into an owned action\'s');
    const admits = m.mods.find((x) => x && x.name === 'admits');
    if (admits && (m.kind !== 'action' || !owned.includes(admits.field))) fail(where, 'admits is an instance action\'s, naming an owned type');
    if ('arriving' in m && !admits) fail(where, 'only an admits action has an arriving object');
    // #101 review F2: an owned program as strict as the checker, staged with M3a's parts (OWNED_STAGE).
    if (ownedKind(m.kind) && m.mods.some((x) => x && x.name === 'receives')) {
      fail(where, 'an owned method receives no deposits: a deposit\'s target is an instance (JSC0447)');
    }
    if (OWNED_STAGE < 2 && m.kind === 'ownedDestroy' && m.mods.some((x) => x && x.name === 'payable')) {
      fail(where, 'a payable owned destroy is not produced yet (M3a2; JSC0406)');
    }
    if (OWNED_STAGE < 2 && ownedKind(m.kind) && hasNode(m.body, ['Send', 'Mint', 'Deposit', 'Accept', 'FundsRef'])) {
      fail(where, 'native assets in an owned method (accept, send, mint, burn, deposit, funds) are not produced yet (M3a2; JSC1215)');
    }
    if (OWNED_STAGE < 3 && ('into' in m || 'arriving' in m || admits)) fail(where, 'moves (into, admits) are not produced yet (M3a3; JSC1215)');
    const scope = new Set();
    if ('shared' in m) {
      decl(m.shared, where, scope);
      if (JSON.stringify(m.shared.type) !== JSON.stringify({ record: 'State' })) fail(where, 'the shared state is a State');
    }
    if ('into' in m) {
      decl(m.into, where, scope);
      if (JSON.stringify(m.into.type) !== JSON.stringify({ record: 'State' })) fail(where, 'a move\'s destination is a State');
    }
    m.params.forEach((l) => decl(l, where, scope));
    if ('arriving' in m) {
      decl(m.arriving, where, scope);
      if (JSON.stringify(m.arriving.type) !== JSON.stringify({ record: admits.field })) fail(where, `the arriving object is a ${admits.field}`);
    }
    // M2c: each read: a local of the instance's state type, named by a params field Instance<C>.
    for (const r of m.reads ?? []) {
      keys(r, ['local', 'field'], where);
      const f = (p.params?.fields ?? []).find((x) => x.name === str(r.field, where));
      if (!f || typeof f.type !== 'object' || !('instance' in f.type)) fail(where, `reads params.${r.field}, which is not an Instance<C>`);
      decl(r.local, where, scope);
      if (JSON.stringify(r.local.type) !== JSON.stringify({ record: `${f.type.instance}.State` })) fail(where, `a read of ${f.type.instance} has its state's type`);
    }
    // M3a: an owned method's modifiers read its object's fields.
    const input = 'on' in m ? new Map(p.records.find((r) => r.name === m.on).fields.map((f) => [f.name, f.type])) : stateFields;
    modifiers(m, where, fail, keys, input, (ref, want, w) => modField(ref, want, w, 'on' in m ? m.on : 'State'), type);
    if (m.result !== null) type(m.result, where);
    inputRec = m.on ?? 'State';
    block(m.body, where, scope);
    inputRec = 'State';
    // #80 review C4, S7; #98 F1: an effect only where the checker allows one (JSC0413): in an action's or destroy's
    // statement blocks, never in an expression's block (a value block, a value if or match, a lambda), so the statement
    // walks below (paysIn, movesIn) are complete.
    // #98 review F1: an init and a view move nothing (JSC0404): their whole body is pure.
    noPayInExpressions(m.body, m.kind === 'init' || m.kind === 'view', (what) => fail(where, what));
    // #80 review S3: pays is recomputed from the body, and a method that pays says who may call it (JSC0408, JSC0420).
    const pays = paysIn(m.body);
    if (m.pays !== pays) fail(where, `pays is ${JSON.stringify(m.pays)}, but the body ${pays ? 'pays' : 'does not pay'}`);
    // M2a/M2c: send, mint, burn and deposit move value as a pay does; M2b: quorum and authorized are audiences too.
    const moves = pays || movesIn(m.body);
    if (moves && !m.mods.some((x) => x.name === 'open' || x.name === 'quorum' || x.name === 'authorized'
      || (x.name === 'only' && typeof x.field === 'string'))) {
      fail(where, 'it pays, sends, mints, burns or deposits, so it says who may call it (only f, open, quorum or authorized)');
    }
    // #80 review F7: a destroy ends the instance, so it pays out what it holds (JSC0409).
    if ((m.kind === 'destroy' || m.kind === 'ownedDestroy') && !moves) fail(where, `a destroy pays out what ${m.kind === 'destroy' ? 'the instance' : 'its object'} holds: it has no pay, send, mint, burn or deposit`);
    // F7: the checker's two decisions, recomputed from their definitions.
    const checked = m.kind === 'view' ? [] : m.params.filter((l) => needsCheck(l.type, shapes, enums)).map((l) => l.id);
    if (JSON.stringify(checked) !== JSON.stringify(m.checked)) {
      fail(where, `checked is ${JSON.stringify(m.checked)}, but the parameters whose types hold a Key, a Hash or a Map are ${JSON.stringify(checked)}`);
    }
    // M3a: an owned destroy reads it as a destroy does; a method with a shared or into parameter takes it (as the Java examples do).
    const usesCtx = m.kind === 'destroy' || m.kind === 'ownedDestroy' || 'shared' in m || 'into' in m || (m.kind !== 'view'
      && (m.mods.some((x) => !NO_CTX.has(x.name)) || readsTx(m.body)));
    if (usesCtx !== m.usesCtx) fail(where, `usesCtx is ${m.usesCtx}, but the method ${usesCtx ? 'reads' : 'does not read'} the transaction`);
  }
  // #101 review F2: each owned type has an owned init (JSC1208) and an escape hatch (JSC1209): an owned destroy without
  // shared, before or after.
  for (const o of owned) {
    if (!p.methods.some((m) => m.kind === 'ownedInit' && m.on === o)) fail('owned', `the owned type ${o} has no owned init (JSC1208)`);
    if (!p.methods.some((m) => m.kind === 'ownedDestroy' && m.on === o && !('shared' in m)
      && !m.mods.some((x) => x && (x.name === 'before' || x.name === 'after')))) {
      fail('owned', `the owned type ${o} has no escape hatch: an owned destroy without shared, before or after (JSC1209)`);
    }
  }
  for (const n of p.recursiveFns) if (!fnNames.has(n)) fail('recursiveFns', `no fn ${n}`);
  // M2b: invariants, each a Bool over the state and the params, with names of their own.
  if ('invariants' in p) {
    const names = new Set();
    for (const inv of p.invariants) {
      const where = `invariant ${inv.name}`;
      keys(inv, ['name', ...('on' in inv ? ['on'] : []), 'body', 'at'], where);
      if ('on' in inv && !owned.includes(inv.on)) fail(where, `${inv.on} is not an owned type`);
      if ('on' in inv && OWNED_STAGE < 3) fail(where, 'an owned type\'s invariant is not produced yet (M3a3; JSC1215)');
      if (names.has(inv.name) || methodNames.has(inv.name) || fnNames.has(inv.name)) fail(where, 'a name taken by another method, fn or invariant');
      names.add(inv.name);
      inputRec = inv.on ?? 'State';
      block(inv.body, where, new Set());
      inputRec = 'State';
      noPayInExpressions(inv.body, true, (what) => fail(where, what));   // #98 F1: an invariant moves nothing
      if (inv.body.result === null) fail(where, 'an invariant has a value');
      bool(inv.body.result, where, 'an invariant');
      at(inv.at, where);
    }
  }
}

/**
 * A method's modifiers are the checker's (D4, D10; M1 hardening, review S5 on #76): each is known; `payable` and `open`
 * name no field; `only f` names a Key field of the state and `after f`/`before f` an Int field; `batched` names none
 * or is `batched only`, on an action, with no `only`, `after` or `before` beside it; none is given twice; an init takes
 * only `payable`, a view none, a destroy is never payable. A reader that took any other shape on trust would decide on
 * its own who may call a method (an `only` with no field was skipped, and the call accepted unsigned).
 */
function modifiers(m, where, fail, keys, state, modField, type) {
  const seen = new Set();
  const names = m.mods.map((x) => x?.name);
  for (const mod of m.mods) {
    if (!mod || typeof mod !== 'object' || Array.isArray(mod)) fail(where, 'a modifier is not an object');
    const w = mod.name;
    // M2b: a quorum names its keys and threshold; M2c: receives may name a memo type; every other modifier a field at
    // most (the integration of #80 with #84 and #90: the strict shapes of both).
    if (w === 'quorum') keys(mod, ['name', 'keys', 'threshold'], where);
    else if (w === 'receives') keys(mod, ['name', ...('type' in mod ? ['type'] : [])], where);
    // #98 review C6 (with the Java reader): payable and open name no field at all; a batched modifier's field is absent
    // or "only", never null.
    else if (w === 'payable' || w === 'open') keys(mod, ['name'], where);
    else keys(mod, ['name', 'field'], where);
    if (w === 'batched' && 'field' in mod && mod.field === null) fail(where, 'batched names no field or "only", not null');
    if (!MODS.has(w)) fail(where, `unknown modifier ${w}`);
    const field = mod.field ?? null;
    if (seen.has(w)) fail(where, `the modifier ${w} is given twice`);
    seen.add(w);
    if (m.kind === 'view') fail(where, `a view takes no modifiers, not ${w}`);
    if ((m.kind === 'init' || m.kind === 'ownedInit') && w !== 'payable') fail(where, `an init takes only payable, not ${w}`);
    if (w === 'payable' || w === 'open') {
      if (field !== null) fail(where, `${w} names no field, not ${JSON.stringify(field)}`);
      if (w === 'payable' && m.kind === 'destroy') fail(where, 'a destroy cannot be payable');
    } else if (w === 'only' || w === 'after' || w === 'before') {
      const want = w === 'only' ? 'Key' : 'Int';
      if (typeof field !== 'string') fail(where, `${w} names a state field, not ${JSON.stringify(field)}`);
      if (!state.has(field)) fail(where, `${w} ${field}: the state has no field ${field}`);
      if (state.get(field) !== want) fail(where, `${w} ${field}: ${field} is of type ${JSON.stringify(state.get(field))}, not ${want}`);
    } else if (w === 'quorum') {
      // M2b (ADR-0009 D2, JSC0439): keys a List<Key> and the threshold an Int, both state fields or both params fields.
      if (typeof mod.keys !== 'string' || typeof mod.threshold !== 'string') fail(where, 'a quorum names its keys and threshold');
      if (mod.keys.startsWith('params.') !== mod.threshold.startsWith('params.')) {
        fail(where, `quorum ${mod.keys} ${mod.threshold}: the keys and the threshold are both state fields or both params fields`);
      }
      modField(mod.keys, { list: 'Key' }, where);
      modField(mod.threshold, 'Int', where);
    } else if (w === 'authorized') {
      if (typeof field !== 'string') fail(where, `authorized names a field, not ${JSON.stringify(field)}`);
      modField(field, 'Credential', where);   // M2b (ADR-0009 D3)
    } else if (w === 'receives') {
      // M2c (ADR-0011 D3, JSC0447): receives, or receives T (the memo type), on an action.
      if ('type' in mod) type(mod.type, where);
      if (m.kind !== 'action' && m.kind !== 'ownedAction') fail(where, 'only an action receives');
    } else if (w === 'admits') {
      // M3a (ADR-0012 D3, JSC1212): an instance action, naming an owned type (checked with the method).
      if (typeof field !== 'string') fail(where, 'admits names an owned type');
      if (m.kind !== 'action') fail(where, 'only an action admits objects');
    } else if (w === 'batched') {
      if (field !== null && field !== 'only') fail(where, `batched is plain or "batched only", not ${JSON.stringify(field)}`);
      if (m.kind !== 'action') fail(where, `only an action can be batched, not a${m.kind === 'init' ? 'n' : ''} ${m.kind}`);
      // #80 review S3: as the checker (JSC0419): no batched action in a contract with BigMap state.
      if ([...state.values()].some((t) => t && typeof t === 'object' && 'bigmap' in t)) fail(where, 'a contract with BigMap state has no batched action');
    }
  }
  if (names.includes('batched')) {
    // JSC0417 (with M2b's quorum and authorized), JSC0447 (receives).
    for (const w of ['only', 'after', 'before', 'quorum', 'authorized', 'receives']) if (names.includes(w)) fail(where, `a batched action cannot use ${w}`);
  }
}
/** The effect statements (ADR-0018 D4): only in an action's or a destroy's statement blocks (JSC0413, JSC0404). */
const EFFECT_STMTS = new Set(['Pay', 'Send', 'Mint', 'Deposit', 'Accept', 'Emit']);

/**
 * Refuses an effect statement (a pay, and since M2 a send, mint or burn, deposit, accept or emit; #98 review F1) anywhere
 * below an expression node (#80 review C4, S7; JSC0413), or anywhere at all when `pure` (a fn's, an invariant's, an
 * init's or a view's body: JSC0413, JSC0404). Statement blocks of if, for and match statements are not expressions;
 * everything under a node is. So the statement walks (paysIn, movesIn) see every effect a method has.
 */
function noPayInExpressions(o, pure, fail) {
  if (Array.isArray(o)) {
    for (const x of o) noPayInExpressions(x, pure, fail);
    return;
  }
  if (!o || typeof o !== 'object') return;
  if (pure && EFFECT_STMTS.has(o.stmt)) fail(`a ${o.stmt.toLowerCase()} inside an expression or a fn, where the checker allows none (JSC0413; in an init or a view, JSC0404), at ${o.at}`);
  const below = pure || typeof o.node === 'string';
  for (const v of Object.values(o)) noPayInExpressions(v, below, fail);
}

/** Whether a block pays: a Pay statement in it, or in an if's, a for's or a match's block (never in an expression). */
function paysIn(block) {
  return (block?.stmts ?? []).some((s) => s.stmt === 'Pay'
    || (s.stmt === 'If' && (s.branches.some((b) => paysIn(b.then)) || (s.otherwise !== null && paysIn(s.otherwise))))
    || (s.stmt === 'For' && paysIn(s.body))
    || (s.stmt === 'Match' && s.arms.some((a) => paysIn(a.body))));
}

/** Whether a block pays, sends, mints, burns or deposits (M2a, M2c): what the checker calls moving value (JSC0408, JSC0409). */
function movesIn(block) {
  return (block?.stmts ?? []).some((s) => s.stmt === 'Pay' || s.stmt === 'Send' || s.stmt === 'Mint' || s.stmt === 'Deposit'
    || (s.stmt === 'If' && (s.branches.some((b) => movesIn(b.then)) || (s.otherwise !== null && movesIn(s.otherwise))))
    || (s.stmt === 'For' && movesIn(s.body))
    || (s.stmt === 'Match' && s.arms.some((a) => movesIn(a.body))));
}

/** §4: a type holds a Key, a Hash or a Map somewhere (records and variants through their fields). */
function needsCheck(t, shapes, enums) {
  // M2c (review r1, C4): a Value too (it crosses only stored in a read state; an argument never holds one).
  if (typeof t === 'string') return t === 'Key' || t === 'Hash' || t === 'Address' || t === 'Credential' || t === 'Value';
  const [k, v] = Object.entries(t)[0];
  if (k === 'instance' || k === 'deposit') return false;   // M2c: the deployer's; memos are checked where the action starts
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
