// The page's pre-sign check (ADR-0024 D7 step 3, I2), as plain functions so Node tests run exactly what the page runs:
// the anchors a contract is checked against (derived from the build the session pinned, never from the host), and
// protocol 1's D13 check of a `prepare` or `publish` result (verifyHostResult, the conformance kit's reference
// checker) over the request the page sent, the trusted chain's outputs and parameters, and the host's result.

import { verifyHostResult, verifyPrepared, plutusScriptHash, enterpriseAddress, baseAddress, rewardAddress } from '@julclang/state-client';

/** contracts.json's script is the CBOR byte string of the program's CBOR (double-wrapped): the hash is over the inner. */
export function unwrapScript(hex) {
  const head = parseInt(hex.slice(0, 2), 16);
  const skip = head === 0x59 ? 6 : head === 0x58 ? 4 : head === 0x5a ? 10 : head >= 0x40 && head < 0x58 ? 2 : 0;
  return hex.slice(skip);
}

/**
 * The anchors of a pinned build entry ({ description, script, verifiers }): its policy and address (network 0), and the
 * shared verifiers' (the output verifier for sends, the map verifier for MerkleMaps: ADR-0007 D5, ADR-0002 D9) from the
 * same pinned build, each { language, hash, address, rewardAddress }.
 */
export function anchorsOf({ description, script, verifiers = {} }) {
  const hash = plutusScriptHash(unwrapScript(script), 3);
  const x = description['x-joust'] ?? {};
  // A stakeSelf contract's address is a base address whose stake credential is its own script (ADR-0010).
  const cred = { type: 'script', hash };
  const address = x.stakeSelf === true ? baseAddress(cred, cred, 0) : enterpriseAddress(cred, 0);
  const anchors = { description, policy: hash, address, language: 'PlutusScriptV3' };
  for (const key of ['outputVerifier', 'mapVerifier']) {
    if (typeof verifiers?.[key] !== 'string') continue;
    const h = plutusScriptHash(unwrapScript(verifiers[key]), 3);
    const c = { type: 'script', hash: h };
    anchors[key] = { language: 'PlutusScriptV3', hash: h, address: enterpriseAddress(c, 0), rewardAddress: rewardAddress(c, 0) };
  }
  return anchors;
}

/**
 * A prepared call's check. {@code result}: the session's prepare answer ({ prepared, now, ttlSlots }); {@code request}:
 * the call exactly as the page sent it; {@code snapshots}: the chain's outputs for the body's inputs, reference inputs
 * and collateral (protocol snapshot encoding, by ref); {@code params}: the chain's protocol parameters.
 */
export function checkCall({ result, request, anchors, snapshots, params, maxFee, clock }) {
  // {@code clock}: the session's slot clock ({ zeroSlot, zeroTimeMs, slotLengthMs }), which keyed windows are read in.
  return verifyHostResult({ method: 'prepare', request: { now: result.now, ttlSlots: result.ttlSlots, call: request }, networkId: 0,
    maxFee: String(maxFee), anchors, resolved: snapshots, protocolParams: params, ...(clock ? { clock } : {}),
    result: { prepared: result.prepared } });
}

/**
 * A publication's check. {@code node}: { scriptsAt: { holder address: [script hash] }, registrations: { hash: bool } },
 * the chain's answers for the prerequisite's holder and credential.
 */
export function checkPublication({ result, contract, prerequisite, address, anchors, snapshots, node, params, maxFee }) {
  return verifyHostResult({ method: 'publish', request: { now: result.now, ttlSlots: result.ttlSlots, contract, prerequisite, address },
    networkId: 0, maxFee: String(maxFee), anchors, resolved: snapshots, node, protocolParams: params, result: { prepared: result.prepared } });
}

/** A protocol snapshot output as the page verifier reads one. */
export function snapToUtxo(u) {
  return { ref: `${u.tx_hash}#${u.output_index}`, txHash: u.tx_hash, index: u.output_index, address: u.address,
    value: new Map(u.amount.map((a) => [a.unit, BigInt(a.quantity)])), inlineDatum: u.inline_datum ?? null,
    datumHash: u.data_hash ?? null, referenceScriptHash: u.reference_script_hash ?? null };
}

const KEYED_KINDS = new Set(['keyedInit', 'keyedAction', 'keyedRemove', 'keyedRetire']);
/** Whether a call is keyed (a keyed kind, or a keyed contract's creation with its list heads; ADR-0016 D11). */
export function isKeyedRequest(description, request) {
  const k = description['x-joust']?.keyed;
  const keyed = Array.isArray(k) ? k.length > 0 : !!k && Object.keys(k).length > 0;
  return KEYED_KINDS.has(request.kind) || (request.kind === 'init' && keyed);
}

/**
 * The page's whole check of a prepared call before anyone signs it (D7 step 3, I2): protocol 1's D13 rules
 * (verifyHostResult: the conformance kit's reference checker, keyed and map rules included), and the page verifier
 * (verifyPrepared) besides for an unkeyed call; either refusing refuses. The shared verifiers the page verifier allows
 * are the pinned anchors', at the reference inputs of the trusted chain that hold their scripts. Returns { problems,
 * report }: the report (what the review shows) is verifyHostResult's, read from the body.
 *
 * @param o.signer       { address, keyHash } of the caller
 * @param o.walletUtxos  the caller's outputs (snapshot encoding), from the chain
 * @param o.clock        the session's slot clock (keyed windows)
 */
export async function pageCheck({ result, request, meta, anchors, snapshots, params, maxFee, clock, signer, walletUtxos }) {
  const host = checkCall({ result, request, anchors, snapshots, params, maxFee, clock });
  if (host.problems.length) return { problems: host.sites, report: host.report };
  // The keyed rules are verifyHostResult's (the reference's KeyedRules, ported exactly); the page verifier's own keyed
  // checks need the designation journal the playground keeps elsewhere, so they are not run twice.
  if (isKeyedRequest(anchors.description, request)) return { problems: [], report: host.report };
  const outputs = new Map(Object.entries(snapshots).map(([ref, u]) => [ref, snapToUtxo(u)]));
  const pin = (v) => v && ({ rewardAddress: v.rewardAddress,
    referenceScripts: [...outputs].filter(([, u]) => u.address === v.address && u.referenceScriptHash === v.hash).map(([ref]) => ref) });
  const deployment = { policy: anchors.policy, address: anchors.address, networkId: 0,
    ...(anchors.outputVerifier ? { outputVerifier: pin(anchors.outputVerifier) } : {}),
    ...(anchors.mapVerifier ? { mapVerifier: pin(anchors.mapVerifier) } : {}) };
  const { problems } = await verifyPrepared({
    description: anchors.description, txHex: result.prepared.cbor, deployment,
    wallet: { addresses: [signer.address], keyHashes: [signer.keyHash], utxos: new Map(walletUtxos.map((u) => [`${u.tx_hash}#${u.output_index}`, snapToUtxo(u)])) },
    call: { ...request, key: request.object, meta, cosignerKeys: request.cosigners ?? [] },
    // No slotConfig: offline time is the ledger's slot (advance moves it), not the wall clock; the session's now is the
    // chain's tip, which the page trusts as it trusts the chain (#169).
    chain: { tip: result.now, params, utxo: async (ref) => outputs.get(ref) ?? null },
    limits: { maxFee: BigInt(maxFee), maxValiditySlots: result.ttlSlots, maxStartSlots: 60, maxBackdateSlots: 0 },
  });
  return { problems, report: host.report };
}
