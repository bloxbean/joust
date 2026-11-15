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
// (on Entry, whose release is an escape hatch of M3a's kind, so only the keyed rule refuses it)
keyed('retire-after', 'Registry', (p, m) => { m('retire').mods.push({ name: 'after', field: 'stake' }); }, 'needs nobody else');
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
// A move of a keyed type with its admit (M3a's JSC1214 satisfied): the keyed rule refuses the move.
keyed('move', 'Registry', (p, m) => {
  m('setOpen').mods.push({ name: 'admits', field: 'Ballot' });
  m('setOpen').usesCtx = true;
  m('change').into = { id: 99901, name: 'dest', type: { record: 'State' }, taint: 0, at };
  m('change').usesCtx = true;
}, 'a keyed object does not move');
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

// M3c (ADR-0010 D1, D2; ADR-0011 D4; K2, K39, K48): programs with script purposes or migration outside the checker's
// shapes, which both readers refuse (purpose-strict.test.js, JscV2M3cIrStrictTest), each with the fragment both
// readers' messages share (purpose-mutants.json): the code the checker gives the same program.
const purposeMutants = [];
const purpose = (n, base, edit, message) => {
  const p = fixture(base);
  const m = (name) => p.methods.find((x) => x.name === name);
  edit(p, m);
  out(`purpose-${n}`, p);
  purposeMutants.push({ file: `purpose-${n}.json`, message });
};
const Q = () => ({ name: 'quorum', keys: 'params.council', threshold: 'params.quorum' });
const stateField = (field, type) => ({ node: 'StateField', field, type, taint: 0, at });
const payStmt = () => ({ stmt: 'Pay', to: lit('0a'.repeat(28), 'Key'), amount: lit('1', 'Int'), at });
// A second method like m, its locals renumbered (a local id is declared once in a program).
const twin = (m, name) => {
  const renumber = (k, v) => (k === 'id' || k === 'local') && typeof v === 'number' ? v + 90000 : v;
  const t = JSON.parse(JSON.stringify(m), renumber);
  t.name = name;
  return t;
};
const readsValidTo = (m) => {
  m.usesCtx = true;
  m.body.result = { node: 'Compare', op: '>', left: { node: 'CtxRef', name: 'validTo', type: 'Int', taint: 0, at }, right: lit('0', 'Int'),
    type: 'Bool', taint: 0, at };
};
purpose('stakeself-2', 'StakedVault', (p) => { p.options[0].value = '2'; }, '(JSC1301)');
purpose('stakeself-twice', 'StakedVault', (p) => { p.options.push({ ...p.options[0] }); }, '(JSC1301)');
purpose('stakeself-no-certify', 'StakedVault', (p) => { p.methods = p.methods.filter((x) => x.kind !== 'certify'); }, '(JSC1302)');
purpose('stakeself-no-withdraw', 'StakedVault', (p) => { p.methods = p.methods.filter((x) => x.kind !== 'withdraw'); }, '(JSC1302)');
purpose('withdraw-twice', 'StakedVault', (p, m) => { p.methods.push(twin(m('collect'), 'collect2')); }, '(JSC1316)');
purpose('certify-twice', 'StakedVault', (p, m) => { p.methods.push(twin(m('certificate'), 'certificate2')); }, '(JSC1316)');
purpose('certify-voter', 'StakedVault', (p, m) => { m('certificate').params[0].type = 'Voter'; }, '(JSC1314)');
purpose('vote-two-params', 'StakedVault', (p, m) => { m('vote').params.pop(); }, '(JSC1314)');
purpose('withdraw-no-amount', 'StakedVault', (p, m) => { m('collect').params = []; m('collect').body = { stmts: [], result: null, at }; }, '(JSC1314)');
purpose('withdraw-result', 'StakedVault', (p, m) => { m('collect').result = 'Bool'; m('collect').body.result = lit(true, 'Bool'); }, '(JSC1340)');
purpose('certify-no-result', 'StakedVault', (p, m) => { m('certificate').result = null; m('certificate').body.result = null; }, '(JSC1341)');
purpose('certify-ctx', 'StakedVault', (p, m) => { readsValidTo(m('certificate')); }, '(JSC1315)');
purpose('propose-ctx', 'StakedVault', (p, m) => { readsValidTo(m('proposal')); }, '(JSC1315)');
// K39: a handler sees no instance: a state field (the checker's JSC1318).
purpose('certify-state', 'StakedVault', (p, m) => {
  m('certificate').body.result = { node: 'Compare', op: '>', left: stateField('deposits', 'Int'), right: lit('0', 'Int'), type: 'Bool', taint: 0, at };
}, '(JSC1318)');
purpose('withdraw-state', 'StakedVault', (p, m) => {
  const send = m('collect').body.stmts[0].branches[0].then.stmts[0];
  send.value.args[0] = stateField('deposits', 'Int');
}, '(JSC1318)');
purpose('withdraw-pay', 'StakedVault', (p, m) => { m('collect').body.stmts.push(payStmt()); m('collect').pays = true; }, '(JSC1319)');
purpose('propose-quorum', 'StakedVault', (p, m) => { m('proposal').mods = [Q()]; }, '(JSC1313)');
purpose('vote-open', 'StakedVault', (p, m) => { m('vote').mods = []; }, '(JSC1312)');
purpose('certify-open', 'StakedVault', (p, m) => { m('certificate').mods = []; }, '(JSC1312)');
purpose('state-txcert', 'StakedVault', (p) => { p.state.fields[0].type = 'TxCert'; }, '(JSC1321)');
purpose('params-ledgermap', 'StakedVault', (p) => { p.params.fields.push({ name: 'm', type: { ledgerMap: ['Credential', 'Int'] } }); }, '(JSC1321)');
purpose('action-vote-param', 'StakedVault', (p, m) => { m('deposit').params.push({ id: 90101, name: 'v', type: 'Vote', taint: 0, at }); }, '(JSC1321)');
purpose('withdraw-txcert-arg', 'StakedVault', (p, m) => { m('collect').params.push({ id: 90102, name: 'c', type: 'TxCert', taint: 0, at }); }, '(JSC1321)');
// Migration: the adopting side (CounterV2) and the migrating destroy (CounterV1).
purpose('migrate-from-missing', 'CounterV2', (p, m) => { m('migrate').from = 'nope'; }, '(JSC1335)');
purpose('migrate-from-int', 'CounterV2', (p, m) => { p.params.fields.push({ name: 'n', type: 'Int' }); m('migrate').from = 'n'; }, '(JSC1335)');
purpose('migrate-own-state', 'CounterV2', (p, m) => { m('migrate').old.type = { record: 'State' }; delete m('migrate').old.fieldTaints; }, '(JSC1336)');
purpose('migrate-big-maps', 'CounterV2', (p) => { p.contracts[0].bigMaps = true; }, '(JSC1334)');
purpose('migrate-twice', 'CounterV2', (p, m) => { p.methods.push(twin(m('migrate'), 'migrate2')); }, '(JSC1337)');
purpose('migrate-with-send', 'CounterV2', (p, m) => { m('setStep').body.stmts.push(sendStmt()); }, '(JSC1334)');
purpose('migrates-open', 'CounterV1', (p, m) => { m('upgrade').mods = m('upgrade').mods.filter((x) => x.name === 'migrates'); }, '(JSC1333)');
purpose('migrates-pay', 'CounterV1', (p, m) => { m('upgrade').body.stmts.push(payStmt()); m('upgrade').pays = true; }, '(JSC1332)');
purpose('migrates-int-field', 'CounterV1', (p, m) => { m('upgrade').mods.find((x) => x.name === 'migrates').field = 'count'; },
  'migrates to names a Bytes or Hash state field');
purpose('migrates-on-action', 'CounterV1', (p, m) => { m('setSuccessor').mods.push({ name: 'migrates', field: 'successor' }); },
  'only an instance destroy migrates');
purpose('migrates-with-send', 'CounterV1', (p, m) => { m('setSuccessor').body.stmts.push(sendStmt()); }, '(JSC1334)');
// Review C2 (r2): the ledger types' expression rules: equality on a ledger value, a set on a LedgerMap, an opaque bind.
const findNode = (o, pred) => {
  if (Array.isArray(o)) { for (const x of o) { const r = findNode(x, pred); if (r) return r; } return null; }
  if (!o || typeof o !== 'object') return null;
  if (pred(o)) return o;
  for (const v of Object.values(o)) { const r = findNode(v, pred); if (r) return r; }
  return null;
};
purpose('ledger-equality', 'StakedVault', (p, m) => {
  const cert = m('certificate').params[0];
  const ref = { node: 'LocalRef', local: cert.id, type: 'TxCert', taint: 0, at };
  m('certificate').body.result = { node: 'Equals', negate: false, left: ref, right: { ...ref }, operand: 'TxCert', type: 'Bool', taint: 0, at };
}, '(JSC1322)');
purpose('ledger-map-set', 'Guardrail', (p, m) => {
  const has = findNode(m('rail').body, (o) => o.node === 'MapOp' && o.op === 'has');
  const original = JSON.parse(JSON.stringify(has));
  const set = { node: 'MapOp', op: 'set', map: original.map, args: [original.args[0], lit('1', 'Int')], type: original.map.type, taint: 0, at };
  for (const k of Object.keys(has)) delete has[k];
  Object.assign(has, { node: 'MapOp', op: 'isEmpty', map: set, args: [], type: 'Bool', taint: 0, at });
}, '(JSC1324)');
purpose('opaque-bind', 'Guardrail', (p, m) => {
  const arm = findNode(m('rail').body, (o) => Array.isArray(o.ctors) && o.ctors[0] === 'ParameterChange');
  arm.fields.push('parameters');
  arm.binds.push({ id: 90201, name: 'parameters', type: 'Data', taint: 0, at });
}, '(JSC1320)');
// Review C3 (r2): funds in a handler or an init migrate (the processor's signatures take no Funds).
const fundsLet = (id) => ({ stmt: 'Let', local: { id, name: `zf${id}`, type: 'Hash', taint: 0, at },
  value: { node: 'FundsRef', field: 'policy', type: 'Hash', taint: 0, at }, at });
purpose('withdraw-funds', 'StakedVault', (p, m) => { m('collect').body.stmts.unshift(fundsLet(90301)); }, '(JSC1315)');
purpose('certify-funds', 'StakedVault', (p, m) => { m('certificate').body.stmts.unshift(fundsLet(90302)); }, '(JSC1315)');
purpose('vote-funds', 'StakedVault', (p, m) => { m('vote').body.stmts.unshift(fundsLet(90303)); }, '(JSC1315)');
purpose('propose-funds', 'StakedVault', (p, m) => { m('proposal').body.stmts.unshift(fundsLet(90304)); }, '(JSC1315)');
purpose('migrate-funds', 'CounterV2', (p, m) => { m('migrate').body.stmts.unshift(fundsLet(90305)); }, '(JSC1338)');
// Review F1, S3 (r2): stakeSelf beside owned types (Bank's).
purpose('stakeself-owned', 'Bank', (p) => { p.options.push({ name: 'stakeSelf', value: '1' }); }, '(JSC1305)');
writeFileSync(new URL('./purpose-mutants.json', import.meta.url), JSON.stringify(purposeMutants, null, 1) + '\n');
