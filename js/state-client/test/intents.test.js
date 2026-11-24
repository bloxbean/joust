// A batch from the batcher's side (ADR-0004, verifyBatch): the batcher pays the fee and nothing more. A batched intent
// may owe the batcher too (it is also an intent's owner): that refund or payout is the validator's obligation, never
// the batcher's change. Recorded engine transactions (fixtures/m3/pool), edited by CSL.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { verifyBatch } from '../src/intents.js';
import { parseTx } from '../src/ledger.js';
import { recorded, walletOf, chainOf, deploymentOf, editTx, cslOutput, partsOf, STRANGER } from './sends-world.js';

const accepted = (r, what) => assert.deepEqual(r.problems, [], `${what}: ${r.problems.join('; ')}`);
const refused = (r, re, what) => assert.ok(r.problems.some((p) => re.test(p)), `${what}: expected ${re}, got: ${r.problems.join('; ') || 'accepted'}`);
const plus = (m, unit, q) => new Map([...m].map(([k, v]) => [k, k === unit ? v + q : v]));

test('a batch whose signer is also owed an intent\'s refund: the refund is not the batcher\'s change', async () => {
  const r = recorded('pool/018-batch.json');
  const tx = parseTx(r.cbor);
  const base = walletOf(r.x);
  const verify = (txHex, wallet) => verifyBatch({ description: r.description, deployment: deploymentOf(r.x, r.description), txHex,
    call: { instance: r.x.call.instance }, wallet, chain: chainOf(r.x) });
  accepted(await verify(r.cbor, base), 'the recorded batch');
  // The batcher also owns the intent refunded at output 0 (a tagged output to its owner's address).
  const owed = tx.outputs[0];
  assert.ok(owed.datum, 'output 0 carries its tag');
  const wallet = { ...base, addresses: [...base.addresses, owed.address] };
  accepted(await verify(r.cbor, wallet), 'the recorded batch, the batcher owed a refund');
  // The service keeps the refund but diverts as much of the batcher's change to a stranger: the batcher's net loss is
  // still the fee, but it lost the refund it was owed.
  const q = owed.value.get('lovelace');
  const change = tx.outputs.findIndex((o) => base.addresses.includes(o.address) && !o.datum);
  const diverted = await editTx(r.cbor, r.x, {
    outputs: (cur) => [...cur.map(partsOf).map((p, i) => (i === change ? { ...p, value: plus(p.value, 'lovelace', -q) } : p)),
      { address: STRANGER, value: new Map([['lovelace', q]]) }].map(cslOutput),
  });
  refused(await verify(diverted, wallet), /the wallet loses \d+ lovelace of its own, more than the fee/, 'the change diverted');
});
