// Verifies a prepared transaction before a CIP-30 wallet signs it (ADR-0013 D2), against what the page knows itself:
// the contract's bundled description and deployment (the pinned script), the wallet's own UTxOs, the chain (tip,
// protocol parameters, the UTxOs the transaction reads), and the call the page asked for. The service that prepared
// the transaction is not trusted for any of it.
//
// What the page does not check, the pinned validator does: the next state (or object) is exactly what the contract's
// method computes from the call, payouts are paid, and the script's value is conserved. So the page checks that the
// transaction makes exactly the call it asked for, on the pinned script, and that the wallet risks nothing else: it
// loses at most the payment, the fee and (for a creation) the new UTxO's minimum ADA.

import { codec as makeCodec, dataEqual } from './data.js';
import { instanceIdOf, objectIdOf } from './tokens.js';
import { bytesToHex } from './hex.js';

export const DEFAULT_LIMITS = {
  maxCollateral: 5_000_000n,     // lovelace a failing script may cost
  maxValiditySlots: 900,         // a prepared transaction expires within 15 minutes (one-second slots)
  maxStartSlots: 60,
  maxBackdateSlots: 120,         // a Ctx call's validity starts at most 2 minutes before now
  feeMargin: 500_000n,           // over 1.2 x the protocol's fee for this size and budget (reference scripts)
  maxFee: 3_000_000n,            // an absolute bound: the fee cap above trusts the chain API's protocol parameters (r5)
};

const SPEND_KINDS = new Set(['action', 'destroy', 'ownedAction', 'ownedDestroy']);
const DESTROYS = new Set(['destroy', 'ownedDestroy']);
const CREATIONS = new Set(['init', 'ownedInit']);

/** "txhash#index" of a CSL TransactionInput. */
export const refOf = (input) => `${input.transaction_id().to_hex()}#${input.index()}`;

/** Ledger order of inputs: by transaction id, then index. */
export function ledgerOrder(refs) {
  return [...refs].sort((a, b) => {
    const [ha, ia] = a.split('#');
    const [hb, ib] = b.split('#');
    return ha < hb ? -1 : ha > hb ? 1 : Number(ia) - Number(ib);
  });
}

/** A CSL Value as Map unit -> bigint ('lovelace', or policy hex + asset name hex). */
export function valueMap(v) {
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

const add = (into, m, sign = 1n) => { for (const [k, v] of m) into.set(k, (into.get(k) ?? 0n) + sign * v); };

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
 * @param o.CSL          cardano-serialization-lib (nodejs or browser), 15.x
 * @param o.description  the contract's description (bundled with the page)
 * @param o.deployment   { policy, address (bech32), networkId, referenceScripts?: ["tx#i"] } (bundled with the page)
 * @param o.call         { kind, meta (the x-joust action or creation), args, pay: { lovelace, assets? }, instance, object }
 * @param o.wallet       { addresses: [bech32], keyHashes: [hex], utxos: Map ref -> { address, value: Map } }
 * @param o.chain        { tip (slot), params, utxo: async ref -> { address, value: Map, referenceScriptHash } | null }
 * @returns { problems: string[], report }
 */
export async function verifyPrepared(o) {
  const { CSL, description, deployment, txHex, call, wallet, chain } = o;
  const limits = { ...DEFAULT_LIMITS, ...(o.limits ?? {}) };
  const c = makeCodec(CSL, description);
  const rdefs = redeemerDefs(c, description);
  const xj = description['x-joust'];
  const problems = [];
  const report = { outputs: [], next: null, redeemer: null, created: null };
  const policy = deployment.policy;
  // The wallet's addresses, and the enterprise address of one of its payment keys: no stake credential, so wholly the
  // wallet's (a contract may send there, e.g. to ctx.caller()). A wallet key under a stranger's stake key stays refused.
  const isWallet = (addr) => wallet.addresses.includes(addr.to_bech32())
    || (CSL.EnterpriseAddress.from_address(addr) && wallet.keyHashes.includes(paymentKeyHashOf(CSL, addr)));
  const atScript = (bech32) => bech32 === deployment.address;
  const pay = { lovelace: BigInt(call.pay?.lovelace ?? 0), assets: call.pay?.assets ?? [] };

  let tx;
  try {
    tx = CSL.Transaction.from_hex(txHex);
  } catch (e) {
    return { problems: ['not a transaction'], report };
  }
  const body = tx.body();

  if (call.meta.needsService && !verifiableWithoutService(call.meta, description, deployment)) {
    problems.push(`${call.meta.name} needs data only the service computes; the page cannot verify it`);
  }
  if (!SPEND_KINDS.has(call.kind) && !CREATIONS.has(call.kind)) problems.push('unknown call kind ' + call.kind);

  // Nothing beyond the call.
  if (!tx.is_valid()) problems.push('is_valid is false (the collateral would be taken)');
  if (body.certs() && body.certs().len() > 0) problems.push('certificates');
  if (body.voting_procedures() || body.voting_proposals()) problems.push('votes or proposals');
  if (body.donation() || body.current_treasury_value()) problems.push('a treasury donation');
  // Withdrawals: none, except zero withdrawals from JOUST's verifiers the deployment pins: the map verifier (the
  // call's MerkleMap claims; ADR-0002 D9) and the output verifier (its sends; ADR-0007 D5), checked on chain.
  const pinnedVerifiers = [deployment.mapVerifier, deployment.outputVerifier].filter(Boolean);
  const verifierRewards = new Set(pinnedVerifiers.map((v) => v.rewardAddress));
  const ws = body.withdrawals();
  if (ws && ws.len() > 0) {
    const keys = ws.keys();
    let ok = ws.len() <= verifierRewards.size;
    for (let i = 0; i < keys.len(); i++) {
      if (!verifierRewards.has(keys.get(i).to_address().to_bech32()) || ws.get(keys.get(i)).to_str() !== '0') ok = false;
    }
    if (!ok) problems.push('withdrawals');
  }
  const withdrawalCount = ws ? ws.len() : 0;
  if (tx.auxiliary_data()) problems.push('auxiliary data');
  if (body.network_id() && body.network_id().kind() !== (deployment.networkId === 1 ? CSL.NetworkIdKind.Mainnet : CSL.NetworkIdKind.Testnet)) {
    problems.push('another network');
  }

  // The target: the instance or object the call spends (spending calls).
  const targetName = call.kind.startsWith('owned') && call.kind !== 'ownedInit' ? call.object : call.instance;
  const targetUnit = SPEND_KINDS.has(call.kind) ? policy + targetName : null;

  // Inputs: the wallet's own UTxOs and, for a spending call, the target; nothing else.
  const inputs = body.inputs();
  const refs = [];
  for (let i = 0; i < inputs.len(); i++) refs.push(refOf(inputs.get(i)));
  const sorted = ledgerOrder(refs);
  const spent = new Map();
  const consumed = new Map();     // every input's value: the wallet's and the target's
  let targetIndex = -1;
  let targetRef = null;
  let targetValue = new Map();
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
      add(consumed, u.value);
    } else {
      problems.push(`an input that is neither the wallet's nor the ${call.kind.startsWith('owned') ? 'object' : 'instance'}: ${r}`);
    }
  }
  if (targetUnit && targetIndex < 0) problems.push('the pinned instance or object is not spent');
  report.targetRef = targetRef;

  // Redeemers: exactly the call (and a destroy's burn), on the pinned script.
  const rds = tx.witness_set().redeemers();
  const spends = [];
  const mints = [];
  for (let i = 0; rds && i < rds.len(); i++) {
    const r = rds.get(i);
    const kind = r.tag().kind();
    if (kind === CSL.RedeemerTagKind.Spend) spends.push(r);
    else if (kind === CSL.RedeemerTagKind.Mint) mints.push(r);
    else if (kind === CSL.RedeemerTagKind.Reward && Number(r.index().to_str()) < withdrawalCount) { /* a pinned verifier's */ }
    else problems.push('a redeemer that is neither a spend nor a mint');
  }

  // Mint: none for an action; the target's token burned for a destroy; the new token for a creation.
  const mint = body.mint();
  const minted = new Map();
  if (mint) {
    const ps = mint.keys();
    for (let i = 0; i < ps.len(); i++) {
      const assetsList = mint.get(ps.get(i));
      for (let k = 0; k < assetsList.len(); k++) {
        const assets = assetsList.get(k);
        const names = assets.keys();
        for (let j = 0; j < names.len(); j++) {
          const unit = ps.get(i).to_hex() + bytesToHex(names.get(j).name());
          minted.set(unit, (minted.get(unit) ?? 0n) + BigInt(assets.get(names.get(j)).to_str()));
        }
      }
    }
  }

  let createdUnit = null;
  const callFields = (def, r, what) => {
    let decoded;
    try {
      decoded = c.decode(def, r.data());
    } catch (e) {
      problems.push(`${what}: ${e.message}`);
      return null;
    }
    const alt = c.resolve(def).anyOf.find((a) => a.index === Number(r.data().as_constr_plutus_data()?.alternative().to_str()));
    return { decoded, alt, fields: r.data().as_constr_plutus_data().data(), data: r.data().to_hex() };
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
    alt.fields.forEach((f, i) => {
      const actual = fields.get(i);
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
      if (Number(r.index().to_str()) !== targetIndex) problems.push('the redeemer is not on the target input');
      checkCall(callFields(rdefs.spend, r, 'the call'));
    }
    if (DESTROYS.has(call.kind)) {
      if (minted.size !== 1 || minted.get(targetUnit) !== -1n) problems.push('a destroy must burn exactly its token');
      if (mints.length !== 1) problems.push('a destroy needs exactly one burn redeemer');
      else {
        const got = callFields(rdefs.mint, mints[0], 'the burn');
        if (got && (got.alt.title !== 'BurnInstance' || got.alt.fields.length !== 0)) problems.push('the burn redeemer is not BurnInstance');
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
  if (mints.length && mint && mint.keys().len() !== 1) problems.push('a mint under another policy');
  if (mint && mint.keys().len() === 1 && mint.keys().get(0).to_hex() !== policy) problems.push('a mint under another policy');

  // Outputs: the wallet's (no datum except a payout tag naming the target), exactly one continuation (none for a
  // destroy), and other parties' (paid by the script, which checks them; listed in the report).
  const payoutTag = targetRef ? c.encode({ $ref: '#/definitions/org.julclang.ledger.TxOutRef' },
    { txId: targetRef.split('#')[0], index: targetRef.split('#')[1] }) : null;
  const back = new Map();          // every wallet output
  const tagged = new Map();        // wallet outputs carrying the payout tag (the script's payouts to the wallet)
  const third = new Map();         // outputs to anyone else
  let continuation = new Map();
  const continuingUnit = createdUnit ?? (DESTROYS.has(call.kind) ? null : targetUnit);
  let continuations = 0;
  let createdLovelace = 0n;
  const outs = body.outputs();
  for (let i = 0; i < outs.len(); i++) {
    const out = outs.get(i);
    const value = valueMap(out.amount());
    for (const unit of value.keys()) {
      if (unit.startsWith(policy) && unit !== continuingUnit && unit.length > 56) problems.push('an output carries another token of the contract');
    }
    if (isWallet(out.address())) {
      if (out.has_script_ref() || out.has_data_hash()) problems.push('a wallet output with a script or datum hash');
      const isTagged = out.has_plutus_data() && payoutTag && dataEqual(out.plutus_data(), payoutTag);
      if (out.has_plutus_data() && !isTagged) problems.push('a wallet output with a datum');
      add(back, value);
      if (isTagged) add(tagged, value);
      continue;
    }
    // The wallet's payment key at an address the wallet does not use (e.g. with a stranger's stake key): refused.
    const pkh = paymentKeyHashOf(CSL, out.address());
    if (pkh && wallet.keyHashes.includes(pkh)) problems.push('an output to the wallet\'s key at an address the wallet does not use');
    const bech = out.address().to_bech32();
    if (continuingUnit && atScript(bech) && value.get(continuingUnit) === 1n) {
      continuations++;
      continuation = value;
      createdLovelace = value.get('lovelace');
      const datum = out.plutus_data();
      if (!datum) problems.push('the continuation has no inline datum');
      else {
        report.nextData = datum.to_hex();
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
  const fee0 = BigInt(body.fee().to_str());
  const produced = new Map([['lovelace', fee0]]);
  for (let i = 0; i < outs.len(); i++) add(produced, valueMap(outs.get(i).amount()));
  const supplied = new Map(consumed);
  add(supplied, minted);
  for (const unit of new Set([...supplied.keys(), ...produced.keys()])) {
    if ((supplied.get(unit) ?? 0n) !== (produced.get(unit) ?? 0n)) {
      problems.push(`the transaction does not balance (${unit}: ${supplied.get(unit) ?? 0n} in, ${produced.get(unit) ?? 0n} out)`);
      break;
    }
  }

  // The wallet loses at most the payment, the fee and (a creation) the new UTxO; and no token it does not pay.
  const fee = BigInt(body.fee().to_str());
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
  // Other parties are paid only from what the script releases beyond its payouts to the wallet: a payout to the
  // wallet must never hide the wallet's own funds going elsewhere (review r4, Critical).
  const released = new Map(targetValue);
  add(released, continuation, -1n);
  add(released, minted);
  for (const [unit, q] of third) {
    const free = (released.get(unit) ?? 0n) - (tagged.get(unit) ?? 0n);
    if (q > (free > 0n ? free : 0n)) problems.push(`others receive ${q} of ${unit}, more than the script releases to them`);
  }
  report.fee = fee;
  report.walletNet = net.get('lovelace') ?? 0n;

  // The fee: at most what the protocol asks for this size and these budgets, with a margin.
  const pp = chain.params;
  let mem = 0n, steps = 0n;
  for (let i = 0; rds && i < rds.len(); i++) {
    mem += BigInt(rds.get(i).ex_units().mem().to_str());
    steps += BigInt(rds.get(i).ex_units().steps().to_str());
  }
  if (mem > BigInt(pp.max_tx_ex_mem) || steps > BigInt(pp.max_tx_ex_steps)) problems.push('execution budgets above the protocol limits');
  const size = BigInt(txHex.length / 2 + 200);
  const scriptFee = BigInt(Math.ceil(Number(mem) * Number(pp.price_mem) + Number(steps) * Number(pp.price_step)));
  const cap = (BigInt(pp.min_fee_a) * size + BigInt(pp.min_fee_b) + scriptFee) * 12n / 10n + limits.feeMargin;
  if (fee > cap) problems.push(`a fee of ${fee}, above the ${cap} this transaction needs`);
  if (fee > limits.maxFee) problems.push(`a fee of ${fee}, above the page's limit of ${limits.maxFee}`);

  // Collateral: the wallet's, returned to the wallet, at most maxCollateral at risk.
  const coll = body.collateral();
  const ret = body.collateral_return();
  const total = body.total_collateral();
  if (rds && rds.len() > 0) {
    if (!coll || coll.len() === 0 || !ret || !total) problems.push('collateral without a return and a total');
    else {
      let inColl = 0n;
      for (let i = 0; i < coll.len(); i++) {
        const r = refOf(coll.get(i));
        if (!wallet.utxos.has(r)) problems.push('collateral that is not the wallet\'s');
        else inColl += wallet.utxos.get(r).value.get('lovelace') ?? 0n;
      }
      if (!isWallet(ret.address())) problems.push('the collateral return goes elsewhere');
      if (BigInt(total.to_str()) > limits.maxCollateral || inColl - BigInt(ret.amount().coin().to_str()) > limits.maxCollateral) {
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
  const ttl = body.ttl_bignum();
  const start = body.validity_start_interval_bignum();
  for (const now of nows) {
    if (!ttl || Number(ttl.to_str()) > now + limits.maxValiditySlots) problems.push('a validity interval that is too long');
    if (start && Number(start.to_str()) > now + limits.maxStartSlots) problems.push('a validity interval that starts later');
    // A call that reads Ctx sees validFrom as "now": it may not be backdated.
    if (call.meta.ctx && (!start || Number(start.to_str()) < now - limits.maxBackdateSlots)) problems.push('a validity interval that starts in the past (Ctx time)');
  }
  if (ttl && start && Number(ttl.to_str()) - Number(start.to_str()) > limits.maxValiditySlots) problems.push('a validity interval that is too long');

  // Signers: only the wallet's keys.
  const rs = body.required_signers();
  for (let i = 0; rs && i < rs.len(); i++) {
    if (!wallet.keyHashes.includes(rs.get(i).to_hex())) problems.push('a required signer that is not the wallet');
  }

  // Reference inputs: the pinned script's reference UTxOs, UTxOs of the pinned script (the instance, read), and the
  // foreign instances the call reads (@Reads), as the deployment's @Params name them (ADR-0011 D2).
  const foreign = (call.meta.recipe?.reads ?? []).map((rd) => {
    const fi = deployment.params?.[rd.params];
    return fi ? fi.policy + fi.instance : null;
  }).filter(Boolean);
  const refIns = body.reference_inputs();
  for (let i = 0; refIns && i < refIns.len(); i++) {
    const r = refOf(refIns.get(i));
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
 * A method marked as needing the service that the page can still verify (ADR-0015 D7): one that needs it only for
 * @Params (the deployment pins the applied script's policy), @Reads (checked as reference inputs) and MerkleMaps (the
 * map verifier's zero withdrawal, pinned by the deployment).
 */
function verifiableWithoutService(meta, description, deployment) {
  const r = meta.recipe;
  if (!r) return false;
  const p = meta.policies ?? {};
  const out = meta.returns ?? {};
  if (p.batched || p.into || p.admits || p.receives || p.quorum || p.authorizedBy?.length) return false;
  if (out.mint || out.deposits || out.events) return false;
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

function paymentKeyHashOf(CSL, address) {
  const cred = CSL.BaseAddress.from_address(address)?.payment_cred() ?? CSL.EnterpriseAddress.from_address(address)?.payment_cred()
    ?? CSL.PointerAddress.from_address(address)?.payment_cred();
  return cred?.to_keyhash()?.to_hex() ?? null;
}

function decodeNext(c, xj, call, datum) {
  if (call.kind === 'action' || call.kind === 'init') return c.decode(xj.state, datum);
  const owned = xj.owned.find((o) => o.type === call.meta.owned);
  if (!owned) throw new Error('unknown owned type ' + call.meta.owned);
  const holding = c.decode(owned.holding, datum);
  if (holding.instance !== call.instance) throw new Error('the object belongs to another instance');
  return holding.value;
}
