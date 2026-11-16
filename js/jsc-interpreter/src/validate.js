// Validates an IR export (docs/reference/jsc-2-ir.md) in one pass before anything is evaluated (M1b review S3, F6,
// F7; r3: C4, S7). A version-1 reader refuses what it does not know (a node, statement, field shape, operator, modifier,
// std row, type). Every local is declared once, and referenced only where it is in scope: a method's or fn's
// parameters in its body, a const after its initializer to the end of its block, a loop, lambda or arm binding in its
// body. Every fn call names a fn with that many parameters, every field read names a field of its record, every record,
// variant and std call has its arity. Ids are safe integers, integer literals decimal, bytes lower-case hex. And it
// recomputes two decisions of the checker from their definitions, refusing an IR that disagrees: the checked
// parameters (semantics §4) and whether a method reads the transaction (§2).

import { ROWS } from './std.js';
import { GOV_TYPES, GOV_RECORDS, govCtors, govFields, holdsGov } from './gov.js';

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
const MODS = set('payable', 'open', 'only', 'after', 'before', 'batched', 'quorum', 'authorized', 'receives', 'admits', 'retire', 'migrates');
// M2b: quorum and authorized read the signatories and withdrawals in the validator, so like open and only they do not
// make a method read the transaction (Ctx).
const NO_CTX = set('open', 'only', 'quorum', 'authorized');
// Argument counts of the std rows, the prelude and the map operations (review S7).
const STD_ARITY = {
  sha2_256: 1, sha3_256: 1, blake2b_256: 1, blake2b_224: 1, verifyEd25519: 3, length: 1, concat: 2, slice: 3, take: 2, drop: 2,
  byteAt: 2, toInt: 1, toHex: 1, div: 2, mod: 2, pow: 2, expMod: 3, sign: 1, andBytes: 3, orBytes: 3, xorBytes: 3, complement: 1,
  readBit: 2, shift: 2, rotate: 2, countSetBits: 1, findFirstSetBit: 1, last: 1, nth: 2, indexOf: 2, elapsedSince: 1,
  elapsedSinceAtMost: 1, windowStart: 0, windowEnd: 0, isEmpty: 1, withoutLovelace: 1, scale: 2, paymentKey: 1, paymentScript: 1,
  isRegistration: 1, delegatesOnlyTo: 2, isDRepLifecycle: 1,
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
const KINDS = set('init', 'action', 'destroy', 'view', 'ownedInit', 'ownedAction', 'ownedDestroy',
  // M3c (ADR-0010 D1, ADR-0011 D4): the script purposes' handlers and the adoption of a previous version's instance.
  'withdraw', 'certify', 'vote', 'propose', 'migrate');
const PURPOSES = set('withdraw', 'certify', 'vote', 'propose');
// #101 review F2: the parts of M3a the checker produces so far (1: owned objects of one type with shared reads; 2: native
// assets in owned methods, payable owned destroys; 3: moves and invariants by type), as the Java reader's OWNED_STAGE.
const OWNED_STAGE = 3;

/** Whether raw IR reads balance (a CtxRef of that name), at any depth (#101 review C4). */
function readsBalance(json) {
  if (Array.isArray(json)) return json.some(readsBalance);
  if (json !== null && typeof json === 'object') {
    if (json.node === 'CtxRef' && json.name === 'balance') return true;
    return Object.values(json).some(readsBalance);
  }
  return false;
}

/** Whether raw IR holds a statement or expression node of one of the names, at any depth. */
function hasNode(json, names) {
  if (Array.isArray(json)) return json.some((v) => hasNode(v, names));
  if (json !== null && typeof json === 'object') {
    if (names.includes(json.stmt) || names.includes(json.node)) return true;
    return Object.values(json).some((v) => hasNode(v, names));
  }
  return false;
}
// The fields below the top level whose value is always a list (docs/reference/jsc-2-ir.md); branches is checked where it is read.
const NESTED_LISTS = set('args', 'arms', 'binds', 'checked', 'ctors', 'enums', 'fields', 'fns', 'items', 'mods', 'params',
  'reads', 'receives', 'records', 'stmts', 'timeFields', 'values', 'variants');

/** A value of the export, as a property key for a lookup table: only a string is (a prototype-less object throws). */
function key(v) {
  return typeof v === 'string' ? v : '\u0000not a name';
}

/**
 * A value of the export, as text for a message: whatever its kind (review r1, security S3). The strict parser builds
 * objects without a prototype, which a template literal cannot convert, so a malformed export (an object where a name or
 * a position is due) made the message itself throw a TypeError.
 */
function show(v) {
  if (v === null || typeof v !== 'object') return String(v);
  if (Array.isArray(v) && v.every((x) => x === null || typeof x !== 'object')) return v.join(',');
  try {
    return JSON.stringify(v);
  } catch {
    return '?';
  }
}

export function validate(p) {
  // A refusal carries its parts as well as its text (review r2, security S13 and Codex C4): irWhere and irWhat (the
  // location and the check's own words, so a reader of the error never parses the message back), irCheck (which check:
  // 'schema' for the export's shape, keys() below; 'enum' for a node attribute outside its set; 'arity', 'type' and
  // 'field' for the checks whose words embed IR-supplied names (review r4, security S13), and M3's 'ctor' (a ledger
  // type's constructor) and 'ledgerType' (a ledger record's field read from another type; #130 r1, Z1); 'check' otherwise) and,
  // for an enumerated attribute, irNode (the node or statement kind).
  const fail = (where, what, check = 'check', node = undefined) => {
    const e = new Error(`invalid IR (${show(where)}): ${show(what)}`);
    e.irWhere = show(where);
    e.irWhat = show(what);
    e.irCheck = check;
    if (node !== undefined) e.irNode = show(node);
    throw e;
  };
  const keys = (o, allowed, where) => {
    if (!o || typeof o !== 'object' || Array.isArray(o)) fail(where, 'not an object', 'schema');
    for (const k of Object.keys(o)) if (!allowed.includes(k)) fail(where, `unknown field "${show(k)}"`, 'schema');
    for (const k of allowed) if (!(k in o) && !(k === 'fieldTaints' || k === 'field')) fail(where, `missing field "${show(k)}"`, 'schema');
  };
  const str = (v, where) => {
    if (typeof v !== 'string') fail(where, 'not a string');
    return v;
  };
  // Positions, local ids and taints are 32-bit, as the Java reader holds them (IrReaderFuzzTest: a column of 2^46 was
  // accepted here and refused there).
  const INT_MAX = 0x7fffffff;
  const at = (v, where) => {
    if (!Array.isArray(v) || v.length !== 2 || !v.every((n) => Number.isSafeInteger(n) && n >= 0 && n <= INT_MAX)) fail(where, 'a bad position');
  };
  const nat = (v, where) => {
    if (!Number.isSafeInteger(v) || v < 0 || v > INT_MAX) fail(where, 'not a non-negative integer');
  };

  // ---- top level and declarations
  // M2b (additive): "params" and "invariants" appear only when the contract declares them.
  // M2c (additive): "contracts" appears only when the contract imports another.
  // An export that is not an object is refused as such (IrReaderFuzzTest: 'in' on a number threw a TypeError).
  if (!p || typeof p !== 'object' || Array.isArray(p)) fail('program', 'not an object');
  // Every field the format holds a list in is a list at every level, before anything iterates it: a string would iterate
  // as characters, or as nothing when empty (IrReaderFuzzTest: a block's stmts "" was read as no statements). The
  // top-level lists are checked below; a top-level "params" is the params record (M2b), a nested one a list.
  const walkLists = (v, top) => {
    if (Array.isArray(v)) {
      for (const x of v) walkLists(x, false);
    } else if (v && typeof v === 'object') {
      for (const [k, x] of Object.entries(v)) {
        // A present null is an absent field written as null (#101 review C5: the same words as the method check's below).
        if (!top && NESTED_LISTS.has(k) && !Array.isArray(x)) {
          fail('program', x === null ? `"${show(k)}" is null: an absent field is not written` : `"${show(k)}" is not a list`);
        }
        // A read state's fieldTaints is keyed by the user's own field names (a field may be named "fields"), not by the
        // format's: its keys are never structural (review r1, Codex C3). decl() checks it.
        if (k === 'fieldTaints') continue;
        walkLists(x, false);
      }
    }
  };
  walkLists(p, true);
  // M3a (additive): "owned" appears only when the contract declares owned types.
  // M3b (additive): "keyed" appears only when the contract declares keyed types.
  const optional = ['params', 'invariants', 'contracts', 'owned', 'keyed'].filter((k) => k in p);
  for (const k of optional) if (p[k] === null || typeof p[k] !== 'object') fail('program', `"${show(k)}" is ${JSON.stringify(p[k])}: an absent ${show(k)} is not written`);
  keys(p, ['ir', 'language', 'sourceSha256', 'package', 'contract', 'source', 'options', 'records', 'enums', 'state', 'timeFields',
    'recursiveFns', 'libraries', 'javaLibraries', 'fns', 'methods', 'warnings', ...optional], 'program');
  if (p.ir !== 2 || typeof p.ir !== 'number') fail('program', `"ir" must be exactly 2, not ${JSON.stringify(p.ir)}`);
  // Every top-level list is one before anything iterates it (IrReaderFuzzTest: "p.fns is not iterable" was a TypeError).
  for (const k of ['options', 'records', 'enums', 'timeFields', 'recursiveFns', 'libraries', 'javaLibraries', 'fns', 'methods', 'warnings']) {
    if (!Array.isArray(p[k])) fail('program', `${show(k)} is a list`);
  }
  // Every entry of a top-level list of declarations is an object, before anything reads its fields (IrReaderFuzzTest: a
  // fn that was null made the fns' arity table throw a TypeError).
  for (const k of ['options', 'records', 'enums', 'libraries', 'javaLibraries', 'fns', 'methods', 'warnings']) {
    for (const x of p[k]) if (!x || typeof x !== 'object' || Array.isArray(x)) fail(k, 'an entry is not an object');
  }
  // M3 (the integration with the readers' fuzzing): "owned" is a list of names and "keyed" a list of objects, before
  // anything reads them (IrReaderFuzzTest: an object among the owned names made a message's template throw a TypeError).
  if ('owned' in p) {
    if (!Array.isArray(p.owned)) fail('program', 'owned is a list');
    for (const o of p.owned) if (typeof o !== 'string') fail('owned', 'an entry is not a name');
  }
  if ('keyed' in p) {
    if (!Array.isArray(p.keyed)) fail('program', 'keyed is a list');
    for (const x of p.keyed) if (!x || typeof x !== 'object' || Array.isArray(x)) fail('keyed', 'an entry is not an object');
  }
  // The optional "invariants" is a list of objects too (CI's IrReaderFuzzTest: an object there made "p.invariants is not
  // iterable" a TypeError).
  if ('invariants' in p) {
    if (!Array.isArray(p.invariants)) fail('program', 'invariants is a list');
    for (const x of p.invariants) if (!x || typeof x !== 'object' || Array.isArray(x)) fail('invariants', 'an entry is not an object');
  }
  for (const l of [...p.libraries, ...p.javaLibraries]) {
    for (const k of ['records', 'enums', 'fns']) {
      if (Array.isArray(l[k])) for (const x of l[k]) if (!x || typeof x !== 'object' || Array.isArray(x)) fail(k, 'an entry is not an object');
    }
  }
  // Version 2 (ADR-0018 M4): the .jsc libraries the program imports, each with its declarations. A build's names are one
  // namespace: a library's records, enums and fns join the program's, and no name is declared twice across them.
  if (!Array.isArray(p.libraries)) fail('program', 'libraries is a list');
  for (const l of p.libraries) {
    keys(l, ['library', 'source', 'sourceSha256', 'records', 'enums', 'fns'], 'library');
    if (!/^([A-Za-z_][A-Za-z0-9_]*\.)+[A-Z][A-Za-z0-9]*$/.test(str(l.library, 'library'))) fail('library', `a bad class name ${show(l.library)}`);
    str(l.source, 'library');
    if (typeof l.sourceSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(l.sourceSha256)) fail('library', 'sourceSha256 must be 64 hex digits');
  }
  // The certified Java libraries it imports (M4b): their records join the namespace (fields only when verified), their
  // imported fns' signatures are listed; their bodies are not here, and evaluating a call of one has no answer (§2.4).
  if (!Array.isArray(p.javaLibraries)) fail('program', 'javaLibraries is a list');
  const javaFns = new Map();
  const javaClasses = new Set();
  for (const j of p.javaLibraries) {
    keys(j, ['class', 'sourceSha256', 'records', 'fns'], 'java library');
    // #110 C17: a library is listed once (two entries of one class would let one's fns hide the other's).
    if (javaClasses.has(key(j.class))) fail('java library', `java:${show(j.class)} is listed twice`);
    javaClasses.add(key(j.class));
    if (!/^([A-Za-z_][A-Za-z0-9_]*\.)+[A-Za-z_][A-Za-z0-9_]*$/.test(str(j.class, 'java library'))) fail('java library', `a bad class name ${show(j.class)}`);
    if (typeof j.sourceSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(j.sourceSha256)) fail('java library', 'sourceSha256 must be 64 hex digits');
    for (const r of j.records) {
      keys(r, ['name', 'fields', 'at', 'owner', 'import', 'javaName', 'verified'], 'java record');
      at(r.at, 'java record');   // its position too (IrReaderFuzzTest)
      if (typeof r.verified !== 'boolean') fail('java record', 'verified is a boolean');
    }
    for (const f of j.fns) {
      keys(f, ['name', 'params', 'result'], 'java fn');
      if (javaFns.has(`${show(j.class)}.${show(f.name)}`)) fail('java fn', `java:${show(j.class)}.${show(f.name)} is listed twice`);
      javaFns.set(`${show(j.class)}.${show(f.name)}`, f);
    }
  }
  const allRecords = [...p.libraries.flatMap((l) => l.records), ...p.javaLibraries.flatMap((j) => j.records.map(({ name, fields, at }) => ({ name, fields, at }))),
    ...p.records];
  const allEnums = [...p.libraries.flatMap((l) => l.enums), ...p.enums];
  const allFns = [...p.libraries.flatMap((l) => l.fns), ...p.fns];
  if (p.language !== 'jsc 2') fail('program', `not a .jsc 2 program: ${show(p.language)}`);
  if (typeof p.sourceSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(p.sourceSha256)) fail('program', 'sourceSha256 must be 64 hex digits');
  for (const k of ['package', 'contract', 'source']) str(p[k], k);
  for (const o of p.options) {
    keys(o, ['name', 'value'], 'option');
    str(o.name, 'option');   // a name (IrReaderFuzzTest: an object there was accepted, the Java reader refused it)
    if (!INT_RE.test(str(o.value, 'option'))) fail('option', `not an integer: ${show(o.value)}`);
  }
  const shapes = new Map();
  const record = (r, where, qualified = false) => {
    keys(r, ['name', 'fields', 'at'], where);
    if (!(qualified ? QUALIFIED : IDENT).test(str(r.name, where))) fail(where, `a bad name ${show(r.name)}`);
    if (shapes.has(r.name)) fail(where, `two declarations named ${show(r.name)} (records, variants and the state share one namespace)`);
    if (!Array.isArray(r.fields)) fail(where, 'fields is not a list');
    for (const f of r.fields) {
      keys(f, ['name', 'type'], `${show(r.name)}'s field`);
      // A field's name is a name (IrReaderFuzzTest: a variant's field named 0 was accepted, the Java reader refused it).
      if (!IDENT.test(str(f.name, `${show(r.name)}'s field`))) fail(`${show(r.name)}'s field`, `a bad name ${show(f.name)}`);
    }
    shapes.set(r.name, r.fields);
    at(r.at, where);
  };
  for (const r of allRecords) record(r, 'record');
  // A declaration read before its own checks run is an object first (CI's short fuzz at 2278fa53: a null variant, and
  // before it a null state, were TypeErrors).
  const objects = (xs, where, what) => {
    if (!Array.isArray(xs)) fail(where, `${show(what)} is not a list`);
    for (const x of xs) if (!x || typeof x !== 'object' || Array.isArray(x)) fail(where, `${show(what)}: an entry is not an object`, 'schema');
  };
  const object = (x, where, what) => {
    if (!x || typeof x !== 'object' || Array.isArray(x)) fail(where, `${show(what)} is not an object`, 'schema');
  };
  const enums = new Map();
  for (const e of allEnums) {
    keys(e, ['name', 'variants', 'at'], 'enum');
    at(e.at, 'enum');   // its position too (IrReaderFuzzTest)
    if (!Array.isArray(e.variants)) fail('enum', 'variants is not a list');
    objects(e.variants, 'enum', 'variants');
    if (enums.has(e.name) || shapes.has(e.name)) fail('enum', `two declarations named ${show(e.name)}`);
    enums.set(e.name, e.variants.map((v) => v.name));
    for (const v of e.variants) record(v, `variant of ${show(e.name)}`);
  }
  // M2c: each imported contract's state and types, under its qualified names; checked like the contract's own.
  const contracts = new Map();
  if ('contracts' in p) {
    if (!Array.isArray(p.contracts)) fail('contracts', 'not a list');
    for (const c of p.contracts) {
      const where = `imported contract ${show(c?.name)}`;
      keys(c, ['name', 'package', 'state', 'records', 'enums', 'timeFields', 'receives', 'bigMaps'], where);
      if (!IDENT.test(str(c.name, where)) || contracts.has(c.name)) fail(where, 'a bad or repeated name');
      str(c.package, where);
      object(c.state, where, 'state');
      objects(c.records, where, 'records');
      objects(c.enums, where, 'enums');
      const q = (n) => typeof n === 'string' && n.startsWith(`${show(c.name)}.`) && QUALIFIED.test(n);
      if (c.state.name !== `${show(c.name)}.State`) fail(where, `its state is named ${show(c.name)}.State`);
      record(c.state, where, true);
      for (const r of c.records) {
        if (!q(r.name)) fail(where, `a type not qualified by ${show(c.name)}: ${show(r.name)}`);
        record(r, where, true);
      }
      for (const e of c.enums) {
        keys(e, ['name', 'variants', 'at'], where);
        objects(e.variants, where, 'variants');
        if (!q(e.name) || enums.has(e.name) || shapes.has(e.name)) fail(where, `a bad enum name ${show(e.name)}`);
        enums.set(e.name, e.variants.map((v) => v.name));
        for (const v of e.variants) {
          if (!q(v.name)) fail(where, `a variant not qualified by ${show(c.name)}: ${show(v.name)}`);
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
  // The state is an object before anything reads it (the long campaign at 40dd8337: a null state was a TypeError).
  if (!p.state || typeof p.state !== 'object' || Array.isArray(p.state)) fail('state', 'not an object', 'schema');
  if (p.state.name !== 'State') fail('state', 'the state record is named State');
  record(p.state, 'state');
  if ('params' in p) {
    if (p.params.name !== 'Config') fail('params', 'the params record is named Config');
    record(p.params, 'params');
  }
  const type = (t, where) => {
    if (typeof t === 'string') {
      // M3c: the purposes' ledger types by name (Data: a PlutusData field's, carried and never read).
      if (!PRIMS.has(t) && !GOV_TYPES.has(key(t))) fail(where, `unknown type ${show(t)}`);
      return;
    }
    if (!t || typeof t !== 'object' || Object.keys(t).length !== 1) fail(where, `a bad type ${JSON.stringify(t)}`);
    const [k, v] = Object.entries(t)[0];
    if (k === 'list' || k === 'option') type(v, where);
    else if (k === 'map' || k === 'bigmap' || k === 'ledgerMap') {
      if (!Array.isArray(v) || v.length !== 2) fail(where, `a bad ${show(k)} type`);
      type(v[0], where);
      type(v[1], where);
    } else if (k === 'record') {
      if (!shapes.has(v) || [...enums.values()].some((vs) => vs.includes(v))) fail(where, `no record ${show(v)}`);
    } else if (k === 'enum') {
      if (!enums.has(v)) fail(where, `no enum ${show(v)}`);
    } else if (k === 'instance') {
      // M2c: an imported contract's instance.
      if (!contracts.has(v)) fail(where, `no imported contract ${show(v)}`);
    } else if (k === 'deposit') {
      // M2c: a deposit a receiving action sees; its memo's type, or null when it reads none.
      if (v !== null) type(v, where);
    } else fail(where, `unknown type ${show(k)}`);
  };
  for (const c of contracts.values()) for (const t of c.receives) if (t !== null) type(t, `imported contract ${show(c.name)}`);
  // #98 review F2, C5: a java: library fn's signature: a name, a list of parameter types and a result type, each a type
  // of the namespace (the Java reader reads them with type()).
  // #98 review C11: every entry of every library's list (a duplicate cannot hide a malformed one: it is refused).
  for (const j of p.javaLibraries) {
    for (const f of j.fns) {
      const k = `${show(j.class)}.${show(f?.name)}`;
      str(f.name, `java fn ${show(k)}`);
      if (!Array.isArray(f.params)) fail(`java fn ${show(k)}`, 'params is a list of types');
      for (const t of f.params) type(t, `java fn ${show(k)}`);
      type(f.result, `java fn ${show(k)}`);
    }
  }
  for (const [name, fields] of shapes) for (const f of fields) type(f.type, `${show(name)}.${show(f.name)}`);
  for (const k of ['timeFields', 'recursiveFns']) for (const x of p[k]) str(x, k);
  for (const w of p.warnings) {
    keys(w, ['line', 'col', 'code', 'message'], 'warning');
    // Of the kinds the Java reader reads (IrReaderFuzzTest: a warning's code that was a node was accepted here).
    nat(w.line, 'warning');
    nat(w.col, 'warning');
    str(w.code, 'warning');
    str(w.message, 'warning');
  }

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
    // A read state's field taints, when written: an object of non-negative integers (IrReaderFuzzTest: a record there was
    // accepted, the Java reader refused it).
    if ('fieldTaints' in l) {
      if (!l.fieldTaints || typeof l.fieldTaints !== 'object' || Array.isArray(l.fieldTaints)) fail(where, 'fieldTaints is not an object');
      for (const v of Object.values(l.fieldTaints)) nat(v, where);
    }
    if (locals.has(l.id)) fail(where, `the local id ${show(l.id)} is declared twice`);
    locals.set(l.id, l);
    scope.add(l.id);
  };
  /** A block's statements in order in a scope of its own (a const is in scope from its statement on). */
  const block = (b, where, outer) => {
    keys(b, ['stmts', 'result', 'at'], where);
    // A list, not anything iterable (IrReaderFuzzTest: stmts "" was read as no statements).
    if (!Array.isArray(b.stmts)) fail(where, 'stmts is not a list');
    const scope = new Set(outer);
    for (const st of b.stmts) stmt(st, scope);
    if (b.result !== null) expr(b.result, scope);
    at(b.at, where);
  };
  const arms = (as, where, scope, scrutineeType) => {
    if (!Array.isArray(as)) fail(where, 'arms is not a list');
    for (const a of as) {
      keys(a, ['ctors', 'fields', 'binds', 'body', 'at'], where);
      // An arm's position and lists are checked like any other's (IrReaderFuzzTest: [24, 7, 0] was accepted here).
      at(a.at, where);
      for (const k of ['ctors', 'fields', 'binds']) if (!Array.isArray(a[k])) fail(where, `an arm's ${show(k)} is a list`);
      a.ctors.forEach((c) => str(c, where));
      a.fields.forEach((c) => str(c, where));
      if (a.binds.length !== a.fields.length) fail(where, 'binds and fields differ in length');
      if (a.binds.length > 0 && a.ctors.length !== 1) fail(where, 'only a single-constructor arm binds');
      // M3c: a sealed ledger type's arm names its constructors (gov.js), its fields theirs; a Data field is never bound.
      const gov = typeof scrutineeType === 'string' && govCtors(scrutineeType).length > 0 ? scrutineeType : null;
      for (const c of a.ctors) {
        if (gov) {
          if (!govCtors(gov).includes(c)) fail(where, `${show(c)} is not a constructor of ${show(gov)}`, 'ctor');
          continue;
        }
        if (c === 'Some' || c === 'None' || key(c) in CRED_FIELDS) continue;
        if (!(shapes.has(c) && [...enums.values()].some((vs) => vs.includes(c)))) fail(where, `no variant ${show(c)}`);
      }
      for (const f of a.fields) {
        const c = a.ctors[0];
        if (gov) {
          const gf = govFields(gov, c).find((x) => x.name === f);
          if (!gf) fail(where, `${show(c)} has no field ${show(f)}`, 'field');
          if (gf.type === 'Data') fail(where, `the field ${show(c)}.${show(f)} is PlutusData, never bound (JSC1320)`);
          continue;
        }
        const known = c === 'Some' ? f === 'value' : key(c) in CRED_FIELDS ? f === CRED_FIELDS[key(c)] : (shapes.get(c) ?? []).some((x) => x.name === f);
        if (!known) fail(where, `${show(c)} has no field ${show(f)}`, 'field');
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
    if (params && !('params' in p)) fail(where, `the modifier's field ${show(ref)}: the contract has no params`);
    const fs = (params ? p.params.fields : shapes.get(input)) ?? [];
    const f = fs.find((x) => x.name === (params ? ref.slice(7) : ref));
    if (!f) fail(where, `no field ${JSON.stringify(ref)}`);
    if (JSON.stringify(f.type) !== JSON.stringify(want)) fail(where, `the modifier's field ${show(ref)} is not ${JSON.stringify(want)}`);
  };
  /** A condition or a boolean operand is of type Bool (review S3 on #76): the interpreter never tests truthiness. */
  const bool = (e, where, what) => {
    if (e.type !== 'Bool') fail(where, `${show(what)} is of type ${JSON.stringify(e.type)}, not Bool`, 'type');
  };
  /** A node's own fields; bindings it introduces (a loop variable, lambda parameters) are in scope in its blocks only. */
  const fields = (o, spec, where, scope) => {
    const inner = new Set(scope);
    for (const [k, kind] of Object.entries(spec)) {
      const v = o[k];
      if (kind instanceof Set) {
        if (!kind.has(v)) fail(where, `unknown ${show(k)} ${JSON.stringify(v)}`, 'enum', o.node ?? o.stmt);
      } else if (kind === 'e') expr(v, scope);
      else if (kind === 'e?') { if (v !== null) expr(v, scope); }
      else if (kind === 'es') { if (!Array.isArray(v)) fail(where, `${show(k)} is not a list`); v.forEach((x) => expr(x, scope)); }
      else if (kind === 'b') block(v, where, inner);
      else if (kind === 'b?') { if (v !== null) block(v, where, inner); }
      else if (kind === 'l') decl(v, where, inner);
      else if (kind === 'let') decl(v, where, scope);   // a const: in scope for the rest of its block
      else if (kind === 't') type(v, where);
      else if (kind === 's') str(v, where);
      else if (kind === 'z') { if (typeof v !== 'boolean') fail(where, `${show(k)} is not a boolean`); }
      else if (kind === 'arms') arms(v, where, scope, o.scrutinee?.type);
      else if (kind === 'branches') branches(v, where, scope);
      else if (kind === 'ref') {
        if (!locals.has(v)) fail(where, `a reference to the undeclared local ${show(v)}`);
        if (!scope.has(v)) fail(where, `a reference to the local ${show(v)} (${show(locals.get(v).name)}) outside its scope`);
      } else if (kind !== 'lit') throw new Error(kind);
    }
  };
  const fnArity = new Map(allFns.map((f) => [f.name, Array.isArray(f.params) ? f.params.length : -1]));
  const arity = (where, what, got, want) => {
    if (got !== want) fail(where, `${show(what)} takes ${show(want)} argument(s), not ${show(got)}`, 'arity');
  };
  function expr(e, scope) {
    const spec = e && NODES[key(e.node)];
    if (!spec) fail('expression', `unknown node ${JSON.stringify(e?.node)}`);
    const where = `${show(e.node)} at ${show(e.at)}`;
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
    if (e.node === 'CallStd' && !(ROWS[key(e.module)] ?? []).includes(e.name)) fail(where, `unknown std row ${show(e.module)}.${show(e.name)}`);
    // M3c: a GetField may read a ledger record's field (gov.js), never a Data one (JSC1320).
    const govRecord = e.node === 'GetField' && key(e.record) in GOV_RECORDS;
    if (govRecord) {
      const gf = GOV_RECORDS[e.record].find((x) => x.name === e.field);
      if (!gf) fail(where, `${show(e.record)} has no field ${show(e.field)}`, 'field');
      if (gf.type === 'Data') fail(where, `the field ${show(e.record)}.${show(e.field)} is PlutusData, never read (JSC1320)`);
      if (JSON.stringify(e.target.type) !== JSON.stringify(e.record)) fail(where, `a ${show(e.record)} field read from a ${JSON.stringify(e.target.type)}`, 'ledgerType');
    }
    if ((e.node === 'New' || (e.node === 'GetField' && !govRecord)) && !shapes.has(e.record)) fail(where, `no record ${show(e.record)}`);
    if (e.node === 'Variant' && !(enums.get(e.enum) ?? []).includes(e.variant)) fail(where, `no variant ${show(e.enum)}.${show(e.variant)}`);
    // Review S7: references and arities.
    if (e.node === 'CallFn') {
      if (!fnArity.has(e.fn)) fail(where, `no fn ${show(e.fn)}`);
      arity(where, `fn ${show(e.fn)}`, e.args.length, fnArity.get(e.fn));
    }
    if (e.node === 'CallJava') {
      const f = javaFns.get(`${show(e.library)}.${show(e.fn)}`);
      if (!f) fail(where, `no fn ${show(e.fn)} imported from java:${show(e.library)}`);
      arity(where, `java:${show(e.library)}.${show(e.fn)}`, e.args.length, f.params.length);
    }
    if (e.node === 'CallStd') arity(where, e.name, e.args.length, STD_ARITY[key(e.name)]);
    if (e.node === 'LedgerOp') arity(where, e.op, e.args.length, LEDGER_ARITY[key(e.op)]);
    if (e.node === 'Prelude') arity(where, e.op, e.args.length, PRELUDE_ARITY[key(e.op)]);
    if (e.node === 'MapOp' || e.node === 'BigOp') arity(where, `${show(e.node)}.${show(e.op)}`, e.args.length, MAP_ARITY[key(e.op)]);
    if (e.node === 'New') arity(where, `the record ${show(e.record)}`, e.values.length, shapes.get(e.record).length);
    if (e.node === 'Variant') arity(where, `the variant ${show(e.variant)}`, e.values.length, shapes.get(e.variant).length);
    if (e.node === 'GetField' && !govRecord && !shapes.get(e.record).some((f) => f.name === e.field)) fail(where, `${show(e.record)} has no field ${show(e.field)}`, 'field');
    // M3a: in an owned method or an owned type's invariant, the input is the object.
    if (e.node === 'StateField' && !shapes.get(inputRec).some((f) => f.name === e.field)) {
      fail(where, `the ${show(inputRec === 'State' ? 'state' : show(inputRec))} has no field ${show(e.field)}`, 'field');
    }
    if (e.node === 'ParamsRef' && !(p.params?.fields ?? []).some((f) => f.name === e.field)) fail(where, `the params have no field ${show(e.field)}`);
  }
  function stmt(s, scope) {
    // M2c: a send with a datum.
    const spec = s && s.stmt === 'Send' && 'datum' in s ? { ...STMTS.Send, datum: 'e' } : s && STMTS[key(s.stmt)];
    if (!spec) fail('statement', `unknown statement ${JSON.stringify(s?.stmt)}`);
    const where = `${show(s.stmt)} at ${show(s.at)}`;
    keys(s, ['stmt', ...Object.keys(spec), 'at'], where);
    fields(s, spec, where, scope);
    if (s.stmt === 'Require') bool(s.cond, where, 'a require condition');
    // #80 review S3: an if's, a for's or a match's pays is what its blocks say.
    if (s.stmt === 'If' || s.stmt === 'For' || s.stmt === 'Match') {
      if (s.pays !== paysIn({ stmts: [{ ...s, pays: undefined }] })) fail(where, `pays is ${show(s.pays)}, but its blocks ${show(s.pays ? 'do not pay' : 'pay')}`);
    }
    if (s.stmt === 'Emit' && JSON.stringify(s.event.type) !== JSON.stringify({ enum: 'Event' })) fail(where, 'an emitted value is an Event');
    at(s.at, where);
  }
  const fnNames = new Set();
  for (const f of allFns) {
    keys(f, ['name', 'params', 'result', 'body', 'at'], 'fn');
    at(f.at, 'fn');   // its position too (IrReaderFuzzTest)
    if (!(QUALIFIED.test(str(f.name, 'fn')) || IDENT.test(f.name))) fail('fn', `a bad name ${show(f.name)}`);
    if (fnNames.has(f.name)) fail('fn', `two fns named ${show(f.name)}`);
    fnNames.add(f.name);
    const scope = new Set();   // a fn sees its parameters only
    f.params.forEach((l) => decl(l, `fn ${show(f.name)}`, scope));
    type(f.result, `fn ${show(f.name)}`);
    block(f.body, `fn ${show(f.name)}`, scope);
    noPayInExpressions(f.body, true, (what) => fail(`fn ${show(f.name)}`, what));   // a fn never pays (JSC0413)
  }
  const methodNames = new Set();
  const stateFields = new Map(p.state.fields.map((f) => [f.name, f.type]));
  // M3a: the owned types, each a record of "records", none twice.
  const owned = p.owned ?? [];
  if ('owned' in p) {
    if (!Array.isArray(p.owned) || p.owned.length === 0) fail('owned', 'a non-empty list, absent when there are none');
    for (const o of p.owned) if (!p.records.some((r) => r.name === o) || p.owned.indexOf(o) !== p.owned.lastIndexOf(o)) fail('owned', `the owned type ${show(o)} is not one record`);
    // #101 review C3 (round r2): M3a2's owned-type forms (several owned types, a BigMap in an owned type) are staged.
    if (OWNED_STAGE < 2 && p.owned.length > 1) fail('owned', `a second owned type (${show(p.owned[1])}) is not produced yet (M3a2; JSC1215)`);
    for (const o of p.owned) {
      const r = p.records.find((x) => x.name === o);
      if (OWNED_STAGE < 2 && r.fields.some((f) => f.type !== null && typeof f.type === 'object' && 'bigmap' in f.type)) {
        fail('owned', `a BigMap in the owned type ${show(o)} is not produced yet (M3a2; JSC1215)`);
      }
    }
  }
  const keepsBigMap = (t) => (p.records.find((r) => r.name === t)?.fields ?? [])
    .some((f) => f.type !== null && typeof f.type === 'object' && 'bigmap' in f.type);
  const ownedKind = (k) => k === 'ownedInit' || k === 'ownedAction' || k === 'ownedDestroy';
  for (const m of p.methods) {
    const where = `method ${show(m.name)}`;
    // M2c (additive): "reads" appears only when the method reads another contract; M3a: "on", "shared", "into" and
    // "arriving" only for owned methods, moves and admits actions; M3c: "from" and "old" only for an init migrate.
    // A method that is not an object is refused by keys (IrReaderFuzzTest: 'in' on a number threw a TypeError).
    const extra = m && typeof m === 'object' ? ['reads', 'on', 'shared', 'into', 'arriving', 'from', 'old'].filter((k) => k in m) : [];
    keys(m, ['kind', 'name', 'params', 'mods', 'checked', ...extra, 'usesCtx', 'pays', 'result', 'body', 'at'], where);
    // #101 review C5 (round r2): a present null is a malformed field, not an absent one (as the Java reader reads them).
    for (const k of extra) if (m[k] === null) fail(where, `"${show(k)}" is null: an absent field is not written`);
    at(m.at, where);   // its position too (IrReaderFuzzTest)
    if (!IDENT.test(str(m.name, where))) fail(where, `a bad name ${show(m.name)}`);   // IrReaderFuzzTest: a name [] was accepted
    if (!Array.isArray(m.mods)) fail(where, 'mods is not a list');
    if (!KINDS.has(m.kind)) fail(where, `unknown kind ${show(m.kind)}`);
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
    // #107 round-r2 review C5, F7: the shapes stage 3 admits, as the checker produces them (and as the Java reader).
    if ('into' in m && 'on' in m && keepsBigMap(m.on)) fail(where, `the owned type ${show(m.on)} keeps a BigMap, so it cannot move yet (JSC1212)`);
    if (admits) {
      if (!Array.isArray(m.params) || m.params.length === 0 || m.params[0] === null || m.params[0].type !== 'Bytes') {
        fail(where, 'an admits action\'s first parameter is the arriving object\'s id, a Bytes (JSC1212)');
      }
      if (m.mods.some((x) => x && x.name === 'batched')) fail(where, 'an admits action cannot be batched (JSC1212)');
      if ('arriving' in m && keepsBigMap(admits.field)) {
        fail(where, `the arriving ${show(admits.field)} keeps a BigMap, so an admits action cannot take it as a parameter (JSC1212)`);
      }
    }
    const scope = new Set();
    if ('shared' in m) {
      decl(m.shared, where, scope);
      if (JSON.stringify(m.shared.type) !== JSON.stringify({ record: 'State' })) fail(where, 'the shared state is a State');
    }
    if ('into' in m) {
      decl(m.into, where, scope);
      if (JSON.stringify(m.into.type) !== JSON.stringify({ record: 'State' })) fail(where, 'a move\'s destination is a State');
    }
    // M3c: init migrate's params field (from) and the adopted state (old), declared before the parameters.
    if (('from' in m) !== ('old' in m) || ('from' in m) !== (m.kind === 'migrate')) fail(where, 'an init migrate, and only one, names from and old');
    if ('old' in m) {
      str(m.from, where);
      decl(m.old, where, scope);
    }
    m.params.forEach((l) => decl(l, where, scope));
    if ('arriving' in m) {
      decl(m.arriving, where, scope);
      if (JSON.stringify(m.arriving.type) !== JSON.stringify({ record: admits.field })) fail(where, `the arriving object is a ${show(admits.field)}`);
    }
    // #112 review C5: reads is written only when a method reads another contract: a present empty list is malformed.
    if (Array.isArray(m.reads) && m.reads.length === 0) fail(where, '"reads" is empty: an absent field is not written');
    // M2c: each read: a local of the instance's state type, named by a params field Instance<C>.
    for (const r of m.reads ?? []) {
      keys(r, ['local', 'field'], where);
      const f = (p.params?.fields ?? []).find((x) => x.name === str(r.field, where));
      if (!f || typeof f.type !== 'object' || !('instance' in f.type)) fail(where, `reads params.${show(r.field)}, which is not an Instance<C>`);
      decl(r.local, where, scope);
      if (JSON.stringify(r.local.type) !== JSON.stringify({ record: `${show(f.type.instance)}.State` })) fail(where, `a read of ${show(f.type.instance)} has its state's type`);
    }
    // M3a: an owned method's modifiers read its object's fields.
    const input = 'on' in m ? new Map(p.records.find((r) => r.name === m.on).fields.map((f) => [f.name, f.type])) : stateFields;
    modifiers(m, where, fail, keys, input, (ref, want, w) => modField(ref, want, w, 'on' in m ? m.on : 'State'), type);
    // #130 round r1 (Codex X4): a view has a result type, as the Java reader requires (it read null as a ledger type).
    if (m.kind === 'view' && m.result === null) fail(where, 'a view has a result type');
    if (m.result !== null) type(m.result, where);
    inputRec = m.on ?? 'State';
    block(m.body, where, scope);
    inputRec = 'State';
    // #80 review C4, S7; #98 F1: an effect only where the checker allows one (JSC0413): in an action's or destroy's
    // statement blocks, never in an expression's block (a value block, a value if or match, a lambda), so the statement
    // walks below (paysIn, movesIn) are complete.
    // #98 review F1: an init and a view move nothing (JSC0404): their whole body is pure.
    // M3c: a certify, vote or propose handler and an init migrate move nothing (JSC1319, JSC1338).
    noPayInExpressions(m.body, ['init', 'view', 'migrate', 'certify', 'vote', 'propose'].includes(m.kind), (what) => fail(where, what));
    // #80 review S3: pays is recomputed from the body, and a method that pays says who may call it (JSC0408, JSC0420).
    const pays = paysIn(m.body);
    if (m.pays !== pays) fail(where, `pays is ${JSON.stringify(m.pays)}, but the body ${show(pays ? 'pays' : 'does not pay')}`);
    // M2a/M2c: send, mint, burn and deposit move value as a pay does; M2b: quorum and authorized are audiences too.
    const moves = pays || movesIn(m.body);
    if (moves && !m.mods.some((x) => x.name === 'open' || x.name === 'quorum' || x.name === 'authorized'
      || (x.name === 'only' && typeof x.field === 'string'))) {
      fail(where, 'it pays, sends, mints, burns or deposits, so it says who may call it (only f, open, quorum or authorized)');
    }
    // #80 review F7: a destroy ends the instance, so it pays out what it holds (JSC0409).
    // M3c: a migrating destroy moves everything into its successor and nothing else (JSC1332, purposeRules).
    if ((m.kind === 'destroy' || m.kind === 'ownedDestroy') && !moves && !m.mods.some((x) => x.name === 'migrates')) {
      fail(where, `a destroy pays out what ${show(m.kind === 'destroy' ? 'the instance' : 'its object')} holds: it has no pay, send, mint, burn or deposit`);
    }
    // F7: the checker's two decisions, recomputed from their definitions. M3c: a certify, vote or propose handler's
    // parameters are the purpose's data (from the script context), a withdrawal's amount is checked by its validator.
    const redeemerParams = ['view', 'certify', 'vote', 'propose'].includes(m.kind) ? [] : m.kind === 'withdraw' ? m.params.slice(1) : m.params;
    const checked = redeemerParams.filter((l) => needsCheck(l.type, shapes, enums)).map((l) => l.id);
    if (JSON.stringify(checked) !== JSON.stringify(m.checked)) {
      fail(where, `checked is ${JSON.stringify(m.checked)}, but the parameters whose types hold a Key, a Hash or a Map are ${JSON.stringify(checked)}`);
    }
    // M3a: an owned destroy reads it as a destroy does; a method with a shared or into parameter takes it (as the Java examples do).
    const usesCtx = m.kind === 'destroy' || m.kind === 'ownedDestroy' || 'shared' in m || 'into' in m || (m.kind !== 'view'
      && (m.mods.some((x) => !NO_CTX.has(x.name)) || readsTx(m.body)));
    if (usesCtx !== m.usesCtx) fail(where, `usesCtx is ${show(m.usesCtx)}, but the method ${show(usesCtx ? 'reads' : 'does not read')} the transaction`);
  }
  // M3 final pass MS2: the checker's contract-level owned rules: no instance destroy beside objects (JSC1210), no BigMap
  // state beside them (JSC1211), keyed objects included.
  if (owned.length > 0 && p.methods.some((m) => m.kind === 'destroy')) fail('owned', 'a contract with owned objects destroys no instance (JSC1210)');
  if (owned.length > 0 && p.state.fields.some((fd) => fd.type && typeof fd.type === 'object' && 'bigmap' in fd.type)) {
    fail('owned', 'a contract with owned objects keeps no BigMap state (JSC1211)');
  }
  // M3 final pass (design MD2): beside an owned type that keeps a BigMap, no move, batched action, deposits, read of another
  // contract, certify, vote or propose handler, or invariant by type (JSC1217, as the checker).
  const mapped = owned.find((o) => keepsBigMap(o));
  if (mapped !== undefined) {
    for (const m of p.methods) {
      const has = (w) => m.mods.some((x) => x && x.name === w);
      const part = 'into' in m || has('admits') ? 'a move' : has('batched') ? 'a batched action' : has('receives') ? 'taking deposits'
        : (m.reads ?? []).length > 0 ? 'a read of another contract'
          : ['certify', 'vote', 'propose'].includes(m.kind) ? `a ${show(m.kind)} handler` : null;
      if (part) fail(`method ${show(m.name)}`, `${show(part)} is not supported beside an owned type that keeps a BigMap (${show(mapped)}) yet (JSC1217)`);
    }
    for (const inv of p.invariants ?? []) {
      if ('on' in inv && !keepsBigMap(inv.on)) {
        fail(`invariant ${show(inv.name)}`, `an invariant by type is not supported beside an owned type that keeps a BigMap (${show(mapped)}) yet (JSC1217)`);
      }
    }
  }
  // #101 review F2: each owned type has an owned init (JSC1208) and an escape hatch (JSC1209): an owned destroy without
  // shared, before or after.
  for (const o of owned) {
    // #101 review F2, M3a3 (JSC1214): a move of a type needs an admits action for it, and the reverse.
    const moves = p.methods.some((m) => m.on === o && 'into' in m);
    const admits = p.methods.some((m) => m.mods.some((x) => x && x.name === 'admits' && x.field === o));
    if (moves !== admits) fail('owned', moves ? `a move of ${show(o)} needs an admits action for it (JSC1214)` : `admits ${show(o)}, but no owned action moves a ${show(o)} (JSC1214)`);
    if (!p.methods.some((m) => m.kind === 'ownedInit' && m.on === o)) fail('owned', `the owned type ${show(o)} has no owned init (JSC1208)`);
    if (!p.methods.some((m) => m.kind === 'ownedDestroy' && m.on === o && !('shared' in m)
      && !m.mods.some((x) => x && (x.name === 'before' || x.name === 'after')))) {
      fail('owned', `the owned type ${show(o)} has no escape hatch: an owned destroy without shared, before or after (JSC1209)`);
    }
  }
  // #101 review C4 (round r2): an escape hatch that never reads balance could never pay out what its object holds (JSC1216).
  for (const m of p.methods) {
    if (m.kind === 'ownedDestroy' && !('shared' in m) && !m.mods.some((x) => x && (x.name === 'before' || x.name === 'after'))
      && !readsBalance(m.body)) {
      fail(`method ${show(m.name)}`, 'it is its type\'s escape hatch but never reads balance (JSC1216)');
    }
  }
  keyedRules(p, owned, fail, keys);
  purposeRules(p, owned, fail, contracts);
  for (const n of p.recursiveFns) if (!fnNames.has(n)) fail('recursiveFns', `no fn ${show(n)}`);
  // M2b: invariants, each a Bool over the state and the params, with names of their own.
  if ('invariants' in p) {
    const names = new Set();
    for (const inv of p.invariants) {
      const where = `invariant ${show(inv.name)}`;
      keys(inv, ['name', ...('on' in inv ? ['on'] : []), 'body', 'at'], where);
      if (!IDENT.test(str(inv.name, where))) fail(where, `a bad name ${show(inv.name)}`);   // IrReaderFuzzTest: a name [] was accepted
      if ('on' in inv && !owned.includes(inv.on)) fail(where, `the invariant's type ${show(inv.on)} is not an owned type`);
      // #107 round-r2 review F7: an owned type that keeps a BigMap has no invariants (JSC1213).
      if ('on' in inv && keepsBigMap(inv.on)) fail(where, `the owned type ${show(inv.on)} keeps a BigMap, so it has no invariants (JSC1213)`);
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
 * M3b (ADR-0016 D1, K2): a keyed program only in the checker's shapes, as the Java reader reads it (IrJson.keyedChecked).
 * Each entry of "keyed" names an owned type once, its key a Bytes, Key or Hash field of that type, with the options'
 * ranges; a keyed contract has at most 16 keyed types, maxDatumBytes at most 2048, no batched action, no BigMap in an
 * owned type, no native assets, no key that is a time field; each keyed type has a retiring exit signed by its owner that
 * needs nobody else; every keyed action names its audience; no keyed type moves; retire is only a keyed type's.
 */
function keyedRules(p, owned, fail, keys) {
  const keyed = new Map();
  if ('keyed' in p) {
    if (!Array.isArray(p.keyed) || p.keyed.length === 0) fail('keyed', 'a non-empty list, absent when there are none');
    for (const k of p.keyed) {
      keys(k, ['type', 'key', 'maxKeyBytes', 'signed', 'linkCooldownMs', 'linkWindowMs'], 'a keyed type');
      if (!owned.includes(k.type) || keyed.has(k.type)) fail('keyed', `keyed type ${show(k.type)} is not one owned type`);
      if (typeof k.key !== 'string') fail('keyed', `keyed type ${show(k.type)}: its key is a field name`);
      if (typeof k.signed !== 'boolean') fail('keyed', `keyed type ${show(k.type)}: signed is a boolean`);
      for (const f of ['maxKeyBytes', 'linkCooldownMs', 'linkWindowMs']) {
        if (!Number.isSafeInteger(k[f])) fail('keyed', `keyed type ${show(k.type)}: ${show(f)} is an integer`);
      }
      keyed.set(k.type, k);
    }
  }
  for (const m of p.methods) {
    if (m.mods.some((x) => x.name === 'retire') && !keyed.has(m.on)) fail(`method ${show(m.name)}`, 'only a keyed type\'s owned destroy retires');
  }
  if (keyed.size === 0) return;
  if (keyed.size > 16) fail('keyed', `a contract has at most 16 keyed types, not ${show(keyed.size)}`);
  let maxDatum = 512n, maxValidity = 3600000n;
  for (const o of p.options) {
    if (o.name === 'maxDatumBytes') maxDatum = BigInt(o.value);
    if (o.name === 'maxValidityMs') maxValidity = BigInt(o.value);
  }
  if (maxDatum > 2048n) fail('keyed', `maxDatumBytes is at most 2048 in a contract with keyed types, not ${show(maxDatum)}`);
  for (const o of owned) {
    if (p.records.find((r) => r.name === o).fields.some((f) => f.type && typeof f.type === 'object' && 'bigmap' in f.type)) {
      fail('keyed', `owned type ${show(o)} keeps a BigMap beside keyed types`);
    }
  }
  if (assetsIn(p.methods)) fail('keyed', 'a keyed contract handles no native assets (send, mint, accept, deposit, funds)');
  const windowCap = maxValidity < 900000n ? maxValidity : 900000n;
  for (const [t, k] of keyed) {
    const kt = p.records.find((r) => r.name === t).fields.find((f) => f.name === k.key)?.type;
    if (!['Bytes', 'Key', 'Hash'].includes(kt)) fail('keyed', `keyed type ${show(t)}: its key ${show(k.key)} is not a Bytes, Key or Hash field`);
    if (k.maxKeyBytes < 1 || k.maxKeyBytes > 64) fail('keyed', `keyed type ${show(t)}: maxKeyBytes is 1 to 64`);
    if ((kt === 'Key' || kt === 'Hash') && k.maxKeyBytes < 28) fail('keyed', `keyed type ${show(t)}: a Key or Hash key is 28 bytes: maxKeyBytes at least 28`);
    if (k.signed && (k.maxKeyBytes < 28 || kt === 'Hash')) fail('keyed', `keyed type ${show(t)}: a signed key is a 28-byte key hash (maxKeyBytes at least 28, no Hash)`);
    if (k.linkCooldownMs !== 0 && (k.linkCooldownMs < 60000 || k.linkCooldownMs > 3600000)) fail('keyed', `keyed type ${show(t)}: linkCooldownMs is 0 or 60000 to 3600000`);
    if (k.linkWindowMs < 20000 || BigInt(k.linkWindowMs) > windowCap) fail('keyed', `keyed type ${show(t)}: linkWindowMs is 20000 to min(900000, maxValidityMs)`);
    if (p.timeFields.includes(`${show(t)}.${show(k.key)}`)) fail('keyed', `keyed type ${show(t)}: its key is a time field`);
    let hatch = false;
    for (const m of p.methods) {
      if (m.on !== t) continue;
      const names = new Set(m.mods.map((x) => x.name));
      if (m.kind === 'ownedDestroy' && names.has('retire')) {
        if (!names.has('only') || ['payable', 'quorum', 'authorized', 'after', 'before'].some((x) => names.has(x)) || 'shared' in m || 'reads' in m) {
          fail(`method ${show(m.name)}`, 'a retiring exit is signed by its owner (only f) and needs nobody else (no shared, reads, payable, quorum, authorized, after or before)');
        }
        // #112 round r2, Codex C6 (JSC1245, as the checker since security S2): nor does its body decide on time.
        if (hasTimePred(m.body)) fail(`method ${show(m.name)}`, 'a retiring exit decides nothing on time (no after or before in its body)');
        hatch = true;
      }
      if (m.kind === 'ownedAction' && !['only', 'quorum', 'authorized'].some((x) => names.has(x))) {
        fail(`method ${show(m.name)}`, 'a keyed action names its audience (only, quorum or authorized)');
      }
      if ('into' in m) fail(`method ${show(m.name)}`, 'a keyed object does not move (into)');
    }
    if (!hatch) fail('keyed', `keyed type ${show(t)} has no retiring exit (an owned destroy with retire)`);
  }
  for (const m of p.methods) {
    if (m.mods.some((x) => x.name === 'batched')) fail(`method ${show(m.name)}`, 'a keyed contract has no batched action');
    const admits = m.mods.find((x) => x.name === 'admits');
    if (admits && keyed.has(admits.field)) fail(`method ${show(m.name)}`, `a keyed object does not move (admits ${show(admits.field)})`);
    if (m.mods.some((x) => x.name === 'receives')) fail(`method ${show(m.name)}`, 'a keyed contract receives no deposits');
  }
}

/**
 * M3c (ADR-0010 D1, D2; ADR-0011 D4; K2, K39, K48): a program with script purposes or migration only in the checker's
 * shapes, as the Java reader reads it (IrJson.purposesChecked; the shared mutants test/mutants/purpose-*.json). The option
 * stakeSelf is the flag "1", once, and needs a withdraw and a certify handler (JSC1302). No ledger type of the purposes is
 * a field of the state, the params, a record, a variant or an imported contract's type, a parameter of a method but a
 * handler's purpose data, or a view's result (JSC1321). Each handler, one per purpose (JSC1316), in a contract with
 * params (JSC1317): the processor's parameter shapes (JSC1314); a withdraw handler's result none, the others' a Bool
 * (JSC1340, JSC1341); no input state (JSC1318); a withdraw handler's only effect a Send (JSC1319); certify, vote and
 * propose without the transaction (JSC1315); its audience quorum or authorized over params.*, required but for propose,
 * which takes none (JSC1312, JSC1313). An init migrate, at most one (JSC1337): no modifiers (JSC1339), its from a Hash or
 * Bytes params field (JSC1335), its old an imported contract's state without BigMaps (JSC1336, JSC1334), its result the
 * state. A migrating destroy: no effects (JSC1332), an audience (JSC1333), its field no time field. Neither side with
 * owned types, BigMap state or native assets (JSC1334).
 */
/** Whether an IR type is, or holds, a purposes' ledger type or a LedgerMap (holdsGov, Data included). */
function holdsLedger(t) {
  return holdsGov(t);
}

/**
 * Review C2 (r2): the ledger types' expression rules, as the checker's, anywhere in a body or a fn: no equality on a ledger
 * value (JSC1322: an Equals operand, a list's contains or indexOf over ledger elements), no set or delete on a LedgerMap
 * (JSC1324), no binding of an opaque field (JSC1320: a match arm's bind of type Data).
 */
function ledgerExpressions(json, where, fail) {
  if (Array.isArray(json)) {
    for (const o of json) ledgerExpressions(o, where, fail);
    return;
  }
  if (!json || typeof json !== 'object') return;
  const n = json.node;
  if (n === 'Equals' && holdsLedger(json.operand)) fail(where, 'a ledger value of the purposes is not comparable (JSC1322)');
  // Review C7 (r3): membership is the Contains node, by its element type.
  if (n === 'Contains' && holdsLedger(json.elem)) fail(where, 'a ledger value of the purposes is not comparable (JSC1322)');
  if (n === 'CallStd' && json.name === 'indexOf' && Array.isArray(json.args) && json.args[0] && holdsLedger(json.args[0].type)) {
    fail(where, 'a ledger value of the purposes is not comparable (JSC1322)');
  }
  if (n === 'MapOp' && (json.op === 'set' || json.op === 'delete') && json.map && json.map.type && typeof json.map.type === 'object'
    && 'ledgerMap' in json.map.type) {
    fail(where, 'a LedgerMap is read-only (JSC1324)');
  }
  if (Array.isArray(json.binds)) {
    for (const b of json.binds) if (b && b.type === 'Data') fail(where, 'a PlutusData field is never bound (JSC1320)');
  }
  for (const v of Object.values(json)) ledgerExpressions(v, where, fail);
}

function purposeRules(p, owned, fail, contracts) {
  for (const m of p.methods) ledgerExpressions(m.body, `method ${show(m.name)}`, fail);
  for (const f of p.fns ?? []) ledgerExpressions(f.body, `fn ${show(f.name)}`, fail);
  // Review C8 (r3): and every imported .jsc library's fns, which the program's bodies call.
  for (const l of p.libraries ?? []) for (const f of l.fns ?? []) ledgerExpressions(f.body, `fn ${show(l.library)}.${show(f.name)}`, fail);
  const stake = p.options.filter((o) => o.name === 'stakeSelf');
  if (stake.length > 1 || stake.some((o) => o.value !== '1')) fail('options', 'the option stakeSelf is the flag "1", once (JSC1301)');
  // Review F1, S3: not beside owned objects yet (their outputs' stake part is not covered).
  if (stake.length > 0 && owned.length > 0) fail('options', 'the option stakeSelf is not supported beside owned types yet (JSC1305)');
  const stored = [...p.records, p.state, ...('params' in p ? [p.params] : []), ...p.enums.flatMap((e) => e.variants)];
  for (const c of contracts.values()) stored.push(c.state, ...c.records, ...c.enums.flatMap((e) => e.variants));
  for (const r of stored) {
    for (const fd of r.fields) if (holdsGov(fd.type)) fail(`${show(r.name)}.${show(fd.name)}`, 'a ledger type of the script purposes is no field (JSC1321)');
  }
  const seen = new Map();
  let migrates = false;
  const mods = (m) => m.mods.map((x) => x.name);
  for (const m of p.methods) {
    const where = `method ${show(m.name)}`;
    const redeemer = PURPOSES.has(m.kind) ? (m.kind === 'withdraw' ? m.params.slice(1) : []) : m.params;
    for (const l of redeemer) if (holdsGov(l.type)) fail(where, `the parameter ${show(l.name)} is a ledger type of the purposes, which no redeemer carries (JSC1321)`);
    if (m.kind === 'view' && holdsGov(m.result)) fail(where, 'a view\'s result is no ledger type (JSC1321)');
    if (PURPOSES.has(m.kind) || m.kind === 'migrate') {
      if (seen.has(m.kind)) fail(where, `at most one ${show(m.kind)}${show(m.kind === 'migrate' ? ' (JSC1337)' : ' handler (JSC1316)')}`);
      seen.set(m.kind, m);
    }
    if (m.kind === 'withdraw' && p.state.fields.some((fd) => fd.type && typeof fd.type === 'object' && 'bigmap' in fd.type)) {
      fail(where, 'a withdraw handler is not supported beside BigMap state yet (JSC1305)');
    }
    // M3 final pass MD6: beside keyed types the checker's code is JSC1244 (the processor's keyed rule).
    if (m.kind === 'withdraw' && owned.length > 0) {
      fail(where, Array.isArray(p.keyed) && p.keyed.length > 0 ? 'a withdraw handler is not supported beside keyed types (JSC1244)'
        : 'a withdraw handler is not supported beside owned types yet (JSC1305)');
    }
    if (PURPOSES.has(m.kind)) {
      if (!('params' in p)) fail(where, 'a handler is governed by the params, which the contract lacks (JSC1317)');
      const shape = { certify: ['TxCert'], vote: ['Voter', 'GovernanceActionId', 'Vote'], propose: ['ProposalProcedure'] }[m.kind];
      const types = m.params.map((l) => JSON.stringify(l.type));
      if (shape ? JSON.stringify(types) !== JSON.stringify(shape.map((t) => JSON.stringify(t))) : types.length === 0 || types[0] !== '"Int"') {
        fail(where, `a ${show(m.kind)} handler's parameters are not the processor's shape (JSC1314)`);
      }
      if (m.kind === 'withdraw' ? m.result !== null : m.result !== 'Bool') fail(where, `a ${show(m.kind)} handler ${show(m.kind === 'withdraw' ? 'has no result (JSC1340)' : 'is a Bool (JSC1341)')}`);
      if (m.kind !== 'withdraw' && m.usesCtx) fail(where, `a ${show(m.kind)} handler takes no transaction (JSC1315)`);
      if (hasNode(m.body, ['StateRef', 'StateField'])) fail(where, 'a handler reads no instance state (JSC1318)');
      if (hasNode(m.body, ['FundsRef'])) fail(where, 'a handler reads no funds (JSC1315)');
      if (m.kind === 'withdraw' && hasNode(m.body, ['Pay', 'Mint', 'Accept', 'Deposit', 'Emit'])) fail(where, 'a withdraw handler\'s only effect is send (JSC1319)');
      if (m.kind === 'propose' && m.mods.length > 0) fail(where, 'a propose handler takes no modifiers (JSC1313)');
      for (const x of m.mods) {
        if (x.name !== 'quorum' && x.name !== 'authorized') fail(where, `a handler takes only quorum or authorized, not ${show(x.name)} (JSC1313)`);
        const refs = x.name === 'quorum' ? [x.keys, x.threshold] : [x.field];
        if (refs.some((r) => typeof r !== 'string' || !r.startsWith('params.'))) fail(where, 'a handler\'s audience names params fields (JSC1312)');
      }
      if (m.kind !== 'propose' && m.mods.length === 0) fail(where, `a ${show(m.kind)} handler needs quorum or authorized (JSC1312)`);
    }
    if (m.kind === 'migrate') {
      if (hasNode(m.body, ['FundsRef'])) fail(where, 'an init migrate reads no funds (JSC1338)');
      const from = (p.params?.fields ?? []).find((x) => x.name === m.from);
      if (!from || !['Hash', 'Bytes'].includes(from.type)) fail(where, 'init migrate\'s from names a Hash or Bytes params field (JSC1335)');
      const c = [...contracts.values()].find((x) => JSON.stringify(m.old.type) === JSON.stringify({ record: x.state.name }));
      if (!c) fail(where, 'init migrate adopts an imported contract\'s state (JSC1336)');
      if (c.bigMaps) fail(where, 'the previous version keeps BigMap state (JSC1334)');
      if (JSON.stringify(m.result) !== JSON.stringify({ record: 'State' })) fail(where, 'init migrate\'s result is the state');
    }
    const to = m.mods.find((x) => x.name === 'migrates');
    if (to) {
      migrates = true;
      if (p.timeFields.includes(to.field)) fail(where, `migrates to ${show(to.field)}: a time field (JSC1330)`);
      if (hasNode(m.body, ['Pay', 'Send', 'Mint', 'Accept', 'Deposit', 'Emit'])) fail(where, 'a migrating destroy has no effects (JSC1332)');
      if (!['only', 'quorum', 'authorized'].some((x) => mods(m).includes(x))) fail(where, 'a migrating destroy names its audience (JSC1333)');
    }
  }
  if (stake.length === 1 && !(seen.has('withdraw') && seen.has('certify'))) fail('options', 'stakeSelf needs a withdraw and a certify handler (JSC1302)');
  if (migrates || seen.has('migrate')) {
    const assets = p.methods.some((m) => !PURPOSES.has(m.kind) && (assetsIn(m.body) || m.mods.some((x) => x.name === 'receives')));
    if (owned.length > 0 || p.state.fields.some((fd) => fd.type && typeof fd.type === 'object' && 'bigmap' in fd.type) || assets) {
      fail('program', 'migration with owned types, BigMap state or native assets (JSC1334)');
    }
  }
}

/** Whether a method's body holds a native-asset statement or reads funds (M3b: none in a keyed contract). */
function assetsIn(o) {
  if (Array.isArray(o)) return o.some(assetsIn);
  if (o !== null && typeof o === 'object') {
    if (['Send', 'Mint', 'Accept', 'Deposit'].includes(o.stmt)) return true;
    if (o.node === 'FundsRef') return true;
    return Object.values(o).some(assetsIn);
  }
  return false;
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
    else if (w === 'payable' || w === 'open' || w === 'retire') keys(mod, ['name'], where);
    else keys(mod, ['name', 'field'], where);
    if (w === 'batched' && 'field' in mod && mod.field === null) fail(where, 'batched names no field or "only", not null');
    if (!MODS.has(w)) fail(where, `unknown modifier ${show(w)}`);
    const field = mod.field ?? null;
    if (seen.has(w)) fail(where, `the modifier ${show(w)} is given twice`);
    seen.add(w);
    if (m.kind === 'view') fail(where, `a view takes no modifiers, not ${show(w)}`);
    if ((m.kind === 'init' || m.kind === 'ownedInit') && w !== 'payable') fail(where, `an init takes only payable, not ${show(w)}`);
    if (m.kind === 'migrate') fail(where, `an init migrate takes no modifiers, not ${show(w)} (JSC1339)`);
    if (w === 'payable' || w === 'open' || w === 'retire') {
      if (field !== null) fail(where, `${show(w)} names no field, not ${JSON.stringify(field)}`);
      // M3b (ADR-0016 D10, JSC1245): retire is an owned destroy's (keyed, checked with the program).
      if (w === 'retire' && m.kind !== 'ownedDestroy') fail(where, `only an owned destroy retires, not a${show(m.kind === 'init' ? 'n' : '')} ${show(m.kind)}`);
      if (w === 'payable' && m.kind === 'destroy') fail(where, 'a destroy cannot be payable');
    } else if (w === 'only' || w === 'after' || w === 'before') {
      const want = w === 'only' ? 'Key' : 'Int';
      if (typeof field !== 'string') fail(where, `${show(w)} names a state field, not ${JSON.stringify(field)}`);
      if (!state.has(field)) fail(where, `${show(w)} ${show(field)}: the state has no field ${show(field)}`, 'field');
      if (state.get(field) !== want) fail(where, `${show(w)} ${show(field)}: ${show(field)} is of type ${JSON.stringify(state.get(field))}, not ${show(want)}`, 'type');
    } else if (w === 'quorum') {
      // M2b (ADR-0009 D2, JSC0439): keys a List<Key> and the threshold an Int, both state fields or both params fields.
      if (typeof mod.keys !== 'string' || typeof mod.threshold !== 'string') fail(where, 'a quorum names its keys and threshold');
      if (mod.keys.startsWith('params.') !== mod.threshold.startsWith('params.')) {
        fail(where, `quorum ${show(mod.keys)} ${show(mod.threshold)}: the keys and the threshold are both state fields or both params fields`);
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
    } else if (w === 'migrates') {
      // M3c (ADR-0011 D4, JSC1330, JSC1331): an instance destroy, naming a Bytes or Hash state field.
      if (m.kind !== 'destroy') fail(where, `only an instance destroy migrates, not a ${show(m.kind)}`);
      if (typeof field !== 'string' || !['Bytes', 'Hash'].includes(state.get(field))) fail(where, `migrates to names a Bytes or Hash state field, not ${JSON.stringify(field)}`);
    } else if (w === 'admits') {
      // M3a (ADR-0012 D3, JSC1212): an instance action, naming an owned type (checked with the method).
      if (typeof field !== 'string') fail(where, 'admits names an owned type');
      if (m.kind !== 'action') fail(where, 'only an action admits objects');
    } else if (w === 'batched') {
      if (field !== null && field !== 'only') fail(where, `batched is plain or "batched only", not ${JSON.stringify(field)}`);
      if (m.kind !== 'action') fail(where, `only an action can be batched, not a${show(m.kind === 'init' ? 'n' : '')} ${show(m.kind)}`);
      // #80 review S3: as the checker (JSC0419): no batched action in a contract with BigMap state.
      if ([...state.values()].some((t) => t && typeof t === 'object' && 'bigmap' in t)) fail(where, 'a contract with BigMap state has no batched action');
    }
  }
  if (names.includes('batched')) {
    // JSC0417 (with M2b's quorum and authorized), JSC0447 (receives).
    for (const w of ['only', 'after', 'before', 'quorum', 'authorized', 'receives']) if (names.includes(w)) fail(where, `a batched action cannot use ${show(w)}`);
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
  if (pure && EFFECT_STMTS.has(o.stmt)) fail(`a ${show(o.stmt.toLowerCase())} inside an expression or a fn, where the checker allows none (JSC0413; in an init or a view, JSC0404), at ${show(o.at)}`);
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
// A type that reaches itself is walked once: a second visit adds no field the first does not reach. The checker refuses
// recursive types, so only a hostile IR holds one (IrReaderFuzzTest: a library enum with a variant holding itself made
// this a stack overflow).
function needsCheck(t, shapes, enums, seen = new Set()) {
  // M2c (review r1, C4): a Value too (it crosses only stored in a read state; an argument never holds one).
  if (typeof t === 'string') return t === 'Key' || t === 'Hash' || t === 'Address' || t === 'Credential' || t === 'Value';
  const [k, v] = Object.entries(t)[0];
  if (k === 'instance' || k === 'deposit') return false;   // M2c: the deployer's; memos are checked where the action starts
  if (k === 'ledgerMap') return false;   // M3c: from the script context (a ledger type's name is a string above, never a Key)
  if (k === 'list' || k === 'option') return needsCheck(v, shapes, enums, seen);
  if (k === 'map') return true;
  if (k === 'bigmap') return false;
  const id = `${show(k)}:${show(v)}`;
  if (seen.has(id)) return false;
  seen.add(id);
  if (k === 'record') return shapes.get(v).some((f) => needsCheck(f.type, shapes, enums, seen));
  return enums.get(v).some((variant) => shapes.get(variant).some((f) => needsCheck(f.type, shapes, enums, seen)));
}

/** Whether IR holds a TimePred (after(…) or before(…)) at any depth. */
function hasTimePred(node) {
  if (Array.isArray(node)) return node.some(hasTimePred);
  if (!node || typeof node !== 'object') return false;
  return node.node === 'TimePred' || Object.values(node).some(hasTimePred);
}

/** Whether a method body names the transaction: caller, received, balance, the window, after/before or std/time. */
function readsTx(node) {
  if (Array.isArray(node)) return node.some(readsTx);
  if (!node || typeof node !== 'object') return false;
  if (node.node === 'CtxRef' || node.node === 'TimePred' || (node.node === 'CallStd' && node.module === 'std/time')) return true;
  return Object.values(node).some(readsTx);
}
