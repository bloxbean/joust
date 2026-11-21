// The output verifier's claims as the page reads them (ADR-0007 D5, ADR-0013 D2). A contract's send is delivered by an
// output that a claim in the output verifier's OutputClaims redeemer names for the paying input (`by`). On chain the send
// accepts any output at its address that covers its value, so for a send to the wallet the claim may name the wallet's
// change. An output a claim names at a wallet address is therefore the script's release to the wallet, never the
// wallet's change: the verifiers count it as spoken for (claimedSends).

import { bytesToHex, hexToBytes } from './hex.js';
import { blake2b } from '@noble/hashes/blake2.js';

/** The claimant of a @Withdraw handler's sends (ADR-0010 I37): TxOutRef(blake2b_256(policy), -1), as "txId#-1". */
export const rewardRefOf = (policy) => `${bytesToHex(blake2b(hexToBytes(policy), { dkLen: 32 }))}#-1`;

const isInt = (d) => d && typeof d.int === 'bigint';
const isConstr = (d, n, arity) => d && d.constr === n && Array.isArray(d.fields) && d.fields.length === arity;

/**
 * The claims of {@code verifier}'s OutputClaims redeemer in {@code tx} ([{ output, by: "txId#index" }]), found by the
 * Rewarding pointer of its zero withdrawal; [] when the transaction has none. Each claim must name a distinct output of
 * the transaction, in strictly increasing order (as the verifier checks on chain), for an input in {@code owners} (the
 * contract inputs the body spends whose sends the claims deliver). Anything else is a problem, and no claims are returned.
 */
export function outputClaims(tx, verifier, owners, problems) {
  if (!verifier?.rewardAddress) return [];
  const index = tx.withdrawals.findIndex((w) => w.address === verifier.rewardAddress);
  if (index < 0) return [];
  const rds = tx.redeemers.filter((r) => r.tag === 'reward' && r.index === index);
  if (rds.length !== 1) {
    problems.push('the output verifier\'s withdrawal has no redeemer');
    return [];
  }
  const d = rds[0].data;
  if (!isConstr(d, 0, 2) || !Array.isArray(d.fields[0].list) || !Array.isArray(d.fields[1].list)) {
    problems.push('the output verifier\'s redeemer is not an OutputClaims');
    return [];
  }
  const claims = [];
  let previous = -1n;
  for (const cl of d.fields[0].list) {
    const by = cl?.fields?.[1];
    if (!isConstr(cl, 0, 2) || !isInt(cl.fields[0]) || !isConstr(by, 0, 2) || !(by.fields[0].bytes instanceof Uint8Array)
      || by.fields[0].bytes.length !== 32 || !isInt(by.fields[1])) {
      problems.push('the output verifier\'s redeemer has a malformed claim');
      return [];
    }
    const output = cl.fields[0].int;
    if (output <= previous || output >= BigInt(tx.outputs.length)) {
      problems.push(`the output verifier's claims do not name distinct outputs in order (output ${output})`);
      return [];
    }
    previous = output;
    const ref = `${bytesToHex(by.fields[0].bytes)}#${by.fields[1].int}`;
    if (!owners.includes(ref)) {
      problems.push(`a send claimed for ${ref}, not a contract input the transaction spends`);
      return [];
    }
    claims.push({ output: Number(output), by: ref });
  }
  return claims;
}

/**
 * What the claims of {@code verifiers} ([{ verifier, owners }]) deliver to the wallet: the outputs they name at a wallet
 * address ({@code indexes}), their summed value ({@code value}: the script's sends to the wallet, so neither the wallet's
 * change nor value others may be paid from), and {@code topUp}: the lovelace the wallet may have added to them, at most
 * each output's ledger minimum ((160 + size) x coinsPerUTxOByte; a send of tokens needs its minimum ADA from the payer).
 */
export function claimedSends(tx, verifiers, isWallet, params, problems) {
  const indexes = new Set();
  const value = new Map();
  let topUp = 0n;
  const seen = new Set();
  const perByte = coinsPerUtxoByte(params);
  for (const { verifier, owners } of verifiers) {
    if (!verifier?.rewardAddress || seen.has(verifier.rewardAddress)) continue;
    seen.add(verifier.rewardAddress);
    for (const { output } of outputClaims(tx, verifier, owners, problems)) {
      const out = tx.outputs[output];
      if (!isWallet(out.address) || indexes.has(output)) continue;
      indexes.add(output);
      for (const [unit, q] of out.value) value.set(unit, (value.get(unit) ?? 0n) + q);
      const lovelace = out.value.get('lovelace') ?? 0n;
      const min = perByte === null || out.size === undefined ? 0n : (160n + BigInt(out.size)) * perByte;
      topUp += lovelace < min ? lovelace : min;
    }
  }
  return { indexes, value, topUp };
}

function coinsPerUtxoByte(pp) {
  const v = pp?.coins_per_utxo_size ?? pp?.coins_per_utxo_byte ?? pp?.coinsPerUtxoByte;
  return v === undefined || v === null ? null : BigInt(v);
}
