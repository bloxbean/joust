// Counter on a local Yaci DevKit (yaci-devkit up --enable-yaci-store), after `npm run build` and `npm run deploy`:
// node --test test/devkit/. The offline test (test/counter.test.js) runs the same calls with no chain.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { generateMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english';
import { joustRuntime, devWallet, devkitTopUp } from '@julclang/joust-runtime';
import { Counter } from '../../build/joust/Counter.js';

const read = (f) => JSON.parse(readFileSync(new URL(f, import.meta.url), 'utf8'));
const contracts = read('../../build/joust/contracts.json');
const deployed = read('../../build/joust/deployments.devkit.json');
const config = read('../../joust.config.json');

test('a counter: create, increment, refuse, close', { timeout: 300_000 }, async () => {
  const rt = joustRuntime({ chainUrl: deployed.chain, contracts, clock: deployed.clock, networkId: deployed.networkId,
    deployments: deployed.contracts, ttlMs: 20_000 });
  // A fresh DevKit wallet with 100 ADA from the faucet.
  const wallet = devWallet({ chain: rt.chain, mnemonic: generateMnemonic(wordlist, 256) });
  await devkitTopUp(wallet.address, 100, config.networks.devkit.admin, { chain: rt.chain });
  for (let i = 0; i < 30 && !(await rt.chain.utxosAt(wallet.address)).length; i++) await new Promise((r) => setTimeout(r, 1000));

  const counter = new Counter(rt.options('app.Counter'));
  const { instance } = await counter.deploy(wallet, {});
  await instance.increment(wallet, { by: 5n });
  await instance.increment(wallet, { by: 2n });
  assert.equal((await instance.state()).count, 7n);
  await assert.rejects(instance.increment(wallet, { by: 0n }), /increment must be positive/);
  await instance.close(wallet);
  assert.equal(await instance.state(), null);
});
