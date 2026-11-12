// ADR-0016 M3 (M2's window criteria, end to end): a recorded insert whose anchor was cooling down (fixtures/m3/registry,
// recorded by examples.test.js on DevKit) is prepared again by the Wasm engine under other slot clocks, and verified by
// the page. With 100 ms slots the page's limits (60 start slots, 900 validity slots) are 6 s and 90 s: the engine waits
// (refuses with the slot to prepare again at) until the cooldown ends within 6 s, then builds a future start whose window
// is cut to the page's 900 slots, below W (120 s); the page accepts it. With one-second slots, the window starts at the
// cooldown's end and is at most W long. No 100 ms network exists here: the chain reads are the recorded ones.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { loadEngine } from '../src/index.js';
import { verifyKeyed, parseTx, decodeData, paymentKeyHashOf, listDatum, keyedTypeOf } from '@julclang/state-client';

const FIXTURES = new URL('../../../fixtures/m3/', import.meta.url).pathname;
const ENGINE = new URL('../engine/joust-runtime.js', import.meta.url).pathname;

/** The recorded insert whose anchor was a fresh node (it waited for the cooldown). */
function cooledInsert() {
  const dir = join(FIXTURES, 'registry');
  if (!existsSync(dir)) return null;
  for (const f of readdirSync(dir).sort()) {
    const x = JSON.parse(readFileSync(join(dir, f), 'utf8'));
    if (x.call.kind === 'keyedInit' && x.call.args?.name === '6f74686572') return x;
  }
  return null;
}

const fixture = cooledInsert();
const skip = !fixture ? 'no recorded registry fixture (JOUST_RECORD=fixtures/m3)' : !existsSync(ENGINE) ? 'no engine' : false;

function contractsOf(x) {
  return x.contracts.map((sha) => JSON.parse(readFileSync(join(FIXTURES, 'contracts', sha + '.json'), 'utf8')));
}

/** The snapshot as the page's chain view. */
function chainOf(snapshot, tip) {
  const all = [...Object.values(snapshot.utxos ?? {}).flat(), ...Object.values(snapshot.assetUtxos ?? {}).flat(), ...(snapshot.outputs ?? [])];
  const find = (r) => all.find((u) => `${u.tx_hash}#${u.output_index}` === r);
  return {
    tip, params: snapshot.protocolParams,
    utxo: async (r) => {
      const u = find(r);
      return u ? { address: u.address, value: new Map(u.amount.map((a) => [a.unit, BigInt(a.quantity)])), inlineDatum: u.inline_datum,
        referenceScriptHash: u.reference_script_hash } : null;
    },
  };
}

async function prepareUnder(engine, x, clock) {
  const contracts = contractsOf(x).map((c) => ({ description: typeof c.description === 'string' ? c.description : JSON.stringify(c.description),
    script: c.script, transitions: c.transitions,
    manifest: typeof c.manifest === 'string' ? c.manifest : JSON.stringify(c.manifest) }));
  return engine.joust.prepare({ call: JSON.stringify(x.call), snapshot: JSON.stringify(x.snapshot), contracts,
    zeroSlot: BigInt(clock.zeroSlot), zeroTimeMs: BigInt(clock.zeroTimeMs), slotLengthMs: BigInt(clock.slotLengthMs),
    ttlMs: BigInt(clock.ttlMs), networkId: 0, mapNodes: {}, mapProofs: {} });
}

test('a cooling anchor\'s insert under 100 ms slots: the engine waits, then builds a future start within the page\'s limits', { skip }, async () => {
  const engine = await loadEngine();
  const x = fixture;
  const description = JSON.parse(contractsOf(x)[0].description);
  const xj = description['x-joust'];
  const kt = keyedTypeOf(xj, 'Entry');
  const S = Number(x.snapshot.tip.slot);
  // The anchor's linkedAt, from the recorded transaction's script input.
  const tx = parseTx(x.cbor);
  const chain0 = chainOf(x.snapshot, S);
  let linkedAt = null;
  for (const r of tx.inputs) {
    const u = await chain0.utxo(r);
    const d = u?.inlineDatum ? listDatum(kt, decodeData(u.inlineDatum)) : null;
    if (d) linkedAt = d.linkedAt;
  }
  assert.ok(linkedAt, 'the anchor');
  const ready = Number(linkedAt) + kt.linkCooldownMs;
  // The pinned reference script: its hash is the policy, its address the contract's.
  const pinned = await chain0.utxo(x.call.referenceScripts[0]);
  const deployment = { policy: pinned.referenceScriptHash, address: pinned.address, networkId: 0, referenceScripts: x.call.referenceScripts };
  const wallet = { addresses: [x.call.address], keyHashes: [paymentKeyHashOf(x.call.address)], networkId: 0,
    utxos: new Map(x.call.walletUtxos.map((u) => [`${u.txHash}#${u.index}`, { address: u.address, value: new Map(u.value.map((a) => [a.unit, BigInt(a.quantity)])) }])) };
  const meta = xj.creations.find((c) => c.name === 'claim');
  const call = { kind: 'keyedInit', meta, args: { ...x.call.args, tier: BigInt(x.call.args.tier), needed: BigInt(x.call.args.needed) },
    pay: { lovelace: BigInt(x.call.pay.lovelace) }, instance: x.call.instance, designated: x.call.designated };

  for (const [what, slotLengthMs, before] of [['100 ms slots', 100, 30_000], ['100 ms slots, 5 s before', 100, 5_000], ['one-second slots', 1000, 0]]) {
    // The tip at {@code before} ms before the cooldown ends.
    const clock = { zeroSlot: 0, slotLengthMs, zeroTimeMs: ready - before - S * slotLengthMs, ttlMs: 120_000 };
    const r = await prepareUnder(engine, x, clock);
    if (before > 60 * slotLengthMs) {
      assert.equal(r.ok, false, `${what}: refused`);
      const m = /prepare again at slot (\d+)/.exec(r.error);
      assert.ok(m, `${what}: ${r.error}`);
      assert.equal(Number(m[1]), S + Math.ceil(before / slotLengthMs) - 60, `${what}: the slot to prepare again at`);
      console.log(`[window] ${what}: the engine waits: prepare again at slot ${m[1]} (tip ${S})`);
      continue;
    }
    assert.ok(r.ok, `${what}: ${r.error}`);
    const t = parseTx(r.cbor);
    const from = Number(t.validFrom);
    const to = Number(t.ttl);
    assert.ok(from >= S && from <= S + 60, `${what}: the start ${from} within the page's 60 slots of ${S}`);
    assert.ok(to - S <= 900, `${what}: the end within the page's 900 slots`);
    assert.ok((to - from) * slotLengthMs <= kt.linkWindowMs, `${what}: the window at most W`);
    assert.ok(clock.zeroTimeMs + from * slotLengthMs >= ready, `${what}: the start after the cooldown`);
    // The page's own clock is this one (R-F5): pinned in the deployment, and "now" is the snapshot's tip.
    const realNow = Date.now;
    Date.now = () => clock.zeroTimeMs + S * slotLengthMs + 1;
    let v;
    try {
      v = await verifyKeyed({ description, deployment: { ...deployment, slotConfig: clock }, txHex: r.cbor, call, wallet, chain: chainOf(x.snapshot, S) });
    } finally {
      Date.now = realNow;
    }
    assert.deepEqual(v.problems, [], `${what}: ${v.problems.join('; ')}`);
    console.log(`[window] ${what}: [${from}, ${to}) at tip ${S}: ${(to - from) * slotLengthMs / 1000} s, starting ${(from - S) * slotLengthMs / 1000} s ahead; the page accepts it`);
  }
});

test('every recorded registry transaction passes the page with the recorded clock pinned (linkedAt == upper, page-clock limits)', { skip }, async () => {
  const dir = join(FIXTURES, 'registry');
  let n = 0;
  for (const f of readdirSync(dir).sort()) {
    const x = JSON.parse(readFileSync(join(dir, f), 'utf8'));
    const description = JSON.parse(contractsOf(x)[0].description);
    const xj = description['x-joust'];
    const meta = [...xj.actions, ...xj.creations].find((a) => a.name === x.call.method);
    const S = Number(x.snapshot.tip.slot);
    const chain = chainOf(x.snapshot, S);
    const pinned = await chain.utxo(x.call.referenceScripts[0]);
    const deployment = { policy: pinned.referenceScriptHash, address: pinned.address, networkId: 0, referenceScripts: x.call.referenceScripts,
      slotConfig: x.clock };
    const wallet = { addresses: [x.call.address], keyHashes: [paymentKeyHashOf(x.call.address)], networkId: 0,
      utxos: new Map(x.call.walletUtxos.map((u) => [`${u.txHash}#${u.index}`, { address: u.address, value: new Map(u.value.map((a) => [a.unit, BigInt(a.quantity)])) }])) };
    const args = {};
    for (const a of meta.args) {
      const v = x.call.args[a.name];
      args[a.name] = typeof v === 'string' && /^-?\d+$/.test(v) && /integer/.test(JSON.stringify(a.schema)) ? BigInt(v) : v;
    }
    const call = { kind: x.call.kind, meta, args, pay: { lovelace: BigInt(x.call.pay?.lovelace ?? 0) }, instance: x.call.instance,
      key: x.call.object ?? undefined, designated: x.call.designated };
    const realNow = Date.now;
    Date.now = () => Number(x.clock.zeroTimeMs) + S * Number(x.clock.slotLengthMs) + 500;
    let v;
    try {
      v = await verifyKeyed({ description, deployment, txHex: x.cbor, call, wallet, chain });
    } finally {
      Date.now = realNow;
    }
    assert.deepEqual(v.problems, [], `${f}: ${v.problems.join('; ')}`);
    n++;
  }
  console.log(`[window] ${n} recorded registry transactions accepted by the page with the clock pinned`);
  assert.equal(n, 22);
});
