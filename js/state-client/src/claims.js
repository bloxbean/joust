// The output verifier's registry as the page reads it (ADR-0007 D5, ADR-0011 D3, ADR-0013 D2): the OutputClaims
// redeemer of its zero withdrawal. A contract's send is delivered by an output that a claim names for the paying input
// (`by`); on chain the send accepts any output at its address that covers its value, so for a send to the wallet the
// claim may name the wallet's change. An output a claim names at a wallet address is therefore the script's send to the
// wallet, never the wallet's change: the verifiers subtract it, as tagged payouts are, from what others may receive
// (claimedSends). A deposit claim adds value to a receiving call's continuation; the receiver credits every claim on its
// output whatever its `by`, so the verifiers accept a deposit only from a composed call that declares deposits (its
// validator then checks its claims exactly) into a composed call that receives them (compose.js).

import { bytesToHex, hexToBytes } from './hex.js';
import { dataEqual } from './pdata.js';
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

const MAX_QUANTITY = (1n << 127n) - 1n;

/**
 * A deposit's Value as Map unit -> bigint (policy hex + asset name hex; '' + '' is lovelace), or null when {@code d} is
 * not one the output verifier accepts (OutputVerifier.positive over ValueAdapter.fromWire's rules, on the wire's own
 * entries): a map of policies in strictly increasing order, each a non-empty map of distinct names (32 bytes at most;
 * under the empty policy only the empty name), every quantity positive and within 128 bits, the value not empty, and
 * no reserved-kind name (32 bytes, or 29 bytes starting 0x4F or 0x49) under a token policy. Nothing is merged: a
 * redeemer the verifier would refuse is refused before the wallet signs it.
 */
function valueOf(d) {
  if (!Array.isArray(d?.map) || d.map.length === 0) return null;
  const m = new Map();
  let previous = null;
  for (const [p, assets] of d.map) {
    if (!isBytes(p) || p.bytes.length > 32 || !Array.isArray(assets?.map) || assets.map.length === 0) return null;
    const policy = bytesToHex(p.bytes);
    if (previous !== null && !(previous < policy)) return null;
    previous = policy;
    const names = new Set();
    for (const [n, q] of assets.map) {
      if (!isBytes(n) || n.bytes.length > 32 || !isInt(q) || q.int <= 0n || q.int > MAX_QUANTITY) return null;
      const name = bytesToHex(n.bytes);
      if (names.has(name)) return null;
      names.add(name);
      const ada = p.bytes.length === 0;
      if (ada && n.bytes.length !== 0) return null;
      const len = n.bytes.length;
      if (!ada && (len === 32 || (len === 29 && (n.bytes[0] === 0x4f || n.bytes[0] === 0x49)))) return null;
      m.set(ada ? 'lovelace' : policy + name, q.int);
    }
  }
  return m;
}

/**
 * {@code verifier}'s registry in {@code tx}: { claims: [{ output, by }], deposits: [{ output, by, value }] } (refs as
 * "txId#index"), found by the Rewarding pointer of its zero withdrawal; null when the transaction has none. Claims must
 * name outputs of the transaction in strictly increasing order and deposits in non-decreasing order (the verifier's own
 * rules on chain). Anything else is a problem, and null is returned. The redeemer is OutputClaims(claims, deposits),
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
 * the claims deliver); a claim for anything else is a problem. An output may be claimed in one registry only: each
 * output verifier checks distinct outputs within its own redeemer and each contract reads only its own verifier's, so
 * one output claimed in two registries (calls pinning different verifiers) would deliver two sends, at any address.
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
      if (!isWallet(out.address)) continue;
      indexes.add(output);
      for (const [unit, q] of out.value) value.set(unit, (value.get(unit) ?? 0n) + q);
    }
  });
  return { indexes, value };
}

/**
 * What the calls keep or forward of their payments (ADR-0015 D7, ADR-0013 §9.2): the credit, per unit, that others may
 * receive beyond what the scripts release. A call's payment ({@code paid}) goes into its continuation or out through
 * the outputs bound to it, as its validator checks: its continuation exact, its payouts paid by outputs to the payee's
 * key carrying its tag (I7), its sends by outputs its claims name (I29). So a call keeps its intake ({@code own}: what
 * its continuation gains, its target's value bound), up to its payment, and forwards the rest, but
 * only up to what the outputs bound to it hold: an output paying a key with its tag (a payout), or one a claim names
 * for its target (a send), to anyone, the wallet included (a refund to the caller is part of the payment the call
 * forwards; the verifiers still subtract every tagged or claimed wallet output from what others may receive). Per call
 * and unit, kept and forwarded together are at most its payment. An output is bound to one call at most, and one that
 * carries a tag and is claimed too is refused by the verifiers (on chain I30 refuses it), so each obligation has its
 * own output and an extra output can be bound only beyond what the obligations need.
 *
 * @param calls   [{ targetRef, tag (Data, the call's payout tag), paid: Map unit -> bigint, own: Map unit -> bigint }]
 * @param outputs [{ index, value: Map, datum, paysKey }] the outputs that are no call's continuation
 * @param claims  [{ output, by }] the claims of every registry
 * @returns Map unit -> bigint
 */
export function paymentCredit(calls, outputs, claims) {
  const bound = new Map(calls.map((k) => [k, new Map()]));
  for (const { index, value, datum, paysKey } of outputs) {
    const k = calls.find((x) => x.targetRef && ((paysKey && datum && x.tag && dataEqual(datum, x.tag))
      || claims.some((cl) => cl.output === index && cl.by === x.targetRef)));
    if (k) for (const [unit, q] of value) bound.get(k).set(unit, (bound.get(k).get(unit) ?? 0n) + q);
  }
  const credit = new Map();
  for (const k of calls) {
    for (const [unit, cap] of k.paid) {
      if (cap <= 0n) continue;
      const q = k.own.get(unit) ?? 0n;
      const keeps = q <= 0n ? 0n : q < cap ? q : cap;
      const sent = bound.get(k).get(unit) ?? 0n;
      const forwards = sent < cap - keeps ? sent : cap - keeps;
      credit.set(unit, (credit.get(unit) ?? 0n) + keeps + forwards);
    }
  }
  return credit;
}
