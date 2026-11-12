// ADR-0018 M0, "Option as Optional end to end", the JS leg: a .jsc 2 contract's Option<Key> state field and redeemer
// argument through the page codec (data.js), from the generated description of examples/dsl's Pledges.jsc. The expected
// CBOR is the JVM's (fixtures/pledges.vectors.json, kept current by examples/dsl's Dsl2JsFixturesTest).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { codec, DataError } from '../src/data.js';
import { Data, dataHex } from '../src/pdata.js';

const read = (name) => JSON.parse(readFileSync(new URL(`./fixtures/${name}`, import.meta.url), 'utf8'));
const description = read('pledges.description.json');
const vectors = read('pledges.vectors.json');
const c = codec(description);
const STATE = { $ref: '#/definitions/org.julclang.state.examples.dsl.Pledges.State' };
const CALLS = { $ref: '#/definitions/org.julclang.state.examples.dsl.PledgesValidator.Calls' };
const owner = '0a'.repeat(28), ann = 'a1'.repeat(28), ben = 'b1'.repeat(28);

const state = (lastBacker) => ({
  owner, goal: 10_000_000n, deadline: BigInt(vectors.deadline), raised: 0n,
  shares: [{ to: ann, bps: 6_000n }, { to: ben, bps: 4_000n }], tag: '0102', lastBacker,
});

test('Pledges.lastBacker (Option<Key> in the state): Some and None encode as the JVM does, and decode back', () => {
  assert.equal(dataHex(c.encode(STATE, state(ben))), vectors.stateSome);
  assert.equal(dataHex(c.encode(STATE, state(null))), vectors.stateNone);
  const some = c.decode(STATE, vectors.stateSome);
  assert.equal(some.lastBacker, ben);
  assert.deepEqual(some.shares, [{ to: ann, bps: 6_000n }, { to: ben, bps: 4_000n }]);
  assert.equal(c.decode(STATE, vectors.stateNone).lastBacker, null);
  // Some is constructor 0 with the key, None constructor 1 without fields (D5).
  assert.deepEqual(c.encode(STATE, state(null)).fields[6], Data.constr(1, []));
  assert.equal(c.encode(STATE, state(ben)).fields[6].constr, 0);
});

test('setBacker(who: Option<Key>) in the redeemer: Some and None', () => {
  assert.equal(dataHex(c.encode(CALLS, { $variant: 'SetBacker', who: ben })), vectors.setBackerSome);
  assert.equal(dataHex(c.encode(CALLS, { $variant: 'SetBacker', who: null })), vectors.setBackerNone);
  assert.deepEqual(c.decode(CALLS, vectors.setBackerSome), { $variant: 'SetBacker', who: ben });
  assert.deepEqual(c.decode(CALLS, vectors.setBackerNone), { $variant: 'SetBacker', who: null });
});

test('Some(None) is refused: no Option nests another, and such data does not decode as an Option<Key>', () => {
  const isOption = (s) => s?.anyOf?.length === 2 && s.anyOf[0].title === 'Some' && s.anyOf[1].title === 'None';
  const nested = [];
  const walk = (s, path) => {
    if (!s || typeof s !== 'object') return;
    if (isOption(s) && isOption(s.anyOf[0].fields[0])) nested.push(path);
    for (const [k, v] of Object.entries(s)) walk(v, `${path}.${k}`);
  };
  walk(description.definitions, 'definitions');
  assert.deepEqual(nested, [], 'the description has no Option<Option<…>>');
  const pd = c.encode(STATE, state(null));
  pd.fields[6] = Data.constr(0, [Data.constr(1, [])]);
  assert.throws(() => c.decode(STATE, dataHex(pd)), DataError);
  assert.throws(() => c.decode(CALLS, dataHex(Data.constr(2, [Data.constr(0, [Data.constr(1, [])])]))), DataError);
});
