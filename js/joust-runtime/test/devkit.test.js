// M2 on Yaci DevKit: Counter and Bank from Node with no JVM. The engine prepares each call from the chain as Yaci Store
// serves it; @julclang/state-client verifies it, a key wallet signs it, and it is submitted and confirmed.
//   JOUST_CONTRACTS: contracts.json as joust build writes it (default: the runtime engine's test vectors)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { generateMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english';
import CSL from '@emurgo/cardano-serialization-lib-nodejs';
import { joustRuntime, devkitClock, devkitTopUp, devWallet, JoustContract } from '../src/index.js';
import { tamperings } from '../../../examples/dapp/frontend/src/tamper.js';

const STORE = process.env.STORE ?? 'http://localhost:8080/api/v1';
const ADMIN = process.env.ADMIN ?? 'http://localhost:10000';
const CONTRACTS = process.env.JOUST_CONTRACTS ?? new URL('../../../julc-state-runtime-wasm/build/prepare-vectors.json', import.meta.url).pathname;

async function reachable() {
  try {
    return (await fetch(STORE + '/blocks/latest')).ok && (await fetch(ADMIN + '/local-cluster/api/admin/devnet')).ok;
  } catch {
    return false;
  }
}

const devkit = await reachable();

test('Counter and Bank on DevKit through the runtime engine', { skip: !devkit || !existsSync(CONTRACTS) ? 'DevKit or contracts missing' : false, timeout: 600_000 }, async () => {
  const bundle = JSON.parse(readFileSync(CONTRACTS, 'utf8'));
  const contracts = bundle.contracts ?? bundle;
  const rt = joustRuntime({ CSL, chainUrl: STORE, contracts, clock: await devkitClock(ADMIN), networkId: 0, ttlMs: 20_000 });
  const mnemonic = generateMnemonic(wordlist, 256);
  const wallet = devWallet({ CSL, chain: rt.chain, mnemonic });
  await devkitTopUp(wallet.address, 500, ADMIN, { CSL, chain: rt.chain });
  for (let i = 0; i < 30 && !(await rt.chain.utxosAt(wallet.address)).length; i++) await new Promise((r) => setTimeout(r, 1000));

  const description = (title) => JSON.parse(contracts.map((c) => (typeof c.description === 'string' ? c.description : JSON.stringify(c.description)))
    .find((d) => JSON.parse(d).preamble.title.endsWith('.' + title)));
  const contract = async (title, service = rt.service) => {
    const d = description(title);
    const deployment = await rt.ensureDeployment(d.preamble.title, wallet);
    return new JoustContract({ CSL, description: d, deployment, service, chain: rt.chain });
  };

  // I5: every tampering of an engine-built transaction is refused before the wallet is asked to sign.
  let signRequests = 0;
  const signing = { ...wallet, signTx: (...a) => { signRequests++; return wallet.signTx(...a); } };
  async function refusesEveryTampering(label, make, call) {
    let variants = null;
    for (let i = 0; ; i++) {
      const evil = {
        ...rt.service,
        async prepare(request) {
          const p = await rt.service.prepare(request);
          const all = Object.entries(tamperings(CSL, p.cbor, [wallet.address]));
          variants ??= all.length;
          return { ...p, cbor: all[i][1] };
        },
      };
      const before = signRequests;
      await assert.rejects(call(await make(evil), signing), (e) => /refused|verif|does not describe/i.test(e.message), `${label}: variant ${i}`);
      assert.equal(signRequests, before, `${label}: variant ${i} reached the wallet`);
      if (variants !== null && i + 1 >= variants) return variants;
    }
  }

  // Counter: create, increment, read, refuse, close.
  const counter = await contract('Counter');
  const made = await counter.deploy(wallet, {});
  assert.match(made.instance, /^[0-9a-f]{64}$/);
  const c = counter.at(made.instance);
  await c.call(wallet, 'increment', { by: 5n });
  await c.call(wallet, 'increment', { by: 2n });
  assert.equal((await c.state()).count, 7n);
  const nc = await refusesEveryTampering('Counter.increment', (svc) => contract('Counter', svc),
    (k, w) => k.at(made.instance).call(w, 'increment', { by: 1n }));
  console.log(`I5: ${nc} tamperings of an engine-built increment refused before signing`);
  await assert.rejects(c.call(wallet, 'increment', { by: 0n }), /increment must be positive/);
  await c.call(wallet, 'close', {});
  assert.equal(await c.state(), null);

  // Bank: an instance, an owned account (payable, @Shared on withdraw), close.
  const bank = await contract('Bank');
  const b = bank.at((await bank.deploy(wallet, { withdrawLimit: 30_000_000n })).instance);
  const opened = await b.create(wallet, 'open', {}, { lovelace: 10_000_000n });
  const acct = b.object(opened.object);
  await acct.call(wallet, 'deposit', {}, { lovelace: 5_000_000n });
  await acct.call(wallet, 'withdraw', { amount: 4_000_000n });
  const nb = await refusesEveryTampering('Bank.withdraw', (svc) => contract('Bank', svc),
    (k, w) => k.at(b.id).object(opened.object).call(w, 'withdraw', { amount: 1_000_000n }));
  console.log(`I5: ${nb} tamperings of an engine-built withdraw refused before signing`);
  assert.equal((await acct.read()).value.balance, 11_000_000n);
  await acct.call(wallet, 'close', {});
  console.log(`engine rounds per prepare: ${rt.service.rounds.join(' ')}`);
});
