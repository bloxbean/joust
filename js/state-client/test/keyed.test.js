// ADR-0016 D11: the page verifier's rules for keyed calls, on transactions built here with an independent ledger
// library (CSL), against the registry's description (a copy of the generated one; RegistryDescriptionTest keeps it
// current). Each honest transaction is accepted; each tampering of M3's list is refused for its own reason.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { keyIdOf, typeIdOf, compareKeys, verifyKeyed } from '../src/keyed.js';
import { dataHex } from '../src/pdata.js';
import { verifyComposed } from '../src/compose.js';
import {
  DESCRIPTION, ADA, PARAMS, WK, I, SA, THIRD, TIP, ZERO_TIME, ref, scriptAddr, world, build, chainOf, deployment, check, spendR, mintR, TxOutRef,
  insertScn, actionScn, retireScn, removeScn, createScn,
} from './keyed-world.js';

const has = (r, re) => r.problems.some((p) => re.test(p));
const accepted = (r, what) => assert.deepEqual(r.problems, [], `${what}: ${r.problems.join('; ')}`);
const refused = (r, re, what) => assert.ok(has(r, re), `${what}: expected ${re}, got: ${r.problems.join('; ') || 'accepted'}`);

// -------------------------------------------------------------------------------------------- identities and order

test('typeId, keyId and the list order (shared vectors: 7f/80, ab/ab00, the empty key)', () => {
  assert.equal(typeIdOf('Ballot'), '0642616c6c6f74');
  const v = JSON.parse(readFileSync(new URL('../../../fixtures/keyed/order-vectors.json', import.meta.url)));
  for (const [a, b, sign] of v.order) assert.equal(Math.sign(compareKeys(a, b)), sign, `${a} vs ${b}`);
  for (const k of v.keyIds) assert.equal(keyIdOf(k.instance, typeIdOf(k.type), k.key), k.keyId, `${k.type} ${k.key}`);
});

// ------------------------------------------------------------------------------------------------------- insert

test('insert: honest, with and without transaction CBOR', async () => {
  const w = world();
  const scn = insertScn(w);
  accepted(await check(w, scn), 'insert');
  accepted(await check(w, scn, undefined, { noCbor: true }), 'insert, the anchor solved by the balance equation');
});

test('insert: tamperings refused', async () => {
  const w = world();
  const scn = insertScn(w);
  const out = (s, role) => s.outputs.find((o) => o.role === role);
  // A substituted anchor: the head, though "aa" lies between it and the new key.
  const sub = insertScn(w);
  const H = ref('b3');
  sub.put(H, SA, w.minAda, { [w.unit(w.entry, '')]: 1n }, w.head(w.entry, 'aa', w.L0));
  refused(await check(w, sub, (s) => {
    s.inputs[1] = { ref: H, redeemer: spendR(w, 'Link', {}) };
    s.mintRedeemer = mintR(w, 'Insert_claim', { instance: I, anchor: TxOutRef(H), name: 'bbbb', tier: 1n, guardians: [WK], needed: 1n, ctx: w.ctx(2n * ADA, 0n) });
    s.outputs[0] = { address: SA, lovelace: w.minAda, assets: { [w.unit(w.entry, '')]: 1n }, datum: w.head(w.entry, 'bbbb', w.upper), role: 'anchor' };
    return s;
  }), /not between the anchor and its next/, 'a substituted anchor');
  refused(await check(w, scn, (s) => { out(s, 'anchor').lovelace -= ADA; return s; }), /skims/, 'a skimmed relink');
  refused(await check(w, scn, (s) => { out(s, 'anchor').address = scriptAddr('c1'.repeat(28)); return s; }), /moves to another address/, 'a re-addressed relink');
  refused(await check(w, scn, (s) => { out(s, 'anchor').scriptRef = true; return s; }), /reference script/, 'a relink with a reference script');
  refused(await check(w, scn, (s) => { out(s, 'anchor').datum = w.node(w.entry, 'bbbb', w.upper + 1000n, w.value('aa')); return s; }),
    /linkedAt other than the upper bound/, 'a relink with another linkedAt');
  // An extra Link input: a second list node rides along.
  const extra = insertScn(w);
  const X = ref('b4');
  extra.put(X, SA, w.minAda, { [w.unit(w.entry, 'dd')]: 1n }, w.node(w.entry, '', w.L0, w.value('dd')));
  refused(await check(w, extra, (s) => {
    s.inputs.push({ ref: X, redeemer: spendR(w, 'Link', {}) });
    s.outputs.push({ address: SA, lovelace: w.minAda, assets: { [w.unit(w.entry, 'dd')]: 1n }, datum: w.node(w.entry, '', w.upper, w.value('dd')) });
    return s;
  }), /an insert spends exactly its anchor/, 'an extra Link input');
  // Heads on a non-creation: an insert that also mints a head.
  refused(await check(w, scn, (s) => {
    const h = w.unit(w.entry, '', 'cd'.repeat(32));
    s.mint[h] = 1n;
    s.outputs.push({ address: SA, lovelace: w.minAda, assets: { [h]: 1n }, datum: w.head(w.entry, '', 0n, 'cd'.repeat(32)) });
    return s;
  }), /mints exactly one keyed token/, 'a head minted by an insert');
  // An anchor top-up above allowA (at the honest parameters the anchor needs none).
  refused(await check(w, scn, (s) => { out(s, 'anchor').lovelace += ADA; return s; }), /topped up by 1000000 lovelace, above the 0/, 'an anchor top-up');
  // An extra output to a third party.
  refused(await check(w, scn, (s) => { s.outputs.push({ address: THIRD, lovelace: 2n * ADA }); return s; }), /others receive/, 'an insert paying a third party');
  // The new node: another key than the mint's, or another value.
  refused(await check(w, scn, (s) => { out(s, 'node').datum = w.node(w.entry, '', w.upper, w.value('bbbc', 2n * ADA)); return s; }),
    /not minted as its key's token/, 'a node whose key is not the minted one');
  refused(await check(w, scn, (s) => { out(s, 'node').lovelace += ADA; return s; }), /not the minimum ADA and the payment/, 'a node holding more');
  // The designated input not spent (a retry that does not spend the first attempt's input).
  refused(await check(w, scn, (s) => { s.inputs[0] = { ref: ref('a0') }; return s; }), /does not spend the operation's designated input/,
    'a retry without the designated input');
});

test('insert: an anchor top-up after a raised coinsPerUTxOByte is accepted, within allowA only', async () => {
  const w = world();
  const params = { ...PARAMS, coins_per_utxo_size: '20000' };
  const scn = insertScn(w);
  // The relinked anchor's ledger minimum, from its size as built: (160 + size) x 20,000.
  const probe = await check(w, scn, undefined, { params });
  const m = probe.problems.find((p) => /the relinked anchor/.test(p));
  assert.ok(!m || /topped up/.test(m) === false, m);
  // Find the size by building: top up exactly to the minimum.
  const { parseTx } = await import('../src/ledger.js');
  const tx = parseTx(build(w, structuredClone(scn.spec), scn.truth));
  const anchorOut = tx.outputs.find((o) => o.value.get(w.unit(w.entry, 'aa')) === 1n);
  const need = (160n + BigInt(anchorOut.size)) * 20000n - w.minAda;
  assert.ok(need > 0n);
  const topped = (s) => { s.outputs[0].lovelace += need; return s; };
  accepted(await check(w, scn, topped, { params }), 'an anchor topped up to its ledger minimum');
  refused(await check(w, scn, (s) => { s.outputs[0].lovelace += need + 1n; return s; }, { params }), /above the/, 'one lovelace more');
  refused(await check(w, scn, topped, { params: { ...PARAMS, coins_per_utxo_size: '50000' } }), /above the page's limit/, 'parameters above the cap');
});

// ---------------------------------------------------------------------------------------------- keyed actions

test('keyed action: a payable action paying a beneficiary from the payment and its funds (100 + 10 -> 90, 20)', async () => {
  const w = world();
  const scn = actionScn(w, { out: 90n * ADA, beneficiary: 20n * ADA });
  accepted(await check(w, scn), 'with transaction CBOR');
  accepted(await check(w, scn, undefined, { noCbor: true }), 'without transaction CBOR (bound by Ctx)');
  refused(await check(w, scn, (s) => { s.outputs.push({ address: THIRD, lovelace: 5n * ADA }); return s; }), /others receive/, 'an extra third-party output');
  // The wrong Ctx offset (the balance as the whole input): refused with CBOR, and without (the provider disagrees).
  const wrong = (s) => { s.inputs[1].redeemer = spendR(w, 'Upgrade', { tier: 2n, ctx: w.ctx(10n * ADA, 100n * ADA) }); return s; };
  refused(await check(w, scn, wrong), /wrong offset/, 'a wrong Ctx offset, with CBOR');
  refused(await check(w, scn, wrong, { noCbor: true }), /disagrees with the call's Ctx/, 'a wrong Ctx offset, without CBOR');
  // Changing the key, the next link or linkedAt (I50).
  refused(await check(w, scn, (s) => { s.outputs[0].datum = w.node(w.entry, 'dd', w.L0, w.value('aa', 15n, 2n)); return s; }), /I50/, 'a changed next');
});

// ------------------------------------------------------------------------------------------------------ retires

test('retire: honest, with and without CBOR; top-ups bounded by allowR; the Ctx offset', async () => {
  const w = world();
  accepted(await check(w, retireScn(w)), 'a retire');
  accepted(await check(w, retireScn(w), undefined, { noCbor: true }), 'a retire without CBOR (bound by Ctx)');
  refused(await check(w, retireScn(w, { keep: w.minAda + ADA, paid: 100n * ADA - w.minAda - ADA })), /topped up by 1000000/, 'a retire top-up above allowR');
  refused(await check(w, retireScn(w, { ctxBalance: 100n * ADA })), /wrong offset/, 'a retire with the removal\'s offset');
  refused(await check(w, retireScn(w, { payee: THIRD }), (s) => { s.outputs.push({ address: THIRD, lovelace: ADA }); return s; }), /others receive/,
    'a retire paying a third party more than it releases');
});

test('retire after a raised coinsPerUTxOByte: the owner tops up, a beneficiary is paid (100 ADA in, deposit 4, minimum 5, 96 out)', async () => {
  const desc = structuredClone(DESCRIPTION);
  desc['x-joust'].minAda = 4_000_000;
  const w = world(desc);
  // Coins per byte such that the retired output's minimum is just above 5 ADA; the owner tops up to it.
  const { parseTx } = await import('../src/ledger.js');
  const base = retireScn(w, { keep: 5n * ADA, payee: THIRD, paid: 96n * ADA });
  const size = BigInt(parseTx(build(w, structuredClone(base.spec), base.truth)).outputs[0].size);
  const coins = (5n * ADA + 160n + size - 1n) / (160n + size);
  const min = (160n + size) * coins;
  const scn = retireScn(w, { keep: min, payee: THIRD, paid: 96n * ADA });
  const params = { ...PARAMS, coins_per_utxo_size: String(coins) };
  const r = await check(w, scn, undefined, { params });
  accepted(r, 'a retire with the owner\'s top-up and a third-party beneficiary');
  assert.equal(r.report.keyed.topUpR, min - 4n * ADA);
  assert.ok(r.report.walletNet <= r.report.fee + (min - 4n * ADA));
  refused(await check(w, retireScn(w, { keep: min + 1n, payee: THIRD, paid: 96n * ADA }), undefined, { params }), /above the/, 'one lovelace more');
});

// ------------------------------------------------------------------------------------------------------ removals

test('removal: honest; the two-input forgery and the remove-with-gift refused; a payable removal paying in + received', async () => {
  const w = world();
  accepted(await check(w, removeScn(w)), 'a removal');
  accepted(await check(w, removeScn(w), undefined, { noCbor: true }), 'a removal without CBOR (the node by Ctx, the predecessor solved)');
  // The remove-with-gift: the predecessor gets 40 ADA of the node's funds.
  const gift = removeScn(w, { gift: 40n * ADA });
  refused(await check(w, gift), /topped up by 40000000/, 'the remove-with-gift');
  // The two-input forgery: the provider reports the node low and the predecessor high, hiding the gift.
  const lies = new Map([[gift.N, { ...gift.truth.get(gift.N), value: new Map([...gift.truth.get(gift.N).value, ['lovelace', 10n * ADA]]) }],
    [gift.P, { ...gift.truth.get(gift.P), value: new Map([...gift.truth.get(gift.P).value, ['lovelace', w.minAda + 40n * ADA]]) }]]);
  const forged = { ...gift, spec: { ...gift.spec, inputs: [gift.spec.inputs[0], { ref: gift.N, redeemer: spendR(w, 'Release', { ctx: w.ctx(0n, 50n * ADA) }) }, gift.spec.inputs[2]] } };
  refused(await check(w, forged, undefined, { lies }), /topped up by 40000000/, 'the forgery, with CBOR (values authenticated)');
  refused(await check(w, forged, undefined, { lies, noCbor: true }), /disagrees with the call's Ctx/, 'the forgery, without CBOR (Ctx binds the node)');
  // A payable removal (release made payable here) pays in + received, counted once.
  const desc = structuredClone(DESCRIPTION);
  desc['x-joust'].actions.find((a) => a.name === 'release').payable = true;
  const wp = world(desc);
  const payable = removeScn(wp, { pay: 5n * ADA, payee: THIRD });
  accepted(await check(wp, payable), 'a payable removal paying in + received to a beneficiary');
  refused(await check(wp, payable, (s) => { s.outputs.push({ address: THIRD, lovelace: ADA }); return s; }), /others receive/, 'with an extra third-party output');
  // The wrong offset: a removal's Ctx sees the whole input.
  refused(await check(w, removeScn(w, { ctxBalance: 50n * ADA - w.minAda })), /wrong offset/, 'a removal with the action\'s offset');
});

// ----------------------------------------------------------------------------------------------------- creations

test('creation with heads: honest; deposits exact; differing constructor widths; a payment claimed twice', async () => {
  const w = world();
  const scn = createScn(w);
  accepted(await check(w, scn), 'a creation with two heads');
  refused(await check(w, createScn(w, { deposits: [w.minAda + ADA, w.minAda] })), /not its deposit/, 'an excessive head deposit');
  refused(await check(w, scn, (s) => { s.outputs.push({ address: THIRD, lovelace: 2n * ADA }); return s; }), /others receive/, 'a creation paying a third party');
  // The payment claimed twice: the instance holds the payment and the wallet pays it again elsewhere.
  refused(await check(w, scn, (s) => { s.outputs[0].lovelace += 5n * ADA; s.call = { pay: { lovelace: 5n * ADA } }; s.outputs.push({ address: THIRD, lovelace: 5n * ADA }); return s; }),
    /others receive|loses/, 'a creation whose payment is claimed twice');
  // Heads of differing constructor widths (tags 0 and 7 encode in 2 and 3 bytes), at a raised coinsPerUTxOByte.
  const desc = structuredClone(DESCRIPTION);
  const e = desc['x-joust'].keyed[1];
  e.headTag = 7; e.nodeTag = 8; e.retiredTag = 9;
  const wd = world(desc);
  const params = { ...PARAMS, coins_per_utxo_size: '19000' };
  const { parseTx } = await import('../src/ledger.js');
  const sized = createScn(wd);
  const outs = parseTx(build(wd, structuredClone(sized.spec), sized.truth)).outputs;
  const deps = [outs[1], outs[2]].map((o) => (160n + BigInt(o.size)) * 19000n);
  assert.ok(deps[0] > wd.minAda && deps[1] > deps[0], `differing deposits: ${deps}`);
  accepted(await check(wd, createScn(wd, { deposits: deps }), undefined, { params }), 'heads above minAda, each at its own minimum');
  refused(await check(wd, createScn(wd, { deposits: [deps[1], deps[0]] }), undefined, { params }), /not its deposit/, 'each funded at the other\'s amount');
});

// --------------------------------------------------------------------------------------------------- composition

test('a composition containing a keyed call is refused', async () => {
  const w = world();
  const scn = actionScn(w);
  const txHex = build(w, structuredClone(scn.spec), scn.truth);
  const r = await verifyComposed({ txHex, steps: [{ description: DESCRIPTION, deployment, call: scn.call }], wallet: scn.wallet,
    chain: chainOf(scn.truth) });
  refused(r, /keyed call is verified on its own/, 'a keyed call in a composition');
});

// ------------------------------------------------------------------------------------- review of M3 (R-F1, F80, F81)

/** {@code txHex} with {@code datum}'s inline bytes padded: its instance as an indefinite bytestring of empty chunks. */
function padded(txHex, datum, n = 2000) {
  const hex = dataHex(datum);
  const tag24 = (h) => {
    const len = h.length / 2;
    const head = len < 24 ? (0x40 + len).toString(16) : len < 256 ? '58' + len.toString(16).padStart(2, '0') : '59' + len.toString(16).padStart(4, '0');
    return 'd818' + head + h;
  };
  const inst = hex.match(/5820([0-9a-f]{64})/)[1];
  const p = hex.replace('5820' + inst, '5f' + '40'.repeat(n) + '5820' + inst + 'ff');
  assert.equal(txHex.split(tag24(hex)).length, 2, 'the datum once in the transaction');
  return txHex.replace(tag24(hex), tag24(p));
}

async function verifyHex(w, scn, txHex, chainOpts = {}, extra = {}) {
  return verifyKeyed({ description: w.description, deployment: extra.deployment ?? deployment, txHex, call: scn.call, wallet: scn.wallet,
    chain: { ...chainOf(scn.truth, chainOpts), ...(extra.chain ?? {}) } });
}

test('R-F1: a datum padded to raise an allowance is refused (relink, head, retire); allowances are capped', async () => {
  const w = world();
  const ins = insertScn(w);
  const anchorDatum = ins.spec.outputs[0].datum;
  refused(await verifyHex(w, ins, padded(build(w, structuredClone(ins.spec), ins.truth), anchorDatum), { params: { ...PARAMS, coins_per_utxo_size: '20000' } }),
    /relinked anchor has an inline datum that is not its input's relinked in the canonical encoding/, 'a padded relink');
  const cr = createScn(w);
  refused(await verifyHex(w, cr, padded(build(w, structuredClone(cr.spec), cr.truth), cr.spec.outputs[1].datum)),
    /head has an inline datum that is not in the canonical encoding/, 'a padded head');
  const rt = retireScn(w);
  refused(await verifyHex(w, rt, padded(build(w, structuredClone(rt.spec), rt.truth), rt.spec.outputs[0].datum)),
    /retired node has an inline datum that is not in the canonical encoding/, 'a padded retired node');
  // The cap: at the page's highest coinsPerUTxOByte a large honest anchor's allowance is still at most 10 ADA.
  const big = insertScn(w);
  const r = await check(w, big, (s) => { s.outputs[0].lovelace += 20n * ADA; return s; }, { params: { ...PARAMS, coins_per_utxo_size: '20000' } });
  refused(r, /above the/, 'a top-up above the cap');
});

test('F80: a keyed call needs the page\'s slot clock, and the chain API\'s tip must agree with it', async () => {
  const w = world();
  const scn = insertScn(w);
  const txHex = build(w, structuredClone(scn.spec), scn.truth);
  refused(await verifyHex(w, scn, txHex, {}, { deployment: { ...deployment, slotConfig: undefined } }), /needs the deployment's slot clock/, 'no clock');
  refused(await verifyHex(w, scn, txHex, {}, { deployment: { ...deployment, slotConfig: { zeroSlot: 0, zeroTimeMs: 'x', slotLengthMs: 1000 } } }),
    /needs the deployment's slot clock/, 'a malformed clock');
  refused(await verifyHex(w, scn, txHex, {}, { chain: { tip: TIP + 100_000 } }), /tip .* is not the page's own slot/, 'a lying future tip');
  // linkedAt off by a second is refused (it always runs now).
  refused(await check(w, scn, (s) => { s.outputs[1].datum = w.node(w.entry, '', w.upper + 1000n, w.value('bbbb', 2n * ADA)); return s; }),
    /linkedAt other than the upper bound/, 'a node linkedAt off by a second');
});

test('F81: the page checks a structural operation\'s cooldown and its window against W', async () => {
  const w = world();
  // The anchor was relinked at the transaction's own upper bound: still cooling down.
  const cooling = insertScn(w);
  const A = cooling.A;
  cooling.truth.get(A).datum = w.node(w.entry, '', w.upper, w.value('aa'));
  refused(await check(w, cooling, (s) => { s.outputs[0].datum = w.node(w.entry, 'bbbb', w.upper, w.value('aa')); return s; }),
    /before the end of its cooldown/, 'an insert during the anchor\'s cooldown');
  // A window wider than W (120 s): 200 slots.
  const wide = insertScn(w);
  const upper = BigInt(ZERO_TIME) + BigInt(TIP + 200) * 1000n;
  refused(await check(w, wide, (s) => {
    s.ttl = TIP + 200;
    s.outputs[0].datum = w.node(w.entry, 'bbbb', upper, w.value('aa'));
    s.outputs[1].datum = w.node(w.entry, '', upper, w.value('bbbb', 2n * ADA));
    return s;
  }), /wider than linkWindowMs/, 'a window wider than W');
  // The removal's predecessor: the same rule.
  const rm = removeScn(w);
  rm.truth.get(rm.P).datum = w.head(w.entry, 'aa', w.upper);
  refused(await check(w, rm), /before the end of its cooldown/, 'a removal during the predecessor\'s cooldown');
});

test('R-F4: an insert\'s and a creation\'s Ctx balance is 0', async () => {
  const w = world();
  const ins = insertScn(w);
  refused(await check(w, ins, (s) => {
    s.mintRedeemer = mintR(w, 'Insert_claim', { instance: I, anchor: TxOutRef(ins.A), name: 'bbbb', tier: 1n, guardians: [WK], needed: 1n, ctx: w.ctx(2n * ADA, 5n) });
    return s;
  }), /Ctx balance is 5, not 0/, 'an insert with a balance');
  const cr = createScn(w);
  refused(await check(w, cr, (s) => { s.mintRedeemer = mintR(w, 'CreateInstance', { seed: TxOutRef(ref('a1')), ctx: w.ctx(0n, 1n) }); return s; }),
    /Ctx balance is 1, not 0/, 'a creation with a balance');
});

test('R-F5: chain.verifiedOutput reads an input\'s value from its producing transaction, and refuses a forged one', async () => {
  const { chainOf: chainView } = await import('../src/chain.js');
  const { txHash } = await import('../src/ledger.js');
  const w = world();
  const scn = insertScn(w);
  const cbor = build(w, structuredClone(scn.spec), scn.truth);
  const forged = build(w, (() => { const s = structuredClone(scn.spec); s.outputs[0].lovelace += 1n; return s; })(), scn.truth);
  const h = txHash(cbor);
  const honest = await chainView({ txCbor: async () => cbor }).verifiedOutput(h + '#0');
  assert.equal(honest.address, SA);
  assert.equal(honest.value.get('lovelace'), scn.spec.outputs[0].lovelace);
  assert.equal(honest.value.get(w.unit(w.entry, 'aa')), 1n);
  assert.ok(honest.size > 100 && honest.inlineDatum);
  await assert.rejects(chainView({ txCbor: async () => forged }).verifiedOutput(h + '#0'), /served another transaction/);
});

test('F83: joust-gen refuses a keyed call named like a generated wrapper member', async () => {
  const { generate } = await import('../src/codegen.js');
  assert.ok(generate(DESCRIPTION).js.includes('class RegistryEntryObject'));
  const d = structuredClone(DESCRIPTION);
  d['x-joust'].actions.find((a) => a.name === 'upgrade').name = 'read';
  assert.throws(() => generate(d), /keyed call read clashes with the generated object's read/);
});

/** {@code txHex} with {@code datum}'s tag-24 wrapper padded: an indefinite bytestring of empty chunks around it. */
function paddedWrapper(txHex, datum, n = 1500) {
  const d = dataHex(datum);
  const hdr = (k) => (k < 24 ? (0x40 + k).toString(16) : k < 256 ? '58' + k.toString(16).padStart(2, '0') : '59' + k.toString(16).padStart(4, '0'));
  const before = 'd818' + hdr(d.length / 2) + d;
  assert.equal(txHex.split(before).length, 2, 'the datum once in the transaction');
  return txHex.replace(before, 'd818' + '5f' + '40'.repeat(n) + hdr(d.length / 2) + d + 'ff');
}

test('F86, R-F10: a padded tag-24 wrapper raises no allowance (anchor with and without CBOR, head, retire)', async () => {
  const w = world();
  // An anchor topped up as if its padded size counted, at the honest parameters.
  const ins = insertScn(w);
  const txAnchor = paddedWrapper(build(w, (() => { const s = structuredClone(ins.spec); s.outputs[0].lovelace += 4_000_000n; return s; })(), ins.truth),
    ins.spec.outputs[0].datum);
  for (const noCbor of [false, true]) {
    const r = await verifyHex(w, ins, txAnchor, { noCbor });
    refused(r, /relinked anchor is not encoded as the page would write it/, `a padded anchor wrapper (${noCbor ? 'no CBOR' : 'CBOR'})`);
    refused(r, /relinked anchor is topped up by 4000000 lovelace, above the 0/, 'its top-up counts only the page\'s own encoding');
  }
  // A head funded at its padded size.
  const cr = createScn(w);
  const padHead = paddedWrapper(build(w, structuredClone(cr.spec), cr.truth), cr.spec.outputs[1].datum);
  refused(await verifyHex(w, cr, padHead), /head is not encoded as the page would write it/, 'a padded head wrapper');
  // A retire topped up for its padded size at a raised coinsPerUTxOByte.
  const rt = retireScn(w, { keep: w.minAda + 3_000_000n, paid: 100n * ADA - w.minAda - 3_000_000n });
  const padRetire = paddedWrapper(build(w, structuredClone(rt.spec), rt.truth), rt.spec.outputs[0].datum);
  const r = await verifyHex(w, rt, padRetire, { params: { ...PARAMS, coins_per_utxo_size: '19000' } });
  refused(r, /retired node is not encoded as the page would write it/, 'a padded retired node wrapper');
  refused(r, /retired node is topped up by 3000000/, 'its top-up counts only the page\'s own encoding');
});

/** {@code d} with every list and constructor's fields as definite-length CBOR arrays (another writer's encoding). */
function definite(d) {
  const { decodeData } = PD;
  const h = (major, n) => (n < 24 ? [(major << 5) | n] : n < 256 ? [(major << 5) | 24, n] : [(major << 5) | 25, n >> 8, n & 0xff]);
  const enc = (x) => {
    if ('constr' in x) return [0xd8, 121 + x.constr, ...h(4, x.fields.length), ...x.fields.flatMap(enc)];
    if ('list' in x) return [...h(4, x.list.length), ...x.list.flatMap(enc)];
    return [...Buffer.from(dataHex(x), 'hex')];
  };
  return decodeData(Buffer.from(enc(d)).toString('hex'));
}
const PD = await import('../src/pdata.js');

test('R-F6: a relink next to a node another writer encoded with definite lists is accepted; its value bytes are kept', async () => {
  const w = world();
  const scn = insertScn(w);
  const v = definite(w.value('aa'));
  assert.notEqual(dataHex(v), PD.canonicalHex(v), 'a non-canonical encoding of the same Data');
  scn.truth.get(scn.A).datum = w.node(w.entry, '', w.L0, v);
  accepted(await check(w, scn, (s) => { s.outputs[0].datum = w.node(w.entry, 'bbbb', w.upper, v); return s; }), 'the engine relink, value bytes kept');
  accepted(await check(w, scn, (s) => { s.outputs[0].datum = w.node(w.entry, 'bbbb', w.upper, v); return s; }, { noCbor: true }), 'the same, without CBOR');
  refused(await check(w, scn, (s) => { s.outputs[0].datum = w.node(w.entry, 'bbbb', w.upper, w.value('aa')); return s; }),
    /not its input's relinked/, 'a relink that re-encodes the value');
});
