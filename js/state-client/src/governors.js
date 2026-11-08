// Script governors for @AuthorizedBy (ADR-0009 D3) from a page (ADR-0015 M3). A governor is a plain JuLC validator
// (a withdraw-zero script, built by `joust build` beside the contracts) whose stake credential is registered once per
// network; a call it approves withdraws zero from it, with the Approvals redeemer naming exactly that call. The page
// checks the registration transaction before the wallet signs it, as it checks calls.

import { DEFAULT_LIMITS, refOf, valueMap, add, paymentKeyHashOf } from './verify.js';
import { hexToBytes } from './hex.js';

/**
 * A plain validator's parameters as Data (CBOR hex, in order), from JSON values by their Java types: byte[] (hex),
 * BigInteger (bigint, number or decimal string), boolean, PlutusData (CBOR hex).
 */
export function governorParams(CSL, validator, values = {}) {
  return (validator.params ?? []).map((p) => {
    const v = Array.isArray(values) ? values[validator.params.indexOf(p)] : values[p.name];
    if (v === undefined) throw new Error(`${validator.name}: missing parameter ${p.name}`);
    const t = p.type.replace(/^java\.math\./, '');
    if (t === 'byte[]') {
      if (typeof v !== 'string' || !/^([0-9a-f]{2})*$/.test(v)) throw new Error(`${validator.name}.${p.name}: hex bytes`);
      return CSL.PlutusData.new_bytes(hexToBytes(v)).to_hex();
    }
    if (t === 'BigInteger') return CSL.PlutusData.new_integer(CSL.BigInt.from_str(BigInt(v).toString())).to_hex();
    if (t === 'boolean') {
      return CSL.PlutusData.new_constr_plutus_data(CSL.ConstrPlutusData.new(CSL.BigNum.from_str(v ? '1' : '0'), CSL.PlutusList.new())).to_hex();
    }
    if (t === 'PlutusData' || t === 'org.julclang.core.PlutusData') return CSL.PlutusData.from_hex(v).to_hex();
    throw new Error(`${validator.name}.${p.name}: a parameter of type ${p.type} (give it as PlutusData)`);
  });
}

/** The reward address of a script credential: a governor's (bech32). */
export function rewardAddressOf(CSL, scriptHash, networkId) {
  return CSL.RewardAddress.new(networkId === 1 ? 1 : 0, CSL.Credential.from_scripthash(CSL.ScriptHash.from_hex(scriptHash)))
    .to_address().to_bech32();
}

/** Approvals [Approval(policy, target, action)] (org.julclang.state.api): what a governor grants one call. */
export function approvalsData(CSL, policy, target, action) {
  const approval = CSL.PlutusList.new();
  approval.add(CSL.PlutusData.new_bytes(hexToBytes(policy)));
  approval.add(CSL.PlutusData.new_bytes(hexToBytes(target)));
  approval.add(CSL.PlutusData.new_integer(CSL.BigInt.from_str(String(action))));
  const list = CSL.PlutusList.new();
  list.add(CSL.PlutusData.new_constr_plutus_data(CSL.ConstrPlutusData.new(CSL.BigNum.zero(), approval)));
  const fields = CSL.PlutusList.new();
  fields.add(CSL.PlutusData.new_list(list));
  return CSL.PlutusData.new_constr_plutus_data(CSL.ConstrPlutusData.new(CSL.BigNum.zero(), fields));
}

/**
 * Everything wrong with {@code txHex} as the registration of {@code governor}'s stake credential, paid by the wallet:
 * one stake registration certificate for exactly that script credential, the deposit the protocol asks, the wallet's
 * inputs and outputs only, and nothing else (no withdrawals, mints, scripts, votes, metadata or other signers).
 *
 * @param o.governor  { hash } (the applied script's)
 * @param o.wallet    { addresses, keyHashes, utxos } (walletView)
 * @param o.chain     { params, tip } (protocol parameters: key_deposit; the tip slot)
 */
export function verifyRegistration(o) {
  const { CSL, txHex, governor, wallet, chain, networkId } = o;
  const limits = { ...DEFAULT_LIMITS, ...(o.limits ?? {}) };
  const problems = [];
  let tx;
  try {
    tx = CSL.Transaction.from_hex(txHex);
  } catch (e) {
    return { problems: ['not a transaction'] };
  }
  const body = tx.body();
  const ws = tx.witness_set();
  const isWallet = (addr) => wallet.addresses.includes(addr.to_bech32())
    || (CSL.EnterpriseAddress.from_address(addr) && wallet.keyHashes.includes(paymentKeyHashOf(CSL, addr)));
  if (!tx.is_valid()) problems.push('is_valid is false');
  if (body.voting_procedures() || body.voting_proposals()) problems.push('votes or proposals');
  if (body.donation() || body.current_treasury_value()) problems.push('a treasury donation');
  if (body.withdrawals() && body.withdrawals().len() > 0) problems.push('withdrawals');
  if (body.mint()) problems.push('a mint');
  if (tx.auxiliary_data()) problems.push('auxiliary data');
  if (ws.redeemers() && ws.redeemers().len() > 0) problems.push('redeemers');
  if (ws.plutus_scripts() && ws.plutus_scripts().len() > 0) problems.push('scripts');
  if ((body.collateral() && body.collateral().len() > 0) || body.collateral_return() || body.total_collateral()) problems.push('collateral');
  // Validity: a short window, if any (a registration does not depend on time).
  const ttl = body.ttl_bignum();
  if (ttl && chain.tip !== undefined && Number(ttl.to_str()) > chain.tip + limits.maxValiditySlots) problems.push('a validity interval that is too long');
  if (body.reference_inputs() && body.reference_inputs().len() > 0) problems.push('reference inputs');
  if (body.network_id() && body.network_id().kind() !== (networkId === 1 ? CSL.NetworkIdKind.Mainnet : CSL.NetworkIdKind.Testnet)) {
    problems.push('another network');
  }

  // The certificate: one registration of the governor's script credential.
  const certs = body.certs();
  let deposit = null;
  if (!certs || certs.len() !== 1) problems.push(`${certs ? certs.len() : 0} certificates, expected one registration`);
  else {
    const c = certs.get(0);
    let cred = null;
    if (c.kind() === CSL.CertificateKind.StakeRegistration) {
      const r = c.as_stake_registration();
      cred = r.stake_credential();
      deposit = r.coin() ? BigInt(r.coin().to_str()) : BigInt(chain.params.key_deposit);
    } else {   // CSL 15 reads Conway's reg_cert as a StakeRegistration with its coin
      problems.push('a certificate that is not a stake registration');
    }
    if (cred && (cred.kind() !== CSL.CredKind.Script || cred.to_scripthash().to_hex() !== governor.hash)) {
      problems.push('the registration is for another credential than the governor');
    }
    if (deposit !== null && deposit !== BigInt(chain.params.key_deposit)) problems.push(`a deposit of ${deposit}, not the protocol's ${chain.params.key_deposit}`);
  }

  // Inputs and outputs: the wallet's only; the wallet pays exactly the fee and the deposit.
  const consumed = new Map();
  const inputs = body.inputs();
  for (let i = 0; i < inputs.len(); i++) {
    const r = refOf(inputs.get(i));
    if (!wallet.utxos.has(r)) problems.push(`an input that is not the wallet's: ${r}`);
    else add(consumed, wallet.utxos.get(r).value);
  }
  const produced = new Map();
  const outs = body.outputs();
  for (let i = 0; i < outs.len(); i++) {
    const out = outs.get(i);
    if (!isWallet(out.address())) problems.push('an output to another address');
    if (out.has_plutus_data() || out.has_data_hash() || out.has_script_ref()) problems.push('an output with a datum or script');
    add(produced, valueMap(out.amount()));
  }
  const fee = BigInt(body.fee().to_str());
  add(produced, new Map([['lovelace', fee + (deposit ?? 0n)]]));
  for (const unit of new Set([...consumed.keys(), ...produced.keys()])) {
    if ((consumed.get(unit) ?? 0n) !== (produced.get(unit) ?? 0n)) problems.push(`the transaction does not balance in ${unit}`);
  }
  if (fee > limits.maxFee) problems.push(`a fee of ${fee}, above the page's limit of ${limits.maxFee}`);
  const rs = body.required_signers();
  for (let i = 0; rs && i < rs.len(); i++) if (!wallet.keyHashes.includes(rs.get(i).to_hex())) problems.push('a required signer that is not the wallet');
  return { problems, fee, deposit };
}
