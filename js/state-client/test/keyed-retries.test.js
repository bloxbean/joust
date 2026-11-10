// ADR-0016 D11 and the page's form of D7 (retries.js): retries through a page. Before the first attempt of a keyed
// operation the page designates a wallet input and records it; it signs a retry only when the retry spends that input,
// after a reload too, until every attempt's window has passed by the page's own clock, or the user abandons it. The
// chain API's answers (its tip, an input's spentness) never move the designated input (M3 review: F78, R-F2, F79,
// R-F3, F82). Here with a CIP-30 wallet whose key the test holds, services that may turn hostile, and a chain view the
// test controls (it may lie).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import CSL from '@emurgo/cardano-serialization-lib-nodejs';
import { JoustContract, memoryRetryStore, PendingKeyedOperation, planOf, txHash as hashOf } from '../src/index.js';
import { dataHex } from '../src/pdata.js';
import { world, build, actionScn, DESCRIPTION, ADA, PARAMS, I, ref, deployment as DEPLOYMENT, TIP } from './keyed-world.js';

// The wallet's signing key: the page checks no witness, only that signing keeps the body (and its hash).
const KEY = CSL.PrivateKey.from_normal_bytes(new Uint8Array(32).fill(7));

/** A page world: the action on Entry "aa", a chain view (which can lie), a wallet, a service and a shared store. */
function rig() {
  const w = world();
  const scns = { a0: actionScn(w, { d: ref('a0') }), a1: actionScn(w, { d: ref('a1') }), a3: actionScn(w, { d: ref('a3') }) };
  const truth = scns.a0.truth;
  const address = [...scns.a0.wallet.utxos.values()][0].address;
  const state = { spent: new Set(), tip: TIP, lieTip: null, lieSpent: new Set(), extra: new Map(), mode: 'honest', signed: 0, submits: 0,
    prepared: [], submit: async () => { throw new Error('submit failed: rejected'); } };
  const chain = {
    tip: async () => state.lieTip ?? state.tip,
    params: async () => PARAMS,
    utxo: async (r) => {
      const u = truth.get(r);
      return u && { address: u.address, value: new Map(u.value), inlineDatum: u.datum ? dataHex(u.datum) : null, referenceScriptHash: u.referenceScriptHash ?? null };
    },
    utxosAt: async (a) => [...truth].filter(([r, u]) => u.address === a && !state.spent.has(r) && !state.lieSpent.has(r))
      .map(([r, u]) => ({ ref: r, address: u.address, value: u.value })),
    awaitTx: async () => true,
  };
  const service = {
    async prepare(req) {
      state.prepared.push(req);
      // honest: builds what the page asked for; hostile: spends another wallet input than the designated one.
      const scn = state.mode === 'honest' ? scns[req.designated === ref('a0') ? 'a0' : req.designated === ref('a3') ? 'a3' : 'a1'] : scns.a1;
      const cbor = build(w, structuredClone(scn.spec), truth);
      return { cbor, plan: planOf(cbor) };
    },
  };
  const witness = (cbor) => {
    const ws = CSL.TransactionWitnessSet.new();
    const vk = CSL.Vkeywitnesses.new();
    vk.add(CSL.make_vkey_witness(CSL.TransactionHash.from_hex(hashOf(cbor)), KEY));
    ws.set_vkeys(vk);
    return ws.to_hex();
  };
  const utxoHex = (r, u) => CSL.TransactionUnspentOutput.new(
    CSL.TransactionInput.new(CSL.TransactionHash.from_hex(r.split('#')[0]), Number(r.split('#')[1])),
    CSL.TransactionOutput.new(CSL.Address.from_bech32(u.address), CSL.Value.new(CSL.BigNum.from_str(String(u.value.get('lovelace')))))).to_hex();
  const api = {
    getNetworkId: async () => 0,
    getChangeAddress: async () => CSL.Address.from_bech32(address).to_hex(),
    getUsedAddresses: async () => [],
    getUtxos: async () => [...scns.a0.wallet.utxos, ...state.extra].filter(([r]) => !state.spent.has(r)).map(([r, u]) => utxoHex(r, u)),
    signTx: async (cbor) => {
      state.signed++;
      if (state.signError) throw new Error(state.signError);
      return witness(cbor);
    },
    submitTx: async (cbor) => { state.submits++; return state.submit(cbor); },
  };
  const store = memoryRetryStore();
  const page = (svc = service, extra = {}) => new JoustContract({ description: DESCRIPTION, deployment: DEPLOYMENT, service: svc, chain, retryStore: store, ...extra });
  const call = (k, opts) => k.at(I).keyed('Entry').object('aa').call(api, 'upgrade', { tier: 2n }, { lovelace: 10n * ADA }, opts);
  return { w, truth, state, chain, service, api, store, page, call, address };
}

/** Runs {@code f} with Date.now() moved {@code ms} ahead (the page's own clock). */
async function later(ms, f) {
  const real = Date.now;
  Date.now = () => real() + ms;
  try {
    return await f();
  } finally {
    Date.now = real;
  }
}

test('every attempt spends the first attempt\'s designated input: a hostile retry is refused, after a reload too', async () => {
  const r = rig();
  // 1. The first attempt is signed and handed to the wallet, which says "rejected" (it may still land): pending.
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  assert.equal(r.state.signed, 1);
  assert.equal(r.state.prepared[0].designated, ref('a0'), 'the page designated the largest ADA-only input');
  const [rec] = await r.store.all();
  assert.equal(rec.designated, ref('a0'));
  assert.equal(rec.attempts.length, 1);
  // 2. A hostile service's retry that does not spend the designated input is refused before the wallet signs.
  r.state.mode = 'hostile';
  await assert.rejects(r.call(r.page()), /does not spend the operation's designated input/);
  assert.equal(r.state.signed, 1, 'the wallet was not asked');
  assert.equal(r.state.prepared.at(-1).designated, ref('a0'), 'the retry asked for the same input');
  // 3. After a reload (a new page over the same persisted store): the same.
  await assert.rejects(r.call(r.page()), /does not spend the operation's designated input/);
  assert.equal(r.state.signed, 1, 'the wallet was not asked after the reload either');
  assert.equal((await r.store.all())[0].attempts.length, 1, 'the record survives the refusals');
  // 4. An honest retry spends it, and is confirmed: the record goes.
  r.state.mode = 'honest';
  r.state.submit = async (cbor) => { r.state.spent.add(ref('a0')); return hashOf(cbor); };
  const done = await r.call(r.page());
  assert.ok(done.txHash);
  assert.equal(r.state.signed, 2);
  assert.deepEqual(await r.store.all(), [], 'confirmed: no record');
});

test('the chain API cannot move the designated input: a lying tip, a lying "spent", a new larger wallet input (F78, R-F2)', async () => {
  const r = rig();
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  const [rec] = await r.store.all();
  // A new, larger ordinary UTxO arrives in the wallet: a fresh operation would designate it.
  r.state.extra.set(ref('a3'), { address: r.address, value: new Map([['lovelace', 900n * ADA]]) });
  r.truth.set(ref('a3'), { address: r.address, value: new Map([['lovelace', 900n * ADA]]) });
  // The provider claims a tip far past the attempt's window, then that the designated input is spent.
  r.state.lieTip = rec.attempts[0].validTo + 1000;
  await assert.rejects(r.call(r.page()), /the chain API's tip/);
  r.state.lieTip = null;
  r.state.lieSpent.add(ref('a0'));
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  assert.ok(r.state.prepared.every((p) => p.designated === ref('a0')), 'every attempt asked for the first designated input');
  const [after] = await r.store.all();
  assert.equal(after.id, rec.id, 'the same record');
  assert.equal(after.designated, ref('a0'));
  assert.equal(after.attempts.length, 2, 'the retry was another attempt of the same operation');
});

test('a signing error never removes an attempt (F82); a reload keeps it; expiry is by the page\'s own clock', async () => {
  const r = rig();
  r.state.signError = 'the wallet bridge failed after signing';
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  r.state.signError = null;
  const [rec] = await r.store.all();
  assert.equal(rec.attempts.length, 1, 'the attempt stays though signing failed');
  // Still pending by the page's clock: a retry (after a reload) spends the same input.
  r.state.extra.set(ref('a3'), { address: r.address, value: new Map([['lovelace', 900n * ADA]]) });
  r.truth.set(ref('a3'), { address: r.address, value: new Map([['lovelace', 900n * ADA]]) });
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  assert.equal(r.state.prepared.at(-1).designated, ref('a0'));
  // Once every window has passed by the page's clock (and the provider's tip agrees), with the input unspent: failed.
  // The next call is a new operation, on the same input since the wallet still has it.
  const [rec2] = await r.store.all();
  const passMs = (rec2.attempts.at(-1).validTo - TIP + 40) * 1000;
  r.state.tip = rec2.attempts.at(-1).validTo + 40;
  await later(passMs, () => assert.rejects(r.call(r.page()), PendingKeyedOperation));
  const [rec3] = await r.store.all();
  assert.notEqual(rec3?.id, rec2.id, 'the expired record ended');
  assert.equal(r.state.prepared.at(-1).designated, ref('a0'), 'the new operation reuses the unspent input, not the larger one');
});

test('a record whose windows passed and whose input is gone is reported once, and abandoning is explicit', async () => {
  const r = rig();
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  const [rec] = await r.store.all();
  // The input is gone, but the windows have not passed by the page's clock: still a retry of the same operation.
  r.state.spent.add(ref('a0'));
  await assert.rejects(r.call(r.page()), /designated input .* is not the wallet's/);
  assert.equal((await r.store.all())[0].id, rec.id);
  // After the windows (page clock), it is reported once: the operation may have landed.
  r.state.tip = rec.attempts[0].validTo + 40;
  await later((rec.attempts[0].validTo - TIP + 40) * 1000, () => assert.rejects(r.call(r.page()), /may have landed; check/));
  assert.deepEqual(await r.store.all(), []);
  // Abandoning while pending: the user's explicit choice starts a new operation.
  const r2 = rig();
  await assert.rejects(r2.call(r2.page()), PendingKeyedOperation);
  const [p] = await r2.store.all();
  await assert.rejects(r2.call(r2.page(), { abandon: true }), PendingKeyedOperation);
  assert.notEqual((await r2.store.all())[0].id, p.id);
});

test('two calls (or tabs) on one store take turns: the second waits for the first\'s lock and retries its record (F79, R-F3)', async () => {
  const r = rig();
  let release;
  const reviewing = new Promise((resolve) => { release = resolve; });
  let entered;
  const inReview = new Promise((resolve) => { entered = resolve; });
  const a = r.page(r.service, { onReview: async () => { entered(); await reviewing; } });
  const b = r.page();
  const first = r.call(a);
  await inReview;
  // While A reviews (before its attempt is recorded), B starts: it must not act on A's record.
  r.state.extra.set(ref('a3'), { address: r.address, value: new Map([['lovelace', 900n * ADA]]) });
  r.truth.set(ref('a3'), { address: r.address, value: new Map([['lovelace', 900n * ADA]]) });
  const second = r.call(b);
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(r.state.prepared.length, 1, 'B has not prepared while A holds the lock');
  release();
  await assert.rejects(first, PendingKeyedOperation);
  await assert.rejects(second, PendingKeyedOperation);
  const all = await r.store.all();
  assert.equal(all.length, 1, 'one record');
  assert.equal(all[0].attempts.length, 2, 'both attempts recorded, none lost');
  assert.ok(r.state.prepared.every((p) => p.designated === ref('a0')), 'B retried A\'s operation with its input');
  // The store refuses a stale revision (a writer that did not hold the lock).
  await assert.rejects(r.store.put({ ...all[0], rev: all[0].rev }), /revision/);
  await assert.rejects(r.store.delete(all[0].id, all[0].rev - 1), /revision/);
});
