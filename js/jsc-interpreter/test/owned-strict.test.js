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
