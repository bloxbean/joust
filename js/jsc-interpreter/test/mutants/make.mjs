// Writes the shared IR mutants (#80 review r2: C4, S7, F7) that both readers must refuse: semantics.test.js and
// JscV2ModifierShapeTest read them. Run: node test/mutants/make.mjs (from js/jsc-interpreter).
import { readFileSync, writeFileSync } from 'node:fs';

const fixture = (n) => JSON.parse(readFileSync(new URL(`../fixtures/${n}.ir.json`, import.meta.url), 'utf8'));
const out = (n, p) => writeFileSync(new URL(`./${n}.json`, import.meta.url), JSON.stringify(p, null, 1) + '\n');
const at = [1, 1];

// Pool's withdraw with its pay moved into a value block (the body's result), its audience dropped, pays: false.
{
  const p = fixture('Pool');
  const w = p.methods.find((m) => m.name === 'withdraw');
  const pay = w.body.stmts.find((s) => s.stmt === 'Pay');
  w.body.stmts = w.body.stmts.filter((s) => s !== pay);
  w.body.result = { node: 'BlockValue', block: { stmts: [pay], result: w.body.result, at }, type: w.body.result.type, taint: 0, at };
  w.mods = [];
  w.pays = false;
  out('pay-in-value-block', p);
}
// The same pay one level further down: in a value if's branch inside the value block.
{
  const p = fixture('Pool');
  const w = p.methods.find((m) => m.name === 'withdraw');
  const pay = w.body.stmts.find((s) => s.stmt === 'Pay');
  w.body.stmts = w.body.stmts.filter((s) => s !== pay);
  const result = w.body.result;
  const yes = { node: 'Lit', value: true, type: 'Bool', taint: 0, at };
  w.body.result = { node: 'IfValue', branches: [{ cond: yes, then: { stmts: [pay], result, at }, at }],
    otherwise: { stmts: [], result, at }, type: result.type, taint: 0, at };
  w.mods = [];
  w.pays = false;
  out('pay-in-value-if', p);
}
// A fn that pays (Pledges' total).
{
  const p = fixture('Pledges');
  const f = p.fns.find((x) => x.name === 'total');
  f.body.stmts.unshift({ stmt: 'Pay', to: { node: 'Lit', value: '0a'.repeat(28), type: 'Key', taint: 0, at },
    amount: { node: 'Lit', value: '1', type: 'Int', taint: 0, at }, at });
  out('pay-in-fn', p);
}
// Counter2's destroy without its pay (JSC0409).
{
  const p = fixture('Counter2');
  const c = p.methods.find((m) => m.name === 'close');
  c.body.stmts = c.body.stmts.filter((s) => s.stmt !== 'Pay');
  c.pays = false;
  out('destroy-without-pay', p);
}
