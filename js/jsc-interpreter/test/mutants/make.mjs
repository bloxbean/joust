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

// M3b (ADR-0016 D1, K2): keyed programs outside the checker's shapes, which both readers refuse (keyed-strict.test.js,
// JscV2M3bIrStrictTest), each with the fragment both readers' messages share (keyed-mutants.json).
const keyedMutants = [];
const keyed = (n, base, edit, message) => {
  const p = fixture(base);
  const m = (name) => p.methods.find((x) => x.name === name);
  edit(p, m);
  out(`keyed-${n}`, p);
  keyedMutants.push({ file: `keyed-${n}.json`, message });
};
const ballot = (p) => p.keyed.find((k) => k.type === 'Ballot');
const entryK = (p) => p.keyed.find((k) => k.type === 'Entry');
keyed('retire-open', 'Registry', (p, m) => { m('leave').mods[0] = { name: 'open' }; }, 'a retiring exit is signed by its owner');
keyed('retire-payable', 'Registry', (p, m) => { m('leave').mods.push({ name: 'payable' }); }, 'needs nobody else');
keyed('retire-after', 'Registry', (p, m) => { m('leave').mods.push({ name: 'after', field: 'choice' }); }, 'needs nobody else');
keyed('retire-quorum', 'Registry', (p, m) => { m('retire').mods.push({ name: 'quorum', keys: 'guardians', threshold: 'needed' }); }, 'needs nobody else');
keyed('action-open', 'Registry', (p, m) => { m('change').mods = [{ name: 'open' }]; }, 'a keyed action names its audience');
keyed('no-hatch', 'Registry', (p, m) => { m('leave').mods = m('leave').mods.filter((x) => x.name !== 'retire'); }, 'Ballot has no retiring exit');
keyed('max-key-65', 'Registry', (p) => { entryK(p).maxKeyBytes = 65; }, 'maxKeyBytes is 1 to 64');
keyed('max-key-0', 'Registry', (p) => { entryK(p).maxKeyBytes = 0; }, 'maxKeyBytes is 1 to 64');
keyed('signed-27', 'Registry', (p) => { ballot(p).maxKeyBytes = 27; }, 'a signed key is a 28-byte key hash');
keyed('cooldown-59999', 'Registry', (p) => { entryK(p).linkCooldownMs = 59999; }, 'linkCooldownMs is 0 or 60000 to 3600000');
keyed('window-19999', 'Registry', (p) => { entryK(p).linkWindowMs = 19999; }, 'linkWindowMs is 20000 to min(900000, maxValidityMs)');
keyed('window-900001', 'Registry', (p) => { entryK(p).linkWindowMs = 900001; }, 'linkWindowMs is 20000 to min(900000, maxValidityMs)');
keyed('type-unknown', 'Registry', (p) => { ballot(p).type = 'Nope'; }, 'keyed type Nope is not one owned type');
keyed('type-twice', 'Registry', (p) => { p.keyed.push({ ...ballot(p) }); }, 'keyed type Ballot is not one owned type');
keyed('key-int', 'Registry', (p) => { entryK(p).key = 'stake'; }, 'is not a Bytes, Key or Hash field');
keyed('key-missing', 'Registry', (p) => { entryK(p).key = 'nope'; }, 'is not a Bytes, Key or Hash field');
keyed('null', 'Registry', (p) => { p.keyed = null; }, 'keyed');
keyed('empty', 'Registry', (p) => { p.keyed = []; }, 'keyed');
keyed('extra-field', 'Registry', (p) => { ballot(p).extra = 1; }, 'extra');
keyed('no-signed', 'Registry', (p) => { delete ballot(p).signed; }, 'signed');
keyed('signed-string', 'Registry', (p) => { ballot(p).signed = 'true'; }, 'signed is a boolean');
keyed('key-time-field', 'Registry', (p) => { p.timeFields.push('Entry.name'); p.timeFields.sort(); }, 'its key is a time field');
keyed('max-datum-2049', 'Registry', (p) => { p.options.find((o) => o.name === 'maxDatumBytes').value = '2049'; }, 'maxDatumBytes is at most 2048');
keyed('batched', 'Registry', (p, m) => { m('setOpen').mods = [{ name: 'batched' }]; m('setOpen').usesCtx = true; }, 'a keyed contract has no batched action');
keyed('admits', 'Registry', (p, m) => { m('setOpen').mods.push({ name: 'admits', field: 'Ballot' }); m('setOpen').usesCtx = true; }, 'a keyed object does not move (admits Ballot)');
keyed('send', 'Registry', (p, m) => { m('withdraw').body.stmts.unshift(sendStmt()); }, 'a keyed contract handles no native assets');
keyed('retire-plain', 'Bank', (p, m) => { m('close').mods.push({ name: 'retire' }); }, 'only a keyed type');
keyed('retire-on-action', 'Registry', (p, m) => { m('setOpen').mods.push({ name: 'retire' }); m('setOpen').usesCtx = true; }, 'only an owned destroy retires');
keyed('retire-field', 'Registry', (p, m) => { m('leave').mods[1] = { name: 'retire', field: 'voter' }; }, 'field');
writeFileSync(new URL('./keyed-mutants.json', import.meta.url), JSON.stringify(keyedMutants, null, 1) + '\n');

// M3b (K32, A28): a keyed action that changes its key, in the IR (the checker refuses it, JSC1248): both readers accept
// it, and both interpreters refuse the call (keyed:fixed), as the validator refuses the transaction.
{
  const p = fixture('Registry');
  const c = p.methods.find((x) => x.name === 'change');
  // change returns New Ballot(state.voter, pick): the voter's key replaced by another key.
  c.body.result.values[0] = lit('b0'.repeat(28), 'Key');
  out('keyed-key-changed', p);
}
