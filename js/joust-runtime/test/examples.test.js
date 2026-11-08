// M3 on Yaci DevKit: the example contracts the described dispatcher already covers, through the npm path. Each is built
// from its Java source by the toolchain engine (as `joust build` does) and every call is prepared by the runtime engine,
// verified by @julclang/state-client and signed by a key wallet. Mirrors the examples' Java DevKit tests.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { generateMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english';
import CSL from '@emurgo/cardano-serialization-lib-nodejs';
import { joustRuntime, devkitClock, devkitTopUp, devWallet, JoustContract } from '../src/index.js';
import { build } from '../../joust/src/build.js';

const STORE = process.env.STORE ?? 'http://localhost:8080/api/v1';
const ADMIN = process.env.ADMIN ?? 'http://localhost:10000';
const ADA = 1_000_000n;
const EXAMPLES = new URL('../../../examples/', import.meta.url).pathname;

async function reachable() {
  try {
    return (await fetch(STORE + '/blocks/latest')).ok && (await fetch(ADMIN + '/local-cluster/api/admin/devnet')).ok;
  } catch {
    return false;
  }
}
const devkit = await reachable();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** The contracts of {@code examples/<name>}, built by the toolchain engine. */
async function built(name) {
  const out = mkdtempSync(join(tmpdir(), 'joust-ex-'));
  const r = await build({ root: join(EXAMPLES, name), src: 'src/main/java', out, log: () => {},
    keep: (f, text) => f.endsWith('.jsc') || /@StateContract|@OnchainLibrary/.test(text) });   // not the JVM apps
  assert.ok(r.ok, `${name} builds`);
  return JSON.parse(readFileSync(join(out, 'contracts.json'), 'utf8'));
}

async function setup(names) {
  const contracts = (await Promise.all(names.map(built))).flat();
  const rt = joustRuntime({ CSL, chainUrl: STORE, contracts, clock: await devkitClock(ADMIN), networkId: 0, ttlMs: 20_000 });
  const wallets = async (n, ada = 300) => {
    const ws = Array.from({ length: n }, () => devWallet({ CSL, chain: rt.chain, mnemonic: generateMnemonic(wordlist, 256) }));
    for (const w of ws) await devkitTopUp(w.address, ada, ADMIN);
    for (const w of ws) for (let i = 0; i < 30 && !(await rt.chain.utxosAt(w.address)).length; i++) await sleep(1000);
    return ws;
  };
  const contract = async (simple, payer) => {
    const c = contracts.find((x) => JSON.parse(x.description).preamble.title.endsWith('.' + simple));
    const d = JSON.parse(c.description);
    const deployment = await rt.ensureDeployment(d.preamble.title, payer);
    return new JoustContract({ CSL, description: d, deployment, service: rt.service, chain: rt.chain });
  };
  return { rt, wallets, contract };
}

/** Waits until the chain's clock (tip time) passes {@code ms}. */
async function untilChainTime(rt, clock, ms) {
  for (;;) {
    const slot = await rt.chain.tip();
    if (clock.zeroTimeMs + (slot - clock.zeroSlot) * clock.slotLengthMs > ms) return;
    await sleep(1000);
  }
}

const skip = devkit ? false : 'DevKit not reachable';

test('Vault: payable open and deposit, @ValidAfter withdraw and close', { skip, timeout: 600_000 }, async () => {
  const { rt, wallets, contract } = await setup(['vault']);
  const [owner, bob] = await wallets(2);
  const clock = await devkitClock(ADMIN);
  const vault = await contract('Vault', owner);
  const unlockAt = BigInt(Date.now() + 40_000);
  const v = vault.at((await vault.deploy(owner, { unlockAt }, { lovelace: 10n * ADA })).instance);
  await v.call(bob, 'deposit', {}, { lovelace: 5n * ADA });
  assert.equal((await v.state()).total, 15n * ADA);
  await assert.rejects(v.call(owner, 'withdraw', { amount: ADA }));                      // still locked
  await untilChainTime(rt, clock, Number(unlockAt) + 2_000);
  await assert.rejects(v.call(bob, 'withdraw', { amount: ADA }));                        // not the owner
  await v.call(owner, 'withdraw', { amount: 4n * ADA });
  assert.equal((await v.state()).total, 11n * ADA);
  await v.call(owner, 'close', {});
  assert.equal(await v.state(), null);
});

test('Crowdfund: backers (a sorted map in the state), deadline, claim', { skip, timeout: 600_000 }, async () => {
  const { rt, wallets, contract } = await setup(['crowdfund']);
  const [creator, a, b, c] = await wallets(4);
  const clock = await devkitClock(ADMIN);
  const cf = await contract('Crowdfund', creator);
  const deadline = BigInt(Date.now() + 60_000);
  const k = cf.at((await cf.deploy(creator, { goal: 40n * ADA, deadline, minContribution: 2n * ADA })).instance);
  for (const w of [a, b, c]) await k.call(w, 'contribute', {}, { lovelace: 10n * ADA });
  await k.call(a, 'contribute', {}, { lovelace: 10n * ADA });                              // a repeat backer
  const s = await k.state();
  assert.equal(s.raised, 40n * ADA);
  assert.equal(s.backers.length, 3);
  assert.equal(s.backers.find(([pkh]) => pkh === a.keyHash)[1], 20n * ADA);
  await assert.rejects(k.call(creator, 'claim', {}));                                       // still running
  await untilChainTime(rt, clock, Number(deadline) + 2_000);
  await assert.rejects(k.call(b, 'refund', {}));                                            // the goal was reached
  await k.call(creator, 'claim', {});
  assert.equal(await k.state(), null);
});

test('Escrow: released by the buyer; refunded by anyone after the deadline', { skip, timeout: 600_000 }, async () => {
  const { rt, wallets, contract } = await setup(['escrow']);
  const [buyer, seller, arbiter, stranger] = await wallets(4);
  const clock = await devkitClock(ADMIN);
  const escrow = await contract('Escrow', buyer);
  const price = 20n * ADA;
  const deal = escrow.at((await escrow.deploy(buyer, { seller: seller.keyHash, arbiter: arbiter.keyHash,
    deadline: BigInt(Date.now() + 3_600_000) }, { lovelace: price })).instance);
  await assert.rejects(deal.call(seller, 'release', {}));
  await deal.call(buyer, 'release', {});
  assert.equal(await deal.state(), null);
  const deadline = BigInt(Date.now() + 30_000);
  const deal2 = escrow.at((await escrow.deploy(buyer, { seller: seller.keyHash, arbiter: arbiter.keyHash, deadline },
    { lovelace: price })).instance);
  await assert.rejects(deal2.call(stranger, 'refund', {}));
  await untilChainTime(rt, clock, Number(deadline) + 2_000);
  await deal2.call(stranger, 'refund', {});
  assert.equal(await deal2.state(), null);
});

test('Oracle: posted by its publisher only', { skip, timeout: 600_000 }, async () => {
  const { wallets, contract } = await setup(['oracle']);
  const [publisher, other] = await wallets(2);
  const oracle = await contract('Oracle', publisher);
  const o = oracle.at((await oracle.deploy(publisher, { price: 3_000_000n })).instance);
  await o.call(publisher, 'post', { price: 5_000_000n });
  assert.equal((await o.state()).price, 5_000_000n);
  await assert.rejects(o.call(other, 'post', { price: 1n }));
  await o.call(publisher, 'close', {});
  assert.equal(await o.state(), null);
});
