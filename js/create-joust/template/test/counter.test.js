// Counter, offline (ADR-0018 D14): `npm test` (joust test) builds, then runs this against an in-memory ledger. The
// runtime engine prepares each call as on a chain and evaluates the generated validators; the attack suite runs on
// every script transaction, and the validators must refuse each of its mutations. No DevKit: the same calls on a chain
// are test/devkit/counter.test.js.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { offline } from '@julclang/joust/test';
import { Counter } from '../build/joust/Counter.js';

const contracts = JSON.parse(readFileSync(new URL('../build/joust/contracts.json', import.meta.url), 'utf8'));

test('a counter: create, increment, refuse, close (offline)', { timeout: 300_000 }, async () => {
  const net = await offline({ contracts });
  const owner = await net.wallet(100);
  const counter = new Counter(await net.options('app.Counter', owner));
  const { instance } = await counter.deploy(owner, {});
  await instance.increment(owner, { by: 5n });
  await instance.increment(owner, { by: 2n });
  assert.equal((await instance.state()).count, 7n);
  await assert.rejects(instance.increment(owner, { by: 0n }), /increment must be positive/);
  // Only the owner closes it.
  const stranger = await net.wallet(10);
  await assert.rejects(instance.close(stranger));
  await instance.close(owner);
  assert.equal(await instance.state(), null);
  // Every mutation of the attack suite (a replaced datum, skimmed or added value, a redirected state output, a dropped
  // signer, ...) was refused by the validators.
  assert.ok(net.ledger.attacks().length > 0);
  assert.deepEqual(net.ledger.acceptedAttacks(), []);
});
