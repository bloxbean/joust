// #165 Codex round 6: an output carrying a reference script is never offered for spending, whether the engine's snapshot
// lists it (a provider-backed prepare without walletUtxos) or a DevKit helper funds a plain transaction with it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fetchNeed, emptySnapshot } from '../src/snapshot.js';
import { engineService } from '../src/service.js';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { devkitMint, devkitSweep } from '../src/devkit.js';
import { devWallet } from '@julclang/state-client/devwallet';
import { enterpriseAddress, parseTx } from '@julclang/state-client/ledger';

const lovelace = (q) => [{ unit: 'lovelace', quantity: String(q) }];

test('a listing need leaves out an output whose reference script has no known hash; a lookup keeps it', async () => {
  const flagged = { txHash: 'ee'.repeat(32), index: 0, address: 'addr_test1x', value: lovelace(9_000_000), datumHash: null,
    inlineDatum: null, referenceScriptHash: null, hasReferenceScript: true };
  const hashed = { ...flagged, txHash: 'ef'.repeat(32), referenceScriptHash: '44'.repeat(28) };
  const plain = { ...flagged, txHash: 'f0'.repeat(32), hasReferenceScript: false };
  const provider = { tip: async () => ({}), utxos: async () => [flagged, hashed, plain], output: async () => flagged };
  const s = emptySnapshot();
  await fetchNeed(provider, s, 'utxos', 'addr_test1x');
  // The hashed one stays, with its hash (the runtime excludes it by that); the flagged one is left out.
  assert.deepEqual(s.utxos['addr_test1x'].map((u) => [u.tx_hash, u.reference_script_hash]),
    [['ef'.repeat(32), '44'.repeat(28)], ['f0'.repeat(32), null]]);
  await fetchNeed(provider, s, 'output', 'ee'.repeat(32) + '#0');
  assert.equal(s.outputs.length, 1);
});

/** A chain over {@code utxos} (by address) recording the transaction submitted. */
function chainWith(utxos) {
  const sent = [];
  return {
    sent,
    utxosAt: async (a) => utxos[a] ?? [],
    params: async () => ({ min_fee_a: 44, min_fee_b: 155381 }),
    submit: async (cbor) => { sent.push(cbor); return 'aa'.repeat(32); },
    awaitTx: async () => {},
  };
}

const inputsOf = (cbor) => parseTx(cbor).inputs.map((r) => r.split('#')[0]);

test('devkitMint and devkitSweep never fund from a UTxO carrying a reference script', async () => {
  const probe = devWallet({ chain: chainWith({}) });
  const at = (address, id, ada, extra = {}) => ({ ref: id.repeat(32) + '#0', address, value: new Map([['lovelace', BigInt(ada)]]),
    inlineDatum: null, datumHash: null, referenceScriptHash: null, hasReferenceScript: false, ...extra });
  const base = [at(probe.address, 'a1', 50_000_000, { referenceScriptHash: '44'.repeat(28), hasReferenceScript: true }),
    at(probe.address, 'a2', 40_000_000, { hasReferenceScript: true }), at(probe.address, 'a3', 10_000_000)];
  const chain = chainWith({ [probe.address]: base });
  const wallet = devWallet({ chain });
  await devkitMint(chain, wallet, 'T', 1);
  assert.deepEqual(inputsOf(chain.sent[0]), ['a3'.repeat(32)]);
  const ent = enterpriseAddress({ type: 'key', hash: wallet.keyHash }, 0);
  const sweep = chainWith({ [probe.address]: base, [ent]: [at(ent, 'b1', 3_000_000), at(ent, 'b2', 9_000_000, { hasReferenceScript: true })] });
  await devkitSweep(sweep, devWallet({ chain: sweep }), 0);
  assert.deepEqual(inputsOf(sweep.sent[0]).sort(), ['a3'.repeat(32), 'b1'.repeat(32)].sort());
});

test('prepare with walletUtxos: an output carrying a reference script with no known hash never reaches the engine', async () => {
  // A stand-in engine (the image's loading contract: onJulcReady), recording the snapshot it is asked to prepare from.
  const dir = mkdtempSync(join(tmpdir(), 'joust-engine-'));
  writeFileSync(join(dir, 'package.json'), '{"type":"commonjs"}');
  writeFileSync(join(dir, 'joust-runtime.js'), `globalThis.onJulcReady({ joust: { prepare(r) {
    globalThis.__joustSnapshot = JSON.parse(r.snapshot);
    return { ok: true, cbor: null, txHash: 'tx', validTo: 0, signers: [], plan: null };
  } } });`);
  const svc = engineService({ contracts: [], engine: { dir }, clock: { zeroSlot: 0, zeroTimeMs: 0, slotLengthMs: 1000 },
    api: { tip: async () => ({}) }, mapMirror: {} });
  const row = (id, extra) => ({ txHash: id.repeat(32), index: 0, address: 'addr_test1x', value: lovelace(9_000_000), datumHash: null,
    inlineDatum: null, referenceScriptHash: null, ...extra });
  await svc.prepare({ contract: 'Test', kind: 'call', address: 'addr_test1x', walletUtxos: [
    row('ee', { hasReferenceScript: true }), row('ef', { referenceScriptHash: '44'.repeat(28), hasReferenceScript: true }), row('f0', {})] });
  assert.deepEqual(globalThis.__joustSnapshot.utxos['addr_test1x'].map((u) => u.tx_hash), ['ef'.repeat(32), 'f0'.repeat(32)]);
});
