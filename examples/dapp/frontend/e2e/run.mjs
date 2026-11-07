// End-to-end on Yaci DevKit, the way the page runs (ADR-0013): the generated clients, the prepare service, the
// page's verifier and a CIP-30 development wallet. Run by DappDevnetTest with:
//   SERVICE (the dApp server), STORE (Yaci Store API), DEPLOYMENT (JSON), MNEMONIC and ADDRESS (the wallet).

import CSL from '@emurgo/cardano-serialization-lib-nodejs';
import { blockfrostChain, httpService, VerificationError } from '@julclang/state-client';
import { devWallet } from '@julclang/state-client/devwallet';
import { Counter } from '../src/generated/Counter.js';
import { Bank } from '../src/generated/Bank.js';
import { tamperings } from '../src/tamper.js';

const env = process.env;
const deployment = JSON.parse(env.DEPLOYMENT);
const chain = blockfrostChain(env.STORE);
const service = httpService(env.SERVICE + '/api');
const ADA = 1_000_000n;

let signed = 0;
const raw = devWallet({ CSL, chain, mnemonic: env.MNEMONIC, index: 0, networkId: deployment.networkId });
const wallet = { ...raw, signTx: async (tx, partial) => { signed++; return raw.signTx(tx, partial); } };
if (raw.address !== env.ADDRESS) fail(`the dev wallet derives ${raw.address}, cardano-client-lib ${env.ADDRESS}`);

const reviews = [];
const opts = (name, svc = service) => ({ CSL, deployment: deployment.contracts[name], service: svc, chain, onReview: async (r) => { reviews.push(r); } });
const counter = new Counter(opts('Counter'));
const bank = new Bank(opts('Bank'));

function fail(msg) {
  console.error('FAIL ' + msg);
  process.exit(1);
}
function ok(msg) {
  console.log('ok ' + msg);
}
async function refuses(what, fn, pattern) {
  try {
    await fn();
  } catch (e) {
    if (pattern && !pattern.test(e.message)) fail(`${what}: wrong error ${e.message}`);
    ok(`${what}: refused (${e.message.slice(0, 120)})`);
    return e;
  }
  fail(`${what}: accepted`);
}

// ---- Counter: deploy from the wallet, increment, refused call, reset, close
const created = await counter.deploy(wallet, {});
const c = created.instance;
ok(`Counter deployed ${c.id} in ${created.txHash}`);
let s = await c.state();
if (s.count !== 0n || s.owner !== raw.keyHash) fail('initial counter state ' + JSON.stringify(s, (_, v) => (typeof v === 'bigint' ? v.toString() : v)));
await c.increment(wallet, { by: 3n });
s = await c.state();
if (s.count !== 3n) fail('count after +3 is ' + s.count);
ok('increment(3) -> 3; the review showed next count ' + reviews.at(-1).report.next.count);
const viewed = await service.view({ contract: counter.contract.description.preamble.title, instance: c.id, view: 'count' });
if (viewed.value !== '3') fail('the count view returned ' + JSON.stringify(viewed));
ok('view count() -> 3 (from the service)');
if (reviews.at(-1).report.next.count !== 3n) fail('the review showed another next state');
await refuses('increment(0) (the contract\'s Require)', () => c.increment(wallet, { by: 0n }), /positive/);

// ---- a malicious service: every tampered variant refused, the wallet never asked
for (const what of Object.keys(tamperings(CSL, (await service.prepare({
  contract: counter.contract.description.preamble.title, kind: 'action', method: 'increment', instance: c.id,
  args: { by: '1' }, pay: { lovelace: '0', assets: [] }, address: raw.address,
})).cbor, [raw.address]))) {
  const evil = {
    ...service,
    prepare: async (req) => {
      const p = await service.prepare(req);
      return { ...p, cbor: tamperings(CSL, p.cbor, [raw.address])[what] };
    },
  };
  const before = signed;
  const e = await refuses(`malicious service: ${what}`, () => new Counter(opts('Counter', evil)).at(c.id).increment(wallet, { by: 1n }));
  if (!(e instanceof VerificationError)) fail(`${what}: not refused by the verifier but by ${e.message}`);
  if (signed !== before) fail(`${what}: the wallet was asked to sign`);
}

await c.reset(wallet);
if ((await c.state()).count !== 0n) fail('reset');
ok('reset -> 0');
await c.close(wallet);
if ((await c.read()) !== null) fail('the counter is still on chain');
ok('close: the instance is gone and its deposit returned');

// ---- Bank: an account in the shared bank (owned object, payable), deposit, withdraw, close
const shared = bank.at(deployment.sharedBank);
const opened = await shared.open(wallet, {}, { lovelace: 20n * ADA });
const acct = opened.object;
ok(`Bank account ${acct.id} opened with 20 ADA`);
await acct.deposit(wallet, {}, { lovelace: 5n * ADA });
await acct.withdraw(wallet, { amount: 10n * ADA });
let a = await acct.read();
if (a.value.balance !== 15n * ADA) fail('balance ' + a.value.balance);
ok('deposit 5, withdraw 10 -> 15 ADA');
const mine = (await shared.objects()).filter((o) => o.value.owner === raw.keyHash);
if (!mine.some((o) => o.id === acct.id)) fail('objects() does not list the account');
// A payout to the wallet must not hide a theft of the wallet's own funds, nor be redirected to its key with a
// stranger's stake key (review r4).
for (const what of ['an extra output to an attacker', 'the payout at the wallet key with a stranger\'s stake key']) {
  const evil = {
    ...service,
    prepare: async (req) => {
      const p = await service.prepare(req);
      const v = tamperings(CSL, p.cbor, [raw.address])[what];
      if (!v) fail(`no ${what} variant for a withdrawal`);
      return { ...p, cbor: v };
    },
  };
  const before = signed;
  const e = await refuses(`malicious service on a withdrawal: ${what}`, () => new Bank(opts('Bank', evil)).at(deployment.sharedBank).account(acct.id).withdraw(wallet, { amount: 6n * ADA }));
  if (!(e instanceof VerificationError) || signed !== before) fail(`${what}: not refused unsigned`);
}
await refuses('withdraw above the bank\'s limit', () => acct.withdraw(wallet, { amount: 1000n * ADA }));

// A tampered deposit (payable owned action) is refused too.
const evilDeposit = {
  ...service,
  prepare: async (req) => {
    const p = await service.prepare(req);
    return { ...p, cbor: tamperings(CSL, p.cbor, [raw.address])['an extra output to an attacker'] };
  },
};
await refuses('malicious service on a deposit', () => new Bank(opts('Bank', evilDeposit)).at(deployment.sharedBank).account(acct.id).deposit(wallet, {}, { lovelace: 5n * ADA }), /refused/);

await acct.close(wallet);
if ((await acct.read()) !== null) fail('the account is still on chain');
ok('close: the account is gone and its balance paid out');

// ---- a bank of the wallet's own: it is the admin
const own = (await bank.deploy(wallet, { withdrawLimit: 30n * ADA })).instance;
await own.setPaused(wallet, { paused: true });
if ((await own.state()).paused !== true) fail('pause');
await refuses('open an account in a paused bank', () => own.open(wallet, {}, { lovelace: 5n * ADA }), /paused/i);
ok('own bank: admin pauses it; opening an account is refused');

console.log(`DONE signed=${signed} reviews=${reviews.length}`);
