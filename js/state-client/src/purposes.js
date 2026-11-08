// Other script purposes (ADR-0010) from a page (ADR-0015 M3): a contract's own stake credential. Its funds sit at
// base addresses staked to its script; its @Certify handler decides delegations, its @Withdraw handler where the
// rewards go. Each prepared transaction is verified before the wallet signs: nothing beyond the operation, the
// wallet's funds bounded (it pays the fee only), the redeemers the page's own.

import { DEFAULT_LIMITS, refOf, valueMap, add, paymentKeyHashOf } from './verify.js';
import { dataEqual, codec as makeCodec } from './data.js';
import { rewardAddressOf } from './governors.js';

/** The contract's purpose handler of {@code purpose} (withdraw, certify, vote, propose), or null. */
export const purposeOf = (description, purpose) => (description['x-joust'].purposes ?? []).find((p) => p.purpose === purpose) ?? null;

/** Rewards(74, amount, args...): a @Withdraw redeemer, as the page encodes it. */
export function rewardsData(CSL, description, meta, amount, args = {}) {
  const c = makeCodec(CSL, description);
  const fields = CSL.PlutusList.new();
  fields.add(CSL.PlutusData.new_integer(CSL.BigInt.from_str(String(meta.redeemer.marker ?? 74))));
  fields.add(CSL.PlutusData.new_integer(CSL.BigInt.from_str(String(amount))));
  for (const a of meta.args) fields.add(c.encode(a.schema, args[a.name], a.name));
  return CSL.PlutusData.new_constr_plutus_data(CSL.ConstrPlutusData.new(CSL.BigNum.zero(), fields));
}

/**
 * Everything wrong with {@code txHex} as {@code op} on the contract's stake credential: {@code delegate} (one stake
 * delegation of the script credential to {@code pool}, with the @Certify redeemer) or {@code collect} (the whole
 * reward balance {@code amount} withdrawn with the page's Rewards redeemer, sent as the handler decides: to others at
 * most what was withdrawn). Signers: the wallet's keys and the co-signers the page named.
 *
 * @param o.op           'delegate' | 'collect'
 * @param o.deployment   { policy, networkId, referenceScripts, outputVerifier? }
 * @param o.pool         the pool key hash (hex), for a delegation
 * @param o.amount       the reward balance (bigint), for a collection
 * @param o.args         the @Withdraw handler's arguments
 * @param o.cosignerKeys the further keys the page named (@Quorum)
 * @param o.chain        { tip, params, utxo }
 */
export async function verifyPurpose(o) {
  const { CSL, description, deployment, txHex, wallet, chain, op } = o;
  const limits = { ...DEFAULT_LIMITS, ...(o.limits ?? {}) };
  const problems = [];
  let tx;
  try {
    tx = CSL.Transaction.from_hex(txHex);
  } catch (e) {
    return { problems: ['not a transaction'], report: {} };
  }
  const body = tx.body();
  const ws = tx.witness_set();
  const report = { fee: BigInt(body.fee().to_str()) };
  const policy = deployment.policy;
  const own = rewardAddressOf(CSL, policy, deployment.networkId);
  const meta = purposeOf(description, op === 'delegate' ? 'certify' : 'withdraw');
  if (!meta) return { problems: [`${description['x-joust'].contract} has no ${op === 'delegate' ? '@Certify' : '@Withdraw'} handler`], report };
  const isWallet = (addr) => wallet.addresses.includes(addr.to_bech32())
    || (CSL.EnterpriseAddress.from_address(addr) && wallet.keyHashes.includes(paymentKeyHashOf(CSL, addr)));

  if (!tx.is_valid()) problems.push('is_valid is false (the collateral would be taken)');
  if (body.voting_procedures() || body.voting_proposals()) problems.push('votes or proposals');
  if (body.donation() || body.current_treasury_value()) problems.push('a treasury donation');
  if (body.mint()) problems.push('a mint');
  if (tx.auxiliary_data()) problems.push('auxiliary data');
  if (body.network_id() && body.network_id().kind() !== (deployment.networkId === 1 ? CSL.NetworkIdKind.Mainnet : CSL.NetworkIdKind.Testnet)) {
    problems.push('another network');
  }

  // Certificates: a delegation's one, of the script's own credential to the page's pool; none for a collection.
  const certs = body.certs();
  if (op === 'delegate') {
    if (!certs || certs.len() !== 1) problems.push(`${certs ? certs.len() : 0} certificates, expected one delegation`);
    else {
      const cert = certs.get(0);
      const d = cert.kind() === CSL.CertificateKind.StakeDelegation ? cert.as_stake_delegation() : null;
      if (!d) problems.push('a certificate that is not a stake delegation');
      else {
        const cred = d.stake_credential();
        if (cred.kind() !== CSL.CredKind.Script || cred.to_scripthash().to_hex() !== policy) problems.push('a delegation of another credential');
        if (d.pool_keyhash().to_hex() !== o.pool) problems.push('a delegation to another pool than the page asked');
      }
    }
  } else if (certs && certs.len() > 0) problems.push('certificates');

  // Withdrawals: a collection's whole balance from the script's own credential, and the output verifier's zero.
  const wd = body.withdrawals();
  const order = [];
  let withdrawn = 0n;
  if (wd && wd.len() > 0) {
    const keys = wd.keys();
    for (let i = 0; i < keys.len(); i++) {
      const a = keys.get(i).to_address();
      const q = BigInt(wd.get(keys.get(i)).to_str());
      order.push({ bech32: a.to_bech32(), bytes: a.to_hex() });
      if (op === 'collect' && a.to_bech32() === own) {
        withdrawn += q;
        if (q !== BigInt(o.amount)) problems.push(`a withdrawal of ${q}, not the ${o.amount} the page asked`);
      } else if (op === 'collect' && deployment.outputVerifier && a.to_bech32() === deployment.outputVerifier.rewardAddress && q === 0n) {
        // the output verifier: checks the sends' claims on chain
      } else problems.push('a withdrawal the page did not ask for');
    }
  }
  order.sort((x, y) => (x.bytes < y.bytes ? -1 : x.bytes > y.bytes ? 1 : 0));
  if (op === 'collect' && !order.some((r) => r.bech32 === own)) problems.push('no withdrawal from the contract\'s stake credential');

  // Redeemers: the handler's, on its certificate or its withdrawal; the output verifier's.
  const rds = ws.redeemers();
  let mine = 0;
  for (let i = 0; rds && i < rds.len(); i++) {
    const r = rds.get(i);
    const kind = r.tag().kind();
    const index = Number(r.index().to_str());
    if (op === 'delegate' && kind === CSL.RedeemerTagKind.Cert && index === 0) {
      mine++;
      const unit = CSL.PlutusData.new_constr_plutus_data(CSL.ConstrPlutusData.new(CSL.BigNum.zero(), CSL.PlutusList.new()));
      if (!dataEqual(r.data(), unit)) problems.push('the certificate redeemer is not the handler\'s');
    } else if (op === 'collect' && kind === CSL.RedeemerTagKind.Reward && order[index]?.bech32 === own) {
      mine++;
      let want;
      try {
        want = rewardsData(CSL, description, meta, o.amount, o.args ?? {});
      } catch (e) {
        problems.push(e.message);
        continue;
      }
      if (!dataEqual(r.data(), want)) problems.push('the Rewards redeemer is not the page\'s');
    } else if (op === 'collect' && kind === CSL.RedeemerTagKind.Reward && deployment.outputVerifier
      && order[index]?.bech32 === deployment.outputVerifier.rewardAddress) {
      // the output verifier's claims: checked on chain
    } else problems.push('a redeemer the operation does not need');
  }
  if (mine !== 1) problems.push(`${mine} redeemers of the handler, expected 1`);

  // Inputs: the wallet's only. Reference inputs: the pinned reference scripts (the contract's, the output verifier's).
  const consumed = new Map();
  const inputs = body.inputs();
  for (let i = 0; i < inputs.len(); i++) {
    const r = refOf(inputs.get(i));
    if (!wallet.utxos.has(r)) problems.push(`an input that is not the wallet's: ${r}`);
    else add(consumed, wallet.utxos.get(r).value);
  }
  const pinned = new Set([...(deployment.referenceScripts ?? []), ...(deployment.outputVerifier?.referenceScripts ?? [])]);
  const refIns = body.reference_inputs();
  for (let i = 0; refIns && i < refIns.len(); i++) if (!pinned.has(refOf(refIns.get(i)))) problems.push(`a reference input the page does not know: ${refOf(refIns.get(i))}`);

  // Outputs: the wallet's (no datum or script); others only from the rewards withdrawn.
  const produced = new Map();
  const back = new Map();
  let others = 0n;
  const outs = body.outputs();
  for (let i = 0; i < outs.len(); i++) {
    const out = outs.get(i);
    const value = valueMap(out.amount());
    add(produced, value);
    if (out.has_script_ref() || out.has_data_hash()) problems.push('an output with a script or datum hash');
    if (isWallet(out.address())) {
      if (out.has_plutus_data()) problems.push('a wallet output with a datum');
      add(back, value);
    } else {
      for (const [unit, q] of value) if (unit !== 'lovelace' && q > 0n) problems.push(`others receive ${unit}`);
      others += value.get('lovelace') ?? 0n;
    }
  }
  if (others > withdrawn) problems.push(`others receive ${others} lovelace, more than the ${withdrawn} of rewards withdrawn`);
  const fee = report.fee;
  add(produced, new Map([['lovelace', fee]]));
  const supplied = new Map(consumed);
  add(supplied, new Map([['lovelace', withdrawn]]));
  for (const unit of new Set([...supplied.keys(), ...produced.keys()])) {
    if ((supplied.get(unit) ?? 0n) !== (produced.get(unit) ?? 0n)) problems.push(`the transaction does not balance in ${unit}`);
  }
  const net = new Map(consumed);
  add(net, back, -1n);
  for (const [unit, q] of net) {
    if (unit === 'lovelace' && q > fee) problems.push(`the wallet loses ${q} lovelace, more than the fee`);
    if (unit !== 'lovelace' && q > 0n) problems.push(`the wallet loses ${q} of ${unit}`);
  }
  if (fee > limits.maxFee) problems.push(`a fee of ${fee}, above the page's limit of ${limits.maxFee}`);
  report.walletNet = net.get('lovelace') ?? 0n;
  report.withdrawn = withdrawn;

  // Collateral: the wallet's, returned to it, bounded.
  const coll = body.collateral();
  const ret = body.collateral_return();
  if (!coll || coll.len() === 0 || !ret || !body.total_collateral()) problems.push('collateral without a return and a total');
  else {
    let inColl = 0n;
    for (let i = 0; i < coll.len(); i++) {
      const r = refOf(coll.get(i));
      if (!wallet.utxos.has(r)) problems.push('collateral that is not the wallet\'s');
      else inColl += wallet.utxos.get(r).value.get('lovelace') ?? 0n;
    }
    if (!isWallet(ret.address())) problems.push('the collateral return goes elsewhere');
    if (BigInt(body.total_collateral().to_str()) > limits.maxCollateral || inColl - BigInt(ret.amount().coin().to_str()) > limits.maxCollateral) {
      problems.push(`more than ${limits.maxCollateral} lovelace of collateral at risk`);
    }
  }

  // Validity: short.
  const ttl = body.ttl_bignum();
  if (!ttl || Number(ttl.to_str()) > chain.tip + limits.maxValiditySlots) problems.push('a validity interval that is too long');

  // Signers: the wallet's keys and the named co-signers, each of them required.
  const named = o.cosignerKeys ?? [];
  const required = new Set();
  const rs = body.required_signers();
  for (let i = 0; rs && i < rs.len(); i++) {
    const k = rs.get(i).to_hex();
    required.add(k);
    if (!wallet.keyHashes.includes(k) && !named.includes(k)) problems.push('a required signer that is not the wallet');
  }
  for (const k of named) if (!required.has(k)) problems.push('a co-signer the transaction does not require');
  return { problems, report };
}
