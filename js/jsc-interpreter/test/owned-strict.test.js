// #101 review F2 (K2): the JavaScript IR reader is as strict as the checker on owned programs, on the same mutants of the
// bank port's IR as the Java reader's twin (julc-state-dsl's JscV2M3a1IrStrictTest). Each is a program the checker never
// produces; the reader refuses it with the reason.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { load } from '../src/index.js';

const BANK = readFileSync(new URL('./fixtures/Bank.ir.json', import.meta.url), 'utf8');

const method = (ir, name) => ir.methods.find((m) => m.name === name);
const local = (id, name, record) => ({ id, name, type: { record }, taint: 0, at: [1, 1] });

function reads(mutate) {
  const ir = JSON.parse(BANK);
  mutate(ir);
  load(JSON.stringify(ir));
}

function refused(mutate, fragment) {
  const ir = JSON.parse(BANK);
  mutate(ir);
  assert.throws(() => load(JSON.stringify(ir)), (e) => e.message.includes(fragment) || assert.fail(`'${e.message}' should mention '${fragment}'`));
}

test('the unmutated bank port loads', () => {
  load(BANK);
});

test('owned mutants are refused (#101 review F2)', () => {
  // From M3a2 (OWNED_STAGE 2) a payable owned destroy and native assets in an owned method are produced: they read.
  reads((ir) => method(ir, 'close').mods.unshift({ name: 'payable' }));
  refused((ir) => method(ir, 'deposit').mods.push({ name: 'receives' }), 'an owned method receives no deposits');
  refused((ir) => { method(ir, 'close').shared = local(900, 'bank', 'State'); }, 'has no escape hatch');
  refused((ir) => method(ir, 'close').mods.push({ name: 'before', field: 'saved' }), 'has no escape hatch');
  refused((ir) => { method(ir, 'withdraw').into = local(901, 'dest', 'State'); }, 'moves (into, admits) are not produced yet');
  refused((ir) => method(ir, 'setPaused').mods.push({ name: 'admits', field: 'Account' }), 'moves (into, admits) are not produced yet');
  refused((ir) => { method(ir, 'deposit').shared = local(902, 'bank', 'Account'); }, 'the shared state is a State');
  reads((ir) => {
    const held = { node: 'FundsRef', field: 'held', type: 'Value', taint: 0, at: [1, 1] };
    const cond = { node: 'LedgerOp', op: 'covers', args: [held, held], type: 'Bool', taint: 0, at: [1, 1] };
    method(ir, 'deposit').body.stmts.unshift({ stmt: 'Require', cond, message: 'x', at: [1, 1] });
  });
  refused((ir) => { ir.methods = ir.methods.filter((m) => m.name !== 'open'); }, 'has no owned init');
});

// #101 round-r2 review C3, C4, C5, as the Java reader's twin (reviewRound2_mutants_areRefused).
// A copy of a method with its locals renumbered (ids, references and checked inputs), so it can sit beside the original.
const renumber = (o, by) => {
  if (Array.isArray(o)) return o.map((v) => renumber(v, by));
  if (o === null || typeof o !== 'object') return o;
  const c = {};
  for (const [k, v] of Object.entries(o)) {
    if (k === 'id' && typeof v === 'number') c[k] = v + by;
    else if (k === 'local' && o.node === 'LocalRef' && typeof v === 'number') c[k] = v + by;
    else if (k === 'checked' && Array.isArray(v)) c[k] = v.map((x) => x + by);
    else c[k] = renumber(v, by);
  }
  return c;
};
const replaceBalance = (o) => {
  if (Array.isArray(o)) { o.forEach(replaceBalance); return; }
  if (o === null || typeof o !== 'object') return;
  for (const [k, v] of Object.entries(o)) {
    if (v && v.node === 'CtxRef' && v.name === 'balance') o[k] = { node: 'Lit', value: '1', type: 'Int', taint: 0, at: v.at };
    else replaceBalance(v);
  }
};

test('round-r2 mutants (#101 review C3, C4, C5)', () => {
  // M3a2's forms (refused at stage 1) are produced from M3a2: a second owned type, with its own owned init and escape
  // hatch, and a BigMap in an owned type read.
  reads((ir) => {
    ir.records.push({ ...structuredClone(ir.records[0]), name: 'Account2' });
    ir.owned = ['Account', 'Account2'];
    for (const n of ['open', 'close']) ir.methods.push({ ...renumber(method(ir, n), 1000), name: `${n}2`, on: 'Account2' });
  });
  reads((ir) => {
    const map = { bigmap: ['Key', 'Int'] };
    ir.records[0].fields.push({ name: 'entries', type: map });
    const grow = (o) => {   // each New Account gains an empty map
      if (Array.isArray(o)) { o.forEach(grow); return; }
      if (o === null || typeof o !== 'object') return;
      if (o.node === 'New' && o.record === 'Account') o.values.push({ node: 'BigEmpty', type: map, taint: 0, at: o.at });
      Object.values(o).forEach(grow);
    };
    grow(ir.methods);
  });
  refused((ir) => replaceBalance(method(ir, 'close').body), "it is its type's escape hatch but never reads balance (JSC1216)");
  for (const k of ['into', 'arriving', 'shared', 'on', 'reads']) {
    refused((ir) => { method(ir, 'withdraw')[k] = null; }, `"${k}" is null: an absent field is not written`);
  }
});
