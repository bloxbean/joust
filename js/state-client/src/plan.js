// The portable plan of a transaction (ADR-0013 D4), as the runtime's TxPlan writes it: what the transaction spends,
// reads, creates, mints, runs and requires, in plain values. The page derives it from the CBOR itself and compares it
// with the service's.

import { parseTx } from './ledger.js';
import { dataHex } from './pdata.js';

function units(entries) {
  const sums = new Map();
  for (const [unit, q] of entries) sums.set(unit, (sums.get(unit) ?? 0n) + q);
  const out = {};
  for (const k of [...sums.keys()].sort()) out[k] = sums.get(k).toString();
  return out;
}

function output(o) {
  return {
    address: o.address,
    value: units([...o.value]),
    datum: o.datum ? dataHex(o.datum) : null,
    datumHash: o.datumHash,
    hasScript: o.hasScriptRef,
  };
}

const TAGS = ['spend', 'mint', 'cert', 'reward', 'voting', 'proposing'];
const TAG_NAME = { spend: 'spend', mint: 'mint', cert: 'cert', reward: 'reward', vote: 'voting', propose: 'proposing' };

/** The plan of a transaction (hex CBOR). ({@code planOf(CSL, hex)} still accepted.) */
export function planOf(...args) {
  const t = parseTx(args.at(-1));
  return {
    inputs: t.inputs,
    referenceInputs: t.referenceInputs,
    collateral: t.collateral,
    outputs: t.outputs.map(output),
    collateralReturn: t.collateralReturn ? output(t.collateralReturn) : null,
    totalCollateral: t.totalCollateral !== null ? t.totalCollateral.toString() : null,
    mint: units([...t.mint]),
    // The ledger's order (tag, then index), whatever order the witness set lists them in.
    redeemers: t.redeemers.map((r) => ({
      tag: TAG_NAME[r.tag], index: r.index, data: dataHex(r.data), mem: r.mem.toString(), steps: r.steps.toString(),
    })).sort((x, y) => TAGS.indexOf(x.tag) - TAGS.indexOf(y.tag) || x.index - y.index),
    requiredSigners: t.requiredSigners,
    // 0 and absent read alike (cardano-client-lib cannot tell them apart).
    validFrom: t.validFrom ? Number(t.validFrom) : null,
    validTo: t.ttl ? Number(t.ttl) : null,
    fee: t.fee.toString(),
    // Everything else, so that equal plans mean equal transactions (r5).
    networkId: t.networkId,
    scriptDataHash: t.scriptDataHash,
    auxiliaryDataHash: t.auxiliaryDataHash,
    certificates: t.certificates.length,
    withdrawals: t.withdrawals.length,
    votes: t.hasVotes,
    proposals: t.proposals,
    donation: t.donation !== null ? t.donation.toString() : null,
    currentTreasuryValue: t.treasuryValue !== null ? t.treasuryValue.toString() : null,
    auxiliaryData: t.hasAuxiliaryData,
    isValid: t.isValid,
  };
}

/** The first difference between two plans ("" when equal). */
export function planDifference(a, b, path = 'plan') {
  if (a === b) return '';
  if (typeof a !== typeof b || a === null || b === null || typeof a !== 'object') return `${path}: ${JSON.stringify(a)} vs ${JSON.stringify(b)}`;
  if (Array.isArray(a) !== Array.isArray(b)) return `${path}: shapes differ`;
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  for (const k of keys) {
    const d = planDifference(a[k], b[k], `${path}.${k}`);
    if (d) return d;
  }
  return '';
}
