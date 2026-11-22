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

test('a keyed operation never designates a UTxO carrying a reference script', async () => {
  const { designatedFor } = await import('../src/client.js');
  const wallet = { change: address, addresses: [address], utxos: new Map([
    ['aa'.repeat(32) + '#0', { address, value: new Map([['lovelace', 9_000_000n]]), referenceScriptHash: '44'.repeat(28) }],
    ['bb'.repeat(32) + '#0', { address, value: new Map([['lovelace', 6_000_000n]]) }],
  ]) };
  assert.equal(designatedFor(wallet).ref, 'bb'.repeat(32) + '#0');
  wallet.utxos.delete('bb'.repeat(32) + '#0');
  assert.equal(designatedFor(wallet), null);
});

test('with none at the funding address, a keyed operation designates an ordinary UTxO at another of its addresses', async () => {
  const { designatedFor, fromWallet } = await import('../src/client.js');
  const other = baseAddress({ type: 'key', hash: '55'.repeat(28) }, { type: 'key', hash: '22'.repeat(28) }, 0);
  const wallet = { change: address, addresses: [address, other], utxos: new Map([
    ['aa'.repeat(32) + '#0', { address, value: new Map([['lovelace', 9_000_000n]]), referenceScriptHash: '44'.repeat(28) }],
    ['bb'.repeat(32) + '#0', { address: other, value: new Map([['lovelace', 6_000_000n]]) }],
  ]) };
  const d = designatedFor(wallet);
  assert.deepEqual([d.ref, d.address], ['bb'.repeat(32) + '#0', other]);
  // The engine builds from that address.
  assert.equal(fromWallet(wallet, d.address).address, other);
  assert.deepEqual(fromWallet(wallet, d.address).walletUtxos.map((u) => u.txHash), ['bb'.repeat(32)]);
});

test('the DevKit wallet leaves out a UTxO carrying a reference script and reports a datum hash', async () => {
  const { chainOf } = await import('../src/chain.js');
  const { devWallet } = await import('../src/devwallet.js');
  const listed = [];
  const provider = { utxos: async (a) => listed.map((u) => ({ ...u, address: a })) };
  const wallet = devWallet({ chain: chainOf(provider) });
  const mine = wallet.address;
  listed.push({ txHash: 'aa'.repeat(32), index: 0, value: [{ unit: 'lovelace', quantity: '9000000' }], datumHash: null,
    inlineDatum: null, referenceScriptHash: '44'.repeat(28) });
  listed.push({ txHash: 'bb'.repeat(32), index: 0, value: [{ unit: 'lovelace', quantity: '6000000' }], datumHash: '66'.repeat(32),
    inlineDatum: null, referenceScriptHash: null });
  listed.push({ txHash: 'cc'.repeat(32), index: 0, value: [{ unit: 'lovelace', quantity: '5000000' }], datumHash: null,
    inlineDatum: null, referenceScriptHash: null });
  const seen = (await wallet.getUtxos()).map(unspentOutput);
  assert.deepEqual(seen.map((u) => u.ref), ['bb'.repeat(32) + '#0', 'cc'.repeat(32) + '#0']);
  assert.equal(seen[0].datumHash, '66'.repeat(32));
  assert.ok(mine);
});

test('an inline datum 00 survives the CIP-30 encoding, and a keyed operation does not designate it', async () => {
  const { designatedFor } = await import('../src/client.js');
  const u = unspentOutput(unspentOutputCbor({ ref: 'dd'.repeat(32) + '#0', address, value: new Map([['lovelace', 7_000_000n]]), inlineDatum: '00' }));
  assert.equal(u.inlineDatum, '00');
  const wallet = { change: address, addresses: [address], utxos: new Map([['dd'.repeat(32) + '#0', u]]) };
  assert.equal(designatedFor(wallet), null);
});

test('Ogmios: a native reference script is seen, and the DevKit wallet leaves its UTxO out', async () => {
  const { ogmiosProvider } = await import('../src/providers.js');
  const { chainOf } = await import('../src/chain.js');
  const { devWallet } = await import('../src/devwallet.js');
  // A native script (RequireSignature of a key hash): its CBOR, as Ogmios gives it.
  const native = Serialization.NativeScript.fromCore({ __type: Cardano.ScriptType.Native, kind: Cardano.NativeScriptKind.RequireSignature,
    keyHash: '77'.repeat(28) });
  const nativeCbor = native.toCbor();
  let mine;
  const out = (id, ada, extra = {}) => ({ transaction: { id: id.repeat(32) }, index: 0, address: mine, value: { ada: { lovelace: ada } }, ...extra });
  const fetchImpl = async (_url, init) => {
    const { method, id } = JSON.parse(init.body);
    const result = method === 'queryLedgerState/utxo'
      ? [out('ee', 7_000_000, { script: { language: 'native', json: {}, cbor: nativeCbor } }),
        out('ef', 7_000_000, { script: { language: 'native', json: {} } }),
        out('f0', 6_000_000)]
      : null;
    return { status: 200, text: async () => JSON.stringify({ jsonrpc: '2.0', id, result }) };
  };
  const provider = ogmiosProvider('http://ogmios.test', { fetchImpl });
  const wallet = devWallet({ chain: chainOf(provider) });
  mine = wallet.address;
  const listed = await provider.utxos(mine);
  assert.equal(listed[0].referenceScriptHash, Serialization.Script.fromCore(native.toCore()).hash());
  assert.equal(listed[1].hasReferenceScript, true);
  const seen = (await wallet.getUtxos()).map(unspentOutput).map((u) => u.ref);
  assert.deepEqual(seen, ['f0'.repeat(32) + '#0']);
});
