// verifyHostResult (src/hostcheck.js), the dev-host client's pre-sign check, against every vector of the protocol's
// verifier fixtures (fixtures/host/verifier, decided by the conformance kit's ReferenceChecker): the same problem codes
// for each, positive and negative, prepare and publish (ADR-0024 I2).
//
// One category is decided differently on purpose: a call on a contract state or owned value with a MerkleMap field.
// The reference verifies its map claims' proofs; verifyHostResult does not, so it refuses every such call as
// notVerifiable (fail closed). The playground offers no map calls (they arrive with ADR-0024 M4).

import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import test from 'node:test';
import { verifyHostResult } from '../src/hostcheck.js';

const dir = new URL('../../../fixtures/host/verifier/', import.meta.url);
const MAP = 'org.julclang.state.api.MerkleMap';

/** Whether the vector's call touches a MerkleMap field (independently of the checker). */
function mapCall(v) {
  if (v.method !== 'prepare') return false;
  const d = v.anchors.description;
  if (!d.definitions?.[MAP]) return false;
  const x = d['x-joust'];
  const { call } = v.request;
  const meta = [...x.actions, ...x.creations].find((m) => m.kind === call.kind && m.name === call.method);
  if (!meta) return false;
  const ref = call.kind.startsWith('owned') ? meta.owned : x.state.$ref.slice('#/definitions/'.length);
  const def = d.definitions[ref];
  return (def.anyOf?.[0]?.fields ?? []).some((f) => f.$ref === '#/definitions/' + MAP);
}

const files = readdirSync(dir).filter((f) => f.endsWith('.json')).sort();
const counts = { agree: 0, maps: 0, positive: 0, negative: 0 };

for (const file of files) {
  const v = JSON.parse(readFileSync(new URL(file, dir)));
  const maps = mapCall(v);
  test(`${maps ? 'map call refused' : 'reference agreement'} on ${file}`, () => {
    const { problems, sites } = verifyHostResult(v);
    const got = [...new Set(problems)].sort();
    if (maps) {
      counts.maps++;
      assert.deepEqual(got, ['notVerifiable'], sites.join('; '));
      return;
    }
    counts.agree++;
    counts[v.violations.length ? 'negative' : 'positive']++;
    assert.deepEqual(got, [...new Set(v.violations)].sort(), sites.join('; '));
  });
}

test('every vector was decided (none skipped)', () => {
  assert.equal(counts.agree + counts.maps, files.length);
  assert.ok(files.length >= 333, `${files.length} vectors`);
  assert.equal(counts.maps, 31, 'the map calls refused as notVerifiable');
  assert.ok(counts.positive >= 46 && counts.negative >= 250, JSON.stringify(counts));
});

test('a body the checker cannot read is malformed, never accepted', () => {
  const v = JSON.parse(readFileSync(new URL('accept-pointer-at-one.json', dir)));
  for (const cbor of ['', 'zz', '84', v.result.prepared.cbor + '00', v.result.prepared.cbor.slice(0, -2)]) {
    const r = verifyHostResult({ ...v, result: { prepared: { ...v.result.prepared, cbor } } });
    assert.ok(r.problems.length > 0, cbor.slice(0, 20));
  }
  assert.deepEqual(verifyHostResult(null).problems, ['malformed']);
  assert.deepEqual(verifyHostResult({ ...v, anchors: undefined }).problems, ['malformed']);
});

test('the report comes from the body: signers, net outflow, the created id', () => {
  const v = JSON.parse(readFileSync(new URL('accept-pointer-at-one.json', dir)));
  const { problems, report } = verifyHostResult(v);
  assert.deepEqual(problems, []);
  assert.equal(report.plan.fee, v.result.prepared.plan.fee);
  assert.deepEqual(report.signers.sort(), [...v.result.prepared.signers].sort());
  assert.equal(report.walletNet.lovelace, report.plan.fee, 'an increment costs the wallet its fee');
  const init = files.map((f) => JSON.parse(readFileSync(new URL(f, dir)))).find((x) => x.method === 'prepare'
    && x.request.call.kind === 'init' && !x.violations.length && !mapCall(x) && x.result.prepared.instance);
  const r = verifyHostResult(init);
  assert.deepEqual(r.problems, []);
  assert.equal(r.report.created, init.result.prepared.instance);
});

// ---- review round 3 (Codex r2): the checker reads Plutus data as the pinned JuLC decoder does, constructor indices
// exactly, addresses as the received bytes.

const A64 = 'ab'.repeat(64);
// Each case's verdict under the pinned decoder (julc-core 0.1.0-pre22 PlutusDataCborDecoder.decode, run on these hex
// strings): true decodes, false is refused.
const JULC_PRE22 = [
  ['5840' + A64, true, 'a 64-byte definite string'],
  ['5841' + A64 + 'ab', false, 'a 65-byte definite string'],
  ['5f5840' + A64 + '41abff', true, '65 bytes in chunks of at most 64'],
  ['5f5841' + A64 + 'abff', false, 'an indefinite string with a 65-byte chunk'],
  ['c25841' + A64 + 'ab', false, 'a bignum with a 65-byte definite payload'],
  ['c25f5840' + A64 + '41abff', true, 'a bignum with a chunked payload'],
  ['d8024101', false, 'tag 2 in a two-byte header'],
  ['6161', false, 'a text string'],
  ['f5', false, 'a simple value'],
  ['d8799f0102ff', true, 'a constructor'],
  ['d866820780', true, 'a general constructor, a definite pair'],
  ['d8669f0780ff', true, 'a general constructor, an indefinite pair'],
  ['d8669f079f01ffff', true, 'an indefinite pair with indefinite fields'],
  ['d8669f0780', false, 'an indefinite pair without its break'],
  ['d8669f078001ff', false, 'an indefinite pair of three'],
  ['d8669f07ff', false, 'an indefinite pair of one'],
];

test('Plutus data as the pinned JuLC decoder reads it: byte strings of at most 64 bytes per chunk', async () => {
  const { plutusData } = await import('../src/hostcheck.js');
  for (const [hex, ok, what] of JULC_PRE22) {
    if (ok) assert.doesNotThrow(() => plutusData(hex), what);
    else assert.throws(() => plutusData(hex), what);
  }
  assert.deepEqual(plutusData('5f5840' + A64 + '41abff').bytes.length, 65);
  assert.deepEqual({ ...plutusData('d8669f079f01ffff') }, { constr: 7, fields: [{ int: 1n }] });
});

test('constructor indices beyond safe integers fail closed (the data model would equate distinct ones)', async () => {
  const { plutusData } = await import('../src/hostcheck.js');
  const { decodeData, dataEqual } = await import('../src/pdata.js');
  const word = (n) => '1b' + n.toString(16).padStart(16, '0');
  const definite = (n) => 'd86682' + word(n) + '80';
  const indefinite = (n) => 'd8669f' + word(n) + '80ff';
  const two53 = 2n ** 53n;
  // Both encodings, through the shared decoder (decodeData, behind dataEqual and verifyPrepared) and the checker's:
  // an index the data model cannot hold exactly is refused before conversion, so 2^53 and 2^53 + 1 never compare
  // equal (JuLC would accept them: the one place these decoders are stricter, documented); a safe one is kept.
  for (const enc of [definite, indefinite]) {
    for (const n of [two53, two53 + 1n, 2n ** 64n - 1n]) {
      assert.throws(() => decodeData(enc(n)), /beyond/);
      assert.throws(() => plutusData(enc(n)));
    }
    assert.equal(decodeData(enc(two53 - 1n)).constr, Number.MAX_SAFE_INTEGER);
    assert.equal(plutusData(enc(two53 - 1n)).constr, Number.MAX_SAFE_INTEGER);
    assert.equal(dataEqual(decodeData(enc(two53 - 1n)), decodeData(enc(two53 - 2n))), false);
  }
});

test('an output address that does not round-trip to its received bytes is refused (a pointer with trailing bytes)', async () => {
  const { addressHex } = await import('../src/ledger.js');
  const v = JSON.parse(readFileSync(new URL('accept-pointer-at-one.json', dir)));
  const payer = addressHex(v.request.call.address);
  assert.ok(v.result.prepared.cbor.includes('5839' + payer));
  const pointer = '40' + payer.slice(2, 58) + '010203' + '00';   // the payer's key, a pointer, one trailing byte
  const cbor = v.result.prepared.cbor.replace('5839' + payer, '5821' + pointer);
  const r = verifyHostResult({ ...v, result: { prepared: { ...v.result.prepared, cbor } } });
  assert.deepEqual(r.problems, ['malformed']);
  assert.match(r.sites[0], /round-trip/);
  // The same pointer without the trailing byte encodes exactly: read (and refused only by the body's other rules).
  const exact = v.result.prepared.cbor.replace('5839' + payer, '5820' + pointer.slice(0, -2));
  assert.ok(!verifyHostResult({ ...v, result: { prepared: { ...v.result.prepared, cbor: exact } } }).sites.some((s) => /round-trip/.test(s)));
});

test('the report carries the state before and after, both from the trusted chain and the verified body', () => {
  const v = JSON.parse(readFileSync(new URL('accept-pointer-at-one.json', dir)));
  const { problems, report } = verifyHostResult(v);
  assert.deepEqual(problems, []);
  assert.equal(BigInt(report.after.count) - BigInt(report.before.count), BigInt(v.request.call.args.by));
  assert.equal(report.removed, false);
});

test('keyed calls report the keyed object\'s value before and after, a removal as removed', () => {
  const read = (f) => verifyHostResult(JSON.parse(readFileSync(new URL(f, dir))));
  const shape = (r) => ({ problems: r.problems, before: r.report.before !== null, after: r.report.after !== null, removed: r.report.removed });
  assert.deepEqual(shape(read('registry-m3-upgrade.json')), { problems: [], before: true, after: true, removed: false });
  assert.deepEqual(shape(read('registry-m3-claim.json')), { problems: [], before: false, after: true, removed: false });
  for (const f of ['registry-m3-release.json', 'Registry-remove.json']) {
    assert.deepEqual(shape(read(f)), { problems: [], before: true, after: false, removed: true }, f);
  }
  for (const f of ['registry-m3-retire.json', 'Registry-retire.json']) {
    assert.deepEqual(shape(read(f)), { problems: [], before: true, after: true, removed: false }, f);
  }
  assert.deepEqual(shape(read('registry-m3-init.json')), { problems: [], before: false, after: true, removed: false });
  // The update's states are the object's value, decoded with the description, and they differ.
  const up = read('registry-m3-upgrade.json').report;
  assert.equal(typeof up.before, 'object');
  assert.notDeepEqual(up.before, up.after);
});
