// What the page binds about the calls it verifies (ADR-0015 §5, ADR-0013 §9.2), the chain provider and the wallet
// trusted: a payment is credited only where the redeemer binds it, and an owned object's source instance (its input
// datum, a consistency check) is the call's. Recorded engine transactions (fixtures/m3), edited by CSL.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { verifyPrepared } from '../src/verify.js';
import { verifyComposed } from '../src/compose.js';
import { parseTx } from '../src/ledger.js';
import { Data, dataHex } from '../src/pdata.js';
import {
  recordedComposition, fixtureInputs, editTx, cslOutput, partsOf, STRANGER,
} from './sends-world.js';

const accepted = (r, what) => assert.deepEqual(r.problems, [], `${what}: ${r.problems.join('; ')}`);
const refused = (r, re, what) => assert.ok(r.problems.some((p) => re.test(p)), `${what}: expected ${re}, got: ${r.problems.join('; ') || 'accepted'}`);
const ADA = 1_000_000n;
const plus = (m, unit, q) => new Map([...m].map(([k, v]) => [k, k === unit ? v + q : v]));
const refData = (ref) => {
  const [h, i] = ref.split('#');
  return Data.constr(0, [Data.bytes(h), Data.int(BigInt(i))]);
};
const both = async (o) => [await verifyPrepared(o), await verifyComposed({ ...o, steps: [o] })];

// ---------------------------------------------------------------- a payment is credited only where it is bound

test('a non-payable method is paid nothing alone either, and a payment no Ctx binds is not credited', async () => {
  // upgrade/002: CounterV1.increment, not payable, no Ctx. The page states a 2 ADA payment, and the body pays 2 ADA of
  // the wallet's change to a stranger's key carrying the target's payout tag.
  const i = fixtureInputs('upgrade/002-increment.json').single;
  const tx = parseTx(i.txHex);
  const target = tx.inputs.find((r) => !i.wallet.utxos.has(r));
  const x = JSON.parse(JSON.stringify({ snapshot: { protocolParams: i.chain.params } }));
  const hex = await editTx(i.txHex, x, {
    outputs: (cur) => {
      const parts = cur.map(partsOf);
      const change = parts.findIndex((p) => i.wallet.addresses.includes(p.address));
      parts[change] = { ...parts[change], value: plus(parts[change].value, 'lovelace', -2n * ADA) };
      return [...parts, { address: STRANGER, value: new Map([['lovelace', 2n * ADA]]), datumHex: dataHex(refData(target)) }].map(cslOutput);
    },
  });
  const paid = { ...i, txHex: hex, call: { ...i.call, pay: { lovelace: 2n * ADA, assets: [] } } };
  refused(await verifyPrepared(paid), /not payable/, 'a payment to a non-payable method');
  // The same method marked payable: no Ctx binds the payment, so nothing of it is credited.
  const unmarked = { ...paid, call: { ...paid.call, meta: { ...paid.call.meta, payable: undefined } } };
  for (const r of await both(unmarked)) refused(r, /others receive/, 'a payment no Ctx binds');
});

// --------------------------------------------------------------------- an owned object's source instance

/** guild/017: Guild.retire destroys an owned object (no continuation): its source instance is in its input's datum. */
function retire() {
  const i = fixtureInputs('guild/017-retire.json').single;
  const tx = parseTx(i.txHex);
  const target = tx.inputs.find((r) => !i.wallet.utxos.has(r));
  return { i, target };
}

test('an owned destroy binds its source instance by its input\'s datum', async () => {
  const { i, target } = retire();
  for (const v of await both(i)) accepted(v, 'the recorded retire');
  // The input's datum names another instance than the call: refused.
  const other = 'ab'.repeat(32);
  const moved = { ...i.chain, async utxo(ref) {
    const u = await i.chain.utxo(ref);
    return ref === target ? { ...u, inlineDatum: u.inlineDatum.replace(i.call.instance, other) } : u;
  } };
  assert.ok((await i.chain.utxo(target)).inlineDatum.includes(i.call.instance));
  for (const v of await both({ ...i, chain: moved })) refused(v, /held by another instance/, 'the object under another instance');
});

test('a move binds its source instance too', async () => {
  const c = recordedComposition('guild/005-undefined.json');
  accepted(await verifyComposed({ steps: c.steps, wallet: c.wallet, chain: c.chain, txHex: c.cbor }), 'the recorded transfer');
  const tx = parseTx(c.cbor);
  const move = c.steps.find((s) => s.call.meta.policies?.into);
  const object = [];
  for (const r of tx.inputs) if (!c.wallet.utxos.has(r) && (await c.chain.utxo(r)).value.get(move.deployment.policy + move.call.object) === 1n) object.push(r);
  // The object's datum names another instance than the move's source: refused.
  const moved = { ...c.chain, async utxo(ref) {
    const u = await c.chain.utxo(ref);
    return object.includes(ref) ? { ...u, inlineDatum: u.inlineDatum.replace(move.call.instance, 'ab'.repeat(32)) } : u;
  } };
  refused(await verifyComposed({ steps: c.steps, wallet: c.wallet, chain: moved, txHex: c.cbor }), /held by another instance/, 'a move from another instance');
});
