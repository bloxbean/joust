// Several JOUST calls in one transaction (ADR-0011 composition; ADR-0015 D7): the page verifies a composed transaction
// call by call (each call's target input, redeemer, mint and continuation, against that call's own description and
// deployment) and as a whole (inputs, withdrawals, signers, validity, fee, collateral, balance, and what the wallet
// loses and others receive, over every call). Spending calls only (actions, destroys, owned calls).

import { codec as makeCodec, dataEqual } from './data.js';
import { instanceIdOf } from './tokens.js';
import {
  DEFAULT_LIMITS, ledgerOrder, redeemerDefs, add, SPEND_KINDS, DESTROYS, verifiableWithoutService,
  paymentKeyHashOf, isWalletEnterprise, decodeNext, integrityChecks, reservedName, heldBy, boundPayment,
} from './verify.js';
import { parseTx, txHash as hashOf } from './ledger.js';
import { KEYED_KINDS } from './keyed.js';
import { outputRegistry, claimedSends } from './claims.js';

/**
 * @param o.steps  [{ description, deployment, call: { kind, meta, args, pay, instance, object } }] in composition order
 * @param o.wallet, o.chain, o.txHex, o.limits  as for verifyPrepared
 * @returns { problems, report: { calls: [{ redeemer, next, targetRef }], fee, walletNet, outputs } }
 */
export async function verifyComposed(o) {
  const { txHex, steps, wallet, chain } = o;
  const limits = { ...DEFAULT_LIMITS, ...(o.limits ?? {}) };
  const problems = [];
  const report = { calls: [], outputs: [] };
  const isWallet = (addr) => wallet.addresses.includes(addr) || isWalletEnterprise(addr, wallet);

  let tx;
  try {
    tx = parseTx(txHex);
  } catch (e) {
    return { problems: ['not a transaction'], report };
  }
  // The body commits to the redeemers verified below (the calls', the claims, the approvals).
  integrityChecks(tx, chain.params, problems);

  // Each call: its contract, a spending kind, verifiable by the page.
  const calls = steps.map((s) => {
    const c = makeCodec(s.description);
    const xj = s.description['x-joust'];
    const policy = s.deployment.policy;
    const targetName = s.call.kind.startsWith('owned') ? s.call.object : s.call.instance;
    return {
      ...s, c, xj, policy, rdefs: redeemerDefs(c, s.description), targetUnit: policy + targetName,
      creation: s.call.kind === 'migrate',
      pay: { lovelace: BigInt(s.call.pay?.lovelace ?? 0), assets: s.call.pay?.assets ?? [] },
      targetIndex: -1, targetRef: null, targetValue: new Map(), continuation: new Map(), continuations: 0,
    };
  });
  for (const k of calls) {
    if (KEYED_KINDS.has(k.call.kind)) problems.push(`${k.call.meta.name}: a keyed call is verified on its own, never in a composition (ADR-0016 D11)`);
    else if (!SPEND_KINDS.has(k.call.kind) && !k.creation) problems.push(`${k.call.meta.name}: only spending calls and adoptions can be composed here`);
    // An adoption holds exactly what the prior instance held (below): it takes no payment, and one stated for it would
    // only raise what the wallet may lose.
    if (k.creation && (k.pay.lovelace !== 0n || [...k.pay.assets].length)) problems.push(`${k.call.meta.name}: an adoption takes no payment`);
    // A method that is not payable takes no lovelace (as client.js refuses for one call).
    else if (k.call.meta.payable === false && k.pay.lovelace !== 0n) problems.push(`${k.call.meta.name}: it is not payable, but the page pays ${k.pay.lovelace} lovelace`);
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
  if (!tx.isValid) problems.push('is_valid is false (the collateral would be taken)');
  if (tx.certificates.length > 0) problems.push('certificates');
  if (tx.hasVotes || tx.hasProposals) problems.push('votes or proposals');
  if (tx.hasDonation || tx.hasTreasuryValue) problems.push('a treasury donation');
  if (tx.hasAuxiliaryData) problems.push('auxiliary data');
  const verifiers = calls.flatMap((k) => [k.deployment.mapVerifier, k.deployment.outputVerifier]).filter(Boolean);
  const verifierRewards = new Set(verifiers.map((v) => v.rewardAddress));
  if (tx.withdrawals.length > 0) {
    let ok = tx.withdrawals.length <= verifierRewards.size;
    for (const w of tx.withdrawals) if (!verifierRewards.has(w.address) || w.amount !== 0n) ok = false;
    if (!ok) problems.push('withdrawals');
  }
  const withdrawalCount = tx.withdrawals.length;
  const networkId = calls[0]?.deployment.networkId;
  if (calls.some((k) => k.deployment.networkId !== networkId)) problems.push('calls on different networks');
  if (tx.networkId !== null && tx.networkId !== (networkId === 1 ? 1 : 0)) problems.push('another network');

  // Inputs: the wallet's and each call's target; nothing else.
  const sorted = ledgerOrder(tx.inputs);
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
    const k = u && calls.find((x) => !x.creation && x.targetIndex < 0 && u.address === x.deployment.address && u.value.get(x.targetUnit) === 1n);
    if (k) {
      k.targetIndex = i;
      k.targetRef = r;
      k.targetValue = u.value;
      k.targetDatum = u.inlineDatum ?? null;
      add(consumed, u.value);
    } else {
      problems.push(`an input that is neither the wallet's nor a call's target: ${r}`);
    }
  }
  for (const k of calls) if (!k.creation && k.targetIndex < 0) problems.push(`${k.call.meta.name}: its instance or object is not spent`);
  // A consistency check of the page's own calls: an owned destroy and a move leave no continuation under their source
  // instance, so the object's input datum (its Holding, as the chain provider gives it) must name the call's instance.
  for (const k of calls) {
    if (!k.targetRef || !(k.call.kind === 'ownedDestroy' || k.call.meta.policies?.into)) continue;
    if (heldBy(k.c, k.xj, k.call, k.targetDatum) !== k.call.instance) problems.push(`${k.call.meta.name}: the object is held by another instance than the call's`);
  }
  // An adoption (@Migrate, ADR-0011 D4): of the prior instance the page names, which its own @MigrateTo destroy spends
  // here, under the policy the adopting deployment's params name. Its token is named by that prior UTxO.
  for (const k of calls.filter((x) => x.creation)) {
    const from = k.call.meta.recipe?.reads?.[0]?.params;
    const previous = from ? k.deployment.params?.[from] : null;
    const source = calls.find((x) => DESTROYS.has(x.call.kind) && x.call.meta.policies?.migrateTo && x.policy === previous
      && x.call.instance === k.call.instance);
    if (!source || !source.targetRef) {
      problems.push(`${k.call.meta.name}: an adoption without its old instance's migrating destroy`);
      continue;
    }
    const [txId, index] = source.targetRef.split('#');
    k.priorRef = source.targetRef;
    k.priorValue = source.targetValue;
    k.created = instanceIdOf(txId, Number(index));
    k.targetUnit = k.policy + k.created;
  }

  // Redeemers: one spend per call on its target; mints per call (a destroy's burn, a minting method's ByAction);
  // reward redeemers only for the pinned verifiers' withdrawals.
  const rds = tx.redeemers;
  const spends = [];
  const mints = [];
  for (const r of rds) {
    if (r.tag === 'spend') spends.push(r);
    else if (r.tag === 'mint') mints.push(r);
    else if (r.tag === 'reward' && r.index < withdrawalCount) { /* a pinned verifier's */ }
    else problems.push('a redeemer that is neither a spend, a mint nor a pinned verifier\'s');
  }
  const spending = calls.filter((x) => !x.creation).length;
  if (spends.length !== spending) problems.push(`${spends.length} spend redeemers for ${spending} spending calls`);

  // Mint: by policy, in the ledger's policy order (mint redeemers are indexed by it).
  const minted = new Map(tx.mint);
  const mintPolicies = [...new Set([...minted.keys()].map((u) => u.slice(0, 56)))];
  const sortedPolicies = [...mintPolicies].sort();
  for (const p of sortedPolicies) {
    if (!calls.some((k) => k.policy === p)) problems.push(`a mint under a policy no call pins: ${p}`);
  }
  // Mint owners, per policy (ADR-0007 D4). A unit under a called policy whose name is reserved (an instance's, an owned
  // object's, an intent's, a keyed object's: ADR-0016) is a lifecycle token: only a composed destroy's burn of its own
  // target (-1) or an adoption's mint of its own (+1, checked per call below). Any other unit is a user token: its
  // policy's one mint redeemer must be ByAction naming the target of a composed call under that policy whose method
  // mints (the policy checks the rest on chain, as for one call). That call owns the policy's user-token mint (a
  // destroy may: it then burns its token under the same ByAction); siblings under the same policy mint nothing.
  const keyedPolicy = (p) => calls.some((x) => x.policy === p && x.xj.keyed);
  const reserved = (unit) => calls.some((x) => x.policy === unit.slice(0, 56)) && reservedName(unit.slice(56), keyedPolicy(unit.slice(0, 56)));
  const mintOwner = new Map();
  for (const [unit, q] of minted) {
    if (!reserved(unit)) continue;
    const k = calls.find((x) => x.targetUnit === unit);
    if (!k || !((DESTROYS.has(k.call.kind) && q === -1n) || (k.creation && q === 1n))) problems.push(`${k ? `${k.call.meta.name}: ` : ''}a mint of ${unit}, a reserved token name`);
  }
  for (const p of sortedPolicies) {
    const under = calls.filter((x) => x.policy === p && !x.creation);
    if (!under.length || ![...minted.keys()].some((u) => u.startsWith(p) && !reserved(u))) continue;
    const r = mints.filter((x) => sortedPolicies[x.index] === p);
    const k0 = under[0];
    let alt = null;
    let by = null;
    if (r.length === 1 && k0.rdefs.mint) {
      try {
        const d = k0.c.decode(k0.rdefs.mint, r[0].data);
        alt = k0.c.resolve(k0.rdefs.mint).anyOf.find((a) => a.index === r[0].data.constr);
        by = alt?.title === 'ByAction' ? `${d.by?.txId}#${d.by?.index}` : null;
      } catch (e) {
        problems.push(`the mint redeemer under ${p} does not decode: ${e.message}`);
        continue;
      }
    }
    if (!by) {
      problems.push(`a mint under ${p} needs one ByAction redeemer`);
      continue;
    }
    const owner = under.find((x) => x.targetRef === by);
    if (!owner) problems.push(`the ByAction redeemer under ${p} names no composed call's target`);
    else if (!owner.call.meta.returns?.mint) problems.push(`${owner.call.meta.name}: the policy's ByAction redeemer names it, but its method does not mint`);
    else mintOwner.set(p, owner);
  }

  for (const k of calls) {
    const { c, rdefs, call } = k;
    const fields = (def, r, what) => {
      let decoded;
      try {
        decoded = c.decode(def, r.data);
      } catch (e) {
        problems.push(`${call.meta.name}: ${what}: ${e.message}`);
        return null;
      }
      const alt = c.resolve(def).anyOf.find((a) => a.index === r.data.constr);
      return { decoded, alt, fields: r.data.fields };
    };
    if (k.creation) {
      // An adoption: exactly its token minted under its policy, with Adopt naming the prior UTxO as its seed.
      const entry = { method: call.meta.name, created: k.created ?? null, redeemer: null, next: null };
      report.calls.push(entry);
      if (!k.created) continue;
      const ownMinted = [...minted].filter(([unit]) => unit.startsWith(k.policy));
      if (ownMinted.length !== 1 || minted.get(k.targetUnit) !== 1n) problems.push(`${call.meta.name}: the mint is not exactly the adopted instance's token`);
      const ownMints = mints.filter((r) => sortedPolicies[r.index] === k.policy);
      const got = ownMints.length === 1 ? fields(rdefs.mint, ownMints[0], 'the adoption') : null;
      if (!got) problems.push(`${call.meta.name}: not exactly one mint redeemer`);
      else {
        entry.redeemer = got.decoded;
        if (got.alt.title !== call.meta.redeemer.constructor || got.alt.index !== call.meta.redeemer.index) problems.push(`${call.meta.name}: the mint redeemer is ${got.alt.title}, not ${call.meta.redeemer.constructor}`);
        else if (`${got.decoded.seed?.txId}#${got.decoded.seed?.index}` !== k.priorRef) problems.push(`${call.meta.name}: the adoption's seed is not the prior instance`);
        got.alt.fields.forEach((f) => { if (f.title !== 'seed') problems.push(`${call.meta.name}: the redeemer has a field the page cannot check: ${f.title}`); });
      }
      continue;
    }
    const own = spends.filter((r) => r.index === k.targetIndex);
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
            const actual = got.fields[i];
            if (argNames.has(f.title)) {
              if (!dataEqual(c.encode(f, call.args[f.title], f.title), actual)) problems.push(`${call.meta.name}: the redeemer's ${f.title} is not the argument the page gave`);
            } else if (f.title === 'ctx') {
              if (!wallet.keyHashes.includes(got.decoded.ctx.caller)) problems.push(`${call.meta.name}: the caller is not this wallet`);
              if (got.decoded.ctx.received !== k.pay.lovelace) problems.push(`${call.meta.name}: it receives ${got.decoded.ctx.received} lovelace, not the ${k.pay.lovelace} the page pays`);
              else k.paymentBound = true;
            } else if (f.title === 'assetsIn') {
              if (!dataEqual(c.encode(f, k.pay.assets, 'assetsIn'), actual)) problems.push(`${call.meta.name}: it offers other assets than the page pays`);
            } else if (f.title === 'into') {
              // A move (ADR-0012 D3): its redeemer names the destination the page asked for.
              if (got.decoded.into !== call.into) problems.push(`${call.meta.name}: the object moves to another instance than the page asked`);
            } else if (f.title !== 'events') {
              problems.push(`${call.meta.name}: the redeemer has a field the page cannot check: ${f.title}`);
            }
          });
          // An offer of assets binds only through the redeemer's assetsIn (the validator checks the call accepts exactly
          // those): a method without one takes none, and an offer to it would only widen what the wallet may lose.
          k.acceptsAssets = got.alt.fields.some((f) => f.title === 'assetsIn');
          if (!k.acceptsAssets && [...k.pay.assets].length) {
            problems.push(`${call.meta.name}: it takes no assets, but the page offers some`);
          }
        }
      }
    }
    // A destroy burns exactly its token: under BurnInstance, unless it owns its policy's user mint (its ByAction redeemer
    // then covers the burn too; user tokens are checked by policy, above).
    const ownMints = mints.filter((r) => sortedPolicies[r.index] === k.policy);
    if (DESTROYS.has(call.kind)) {
      if (minted.get(k.targetUnit) !== -1n) problems.push(`${call.meta.name}: a destroy must burn exactly its token`);
      const burns = ownMints.map((r) => fields(rdefs.mint, r, 'the burn')).filter(Boolean);
      if (mintOwner.get(k.policy) !== k && !burns.some((b) => b.alt.title === 'BurnInstance')) problems.push(`${call.meta.name}: no BurnInstance redeemer`);
    }
  }

  // Outputs: the wallet's, each call's continuation (one per non-destroy call, its datum decoded by its description),
  // and other parties'.
  const tags = calls.map((k) => (k.targetRef ? k.c.encode({ $ref: '#/definitions/org.julclang.ledger.TxOutRef' },
    { txId: k.targetRef.split('#')[0], index: k.targetRef.split('#')[1] }) : null)).filter(Boolean);
  const back = new Map();
  const tagged = new Map();
  const third = new Map();
  // Each output verifier the calls pin, with its registry and the calls pinning it. The wallet outputs its claims name
  // (each for a target spent by one of those calls; ADR-0007 D5) are the scripts' sends to the wallet, never its change.
  const registries = [];
  for (const k of calls) {
    const v = k.deployment.outputVerifier;
    if (!v?.rewardAddress || registries.some((r) => r.verifier.rewardAddress === v.rewardAddress)) continue;
    const pinning = calls.filter((x) => x.deployment.outputVerifier?.rewardAddress === v.rewardAddress);
    registries.push({ verifier: v, registry: outputRegistry(tx, v, problems), pinning,
      owners: pinning.filter((x) => x.targetRef).map((x) => x.targetRef) });
  }
  const claimed = claimedSends(tx, registries, isWallet, problems);
  for (const [index, out] of tx.outputs.entries()) {
    const value = out.value;
    const bech = out.address;
    const k = calls.find((x) => !DESTROYS.has(x.call.kind) && bech === x.deployment.address && value.get(x.targetUnit) === 1n);
    for (const unit of value.keys()) {
      for (const x of calls) {
        // A thread token (a reserved name) of a called contract moves only as its own continuation; the contracts'
        // user tokens are bounded per unit like any other asset.
        if (unit.startsWith(x.policy) && unit !== k?.targetUnit && reservedName(unit.slice(56), !!x.xj.keyed)) {
          problems.push('an output carries another token of a called contract');
        }
      }
    }
    if (k) {
      k.continuations++;
      k.continuation = value;
      k.continuationIndex = index;
      const datum = out.datum;
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
    if (isWallet(out.address)) {
      if (out.hasScriptRef || out.datumHash) problems.push('a wallet output with a script or datum hash');
      const isTagged = out.datum && tags.some((t) => dataEqual(out.datum, t));
      if (out.datum && !isTagged) problems.push('a wallet output with a datum');
      const pkh = paymentKeyHashOf(out.address);
      add(back, value);
      if (isTagged) add(tagged, value);
      if (pkh === null) problems.push('a wallet output without a payment key');
      continue;
    }
    const pkh = paymentKeyHashOf(out.address);
    if (pkh && wallet.keyHashes.includes(pkh)) problems.push('an output to the wallet\'s key at an address the wallet does not use');
    report.outputs.push({ address: bech, value });
    add(third, value);
  }
  // Deposits (ADR-0011 D3): a receiver credits every deposit claimed on its continuation, whatever its `by`, so each must
  // come from the target of a call that declares deposits (its validator checks its claims exactly: their number, values,
  // memos and outputs) and land on the continuation of a call that receives them, both pinning this verifier. A deposit
  // by a wallet input would move the wallet's own funds to the receiver. Between composed calls deposits cancel out
  // of what the scripts release (one's release, another's intake), so the bound on others below needs no deposit value.
  for (const { registry, pinning } of registries) {
    for (const d of registry?.deposits ?? []) {
      const from = pinning.find((x) => x.targetRef === d.by);
      const into = pinning.find((x) => x.continuationIndex === d.output);
      if (!from || !from.call.meta.returns?.deposits) problems.push(`a deposit claimed for ${d.by}, not a composed call that deposits`);
      if (!into || !into.call.meta.policies?.receives) problems.push(`a deposit into output ${d.output}, not the continuation of a composed call that receives`);
    }
  }
  for (const k of calls) {
    if (!DESTROYS.has(k.call.kind) && k.continuations !== 1) problems.push(`${k.call.meta.name}: not exactly one continuation`);
    // An adoption holds exactly what the prior instance held, its token aside (the funds move; ADR-0011 D4).
    if (k.creation && k.continuation && k.priorValue) {
      const lovelace = k.continuation.get('lovelace') ?? 0n;
      if (lovelace !== (k.priorValue.get('lovelace') ?? 0n)) problems.push(`${k.call.meta.name}: the adopted instance holds ${lovelace} lovelace, not the prior instance's ${k.priorValue.get('lovelace')}`);
    }
  }

  // Balance: inputs and mint equal outputs and fee.
  const fee = tx.fee;
  const produced = new Map([['lovelace', fee]]);
  for (const out of tx.outputs) add(produced, out.value);
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
  // Others receive only what the scripts release beyond their payouts and claimed sends to the wallet, over all calls,
  // plus the payments the calls take as their redeemers bind them (verify.js boundPayment; an adoption takes none): the
  // rule that protects the wallet, as in verify.js. Each validator settles its call exactly and deposits move between
  // composed calls only, so the release plus the payments is what the calls owe in payouts and sends.
  const released = new Map();
  for (const k of calls) {
    add(released, k.targetValue);
    add(released, k.continuation, -1n);
  }
  add(released, minted);
  const kept = new Map();
  for (const k of calls) if (!k.creation) add(kept, boundPayment(k.pay, k.paymentBound, k.acceptsAssets));
  for (const [unit, q] of third) {
    const free = (released.get(unit) ?? 0n) + (kept.get(unit) ?? 0n) - (tagged.get(unit) ?? 0n) - (claimed.value.get(unit) ?? 0n);
    const sends = claimed.indexes.size ? ' beyond their claimed sends to the wallet' : '';
    if (q > (free > 0n ? free : 0n)) problems.push(`others receive ${q} of ${unit}, more than the scripts release to them${sends}`);
  }
  report.fee = fee;
  report.walletNet = net.get('lovelace') ?? 0n;

  // Signers: only the wallet's keys, and the co-signers the page named for @Quorum calls (ADR-0009 D2).
  const named = new Set(calls.flatMap((k) => k.call.cosignerKeys ?? []));
  const required = new Set();
  for (const k of tx.requiredSigners) {
    required.add(k);
    if (!wallet.keyHashes.includes(k) && !named.has(k)) problems.push('a required signer that is not the wallet');
  }
  for (const k of named) if (!required.has(k)) problems.push('a co-signer the transaction does not require');

  // Validity: short, and starting now (as for one call; any call reading Ctx forbids backdating).
  const nows = [chain.tip];
  const sc = calls.find((k) => k.deployment.slotConfig)?.deployment.slotConfig;
  if (sc) nows.push(Number(sc.zeroSlot) + Math.floor((Date.now() - Number(sc.zeroTimeMs)) / Number(sc.slotLengthMs)));
  const ttl = tx.ttl === null ? null : Number(tx.ttl);
  const start = tx.validFrom === null ? null : Number(tx.validFrom);
  const readsCtx = calls.some((k) => k.call.meta.ctx);
  for (const now of nows) {
    if (!ttl || ttl > now + limits.maxValiditySlots) problems.push('a validity interval that is too long');
    if (start && start > now + limits.maxStartSlots) problems.push('a validity interval that starts later');
    if (readsCtx && (!start || start < now - limits.maxBackdateSlots)) problems.push('a validity interval that starts in the past (Ctx time)');
  }

  // The fee: at most what the protocol asks for this size and these budgets, with a margin.
  const pp = chain.params;
  let mem = 0n, stepsUsed = 0n;
  for (const rd of rds) {
    mem += rd.mem;
    stepsUsed += rd.steps;
  }
  if (mem > BigInt(pp.max_tx_ex_mem) || stepsUsed > BigInt(pp.max_tx_ex_steps)) problems.push('execution budgets above the protocol limits');
  const size = BigInt(txHex.length / 2 + 200);
  const scriptFee = BigInt(Math.ceil(Number(mem) * Number(pp.price_mem) + Number(stepsUsed) * Number(pp.price_step)));
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

  // Reference inputs: each call's reference scripts, its pinned verifiers', its contract's UTxOs, its foreign reads.
  for (const r of tx.referenceInputs) {
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
  const { fromWallet, cosignersOf, signedByOthers } = await import('./client.js');
  const prepared = steps.map((s) => {
    const k = s.contract;
    // ADR-0016 D11: a page verifies a keyed call on its own; composed accounting is a follow-up.
    if ([...k.x.actions, ...k.x.creations].some((a) => a.name === s.method && KEYED_KINDS.has(a.kind))) {
      throw new Error(`${s.method} is a keyed call: a page does not compose keyed calls (ADR-0016 D11)`);
    }
    const meta = k.meta(s.method, ['action', 'destroy', 'ownedAction', 'ownedDestroy', 'migrate']);
    if (meta.kind === 'migrate' && (BigInt(s.pay?.lovelace ?? 0) !== 0n || (s.pay?.assets ?? []).length)) {
      throw new Error(`${s.method}: an adoption takes no payment`);
    }
    if (meta.payable === false && BigInt(s.pay?.lovelace ?? 0) !== 0n) throw new Error(`${s.method} is not payable`);
    const args = {};
    for (const a of meta.args) {
      if (!(a.name in (s.args ?? {}))) throw new Error(`${s.method}: missing argument ${a.name}`);
      args[a.name] = k.codec.decode(a.schema, k.codec.encode(a.schema, s.args[a.name], a.name));
    }
    return { k, call: { kind: meta.kind, meta, args, pay: s.pay ?? {}, instance: s.instance, object: s.object, into: s.into }, apis: s.cosigners ?? [] };
  });
  const wallet = await walletView(CSL, api);
  // @Quorum co-signers, per call (CIP-30 APIs); each distinct wallet signs the transaction once.
  const signing = [];
  for (const p of prepared) {
    if (p.apis.length && !signedByOthers(p.call.meta)) throw new Error(`${p.call.meta.name} takes no co-signers`);
    const co = await cosignersOf(CSL, p.apis, wallet, p.k.deployment.networkId);
    p.call.cosignerKeys = co.keys;
    co.apis.forEach((a, i) => { if (!signing.some((x) => x.key === co.keys[i])) signing.push({ key: co.keys[i], api: a }); });
  }
  const request = {
    ...fromWallet(wallet),
    calls: prepared.map(({ k, call }) => ({
      contract: k.description.preamble.title, kind: call.kind, method: call.meta.name, instance: call.instance ?? null,
      object: call.object ?? null, args: toJson(call.args), pay: toJson({ lovelace: call.pay?.lovelace ?? 0n, assets: call.pay?.assets ?? [] }),
      ...(call.into ? { into: call.into } : {}),
      ...(call.cosignerKeys.length ? { cosigners: call.cosignerKeys } : {}),
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
  const txHash = hashOf(p.cbor);
  if (onReview) await onReview({ calls: request.calls, report, txHash });
  const witnesses = [await api.signTx(p.cbor, true)];
  for (const c of signing) witnesses.push(await c.api.signTx(p.cbor, true));
  const signed = assemble(CSL, p.cbor, ...witnesses);
  if (signed.txHash !== txHash) throw new Error('the signed transaction has another hash');
  await api.submitTx(signed.cbor);
  await chain.awaitTx(signed.txHash);
  return { txHash: signed.txHash, report };
}
