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

/**
 * {@code verifier}'s registry in {@code tx}: { claims: [{ output, by }], deposits: [{ output, by }] } (refs as
 * "txId#index"), found by the Rewarding pointer of its zero withdrawal; null when the transaction has none. Each claim
 * and deposit names an output of the transaction; a redeemer of another shape is a problem, and null is returned. The
 * output verifier checks the rest on chain (order, distinct outputs, deposit values). The redeemer is
 * OutputClaims(claims, deposits),
 * the only format any JOUST release's output verifier reads: its one-field predecessor (claims only) lived for one day
 * of development and was replaced, with a new verifier hash, before the first release, so no page meets it and it is
 * refused as malformed.
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
  for (const cl of d.fields[0].list) {
    const by = isConstr(cl, 0, 2) && isInt(cl.fields[0]) ? refOf(cl.fields[1]) : null;
    if (!by) {
      problems.push('the output verifier\'s redeemer has a malformed claim');
      return null;
    }
    const output = cl.fields[0].int;
    if (output < 0n || output >= outputs) {
      problems.push(`the output verifier's claims name no output of the transaction (output ${output})`);
      return null;
    }
    claims.push({ output: Number(output), by });
  }
  const deposits = [];
  for (const dc of d.fields[1].list) {
    const by = isConstr(dc, 0, 4) && isInt(dc.fields[0]) ? refOf(dc.fields[1]) : null;
    if (!by) {
      problems.push('the output verifier\'s redeemer has a malformed deposit');
      return null;
    }
    const output = dc.fields[0].int;
    if (output < 0n || output >= outputs) {
      problems.push(`the output verifier's deposits name no output of the transaction (output ${output})`);
      return null;
    }
    deposits.push({ output: Number(output), by });
  }
  return { claims, deposits };
}

/**
 * The outputs at a wallet address that the claims of {@code registries} ([{ registry, owners }]) name ({@code
 * indexes}) and their summed value ({@code value}): the scripts' sends to the wallet, so not value others may be paid
 * from. Each claim's `by` must be one of its registry's {@code owners} (the contract inputs the body spends whose sends
 * the claims deliver); a claim for anything else is a problem. An output may be claimed in one registry only: each
 * output verifier checks distinct outputs within its own redeemer and each contract reads only its own verifier's, so
 * one output claimed in two registries (calls pinning different verifiers) would deliver two sends, at any address.
 * The rest (I29, I30) the validators check on chain.
 */
export function claimedSends(tx, registries, isWallet, problems) {
  const indexes = new Set();
  const value = new Map();
  const claimedIn = new Map();     // every claimed output (at any address) -> the registry naming it
  registries.forEach(({ registry, owners }, r) => {
    for (const { output, by } of registry?.claims ?? []) {
      if (!owners.includes(by)) {
        problems.push(`a send claimed for ${by}, not a contract input the transaction spends`);
        continue;
      }
      if (claimedIn.has(output) && claimedIn.get(output) !== r) {
        problems.push(`output ${output} is claimed in two output verifiers' registries`);
        continue;
      }
      claimedIn.set(output, r);
      const out = tx.outputs[output];
      if (!isWallet(out.address) || indexes.has(output)) continue;
      indexes.add(output);
      for (const [unit, q] of out.value) value.set(unit, (value.get(unit) ?? 0n) + q);
    }
  });
  return { indexes, value };
}
