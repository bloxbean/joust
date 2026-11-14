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

// #98 review F1: every effect statement, not only a pay, is refused under an expression or in a fn, by both readers.
const lit = (value, type) => ({ node: 'Lit', value, type, taint: 0, at });
const sendStmt = () => ({ stmt: 'Send', to: { node: 'LedgerOp', op: 'wallet', args: [lit('0a'.repeat(28), 'Key')], type: 'Address', taint: 0, at },
  value: { node: 'LedgerOp', op: 'none', args: [], type: 'Value', taint: 0, at }, at });
// Coins' give with its send moved into a value block (the body's result), its audience dropped.
{
  const p = fixture('Coins');
  const g = p.methods.find((m) => m.name === 'give');
  const send = g.body.stmts.find((s) => s.stmt === 'Send');
  g.body.stmts = g.body.stmts.filter((s) => s !== send);
  g.body.result = { node: 'BlockValue', block: { stmts: [send], result: g.body.result, at }, type: g.body.result.type, taint: 0, at };
  g.mods = [];
  out('send-in-value-block', p);
}
// A fn that sends (Coins' total).
{
  const p = fixture('Coins');
  p.fns.find((x) => x.name === 'total').body.stmts.unshift(sendStmt());
  out('send-in-fn', p);
}
// A fn that mints (Coins' tokens).
{
  const p = fixture('Coins');
  p.fns.find((x) => x.name === 'tokens').body.stmts.unshift({ stmt: 'Mint', burn: false, name: lit('01', 'Bytes'), qty: lit('1', 'Int'), at });
  out('mint-in-fn', p);
}
// Stall's sell with its deposit moved into a lambda (a Hof over [1]), so no statement block holds it.
{
  const p = fixture('Stall');
  const sell = p.methods.find((m) => m.name === 'sell');
  const dep = sell.body.stmts.find((s) => s.stmt === 'Deposit');
  sell.body.stmts = sell.body.stmts.filter((s) => s !== dep);
  const x = { id: 90001, name: 'zx', type: 'Int', taint: 0, at };
  const any = { node: 'Hof', kind: 'any', list: { node: 'ListLit', items: [lit('1', 'Int')], rest: null, type: { list: 'Int' }, taint: 0, at },
    x, body: { stmts: [dep], result: lit(true, 'Bool'), at }, type: 'Bool', taint: 0, at };
  sell.body.stmts.push({ stmt: 'Let', local: { id: 90002, name: 'zz', type: 'Bool', taint: 0, at }, value: any, at });
  out('deposit-in-lambda', p);
}
