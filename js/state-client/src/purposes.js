// Other script purposes (ADR-0010) from a page (ADR-0015 M3): a contract's own stake credential. Its funds sit at
// base addresses staked to its script; its @Certify handler decides delegations, its @Withdraw handler where the
// rewards go. Each prepared transaction is verified before the wallet signs: nothing beyond the operation, the
// wallet's funds bounded (it pays the fee only), the redeemers the page's own.

import { DEFAULT_LIMITS, add, isWalletEnterprise } from './verify.js';
import { codec as makeCodec } from './data.js';
import { Data, dataEqual } from './pdata.js';
import { rewardAddressOf } from './governors.js';
import { parseTx } from './ledger.js';

/** The contract's purpose handler of {@code purpose} (withdraw, certify, vote, propose), or null. */
export const purposeOf = (description, purpose) => (description['x-joust'].purposes ?? []).find((p) => p.purpose === purpose) ?? null;

/** Rewards(74, amount, args...): a @Withdraw redeemer, as the page encodes it. ({@code (CSL, ...)} accepted.) */
export function rewardsData(...a) {
  const [description, meta, amount, args = {}] = typeof a[0]?.PlutusData === 'function' ? a.slice(1) : a;
  const c = makeCodec(description);
  return Data.constr(0, [Data.int(BigInt(meta.redeemer.marker ?? 74)), Data.int(BigInt(amount)),
    ...meta.args.map((x) => c.encode(x.schema, args[x.name], x.name))]);
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
  const { description, deployment, txHex, wallet, chain, op } = o;
  const limits = { ...DEFAULT_LIMITS, ...(o.limits ?? {}) };
  const problems = [];
  let tx;
  try {
    tx = parseTx(txHex);
  } catch (e) {
    return { problems: ['not a transaction'], report: {} };
  }
  const report = { fee: tx.fee };
  const policy = deployment.policy;
  const own = rewardAddressOf(policy, deployment.networkId);
  const meta = purposeOf(description, op === 'delegate' ? 'certify' : 'withdraw');
  if (!meta) return { problems: [`${description['x-joust'].contract} has no ${op === 'delegate' ? '@Certify' : '@Withdraw'} handler`], report };
  const isWallet = (addr) => wallet.addresses.includes(addr) || isWalletEnterprise(addr, wallet);

  if (!tx.isValid) problems.push('is_valid is false (the collateral would be taken)');
  if (tx.hasVotes || tx.hasProposals) problems.push('votes or proposals');
  if (tx.hasDonation || tx.hasTreasuryValue) problems.push('a treasury donation');
  if (tx.mint.size) problems.push('a mint');
  if (tx.hasAuxiliaryData) problems.push('auxiliary data');
  if (tx.networkId !== null && tx.networkId !== (deployment.networkId === 1 ? 1 : 0)) problems.push('another network');

  // Certificates: a delegation's one, of the script's own credential to the page's pool; none for a collection.
  const certs = tx.certificates;
  if (op === 'delegate') {
    if (certs.length !== 1) problems.push(`${certs.length} certificates, expected one delegation`);
    else if (certs[0].kind !== 'StakeDelegation') problems.push('a certificate that is not a stake delegation');
    else {
      const d = certs[0];
      if (d.credential?.type !== 'script' || d.credential.hash !== policy) problems.push('a delegation of another credential');
      if (d.pool !== o.pool) problems.push('a delegation to another pool than the page asked');
    }
  } else if (certs.length > 0) problems.push('certificates');

  // Withdrawals: a collection's whole balance from the script's own credential, and the output verifier's zero.
  const order = tx.withdrawals.map((w) => ({ bech32: w.address, bytes: w.bytes }));
  let withdrawn = 0n;
  for (const w of tx.withdrawals) {
    if (op === 'collect' && w.address === own) {
      withdrawn += w.amount;
      if (w.amount !== BigInt(o.amount)) problems.push(`a withdrawal of ${w.amount}, not the ${o.amount} the page asked`);
    } else if (op === 'collect' && deployment.outputVerifier && w.address === deployment.outputVerifier.rewardAddress && w.amount === 0n) {
      // the output verifier: checks the sends' claims on chain
    } else problems.push('a withdrawal the page did not ask for');
  }
  if (op === 'collect' && !order.some((r) => r.bech32 === own)) problems.push('no withdrawal from the contract\'s stake credential');

  // Redeemers: the handler's, on its certificate or its withdrawal; the output verifier's.
  let mine = 0;
  for (const r of tx.redeemers) {
    const kind = r.tag;
    const index = r.index;
    if (op === 'delegate' && kind === 'cert' && index === 0) {
      mine++;
      if (!dataEqual(r.data, Data.constr(0, []))) problems.push('the certificate redeemer is not the handler\'s');
    } else if (op === 'collect' && kind === 'reward' && order[index]?.bech32 === own) {
      mine++;
      let want;
      try {
        want = rewardsData(description, meta, o.amount, o.args ?? {});
      } catch (e) {
        problems.push(e.message);
        continue;
      }
      if (!dataEqual(r.data, want)) problems.push('the Rewards redeemer is not the page\'s');
    } else if (op === 'collect' && kind === 'reward' && deployment.outputVerifier
      && order[index]?.bech32 === deployment.outputVerifier.rewardAddress) {
      // the output verifier's claims: checked on chain
    } else problems.push('a redeemer the operation does not need');
  }
  if (mine !== 1) problems.push(`${mine} redeemers of the handler, expected 1`);

  // Inputs: the wallet's only. Reference inputs: the pinned reference scripts (the contract's, the output verifier's).
  const consumed = new Map();
  for (const r of tx.inputs) {
    if (!wallet.utxos.has(r)) problems.push(`an input that is not the wallet's: ${r}`);
    else add(consumed, wallet.utxos.get(r).value);
  }
  const pinned = new Set([...(deployment.referenceScripts ?? []), ...(deployment.outputVerifier?.referenceScripts ?? [])]);
  for (const r of tx.referenceInputs) if (!pinned.has(r)) problems.push(`a reference input the page does not know: ${r}`);

  // Outputs: the wallet's (no datum or script); others only from the rewards withdrawn.
  const produced = new Map();
  const back = new Map();
  let others = 0n;
  for (const out of tx.outputs) {
    const value = out.value;
    add(produced, value);
    if (out.hasScriptRef || out.datumHash) problems.push('an output with a script or datum hash');
    if (isWallet(out.address)) {
      if (out.datum) problems.push('a wallet output with a datum');
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
  const coll = tx.collateral;
  const ret = tx.collateralReturn;
  if (coll.length === 0 || !ret || tx.totalCollateral === null) problems.push('collateral without a return and a total');
  else {
    let inColl = 0n;
    for (const r of coll) {
      if (!wallet.utxos.has(r)) problems.push('collateral that is not the wallet\'s');
      else inColl += wallet.utxos.get(r).value.get('lovelace') ?? 0n;
    }
    if (!isWallet(ret.address)) problems.push('the collateral return goes elsewhere');
    if (tx.totalCollateral > limits.maxCollateral || inColl - ret.value.get('lovelace') > limits.maxCollateral) {
      problems.push(`more than ${limits.maxCollateral} lovelace of collateral at risk`);
    }
  }

  // Validity: short.
  if (tx.ttl === null || Number(tx.ttl) > chain.tip + limits.maxValiditySlots) problems.push('a validity interval that is too long');

  // Signers: the wallet's keys and the named co-signers, each of them required.
  const named = o.cosignerKeys ?? [];
  const required = new Set();
  for (const k of tx.requiredSigners) {
    required.add(k);
    if (!wallet.keyHashes.includes(k) && !named.includes(k)) problems.push('a required signer that is not the wallet');
  }
  for (const k of named) if (!required.has(k)) problems.push('a co-signer the transaction does not require');
  return { problems, report };
}
