// Several JOUST calls in one transaction (ADR-0011 composition; ADR-0015 D7): the page verifies a composed transaction
// call by call (each call's target input, redeemer, mint and continuation, against that call's own description and
// deployment) and as a whole (inputs, withdrawals, signers, validity, fee, collateral, balance, and what the wallet
// loses and others receive, over every call). Spending calls only (actions, destroys, owned calls).

import { codec as makeCodec, dataEqual } from './data.js';
import {
  DEFAULT_LIMITS, refOf, ledgerOrder, valueMap, redeemerDefs, add, SPEND_KINDS, DESTROYS, verifiableWithoutService,
  paymentKeyHashOf, decodeNext,
} from './verify.js';

/**
 * @param o.steps  [{ description, deployment, call: { kind, meta, args, pay, instance, object } }] in composition order
 * @param o.wallet, o.chain, o.txHex, o.CSL, o.limits  as for verifyPrepared
 * @returns { problems, report: { calls: [{ redeemer, next, targetRef }], fee, walletNet, outputs } }
 */
export async function verifyComposed(o) {
  const { CSL, txHex, steps, wallet, chain } = o;
  const limits = { ...DEFAULT_LIMITS, ...(o.limits ?? {}) };
  const problems = [];
  const report = { calls: [], outputs: [] };
  const isWallet = (addr) => wallet.addresses.includes(addr.to_bech32())
    || (CSL.EnterpriseAddress.from_address(addr) && wallet.keyHashes.includes(paymentKeyHashOf(CSL, addr)));

  let tx;
  try {
    tx = CSL.Transaction.from_hex(txHex);
  } catch (e) {
    return { problems: ['not a transaction'], report };
  }
  const body = tx.body();

  // Each call: its contract, a spending kind, verifiable by the page.
  const calls = steps.map((s) => {
    const c = makeCodec(CSL, s.description);
    const xj = s.description['x-joust'];
    const policy = s.deployment.policy;
    const targetName = s.call.kind.startsWith('owned') ? s.call.object : s.call.instance;
    return {
      ...s, c, xj, policy, rdefs: redeemerDefs(c, s.description), targetUnit: policy + targetName,
      pay: { lovelace: BigInt(s.call.pay?.lovelace ?? 0), assets: s.call.pay?.assets ?? [] },
      targetIndex: -1, targetRef: null, targetValue: new Map(), continuation: new Map(), continuations: 0,
    };
  });
  for (const k of calls) {
    if (!SPEND_KINDS.has(k.call.kind)) problems.push(`${k.call.meta.name}: only spending calls can be composed here`);
    // In a composition, deposits and @Receives are verifiable: their claims go through the pinned output verifier
    // (checked on chain), and the receiving call is part of the same transaction.
    const meta = k.call.meta;
    // So are moves and @Admits calls: the move's destination and its consent are checked below (ADR-0012 D3).
    const composedOnly = (meta.returns?.deposits || meta.policies?.receives) && k.deployment.outputVerifier;
    const asComposed = composedOnly || meta.policies?.into || meta.policies?.admits
      ? { ...meta, returns: { ...meta.returns, deposits: composedOnly ? false : meta.returns?.deposits },
          policies: { ...meta.policies, receives: composedOnly ? false : meta.policies?.receives, into: false, admits: null } }
      : meta;
    if (meta.needsService && !verifiableWithoutService(asComposed, k.description, k.deployment)) {
      problems.push(`${k.call.meta.name} needs data only the service computes; the page cannot verify it`);
    }
  }
  // A move needs its destination's consent: an @Admits call on that instance in the same transaction (ADR-0012 D3).
  for (const k of calls) {
    if (!k.call.meta.policies?.into) continue;
    if (!k.call.into) problems.push(`${k.call.meta.name}: a move without its destination`);
    else if (!calls.some((x) => x !== k && x.call.instance === k.call.into && x.call.meta.policies?.admits && x.policy === k.policy)) {
      problems.push(`${k.call.meta.name}: no @Admits call of the destination in the transaction`);
    }
  }
  const targets = new Set(calls.map((k) => k.targetUnit));
  if (targets.size !== calls.length) problems.push('two calls spend the same instance or object');

  // Nothing beyond the calls.
  if (!tx.is_valid()) problems.push('is_valid is false (the collateral would be taken)');
  if (body.certs() && body.certs().len() > 0) problems.push('certificates');
  if (body.voting_procedures() || body.voting_proposals()) problems.push('votes or proposals');
  if (body.donation() || body.current_treasury_value()) problems.push('a treasury donation');
  if (tx.auxiliary_data()) problems.push('auxiliary data');
  const verifiers = calls.flatMap((k) => [k.deployment.mapVerifier, k.deployment.outputVerifier]).filter(Boolean);
  const verifierRewards = new Set(verifiers.map((v) => v.rewardAddress));
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
  const networkId = calls[0]?.deployment.networkId;
  if (calls.some((k) => k.deployment.networkId !== networkId)) problems.push('calls on different networks');
  if (body.network_id() && body.network_id().kind() !== (networkId === 1 ? CSL.NetworkIdKind.Mainnet : CSL.NetworkIdKind.Testnet)) {
    problems.push('another network');
  }

  // Inputs: the wallet's and each call's target; nothing else.
  const inputs = body.inputs();
  const refs = [];
  for (let i = 0; i < inputs.len(); i++) refs.push(refOf(inputs.get(i)));
  const sorted = ledgerOrder(refs);
  const spent = new Map();
  const consumed = new Map();
  for (let i = 0; i < sorted.length; i++) {
    const r = sorted[i];
    if (wallet.utxos.has(r)) {
      add(spent, wallet.utxos.get(r).value);
      add(consumed, wallet.utxos.get(r).value);
      continue;
    }
    const u = await chain.utxo(r);
    const k = u && calls.find((x) => x.targetIndex < 0 && u.address === x.deployment.address && u.value.get(x.targetUnit) === 1n);
    if (k) {
      k.targetIndex = i;
      k.targetRef = r;
      k.targetValue = u.value;
      add(consumed, u.value);
    } else {
      problems.push(`an input that is neither the wallet's nor a call's target: ${r}`);
    }
  }
  for (const k of calls) if (k.targetIndex < 0) problems.push(`${k.call.meta.name}: its instance or object is not spent`);

  // Redeemers: one spend per call on its target; mints per call (a destroy's burn, a minting method's ByAction);
  // reward redeemers only for the pinned verifiers' withdrawals.
  const rds = tx.witness_set().redeemers();
  const spends = [];
  const mints = [];
  for (let i = 0; rds && i < rds.len(); i++) {
    const r = rds.get(i);
    const kind = r.tag().kind();
    if (kind === CSL.RedeemerTagKind.Spend) spends.push(r);
    else if (kind === CSL.RedeemerTagKind.Mint) mints.push(r);
    else if (kind === CSL.RedeemerTagKind.Reward && Number(r.index().to_str()) < withdrawalCount) { /* a pinned verifier's */ }
    else problems.push('a redeemer that is neither a spend, a mint nor a pinned verifier\'s');
  }
  if (spends.length !== calls.length) problems.push(`${spends.length} spend redeemers for ${calls.length} calls`);

  // Mint: by policy, in the ledger's policy order (mint redeemers are indexed by it).
  const minted = new Map();
  const mint = body.mint();
  const mintPolicies = [];
  if (mint) {
    const ps = mint.keys();
    for (let i = 0; i < ps.len(); i++) {
      mintPolicies.push(ps.get(i).to_hex());
      const all = mint.get(ps.get(i));
      for (let k = 0; k < all.len(); k++) {
        const assets = all.get(k);
        const names = assets.keys();
        for (let j = 0; j < names.len(); j++) {
          const unit = ps.get(i).to_hex() + Buffer.from(names.get(j).name()).toString('hex');
          minted.set(unit, (minted.get(unit) ?? 0n) + BigInt(assets.get(names.get(j)).to_str()));
        }
      }
    }
  }
  const sortedPolicies = [...mintPolicies].sort();
  for (const p of sortedPolicies) {
    if (!calls.some((k) => k.policy === p)) problems.push(`a mint under a policy no call pins: ${p}`);
  }

  for (const k of calls) {
    const { c, rdefs, call } = k;
    const fields = (def, r, what) => {
      let decoded;
      try {
        decoded = c.decode(def, r.data());
      } catch (e) {
        problems.push(`${call.meta.name}: ${what}: ${e.message}`);
        return null;
      }
      const alt = c.resolve(def).anyOf.find((a) => a.index === Number(r.data().as_constr_plutus_data()?.alternative().to_str()));
      return { decoded, alt, fields: r.data().as_constr_plutus_data().data() };
    };
    const own = spends.filter((r) => Number(r.index().to_str()) === k.targetIndex);
    const entry = { method: call.meta.name, targetRef: k.targetRef, redeemer: null, next: null };
    report.calls.push(entry);
    if (own.length !== 1) {
      problems.push(`${call.meta.name}: ${own.length} redeemers on its target, expected 1`);
    } else {
      const got = fields(rdefs.spend, own[0], 'the call');
      if (got) {
        entry.redeemer = got.decoded;
        if (got.alt.title !== call.meta.redeemer.constructor || got.alt.index !== call.meta.redeemer.index) {
          problems.push(`${call.meta.name}: the redeemer is ${got.alt.title}, not the call the page asked for`);
        } else {
          const argNames = new Set(call.meta.args.map((a) => a.name));
          got.alt.fields.forEach((f, i) => {
            const actual = got.fields.get(i);
            if (argNames.has(f.title)) {
              if (!dataEqual(c.encode(f, call.args[f.title], f.title), actual)) problems.push(`${call.meta.name}: the redeemer's ${f.title} is not the argument the page gave`);
            } else if (f.title === 'ctx') {
              if (!wallet.keyHashes.includes(got.decoded.ctx.caller)) problems.push(`${call.meta.name}: the caller is not this wallet`);
              if (got.decoded.ctx.received !== k.pay.lovelace) problems.push(`${call.meta.name}: it receives ${got.decoded.ctx.received} lovelace, not the ${k.pay.lovelace} the page pays`);
            } else if (f.title === 'assetsIn') {
              if (!dataEqual(c.encode(f, k.pay.assets, 'assetsIn'), actual)) problems.push(`${call.meta.name}: it offers other assets than the page pays`);
            } else if (f.title === 'into') {
              // A move (ADR-0012 D3): its redeemer names the destination the page asked for.
              if (got.decoded.into !== call.into) problems.push(`${call.meta.name}: the object moves to another instance than the page asked`);
            } else if (f.title !== 'events') {
              problems.push(`${call.meta.name}: the redeemer has a field the page cannot check: ${f.title}`);
            }
          });
        }
      }
    }
    // This call's mint: a destroy burns exactly its token; a minting method mints its policy's own tokens.
    const ownMinted = [...minted].filter(([unit]) => unit.startsWith(k.policy));
    const ownMints = mints.filter((r) => sortedPolicies[Number(r.index().to_str())] === k.policy);
    if (DESTROYS.has(call.kind)) {
      if (minted.get(k.targetUnit) !== -1n) problems.push(`${call.meta.name}: a destroy must burn exactly its token`);
      const burns = ownMints.map((r) => fields(rdefs.mint, r, 'the burn')).filter(Boolean);
      if (!burns.some((b) => b.alt.title === 'BurnInstance')) problems.push(`${call.meta.name}: no BurnInstance redeemer`);
    } else {
      for (const [unit] of ownMinted) {
        const othersBurn = calls.some((x) => x !== k && DESTROYS.has(x.call.kind) && x.targetUnit === unit);
        if (unit === k.targetUnit || (!call.meta.returns?.mint && !othersBurn)) problems.push(`${call.meta.name}: a mint of ${unit}`);
      }
    }
  }

  // Outputs: the wallet's, each call's continuation (one per non-destroy call, its datum decoded by its description),
  // and other parties'.
  const tags = calls.map((k) => (k.targetRef ? k.c.encode({ $ref: '#/definitions/org.julclang.ledger.TxOutRef' },
    { txId: k.targetRef.split('#')[0], index: k.targetRef.split('#')[1] }) : null)).filter(Boolean);
  const back = new Map();
  const tagged = new Map();
  const third = new Map();
  const outs = body.outputs();
  for (let i = 0; i < outs.len(); i++) {
    const out = outs.get(i);
    const value = valueMap(out.amount());
    const bech = out.address().to_bech32();
    const k = calls.find((x) => !DESTROYS.has(x.call.kind) && bech === x.deployment.address && value.get(x.targetUnit) === 1n);
    for (const unit of value.keys()) {
      for (const x of calls) {
        const mintedHere = x.call.meta.returns?.mint && (minted.get(unit) ?? 0n) > 0n;
        if (unit.startsWith(x.policy) && unit.length > 56 && unit !== k?.targetUnit && !mintedHere) {
          problems.push('an output carries another token of a called contract');
        }
      }
    }
    if (k) {
      k.continuations++;
      k.continuation = value;
      const datum = out.plutus_data();
      if (!datum) problems.push(`${k.call.meta.name}: the continuation has no inline datum`);
      else {
        try {
          // A moved object continues under its destination (ADR-0012 D3).
          const asHeld = k.call.meta.policies?.into ? { ...k.call, instance: k.call.into } : k.call;
          report.calls[calls.indexOf(k)].next = decodeNext(k.c, k.xj, asHeld, datum);
        } catch (e) {
          problems.push(`${k.call.meta.name}: the continuing datum does not decode: ${e.message}`);
        }
      }
      continue;
    }
    if (isWallet(out.address())) {
      if (out.has_script_ref() || out.has_data_hash()) problems.push('a wallet output with a script or datum hash');
      const isTagged = out.has_plutus_data() && tags.some((t) => dataEqual(out.plutus_data(), t));
      if (out.has_plutus_data() && !isTagged) problems.push('a wallet output with a datum');
      const pkh = paymentKeyHashOf(CSL, out.address());
      add(back, value);
      if (isTagged) add(tagged, value);
      if (pkh === null) problems.push('a wallet output without a payment key');
      continue;
    }
    const pkh = paymentKeyHashOf(CSL, out.address());
    if (pkh && wallet.keyHashes.includes(pkh)) problems.push('an output to the wallet\'s key at an address the wallet does not use');
    report.outputs.push({ address: bech, value });
    add(third, value);
  }
  for (const k of calls) {
    if (!DESTROYS.has(k.call.kind) && k.continuations !== 1) problems.push(`${k.call.meta.name}: not exactly one continuation`);
  }

  // Balance: inputs and mint equal outputs and fee.
  const fee = BigInt(body.fee().to_str());
  const produced = new Map([['lovelace', fee]]);
  for (let i = 0; i < outs.len(); i++) add(produced, valueMap(outs.get(i).amount()));
  const supplied = new Map(consumed);
  add(supplied, minted);
  for (const unit of new Set([...supplied.keys(), ...produced.keys()])) {
    if ((supplied.get(unit) ?? 0n) !== (produced.get(unit) ?? 0n)) {
      problems.push(`the transaction does not balance (${unit}: ${supplied.get(unit) ?? 0n} in, ${produced.get(unit) ?? 0n} out)`);
      break;
    }
  }

  // The wallet loses at most the calls' payments and the fee, and no token it does not pay.
  const net = new Map(spent);
  add(net, back, -1n);
  const allowed = fee + calls.reduce((s, k) => s + k.pay.lovelace, 0n);
  const offered = new Map();
  for (const k of calls) for (const [p, assets] of k.pay.assets) for (const [n, q] of assets) offered.set(p + n, (offered.get(p + n) ?? 0n) + BigInt(q));
  for (const [unit, q] of net) {
    if (unit === 'lovelace' && q > allowed) problems.push(`the wallet loses ${q} lovelace, more than the ${allowed} it pays (fee included)`);
    if (unit !== 'lovelace' && q > (offered.get(unit) ?? 0n)) problems.push(`the wallet loses ${q} of ${unit}`);
  }
  // Others receive only what the scripts release beyond their payouts to the wallet, over all calls.
  const released = new Map();
  for (const k of calls) {
    add(released, k.targetValue);
    add(released, k.continuation, -1n);
  }
  add(released, minted);
  for (const [unit, q] of third) {
    const free = (released.get(unit) ?? 0n) - (tagged.get(unit) ?? 0n);
    if (q > (free > 0n ? free : 0n)) problems.push(`others receive ${q} of ${unit}, more than the scripts release to them`);
  }
  report.fee = fee;
  report.walletNet = net.get('lovelace') ?? 0n;

  // Signers: only the wallet's keys.
  const rs = body.required_signers();
  for (let i = 0; rs && i < rs.len(); i++) {
    if (!wallet.keyHashes.includes(rs.get(i).to_hex())) problems.push('a required signer that is not the wallet');
  }

  // Validity: short, and starting now (as for one call; any call reading Ctx forbids backdating).
  const nows = [chain.tip];
  const sc = calls.find((k) => k.deployment.slotConfig)?.deployment.slotConfig;
  if (sc) nows.push(Number(sc.zeroSlot) + Math.floor((Date.now() - Number(sc.zeroTimeMs)) / Number(sc.slotLengthMs)));
  const ttl = body.ttl_bignum();
  const start = body.validity_start_interval_bignum();
  const readsCtx = calls.some((k) => k.call.meta.ctx);
  for (const now of nows) {
    if (!ttl || Number(ttl.to_str()) > now + limits.maxValiditySlots) problems.push('a validity interval that is too long');
    if (start && Number(start.to_str()) > now + limits.maxStartSlots) problems.push('a validity interval that starts later');
    if (readsCtx && (!start || Number(start.to_str()) < now - limits.maxBackdateSlots)) problems.push('a validity interval that starts in the past (Ctx time)');
  }

  // The fee: at most what the protocol asks for this size and these budgets, with a margin.
  const pp = chain.params;
  let mem = 0n, stepsUsed = 0n;
  for (let i = 0; rds && i < rds.len(); i++) {
    mem += BigInt(rds.get(i).ex_units().mem().to_str());
    stepsUsed += BigInt(rds.get(i).ex_units().steps().to_str());
  }
  if (mem > BigInt(pp.max_tx_ex_mem) || stepsUsed > BigInt(pp.max_tx_ex_steps)) problems.push('execution budgets above the protocol limits');
  const size = BigInt(txHex.length / 2 + 200);
  const scriptFee = BigInt(Math.ceil(Number(mem) * Number(pp.price_mem) + Number(stepsUsed) * Number(pp.price_step)));
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

  // Reference inputs: each call's reference scripts, its pinned verifiers', its contract's UTxOs, its foreign reads.
  const refIns = body.reference_inputs();
  for (let i = 0; refIns && i < refIns.len(); i++) {
    const r = refOf(refIns.get(i));
    let ok = calls.some((k) => (k.deployment.referenceScripts ?? []).includes(r))
      || verifiers.some((v) => (v.referenceScripts ?? []).includes(r));
    if (!ok) {
      const u = await chain.utxo(r);
      ok = !!u && calls.some((k) => u.referenceScriptHash === k.policy
        || (u.address === k.deployment.address && u.value.get(k.targetUnit) === 1n)
        || (u.address === k.deployment.address && k.call.instance && u.value.get(k.policy + k.call.instance) === 1n)
        || (k.call.meta.recipe?.reads ?? []).some((rd) => {
          const fi = k.deployment.params?.[rd.params];
          return fi && u.value.get(fi.policy + fi.instance) === 1n;
        }));
    }
    if (!ok) problems.push(`a reference input the page does not know: ${r}`);
  }

  return { problems, report };
}

/**
 * Prepares, verifies, has the wallet sign, submits and confirms several calls in one transaction.
 * @param steps [{ contract: JoustContract, method, args, pay, instance, object }] (spending calls)
 * @returns { txHash, report }
 */
export async function runComposed({ CSL, service, chain, api, steps, limits, onReview }) {
  const { walletView, assemble } = await import('./wallet.js');
  const { toJson } = await import('./data.js');
  const { fundingAddress } = await import('./client.js');
  const prepared = steps.map((s) => {
    const k = s.contract;
    const meta = k.meta(s.method, ['action', 'destroy', 'ownedAction', 'ownedDestroy']);
    const args = {};
    for (const a of meta.args) {
      if (!(a.name in (s.args ?? {}))) throw new Error(`${s.method}: missing argument ${a.name}`);
      args[a.name] = k.codec.decode(a.schema, k.codec.encode(a.schema, s.args[a.name], a.name));
    }
    return { k, call: { kind: meta.kind, meta, args, pay: s.pay ?? {}, instance: s.instance, object: s.object, into: s.into } };
  });
  const wallet = await walletView(CSL, api);
  const request = {
    address: fundingAddress(wallet),
    calls: prepared.map(({ k, call }) => ({
      contract: k.description.preamble.title, kind: call.kind, method: call.meta.name, instance: call.instance ?? null,
      object: call.object ?? null, args: toJson(call.args), pay: toJson({ lovelace: call.pay?.lovelace ?? 0n, assets: call.pay?.assets ?? [] }),
      ...(call.into ? { into: call.into } : {}),
    })),
  };
  const p = await service.prepare(request);
  const [tip, params] = await Promise.all([chain.tip(), chain.params()]);
  const { problems, report } = await verifyComposed({
    CSL, txHex: p.cbor, wallet, chain: { tip, params, utxo: (r) => chain.utxo(r) }, limits,
    steps: prepared.map(({ k, call }) => ({ description: k.description, deployment: k.deployment, call })),
  });
  if (problems.length) {
    const e = new Error('the prepared transaction was refused: ' + problems.join('; '));
    e.problems = problems;
    e.report = report;
    throw e;
  }
  const txHash = CSL.FixedTransaction.from_hex(p.cbor).transaction_hash().to_hex();
  if (onReview) await onReview({ calls: request.calls, report, txHash });
  const signed = assemble(CSL, p.cbor, await api.signTx(p.cbor, true));
  if (signed.txHash !== txHash) throw new Error('the signed transaction has another hash');
  await api.submitTx(signed.cbor);
  await chain.awaitTx(signed.txHash);
  return { txHash: signed.txHash, report };
}
