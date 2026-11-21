// Differential intersection of the page verifier and protocol 1. The page API has no publication or host-claim input.
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import test from 'node:test';
import { verifyPrepared, paymentKeyHashOf } from '../src/verify.js';
import { rewardAddressOf } from '../src/governors.js';

const dir = new URL('../../../fixtures/host/verifier/', import.meta.url);
const common = new Set(['mint', 'fee', 'balance', 'signer', 'pointer', 'redeemer', 'collateral', 'deposit',
  'continuingOutput', 'netOutflow', 'walletOutput', 'scriptInput', 'auxiliaryData', 'withdrawal', 'upperBound', 'lowerBound', 'isValid', 'governance', 'referenceInput', 'budget', 'designatedInput', 'keyedToken', 'keyedRedeemer', 'keyedLink', 'keyedInputValue']);

for (const file of readdirSync(dir).filter(f => f.endsWith('.json'))) {
  const v = JSON.parse(readFileSync(new URL(file, dir)));
  if (v.method !== 'prepare' || v.violations.some(code => !common.has(code))) continue;
  test('wallet agreement on ' + file, async t => {
    const { description, policy, address, outputVerifier } = v.anchors;
    const { call } = v.request;
    const x = description['x-joust'];
    const meta = [...x.actions, ...x.creations].find(m => m.kind === call.kind && m.name === call.method);
    const utxos = new Map(Object.entries(v.resolved).map(([r, u]) => [r, {
      address: u.address, inlineDatum: u.inline_datum, referenceScriptHash: u.reference_script_hash,
      value: new Map(u.amount.map(a => [a.unit.replace('.', ''), BigInt(a.quantity)])),
    }]));
    const payer = call.address;
    const wallet = { addresses: [payer], keyHashes: [paymentKeyHashOf(payer)],
      utxos: new Map([...utxos].filter(([, u]) => u.address === payer)) };
    const verifierRefs = [...utxos].filter(([, u]) => u.address === outputVerifier.address &&
      u.referenceScriptHash === outputVerifier.hash).map(([r]) => r);
    if (v.clock) t.mock.method(Date, 'now', () =>
      v.clock.zeroTimeMs + (v.request.now - v.clock.zeroSlot) * v.clock.slotLengthMs);
    const { problems, report } = await verifyPrepared({ description, txHex: v.result.prepared.cbor,
      deployment: { policy, address, slotConfig: v.clock, networkId: v.networkId, outputVerifier: {
        rewardAddress: rewardAddressOf(outputVerifier.hash, v.networkId), referenceScripts: verifierRefs } },
      wallet, call: { ...call, key: call.object, meta, cosignerKeys: call.cosigners },
      chain: { tip: v.request.now, params: v.protocolParams, utxo: async r => utxos.get(r) },
      limits: { maxFee: BigInt(v.maxFee), maxValiditySlots: call.pageLimits?.[1] ?? (v.clock ? 900 : v.request.ttlSlots),
        maxStartSlots: call.pageLimits?.[0] ?? 60, maxBackdateSlots: 0 },
    });
    assert.equal(problems.length === 0, v.violations.length === 0, problems.join('; '));
    if (file === 'dapp-0003.json' || file === 'dapp-0034.json') {
      assert.ok(report.targetRef);
      assert.equal(report.redeemer.by, BigInt(call.args.by));
    }
  });
}
