// The reference interpreter of .jsc 2 (ADR-0018 D15): a direct evaluator of the checked IR (docs/reference/jsc-2-ir.md),
// written from docs/reference/jsc-2-semantics.md. The section numbers below are that document's. It shares nothing
// with the Java code but the IR file: its values, arithmetic, equality, map order, checked inputs, standard library,
// hashes and Ed25519 are its own.

import {
  NONE, Some, some, Rec, MapV, BigMapV, Range, Refused, Limit, bytesEqual, bytesCompare, keyCompare, hex, unsignedBytes,
  ValueV, CredV, AddrV, StakeHash, StakePtr, inRange, reserved,
} from './values.js';
import { parseStrict } from './strict-json.js';
import { validate } from './validate.js';
import { applyStd } from './std.js';

export const IR_VERSION = 1;

/**
 * Reads an IR export from its JSON text and returns the program, ready to call. The text is read strictly (no
 * duplicate keys, integers only, within the safe range) and validated whole before anything runs (validate.js): a
 * version-1 reader refuses what it does not know, and an IR whose checked parameters or transaction use disagree with
 * their definitions.
 */
export function load(text) {
  if (typeof text !== 'string') throw new TypeError('load takes the IR export\'s JSON text');
  const p = parseStrict(text);
  validate(p);
  return new Program(p);
}

/** A binding: locals are bound once, so an environment is a chain searched by id. */
class Env {
  constructor(id, value, next) {
    this.id = id;
    this.value = value;
    this.next = next;
  }
}

function lookup(env, id) {
  for (let e = env; e; e = e.next) if (e.id === id) return e.value;
  throw new Error(`unbound local ${id}`);
}

function bind(env, local, value) {
  return new Env(local.id, value, env);
}

class Program {
  constructor(p) {
    this.ir = p;
    // Field names and types of every record, the state and every variant (variant names are unique in a file).
    this.shapes = new Map();
    const add = (r) => this.shapes.set(r.name, { names: r.fields.map((f) => f.name), types: r.fields.map((f) => f.type) });
    for (const r of p.records) add(r);
    for (const e of p.enums) for (const v of e.variants) add(v);
    add(p.state);
    if (p.params) add(p.params);   // M2b: the params record, Config
    this.invariants = p.invariants ?? [];
    this.params = null;
    this.variantTag = new Map();
    for (const e of p.enums) e.variants.forEach((v, i) => this.variantTag.set(v.name, i));
    this.fns = new Map(p.fns.map((f) => [f.name, f]));
    this.methods = new Map(p.methods.map((m) => [m.name, m]));
  }

  get name() {
    return this.ir.contract;
  }

  kind(method) {
    return this.#method(method).kind;
  }

  usesTx(method) {
    return this.#method(method).usesCtx;
  }

  stateFields() {
    return this.shapes.get('State').names;
  }

  /**
   * M2b (§2.7): the same contract deployed with these params (a Config record), which params.f reads: a new program
   * over the same IR.
   */
  withParams(params) {
    if (!this.ir.params) throw new Error('the contract declares no params');
    if (!(params instanceof Rec) || params.type !== 'Config' || params.fields.length !== this.ir.params.fields.length) {
      throw new Error(`params are a Config record of ${this.ir.params.fields.length} fields`);
    }
    const d = new Program(this.ir);
    d.params = params;
    return d;
  }

  /** A variant's tag (its index in its enum, §1), or 0 for a record. */
  tag(name) {
    return this.variantTag.get(name) ?? 0;
  }

  #method(name) {
    const m = this.methods.get(name);
    if (!m) throw new Error(`no method ${name}`);
    return m;
  }

  /**
   * Calls a method (§2): `state` is the input state (null for an init), `args` the arguments in order, `tx` the
   * transaction context { caller, received, balance, validFrom, validTo, signers } (null where the method neither
   * reads it nor has a modifier that does; `signers` defaults to [caller]). The modifiers are checked first (§2.6).
   * Returns { value, payouts } or throws Refused.
   */
  call(name, state, args, tx) {
    return this.#call(name, state, args, tx, true);
  }

  /** The transition alone (§2.6): a call without its modifiers, as the generated Java and the transition program compute it. */
  transition(name, state, args, tx) {
    return this.#call(name, state, args, tx, false);
  }

  #paramsField(name) {
    if (!this.params) throw new Error('this contract reads its params: supply them (withParams)');
    return this.#field(this.params, name);
  }

  /** A modifier's field: f of the state, or params.f. */
  #modField(state, ref) {
    return ref.startsWith('params.') ? this.#paramsField(ref.slice(7)) : this.#field(state, ref);
  }

  #call(name, state, args, tx, modifiers) {
    const m = this.#method(name);
    if (args.length !== m.params.length) throw new Error(`${name} takes ${m.params.length} arguments`);
    if ((m.kind === 'init') !== (state === null || state === undefined)) throw new Error('an init has no input state; others do');
    return depthGuard(() => {
      if (modifiers) this.#modifiers(m, state, tx);
      const frame = { state, tx, payouts: [], sends: [], mint: ValueV.NONE, accepted: ValueV.NONE, events: [] };
      let env = null;
      m.params.forEach((l, i) => (env = bind(env, l, args[i])));
      // §4: every checked parameter is well formed before any statement runs.
      for (const id of m.checked) {
        const l = m.params.find((x) => x.id === id);
        if (!this.wellFormed(lookup(env, id), l.type)) throw new Refused(`${l.name} is not a well-formed input`, 'input', l.name);
      }
      env = this.#exec(m.body.stmts, env, frame);
      const value = m.body.result === null ? null : this.#eval(m.body.result, env, frame);
      // §5.4: a method that accepts takes in exactly what the caller offers, checked last.
      if (accepts(m.body) && !frame.accepted.equals(funds(frame).received)) {
        throw new Refused('the method accepts other native assets than the caller offers', 'domain', 'accepted');
      }
      const out = { value, payouts: frame.payouts, sends: frame.sends, mint: frame.mint, accepted: frame.accepted, events: frame.events };
      if (modifiers) this.#outputs(m, value);
      return out;
    });
  }

  /**
   * §2.7 (M2b): what the validator checks of every state it accepts as an output, after the body. At a creation, each
   * quorum naming params is well formed; then for an init's or action's next state each quorum naming state fields
   * (every pair any method's quorum names, once, in source order) is well formed, class invariant:quorum <keys>
   * <threshold>; then each invariant holds, in declaration order, class invariant:<name>.
   */
  #outputs(m, next) {
    if (m.kind !== 'init' && m.kind !== 'action') return;
    const pairs = [];
    for (const x of this.ir.methods) {
      for (const mod of x.mods) {
        if (mod.name !== 'quorum') continue;
        const pair = `${mod.keys} ${mod.threshold}`;
        if (!pairs.includes(pair)) pairs.push(pair);
      }
    }
    for (const pair of pairs) {
      const [k, t] = pair.split(' ');
      if (k.startsWith('params.') && m.kind !== 'init') continue;
      if (!wellFormedQuorum(this.#modField(next, k), this.#modField(next, t))) {
        throw new Refused(`the quorum ${pair} is not well formed`, 'invariant', `quorum ${pair}`);
      }
    }
    for (const inv of this.invariants) {
      const frame = { state: next, tx: null, payouts: [], sends: [], mint: ValueV.NONE, accepted: ValueV.NONE, events: [] };
      if (this.#block(inv.body, null, frame) !== true) throw new Refused(`the invariant ${inv.name} does not hold`, 'invariant', inv.name);
    }
  }

  /**
   * §2.6: the transaction preconditions, in source order. `only f`: the key in state field f is among the signers;
   * `after f`: the window starts at or after f; `before f`: it ends at or before f. A broken one refuses the call with
   * the class modifier:<word> <field>.
   */
  #modifiers(m, state, tx) {
    for (const mod of m.mods) {
      const w = mod.name;
      const field = w === 'quorum' ? `${mod.keys} ${mod.threshold}` : mod.field;
      if (!['only', 'after', 'before', 'quorum', 'authorized'].includes(w) || field === null || field === undefined) continue;
      if (!tx) throw new Error(`${m.name} has '${w} ${field}': its call needs the transaction`);
      const signers = tx.signers ?? [tx.caller];
      const signed = (k) => signers.some((x) => bytesEqual(x, k));
      let holds;
      if (w === 'only') holds = signed(this.#field(state, field));
      else if (w === 'after') holds = tx.validFrom >= this.#field(state, field);
      else if (w === 'before') holds = tx.validTo <= this.#field(state, field);
      else if (w === 'quorum') {
        // M2b: at least threshold >= 1 distinct signers among the keys (each read from the input state or the params).
        const keys = this.#modField(state, mod.keys), t = this.#modField(state, mod.threshold);
        const distinct = signers.filter((x, i) => signers.findIndex((y) => bytesEqual(x, y)) === i);
        const n = distinct.filter((x) => keys.some((k) => bytesEqual(k, x))).length;
        holds = t >= 1n && BigInt(n) >= t;
      } else {
        // M2b: a key credential signed; a script credential is among the transaction's approvers.
        const c = this.#modField(state, field);
        holds = c.script ? (tx.approvers ?? []).some((h) => bytesEqual(h, c.hash)) : signed(c.hash);
      }
      if (!holds) {
        const what = w === 'only' ? `the transaction is not signed by ${field}`
          : w === 'after' ? `the validity window starts before ${field}`
            : w === 'before' ? `the validity window ends after ${field}`
              : w === 'quorum' ? `too few of ${mod.keys} signed the transaction` : `${field} did not authorize the transaction`;
        throw new Refused(what, 'modifier', `${w} ${field}`);
      }
    }
  }

  /** Calls a fn directly (§3.9). */
  callFn(name, args) {
    return depthGuard(() => this.#invoke(name, args));
  }

  /** Evaluates one expression node in an empty scope (for the hand-written vectors of §6). */
  evalExpr(expr, { state = null, tx = null } = {}) {
    return depthGuard(() => this.#eval(expr, null, { state, tx, payouts: [], sends: [], mint: ValueV.NONE, accepted: ValueV.NONE }));
  }

  // ------------------------------------------------------------------ statements (§2.2, §2.3)

  #exec(stmts, env, f) {
    for (const s of stmts) {
      switch (s.stmt) {
        case 'Let':
          env = bind(env, s.local, this.#eval(s.value, env, f));
          break;
        case 'Require':
          if (this.#eval(s.cond, env, f) !== true) throw new Refused(s.message, 'require', `${s.at[0]}:${s.at[1]}:${s.message}`)   // §2.5: its position, then its message (review S6);
          break;
        case 'Pay': {
          const to = this.#eval(s.to, env, f);
          const amount = this.#eval(s.amount, env, f);
          if (amount < 0n) throw new Refused('a payout cannot be negative', 'domain', 'pay');
          if (amount > 0n) f.payouts.push({ to, lovelace: amount });
          break;
        }
        case 'Send': {
          // §5.1: both arguments, left to right; no negative quantity, no reserved name of the own policy.
          const to = this.#eval(s.to, env, f);
          const value = this.#eval(s.value, env, f);
          if (!sendable(value, funds(f).policy, false)) throw new Refused('this value cannot be sent', 'domain', 'send');
          f.sends.push({ to, value });
          break;
        }
        case 'Mint': {
          // §5.2: a positive quantity of a user token name (at most 32 bytes, not a reserved kind); the net stays in range.
          const name = this.#eval(s.name, env, f);
          const q = this.#eval(s.qty, env, f);
          if (q <= 0n || name.length > 32 || reserved(name)) {
            throw new Refused(`${s.burn ? 'burn' : 'mint'}: a positive quantity of a user token name`, 'domain', 'mint');
          }
          const policy = funds(f).policy, d = s.burn ? -q : q;
          const total = f.mint.quantity(policy, name) + d;
          if (total !== 0n && !inRange(total)) throw new Refused('mint: a quantity out of the 128-bit range', 'domain', 'mint');
          f.mint = f.mint.plus(ValueV.of(policy, name, d), 1n);
          break;
        }
        case 'Accept': {
          // §5.3: native assets only, every quantity positive, no reserved name of the own policy; summed.
          const v = this.#eval(s.value, env, f);
          if (!sendable(v, funds(f).policy, true)) throw new Refused('this value cannot be accepted', 'domain', 'accept');
          const sum = f.accepted.plus(v, 1n);
          if (!sum.quantities().every(inRange)) throw new Refused('accept: a quantity out of the 128-bit range', 'domain', 'accept');
          f.accepted = sum;
          break;
        }
        case 'Emit':
          // §5.5 (M2b): an event joins the call's events, in source order.
          f.events.push(this.#eval(s.event, env, f));
          break;
        case 'If': {
          // §3: the first branch whose condition holds runs, else the otherwise block (an else-if chain is one node).
          const taken = this.#branch(s.branches, s.otherwise, env, f);
          if (taken) this.#exec(taken.stmts, env, f);
          break;
        }
        case 'For':
          for (const x of this.#seq(this.#eval(s.list, env, f))) this.#exec(s.body.stmts, bind(env, s.var, x), f);
          break;
        case 'Match': {
          const v = this.#eval(s.scrutinee, env, f);
          const arm = this.#arm(s.arms, v);
          this.#exec(arm.body.stmts, this.#bindArm(arm, v, env), f);
          break;
        }
        default:
          throw new Error(`unknown statement ${s.stmt}`);
      }
    }
    return env;
  }

  /** The arm of the first branch whose condition holds, in order, else `otherwise` (null for a statement without one). */
  #branch(branches, otherwise, env, f) {
    for (const b of branches) if (this.#eval(b.cond, env, f) === true) return b.then;
    return otherwise;
  }

  #block(b, env, f) {
    return this.#eval(b.result, this.#exec(b.stmts, env, f), f);
  }

  // ------------------------------------------------------------------ expressions (§3)

  #eval(e, env, f) {
    switch (e.node) {
      case 'Lit':
        return typeof e.value === 'boolean' ? e.value : e.type === 'Int' ? BigInt(e.value) : fromHexLit(e.value);
      case 'LocalRef': return lookup(env, e.local);
      case 'StateRef': return f.state;
      case 'StateField': return this.#field(f.state, e.field);
      case 'CtxRef': {
        if (!f.tx || !(e.name in f.tx)) throw new Error(`no transaction value ${e.name}`);
        return f.tx[e.name];
      }
      case 'NoneLit': return NONE;
      case 'SomeOf': return some(this.#eval(e.value, env, f));
      case 'GetField': return this.#field(this.#eval(e.target, env, f), e.field);
      case 'New': return new Rec(e.record, e.values.map((x) => this.#eval(x, env, f)));
      case 'Variant': return new Rec(e.variant, e.values.map((x) => this.#eval(x, env, f)));
      case 'CallFn': return this.#invoke(e.fn, e.args.map((x) => this.#eval(x, env, f)));
      case 'CallStd': {
        const args = e.args.map((x) => this.#eval(x, env, f));
        const listType = e.args[0]?.type;
        return applyStd(e.name, args, {
          same: (a, b, t) => this.same(a, b, t),
          elemType: listType && typeof listType === 'object' ? listType.list : undefined,
          tx: f.tx,
        });
      }
      case 'Prelude': return this.#prelude(e.op, e.args.map((x) => this.#eval(x, env, f)));
      case 'TimePred': {
        const t = this.#eval(e.threshold, env, f);
        return e.after ? f.tx.validFrom >= t : f.tx.validTo <= t;
      }
      case 'ListLit': {
        const items = e.items.map((x) => this.#eval(x, env, f));
        if (e.rest) items.push(...this.#eval(e.rest, env, f));
        return Object.freeze(items);
      }
      case 'Neg': return -this.#eval(e.operand, env, f);
      case 'Not': return this.#eval(e.operand, env, f) !== true;
      case 'Arith': return arith(e.op, this.#eval(e.left, env, f), this.#eval(e.right, env, f));
      case 'Compare': return compare(e.op, this.#eval(e.left, env, f), this.#eval(e.right, env, f));
      case 'Equals': return this.same(this.#eval(e.left, env, f), this.#eval(e.right, env, f), e.operand) !== e.negate;
      case 'Logic':
        // §2.1: the right operand only when the left does not decide.
        if (e.op === '&&') return this.#eval(e.left, env, f) === true ? this.#eval(e.right, env, f) : false;
        if (e.op === '||') return this.#eval(e.left, env, f) === true ? true : this.#eval(e.right, env, f);
        throw new Error(`unknown operator ${e.op}`);
      case 'Cond': return this.#eval(e.cond, env, f) === true ? this.#eval(e.then, env, f) : this.#eval(e.otherwise, env, f);
      case 'IfValue': return this.#block(this.#branch(e.branches, e.otherwise, env, f), env, f);
      case 'BlockValue': return this.#block(e.block, env, f);
      case 'Coalesce': {
        const o = this.#eval(e.option, env, f);
        return o instanceof Some ? o.value : this.#eval(e.default, env, f);
      }
      case 'Match': {
        const v = this.#eval(e.scrutinee, env, f);
        const arm = this.#arm(e.arms, v);
        return this.#block(arm.body, this.#bindArm(arm, v, env), f);
      }
      case 'ListProp': return listProp(e.op, this.#eval(e.list, env, f));
      case 'Concat': return Object.freeze([...this.#eval(e.left, env, f), ...this.#eval(e.right, env, f)]);
      case 'Contains': {
        const xs = this.#eval(e.list, env, f), v = this.#eval(e.value, env, f);
        for (const x of xs) if (this.same(x, v, e.elem)) return true;
        return false;
      }
      case 'Hof': return this.#hof(e, env, f);
      case 'Fold': {
        const xs = this.#eval(e.list, env, f);
        let acc = this.#eval(e.init, env, f);
        for (const x of this.#seq(xs)) acc = this.#block(e.body, bind(bind(env, e.acc, acc), e.x, x), f);
        return acc;
      }
      case 'Range': {
        const from = this.#eval(e.from, env, f);
        return new Range(from, this.#eval(e.to, env, f));
      }
      case 'ListArg': {
        const xs = this.#eval(e.list, env, f), n = this.#eval(e.arg, env, f);
        if (n < 0n) throw new Refused(`${e.op}: a negative count`, 'domain', `List.${e.op}`);
        const k = n > BigInt(xs.length) ? xs.length : Number(n);
        return Object.freeze(e.op === 'take' ? xs.slice(0, k) : xs.slice(k));
      }
      case 'MapEmpty': return MapV.EMPTY;
      case 'MapOp': return this.#mapOp(e, env, f);
      case 'MapHof': {
        const m = this.#eval(e.map, env, f);
        const any = e.kind === 'any';
        for (let i = 0; i < m.keys.length; i++) {
          const hit = this.#block(e.body, bind(bind(env, e.k, m.keys[i]), e.v, m.values[i]), f);
          if (hit === any) return any;
        }
        return !any;
      }
      case 'MapFold': {
        const m = this.#eval(e.map, env, f);
        let acc = this.#eval(e.init, env, f);
        for (let i = 0; i < m.keys.length; i++) acc = this.#block(e.body, bind(bind(bind(env, e.acc, acc), e.k, m.keys[i]), e.v, m.values[i]), f);
        return acc;
      }
      case 'BigEmpty': return BigMapV.EMPTY;
      case 'BigOp': return this.#bigOp(e, env, f);
      case 'FundsRef': return funds(f)[e.field];
      case 'ParamsRef': return this.#paramsField(e.field);
      case 'LedgerOp': return ledger(e.op, e.args.map((x) => this.#eval(x, env, f)));
      default:
        throw new Error(`unknown node ${e.node}`);
    }
  }

  /** A list or a range, front to back (§3.6). */
  #seq(v) {
    return v;
  }

  #hof(e, env, f) {
    const xs = this.#eval(e.list, env, f);
    const test = (x) => this.#block(e.body, bind(env, e.x, x), f);
    switch (e.kind) {
      case 'any':
        for (const x of xs) if (test(x)) return true;
        return false;
      case 'all':
        for (const x of xs) if (!test(x)) return false;
        return true;
      case 'find':
        for (const x of xs) if (test(x)) return some(x);
        return NONE;
      case 'filter': {
        const out = [];
        for (const x of xs) if (test(x)) out.push(x);
        return Object.freeze(out);
      }
      case 'map': {
        const out = [];
        for (const x of xs) out.push(test(x));
        return Object.freeze(out);
      }
      default:
        throw new Error(`unknown list method ${e.kind}`);
    }
  }

  /** §3.5: the first arm whose constructors include the value's. */
  #arm(arms, v) {
    const ctor = v === NONE ? 'None' : v instanceof Some ? 'Some' : v instanceof CredV ? (v.script ? 'ScriptCred' : 'KeyCred') : v.type;
    for (const a of arms) if (a.ctors.includes(ctor)) return a;
    throw new Error(`no arm for ${ctor}`);
  }

  #bindArm(arm, v, env) {
    arm.binds.forEach((l, i) => {
      env = bind(env, l, v instanceof Some ? v.value : v instanceof CredV ? v.hash : this.#field(v, arm.fields[i]));
    });
    return env;
  }

  #field(rec, name) {
    const shape = this.shapes.get(rec.type);
    const i = shape.names.indexOf(name);
    if (i < 0) throw new Error(`${rec.type} has no field ${name}`);
    return rec.fields[i];
  }

  /** §3.9: a fn sees only its parameters: no state, no transaction, no caller's locals. */
  #invoke(name, args) {
    const fn = this.fns.get(name);
    if (!fn) throw new Error(`no fn ${name}`);
    let env = null;
    fn.params.forEach((l, i) => (env = bind(env, l, args[i])));
    return this.#block(fn.body, env, { state: null, tx: null, payouts: [], sends: [], mint: ValueV.NONE, accepted: ValueV.NONE });
  }

  #prelude(op, args) {
    const [a, b] = args;
    switch (op) {
      case 'min': return a < b ? a : b;
      case 'max': return a > b ? a : b;
      case 'abs': return a < 0n ? -a : a;
      case 'asKey':
      case 'asHash':
        if (a.length !== 28) throw new Refused(`${op}: not a 28-byte hash`, 'domain', op);
        return a;
      default:
        throw new Error(`unknown prelude function ${op}`);
    }
  }

  // ------------------------------------------------------------------ maps (§3.7) and BigMaps (§3.8)

  #mapOp(e, env, f) {
    const m = this.#eval(e.map, env, f);
    const args = e.args.map((x) => this.#eval(x, env, f));
    switch (e.op) {
      case 'get': {
        const i = m.indexOf(args[0]);
        return i < 0 ? NONE : some(m.values[i]);
      }
      case 'has': return m.indexOf(args[0]) >= 0;
      case 'set':
        if (args[0].length > 64) throw new Refused('Map.set: a key has at most 64 bytes', 'domain', 'Map.set');
        return m.set(args[0], args[1]);
      case 'delete': return m.delete(args[0]);
      case 'size': return BigInt(m.keys.length);
      case 'isEmpty': return m.keys.length === 0;
      case 'keys': return m.keys;
      case 'values': return m.values;
      default:
        throw new Error(`unknown map operation ${e.op}`);
    }
  }

  #bigOp(e, env, f) {
    const m = this.#eval(e.map, env, f);
    const args = e.args.map((x) => this.#eval(x, env, f));
    const ints = e.map.type.bigmap[1] === 'Int';
    const k = args[0] && hex(args[0]);
    switch (e.op) {
      case 'at': return m.now.has(k) ? m.now.get(k) : 0n;
      case 'get': return m.now.has(k) ? some(m.now.get(k)) : NONE;
      case 'has': return m.now.has(k);
      case 'isEmpty': return m.before.size === 0 && !m.written;
      case 'delete': {
        if (args[0].length > 64) throw new Refused('BigMap.delete: a key has at most 64 bytes', 'domain', 'BigMap.delete');
        const now = new Map(m.now);
        now.delete(k);
        return new BigMapV(m.before, now, true);
      }
      case 'set': {
        if (args[0].length > 64) throw new Refused('BigMap.set: a key has at most 64 bytes', 'domain', 'BigMap.set');
        const v = args[1];
        const now = new Map(m.now);
        if (ints) {
          if (v < 0n) throw new Refused('BigMap.set: a value cannot be negative', 'domain', 'BigMap.set');
          // Stored as its unsigned big-endian bytes, at most 8,192 of them; checked here, also for a write that is only
          // read through and discarded (M1a review C4).
          if (unsignedBytes(v).length > 8192) throw new Refused('BigMap.set: a value needs more than 8,192 bytes (integerToByteString)', 'domain', 'BigMap.set');
          if (v === 0n) now.delete(k);   // 0 is absence
          else now.set(k, v);
        } else {
          now.set(k, v);
        }
        return new BigMapV(m.before, now, true);
      }
      default:
        throw new Error(`unknown BigMap operation ${e.op}`);
    }
  }

  // ------------------------------------------------------------------ equality (§3.4) and checked inputs (§4)

  /** Structural equality at a type. */
  same(a, b, t) {
    if (typeof t === 'string') {
      switch (t) {
        case 'Int':
        case 'Bool':
          return a === b;
        case 'Bytes':
        case 'Key':
        case 'Hash':
          return bytesEqual(a, b);
        // §3.4 (M2): values are canonical, so equal values have equal entries; credentials and addresses by structure.
        case 'Value':
          return a.equals(b);
        case 'Credential':
          return sameCred(a, b);
        case 'Address':
          return sameCred(a.payment, b.payment) && sameStake(a.stake, b.stake);
        default:
          throw new Error(`unknown type ${t}`);
      }
    }
    if ('list' in t) return a.length === b.length && a.every((x, i) => this.same(x, b[i], t.list));
    if ('option' in t) {
      if (a === NONE || b === NONE) return a === b;
      return this.same(a.value, b.value, t.option);
    }
    if ('record' in t) return this.#sameFields(a, b, t.record);
    if ('enum' in t) return a.type === b.type && this.#sameFields(a, b, a.type);
    if ('map' in t) {
      if (a.keys.length !== b.keys.length) return false;
      for (let i = 0; i < a.keys.length; i++) {
        if (!bytesEqual(a.keys[i], b.keys[i]) || !this.same(a.values[i], b.values[i], t.map[1])) return false;
      }
      return true;
    }
    throw new Error(`not comparable: ${JSON.stringify(t)}`);
  }

  #sameFields(a, b, type) {
    const { types } = this.shapes.get(type);
    return types.every((ft, i) => this.same(a.fields[i], b.fields[i], ft));
  }

  /** Whether `v` is well formed at type `t` (§4). */
  wellFormed(v, t) {
    if (typeof t === 'string') {
      if (t === 'Key' || t === 'Hash') return v.length === 28;
      // §4 (M2): every hash of a credential or an address (its payment credential, a staking hash) is 28 bytes.
      if (t === 'Credential') return v.hash.length === 28;
      if (t === 'Address') return v.payment.hash.length === 28 && (v.stake === NONE || !(v.stake.value instanceof StakeHash) || v.stake.value.credential.hash.length === 28);
      return true;
    }
    if ('list' in t) return v.every((x) => this.wellFormed(x, t.list));
    if ('option' in t) return v === NONE || this.wellFormed(v.value, t.option);
    if ('record' in t) return this.#fieldsWellFormed(v, t.record);
    if ('enum' in t) return this.#fieldsWellFormed(v, v.type);
    if ('map' in t) {
      for (let i = 0; i < v.keys.length; i++) {
        const k = v.keys[i];
        if (k.length > 64 || !this.wellFormed(k, t.map[0])) return false;
        if (i > 0 && keyCompare(v.keys[i - 1], k) >= 0) return false;   // strictly ascending: ordered and unique
        if (!this.wellFormed(v.values[i], t.map[1])) return false;
      }
      return true;
    }
    if ('bigmap' in t) return true;
    throw new Error(`unknown type ${JSON.stringify(t)}`);
  }

  #fieldsWellFormed(v, type) {
    const { types } = this.shapes.get(type);
    return types.every((ft, i) => this.wellFormed(v.fields[i], ft));
  }
}

// ------------------------------------------------------------------ values, credentials, addresses (M2, §1.3, §5)

function funds(f) {
  if (!f.tx || !f.tx.funds) throw new Error('this call reads funds: its transaction needs them');
  return f.tx.funds;
}

/** ADR-0009 D2: keys strictly ascending (so distinct), each 28 bytes, and 1 <= threshold <= their number. */
function wellFormedQuorum(keys, threshold) {
  for (let i = 0; i < keys.length; i++) {
    if (keys[i].length !== 28) return false;
    if (i > 0 && bytesCompare(keys[i - 1], keys[i]) >= 0) return false;
  }
  return threshold >= 1n && threshold <= BigInt(keys.length);
}

/** Whether a block holds an accept statement, in any arm or loop. */
function accepts(b) {
  if (!b) return false;
  return b.stmts.some((s) => s.stmt === 'Accept'
    || (s.stmt === 'If' && (s.branches.some((br) => accepts(br.then)) || accepts(s.otherwise)))
    || (s.stmt === 'For' && accepts(s.body))
    || (s.stmt === 'Match' && s.arms.some((a) => accepts(a.body))));
}

/** §5.1, §5.3: every quantity positive, no reserved name under the own policy, and, accepting, no lovelace. */
function sendable(v, policy, accepting) {
  const own = hex(policy);
  for (const [p, n, q] of v.entries) {
    if (accepting && p === '') return false;
    if (q <= 0n) return false;
    if (p === own && reserved(fromHexBytes(n))) return false;
  }
  return true;
}

function fromHexBytes(s) {
  const out = new Uint8Array(s.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(s.slice(2 * i, 2 * i + 2), 16);
  return out;
}

function inRangeValue(v, op) {
  if (!v.quantities().every(inRange)) throw new Refused(`${op}: a quantity out of the 128-bit range`, 'domain', op);
  return v;
}

const EMPTY = new Uint8Array(0);

/** §1.3: the operations on values, credentials and addresses, each failing exactly outside its domain. */
function ledger(op, a) {
  switch (op) {
    case 'none': return ValueV.NONE;
    case 'lovelace':
      if (a[0] !== 0n && !inRange(a[0])) throw new Refused('Value.lovelace: a quantity out of the 128-bit range', 'domain', 'Value.lovelace');
      return ValueV.of(EMPTY, EMPTY, a[0]);
    case 'asset':
      if (a[2] !== 0n && (a[1].length > 32 || !inRange(a[2]))) {
        throw new Refused('Value.asset: a token name over 32 bytes, or a quantity out of the 128-bit range', 'domain', 'Value.asset');
      }
      return ValueV.of(a[0], a[1], a[2]);
    case 'add': return inRangeValue(a[0].plus(a[1], 1n), 'Value +');
    case 'sub':
      // a - b is a + (-1)·b: every negated quantity of b is in range too.
      if (!a[1].quantities().every((q) => inRange(-q))) throw new Refused('Value -: a quantity out of the 128-bit range', 'domain', 'Value -');
      return inRangeValue(a[0].plus(a[1], -1n), 'Value -');
    case 'lovelaceOf': return a[0].quantity(EMPTY, EMPTY);
    case 'of': return a[0].quantity(a[1], a[2]);
    case 'covers': {
      if (a[0].quantities().some((q) => q < 0n) || a[1].quantities().some((q) => q < 0n)) {
        throw new Refused('covers: a negative quantity', 'domain', 'covers');
      }
      return a[1].entries.every(([p, n, q]) => a[0].quantity(fromHexBytes(p), fromHexBytes(n)) >= q);
    }
    case 'keyCred': return new CredV(false, a[0]);
    case 'scriptCred': return new CredV(true, a[0]);
    case 'wallet': return new AddrV(new CredV(false, a[0]), NONE);
    case 'script': return new AddrV(new CredV(true, a[0]), NONE);
    case 'walletStaked': return new AddrV(new CredV(false, a[0]), some(new StakeHash(new CredV(false, a[1]))));
    case 'scriptStaked': return new AddrV(new CredV(true, a[0]), some(new StakeHash(new CredV(false, a[1]))));
    case 'payment': return a[0].payment;
    default: throw new Error(`unknown ledger operation ${op}`);
  }
}

function sameCred(a, b) {
  return a.script === b.script && bytesEqual(a.hash, b.hash);
}

function sameStake(a, b) {
  if (a === NONE || b === NONE) return a === b;
  const x = a.value, y = b.value;
  if (x instanceof StakeHash) return y instanceof StakeHash && sameCred(x.credential, y.credential);
  return y instanceof StakePtr && x.slot === y.slot && x.txIndex === y.txIndex && x.certIndex === y.certIndex;
}

// ------------------------------------------------------------------ operations

/** §3.2: + - * exact; / and % truncate toward zero (BigInt's own rule); a zero divisor fails. */
function arith(op, a, b) {
  switch (op) {
    case '+': return a + b;
    case '-': return a - b;
    case '*': return a * b;
    case '/':
    case '%':
      if (b === 0n) throw new Refused('division by zero', 'domain', op);
      return op === '/' ? a / b : a % b;
    default:
      throw new Error(`unknown operator ${op}`);
  }
}

/** §3.2, §3.3: integers by value; byte strings lexicographically, unsigned, a proper prefix first. */
function compare(op, a, b) {
  const c = typeof a === 'bigint' ? (a < b ? -1 : a > b ? 1 : 0) : bytesCompare(a, b);
  switch (op) {
    case '<': return c < 0;
    case '<=': return c <= 0;
    case '>': return c > 0;
    case '>=': return c >= 0;
    default: throw new Error(`unknown comparison ${op}`);
  }
}

function listProp(op, xs) {
  switch (op) {
    case 'length': return BigInt(xs.length);
    case 'isEmpty': return xs.length === 0;
    case 'head': return xs.length === 0 ? NONE : some(xs[0]);
    case 'sum': return xs.reduce((s, x) => s + x, 0n);
    case 'reverse': return Object.freeze([...xs].reverse());
    default: throw new Error(`unknown list property ${op}`);
  }
}

function fromHexLit(s) {
  const out = new Uint8Array(s.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(s.slice(2 * i, 2 * i + 2), 16);
  return out;
}

/** §2.4: a call deeper than the JavaScript stack gets no answer (a Limit), never a refusal: the chain may accept it. */
function depthGuard(run) {
  try {
    return run();
  } catch (e) {
    if (e instanceof RangeError && /call stack/i.test(e.message)) throw new Limit('evaluation too deep for this interpreter\'s stack (no answer, §2.4)');
    throw e;
  }
}
