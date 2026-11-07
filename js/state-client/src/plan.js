// The portable plan of a transaction (ADR-0013 D4), as the runtime's TxPlan writes it: what the transaction spends,
// reads, creates, mints, runs and requires, in plain values. The page derives it from the CBOR itself and compares it
// with the service's.

import { bytesToHex } from './hex.js';
import { refOf } from './verify.js';

function units(entries) {
  const sums = new Map();
  for (const [unit, q] of entries) sums.set(unit, (sums.get(unit) ?? 0n) + q);
  const out = {};
  for (const k of [...sums.keys()].sort()) out[k] = sums.get(k).toString();
  return out;
}

function valueUnits(v) {
  const entries = [['lovelace', BigInt(v.coin().to_str())]];
  const ma = v.multiasset();
  if (ma) {
    const ps = ma.keys();
    for (let i = 0; i < ps.len(); i++) {
      const assets = ma.get(ps.get(i));
      const names = assets.keys();
      for (let j = 0; j < names.len(); j++) entries.push([ps.get(i).to_hex() + bytesToHex(names.get(j).name()), BigInt(assets.get(names.get(j)).to_str())]);
    }
  }
  return units(entries);
}

function output(o) {
  return {
    address: o.address().to_bech32(),
    value: valueUnits(o.amount()),
    datum: o.has_plutus_data() ? o.plutus_data().to_hex() : null,
    datumHash: o.has_data_hash() ? o.data_hash().to_hex() : null,
    hasScript: o.has_script_ref(),
  };
}

const list = (xs, f = (x) => x) => {
  const out = [];
  for (let i = 0; xs && i < xs.len(); i++) out.push(f(xs.get(i)));
  return out;
};

const TAGS = ['spend', 'mint', 'cert', 'reward', 'voting', 'proposing'];

/** The plan of a transaction (hex CBOR). */
export function planOf(CSL, txHex) {
  const tx = CSL.Transaction.from_hex(txHex);
  const b = tx.body();
  const mint = [];
  if (b.mint()) {
    const ps = b.mint().keys();
    for (let i = 0; i < ps.len(); i++) {
      const all = b.mint().get(ps.get(i));
      for (let k = 0; k < all.len(); k++) {
        const assets = all.get(k);
        const names = assets.keys();
        for (let j = 0; j < names.len(); j++) mint.push([ps.get(i).to_hex() + bytesToHex(names.get(j).name()), BigInt(assets.get(names.get(j)).to_str())]);
      }
    }
  }
  return {
    inputs: list(b.inputs(), refOf),
    referenceInputs: list(b.reference_inputs(), refOf),
    collateral: list(b.collateral(), refOf),
    outputs: list(b.outputs(), output),
    collateralReturn: b.collateral_return() ? output(b.collateral_return()) : null,
    totalCollateral: b.total_collateral() ? b.total_collateral().to_str() : null,
    mint: units(mint),
    // The ledger's order (tag, then index), whatever order the witness set lists them in.
    redeemers: list(tx.witness_set().redeemers(), (r) => ({
      tag: TAGS[r.tag().kind()],
      index: Number(r.index().to_str()),
      data: r.data().to_hex(),
      mem: r.ex_units().mem().to_str(),
      steps: r.ex_units().steps().to_str(),
    })).sort((x, y) => TAGS.indexOf(x.tag) - TAGS.indexOf(y.tag) || x.index - y.index),
    requiredSigners: list(b.required_signers(), (k) => k.to_hex()),
    // 0 and absent read alike (cardano-client-lib cannot tell them apart).
    validFrom: b.validity_start_interval_bignum() && b.validity_start_interval_bignum().to_str() !== '0' ? Number(b.validity_start_interval_bignum().to_str()) : null,
    validTo: b.ttl_bignum() && b.ttl_bignum().to_str() !== '0' ? Number(b.ttl_bignum().to_str()) : null,
    fee: b.fee().to_str(),
    // Everything else, so that equal plans mean equal transactions (r5).
    networkId: b.network_id() ? (b.network_id().kind() === CSL.NetworkIdKind.Mainnet ? 1 : 0) : null,
    scriptDataHash: b.script_data_hash() ? b.script_data_hash().to_hex() : null,
    auxiliaryDataHash: b.auxiliary_data_hash() ? b.auxiliary_data_hash().to_hex() : null,
    certificates: b.certs() ? b.certs().len() : 0,
    withdrawals: b.withdrawals() ? b.withdrawals().len() : 0,
    votes: !!(b.voting_procedures() && b.voting_procedures().get_voters().len() > 0),
    proposals: b.voting_proposals() ? b.voting_proposals().len() : 0,
    donation: b.donation() ? b.donation().to_str() : null,
    currentTreasuryValue: b.current_treasury_value() ? b.current_treasury_value().to_str() : null,
    auxiliaryData: !!tx.auxiliary_data(),
    isValid: tx.is_valid(),
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
