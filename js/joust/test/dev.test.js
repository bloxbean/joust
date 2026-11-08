// joust dev on Yaci DevKit (ADR-0015 D7): attaches through Yaci Store's Blockfrost-compatible API, builds, deploys,
// serves the chain at /chain (the page's only chain access), funds wallets at /dev/topup, and rebuilds and redeploys
// when a contract is saved.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, cpSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { generateMnemonic } from '@scure/bip39';
import { wordlist } from '@scure/bip39/wordlists/english';
import { dev } from '../src/dev.js';
import { loadConfig } from '../src/config.js';

const TEMPLATE = new URL('../../create-joust/template/', import.meta.url).pathname;
const up = await fetch('http://localhost:8080/api/v1/blocks/latest').then((r) => r.ok).catch(() => false);
const skip = up ? false : 'DevKit not reachable';

test('joust dev attaches to DevKit (Yaci Store), serves the chain and the build, and redeploys on save', { skip, timeout: 900_000 }, async () => {
  const root = mkdtempSync(join(tmpdir(), 'joust-dev-'));
  cpSync(join(TEMPLATE, 'contracts'), join(root, 'contracts'), { recursive: true });
  cpSync(join(TEMPLATE, 'joust.config.json'), join(root, 'joust.config.json'));
  const config = loadConfig(root);
  const logs = [];
  const d = await dev({ root, config, port: 0, log: (m) => logs.push(m) });
  try {
    const net = await (await fetch(d.url + '/joust/network.json')).json();
    assert.equal(net.network, 'devkit');
    assert.equal(net.chain, '/chain');
    const title = Object.keys(net.deployments)[0];
    assert.match(title, /Counter$/);
    const first = net.deployments[title].policy;
    // The chain through the proxy: reads and a submit (a call made by the runtime against /chain only).
    assert.ok((await (await fetch(d.url + '/chain/blocks/latest')).json()).slot > 0);
    const contracts = await (await fetch(d.url + '/joust/contracts.json')).json();
    const { joustRuntime, devWallet, JoustContract } = await import('@julclang/joust-runtime');
    const rt = joustRuntime({ chainUrl: d.url + '/chain', contracts, clock: net.clock, networkId: 0, ttlMs: 20_000, deployments: net.deployments });
    const wallet = devWallet({ chain: rt.chain, mnemonic: generateMnemonic(wordlist, 256) });
    const r = await fetch(d.url + '/dev/topup', { method: 'POST', body: JSON.stringify({ address: wallet.address, ada: 100 }) });
    assert.ok(r.ok, 'the faucet');
    for (let i = 0; i < 30 && !(await rt.chain.utxosAt(wallet.address)).length; i++) await new Promise((ok) => setTimeout(ok, 1000));
    const description = JSON.parse(contracts.find((x) => JSON.parse(x.description).preamble.title === title).description);
    const counter = new JoustContract({ ...rt.options(title), description });
    const c = counter.at((await counter.deploy(wallet, {})).instance);
    await c.call(wallet, 'increment', { by: 2n });
    assert.equal((await c.state()).count, 2n);
    // Save a contract: rebuilt and redeployed (a new script, a new policy).
    const f = join(root, 'contracts', 'app', 'Counter.java');
    // (A change to the code: Require messages are not in the script unless traces are on.)
    writeFileSync(f, readFileSync(f, 'utf8').replace('by.compareTo(BigInteger.ZERO) > 0', 'by.signum() > 0'));
    for (let i = 0; i < 120; i++) {
      const n = await (await fetch(d.url + '/joust/network.json')).json();
      if (n.deployments[title]?.policy && n.deployments[title].policy !== first) break;
      await new Promise((ok) => setTimeout(ok, 1000));
    }
    const after = await (await fetch(d.url + '/joust/network.json')).json();
    assert.notEqual(after.deployments[title].policy, first, 'redeployed after the save:\n' + logs.join('\n'));
    assert.ok(logs.some((m) => /changed: rebuilding/.test(m)), logs.join('\n'));
  } finally {
    await d.close();
  }
});
