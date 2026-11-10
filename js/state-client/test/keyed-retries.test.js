// ADR-0016 D11: retries through a page. Before the first attempt of a keyed operation the page designates a wallet
// input and records it; it signs a retry only when the retry spends that input, also after a reload (the record is
// persisted), until the operation is settled or the user abandons it. Here with a CIP-30 wallet whose key is held by
// the test, a service that may turn hostile, and a chain view that the test controls.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import CSL from '@emurgo/cardano-serialization-lib-nodejs';
import { JoustContract, memoryRetryStore, PendingKeyedOperation, planOf, txHash as hashOf } from '../src/index.js';
import { world, build, actionScn, DESCRIPTION, ADA, PARAMS, I, ref, deployment as DEPLOYMENT, TIP } from './keyed-world.js';

// The wallet's signing key: the page checks no witness, only that signing keeps the body (and its hash).
const KEY = CSL.PrivateKey.from_normal_bytes(new Uint8Array(32).fill(7));

test('retries: every attempt spends the first attempt\'s designated input, after a reload too; settled records go', async () => {
  const w = world();
  // The designated input the page picks is the largest ADA-only UTxO at the funding address: a0 (200 ADA).
  const honest = actionScn(w, { d: ref('a0') });
  const hostile = actionScn(w, { d: ref('a1') });   // a retry that spends another wallet input instead
  const truth = honest.truth;
  const address = [...honest.wallet.utxos.values()][0].address;
  const spent = new Set();
  let tip = TIP;
  const chain = {
    tip: async () => tip,
    params: async () => PARAMS,
    utxo: async (r) => {
      const u = truth.get(r);
      return u && { address: u.address, value: new Map(u.value), inlineDatum: u.datum ? (await import('../src/pdata.js')).dataHex(u.datum) : null,
        referenceScriptHash: u.referenceScriptHash ?? null };
    },
    utxosAt: async (a) => [...truth].filter(([r, u]) => u.address === a && !spent.has(r)).map(([r, u]) => ({ ref: r, address: u.address, value: u.value })),
    awaitTx: async () => true,
    findUnit: async () => null,
  };
  chain.verifiedOutput = async (r) => chain.utxo(r);
  let mode = 'honest';
  const prepared = [];
  const service = {
    async prepare(req) {
      prepared.push(req);
      const scn = mode === 'honest' ? honest : hostile;
      const cbor = build(w, structuredClone(scn.spec), truth);
      return { cbor, plan: planOf(cbor) };
    },
  };
  // The CIP-30 wallet: its UTxOs, a key the verifier knows as the wallet's (WK in the world), signing with KEY.
  let signed = 0;
  let submit = async () => { throw new Error('submit failed: rejected'); };   // a relay that lies "rejected"
  const api = {
    getNetworkId: async () => 0,
    getChangeAddress: async () => CSL.Address.from_bech32(address).to_hex(),
    getUsedAddresses: async () => [],
    getUtxos: async () => [...honest.wallet.utxos].filter(([r]) => !spent.has(r)).map(([r, u]) => CSL.TransactionUnspentOutput.new(
      CSL.TransactionInput.new(CSL.TransactionHash.from_hex(r.split('#')[0]), Number(r.split('#')[1])),
      CSL.TransactionOutput.new(CSL.Address.from_bech32(u.address), CSL.Value.new(CSL.BigNum.from_str(String(u.value.get('lovelace')))))).to_hex()),
    signTx: async (cbor) => {
      signed++;
      const ws = CSL.TransactionWitnessSet.new();
      const vk = CSL.Vkeywitnesses.new();
      vk.add(CSL.make_vkey_witness(CSL.TransactionHash.from_hex(hashOf(cbor)), KEY));
      ws.set_vkeys(vk);
      return ws.to_hex();
    },
    submitTx: async (cbor) => submit(cbor),
  };
  const store = memoryRetryStore();
  const contract = (svc = service) => new JoustContract({ description: DESCRIPTION, deployment: { ...DEPLOYMENT, slotConfig: undefined },
    service: svc, chain, retryStore: store });
  const call = (k) => k.at(I).keyed('Entry').object('aa').call(api, 'upgrade', { tier: 2n }, { lovelace: 10n * ADA });

  // 1. The first attempt is signed and handed to the wallet, which says "rejected" (it may still land): pending.
  await assert.rejects(call(contract()), PendingKeyedOperation);
  assert.equal(signed, 1);
  assert.equal(prepared[0].designated, ref('a0'), 'the page designated the largest ADA-only input');
  const [rec] = await store.all();
  assert.equal(rec.designated, ref('a0'));
  assert.equal(rec.attempts.length, 1);

  // 2. A hostile service's retry that does not spend the designated input is refused before the wallet signs.
  mode = 'hostile';
  await assert.rejects(call(contract()), /does not spend the operation's designated input/);
  assert.equal(signed, 1, 'the wallet was not asked');
  assert.equal(prepared.at(-1).designated, ref('a0'), 'the retry asked for the same input');

  // 3. After a reload (a new page over the same persisted store): the same.
  await assert.rejects(call(contract()), /does not spend the operation's designated input/);
  assert.equal(signed, 1, 'the wallet was not asked after the reload either');
  assert.equal((await store.all())[0].attempts.length, 1, 'the record survives the refusals');

  // 4. An honest retry spends it: signed, and this time delivered; the operation is settled and its record goes.
  mode = 'honest';
  submit = async (cbor) => {
    spent.add(ref('a0'));   // the attempt landed: the designated input is spent
    return hashOf(cbor);
  };
  const r = await call(contract());
  assert.equal(signed, 2);
  assert.ok(r.txHash);
  assert.deepEqual(await store.all(), [], 'settled: no record');
});

test('retries: a record settled by its spent input is reported once; an expired one goes; abandoning is explicit', async () => {
  const w = world();
  const honest = actionScn(w, { d: ref('a0') });
  const truth = honest.truth;
  const address = [...honest.wallet.utxos.values()][0].address;
  const spent = new Set();
  let tip = TIP;
  const { dataHex } = await import('../src/pdata.js');
  const chain = {
    tip: async () => tip,
    params: async () => PARAMS,
    utxo: async (r) => {
      const u = truth.get(r);
      return u && { address: u.address, value: new Map(u.value), inlineDatum: u.datum ? dataHex(u.datum) : null, referenceScriptHash: u.referenceScriptHash ?? null };
    },
    utxosAt: async (a) => [...truth].filter(([r, u]) => u.address === a && !spent.has(r)).map(([r, u]) => ({ ref: r, address: u.address, value: u.value })),
    awaitTx: async () => true,
  };
  chain.verifiedOutput = async (r) => chain.utxo(r);
  const service = { prepare: async () => { const cbor = build(w, structuredClone(honest.spec), truth); return { cbor, plan: planOf(cbor) }; } };
  let submits = 0;
  const api = {
    getNetworkId: async () => 0,
    getChangeAddress: async () => CSL.Address.from_bech32(address).to_hex(),
    getUsedAddresses: async () => [],
    getUtxos: async () => [...honest.wallet.utxos].map(([r, u]) => CSL.TransactionUnspentOutput.new(
      CSL.TransactionInput.new(CSL.TransactionHash.from_hex(r.split('#')[0]), Number(r.split('#')[1])),
      CSL.TransactionOutput.new(CSL.Address.from_bech32(u.address), CSL.Value.new(CSL.BigNum.from_str(String(u.value.get('lovelace')))))).to_hex()),
    signTx: async (cbor) => {
      const ws = CSL.TransactionWitnessSet.new();
      const vk = CSL.Vkeywitnesses.new();
      vk.add(CSL.make_vkey_witness(CSL.TransactionHash.from_hex(hashOf(cbor)), KEY));
      ws.set_vkeys(vk);
      return ws.to_hex();
    },
    submitTx: async () => { submits++; throw new Error('timeout'); },
  };
  const store = memoryRetryStore();
  const k = new JoustContract({ description: DESCRIPTION, deployment: { ...DEPLOYMENT, slotConfig: undefined }, service, chain, retryStore: store });
  const call = (opts) => k.at(I).keyed('Entry').object('aa').call(api, 'upgrade', { tier: 2n }, { lovelace: 10n * ADA }, opts);

  // Pending, then the designated input is spent by something: settled, reported once, then a new operation may start.
  await assert.rejects(call(), PendingKeyedOperation);
  spent.add(ref('a0'));
  await assert.rejects(call(), /is settled: its designated input .* is spent/);
  assert.deepEqual(await store.all(), []);
  spent.clear();

  // Pending, then every attempt's window passes with the input unspent: settled silently (nothing can land).
  await assert.rejects(call(), PendingKeyedOperation);
  const [rec] = await store.all();
  tip = rec.attempts[0].validTo + 3;
  await assert.rejects(call(), PendingKeyedOperation);   // a new operation (its own first attempt), pending again
  assert.equal((await store.all()).length, 1);
  assert.notEqual((await store.all())[0].id, rec.id, 'the expired record was replaced by the new operation\'s');

  // While pending, a new operation starts only by the user's explicit choice.
  const before = (await store.all())[0].id;
  await assert.rejects(call({ abandon: true }), PendingKeyedOperation);
  assert.notEqual((await store.all())[0].id, before);
  assert.equal(submits, 4);
});
