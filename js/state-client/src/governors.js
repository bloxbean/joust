// Script governors for @AuthorizedBy (ADR-0009 D3) from a page (ADR-0015 M3). A governor is a plain JuLC validator
// (a withdraw-zero script, built by `joust build` beside the contracts) whose stake credential is registered once per
// network; a call it approves withdraws zero from it, with the Approvals redeemer naming exactly that call. The page
// checks the registration transaction before the wallet signs it, as it checks calls.

import { DEFAULT_LIMITS, add, isWalletEnterprise } from './verify.js';
import { Data, decodeData, dataHex } from './pdata.js';
import { parseTx, rewardAddress } from './ledger.js';
import { hexToBytes } from './hex.js';

/** Drops a leading ledger-library argument (earlier signatures took CSL first). */
const args = (a) => (a.length && typeof a[0] === 'object' && a[0] !== null && typeof a[0].PlutusData === 'function' ? a.slice(1) : a);

/**
 * A plain validator's parameters as Data (CBOR hex, in order), from JSON values by their Java types: byte[] (hex),
 * BigInteger (bigint, number or decimal string), boolean, PlutusData (CBOR hex). ({@code (CSL, validator, values)} accepted.)
 */
export function governorParams(...a) {
  const [validator, values = {}] = args(a);
  return (validator.params ?? []).map((p) => {
    const v = Array.isArray(values) ? values[validator.params.indexOf(p)] : values[p.name];
    if (v === undefined) throw new Error(`${validator.name}: missing parameter ${p.name}`);
    const t = p.type.replace(/^java\.math\./, '');
    if (t === 'byte[]') {
      if (typeof v !== 'string' || !/^([0-9a-f]{2})*$/.test(v)) throw new Error(`${validator.name}.${p.name}: hex bytes`);
      return dataHex(Data.bytes(hexToBytes(v)));
    }
    if (t === 'BigInteger') return dataHex(Data.int(BigInt(v)));
    if (t === 'boolean') return dataHex(Data.constr(v ? 1 : 0, []));
    if (t === 'PlutusData' || t === 'org.julclang.core.PlutusData') return dataHex(decodeData(v));
    throw new Error(`${validator.name}.${p.name}: a parameter of type ${p.type} (give it as PlutusData)`);
  });
}

/** The reward address of a script credential: a governor's, or a contract's own (bech32). ({@code (CSL, ...)} accepted.) */
export function rewardAddressOf(...a) {
  const [scriptHash, networkId] = args(a);
  return rewardAddress({ type: 'script', hash: scriptHash }, networkId);
}

/** Approvals [Approval(policy, target, action)] (org.julclang.state.api): what a governor grants one call. */
export function approvalsData(...a) {
  const [policy, target, action] = args(a);
  return Data.constr(0, [Data.list([Data.constr(0, [Data.bytes(hexToBytes(policy)), Data.bytes(hexToBytes(target)), Data.int(BigInt(action))])])]);
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
  const { txHex, governor, wallet, chain, networkId } = o;
  const limits = { ...DEFAULT_LIMITS, ...(o.limits ?? {}) };
  const problems = [];
  let tx;
  try {
    tx = parseTx(txHex);
  } catch (e) {
    return { problems: ['not a transaction'] };
  }
  const isWallet = (addr) => wallet.addresses.includes(addr) || isWalletEnterprise(addr, wallet);
  if (!tx.isValid) problems.push('is_valid is false');
  if (tx.hasVotes || tx.hasProposals) problems.push('votes or proposals');
  if (tx.hasDonation || tx.hasTreasuryValue) problems.push('a treasury donation');
  if (tx.withdrawals.length > 0) problems.push('withdrawals');
  if (tx.mint.size) problems.push('a mint');
  if (tx.hasAuxiliaryData) problems.push('auxiliary data');
  if (tx.redeemers.length > 0) problems.push('redeemers');
  if (tx.plutusScripts > 0) problems.push('scripts');
  if (tx.collateral.length > 0 || tx.collateralReturn || tx.totalCollateral !== null) problems.push('collateral');
  // Validity: a short window, if any (a registration does not depend on time).
  if (tx.ttl !== null && chain.tip !== undefined && Number(tx.ttl) > chain.tip + limits.maxValiditySlots) problems.push('a validity interval that is too long');
  if (tx.referenceInputs.length > 0) problems.push('reference inputs');
  if (tx.networkId !== null && tx.networkId !== (networkId === 1 ? 1 : 0)) problems.push('another network');

  // The certificate: one registration of the governor's script credential.
  let deposit = null;
  if (tx.certificates.length !== 1) problems.push(`${tx.certificates.length} certificates, expected one registration`);
  else {
    const c = tx.certificates[0];
    if (c.kind !== 'StakeRegistration' && c.kind !== 'Registration') problems.push('a certificate that is not a stake registration');
    else {
      // A legacy registration carries no deposit (the protocol's applies); Conway's reg_cert states it.
      deposit = c.deposit ?? BigInt(chain.params.key_deposit);
      if (c.credential?.type !== 'script' || c.credential.hash !== governor.hash) problems.push('the registration is for another credential than the governor');
      if (deposit !== BigInt(chain.params.key_deposit)) problems.push(`a deposit of ${deposit}, not the protocol's ${chain.params.key_deposit}`);
    }
  }

  // Inputs and outputs: the wallet's only; the wallet pays exactly the fee and the deposit.
  const consumed = new Map();
  for (const r of tx.inputs) {
    if (!wallet.utxos.has(r)) problems.push(`an input that is not the wallet's: ${r}`);
    else add(consumed, wallet.utxos.get(r).value);
  }
  const produced = new Map();
  for (const out of tx.outputs) {
    if (!isWallet(out.address)) problems.push('an output to another address');
    if (out.datum || out.datumHash || out.hasScriptRef) problems.push('an output with a datum or script');
    add(produced, out.value);
  }
  add(produced, new Map([['lovelace', tx.fee + (deposit ?? 0n)]]));
  for (const unit of new Set([...consumed.keys(), ...produced.keys()])) {
    if ((consumed.get(unit) ?? 0n) !== (produced.get(unit) ?? 0n)) problems.push(`the transaction does not balance in ${unit}`);
  }
  if (tx.fee > limits.maxFee) problems.push(`a fee of ${tx.fee}, above the page's limit of ${limits.maxFee}`);
  for (const k of tx.requiredSigners) if (!wallet.keyHashes.includes(k)) problems.push('a required signer that is not the wallet');
  return { problems, fee: tx.fee, deposit };
}
