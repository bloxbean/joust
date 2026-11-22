// #165 Codex round 2: a CIP-30 wallet UTxO carrying a reference script keeps its script hash on the way to the runtime,
// so the runtime never spends it for the tokens it holds (it would consume a deployment's script).
import test from 'node:test';
import assert from 'node:assert/strict';
import { Serialization, Cardano } from '@cardano-sdk/core';
import { unspentOutput, unspentOutputCbor, baseAddress } from '../src/ledger.js';
import { walletView } from '../src/wallet.js';
import { fromWallet } from '../src/client.js';

const address = baseAddress({ type: 'key', hash: '11'.repeat(28) }, { type: 'key', hash: '22'.repeat(28) }, 0);
const unit = '33'.repeat(28) + '01';
const value = new Map([['lovelace', 20_000_000n], [unit, 100n]]);
// An always-true PlutusV3 script: any bytes do, only its hash matters here.
const script = { __type: Cardano.ScriptType.Plutus, bytes: '4e4d01000033222220051200120011', version: Cardano.PlutusLanguageVersion.V3 };

function withScript(txId) {
  const output = { address: Cardano.PaymentAddress(address), value: { coins: 20_000_000n, assets: new Map([[Cardano.AssetId(unit), 100n]]) },
    scriptReference: script };
  return Serialization.TransactionUnspentOutput.fromCore([{ txId: Cardano.TransactionId(txId), index: 0 }, output]).toCbor();
}

test('a CIP-30 UTxO keeps its reference script hash; one without has none', () => {
  const hash = Serialization.Script.fromCore(script).hash();
  assert.equal(unspentOutput(withScript('aa'.repeat(32))).referenceScriptHash, hash);
  assert.equal(unspentOutput(unspentOutputCbor({ ref: 'bb'.repeat(32) + '#0', address, value })).referenceScriptHash, null);
});

test('walletView and fromWallet hand the script hash to the runtime', async () => {
  const api = {
    getNetworkId: async () => 0,
    getChangeAddress: async () => Cardano.Address.fromBech32(address).toBytes(),
    getUsedAddresses: async () => [Cardano.Address.fromBech32(address).toBytes()],
    getUtxos: async () => [withScript('aa'.repeat(32)), unspentOutputCbor({ ref: 'bb'.repeat(32) + '#0', address, value })],
  };
  const { walletUtxos } = fromWallet(await walletView(api));
  const byTx = Object.fromEntries(walletUtxos.map((u) => [u.txHash, u.referenceScriptHash]));
  assert.equal(byTx['aa'.repeat(32)], Serialization.Script.fromCore(script).hash());
  assert.equal(byTx['bb'.repeat(32)], null);
});
