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
  // From M3a3 (OWNED_STAGE 3) moves are produced: a move without its admits, or the reverse, is JSC1214's.
  refused((ir) => { method(ir, 'withdraw').into = local(901, 'dest', 'State'); }, 'needs an admits action for it');
  refused((ir) => { method(ir, 'deposit').shared = local(902, 'bank', 'Account'); }, 'the shared state is a State');
  reads((ir) => {
    const held = { node: 'FundsRef', field: 'held', type: 'Value', taint: 0, at: [1, 1] };
    const cond = { node: 'LedgerOp', op: 'covers', args: [held, held], type: 'Bool', taint: 0, at: [1, 1] };
    method(ir, 'deposit').body.stmts.unshift({ stmt: 'Require', cond, message: 'x', at: [1, 1] });
  });
  refused((ir) => { ir.methods = ir.methods.filter((m) => m.name !== 'open'); }, 'has no owned init');
});

test('a move and its admits come together (M3a3, JSC1214)', () => {
  const GUILD = readFileSync(new URL('./fixtures/Guild.ir.json', import.meta.url), 'utf8');
  load(GUILD);
  const refusedIn = (src, mutate, fragment) => {
    const ir = JSON.parse(src);
    mutate(ir);
    assert.throws(() => load(JSON.stringify(ir)), (e) => e.message.includes(fragment) || assert.fail(`'${e.message}' should mention '${fragment}'`));
  };
  // admit Hero with no move of a Hero left (transfer without its into).
  refusedIn(GUILD, (ir) => { const t = method(ir, 'transfer'); delete t.into; }, 'but no owned action moves');
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

// #107 round-r2 review C5, F6, F7, as the Java reader's twin (stage3_admitsOnlyTheCheckersMoveShapes): the shapes stage 3
// admits are the checker's.
test('stage 3 admits only the checker\'s move shapes (#107 round-r2 C5, F6, F7)', () => {
  const FERRY = readFileSync(new URL('./fixtures/Ferry.ir.json', import.meta.url), 'utf8');
  const ARMORY = readFileSync(new URL('./fixtures/Armory.ir.json', import.meta.url), 'utf8');
  load(FERRY);
  load(ARMORY);
  const refusedIn = (src, mutate, fragment) => {
    const ir = JSON.parse(src);
    mutate(ir);
    assert.throws(() => load(JSON.stringify(ir)), (e) => e.message.includes(fragment) || assert.fail(`'${e.message}' should mention '${fragment}'`));
  };
  const passKeepsAMap = (ir) => {
    const map = { bigmap: ['Bytes', 'Int'] };
    ir.records.find((r) => r.name === 'Pass').fields.push({ name: 'm', type: map });
    const grow = (o) => {
      if (Array.isArray(o)) { o.forEach(grow); return; }
      if (o === null || typeof o !== 'object') return;
      if (o.node === 'New' && o.record === 'Pass') o.values.push({ node: 'BigEmpty', type: map, taint: 0, at: o.at });
      Object.values(o).forEach(grow);
    };
    grow(ir.methods);
    const arriving = method(ir, 'board').arriving;
    if (arriving.fieldTaints) arriving.fieldTaints.m = 0;
  };
  const id = 'an admits action\'s first parameter is the arriving object\'s id, a Bytes (JSC1212)';
  refusedIn(FERRY, (ir) => { method(ir, 'board').params = []; method(ir, 'board').checked = []; }, id);
  refusedIn(FERRY, (ir) => { method(ir, 'board').params[0].type = 'Int'; }, id);
  refusedIn(FERRY, (ir) => { method(ir, 'board').mods.push({ name: 'batched' }); }, 'an admits action cannot be batched (JSC1212)');
  refusedIn(FERRY, (ir) => { method(ir, 'lift').into.type = { record: 'Pass' }; }, 'a move\'s destination is a State');
  refusedIn(FERRY, passKeepsAMap, 'Pass keeps a BigMap, so it cannot move yet (JSC1212)');
  refusedIn(FERRY, (ir) => {
    passKeepsAMap(ir);
    const board = method(ir, 'board');
    ir.methods = [board, ...ir.methods.filter((m) => m !== board)];
  }, 'the arriving Pass keeps a BigMap, so an admits action cannot take it as a parameter (JSC1212)');
  refusedIn(ARMORY, (ir) => {
    ir.invariants = [{ name: 'small', on: 'Vault', body: { stmts: [], result: { node: 'Lit', value: true, type: 'Bool', taint: 0, at: [1, 1] }, at: [1, 1] }, at: [1, 1] }];
  }, 'the owned type Vault keeps a BigMap, so it has no invariants (JSC1213)');
});
