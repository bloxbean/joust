// Protocol 1's publication vectors (fixtures/host/verifier, method "publish") through the page's publication check
// (verifyPublication, ADR-0024 D7 step 3): every positive is accepted, and every negative is refused with exactly the
// vector's code(s) while the positive it mutates (its `source`) is accepted, so each is refused for the right reason.
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import test from 'node:test';
import { verifyPublication, publicationExpectation } from '../src/publication.js';

const dir = new URL('../../../fixtures/host/verifier/', import.meta.url);
const load = (file) => JSON.parse(readFileSync(new URL(file, dir)));

/** The page's inputs, assembled from a vector as the conformance kit says (only `result` comes from the host). */
export function publicationInput(v) {
  const utxos = new Map(Object.entries(v.resolved).map(([r, u]) => [r, {
    address: u.address, value: new Map(u.amount.map((a) => [a.unit.replace('.', ''), BigInt(a.quantity)])),
  }]));
  const { prepared } = v.result;
  return {
    prepared: { cbor: prepared.cbor, txHash: prepared.txHash, validFrom: prepared.validFrom, validTo: prepared.validTo,
      signers: prepared.signers },
    prerequisite: v.request.prerequisite,
    anchors: v.anchors,
    contract: v.request.contract,
    payer: v.request.address,
    networkId: v.networkId,
    now: v.request.now,
    ttlSlots: v.request.ttlSlots,
    chain: {
      params: v.protocolParams,
      utxo: async (r) => utxos.get(r) ?? null,
      scriptsAt: async (a) => v.node.scriptsAt?.[a] ?? null,
      registration: async (h) => v.node.registrations?.[h] ?? null,
    },
    limits: { maxFee: BigInt(v.maxFee) },
  };
}

const vectors = readdirSync(dir).filter((f) => f.endsWith('.json')).map((f) => [f, load(f)]).filter(([, v]) => v.method === 'publish');
const positives = vectors.filter(([, v]) => v.violations.length === 0);
const negatives = vectors.filter(([, v]) => v.violations.length > 0);

test('the publication vectors are all here', () => {
  assert.ok(positives.length >= 11, `${positives.length} positives`);
  assert.ok(negatives.length >= 48, `${negatives.length} negatives`);
  for (const [, v] of negatives) assert.ok(positives.some(([f]) => f === v.source + '.json'), `${v.source} is a positive`);
});

for (const [file, v] of positives) {
  test('publication accepted: ' + file, async () => {
    const r = await verifyPublication(publicationInput(v));
    assert.deepEqual(r.problems, []);
    assert.deepEqual(r.violations, []);
  });
}

for (const [file, v] of negatives) {
  test(`publication refused (${v.violations.join(', ')}): ${file}`, async () => {
    const source = await verifyPublication(publicationInput(load(v.source + '.json')));
    assert.deepEqual(source.problems, [], `its source ${v.source} must pass`);
    const r = await verifyPublication(publicationInput(v));
    assert.deepEqual(r.violations, v.violations, r.problems.join('; '));
    if (v.check) assert.ok(r.problems.some((p) => p.startsWith(v.check + ':')), `refused by ${v.check}: ${r.problems.join('; ')}`);
  });
}

test('the expectation a page derives for each kind', () => {
  const v = load('Dex-publish-1.json');
  const ref = publicationExpectation({ prerequisite: v.request.prerequisite, anchors: v.anchors, networkId: 0 });
  assert.deepEqual(ref.problems, []);
  assert.equal(ref.expected.target, v.anchors.address);
  assert.equal(ref.expected.holder, true);
  assert.equal(ref.expected.registration, false);
  const ov = v.anchors.outputVerifier;
  const reg = publicationExpectation({ prerequisite: { id: 'stakeRegistration:' + ov.hash, kind: 'stakeRegistration' },
    anchors: v.anchors, networkId: 0 });
  assert.deepEqual(reg.problems, []);
  assert.equal(reg.expected.holder, false);
  assert.deepEqual(reg.expected.credential, { type: 'script', hash: ov.hash });
  assert.match(reg.expected.rewardAddress, /^stake_test1/);
  const listed = publicationExpectation({ prerequisite: { id: 'verifier:' + ov.hash, kind: 'verifier', target: v.anchors.address },
    anchors: v.anchors, networkId: 0 });
  assert.deepEqual(listed.problems, ['publicationShape/kind: the listed target is not the anchored one']);
});

test('a body that is not a transaction is malformed', async () => {
  const v = load('Dex-publish-1.json');
  const input = publicationInput(v);
  input.prepared = { ...input.prepared, cbor: input.prepared.cbor + '00' };
  const r = await verifyPublication(input);
  assert.deepEqual(r.violations, ['malformed']);
});

test('the page\'s own inputs decide the window, the fee limit, wallet inputs and absence', async () => {
  const v = load('Dex-publish-3.json');
  const run = async (edit) => {
    const input = publicationInput(v);
    edit(input);
    return (await verifyPublication(input)).problems.map((p) => p.slice(0, p.indexOf(':')));
  };
  const validTo = Number(v.result.prepared.validTo);
  // The upper bound must lie in (sent now, sent now + ttlSlots].
  assert.deepEqual(await run((i) => { i.now = validTo - v.request.ttlSlots - 1; }), ['upperBound/window']);
  assert.deepEqual(await run((i) => { i.now = validTo; }), ['upperBound/window']);
  assert.deepEqual(await run((i) => { i.limits = { maxFee: 1n }; }), ['fee/max']);
  // An input the chain places at another address: not the wallet's (and the payer's key then signs for nothing).
  assert.deepEqual((await run((i) => {
    const utxo = i.chain.utxo;
    i.chain.utxo = async (r) => ({ ...(await utxo(r)), address: v.anchors.address });
  })).sort(), ['planMismatch/signers', 'scriptInput/foreign']);
  // An input the chain does not know: unresolved, and nothing to balance.
  assert.deepEqual(await run((i) => { i.chain.utxo = async () => null; }), ['unresolvedInput/spending']);
  // No registration answer: absence is not established.
  assert.deepEqual(await run((i) => { i.chain.registration = async () => null; }), ['publicationPresence/registration']);
  // Another network selected than the payer's.
  assert.ok((await run((i) => { i.networkId = 1; })).includes('network/client'));
});
