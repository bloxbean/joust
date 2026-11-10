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

/** Moves the page's clock and the chain's tip past the record's last window by {@code extra} slots, then runs {@code f}. */
async function pastWindows(r, rec, extra, f) {
  const last = Math.max(...rec.attempts.map((a) => a.validTo));
  r.state.tip = last + extra;
  return later((last + extra - TIP) * 1000, f);
}

test('a signing error never removes an attempt (F82); a reload keeps it; "failed" ends a record without starting another (R-F7)', async () => {
  const r = rig();
  r.state.signError = 'the wallet bridge failed after signing';
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  r.state.signError = null;
  const [rec] = await r.store.all();
  assert.equal(rec.attempts.length, 1, 'the attempt stays though signing failed');
  // Still pending by the page's clock: a retry (after a reload) spends the same input, though a larger one arrived.
  r.state.extra.set(ref('a3'), { address: r.address, value: new Map([['lovelace', 900n * ADA]]) });
  r.truth.set(ref('a3'), { address: r.address, value: new Map([['lovelace', 900n * ADA]]) });
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  assert.equal(r.state.prepared.at(-1).designated, ref('a0'));
  // Every window has passed by the page's clock (less its 120 s margin) and the tip, and both the wallet and the chain
  // API still show the input: "failed" is reported, and that call starts nothing.
  const [rec2] = await r.store.all();
  const asked = r.state.prepared.length;
  await pastWindows(r, rec2, 130, () => assert.rejects(r.call(r.page()), /failed: .* none of its attempts can land; call again/));
  assert.equal(r.state.prepared.length, asked, 'the call that ended the operation prepared nothing');
  assert.deepEqual(await r.store.all(), []);
  // The next call is a new operation, designating as any first attempt does (the largest input now). (The test world's
  // fixed transaction is in the past by then, so the page refuses it; what matters is the input it asked for.)
  await pastWindows(r, rec2, 130, () => assert.rejects(r.call(r.page())));
  assert.equal(r.state.prepared.at(-1).designated, ref('a3'));
});

test('R-F7: after the windows, "failed" needs the wallet and the chain API both; otherwise "may have landed", and nothing more', async () => {
  // The attempt landed (the wallet no longer lists a0) but the chain API lies that a0 is unspent.
  const r = rig();
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  const [rec] = await r.store.all();
  r.state.spent.add(ref('a0'));
  r.truth.set(ref('a0x'), null);
  const lyingUtxosAt = r.chain.utxosAt;
  r.chain.utxosAt = async (a) => [...await lyingUtxosAt(a), { ref: ref('a0'), address: r.address, value: new Map([['lovelace', 200n * ADA]]) }];
  const asked = r.state.prepared.length;
  await pastWindows(r, rec, 130, () => assert.rejects(r.call(r.page()), /may have landed; check/));
  assert.equal(r.state.prepared.length, asked, 'no new attempt in the call that ended the operation');
  // Within the windows, neither answer matters: a gone input keeps the operation pending (the retry is refused).
  const r2 = rig();
  await assert.rejects(r2.call(r2.page()), PendingKeyedOperation);
  r2.state.spent.add(ref('a0'));
  await assert.rejects(r2.call(r2.page()), /designated input .* is not the wallet's/);
  assert.equal((await r2.store.all()).length, 1);
});

test('R-F8: a fast page clock with a matching lying tip cannot end a record early', async () => {
  const r = rig();
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  const [rec] = await r.store.all();
  const last = rec.attempts[0].validTo;
  // The real slot is last - 15 (the attempt can still land); the page's clock runs 100 s fast and the tip lies to match.
  r.state.tip = last - 15 + 100;
  await later((last - 15 + 100 - TIP) * 1000, () => assert.rejects(r.call(r.page()), PendingKeyedOperation));
  const [after] = await r.store.all();
  assert.equal(after.id, rec.id, 'the same operation');
  assert.ok(r.state.prepared.every((p) => p.designated === ref('a0')));
});

test('F87: an invalid slot clock keeps a record pending (it never expires) and nothing is deleted', async () => {
  const r = rig();
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  const [rec] = await r.store.all();
  for (const slotConfig of [{ zeroSlot: 0, slotLengthMs: 1000 }, { zeroSlot: 0, zeroTimeMs: 'bad', slotLengthMs: 1000 }, null]) {
    const k = new JoustContract({ description: DESCRIPTION, deployment: { ...DEPLOYMENT, slotConfig }, service: r.service, chain: r.chain, retryStore: r.store });
    r.state.tip = rec.attempts[0].validTo + 1000;
    await later(2_000_000, () => assert.rejects(r.call(k), /slot clock|PendingKeyedOperation|tip/));
    assert.equal((await r.store.all())[0]?.id, rec.id, `the record stays with slotConfig ${JSON.stringify(slotConfig)}`);
  }
});

test('F88: an abandon from the review, or a nested keyed call, does not deadlock; the lock is free afterwards', async () => {
  const r = rig();
  let k;
  k = r.page(r.service, { onReview: async () => { await k.abandonKeyed(r.api); } });
  const run = r.call(k);
  const outcome = await Promise.race([run.then(() => 'done', (e) => e.message), new Promise((res) => setTimeout(() => res('hung'), 3000))]);
  assert.match(outcome, /abandoned during review/);
  assert.deepEqual(await r.store.all(), [], 'nothing signed, nothing recorded');
  assert.equal(r.state.signed, 0);
  let nested;
  const k2 = r.page(r.service, { onReview: async () => { nested = await r.call(r.page()).then(() => 'done', (e) => e.constructor.name + ': ' + e.message); } });
  await assert.rejects(r.call(k2), PendingKeyedOperation);
  assert.match(nested, /KeyedCallInReview/);
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);   // the lock was released
});

test('F79, R-F3: two calls on one store take turns around signing; stale revisions refused; a record without a revision (F89) is retried', async () => {
  const r = rig();
  let release;
  const signing = new Promise((resolve) => { release = resolve; });
  let entered;
  const inSigning = new Promise((resolve) => { entered = resolve; });
  const realSign = r.api.signTx;
  r.api.signTx = async (cbor) => { if (r.state.signed === 0) { entered(); await signing; } return realSign(cbor); };
  const first = r.call(r.page());
  await inSigning;
  r.state.extra.set(ref('a3'), { address: r.address, value: new Map([['lovelace', 900n * ADA]]) });
  r.truth.set(ref('a3'), { address: r.address, value: new Map([['lovelace', 900n * ADA]]) });
  const second = r.call(r.page());
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.equal(r.state.prepared.length, 1, 'the second has not prepared while the first holds the lock');
  release();
  await assert.rejects(first, PendingKeyedOperation);
  await assert.rejects(second, PendingKeyedOperation);
  const all = await r.store.all();
  assert.equal(all.length, 1, 'one record');
  assert.equal(all[0].attempts.length, 2, 'both attempts recorded, none lost');
  assert.ok(r.state.prepared.every((p) => p.designated === ref('a0')), 'the second retried the first\'s operation with its input');
  await assert.rejects(r.store.put({ ...all[0], rev: all[0].rev }), /revision/);
  await assert.rejects(r.store.delete(all[0].id, all[0].rev - 1), /revision/);
  // A record written before revisions existed (never released, F89): it counts as revision 0, and is retried.
  const { rev: _rev, ...legacy } = all[0];
  const old = memoryRetryStore([legacy]);
  await assert.rejects(r.call(r.page(r.service, { retryStore: old })), PendingKeyedOperation);
  const [now] = await old.all();
  assert.equal(now.attempts.length, 3, 'the legacy record took a third attempt');
  assert.equal(now.rev, 1);
});
