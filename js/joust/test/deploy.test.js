// joust deploy writes build/joust/deployments.<network>.json (ADR-0013 D2, ADR-0018 D16), offline (issue #75): the
// network has its clock in the config, and the runtime and the deployer's wallet are stubs. A rewrite once dropped the
// write without any offline test noticing; the DevKit tests were the only ones to reach it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { deploy, scriptDigest } from '../src/deploy.js';

const description = (title, params) => JSON.stringify({ preamble: { title }, 'x-joust': params ? { params: { $ref: '#/definitions/P' } } : {} });

function project() {
  const root = mkdtempSync(join(tmpdir(), 'joust-deploy-'));
  mkdirSync(join(root, 'build', 'joust'), { recursive: true });
  writeFileSync(join(root, 'build', 'joust', 'contracts.json'), JSON.stringify([
    { description: description('app.Counter'), script: '43420102', transitions: {}, built: { joust: '0.1.0-test' } },
    { description: description('app.Governed', true), script: '43420103', transitions: {} },
    { description: description('app.Tuned', true), script: '43420104', transitions: {} },
  ]));
  const config = {
    out: 'build/joust', network: 'offline',
    networks: { offline: { chain: 'http://chain.invalid/api/v1', networkId: 0, clock: { zeroSlot: 0, zeroTimeMs: 1_000, slotLengthMs: 1_000 } } },
    params: { Tuned: { fee: 5 } },
  };
  return { root, config };
}

/** A runtime that deploys nothing: each contract gets the deployment `policyOf(title)` names; calls are recorded. */
function stubRuntime(policyOf, calls) {
  return (opts) => {
    calls.push({ opts });
    return {
      async ensureDeployment(title, wallet, params) {
        calls.push({ title, wallet, params });
        const policy = policyOf(title);
        return { contract: title, policy, address: `addr_test_${policy}`, referenceScripts: [`${policy}#0`], ...(params ? { params } : {}) };
      },
    };
  };
}

test('deploy writes deployments.<network>.json, offline, with the runtime and the wallet stubbed', async () => {
  const { root, config } = project();
  const calls = [];
  const wallet = { address: 'addr_test_deployer' };
  const logs = [];
  const out = await deploy({ root, config, log: (m) => logs.push(m), wallet, runtime: stubRuntime((t) => (t === 'app.Counter' ? 'aa' : 'bb'), calls) });
  const file = join(root, 'build', 'joust', 'deployments.offline.json');
  assert.ok(existsSync(file), 'the deployments file is written');
  const d = JSON.parse(readFileSync(file, 'utf8'));
  assert.equal(d.network, 'offline');
  assert.equal(d.networkId, 0);
  assert.equal(d.chain, 'http://chain.invalid/api/v1');
  assert.deepEqual(d.clock, config.networks.offline.clock);
  // Counter and Tuned (its @Params from the config); Governed has no params in the config and is skipped, said once.
  assert.deepEqual(Object.keys(d.contracts).sort(), ['app.Counter', 'app.Tuned']);
  assert.deepEqual(d.contracts, out);
  assert.equal(d.contracts['app.Counter'].policy, 'aa');
  assert.equal(d.contracts['app.Counter'].scriptDigest, scriptDigest('43420102'));
  assert.deepEqual(d.contracts['app.Counter'].built, { joust: '0.1.0-test' });
  assert.deepEqual(d.contracts['app.Tuned'].params, { fee: 5 });
  assert.equal(d.contracts['app.Tuned'].built, null);
  assert.ok(logs.some((l) => /app\.Governed: takes @Params/.test(l)), logs.join('\n'));
  assert.ok(logs.some((l) => l.includes(`wrote ${file}`)), logs.join('\n'));
  // The runtime saw the network and the bundle; the stub wallet paid for every deployment.
  assert.equal(calls[0].opts.chainUrl, 'http://chain.invalid/api/v1');
  assert.equal(calls[0].opts.contracts.length, 3);
  assert.ok(calls.slice(1).every((c) => c.wallet === wallet));
  assert.equal(d.superseded, undefined);

  // A second deploy whose Counter is another script: the old record is kept under "superseded", never dropped.
  await deploy({ root, config, log: () => {}, wallet, runtime: stubRuntime((t) => (t === 'app.Counter' ? 'cc' : 'bb'), []) });
  const again = JSON.parse(readFileSync(file, 'utf8'));
  assert.equal(again.contracts['app.Counter'].policy, 'cc');
  assert.equal(again.superseded.length, 1);
  assert.equal(again.superseded[0].title, 'app.Counter');
  assert.equal(again.superseded[0].policy, 'aa');
  assert.equal(again.superseded[0].reason, 'redeployed as another script');
});

test('deploy without a build refuses, and writes nothing', async () => {
  const root = mkdtempSync(join(tmpdir(), 'joust-deploy-'));
  const { config } = project();
  await assert.rejects(deploy({ root, config, log: () => {}, wallet: {}, runtime: stubRuntime(() => 'aa', []) }), /no build: run joust build first/);
  assert.equal(existsSync(join(root, 'build', 'joust', 'deployments.offline.json')), false);
});
