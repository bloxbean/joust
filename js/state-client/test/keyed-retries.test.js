// ADR-0016 D11 and the page's form of D7 (retries.js): retries through a page. Before the first attempt of a keyed
// operation the page designates a wallet input and records it; it signs a retry only when the retry spends that input,
// after a reload too, until every attempt's window has passed by the page's own clock, or the user abandons it. The
// chain API's answers (its tip, an input's spentness) never move the designated input (M3 review: F78, R-F2, F79,
// R-F3, F82). Here with a CIP-30 wallet whose key the test holds, services that may turn hostile, and a chain view the
// test controls (it may lie).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import CSL from '@emurgo/cardano-serialization-lib-nodejs';
import { createServer } from 'node:http';
import { servePrepare, prepareErrorBody } from '../../joust/src/http-prepare.js';
import { JoustContract, httpService, memoryRetryStore, PendingKeyedOperation, KeyedCallInProgress, settle, planOf, txHash as hashOf } from '../src/index.js';
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

const barrier = () => {
  let release;
  let reached;
  const gate = new Promise((r) => { release = r; });
  const at = new Promise((r) => { reached = r; });
  return { release, at, wait: async () => { reached(); await gate; } };
};

test('F90: a keyed call is never queued: a second call while one is in flight is refused at once (settlement and confirmation)', async () => {
  // A retry is held while it ends the record (its settlement reads the tip); a second retry is refused, not queued.
  const r = rig();
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  const [rec] = await r.store.all();
  r.state.spent.add(ref('a0'));
  r.state.extra.set(ref('a3'), { address: r.address, value: new Map([['lovelace', 900n * ADA]]) });
  r.truth.set(ref('a3'), { address: r.address, value: new Map([['lovelace', 900n * ADA]]) });
  const last = rec.attempts[0].validTo;
  r.state.tip = last + 130;
  await later((last + 130 - TIP) * 1000, async () => {
    const b = barrier();
    const tip = r.chain.tip;
    r.chain.tip = async () => { await b.wait(); return tip(); };
    const a = r.call(r.page());
    await b.at;
    await assert.rejects(r.call(r.page()), KeyedCallInProgress, 'the second retry is refused while the first is in flight');
    b.release();
    r.chain.tip = tip;
    await assert.rejects(a, /may have landed; check, then acknowledge/);
    // A blind retry after that outcome is refused by the tombstone until the application acknowledges it.
    await assert.rejects(r.call(r.page()), /may have landed; check, then acknowledge/);
    assert.ok(r.state.prepared.every((p) => p.designated === ref('a0')), 'no second designated input');
    const ack = await r.page().acknowledgeKeyed(r.api);
    assert.equal(ack.ended, 'may have landed');
    assert.deepEqual(await r.store.all(), []);
  });
  // Confirmation: while a call waits for awaitTx, another is refused at once (it is not queued behind the success).
  const r2 = rig();
  const b2 = barrier();
  r2.state.submit = async (cbor) => hashOf(cbor);
  r2.chain.awaitTx = async () => { await b2.wait(); return true; };
  const first = r2.call(r2.page());
  await b2.at;
  await assert.rejects(r2.call(r2.page()), KeyedCallInProgress);
  b2.release();
  await first;
  assert.deepEqual(await r2.store.all(), []);
  assert.equal(r2.state.signed, 1);
});

test('F92: a review awaiting a call made while the first was preparing does not deadlock (that call was refused)', async () => {
  const r = rig();
  const b = barrier();
  const prepare = r.service.prepare;
  let second;
  const svc = { prepare: async (req) => { if (!second) await b.wait(); return prepare(req); } };
  const a = r.call(r.page(svc, { onReview: async () => { await second.catch(() => {}); } }));
  await b.at;
  second = r.call(r.page());
  await assert.rejects(second, KeyedCallInProgress);
  b.release();
  const outcome = await Promise.race([a.then(() => 'done', (e) => e.constructor.name), new Promise((res) => setTimeout(() => res('hung'), 3000))]);
  assert.equal(outcome, 'PendingKeyedOperation');
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);   // free afterwards: a retry runs
});

test('F93, R-F13: abandon during review deletes the reviewing call\'s record (signed attempts included), whether the callback returns or throws', async () => {
  for (const throws of [false, true]) {
    const r = rig();
    await assert.rejects(r.call(r.page()), PendingKeyedOperation);
    const [rec] = await r.store.all();
    let k;
    let got;
    k = r.page(r.service, { onReview: async () => { got = await k.abandonKeyed(r.api); if (throws) throw new Error('cancelled'); } });
    await assert.rejects(r.call(k), /abandoned during review; an earlier signed attempt .* may still land/);
    assert.equal(got.id, rec.id);
    assert.deepEqual(await r.store.all(), [], `deleted (callback ${throws ? 'throws' : 'returns'})`);
    assert.equal(r.state.signed, 1, 'nothing more signed');
  }
  // A nested keyed call from the review is refused at once; the lock is free afterwards.
  const r = rig();
  let nested;
  const k2 = r.page(r.service, { onReview: async () => { nested = await r.call(r.page()).then(() => 'done', (e) => e.constructor.name); } });
  await assert.rejects(r.call(k2), PendingKeyedOperation);
  assert.equal(nested, 'KeyedCallInProgress');
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
});

test('F91, R-F15: settlement uses the page\'s own tip tolerance and slot length', async () => {
  const r = rig();
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  const [rec] = await r.store.all();
  const last = rec.attempts[0].validTo;
  // A page tolerating 300 slots; its clock 200 s fast, the tip lying to match; the real chain at last - 15.
  r.state.tip = last - 15 + 200;
  const k = r.page(r.service, { limits: { maxTipSkewSlots: 300 } });
  // (The test world's fixed transaction is backdated by then and refused; what matters is the record and the input.)
  await later((last - 15 + 200 - TIP) * 1000, () => assert.rejects(r.call(k)));
  assert.equal((await r.store.all())[0].id, rec.id, 'still the same operation');
  assert.equal(r.state.prepared.at(-1).designated, ref('a0'), 'a retry of it');
  // Directly: 100 ms slots, the margin follows them (300 slots = 30 s), and a deadline of null is unknown (R-F14).
  const clock = { zeroSlot: 0, zeroTimeMs: Date.now() - 1_000_000, slotLengthMs: 100 };
  const now = 10_000;
  const chain = { tip: async () => now, utxosAt: async () => [] };
  const one = (validTo) => ({ designated: 'aa#0', address: 'x', attempts: [{ txHash: 'h', validTo }] });
  assert.equal((await settle(one(now - 400 + 300 - 3), chain, { clock, maxTipSkewSlots: 300 })).outcome, 'pending');
  assert.equal((await settle(one(null), chain, { clock })).outcome, 'pending');
});

test('F79, R-F3: stale revisions are refused; a record without a revision (F89) is retried', async () => {
  const r = rig();
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  const all = await r.store.all();
  assert.equal(all.length, 1, 'one record');
  assert.equal(all[0].attempts.length, 2, 'both attempts recorded, none lost');
  await assert.rejects(r.store.put({ ...all[0], rev: all[0].rev }), /revision/);
  await assert.rejects(r.store.delete(all[0].id, all[0].rev - 1), /revision/);
  const { rev: _rev, ...legacy } = all[0];
  const old = memoryRetryStore([legacy]);
  await assert.rejects(r.call(r.page(r.service, { retryStore: old })), PendingKeyedOperation);
  const [now] = await old.all();
  assert.equal(now.attempts.length, 3, 'the legacy record took a third attempt');
  assert.equal(now.rev, 1);
});

test('F95: the slot is reserved at entry, before the wallet\'s reads: a call whose wallet read is held while another confirms is refused', async () => {
  const r = rig();
  r.state.submit = async (cbor) => hashOf(cbor);
  const confirm = barrier();
  r.chain.awaitTx = async () => { await confirm.wait(); return true; };
  const a = r.call(r.page());
  await confirm.at;
  // B's wallet read is held; B is refused at once all the same (it never reached its wallet).
  const held = barrier();
  const slowApi = { ...r.api, getNetworkId: async () => { await held.wait(); return 0; } };
  const b = r.page().at(I).keyed('Entry').object('aa').call(slowApi, 'upgrade', { tier: 2n }, { lovelace: 10n * ADA });
  await assert.rejects(b, KeyedCallInProgress);
  confirm.release();
  await a;
  held.release();
  assert.equal(r.state.signed, 1, 'no second signature');
  // abandon and acknowledge check the slot at entry too.
  const r2 = rig();
  const c2 = barrier();
  r2.chain.awaitTx = async () => { await c2.wait(); return true; };
  r2.state.submit = async (cbor) => hashOf(cbor);
  const a2 = r2.call(r2.page());
  await c2.at;
  await assert.rejects(r2.page().abandonKeyed(r2.api), KeyedCallInProgress);
  await assert.rejects(r2.page().acknowledgeKeyed(r2.api), KeyedCallInProgress);
  c2.release();
  await a2;
});

test('R-F16: acknowledgeKeyed clears only a tombstone; a live operation is dropped only by abandonKeyed', async () => {
  const r = rig();
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);
  await assert.rejects(r.page().acknowledgeKeyed(r.api), /still pending .* abandonKeyed/);
  assert.equal((await r.store.all()).length, 1, 'the live record stays');
  const [rec] = await r.store.all();
  await r.store.put({ ...rec, rev: rec.rev + 1, ended: 'may have landed' });
  assert.equal((await r.page().acknowledgeKeyed(r.api)).ended, 'may have landed');
  assert.deepEqual(await r.store.all(), []);
  const r2 = rig();
  await assert.rejects(r2.call(r2.page()), PendingKeyedOperation);
  assert.ok(await r2.page().abandonKeyed(r2.api));
  assert.deepEqual(await r2.store.all(), []);
});

test('F96: a falsy rejection from onReview is a refusal: nothing signed, fresh or retried', async () => {
  for (const value of [null, undefined, false, 0, '']) {
    const r = rig();
    await assert.rejects(r.call(r.page(r.service, { onReview: async () => { throw value; } })), (e) => e === value, `fresh ${String(value)}`);
    assert.equal(r.state.signed, 0);
    assert.deepEqual(await r.store.all(), []);
    await assert.rejects(r.call(r.page()), PendingKeyedOperation);
    await assert.rejects(r.call(r.page(r.service, { onReview: async () => { throw value; } })), (e) => e === value, `retry ${String(value)}`);
    assert.equal(r.state.signed, 1, 'the retry was not signed');
    assert.equal((await r.store.all())[0].attempts.length, 1, 'its record unchanged');
  }
});

test('F97: an abandon during review applies only through the reviewing call\'s store and record', async () => {
  const r = rig();
  const storeB = memoryRetryStore();
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);                                  // A's record
  await assert.rejects(r.call(r.page(r.service, { retryStore: storeB })), PendingKeyedOperation);   // B's, in another store
  let outcome;
  const a = r.page(r.service, { onReview: async () => {
    outcome = await r.page(r.service, { retryStore: storeB }).abandonKeyed(r.api).then(() => 'abandoned', (e) => e.constructor.name);
  } });
  await assert.rejects(r.call(a), PendingKeyedOperation);
  assert.equal(outcome, 'KeyedCallInProgress', 'another store\'s abandon is refused');
  assert.equal((await r.store.all())[0].attempts.length, 2, 'A went on: its record kept, a second attempt');
  assert.equal((await storeB.all()).length, 1, 'B\'s record untouched');
});

test('F98: an abandon during review applies only through the same storage, not a copy holding the same record', async () => {
  // A seeded store holds a copy of A's records (identical ids): it is another storage, so its abandon is refused.
  const r = rig();
  await assert.rejects(r.call(r.page()), PendingKeyedOperation);                                  // A's signed record
  const copy = memoryRetryStore(await r.store.all());
  let outcome;
  const a = r.page(r.service, { onReview: async () => {
    outcome = await r.page(r.service, { retryStore: copy }).abandonKeyed(r.api).then(() => 'abandoned', (e) => e.constructor.name);
  } });
  await assert.rejects(r.call(a), PendingKeyedOperation);
  assert.equal(outcome, 'KeyedCallInProgress', 'the copy\'s abandon is refused');
  assert.equal((await r.store.all())[0].attempts.length, 2, 'A went on: its record kept, a second attempt');
  assert.equal((await copy.all())[0].attempts.length, 1, 'the copy untouched');
  assert.equal(r.state.signed, 2);

  // Two wrappers over one persisted store (as two indexedDbRetryStore('joust') are) share a scope: the abandon applies.
  const shared = memoryRetryStore();
  const wrap = () => ({ scope: 'indexeddb:test/keyed', all: () => shared.all(), put: (x) => shared.put(x), delete: (i, v) => shared.delete(i, v) });
  const r2 = rig();
  await assert.rejects(r2.call(r2.page(r2.service, { retryStore: wrap() })), PendingKeyedOperation);
  let got;
  const b = r2.page(r2.service, { retryStore: wrap(), onReview: async () => {
    got = await r2.page(r2.service, { retryStore: wrap() }).abandonKeyed(r2.api);
  } });
  await assert.rejects(r2.call(b), /abandoned during review; an earlier signed attempt .* may still land/);
  assert.ok(got, 'the other wrapper\'s abandon applied');
  assert.deepEqual(await shared.all(), [], 'the record deleted');
  assert.equal(r2.state.signed, 1, 'nothing more signed');
});

test('F98: store scopes: each memory store its own, IndexedDB stores by database', async () => {
  const { memoryRetryStore: mem, indexedDbRetryStore: idb } = await import('../src/index.js');
  const { scopeOf } = await import('../src/retries.js');
  assert.notEqual(scopeOf(mem()), scopeOf(mem()));
  assert.equal(scopeOf(idb('joust')), scopeOf(idb('joust')));
  assert.notEqual(scopeOf(idb('joust')), scopeOf(idb('other')));
  const custom = { all: async () => [], put: async () => {}, delete: async () => {} };
  assert.equal(scopeOf(custom), custom, 'a store without a scope is its own');
});

test('ADR-0020 §12.1 (PR #62 S2): a cooldown is waited on only through the structured retryAtSlot, never the error text', async () => {
  // A refusal whose text says "prepare again at slot N" (a contract can trace any text) is not a cooldown: no wait.
  const r = rig();
  let asked = 0;
  const forged = { prepare: async () => { asked++; throw new Error(`Registry.upgrade: prepare again at slot ${TIP + 10_000}`); } };
  const t0 = Date.now();
  await assert.rejects(r.call(r.page(forged)), /prepare again at slot/);
  assert.equal(asked, 1, 'the text alone never makes the page wait and prepare again');
  assert.ok(Date.now() - t0 < 5_000, 'no wait');
  // The structured field does: the page waits for the slot (here reached) and prepares again.
  const r2 = rig();
  let calls = 0;
  const cooling = { prepare: async (req) => {
    calls++;
    if (calls === 1) throw Object.assign(new Error('not yet (a cooldown)'), { retryAtSlot: TIP });
    return r2.service.prepare(req);
  } };
  r2.state.submit = async (cbor) => { r2.state.spent.add(ref('a0')); return hashOf(cbor); };
  const done = await r2.call(r2.page(cooling));
  assert.ok(done.txHash);
  assert.equal(calls, 2, 'prepared again after the structured cooldown');
});

test('PR #62 C5, F10, S8: a cooldown over HTTP (joust dev\'s prepare route) keeps its slot: the page waits, then the call succeeds', async () => {
  // joust dev's POST /joust/service/prepare (http-prepare.js) in a real HTTP server, called by httpService.
  const r = rig();
  let calls = 0;
  const behind = { prepare: async (req) => {
    calls++;
    if (calls === 1) throw Object.assign(new Error(`Registry.upgrade: not yet; prepare again at slot ${TIP}`), { retryAtSlot: TIP });
    if (calls === 2) throw new Error(`a forged refusal: prepare again at slot ${TIP}`);
    return r.service.prepare(req);
  } };
  const server = createServer((req, res) => servePrepare(req, res, behind));
  await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
  try {
    const svc = httpService(`http://127.0.0.1:${server.address().port}`);
    r.state.submit = async (cbor) => { r.state.spent.add(ref('a0')); return hashOf(cbor); };
    // 1st prepare: a cooldown (field) -> wait and prepare again; 2nd: forged text only -> no wait: the call fails.
    await assert.rejects(r.call(r.page(svc)), /a forged refusal: prepare again at slot/);
    assert.equal(calls, 2, 'waited once on the structured slot, never on the forged text');
    // A fresh operation: the cooldown again, then the real build: the call completes.
    const r2 = rig();
    r2.state.submit = async (cbor) => { r2.state.spent.add(ref('a0')); return hashOf(cbor); };
    let n = 0;
    const behind2 = { prepare: async (req) => {
      n++;
      if (n === 1) throw Object.assign(new Error('not yet'), { retryAtSlot: TIP });
      return r2.service.prepare(req);
    } };
    const server2 = createServer((req, res) => servePrepare(req, res, behind2));
    await new Promise((ok) => server2.listen(0, '127.0.0.1', ok));
    try {
      const done = await r2.call(r2.page(httpService(`http://127.0.0.1:${server2.address().port}`)));
      assert.ok(done.txHash);
      assert.equal(n, 2, 'prepared again after the cooldown, over HTTP');
    } finally {
      server2.close();
    }
  } finally {
    server.close();
  }
  // The error body: the field only when it is a real, non-negative safe integer.
  assert.deepEqual(prepareErrorBody(Object.assign(new Error('x'), { retryAtSlot: 7 })), { error: 'x', retryAtSlot: 7 });
  assert.deepEqual(prepareErrorBody(new Error('prepare again at slot 7')), { error: 'prepare again at slot 7' });
  assert.deepEqual(prepareErrorBody(Object.assign(new Error('x'), { retryAtSlot: '7' })), { error: 'x' });
});
