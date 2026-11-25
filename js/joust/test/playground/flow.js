// A playground call driven in Node exactly as the page drives it (ADR-0024 D7): prerequisites and publications checked
// by check.js, the call prepared by the session's dev host, checked (verifyHostResult over the pinned anchors, the
// shared verifiers included), signed by every required identity, submitted. Tests of M4's call kinds use it.

import assert from 'node:assert/strict';
import { anchorsOf, checkPublication, pageCheck } from '../../playground/check.js';

export const MAX_FEE = 5_000_000n;

/** The anchors the page derives for a contract of a session's pinned build (its server's `contract` route). */
export async function anchorsFor(s, contract) {
  const entry = s.entries.find((e) => JSON.parse(e.description).preamble?.title === contract);
  assert.ok(entry, `${contract} is in the build`);
  let verifiers = s.verifiers;
  if (!verifiers) {
    const e = await (await import('@julclang/joust-runtime')).loadEngine();
    verifiers = {};
    for (const [k, v] of [['outputVerifier', 'OutputVerifier'], ['mapVerifier', 'MapVerifier']]) {
      verifiers[k] = (await e.joust.validatorScript({ validator: v })).cborHex;
    }
  }
  return anchorsOf({ description: JSON.parse(entry.description), script: entry.script, verifiers });
}

export async function snapshotsOf(s, cbor) {
  const { parseTx } = await import('@julclang/state-client');
  const tx = parseTx(cbor);
  return s.outputs([...new Set([...tx.inputs, ...tx.referenceInputs, ...tx.collateral].map((i) => (typeof i === 'string' ? i : i.ref)))]);
}

/** Every signer's witnesses on the body (the caller first), assembled. */
export async function signedBy(wallets, prepared) {
  const { assemble, ledgerTx } = await import('@julclang/state-client/ledger');
  void ledgerTx;
  let cbor = prepared.cbor;
  for (const w of wallets) cbor = assemble(cbor, await w.signTx(prepared.cbor, true)).cbor;
  return cbor;
}

/** Publishes a contract's prerequisites as {@code payer}, each checked as the page checks it. */
export async function publishAll(s, contract, payer) {
  const anchors = await anchorsFor(s, contract);
  const params = await s.protocolParams();
  for (let round = 0; round < 8; round++) {
    const missing = await s.prerequisites(contract);
    if (!missing.length) return;
    const p = missing[0];
    const r = await s.publish(contract, p.id, payer.address);
    if (!r?.prepared) continue;
    const { publicationExpectation } = await import('@julclang/state-client');
    const { expected } = publicationExpectation({ prerequisite: p, anchors, networkId: 0 });
    const hash = p.id.split(':')[1];
    const node = { scriptsAt: {}, registrations: { [hash]: await s.registered(hash) } };
    if (expected?.target) node.scriptsAt[expected.target] = (await s.utxos(expected.target)).map((u) => u.reference_script_hash).filter(Boolean);
    const checked = checkPublication({ result: r, contract, prerequisite: p.id, address: payer.address, anchors,
      snapshots: await snapshotsOf(s, r.prepared.cbor), node, params, maxFee: MAX_FEE });
    assert.deepEqual(checked.sites, [], `the publication ${p.id}`);
    await s.submit(r.prepared.reservation, await signedBy([payer], r.prepared));
  }
  assert.fail('the prerequisites did not settle');
}

/**
 * Prepares, checks and submits {@code call} as {@code caller} (with {@code cosigners}' wallets for a quorum call).
 * Returns { res, report } or throws the check's sites.
 */
export async function run(s, call, { caller, cosigners = [], pageLimits } = {}) {
  const anchors = await anchorsFor(s, call.contract);
  const params = await s.protocolParams();
  const request = { ...call, address: caller.address };
  if (cosigners.length) request.cosigners = cosigners.map((w) => w.keyHash);
  const r = await s.prepare(request);
  const x = anchors.description['x-joust'];
  const meta = [...x.actions, ...x.creations].find((m) => m.kind === request.kind && m.name === request.method);
  const checked = await pageCheck({ result: r, request: pageLimits ? { ...request, pageLimits } : request, meta, anchors,
    snapshots: await snapshotsOf(s, r.prepared.cbor), params, maxFee: MAX_FEE, clock: s.status().clock,
    signer: { address: caller.address, keyHash: caller.keyHash }, walletUtxos: await s.utxos(caller.address) });
  if (checked.problems.length) {
    await s.discard(r.prepared.reservation).catch(() => {});
    throw Object.assign(new Error('refused: ' + checked.problems.join('; ')), { problems: checked.problems });
  }
  const res = await s.submit(r.prepared.reservation, await signedBy([caller, ...cosigners], r.prepared));
  return { res, report: checked.report, prepared: r.prepared };
}
