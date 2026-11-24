// verifyHostResult (src/hostcheck.js), the dev-host client's pre-sign check, against every vector of the protocol's
// verifier fixtures (fixtures/host/verifier, decided by the conformance kit's ReferenceChecker): the same problem codes
// for each, positive and negative, prepare and publish (ADR-0024 I2).
// Map calls included: their claims and MPF proofs are checked as the reference checks them (ADR-0024 M4).

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
const counts = { maps: 0, positive: 0, negative: 0 };

for (const file of files) {
  const v = JSON.parse(readFileSync(new URL(file, dir)));
  const maps = mapCall(v);
  test(`reference agreement on ${file}${maps ? ' (a map call)' : ''}`, () => {
    const { problems, sites } = verifyHostResult(v);
    const got = [...new Set(problems)].sort();
    if (maps) counts.maps++;
    counts[v.violations.length ? 'negative' : 'positive']++;
    assert.deepEqual(got, [...new Set(v.violations)].sort(), sites.join('; '));
  });
}

test('every vector was decided as the reference decides it (none skipped, none excluded)', () => {
  assert.equal(counts.positive + counts.negative, files.length);
  assert.ok(files.length >= 333, `${files.length} vectors`);
  assert.equal(counts.maps, 31, 'the map calls are among them');
  assert.ok(counts.positive >= 60 && counts.negative >= 270, JSON.stringify(counts));
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
