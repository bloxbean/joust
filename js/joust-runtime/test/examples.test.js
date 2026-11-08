// M3 on Yaci DevKit: the example contracts the described dispatcher already covers, through the npm path. Each is built
// from its Java source by the toolchain engine (as `joust build` does) and every call is prepared by the runtime engine,
// verified by @julclang/state-client and signed by a key wallet. Mirrors the examples' Java DevKit tests.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { generateMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english';
import CSL from '@emurgo/cardano-serialization-lib-nodejs';
import { joustRuntime, devkitClock, devkitTopUp, devkitMint, devkitSweep, devWallet, JoustContract, runComposed } from '../src/index.js';
import { build } from '../../joust/src/build.js';
import { tamperings } from '../../../examples/dapp/frontend/src/tamper.js';

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

/**
 * JOUST_RECORD=<dir>: every engine-prepared call as an I3 fixture (ADR-0015), replayed on the JVM by
 * julc-state-runtime-wasm's EngineFixturesTest. Contracts are stored once, by the hash of their description.
 */
function recorder(group) {
  const dir = process.env.JOUST_RECORD;
  if (!dir) return undefined;
  let seq = 0;
  return (f) => {
    mkdirSync(join(dir, 'contracts'), { recursive: true });
    mkdirSync(join(dir, group), { recursive: true });
    const refs = f.contracts.map((d) => {
      const sha = createHash('sha256').update(d).digest('hex');
      const file = join(dir, 'contracts', sha + '.json');
      if (!existsSync(file)) writeFileSync(file, JSON.stringify(contractsByDescription.get(d)));
      return sha;
    });
    const name = `${String(++seq).padStart(3, '0')}-${f.call.method ?? f.call.kind}.json`;
    writeFileSync(join(dir, group, name), JSON.stringify({ ...f, contracts: refs }));
  };
}
const contractsByDescription = new Map();

async function setup(names) {
  const contracts = (await Promise.all(names.map(built))).flat();
  for (const c of contracts) contractsByDescription.set(c.description, c);
  const rt = joustRuntime({ CSL, chainUrl: STORE, contracts, clock: await devkitClock(ADMIN), networkId: 0, ttlMs: 20_000,
    record: recorder(names.join('+')) });
  const wallets = async (n, ada = 300) => {
    const ws = Array.from({ length: n }, () => devWallet({ CSL, chain: rt.chain, mnemonic: generateMnemonic(wordlist, 256) }));
    for (const w of ws) await devkitTopUp(w.address, ada, ADMIN, { CSL, chain: rt.chain });
    for (const w of ws) for (let i = 0; i < 30 && !(await rt.chain.utxosAt(w.address)).length; i++) await sleep(1000);
    return ws;
  };
  const contract = async (simple, payer, params) => {
    const c = contracts.find((x) => JSON.parse(x.description).preamble.title.endsWith('.' + simple));
    const d = JSON.parse(c.description);
    const deployment = await rt.ensureDeployment(d.preamble.title, payer, params);
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

test('Oracle and Shop: @SignedBy; @Params naming the oracle; @Reads of its price', { skip, timeout: 600_000 }, async () => {
  const { wallets, contract } = await setup(['oracle']);
  const [publisher, other, shopOwner, buyer] = await wallets(4);
  const oracle = await contract('Oracle', publisher);
  const o = oracle.at((await oracle.deploy(publisher, { price: 3_000_000n })).instance);
  await o.call(publisher, 'post', { price: 3_000_000n });
  assert.equal((await o.state()).price, 3_000_000n);
  await assert.rejects(o.call(other, 'post', { price: 1n }));
  // The shop's script carries its oracle (@Params); buy reads the oracle's state as a reference input (@Reads).
  const shop = await contract('Shop', shopOwner, { oracle: { policy: oracle.deployment.policy, instance: o.id }, maxAgeMs: 600_000n });
  const sh = shop.at((await shop.deploy(shopOwner, {})).instance);
  await sh.call(buyer, 'buy', { qty: 2n }, { lovelace: 6_000_000n });
  assert.equal((await sh.state()).sold, 2n);
  await assert.rejects(sh.call(buyer, 'buy', { qty: 1n }, { lovelace: 2_000_000n }), /pay exactly/);
  await sh.call(shopOwner, 'close', {});
  await o.call(publisher, 'close', {});
  assert.equal(await o.state(), null);
});

test('Token: MerkleMap balances, read discovery, the map verifier', { skip, timeout: 600_000 }, async () => {
  const { rt, wallets, contract } = await setup(['token']);
  const [admin, ann, ben] = await wallets(3);
  const token = await contract('Token', admin);
  assert.ok(token.deployment.mapVerifier?.referenceScripts?.length, 'the map verifier is pinned');
  const t = token.at((await token.deploy(admin, {})).instance);
  await t.call(admin, 'mint', { to: ann.keyHash, amount: 1000n });
  await t.call(admin, 'mint', { to: ben.keyHash, amount: 500n });
  await t.call(ann, 'transfer', { to: ben.keyHash, amount: 250n });   // the recipient's read depends on the sender's
  // I5 over map transactions: the dApp's tamperings, and the verifier's withdrawal changed, doubled or removed.
  const mapTamperings = (txHex) => {
    const out = tamperings(CSL, txHex, [ann.address]);
    const tx = CSL.Transaction.from_hex(txHex);
    const edit = (f) => {
      const b = CSL.TransactionBody.from_bytes(tx.body().to_bytes());
      f(b);
      return CSL.Transaction.new(b, tx.witness_set(), tx.auxiliary_data()).to_hex();
    };
    const ws = tx.body().withdrawals();
    const key = ws.keys().get(0);
    out['a non-zero withdrawal from the map verifier'] = edit((b) => {
      const w = CSL.Withdrawals.new();
      w.insert(key, CSL.BigNum.from_str('1000000'));
      b.set_withdrawals(w);
    });
    out['a second withdrawal to an attacker'] = edit((b) => {
      const w = CSL.Withdrawals.new();
      w.insert(key, CSL.BigNum.zero());
      w.insert(CSL.RewardAddress.new(0, CSL.Credential.from_keyhash(CSL.Ed25519KeyHash.from_hex('ee'.repeat(28)))), CSL.BigNum.zero());
      b.set_withdrawals(w);
    });
    out['the verifier\'s withdrawal removed'] = edit((b) => b.set_withdrawals(CSL.Withdrawals.new()));
    return out;
  };
  let refused = 0;
  for (let i = 0; ; i++) {
    let total = 0;
    const evil = { ...rt.service, async prepare(req) {
      const p = await rt.service.prepare(req);
      const all = Object.entries(mapTamperings(p.cbor));
      total = all.length;
      return { ...p, cbor: all[i][1] };
    } };
    const k = new JoustContract({ CSL, description: token.description, deployment: token.deployment, service: evil, chain: rt.chain });
    let asked = false;
    const watched = { ...ann, signTx: (...a) => { asked = true; return ann.signTx(...a); } };
    await assert.rejects(k.at(t.id).call(watched, 'transfer', { to: ben.keyHash, amount: 1n }), undefined, `tampering ${i}`);
    assert.equal(asked, false, `tampering ${i} reached the wallet`);
    refused++;
    if (i + 1 >= total) break;
  }
  console.log(`I5: ${refused} tamperings of an engine-built map transfer refused before signing`);
  await t.call(ben, 'transfer', { to: ben.keyHash, amount: 100n });   // to itself
  await assert.rejects(t.call(ann, 'transfer', { to: ben.keyHash, amount: 751n }), /insufficient balance/);
  await t.call(ann, 'burn', { amount: 750n });                          // emptied: removed from the map
  assert.equal((await t.state()).supply, 750n);
  await assert.rejects(t.call(admin, 'close', {}), /still in circulation/);
  await t.call(ben, 'burn', { amount: 750n });
  await t.call(admin, 'close', {});
  assert.equal(await t.state(), null);
});

test('Locker: native assets accepted and sent back (the output verifier), balances in a MerkleMap', { skip, timeout: 600_000 }, async () => {
  const { rt, wallets, contract } = await setup(['locker']);
  const [admin, ann] = await wallets(2);
  const { policy, unit } = await devkitMint(CSL, rt.chain, ann, 'GOLD', 1000n);
  const gold = Buffer.from('GOLD').toString('hex');
  const locker = await contract('Locker', admin);
  assert.ok(locker.deployment.outputVerifier?.referenceScripts?.length, 'the output verifier is pinned');
  const l = locker.at((await locker.deploy(admin, { tokenPolicy: policy, tokenName: gold })).instance);
  await l.call(ann, 'lock', { qty: 400n }, { lovelace: 0n, assets: [[policy, [[gold, 400n]]]] });
  await l.call(ann, 'unlock', { qty: 150n });
  assert.equal((await l.state()).total, 250n);
  await assert.rejects(l.call(ann, 'unlock', { qty: 251n }), /not that many locked/);
  await l.call(ann, 'unlock', { qty: 250n });
  await l.call(admin, 'close', {});
  let back = 0n;
  for (const u of await rt.chain.utxosAt(ann.address)) back += u.value.get(unit) ?? 0n;
  const ent = CSL.EnterpriseAddress.new(0, CSL.Credential.from_keyhash(CSL.Ed25519KeyHash.from_hex(ann.keyHash))).to_address().to_bech32();
  for (const u of await rt.chain.utxosAt(ent)) back += u.value.get(unit) ?? 0n;
  assert.equal(back, 1000n, 'every token came back');
});

test('Dex: liquidity minted as LP tokens (ByAction), swaps both ways, LP burned', { skip, timeout: 900_000 }, async () => {
  const { rt, wallets, contract } = await setup(['dex']);
  const [admin, lp, trader] = await wallets(3, 500);
  const { policy } = await devkitMint(CSL, rt.chain, lp, 'HOSKY', 1_000_000n);
  const name = Buffer.from('HOSKY').toString('hex');
  const dex = await contract('Dex', admin);
  const d = dex.at((await dex.deploy(admin, { tokenPolicy: policy, tokenName: name })).instance);
  await d.call(lp, 'addLiquidity', { tokenIn: 100_000n }, { lovelace: 100n * ADA, assets: [[policy, [[name, 100_000n]]]] });
  const pool = await d.state();
  assert.equal(pool.lpSupply, 100n * ADA);
  const lpUnit = dex.deployment.policy + pool.lpName;
  // ADA -> token, with slippage protection: amountOut(in, reserveIn, reserveOut) with the 0.3% fee.
  const amountOut = (i, rIn, rOut) => (i * 997n * rOut) / (rIn * 1000n + i * 997n);
  const expect = amountOut(10n * ADA, 100n * ADA, 100_000n);
  await assert.rejects(d.call(trader, 'swapAdaForToken', { minOut: expect + 1n }, { lovelace: 10n * ADA }));
  await d.call(trader, 'swapAdaForToken', { minOut: expect }, { lovelace: 10n * ADA });
  await devkitSweep(CSL, rt.chain, trader);
  const sell = expect / 2n;
  await d.call(trader, 'swapTokenForAda', { tokenIn: sell, minOut: 1n }, { lovelace: 0n, assets: [[policy, [[name, sell]]]] });
  // The provider takes its liquidity out: LP burned.
  await devkitSweep(CSL, rt.chain, lp);
  const all = (await d.state()).lpSupply;
  await d.call(lp, 'removeLiquidity', { lp: all }, { lovelace: 0n, assets: [[dex.deployment.policy, [[pool.lpName, all]]]] });
  assert.equal((await d.state()).lpSupply, 0n);
  let left = 0n;
  for (const u of await rt.chain.utxosAt(lp.address)) left += u.value.get(lpUnit) ?? 0n;
  assert.equal(left, 0n, 'every LP token burned');
  await d.call(admin, 'close', {});
});

test('Fees: a sale deposits its fee into the Pot in the same transaction (composition, @Receives)', { skip, timeout: 900_000 }, async () => {
  const { rt, wallets, contract } = await setup(['fees']);
  const [owner, buyer] = await wallets(2);
  const pot = await contract('Pot', owner);
  const potId = (await pot.deploy(owner, {})).instance;
  const stall = await contract('Stall', owner, { pot: { policy: pot.deployment.policy, instance: potId }, feeBps: 500n });
  const stallId = (await stall.deploy(owner, {})).instance;
  // A deposit needs its receiver in the same transaction; the pot cannot collect nothing.
  await assert.rejects(stall.at(stallId).call(buyer, 'sell', { price: 10n * ADA }, { lovelace: 10n * ADA }));
  await assert.rejects(pot.at(potId).call(buyer, 'collect', {}));
  for (let i = 0; i < 2; i++) {
    await rt.compose(buyer, [
      { contract: stall, method: 'sell', instance: stallId, args: { price: 10n * ADA }, pay: { lovelace: 10n * ADA } },
      { contract: pot, method: 'collect', instance: potId, args: {} },
    ]);
  }
  let s = await pot.at(potId).state();
  assert.equal(s.collected, ADA);
  assert.equal(s.deposits, 2n);
  // The receiver may come first: its call sees the deposits made after it.
  await rt.compose(buyer, [
    { contract: pot, method: 'collect', instance: potId, args: {} },
    { contract: stall, method: 'sell', instance: stallId, args: { price: 20n * ADA }, pay: { lovelace: 20n * ADA } },
  ]);
  s = await pot.at(potId).state();
  assert.equal(s.collected, 2n * ADA);
  // I5 over composed transactions: every tampering is refused before the wallet is asked to sign.
  const steps = [
    { contract: stall, method: 'sell', instance: stallId, args: { price: 10n * ADA }, pay: { lovelace: 10n * ADA } },
    { contract: pot, method: 'collect', instance: potId, args: {} },
  ];
  let refused = 0;
  for (let i = 0; ; i++) {
    let total = 0;
    const evil = { ...rt.service, async prepare(req) {
      const p = await rt.service.prepare(req);
      const all = Object.entries(tamperings(CSL, p.cbor, [buyer.address]));
      total = all.length;
      return { ...p, cbor: all[i][1] };
    } };
    let asked = false;
    const watched = { ...buyer, signTx: (...a) => { asked = true; return buyer.signTx(...a); } };
    await assert.rejects(runComposed({ CSL, service: evil, chain: rt.chain, api: watched, steps }), /refused/, `tampering ${i}`);
    assert.equal(asked, false, `tampering ${i} reached the wallet`);
    refused++;
    if (i + 1 >= total) break;
  }
  console.log(`I5: ${refused} tamperings of an engine-built composed transaction refused before signing`);
});

test('Guild: a hero moves to another guild, which admits it in the same transaction (owned moves)', { skip, timeout: 900_000 }, async () => {
  const { rt, wallets, contract } = await setup(['guild']);
  const [master, ann] = await wallets(2);
  const guild = await contract('Guild', master);
  const a = (await guild.deploy(master, { minLevel: 1n })).instance;
  const b = (await guild.deploy(master, { minLevel: 2n })).instance;
  const hero = (await guild.at(a).create(ann, 'recruit', {})).object;
  await guild.at(a).object(hero).call(ann, 'levelUp', {});
  const move = [
    { contract: guild, method: 'transfer', instance: a, object: hero, into: b, args: {} },
    { contract: guild, method: 'admit', instance: b, args: { heroId: hero } },
  ];
  // A move needs the destination's consent: alone, it is refused.
  await assert.rejects(rt.compose(ann, [move[0]]), /no @Admits call|Script evaluation failed/);   // the engine or the page refuses it
  // I5 over a move: every tampering is refused before the wallet is asked to sign.
  let refused = 0;
  for (let i = 0; ; i++) {
    let total = 0;
    const evil = { ...rt.service, async prepare(req) {
      const p = await rt.service.prepare(req);
      const all = Object.entries(tamperings(CSL, p.cbor, [ann.address]));
      total = all.length;
      return { ...p, cbor: all[i][1] };
    } };
    let asked = false;
    const watched = { ...ann, signTx: (...x) => { asked = true; return ann.signTx(...x); } };
    await assert.rejects(runComposed({ CSL, service: evil, chain: rt.chain, api: watched, steps: move }), /refused/, `tampering ${i}`);
    assert.equal(asked, false, `tampering ${i} reached the wallet`);
    refused++;
    if (i + 1 >= total) break;
  }
  console.log(`I5: ${refused} tamperings of an engine-built move refused before signing`);
  await rt.compose(ann, move);
  assert.equal((await guild.at(b).state()).arrivals, 1n);
  const moved = await guild.at(b).object(hero).read();
  assert.equal(moved.value.level, 2n);
  await guild.at(b).object(hero).call(ann, 'levelUp', {});
  await guild.at(b).object(hero).call(ann, 'retire', {});
});
