// ADR-0020 D8 (PR #62 C1, F1): the engine binds a contract's programs to its description's canonical JSON, so the
// description given as the text joust build wrote and given as an object (serialised by engineContracts) prepare the
// same recorded transaction; a changed description is refused with the remedy.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { loadEngine } from '../src/index.js';
import { engineContracts } from '../src/service.js';

const FIXTURES = new URL('../../../fixtures/m3/', import.meta.url).pathname;
const ENGINE = new URL('../engine/joust-runtime.js', import.meta.url).pathname;

function recorded(group) {
  const dir = join(FIXTURES, group);
  if (!existsSync(dir)) return null;
  return JSON.parse(readFileSync(join(dir, readdirSync(dir).sort()[0]), 'utf8'));
}

const x = recorded('vault');
const skip = !x ? 'no fixtures/m3/vault' : !existsSync(ENGINE) ? 'no engine' : false;

async function prepare(engine, contracts) {
  return engine.joust.prepare({ call: JSON.stringify(x.call), snapshot: JSON.stringify(x.snapshot), contracts: engineContracts(contracts),
    zeroSlot: BigInt(x.clock.zeroSlot), zeroTimeMs: BigInt(x.clock.zeroTimeMs), slotLengthMs: BigInt(x.clock.slotLengthMs),
    ttlMs: BigInt(x.ttlMs), networkId: x.networkId, mapNodes: x.mapNodes ?? {}, mapProofs: x.mapProofs ?? {} });
}

test('a description given as text or as an object prepares the recorded transaction; a changed one is refused', { skip }, async () => {
  const engine = await loadEngine();
  const built = x.contracts.map((sha) => JSON.parse(readFileSync(join(FIXTURES, 'contracts', sha + '.json'), 'utf8')));
  const asText = await prepare(engine, built);
  assert.ok(asText.ok, asText.error);
  assert.equal(asText.cbor, x.cbor);
  const asObject = await prepare(engine, built.map((c) => ({ ...c, description: JSON.parse(c.description) })));
  assert.ok(asObject.ok, asObject.error);
  assert.equal(asObject.cbor, x.cbor, 'the object form prepares the same transaction');
  const changed = await prepare(engine, built.map((c) => {
    const d = JSON.parse(c.description);
    d.preamble.description += ' (changed)';
    return { ...c, description: d };
  }));
  assert.equal(changed.ok, false);
  assert.match(changed.error, /the programs were compiled with another description.*joust build/);
});
