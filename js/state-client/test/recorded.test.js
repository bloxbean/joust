// Every recorded engine transaction of fixtures/m3 that the page verifies through verifyPrepared or verifyComposed
// (spending calls, creations, owned calls, compositions; a spending call also as a one-call composition) is accepted:
// an over-refusal of a legitimate flow shows here, not in review. Keyed calls, intents, purposes and registrations have
// their own verifiers and tests (keyed.test.js, sends.test.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { verifyPrepared } from '../src/verify.js';
import { verifyComposed } from '../src/compose.js';
import { fixtureInputs } from './sends-world.js';

const ROOT = new URL('../../../fixtures/m3/', import.meta.url);
const SPENDING = new Set(['action', 'destroy', 'ownedAction', 'ownedDestroy']);

// Refused on main too, and outside this verifier's rules so far (each a follow-up):
// - a payout to another party below its minimum ADA, topped up from the wallet (treasury pays 1 ADA to a key): the
//   page counts the top-up as the wallet's funds going to others.
const KNOWN = new Map([...['004', '005', '006', '007', '008', '009', '010', '011', '012'].map((n) => [`treasury/${n}-pay.json`, /others receive/])]);

test('every recorded call the page verifies is accepted, alone and as a one-call composition', async () => {
  let verified = 0;
  const refused = [];
  for (const dir of readdirSync(ROOT).sort()) {
    if (dir === 'contracts') continue;
    for (const file of readdirSync(new URL(`${dir}/`, ROOT)).sort()) {
      const name = `${dir}/${file}`;
      const i = fixtureInputs(name);
      if (!i) continue;
      const results = [];
      if (i.steps) results.push(['composed', await verifyComposed(i)]);
      else {
        results.push(['alone', await verifyPrepared(i.single)]);
        // A composition carries no governor's approval (yet): those calls are verified alone.
        if (SPENDING.has(i.single.call.kind) && !i.single.call.governor) results.push(['composed', await verifyComposed({ ...i.single, steps: [i.single] })]);
      }
      for (const [how, r] of results) {
        verified++;
        if (KNOWN.has(name)) assert.ok(r.problems.some((p) => KNOWN.get(name).test(p)), `${name} (${how}): a known refusal changed: ${r.problems.join('; ') || 'accepted'}`);
        else if (r.problems.length) refused.push(`${name} (${how}): ${r.problems.join('; ')}`);
      }
    }
  }
  assert.deepEqual(refused, [], `refused:\n${refused.join('\n')}`);
  assert.ok(verified >= 190, `only ${verified} verifications`);
});
