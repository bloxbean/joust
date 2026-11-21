// The output verifier's registry as the page reads it (ADR-0007 D5, ADR-0011 D3, ADR-0013 D2): the OutputClaims
// redeemer of its zero withdrawal. A contract's send is delivered by an output that a claim names for the paying input
// (`by`); on chain the send accepts any output at its address that covers its value, so for a send to the wallet the
// claim may name the wallet's change. An output a claim names at a wallet address is therefore the script's send to the
// wallet, never the wallet's change: the verifiers subtract it, as tagged payouts are, from what others may receive
// (claimedSends). A deposit claim adds value to a receiving call's continuation; the receiver credits every claim on its
// output whatever its `by`, so the verifiers accept a deposit only from a composed call that declares deposits (its
// validator then checks its claims exactly) into a composed call that receives them (compose.js).

import { bytesToHex, hexToBytes } from './hex.js';
import { blake2b } from '@noble/hashes/blake2.js';

/** The claimant of a @Withdraw handler's sends (ADR-0010 I37): TxOutRef(blake2b_256(policy), -1), as "txId#-1". */
export const rewardRefOf = (policy) => `${bytesToHex(blake2b(hexToBytes(policy), { dkLen: 32 }))}#-1`;

const isInt = (d) => d && typeof d.int === 'bigint';
const isConstr = (d, n, arity) => d && d.constr === n && Array.isArray(d.fields) && d.fields.length === arity;
const isBytes = (d) => d && d.bytes instanceof Uint8Array;

/** A TxOutRef as "txId#index", or null when {@code d} is not one. */
function refOf(d) {
  if (!isConstr(d, 0, 2) || !isBytes(d.fields[0]) || d.fields[0].bytes.length !== 32 || !isInt(d.fields[1])) return null;
  return `${bytesToHex(d.fields[0].bytes)}#${d.fields[1].int}`;
}

/** A Value as Map unit -> bigint (policy hex + asset name hex; '' + '' is lovelace), or null when {@code d} is not one. */
function valueOf(d) {
  if (!Array.isArray(d?.map)) return null;
  const m = new Map();
  for (const [p, assets] of d.map) {
    if (!isBytes(p) || !Array.isArray(assets?.map)) return null;
    for (const [n, q] of assets.map) {
      if (!isBytes(n) || !isInt(q)) return null;
      const unit = p.bytes.length === 0 && n.bytes.length === 0 ? 'lovelace' : bytesToHex(p.bytes) + bytesToHex(n.bytes);
      m.set(unit, (m.get(unit) ?? 0n) + q.int);
    }
  }
  return m;
}

/**
 * {@code verifier}'s registry in {@code tx}: { claims: [{ output, by }], deposits: [{ output, by, value }] } (refs as
 * "txId#index"), found by the Rewarding pointer of its zero withdrawal; null when the transaction has none. Claims must
 * name outputs of the transaction in strictly increasing order and deposits in non-decreasing order (the verifier's own
 * rules on chain). Anything else is a problem, and null is returned.
 */
export function outputRegistry(tx, verifier, problems) {
  if (!verifier?.rewardAddress) return null;
  const index = tx.withdrawals.findIndex((w) => w.address === verifier.rewardAddress);
  if (index < 0) return null;
  const rds = tx.redeemers.filter((r) => r.tag === 'reward' && r.index === index);
  if (rds.length !== 1) {
    problems.push(`the output verifier's withdrawal has ${rds.length} redeemers, not one`);
    return null;
  }
  const d = rds[0].data;
  if (!isConstr(d, 0, 2) || !Array.isArray(d.fields[0].list) || !Array.isArray(d.fields[1].list)) {
    problems.push('the output verifier\'s redeemer is not an OutputClaims');
    return null;
  }
  const outputs = BigInt(tx.outputs.length);
  const claims = [];
  let previous = -1n;
  for (const cl of d.fields[0].list) {
    const by = isConstr(cl, 0, 2) && isInt(cl.fields[0]) ? refOf(cl.fields[1]) : null;
    if (!by) {
      problems.push('the output verifier\'s redeemer has a malformed claim');
      return null;
    }
    const output = cl.fields[0].int;
    if (output <= previous || output >= outputs) {
      problems.push(`the output verifier's claims do not name distinct outputs in order (output ${output})`);
      return null;
    }
    previous = output;
    claims.push({ output: Number(output), by });
  }
  const deposits = [];
  let last = 0n;
  for (const dc of d.fields[1].list) {
    const by = isConstr(dc, 0, 4) && isInt(dc.fields[0]) ? refOf(dc.fields[1]) : null;
    const value = by ? valueOf(dc.fields[2]) : null;
    if (!value) {
      problems.push('the output verifier\'s redeemer has a malformed deposit');
      return null;
    }
    const output = dc.fields[0].int;
    if (output < last || output >= outputs) {
      problems.push(`the output verifier's deposits do not name outputs in order (output ${output})`);
      return null;
    }
    last = output;
    deposits.push({ output: Number(output), by, value });
  }
  return { claims, deposits };
}

/**
 * The outputs at a wallet address that the claims of {@code registries} ([{ registry, owners }]) name ({@code
 * indexes}) and their summed value ({@code value}): the scripts' sends to the wallet, so not value others may be paid
 * from. Each claim's `by` must be one of its registry's {@code owners} (the contract inputs the body spends whose sends
 * the claims deliver); a claim for anything else is a problem.
 */
export function claimedSends(tx, registries, isWallet, problems) {
  const indexes = new Set();
  const value = new Map();
  for (const { registry, owners } of registries) {
    for (const { output, by } of registry?.claims ?? []) {
      if (!owners.includes(by)) {
        problems.push(`a send claimed for ${by}, not a contract input the transaction spends`);
        continue;
      }
      const out = tx.outputs[output];
      if (!isWallet(out.address) || indexes.has(output)) continue;
      indexes.add(output);
      for (const [unit, q] of out.value) value.set(unit, (value.get(unit) ?? 0n) + q);
    }
  }
  return { indexes, value };
}
