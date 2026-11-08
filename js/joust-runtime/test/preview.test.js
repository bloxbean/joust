// The npm path on Cardano preview through Blockfrost (ADR-0015 M4 exit): contracts built by the toolchain engine,
// deployed and called through the runtime engine, every transaction verified before a key wallet signs it.
// Runs only with BLOCKFROST_PREVIEW_PROJECT_ID and JOUST_MNEMONIC (a funded preview key) set; never on CI by default.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import CSL from '@emurgo/cardano-serialization-lib-nodejs';
import { joustRuntime, devWallet, JoustContract } from '../src/index.js';
import { build } from '../../joust/src/build.js';
import { tamperings } from '../../../examples/dapp/frontend/src/tamper.js';

const PROJECT = process.env.BLOCKFROST_PREVIEW_PROJECT_ID;
const MNEMONIC = process.env.JOUST_MNEMONIC;
const CHAIN = 'https://cardano-preview.blockfrost.io/api/v0';
const CLOCK = { zeroSlot: 0, zeroTimeMs: 1666656000000, slotLengthMs: 1000 };
const EXAMPLES = new URL('../../../examples/', import.meta.url).pathname;
const skip = PROJECT && MNEMONIC ? false : 'set BLOCKFROST_PREVIEW_PROJECT_ID and JOUST_MNEMONIC (a funded preview key)';

async function built(name) {
  const out = mkdtempSync(join(tmpdir(), 'joust-preview-'));
  const r = await build({ root: join(EXAMPLES, name), src: 'src/main/java', out, log: () => {},
    keep: (f, text) => f.endsWith('.jsc') || /@StateContract|@OnchainLibrary/.test(text) });
  assert.ok(r.ok, `${name} builds`);
  return JSON.parse(readFileSync(join(out, 'contracts.json'), 'utf8'));
}

async function setup(names) {
  const contracts = (await Promise.all(names.map(built))).flat();
  const rt = joustRuntime({ chainUrl: CHAIN, projectId: PROJECT, contracts, clock: CLOCK, networkId: 0, ttlMs: 180_000 });
  const wallet = devWallet({ chain: rt.chain, mnemonic: MNEMONIC, networkId: 0 });
  const contract = async (simple, params) => {
    const c = contracts.find((x) => JSON.parse(x.description).preamble.title.endsWith('.' + simple));
    const d = JSON.parse(c.description);
    const deployment = await rt.ensureDeployment(d.preamble.title, wallet, params);
    return new JoustContract({ description: d, deployment, service: rt.service, chain: rt.chain });
  };
  return { rt, wallet, contract, contracts };
}

/** Every tampering of an engine-built call is refused before the wallet is asked (I5). */
async function tamperLoop(k, wallet, run) {
  let refused = 0;
  for (let i = 0, total = 1; i < total; i++) {
    const evil = { ...k.service, async prepare(req) {
      const p = await k.service.prepare(req);
      const all = Object.entries(tamperings(CSL, p.cbor, [wallet.address]));
      total = all.length;
      return { ...p, cbor: all[i][1] };
    } };
    const bad = new JoustContract({ description: k.description, deployment: k.deployment, service: evil, chain: k.chain });
    let touched = false;
    const spy = { ...wallet, signTx: (...x) => { touched = true; return wallet.signTx(...x); } };
    await assert.rejects(run(bad, spy), undefined, `tampering ${i}`);
    assert.equal(touched, false, `tampering ${i} reached the wallet`);
    refused++;
  }
  return refused;
}

// One funded key: the flows run one after another (a test file's tests may run concurrently).
test('preview through Blockfrost', { skip, timeout: 3_600_000, concurrency: 1 }, async (tt) => {
await tt.test('Counter (deploy, increment, refusals, I5, close)', async () => {
  const { wallet, contract } = await setup(['counter']);
  const counter = await contract('Counter');
  const c = counter.at((await counter.deploy(wallet, {})).instance);
  await c.call(wallet, 'increment', { by: 5n });
  assert.equal((await c.state()).count, 5n);
  await assert.rejects(c.call(wallet, 'increment', { by: 0n }), /positive/);
  const refused = await tamperLoop(counter, wallet, (bad, spy) => bad.at(c.id).call(spy, 'increment', { by: 1n }));
  console.log(`I5 on preview: ${refused} tamperings of an engine-built increment refused before signing`);
  await c.call(wallet, 'close', {});
  assert.equal(await c.state(), null);
});

await tt.test('Token (MerkleMap); a second runtime rebuilds the map from Blockfrost history', async () => {
  const { rt, wallet, contract, contracts } = await setup(['token']);
  const token = await contract('Token');
  assert.ok(token.deployment.mapVerifier?.referenceScripts?.length, 'the map verifier is pinned');
  const t = token.at((await token.deploy(wallet, {})).instance);
  const other = 'ab'.repeat(28);
  await t.call(wallet, 'mint', { to: wallet.keyHash, amount: 1000n });
  await t.call(wallet, 'transfer', { to: other, amount: 250n });
  // A second runtime, empty mirror: the map is rebuilt from Blockfrost's history (claims checked) before it calls.
  const rt2 = joustRuntime({ chainUrl: CHAIN, projectId: PROJECT, contracts, deployments: rt.deployments, clock: CLOCK, networkId: 0, ttlMs: 180_000 });
  const t2 = new JoustContract({ description: token.description, deployment: token.deployment, service: rt2.service, chain: rt2.chain }).at(t.id);
  await t2.call(wallet, 'transfer', { to: other, amount: 50n });
  assert.ok(rt2.service.mirror.states.size > 1, 'rebuilt from the chain');
  await t.call(wallet, 'burn', { amount: 700n });   // the first runtime catches up the same way
  assert.equal((await t.state()).supply, 300n);
});
});
