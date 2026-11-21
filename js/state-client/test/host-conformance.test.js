// Differential intersection of the page verifier and protocol 1. The page API has no publication or host-claim input.
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import test from 'node:test';
import { verifyPrepared, paymentKeyHashOf } from '../src/verify.js';
import { rewardAddressOf } from '../src/governors.js';

const dir = new URL('../../../fixtures/host/verifier/', import.meta.url);
const common = new Set(['mint', 'fee', 'balance', 'signer', 'pointer', 'redeemer', 'collateral', 'deposit',
  'mapWithdrawal', 'continuingOutput', 'netOutflow', 'walletOutput', 'scriptInput', 'auxiliaryData', 'withdrawal', 'upperBound', 'lowerBound', 'isValid', 'governance', 'referenceInput', 'budget', 'designatedInput', 'keyedToken', 'keyedRedeemer', 'keyedLink', 'keyedInputValue']);

// Negative vectors (by `mutation`) whose protocol-1 check the page verifier does not make yet: the page accepts them
// where the reference refuses. Each still runs and must still be accepted, so an entry fails once verify.js gains the
// check and has to be removed. The ten `claims` entries close with branch fix/verify-wallet-sends (ADR-0013 §9.2).
const claims = 'the output verifier\'s claims (closes with fix/verify-wallet-sends)';
const pageGaps = {
  'claims-shape': claims, 'claims-malformed-claim': claims, 'claims-deposits': claims, 'claims-duplicate': claims,
  'claims-out-of-order': claims, 'claims-out-of-range': claims, 'claims-foreign': claims, 'claimed-send-tagged': claims,
  'send-claim-on-change': claims, 'send-claim-wallet-funded': claims,
  'change-at-enterprise': 'change only at the payer address (the page accepts the caller\'s enterprise address)',
  'target-datum': 'the target datum decodes', 'owned-instance-target': 'the owned target names the call\'s instance',
  'signed-by-other': 'signedBy against the target state', 'caller-not-required': 'the caller required for Ctx methods',
  'cosigner-without-quorum': 'named co-signers only for quorum methods', 'duplicate-signer': 'duplicate required signers',
  'extra-argument': 'every anchored argument in the constructor', 'mint-pointer': 'the method mint redeemer at index 0',
  'continuation-script': 'no script reference on the continuation', 'empty-window': 'a nonempty window',
  'negative-fee': 'a nonnegative fee', votes: 'votes', proposals: 'proposals',
  'collateral-return-datum': 'a clean collateral return', 'collateral-return-script': 'a clean collateral return',
  'collateral-unconserved': 'collateral conservation', 'witness-script': 'witness scripts pinned',
  'missing-holder-reference': 'the policy script supplied',
  'aux-hash-without-data': 'no auxiliary data hash', 'call-withdrawal-without-sends': 'a withdrawal only for a method with sends',
  'collateral-return-enterprise-network': 'the collateral return\'s network', 'collateral-return-inline-datum': 'a clean collateral return',
  'expired-window': 'an upper bound after now', 'missing-reward-redeemer': 'the verifier withdrawal\'s redeemer',
  'missing-verifier-reference': 'the verifier script supplied', 'negative-collateral-total': 'a nonnegative total collateral',
  'short-signer': 'required signers are key hashes',
  'keyed-designated-record': 'the designated input equals the persisted operation record\'s',
  'keyed-signed-by': 'signedBy against the keyed target\'s state',
  'map-verifier-reference': 'the map verifier script supplied',
};

for (const file of readdirSync(dir).filter(f => f.endsWith('.json'))) {
  const v = JSON.parse(readFileSync(new URL(file, dir)));
  if (v.method !== 'prepare' || v.violations.some(code => !common.has(code))) continue;
  const gap = pageGaps[v.mutation];
  test(gap ? `page verifier gap on ${file}: ${gap}` : 'wallet agreement on ' + file, async t => {
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
    const pinned = anchor => ({ rewardAddress: rewardAddressOf(anchor.hash, v.networkId),
      referenceScripts: [...utxos].filter(([, u]) => u.address === anchor.address &&
        u.referenceScriptHash === anchor.hash).map(([r]) => r) });
    if (v.clock) t.mock.method(Date, 'now', () =>
      v.clock.zeroTimeMs + (v.request.now - v.clock.zeroSlot) * v.clock.slotLengthMs);
    const { problems, report } = await verifyPrepared({ description, txHex: v.result.prepared.cbor,
      deployment: { policy, address, slotConfig: v.clock, networkId: v.networkId, outputVerifier: pinned(outputVerifier),
        mapVerifier: v.anchors.mapVerifier && pinned(v.anchors.mapVerifier) },
      wallet, call: { ...call, key: call.object, meta, cosignerKeys: call.cosigners },
      chain: { tip: v.request.now, params: v.protocolParams, utxo: async r => utxos.get(r) },
      limits: { maxFee: BigInt(v.maxFee), maxValiditySlots: call.pageLimits?.[1] ?? (v.clock ? 900 : v.request.ttlSlots),
        maxStartSlots: call.pageLimits?.[0] ?? 60, maxBackdateSlots: 0 },
    });
    if (gap) assert.deepEqual(problems, [], `the page now refuses ${v.mutation}: remove it from pageGaps`);
    else assert.equal(problems.length === 0, v.violations.length === 0, problems.join('; '));
    if (file === 'dapp-0003.json' || file === 'dapp-0034.json') {
      assert.ok(report.targetRef);
      assert.equal(report.redeemer.by, BigInt(call.args.by));
    }
  });
}
