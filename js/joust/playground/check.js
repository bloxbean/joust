// The page's pre-sign check (ADR-0024 D7 step 3, I2), as plain functions so Node tests run exactly what the page runs:
// the anchors a contract is checked against (derived from the build the session pinned, never from the host), and
// protocol 1's D13 check of a `prepare` or `publish` result (verifyHostResult, the conformance kit's reference
// checker) over the request the page sent, the trusted chain's outputs and parameters, and the host's result.

import { verifyHostResult, plutusScriptHash, enterpriseAddress, baseAddress, paymentKeyHash } from '@julclang/state-client';

/**
 * A CIP-30 wallet's body spends from the connected change address only (final pass): the first input (by ref) that is
 * at another address of the wallet's payment key, or, in a publication (all of whose inputs are the wallet's), at any
 * other address; null when there is none. {@code addressOf}: ref -> the address the chain shows it at.
 */
export function foreignPayerInput(refs, addressOf, { payer, keyHash, publication = false }) {
  for (const ref of refs) {
    const at = addressOf(ref);
    let key = null;
    try {
      key = paymentKeyHash(at);
    } catch {
      // a script address
    }
    if (at !== payer && (key === keyHash || publication)) return { ref, at };
  }
  return null;
}

/** contracts.json's script is the CBOR byte string of the program's CBOR (double-wrapped): the hash is over the inner. */
export function unwrapScript(hex) {
  const head = parseInt(hex.slice(0, 2), 16);
  const skip = head === 0x59 ? 6 : head === 0x58 ? 4 : head === 0x5a ? 10 : head >= 0x40 && head < 0x58 ? 2 : 0;
  return hex.slice(skip);
}

/** The anchors of a pinned build entry ({ description, script }): its policy and address (network 0). */
export function anchorsOf({ description, script }) {
  const hash = plutusScriptHash(unwrapScript(script), 3);
  const x = description['x-joust'] ?? {};
  // A stakeSelf contract's address is a base address whose stake credential is its own script (ADR-0010).
  const cred = { type: 'script', hash };
  const address = x.stakeSelf === true ? baseAddress(cred, cred, 0) : enterpriseAddress(cred, 0);
  return { description, policy: hash, address, language: 'PlutusScriptV3' };
}

/**
 * A prepared call's check. {@code result}: the session's prepare answer ({ prepared, now, ttlSlots }); {@code request}:
 * the call exactly as the page sent it; {@code snapshots}: the chain's outputs for the body's inputs, reference inputs
 * and collateral (protocol snapshot encoding, by ref); {@code params}: the chain's protocol parameters.
 */
export function checkCall({ result, request, anchors, snapshots, params, maxFee }) {
  return verifyHostResult({ method: 'prepare', request: { now: result.now, ttlSlots: result.ttlSlots, call: request }, networkId: 0,
    maxFee: String(maxFee), anchors, resolved: snapshots, protocolParams: params, result: { prepared: result.prepared } });
}

/**
 * A publication's check. {@code node}: { scriptsAt: { holder address: [script hash] }, registrations: { hash: bool } },
 * the chain's answers for the prerequisite's holder and credential.
 */
export function checkPublication({ result, contract, prerequisite, address, anchors, snapshots, node, params, maxFee }) {
  return verifyHostResult({ method: 'publish', request: { now: result.now, ttlSlots: result.ttlSlots, contract, prerequisite, address },
    networkId: 0, maxFee: String(maxFee), anchors, resolved: snapshots, node, protocolParams: params, result: { prepared: result.prepared } });
}
