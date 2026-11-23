// Verifies a prepared transaction before a CIP-30 wallet signs it (ADR-0013 D2), against what the page knows itself:
// the contract's bundled description and deployment (the pinned script), the wallet's own UTxOs, the chain (tip,
// protocol parameters, the UTxOs the transaction reads), and the call the page asked for. The service that prepared
// the transaction is not trusted for any of it; the chain provider the app chose and the wallet are (ADR-0015 §5).
//
// What the page does not check, the pinned validator does: the next state (or object) is exactly what the contract's
// method computes from the call, payouts are paid, and the script's value is conserved. So the page checks that the
// transaction makes exactly the call it asked for, on the pinned script, and that the wallet risks nothing else: it
// loses at most the payment, the fee and (for a creation) the new UTxO's minimum ADA; others are paid only from what the
// script releases beyond its payouts and its claimed sends to the wallet, plus the payment the redeemer binds. Rules
// the validators enforce on chain and that do not bound the wallet's loss are left to them.

import { codec as makeCodec, dataEqual } from './data.js';
import { instanceIdOf, objectIdOf } from './tokens.js';
import { bytesToHex } from './hex.js';
import { approvalsData, rewardAddressOf } from './governors.js';
import { parseTx, addressParts, integrityHashOf } from './ledger.js';
import { dataHex, decodeData } from './pdata.js';
import { isKeyedCall, verifyKeyed } from './keyed.js';
import { outputRegistry, claimedSends } from './claims.js';

export const DEFAULT_LIMITS = {
  maxCollateral: 5_000_000n,     // lovelace a failing script may cost
  maxValiditySlots: 900,         // a prepared transaction expires within 15 minutes (one-second slots)
  maxStartSlots: 60,
  maxBackdateSlots: 120,         // a Ctx call's validity starts at most 2 minutes before now
  feeMargin: 500_000n,           // over 1.2 x the protocol's fee for this size and budget (reference scripts)
  maxFee: 3_000_000n,            // an absolute bound: the fee cap above trusts the chain API's protocol parameters (r5)
};

export const SPEND_KINDS = new Set(['action', 'destroy', 'ownedAction', 'ownedDestroy']);
export const DESTROYS = new Set(['destroy', 'ownedDestroy']);
export const CREATIONS = new Set(['init', 'ownedInit']);

/** "txhash#index" of an input: a ref string already, or a ledger library's input object (earlier callers). */
export const refOf = (input) => (typeof input === 'string' ? input : `${input.transaction_id().to_hex()}#${input.index()}`);

/** Ledger order of inputs: by transaction id, then index. */
export function ledgerOrder(refs) {
  return [...refs].sort((a, b) => {
    const [ha, ia] = a.split('#');
    const [hb, ib] = b.split('#');
    return ha < hb ? -1 : ha > hb ? 1 : Number(ia) - Number(ib);
  });
}

/** A value as Map unit -> bigint ('lovelace', or policy hex + asset name hex): a Map already, or a ledger library's Value. */
export function valueMap(v) {
  if (v instanceof Map) return v;
  const m = new Map([['lovelace', BigInt(v.coin().to_str())]]);
  const ma = v.multiasset();
  if (ma) {
    const ps = ma.keys();
    for (let i = 0; i < ps.len(); i++) {
      const assets = ma.get(ps.get(i));
      const names = assets.keys();
      for (let j = 0; j < names.len(); j++) {
        const unit = ps.get(i).to_hex() + bytesToHex(names.get(j).name());
        m.set(unit, (m.get(unit) ?? 0n) + BigInt(assets.get(names.get(j)).to_str()));
      }
    }
  }
  return m;
}

export const add = (into, m, sign = 1n) => { for (const [k, v] of m) into.set(k, (into.get(k) ?? 0n) + sign * v); };

/** The description's redeemer definitions, from its validators. */
export function redeemerDefs(c, description) {
  const out = {};
  for (const v of description.validators) {
    if (v.title.endsWith('.mint')) out.mint = c.resolve(v.redeemer.schema);
    if (v.title.endsWith('.spend')) out.spend = c.resolve(v.redeemer.schema);
  }
  return out;
}

/**
 * Everything wrong with {@code txHex} for {@code call}; an empty {@code problems} means the wallet may sign it.
 *
 * @param o.CSL          (ignored; earlier versions read transactions with it)
 * @param o.description  the contract's description (bundled with the page)
 * @param o.deployment   { policy, address (bech32), networkId, referenceScripts?: ["tx#i"] } (bundled with the page)
 * @param o.call         { kind, meta (the x-joust action or creation), args, pay: { lovelace, assets? }, instance, object }
 * @param o.wallet       { addresses: [bech32], keyHashes: [hex], utxos: Map ref -> { address, value: Map } }
 * @param o.chain        { tip (slot), params (with a PlutusV3 cost model: the script integrity hash), utxo: async ref ->
 *                       { address, value: Map, referenceScriptHash, inlineDatum (hex; an owned destroy's Holding) } | null }
 * @returns { problems: string[], report }
 */
export async function verifyPrepared(o) {
  // A keyed call, or a keyed contract's creation with its list heads: the keyed rules (ADR-0016 D11).
  if (isKeyedCall(o.description, o.call)) return verifyKeyed(o);
  const { description, deployment, txHex, call, wallet, chain } = o;
  const limits = { ...DEFAULT_LIMITS, ...(o.limits ?? {}) };
  const c = makeCodec(description);
  const rdefs = redeemerDefs(c, description);
  const xj = description['x-joust'];
  const problems = [];
  const report = { outputs: [], next: null, redeemer: null, created: null };
  const policy = deployment.policy;
  // The wallet's addresses, and the enterprise address of one of its payment keys: no stake credential, so wholly the
  // wallet's (a contract may send there, e.g. to ctx.caller()). A wallet key under a stranger's stake key stays refused.
  const isWallet = (addr) => wallet.addresses.includes(addr) || isWalletEnterprise(addr, wallet);
  const atScript = (bech32) => bech32 === deployment.address;
  const pay = { lovelace: BigInt(call.pay?.lovelace ?? 0), assets: call.pay?.assets ?? [] };

  let tx;
  try {
    tx = parseTx(txHex);
  } catch (e) {
    return { problems: ['not a transaction'], report };
  }

  if (call.meta.needsService && !verifiableWithoutService(call.meta, description, deployment)) {
    problems.push(`${call.meta.name} needs data only the service computes; the page cannot verify it`);
  }
  if (!SPEND_KINDS.has(call.kind) && !CREATIONS.has(call.kind)) problems.push('unknown call kind ' + call.kind);

  // Nothing beyond the call.
  if (!tx.isValid) problems.push('is_valid is false (the collateral would be taken)');
  if (tx.certificates.length > 0) problems.push('certificates');
  if (tx.hasVotes || tx.hasProposals) problems.push('votes or proposals');
  if (tx.hasDonation || tx.hasTreasuryValue) problems.push('a treasury donation');
  // Withdrawals: none, except zero withdrawals from JOUST's verifiers the deployment pins: the map verifier (the
  // call's MerkleMap claims; ADR-0002 D9) and the output verifier (its sends; ADR-0007 D5), checked on chain.
  const pinnedVerifiers = [deployment.mapVerifier, deployment.outputVerifier].filter(Boolean);
  const verifierRewards = new Set(pinnedVerifiers.map((v) => v.rewardAddress));
  // A script governor's zero withdrawal (@AuthorizedBy with a script credential; ADR-0009 D3): the credential the
  // deployment's @Params name, at the reward address the page derives itself.
  const governor = governorOf(call, deployment, problems);
  const allowedRewards = new Set(verifierRewards);
  if (governor) allowedRewards.add(governor.rewardAddress);
  // The withdrawals in ledger order (a Reward redeemer's index).
  const rewardOrder = tx.withdrawals.map((w) => ({ bech32: w.address, bytes: w.bytes }));
  if (tx.withdrawals.length > 0) {
    let ok = tx.withdrawals.length <= allowedRewards.size;
    for (const w of tx.withdrawals) if (!allowedRewards.has(w.address) || w.amount !== 0n) ok = false;
    if (!ok) problems.push('withdrawals');
  }
  const governorIndex = governor ? rewardOrder.findIndex((r) => r.bech32 === governor.rewardAddress) : -1;
  if (governor && governorIndex < 0) problems.push('the governor does not approve the call (no zero withdrawal from it)');
  const withdrawalCount = tx.withdrawals.length;
  if (tx.hasAuxiliaryData) problems.push('auxiliary data');
  if (tx.networkId !== null && tx.networkId !== (deployment.networkId === 1 ? 1 : 0)) problems.push('another network');

  // The target: the instance or object the call spends (spending calls).
  const targetName = call.kind.startsWith('owned') && call.kind !== 'ownedInit' ? call.object : call.instance;
  const targetUnit = SPEND_KINDS.has(call.kind) ? policy + targetName : null;

  // Inputs: the wallet's own UTxOs and, for a spending call, the target; nothing else.
  const sorted = ledgerOrder(tx.inputs);
  const spent = new Map();
  const consumed = new Map();     // every input's value: the wallet's and the target's
  let targetIndex = -1;
  let targetRef = null;
  let targetValue = new Map();
  let targetDatum = null;
  const walletInputs = new Set();
  for (let i = 0; i < sorted.length; i++) {
    const r = sorted[i];
    if (wallet.utxos.has(r)) {
      add(spent, wallet.utxos.get(r).value);
      add(consumed, wallet.utxos.get(r).value);
      walletInputs.add(r);
      continue;
    }
    const u = targetUnit ? await chain.utxo(r) : null;
    if (u && atScript(u.address) && u.value.get(targetUnit) === 1n && targetIndex < 0) {
      targetIndex = i;
      targetRef = r;
      targetValue = u.value;
      targetDatum = u.inlineDatum ?? null;
      add(consumed, u.value);
    } else {
      problems.push(`an input that is neither the wallet's nor the ${call.kind.startsWith('owned') ? 'object' : 'instance'}: ${r}`);
    }
  }
  if (targetUnit && targetIndex < 0) problems.push('the pinned instance or object is not spent');
  report.targetRef = targetRef;
  // A consistency check of the page's own call: an owned destroy leaves no continuation to decode, so the object's input
  // datum (its Holding, as the chain provider gives it) must name the call's instance, as decodeNext checks for an owned
  // action's continuation.
  if (call.kind === 'ownedDestroy' && targetRef && heldBy(c, xj, call, targetDatum) !== call.instance) {
    problems.push(`${call.meta.name}: the object is held by another instance than the call's`);
  }
  // A method that is not payable takes no lovelace (as client.js and verifyComposed refuse).
  if (call.meta.payable === false && pay.lovelace !== 0n) problems.push(`${call.meta.name}: it is not payable, but the page pays ${pay.lovelace} lovelace`);

  // Redeemers: exactly the call (and a destroy's burn), on the pinned script.
  const rds = tx.redeemers;
  const spends = [];
  const mints = [];
  let governorRedeemers = 0;
  for (const r of rds) {
    if (r.tag === 'spend') spends.push(r);
    else if (r.tag === 'mint') mints.push(r);
    else if (r.tag === 'reward' && governor && r.index === governorIndex) {
      // The governor grants exactly this call: Approvals [Approval(policy, instance, the action's tag)] (I33).
      governorRedeemers++;
      if (!dataEqual(r.data, approvalsData(policy, call.instance, call.meta.redeemer.index))) {
        problems.push('the governor\'s redeemer approves something else than this call');
      }
    } else if (r.tag === 'reward' && r.index < withdrawalCount) { /* a pinned verifier's */ }
    else problems.push('a redeemer that is neither a spend nor a mint');
  }
  if (governor && governorRedeemers !== 1) problems.push('the governor\'s withdrawal has no Approvals redeemer');

  // Mint: none for an action; the target's token burned for a destroy; the new token for a creation.
  const minted = new Map(tx.mint);
  const mintPolicies = [...new Set([...minted.keys()].map((u) => u.slice(0, 56)))];

  let createdUnit = null;
  let acceptsAssets = false;
  let paymentBound = false;      // the redeemer's Ctx states the payment (the validator checks it receives that)
  const callFields = (def, r, what) => {
    let decoded;
    try {
      decoded = c.decode(def, r.data);
    } catch (e) {
      problems.push(`${what}: ${e.message}`);
      return null;
    }
    const alt = c.resolve(def).anyOf.find((a) => a.index === r.data.constr);
    return { decoded, alt, fields: r.data.fields, data: dataHex(r.data) };
  };

  /** The call's own fields: arguments byte-equal to the page's encoding; ctx for this wallet and payment. */
  const checkCall = (got) => {
    if (!got) return;
    const { alt, fields, decoded } = got;
    report.redeemer = decoded;
    report.callData = got.data;
    if (alt.title !== call.meta.redeemer.constructor || alt.index !== call.meta.redeemer.index) {
      problems.push(`the redeemer is ${alt.title}, not the call the page asked for (${call.meta.redeemer.constructor})`);
      return;
    }
    const argNames = new Set(call.meta.args.map((a) => a.name));
    // An offer of assets binds only through the redeemer's assetsIn: a method without one takes none.
    acceptsAssets = alt.fields.some((f) => f.title === 'assetsIn');
    if (!acceptsAssets && [...pay.assets].length) problems.push('the call takes no assets, but the page offers some');
    alt.fields.forEach((f, i) => {
      const actual = fields[i];
      if (argNames.has(f.title)) {
        let want;
        try {
          want = c.encode(f, call.args[f.title], f.title);
        } catch (e) {
          problems.push(e.message);
          return;
        }
        if (!dataEqual(want, actual)) problems.push(`the redeemer's ${f.title} is not the argument the page gave`);
      } else if (f.title === 'ctx') {
        const ctx = decoded.ctx;
        if (!wallet.keyHashes.includes(ctx.caller)) problems.push('the call\'s caller is not this wallet');
        if (ctx.received !== pay.lovelace) problems.push(`the call receives ${ctx.received} lovelace, not the ${pay.lovelace} the page pays`);
        else paymentBound = true;
      } else if (f.title === 'assetsIn') {
        const want = c.encode(f, pay.assets, 'assetsIn');
        if (!dataEqual(want, actual)) problems.push('the call offers other assets than the page pays');
      } else if (f.title === 'events') {
        report.events = decoded.events;     // computed by the method; the validator checks them
      } else if (f.title === 'seed') {
        const seed = decoded.seed;
        const ref = `${seed.txId}#${seed.index}`;
        if (!walletInputs.has(ref)) problems.push('the creation\'s seed is not a wallet input it spends');
        else {
          const id = call.kind === 'init' ? instanceIdOf(seed.txId, Number(seed.index)) : objectIdOf(seed.txId, Number(seed.index));
          createdUnit = policy + id;
          report.created = id;
        }
      } else if (f.title === 'instance') {
        if (decoded.instance !== call.instance) problems.push('the object is created under another instance');
      } else {
        problems.push(`the redeemer has a field the page cannot check: ${f.title}`);
      }
    });
  };

  if (SPEND_KINDS.has(call.kind)) {
    if (spends.length !== 1) problems.push(`${spends.length} spend redeemers, expected 1`);
    else {
      const r = spends[0];
      if (r.index !== targetIndex) problems.push('the redeemer is not on the target input');
      checkCall(callFields(rdefs.spend, r, 'the call'));
    }
    // A destroy burns exactly its token. With a user mint besides (a destroy whose method mints), the policy's one
    // mint redeemer is ByAction naming the target, and the policy checks the burn and the mint together (mintMatches;
    // the runtime's AssetTx builds it so); else it is BurnInstance and the burn is the whole mint.
    const userMint = [...minted.keys()].some((u) => u !== targetUnit);
    if (DESTROYS.has(call.kind)) {
      if (minted.get(targetUnit) !== -1n) problems.push('a destroy must burn exactly its token');
      if (mints.length !== 1) problems.push('a destroy needs exactly one burn redeemer');
    }
    if (DESTROYS.has(call.kind) && !userMint) {
      if (mints.length === 1) {
        const got = callFields(rdefs.mint, mints[0], 'the burn');
        if (got && (got.alt.title !== 'BurnInstance' || got.alt.fields.length !== 0)) problems.push('the burn redeemer is not BurnInstance');
      }
    } else if (call.meta.returns?.mint) {
      // A method that mints the contract's own tokens (ADR-0007 D4): only under the pinned policy, never a reserved
      // name (a thread token's or an owned object's or an intent's: the policy refuses them, I27) other than a
      // destroy's own burn, with one ByAction redeemer naming the target it spends; the amounts are the method's,
      // checked on chain.
      for (const [unit, q] of minted) {
        if (DESTROYS.has(call.kind) && unit === targetUnit && q === -1n) continue;
        if (!unit.startsWith(policy) || unit === targetUnit) problems.push(`a mint of ${unit}, not one of the contract's own tokens`);
        else if (reservedName(unit.slice(56), !!xj.keyed)) problems.push(`a mint of ${unit}, a reserved token name`);
      }
      if (minted.size && mints.length !== 1) problems.push('a mint needs exactly one ByAction redeemer');
      if (!minted.size && mints.length) problems.push('a mint redeemer without a mint');
      if (mints.length === 1) {
        const got = callFields(rdefs.mint, mints[0], 'the mint');
        if (got && got.alt.title !== 'ByAction') problems.push('the mint redeemer is not ByAction');
        else if (got && report.targetRef && `${got.decoded.by.txId}#${got.decoded.by.index}` !== report.targetRef) {
          problems.push('the ByAction redeemer names another input than the target');
        }
      }
    } else {
      if (minted.size) problems.push('a mint');
      if (mints.length) problems.push('a mint redeemer');
    }
  } else if (CREATIONS.has(call.kind)) {
    if (spends.length) problems.push('a creation spends a script input');
    if (mints.length !== 1) problems.push(`${mints.length} mint redeemers, expected 1`);
    else checkCall(callFields(rdefs.mint, mints[0], 'the creation'));
    if (createdUnit && (minted.size !== 1 || minted.get(createdUnit) !== 1n)) problems.push('the creation must mint exactly its new token');
  }
  if (mints.length && mintPolicies.length > 1) problems.push('a mint under another policy');
  if (mintPolicies.length === 1 && mintPolicies[0] !== policy) problems.push('a mint under another policy');

  // Outputs: the wallet's (no datum except a payout tag naming the target), exactly one continuation (none for a
  // destroy), and other parties' (paid by the script, which checks them; listed in the report).
  const payoutTag = targetRef ? c.encode({ $ref: '#/definitions/org.julclang.ledger.TxOutRef' },
    { txId: targetRef.split('#')[0], index: targetRef.split('#')[1] }) : null;
  const back = new Map();          // every wallet output
  const tagged = new Map();        // wallet outputs carrying the payout tag (the script's payouts to the wallet)
  // Wallet outputs the output verifier's claims name for the target (ADR-0007 D5): the script's sends to the wallet,
  // never its change (on chain a send to the wallet's address may name any output there that covers it). No deposits:
  // they need their receiver in the same composition (ADR-0011 D3), and a receiver credits any claim on its output.
  const registry = outputRegistry(tx, deployment.outputVerifier, problems);
  if (registry?.deposits.length) problems.push('a deposit claim in a single call (deposits are verified only in a composition with their receiver)');
  const claimed = claimedSends(tx, [{ registry, owners: targetRef ? [targetRef] : [] }], isWallet, problems);
  const third = new Map();         // outputs to anyone else
  let continuation = new Map();
  const continuingUnit = createdUnit ?? (DESTROYS.has(call.kind) ? null : targetUnit);
  let continuations = 0;
  let createdLovelace = 0n;
  for (const out of tx.outputs) {
    const value = out.value;
    for (const unit of value.keys()) {
      // A thread token (a reserved name: an instance's, an owned object's, an intent's, a keyed object's) other than
      // the continuing one may not move. The contract's user tokens are bounded per unit like any other asset.
      if (unit.startsWith(policy) && unit !== continuingUnit && reservedName(unit.slice(56), !!xj.keyed)) problems.push('an output carries another token of the contract');
    }
    if (isWallet(out.address)) {
      if (out.hasScriptRef || out.datumHash) problems.push('a wallet output with a script or datum hash');
      const isTagged = out.datum && payoutTag && dataEqual(out.datum, payoutTag);
      if (out.datum && !isTagged) problems.push('a wallet output with a datum');
      add(back, value);
      if (isTagged) add(tagged, value);
      continue;
    }
    // The wallet's payment key at an address the wallet does not use (e.g. with a stranger's stake key): refused.
    const pkh = paymentKeyHashOf(out.address);
    if (pkh && wallet.keyHashes.includes(pkh)) problems.push('an output to the wallet\'s key at an address the wallet does not use');
    const bech = out.address;
    if (continuingUnit && atScript(bech) && value.get(continuingUnit) === 1n) {
      continuations++;
      continuation = value;
      createdLovelace = value.get('lovelace');
      const datum = out.datum;
      if (!datum) problems.push('the continuation has no inline datum');
      else {
        report.nextData = dataHex(datum);
        try {
          report.next = decodeNext(c, xj, call, datum);
        } catch (e) {
          problems.push('the continuing datum does not decode: ' + e.message);
        }
      }
      continue;
    }
    report.outputs.push({ address: bech, value });
    add(third, value);
  }
  if (continuingUnit && continuations !== 1) problems.push('not exactly one continuation of the instance or object');

  // The transaction balances: inputs and mint equal outputs and fee (an unbalanced one is not worth a signature).
  const produced = new Map([['lovelace', tx.fee]]);
  for (const out of tx.outputs) add(produced, out.value);
  const supplied = new Map(consumed);
  add(supplied, minted);
  for (const unit of new Set([...supplied.keys(), ...produced.keys()])) {
    if ((supplied.get(unit) ?? 0n) !== (produced.get(unit) ?? 0n)) {
      problems.push(`the transaction does not balance (${unit}: ${supplied.get(unit) ?? 0n} in, ${produced.get(unit) ?? 0n} out)`);
      break;
    }
  }

  // The wallet loses at most the payment, the fee and (a creation) the new UTxO; and no token it does not pay.
  const fee = tx.fee;
  const net = new Map(spent);
  add(net, back, -1n);
  const created = CREATIONS.has(call.kind) ? createdLovelace : 0n;
  if (CREATIONS.has(call.kind) && createdLovelace > pay.lovelace + BigInt(xj.minAda)) {
    problems.push(`the new UTxO holds ${createdLovelace} lovelace, more than the payment and its minimum ADA`);
  }
  const allowed = fee + (CREATIONS.has(call.kind) ? created : pay.lovelace);
  const offered = new Map();
  for (const [p, assets] of pay.assets) for (const [n, q] of assets) offered.set(p + n, BigInt(q));
  for (const [unit, q] of net) {
    if (unit === 'lovelace' && q > allowed) problems.push(`the wallet loses ${q} lovelace, more than the ${allowed} it pays (fee included)`);
    if (unit !== 'lovelace' && q > (offered.get(unit) ?? 0n)) problems.push(`the wallet loses ${q} of ${unit}`);
  }
  // Other parties are paid only from what the script releases beyond its payouts and its claimed sends to the wallet,
  // plus the payment the call takes (credit): neither may hide the wallet's own funds, or the script's release to it,
  // going elsewhere (review r4, Critical). This is the rule that protects the wallet. The validator settles the call
  // exactly (I3, I8, I26: continuation = target + received + accepted + mint - payouts - sends), so the release plus
  // the payment is what the call owes in payouts and sends; others then receive exactly what is owed to them and
  // nothing of the wallet's. The payment counts only where the redeemer binds it: lovelace where Ctx states it
  // (`received`), assets where `assetsIn` names them. A claimed send output is subtracted whole, including what the
  // wallet added to bring a send below its minimum ADA up to it (the page cannot tell that from lovelace the script
  // sends): a method that sends the caller less than the minimum ADA and pays released lovelace to others should give
  // the send its own minimum ADA.
  const released = new Map(targetValue);
  add(released, continuation, -1n);
  add(released, minted);
  const credit = SPEND_KINDS.has(call.kind) && targetRef ? boundPayment(pay, paymentBound, acceptsAssets) : new Map();
  for (const [unit, q] of third) {
    const free = (released.get(unit) ?? 0n) + (credit.get(unit) ?? 0n) - (tagged.get(unit) ?? 0n) - (claimed.value.get(unit) ?? 0n);
    const sends = claimed.indexes.size ? ' beyond its claimed sends to the wallet' : '';
    if (q > (free > 0n ? free : 0n)) problems.push(`others receive ${q} of ${unit}, more than the script releases to them${sends}`);
  }
  report.fee = fee;
  report.walletNet = net.get('lovelace') ?? 0n;

  envelopeChecks({ tx, txHex, call, wallet, chain, deployment, limits, isWallet }, problems);

  // Reference inputs: the pinned script's reference UTxOs, UTxOs of the pinned script (the instance, read), and the
  // foreign instances the call reads (@Reads), as the deployment's @Params name them (ADR-0011 D2).
  const foreign = (call.meta.recipe?.reads ?? []).map((rd) => {
    const fi = deployment.params?.[rd.params];
    return fi ? fi.policy + fi.instance : null;
  }).filter(Boolean);
  for (const r of tx.referenceInputs) {
    if ((deployment.referenceScripts ?? []).includes(r)) continue;
    if (pinnedVerifiers.some((v) => (v.referenceScripts ?? []).includes(r))) continue;
    const u = await chain.utxo(r);
    const ok = u && (u.referenceScriptHash === policy || (atScript(u.address) && u.value.get(policy + call.instance) === 1n)
      || foreign.some((unit) => u.value.get(unit) === 1n));
    if (!ok) problems.push(`a reference input the page does not know: ${r}`);
  }

  return { problems, report };
}

/**
 * A language's cost model from the chain's parameters, as a list in the ledger's order: Blockfrost's cost_models_raw,
 * else cost_models when it is a list or keyed by position ("000", "001", ...: Yaci Store); null otherwise (a model
 * keyed by parameter name has no order the page can rely on).
 */
function costModelOf(params, language) {
  const raw = params?.cost_models_raw?.[language];
  if (Array.isArray(raw)) return raw;
  const named = params?.cost_models?.[language];
  if (Array.isArray(named)) return named;
  if (!named || typeof named !== 'object') return null;
  const keys = Object.keys(named);
  if (keys.length === 0 || !keys.every((k) => /^\d+$/.test(k))) return null;
  return keys.map(Number).sort((a, b) => a - b).every((n, i) => n === i) ? keys.sort((a, b) => Number(a) - Number(b)).map((k) => named[k]) : null;
}

/**
 * The script integrity hash (Conway): the wallet signs the body only, and the body commits to the witness set's
 * redeemers and datums through this hash. Every verifier reads the call, the claims and the approvals from the witness
 * set, so the body must commit to exactly those, else whoever holds the signed body could submit it with other
 * redeemers. The hash is recomputed from the witness set's own bytes, with the languages of its Plutus scripts and
 * PlutusV3 (every JOUST script's) over the chain API's cost models. This is sound whatever cost models the chain API
 * gives: blake2b-256 binds the reviewed redeemers and datums and the cost models together, so a body that matches them
 * under any cost models cannot match other redeemers on chain. Wrong cost models only refuse. Shared by every page
 * verifier (single and keyed calls, compositions, purposes, registrations, intents), with one redeemer per purpose.
 */
export function integrityChecks(tx, params, problems) {
  // One redeemer per purpose (tag and index), as the ledger keys them: a verifier that looks for one with `some` could
  // otherwise read another than the ledger runs.
  const purposes = new Set();
  for (const r of tx.redeemers) {
    const key = `${r.tag}#${r.index}`;
    if (purposes.has(key)) problems.push(`two redeemers for the ${r.tag} purpose at index ${r.index}`);
    purposes.add(key);
  }
  const { parts, redeemerCount, datumCount, languages } = tx.integrity;
  if (redeemerCount === 0 && datumCount === 0) {
    if (tx.scriptDataHash) problems.push('a script integrity hash without redeemers or datums');
    return;
  }
  // The languages of the scripts that run: none without redeemers (datums alone, an empty view map), else PlutusV3
  // (every JOUST script's) and the witness set's own Plutus scripts'.
  const langs = redeemerCount === 0 ? [] : [...new Set(['PlutusV3', ...languages])];
  const costModels = {};
  for (const l of langs) {
    const m = costModelOf(params, l);
    if (!m) {
      problems.push(`the chain gives no ${l} cost model, so the script integrity hash cannot be checked`);
      return;
    }
    costModels[l] = m;
  }
  let want;
  try {
    want = integrityHashOf(parts, langs, costModels);
  } catch (e) {
    problems.push(`the script integrity hash cannot be computed: ${e.message}`);
    return;
  }
  if (want.unsupported) problems.push(`the script integrity hash cannot be checked (${want.unsupported})`);
  else if (tx.scriptDataHash !== want.hash) problems.push('the script integrity hash does not commit to the redeemers and datums the page verified');
}

/**
 * The checks every single call shares (ADR-0013 D2): the fee, the collateral, the validity interval, the signers and
 * the script integrity hash. Shared by the keyed calls' verifier (ADR-0016 D11).
 */
export function envelopeChecks({ tx, txHex, call, wallet, chain, deployment, limits, isWallet }, problems) {
  integrityChecks(tx, chain.params, problems);
  const rds = tx.redeemers;
  const fee = tx.fee;
  // The fee: at most what the protocol asks for this size and these budgets, with a margin.
  const pp = chain.params;
  let mem = 0n, steps = 0n;
  for (const rd of rds) {
    mem += rd.mem;
    steps += rd.steps;
  }
  if (mem > BigInt(pp.max_tx_ex_mem) || steps > BigInt(pp.max_tx_ex_steps)) problems.push('execution budgets above the protocol limits');
  const size = BigInt(txHex.length / 2 + 200);
  const scriptFee = BigInt(Math.ceil(Number(mem) * Number(pp.price_mem) + Number(steps) * Number(pp.price_step)));
  const cap = (BigInt(pp.min_fee_a) * size + BigInt(pp.min_fee_b) + scriptFee) * 12n / 10n + limits.feeMargin;
  if (fee > cap) problems.push(`a fee of ${fee}, above the ${cap} this transaction needs`);
  if (fee > limits.maxFee) problems.push(`a fee of ${fee}, above the page's limit of ${limits.maxFee}`);

  // Collateral: the wallet's, returned to the wallet, at most maxCollateral at risk.
  const coll = tx.collateral;
  const ret = tx.collateralReturn;
  const total = tx.totalCollateral;
  if (rds.length > 0) {
    if (coll.length === 0 || !ret || total === null) problems.push('collateral without a return and a total');
    else {
      let inColl = 0n;
      for (const r of coll) {
        if (!wallet.utxos.has(r)) problems.push('collateral that is not the wallet\'s');
        else inColl += wallet.utxos.get(r).value.get('lovelace') ?? 0n;
      }
      if (!isWallet(ret.address)) problems.push('the collateral return goes elsewhere');
      if (total > limits.maxCollateral || inColl - ret.value.get('lovelace') > limits.maxCollateral) {
        problems.push(`more than ${limits.maxCollateral} lovelace of collateral at risk`);
      }
    }
  }

  // Validity: short, and starting now.
  // The slot "now" is the chain's tip and, when the deployment gives the slot clock, the page's own clock (a lying
  // chain API cannot then stretch the window).
  const nows = [chain.tip];
  if (deployment.slotConfig) {
    const { zeroSlot, zeroTimeMs, slotLengthMs } = deployment.slotConfig;
    nows.push(Number(zeroSlot) + Math.floor((Date.now() - Number(zeroTimeMs)) / Number(slotLengthMs)));
  }
  const ttl = tx.ttl === null ? null : Number(tx.ttl);
  const start = tx.validFrom === null ? null : Number(tx.validFrom);
  for (const now of nows) {
    if (!ttl || ttl > now + limits.maxValiditySlots) problems.push('a validity interval that is too long');
    if (start && start > now + limits.maxStartSlots) problems.push('a validity interval that starts later');
    // A call that reads Ctx sees validFrom as "now": it may not be backdated.
    if (call.meta.ctx && (!start || start < now - limits.maxBackdateSlots)) problems.push('a validity interval that starts in the past (Ctx time)');
  }
  if (ttl && start && ttl - start > limits.maxValiditySlots) problems.push('a validity interval that is too long');

  // Signers: only the wallet's keys, and the co-signers the page named (a @Quorum call's; ADR-0009 D2).
  const named = call.cosignerKeys ?? [];
  const required = new Set();
  for (const k of tx.requiredSigners) {
    required.add(k);
    if (!wallet.keyHashes.includes(k) && !named.includes(k)) problems.push('a required signer that is not the wallet');
  }
  for (const k of named) if (!required.has(k)) problems.push('a co-signer the transaction does not require');
}

/**
 * A method marked as needing the service that the page can still verify (ADR-0015 D7): one that needs it only for
 * @Params (the deployment pins the applied script's policy), @Reads (checked as reference inputs) and MerkleMaps (the
 * map verifier's zero withdrawal, pinned by the deployment).
 */
export function verifiableWithoutService(meta, description, deployment) {
  const r = meta.recipe;
  if (!r) return false;
  const p = meta.policies ?? {};
  const out = meta.returns ?? {};
  // @Quorum is verifiable (the page checks the required signers; the validator counts them), and so are events (the
  // validator compares the redeemer's with the method's).
  if (p.batched || p.into || p.admits || p.receives) return false;
  // @AuthorizedBy: verifiable for credentials the deployment's @Params name: a key is a required signer (checked
  // against the co-signers the page named); a script is the governor whose zero withdrawal and Approvals the page checks.
  for (const at of p.authorizedBy ?? []) {
    const cred = at.startsWith('params.') ? deployment?.params?.[at.slice('params.'.length)] : null;
    if (cred?.$variant !== 'PubKeyCredential' && cred?.$variant !== 'ScriptCredential') return false;
  }
  if (out.deposits) return false;
  // Sends: verifiable when the deployment pins JOUST's output verifier (the outputs are checked on chain; the page
  // still bounds what others receive by what the script releases).
  if (out.sends && !deployment?.outputVerifier) return false;
  const target = description.definitions?.[r.target];
  // MerkleMaps: verifiable when the deployment pins JOUST's map verifier (its claims are checked on chain).
  for (const alt of target?.anyOf ?? []) {
    for (const f of alt.fields ?? []) if (f.$ref?.endsWith('/org.julclang.state.api.MerkleMap') && !deployment?.mapVerifier) return false;
  }
  return true;
}

/**
 * The governor a call names ({@code call.governor: { hash }}), checked against the method's @AuthorizedBy script
 * credential in the deployment's @Params; its reward address derived by the page. Null when the call needs none.
 */
function governorOf(call, deployment, problems) {
  const scripts = (call.meta.policies?.authorizedBy ?? [])
    .map((at) => (at.startsWith('params.') ? deployment.params?.[at.slice('params.'.length)] : null))
    .filter((cred) => cred?.$variant === 'ScriptCredential');
  if (!call.governor) {
    if (scripts.length) problems.push(`${call.meta.name} needs its governor's approval (a script credential)`);
    return null;
  }
  if (scripts.length !== 1) {
    problems.push(`${call.meta.name} has ${scripts.length ? 'several script credentials' : 'no script credential'} for a governor`);
    return null;
  }
  if (!SPEND_KINDS.has(call.kind) || call.kind.startsWith('owned')) {
    problems.push('a governor approves instance actions only');
    return null;
  }
  if (call.governor.hash !== scripts[0].hash) {
    problems.push('the governor is not the script the deployment names');
    return null;
  }
  return { hash: scripts[0].hash, rewardAddress: rewardAddressOf(scripts[0].hash, deployment.networkId) };
}

/**
 * The payment key hash of a base, enterprise or pointer address (bech32), or null. ({@code (CSL, address)}, with a
 * ledger library's address, is still accepted.)
 */
export function paymentKeyHashOf(...args) {
  let a = args.at(-1);
  if (typeof a !== 'string') a = a.to_bech32();
  try {
    const p = addressParts(a);
    return ['base', 'enterprise', 'pointer'].includes(p.kind) && p.payment?.type === 'key' ? p.payment.hash : null;
  } catch {
    return null;
  }
}

/**
 * Whether a token name (hex) is reserved for a contract's own tokens under its policy (ADR-0016): 32 bytes (an
 * instance's), 29 bytes from 0x4F (an owned object's) or 0x49 (an intent's), and in a keyed contract 29 bytes from 0x4B
 * (a keyed object's). Never a user token's.
 */
export function reservedName(nameHex, keyed) {
  const len = nameHex.length / 2;
  const kind = nameHex.slice(0, 2);
  return len === 32 || (len === 29 && (kind === '4f' || kind === '49' || (keyed && kind === '4b')));
}

/** An enterprise address of one of the wallet's payment keys: no stake credential, so wholly the wallet's. */
export function isWalletEnterprise(bech32, wallet) {
  try {
    const p = addressParts(bech32);
    return p.kind === 'enterprise' && p.payment?.type === 'key' && wallet.keyHashes.includes(p.payment.hash);
  } catch {
    return false;
  }
}

export function decodeNext(c, xj, call, datum) {
  if (call.kind === 'action' || call.kind === 'init' || call.kind === 'migrate') return c.decode(xj.state, datum);
  const owned = xj.owned.find((o) => o.type === call.meta.owned);
  if (!owned) throw new Error('unknown owned type ' + call.meta.owned);
  const holding = c.decode(owned.holding, datum);
  if (holding.instance !== call.instance) throw new Error('the object belongs to another instance');
  return holding.value;
}

/**
 * The payment a call takes, as its redeemer binds it (Map unit -> bigint): its lovelace where Ctx states it
 * ({@code ctxBound}: `received` equals the payment), its offered assets where `assetsIn` names them ({@code
 * acceptsAssets}). Shared by verifyPrepared and verifyComposed.
 */
export function boundPayment(pay, ctxBound, acceptsAssets) {
  const paid = new Map([['lovelace', ctxBound ? pay.lovelace : 0n]]);
  if (acceptsAssets) for (const [p, assets] of pay.assets) for (const [n, q] of assets) paid.set(p + n, (paid.get(p + n) ?? 0n) + BigInt(q));
  return paid;
}

/** The instance an owned object's datum (hex) says it is held by, or null when it does not decode as the call's Holding. */
export function heldBy(c, xj, call, datumHex) {
  try {
    const owned = xj.owned.find((o) => o.type === call.meta.owned);
    return owned && datumHex ? c.decode(owned.holding, decodeData(datumHex)).instance : null;
  } catch {
    return null;
  }
}
