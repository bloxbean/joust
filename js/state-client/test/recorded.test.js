// Every recorded engine transaction of fixtures/m3 that the page verifies through verifyPrepared or verifyComposed
// (spending calls, creations, owned calls, compositions; a spending call also as a one-call composition) is accepted:
// an over-refusal of a legitimate flow shows here, not in review. Keyed calls, intents, purposes and registrations have
// their own verifiers and tests (keyed.test.js, sends.test.js). fixtures/m0 (JVM-runtime recordings) carries no
// descriptions or page calls; its Market.buy, a purchase that forwards the buyer's payment, is replayed with the
// Market's description.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { verifyPrepared } from '../src/verify.js';
import { verifyComposed } from '../src/compose.js';
import { fixtureInputs } from './sends-world.js';
import { m0Inputs } from './m0-world.js';

const ROOT = new URL('../../../fixtures/m3/', import.meta.url);
const SPENDING = new Set(['action', 'destroy', 'ownedAction', 'ownedDestroy']);

// Refused on main too, and outside this verifier's rules so far (each a follow-up):
// - a payout to another party below its minimum ADA, topped up from the wallet (treasury pays 1 ADA to a key): the
//   page counts the top-up as the wallet's funds going to others. Every recorded Treasury.pay of 1 ADA (the first pays 10).
const KNOWN = new Map(readdirSync(new URL('treasury/', ROOT)).filter((f) => f.endsWith('-pay.json') &&
  JSON.parse(readFileSync(new URL(`treasury/${f}`, ROOT), 'utf8')).call.args.amount === '1000000')
  .map((f) => [`treasury/${f}`, /others receive/]));

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

test('fixtures/m0\'s Market.buy: the payment it forwards is credited; refused only for the wallet\'s top-up of the NFT send', async () => {
  const description = JSON.parse(readFileSync(new URL('./fixtures/market.description.json', import.meta.url), 'utf8'));
  const i = m0Inputs('market/0004-Market.buy.json', {
    description, walletAddress: 'addr_test1qp7wful8sjxgjvpaw8kmal9zj90etff6u4kx963jwy8z9nk079k7ucj6puhs64pmhhyqy9ynespgvakzrq7yr5qlpz3qj6yt43',
    call: { method: 'buy', instance: '96c648b15a79c0c01c608df74e1bca7e4b40879b5224878d26ca5186c60bac95',
      object: '4fcf8e814ebf338a288ea23b452636e1585ac576764fcf506bbf97285b', pay: { lovelace: 100_000_000n } },
  });
  // The known limitation of ADR-0013 §9.2 (refused on main too, before forwarding): the NFT send to the buyer holds
  // 1.02147 ADA of the wallet's to reach its minimum ADA, and a claimed wallet output counts whole as the script's. The
  // forwarded price is credited: the only refusal is that 1.02147 ADA (others receive 103.6 ADA, 102.57853 allowed).
  // sends.test.js accepts the purchase once the send carries its own minimum ADA.
  for (const r of [await verifyPrepared(i), await verifyComposed({ ...i, steps: [i] })]) {
    assert.equal(r.problems.length, 1, r.problems.join('; '));
    assert.match(r.problems[0], /^others receive 103600000 of lovelace, more than the scripts? releases? to them beyond (its|their) claimed sends to the wallet$/);
  }
});
