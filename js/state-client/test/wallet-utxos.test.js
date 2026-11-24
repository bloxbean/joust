// #165 Codex round 2: a CIP-30 wallet UTxO carrying a reference script keeps its script hash on the way to the runtime,
// so the runtime never spends it for the tokens it holds (it would consume a deployment's script).
import test from 'node:test';
import assert from 'node:assert/strict';
import { Serialization, Cardano } from '@cardano-sdk/core';
import { unspentOutput, unspentOutputCbor, baseAddress } from '../src/ledger.js';
const lovelace = (q) => [{ unit: 'lovelace', quantity: String(q) }];
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
  // Another address of the same payment key (another stake key): the caller stays the same (#165 Codex round 7).
  const other = baseAddress({ type: 'key', hash: '11'.repeat(28) }, { type: 'key', hash: '55'.repeat(28) }, 0);
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

test('the fallback designation keeps the caller: only another address with the same payment key', async () => {
  const { designatedFor, fromWallet } = await import('../src/client.js');
  const { paymentKeyHash } = await import('../src/wallet.js');
  // A: the change address (key 11), holding only a token UTxO. B: another key (88) with ADA. A2: key 11, another stake key.
  const b = baseAddress({ type: 'key', hash: '88'.repeat(28) }, { type: 'key', hash: '22'.repeat(28) }, 0);
  const a2 = baseAddress({ type: 'key', hash: '11'.repeat(28) }, { type: 'key', hash: '99'.repeat(28) }, 0);
  const token = new Map([['lovelace', 2_000_000n], ['33'.repeat(28) + '01', 1n]]);
  const wallet = { change: address, addresses: [address, b], utxos: new Map([
    ['aa'.repeat(32) + '#0', { address, value: token }],
    ['bb'.repeat(32) + '#0', { address: b, value: new Map([['lovelace', 9_000_000n]]) }],
  ]) };
  // An init through B would record key 88 as admin, and the next ordinary call (from A) signs with key 11.
  assert.equal(designatedFor(wallet), null);
  wallet.addresses.push(a2);
  wallet.utxos.set('cc'.repeat(32) + '#0', { address: a2, value: new Map([['lovelace', 6_000_000n]]) });
  const d = designatedFor(wallet);
  assert.equal(d.address, a2);
  // The keyed call (from the designated address) and an ordinary call (from the funding address) sign with one key.
  assert.equal(paymentKeyHash(fromWallet(wallet, d.address).address), paymentKeyHash(fromWallet(wallet).address));
});

test('settling a keyed record: a provider spelling the id in upper case still shows the designated input', async () => {
  const { chainOf } = await import('../src/chain.js');
  const { settle } = await import('../src/retries.js');
  const upper = 'AB'.repeat(32);
  const provider = { tip: async () => ({ slot: 10_000 }),
    utxos: async () => [{ txHash: upper, index: 0, address, value: lovelace(9_000_000), datumHash: null, inlineDatum: null, referenceScriptHash: null }] };
  const chain = chainOf(provider);
  assert.equal((await chain.utxosAt(address))[0].ref, 'ab'.repeat(32) + '#0');
  const record = { designated: 'ab'.repeat(32) + '#0', address, attempts: [{ txHash: 'cd'.repeat(32), validTo: 100 }] };
  const wallet = { utxos: new Map([['ab'.repeat(32) + '#0', { address, value: new Map([['lovelace', 9_000_000n]]) }]]) };
  const s = await settle(record, chain, { clock: { zeroSlot: 0, zeroTimeMs: 0, slotLengthMs: 1000 }, wallet, nowMs: 10_000_000 });
  assert.equal(s.outcome, 'failed');
});

test('Ogmios: a script in the Plutus language JOUST compiles to (V3) gets its hash', async () => {
  const { ogmiosProvider, plutusScriptHash } = await import('../src/providers.js');
  const cbor = '4e4d01000033222220051200120011';
  const fetchImpl = async (_url, init) => {
    const { id } = JSON.parse(init.body);
    return { status: 200, text: async () => JSON.stringify({ jsonrpc: '2.0', id, result: [{ transaction: { id: 'aa'.repeat(32) }, index: 0,
      address, value: { ada: { lovelace: 9_000_000 } }, script: { language: 'plutus:v3', cbor } }] }) };
  };
  const [u] = await ogmiosProvider('http://ogmios.test', { fetchImpl }).utxos(address);
  // Keep ogmiosProvider's language map in step with JOUST's Plutus version: an unmapped one gets no hash.
  assert.equal(u.referenceScriptHash, plutusScriptHash(cbor, 3));
});
