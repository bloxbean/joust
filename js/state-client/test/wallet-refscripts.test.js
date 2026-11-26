// #165 Codex r9: a wallet UTxO carrying a reference script is never spent nor put up as collateral. Its value could
// come back as change while the published script is lost, so every page verifier refuses such an input before the
// wallet signs, whatever the balance says. The wallet's record (CIP-30, walletView) gives referenceScriptHash; a
// provider's record may give only hasReferenceScript.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { verifyPrepared } from '../src/verify.js';
import { verifyComposed } from '../src/compose.js';
import { DESCRIPTION, WK, I, SA, POLICY, ref, world, build, chainOf, deployment, setup, spendR, meta, check, insertScn } from './keyed-world.js';

const SPENT = ref('a1');        // a wallet input every scenario spends
const COLLATERAL = ref('a0');   // the scenarios' collateral
const REFUSAL = /carries a reference script/;
const accepted = (r, what) => assert.deepEqual(r.problems, [], `${what}: ${r.problems.join('; ')}`);
const refused = (r, what) => assert.ok(r.problems.some((p) => REFUSAL.test(p)), `${what}: got ${r.problems.join('; ') || 'accepted'}`);

/** The wallet with {@code r}'s record marked as carrying a reference script (CIP-30's hash, or a provider's flag). */
function marked(wallet, r, flag = { referenceScriptHash: 'e1'.repeat(28) }) {
  const utxos = new Map(wallet.utxos);
  utxos.set(r, { ...utxos.get(r), ...flag });
  return { ...wallet, utxos };
}

/** A plain instance action (setOpen false), funded by {@code SPENT}. */
function setOpen() {
  const w = world();
  const N = ref('b1');
  const s = setup(w, [[N, SA, w.minAda, { [POLICY + I]: 1n }, w.c.encode(w.xj.state, { admin: WK, open: true })]]);
  const txHex = build(w, {
    inputs: [{ ref: SPENT }, { ref: N, redeemer: spendR(w, 'SetOpen', { open: false }) }],
    outputs: [{ address: SA, lovelace: w.minAda, assets: { [POLICY + I]: 1n }, datum: w.c.encode(w.xj.state, { admin: WK, open: false }) }],
  }, s.truth);
  const call = { kind: 'action', meta: meta(w, 'setOpen'), args: { open: false }, pay: { lovelace: 0n }, instance: I };
  return { ...s, txHex, call, chain: chainOf(s.truth) };
}

const cases = [
  ['a spent wallet input (referenceScriptHash)', SPENT, undefined],
  ['a spent wallet input (hasReferenceScript)', SPENT, { hasReferenceScript: true }],
  ['a wallet collateral input (referenceScriptHash)', COLLATERAL, undefined],
  ['a wallet collateral input (hasReferenceScript)', COLLATERAL, { hasReferenceScript: true }],
];

test('a single call: a script-bearing wallet input or collateral is refused', async () => {
  const s = setOpen();
  const verify = (wallet) => verifyPrepared({ description: DESCRIPTION, deployment, txHex: s.txHex, call: s.call, wallet, chain: s.chain });
  accepted(await verify(s.wallet), 'the honest action');
  for (const [what, r, flag] of cases) refused(await verify(marked(s.wallet, r, flag)), what);
});

test('a composition: a script-bearing wallet input or collateral is refused', async () => {
  const s = setOpen();
  const verify = (wallet) => verifyComposed({ txHex: s.txHex, steps: [{ description: DESCRIPTION, deployment, call: s.call }], wallet,
    chain: s.chain });
  accepted(await verify(s.wallet), 'the honest composition');
  for (const [what, r, flag] of cases) refused(await verify(marked(s.wallet, r, flag)), what);
});

test('a keyed call: a script-bearing wallet input (the designated one) or collateral is refused', async () => {
  const w = world();
  const scn = insertScn(w);
  accepted(await check(w, scn), 'the honest insert');
  for (const [what, r, flag] of cases) refused(await check(w, { ...scn, wallet: marked(scn.wallet, r, flag) }), what);
});
