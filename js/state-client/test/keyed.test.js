// ADR-0016 D11: the page verifier's rules for keyed calls, on transactions built here with an independent ledger
// library (CSL), against the registry's description (a copy of the generated one; RegistryDescriptionTest keeps it
// current). Each honest transaction is accepted; each tampering of M3's list is refused for its own reason.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { keyIdOf, typeIdOf, compareKeys } from '../src/keyed.js';
import { verifyComposed } from '../src/compose.js';
import {
  DESCRIPTION, ADA, PARAMS, WK, I, SA, THIRD, ref, scriptAddr, world, build, chainOf, deployment, check, spendR, mintR, TxOutRef,
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
