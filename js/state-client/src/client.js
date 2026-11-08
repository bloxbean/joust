// A JOUST contract in the page (ADR-0013 D2, D3): builds a call from the bundled description, asks a prepare service
// for the transaction, verifies it (verify.js), lets the page review it, has the CIP-30 wallet sign it, adds the
// witnesses to the original bytes and submits through the wallet. The service never sees the signed transaction.

import { codec, toJson, dataEqual } from './data.js';
import { verifyPrepared, redeemerDefs } from './verify.js';
import { walletView, assemble } from './wallet.js';
import { planOf, planDifference } from './plan.js';
import { verifyIntent, verifyCancel, verifyBatch } from './intents.js';

export class VerificationError extends Error {
  constructor(problems, report) {
    super('the prepared transaction was refused: ' + problems.join('; '));
    this.problems = problems;
    this.report = report;
  }
}

/** A prepare service over HTTP (julc-state-server, or a dApp backend running Compose.prepare()). */
export function httpService(baseUrl, { fetchImpl } = {}) {
  const base = baseUrl.replace(/\/$/, '');
  const f = fetchImpl ?? globalThis.fetch.bind(globalThis);
  const call = async (path, init) => {
    const r = await f(base + path, init);
    const text = await r.text();
    let body;
    try {
      body = JSON.parse(text);
    } catch (e) {
      body = { error: text };
    }
    if (!r.ok) throw new Error(body.error ?? `${path}: ${r.status}`);
    return body;
  };
  return {
    prepare: (request) => call('/prepare', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request) }),
    view: (request) => call('/view', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request) }),
  };
}

/**
 * The address the service builds from (one address: JOUST's WalletAccount): the change address when it holds funds,
 * else the wallet address holding the most lovelace. Payouts to the caller go there too.
 */
export function fundingAddress(wallet) {
  const lovelace = new Map();
  for (const u of wallet.utxos.values()) lovelace.set(u.address, (lovelace.get(u.address) ?? 0n) + (u.value.get('lovelace') ?? 0n));
  if ((lovelace.get(wallet.change) ?? 0n) > 0n) return wallet.change;
  let best = wallet.change;
  for (const [a, q] of lovelace) if (wallet.addresses.includes(a) && q > (lovelace.get(best) ?? 0n)) best = a;
  return best;
}

/**
 * The co-signers of a call: CIP-30 APIs on the wallet's network, each named by the payment key of its change address
 * (the key it signs with). The calling wallet is not one of them (it counts anyway).
 */
export async function cosignersOf(CSL, apis = [], wallet, networkId) {
  const keys = [];
  for (const a of apis ?? []) {
    const v = await walletView(CSL, a);
    if (v.networkId !== networkId) throw new Error('a co-signer is on another network');
    const k = v.keyHashes[0];
    if (!k) throw new Error('a co-signer without a payment key');
    if (wallet.keyHashes.includes(k) || keys.includes(k)) throw new Error('a co-signer named twice (the caller counts already)');
    keys.push(k);
  }
  return { apis: apis ?? [], keys };
}

/** A method that others sign for: @Quorum, or @AuthorizedBy (a key credential: the key's wallet co-signs). */
export const signedByOthers = (meta) => !!meta.policies?.quorum || !!meta.policies?.authorizedBy?.length;

export class JoustContract {
  /**
   * @param o.CSL          cardano-serialization-lib 15.x (nodejs or browser)
   * @param o.description  the contract's description, bundled with the page (never fetched from the service)
   * @param o.deployment   { policy, address, networkId, referenceScripts }, bundled with the page
   * @param o.service      a prepare service (httpService(url))
   * @param o.chain        chain access (blockfrostChain(url))
   * @param o.limits       verifier limits (verify.js DEFAULT_LIMITS)
   * @param o.onReview     async (review) => void: shown before the wallet signs; throw to cancel
   */
  constructor(o) {
    Object.assign(this, { limits: {}, ...o });
    this.codec = codec(o.CSL, o.description);
    this.x = o.description['x-joust'];
  }

  get name() {
    return this.x.contract;
  }

  /** The action or creation called {@code name} of {@code kinds}. */
  meta(name, kinds) {
    const all = [...this.x.actions, ...this.x.creations];
    const m = all.find((a) => a.name === name && kinds.includes(a.kind));
    if (!m) throw new Error(`${this.name} has no ${kinds.join('/')} ${name}`);
    return m;
  }

  /** Creates an instance with the @Init method; returns { txHash, instance, report }. */
  async deploy(api, args = {}, pay = {}) {
    const r = await this.run(api, { kind: 'init', meta: this.meta(this.x.creations.find((c) => c.kind === 'init').name, ['init']), args, pay });
    return { ...r, instance: r.report.created };
  }

  /** One instance by id. */
  at(instance) {
    return new JoustInstance(this, instance);
  }

  /** Prepares, verifies, reviews, signs, submits and confirms one call. */
  async run(api, call) {
    const { CSL } = this;
    for (const a of call.meta.args) if (!(a.name in (call.args ?? {}))) throw new Error(`${call.meta.name}: missing argument ${a.name}`);
    if (call.meta.payable === false && call.pay?.lovelace && BigInt(call.pay.lovelace) !== 0n) throw new Error(`${call.meta.name} is not payable`);
    // Arguments in canonical form (maps in key order), as the page will compare them in the redeemer.
    const args = {};
    for (const a of call.meta.args) args[a.name] = this.codec.decode(a.schema, this.codec.encode(a.schema, call.args[a.name], a.name));
    call = { ...call, args };
    const wallet = await walletView(CSL, api);
    if (wallet.networkId !== this.deployment.networkId) throw new Error('the wallet is on another network');
    // Co-signers (@Quorum, ADR-0009 D2; @AuthorizedBy keys, D3): other CIP-30 wallets that sign the same transaction.
    const co = await cosignersOf(CSL, call.cosigners, wallet, this.deployment.networkId);
    if (co.apis.length && !signedByOthers(call.meta)) throw new Error(`${call.meta.name} takes no co-signers`);
    call = { ...call, cosignerKeys: co.keys };
    const prepared = await this.service.prepare({
      contract: this.description.preamble.title,
      kind: call.kind,
      method: call.meta.name,
      instance: call.instance ?? null,
      object: call.object ?? null,
      args: toJson(call.args ?? {}),
      pay: toJson({ lovelace: call.pay?.lovelace ?? 0n, assets: call.pay?.assets ?? [] }),
      address: fundingAddress(wallet),
      ...(co.keys.length ? { cosigners: co.keys } : {}),
    });
    const [tip, params] = await Promise.all([this.chain.tip(), this.chain.params()]);
    const { problems, report } = await verifyPrepared({
      CSL, description: this.description, deployment: this.deployment, txHex: prepared.cbor, call,
      wallet, chain: { tip, params, utxo: (r) => this.chain.utxo(r) }, limits: this.limits,
    });
    // The service's plan must describe exactly the transaction it sent (ADR-0013 D4).
    report.plan = planOf(CSL, prepared.cbor);
    if (prepared.plan) {
      const d = planDifference(prepared.plan, report.plan);
      if (d) problems.push('the service\'s plan does not describe its transaction: ' + d);
    } else if (this.requirePlan !== false) {
      problems.push('the service sent no plan (a JOUST service always does; pass requirePlan: false for another backend)');
    }
    if (!problems.length && this.portable) await this.recompute(call, report, problems);
    if (problems.length) throw new VerificationError(problems, report);
    // The hash is the page's own (of the body bytes the wallet signs), never the service's claim.
    const txHash = CSL.FixedTransaction.from_hex(prepared.cbor).transaction_hash().to_hex();
    if (this.onReview) await this.onReview({ contract: this.name, call, report, txHash });
    const witnesses = [await api.signTx(prepared.cbor, true)];
    for (const c of co.apis) witnesses.push(await c.signTx(prepared.cbor, true));
    const signed = assemble(CSL, prepared.cbor, ...witnesses);
    if (signed.txHash !== txHash) throw new Error('the signed transaction has another hash');
    const submitted = await api.submitTx(signed.cbor);
    if (submitted && submitted !== signed.txHash) throw new Error('the wallet submitted another transaction');
    await this.chain.awaitTx(signed.txHash);
    return { txHash: signed.txHash, report };
  }
}

/**
 * EXPERIMENTAL (ADR-0013 D4): recomputes the call's next state in the page with the contract's own Java method, compiled
 * to Wasm (GraalVM Web Image; option `portable`: the image's API, { contracts, step }), and compares it with the
 * transaction's. The validator already enforces it on chain; this catches it before the wallet signs.
 */
JoustContract.prototype.recompute = async function recompute(call, report, problems) {
  const methods = this.portable.contracts?.[this.name] ?? [];
  if (!methods.includes(call.meta.name) || !report.targetRef) return;   // creations, or not portable
  const { CSL } = this;
  const c = this.codec;
  const owned = call.kind.startsWith('owned');
  // The datums the validator sees: the spent target, and (owned calls) the instance the transaction reads. Each is
  // bound to its input by the producing transaction's hash when the chain API serves transaction CBOR (r5).
  const source = { verified: true };
  const datumOf = async (ref) => {
    const v = this.chain.verifiedOutput ? await this.chain.verifiedOutput(ref) : null;
    if (v) return v.inlineDatum;
    source.verified = false;
    return (await this.chain.utxo(ref))?.inlineDatum ?? null;
  };
  const targetDatum = await datumOf(report.targetRef);
  if (!targetDatum) {
    problems.push('the page could not read the spent datum to recompute the call');
    return;
  }
  let shared = '';
  if (owned) {
    const unit = this.deployment.policy + call.instance;
    const reads = [];
    for (const r of report.plan.referenceInputs) {
      const u = await this.chain.utxo(r);
      if (u && u.address === this.deployment.address && u.value.get(unit) === 1n) reads.push(r);
    }
    if (reads.length > 1) {
      problems.push('the transaction reads more than one instance state');
      return;
    }
    if (reads.length === 1) shared = (await datumOf(reads[0])) ?? '';
  }
  // An owned object's datum is its Holding (instance, value), decoded with its own definition: the method works on
  // the value, and the object must be the instance's.
  const ownedType = owned ? this.x.owned.find((o) => o.type === call.meta.owned) : null;
  const valueOf = (hex) => {
    if (!owned) return hex;
    c.decode(ownedType.holding, hex);   // throws on another type's Holding
    const f = CSL.PlutusData.from_hex(hex).as_constr_plutus_data().data();
    if (f.get(0).as_bytes() && Array.from(f.get(0).as_bytes(), (b) => b.toString(16).padStart(2, '0')).join('') !== call.instance) {
      throw new Error('the object belongs to another instance');
    }
    return f.get(1).to_hex();
  };
  const args = CSL.PlutusList.new();
  for (const a of call.meta.args) args.add(c.encode(a.schema, call.args[a.name], a.name));
  // Ctx: the redeemer's own bytes, as the validator sees them.
  let ctx = '';
  if (call.meta.ctx) {
    const callDef = redeemerDefs(c, this.description).spend.anyOf.find((x) => x.index === call.meta.redeemer.index);
    const i = callDef.fields.findIndex((f) => f.title === 'ctx');
    ctx = CSL.PlutusData.from_hex(report.callData).as_constr_plutus_data().data().get(i).to_hex();
  }
  let out;
  try {
    out = this.portable.step(this.name, call.meta.name, valueOf(targetDatum), shared, CSL.PlutusData.new_list(args).to_hex(), ctx);
  } catch (e) {
    problems.push('the page could not recompute the call: ' + (e.message ?? e));
    return;
  }
  if (out.startsWith('require:')) {
    problems.push(`the contract refuses this call (recomputed in the page): ${out.slice(8)}`);
    return;
  }
  if (!out.startsWith('ok:')) {
    problems.push('the page could not recompute the call: ' + out);
    return;
  }
  const r = CSL.PlutusData.from_hex(out.slice(3)).as_constr_plutus_data().data();
  const next = r.get(0).as_constr_plutus_data();
  const expected = next.alternative().to_str() === '0' ? next.data().get(0) : null;
  const actual = report.nextData ? CSL.PlutusData.from_hex(valueOf(report.nextData)) : null;
  if ((expected === null) !== (actual === null) || (expected && !dataEqual(expected, actual))) {
    problems.push('the next state is not what the contract computes (recomputed in the page)');
    return;
  }
  // Payouts: each payee is paid at least what the method owes it, in outputs tagged with the target; no tagged output
  // goes to anyone else.
  const owed = new Map();
  const payouts = r.get(1).as_list();
  for (let i = 0; i < payouts.len(); i++) {
    const p = payouts.get(i).as_constr_plutus_data().data();
    const to = Array.from(p.get(0).as_bytes(), (b) => b.toString(16).padStart(2, '0')).join('');
    owed.set(to, (owed.get(to) ?? 0n) + BigInt(p.get(1).as_integer().to_str()));
  }
  const paid = new Map();
  const tag = c.encode({ $ref: '#/definitions/org.julclang.ledger.TxOutRef' },
    { txId: report.targetRef.split('#')[0], index: report.targetRef.split('#')[1] });
  for (const o of report.plan.outputs) {
    if (!o.datum || !dataEqual(CSL.PlutusData.from_hex(o.datum), tag)) continue;
    const a = CSL.Address.from_bech32(o.address);
    const key = (CSL.BaseAddress.from_address(a)?.payment_cred() ?? CSL.EnterpriseAddress.from_address(a)?.payment_cred())?.to_keyhash()?.to_hex();
    if (!key || !owed.has(key)) {
      problems.push('a payout goes to someone the contract does not pay (recomputed in the page)');
      return;
    }
    paid.set(key, (paid.get(key) ?? 0n) + BigInt(o.value.lovelace ?? '0'));
  }
  for (const [to, q] of owed) {
    if ((paid.get(to) ?? 0n) < q) {
      problems.push(`the contract pays ${q} lovelace to ${to}, the transaction ${paid.get(to) ?? 0n} (recomputed in the page)`);
      return;
    }
  }
  report.recomputed = true;
  report.recomputedFrom = source.verified ? 'chain transactions (hash-checked)' : 'the chain API';
};

/**
 * Prepares one intent operation (ADR-0004; ADR-0015 M3), verifies it with {@code verify}, reviews, signs, submits and
 * confirms it. {@code request} is the service request (kind intent, cancelIntent or batch).
 */
JoustContract.prototype.runIntentOp = async function runIntentOp(api, request, verify, call) {
  const { CSL } = this;
  const wallet = await walletView(CSL, api);
  if (wallet.networkId !== this.deployment.networkId) throw new Error('the wallet is on another network');
  const address = fundingAddress(wallet);
  const prepared = await this.service.prepare({ contract: this.description.preamble.title, ...request, address });
  const [tip, params] = await Promise.all([this.chain.tip(), this.chain.params()]);
  const { problems, report } = await verify({
    CSL, description: this.description, deployment: this.deployment, txHex: prepared.cbor, call: { ...call, address },
    wallet, chain: { tip, params, utxo: (r) => this.chain.utxo(r) }, limits: this.limits,
  });
  report.plan = planOf(CSL, prepared.cbor);
  if (prepared.plan) {
    const d = planDifference(prepared.plan, report.plan);
    if (d) problems.push('the service\'s plan does not describe its transaction: ' + d);
  }
  if (problems.length) throw new VerificationError(problems, report);
  const txHash = CSL.FixedTransaction.from_hex(prepared.cbor).transaction_hash().to_hex();
  if (this.onReview) await this.onReview({ contract: this.name, call: { ...call, kind: request.kind }, report, txHash });
  const signed = assemble(CSL, prepared.cbor, await api.signTx(prepared.cbor, true));
  if (signed.txHash !== txHash) throw new Error('the signed transaction has another hash');
  const submitted = await api.submitTx(signed.cbor);
  if (submitted && submitted !== signed.txHash) throw new Error('the wallet submitted another transaction');
  await this.chain.awaitTx(signed.txHash);
  return { txHash: signed.txHash, report, created: prepared.instance ?? prepared.created ?? null };
};

export class JoustInstance {
  constructor(contract, id) {
    this.contract = contract;
    this.id = id;
  }

  /** The current state, decoded from the chain: { ref, state, lovelace } or null once destroyed. */
  async read() {
    const k = this.contract;
    const u = await k.chain.findUnit(k.deployment.address, k.deployment.policy + this.id);
    if (!u) return null;
    return { ref: u.ref, state: k.codec.decode(k.x.state, u.inlineDatum), lovelace: u.value.get('lovelace') };
  }

  async state() {
    return (await this.read())?.state ?? null;
  }

  /** An action or destroy on this instance. */
  call(api, name, args = {}, pay = {}, opts = {}) {
    return this.contract.run(api, { kind: this.kindOf(name, ['action', 'destroy']), meta: this.contract.meta(name, ['action', 'destroy']), args, pay, instance: this.id, cosigners: opts.cosigners });
  }

  /**
   * Locks an intent to call the @Batched action {@code name} (ADR-0004): {@code pay.lovelace} goes to the contract
   * when a batch applies it before {@code opts.deadline} (ms; default 30 minutes). Returns { txHash, intent, report }.
   */
  async intent(api, name, args = {}, pay = {}, opts = {}) {
    const k = this.contract;
    const meta = (k.x.intents?.calls ?? []).find((i) => i.name === name);
    if (!meta) throw new Error(`${k.name} has no @Batched action ${name}`);
    for (const a of meta.args) if (!(a.name in args)) throw new Error(`${name}: missing argument ${a.name}`);
    const canon = {};
    for (const a of meta.args) canon[a.name] = k.codec.decode(a.schema, k.codec.encode(a.schema, args[a.name], a.name));
    const lovelace = BigInt(pay.lovelace ?? 0);
    if (!meta.payable && lovelace !== 0n) throw new Error(`${name} is not payable`);
    const deadline = BigInt(opts.deadline ?? Date.now() + 30 * 60_000);
    const r = await k.runIntentOp(api, { kind: 'intent', method: name, instance: this.id, args: toJson(canon),
      pay: toJson({ lovelace, assets: [] }), deadline: Number(deadline) }, verifyIntent,
    { meta, args: canon, pay: { lovelace }, instance: this.id, deadline });
    return { ...r, intent: r.report.intent };
  }

  /** Withdraws the wallet's pending intent {@code intentId}: its token burned, everything it holds back. */
  cancelIntent(api, intentId) {
    return this.contract.runIntentOp(api, { kind: 'cancelIntent', instance: this.id, intent: intentId }, verifyCancel,
      { instance: this.id, intent: intentId });
  }

  /** As a batcher: applies up to {@code opts.max} (8) pending intents in one transaction; pays only the fee. */
  async batch(api, opts = {}) {
    const r = await this.contract.runIntentOp(api, { kind: 'batch', instance: this.id, max: opts.max ?? 8 }, verifyBatch,
      { instance: this.id });
    const outcome = r.created ? JSON.parse(r.created) : { applied: r.report.applied, skipped: {} };
    return { ...r, applied: r.report.applied, skipped: outcome.skipped ?? {} };
  }

  /** This instance's pending intents on chain: [{ intent, ref, owner, deadline, received, call, lovelace }]. */
  async pendingIntents() {
    const k = this.contract;
    const xi = k.x.intents;
    if (!xi) return [];
    const out = [];
    for (const u of await k.chain.utxosAt(k.deployment.address)) {
      const unit = [...u.value.keys()].find((x) => x.startsWith(k.deployment.policy + '49') && x.length === 56 + 58);
      if (!unit || !u.inlineDatum) continue;
      try {
        const d = k.codec.decode(xi.datum, u.inlineDatum);
        if (d.instance === this.id) out.push({ intent: unit.slice(56), ref: u.ref, owner: d.owner, deadline: d.deadline, received: d.received, call: d.call, lovelace: u.value.get('lovelace') });
      } catch (e) {
        // not an intent of this contract
      }
    }
    return out;
  }

  /** Creates an owned object with the creation {@code name}; returns { txHash, object, report }. */
  async create(api, name, args = {}, pay = {}) {
    const r = await this.contract.run(api, { kind: 'ownedInit', meta: this.contract.meta(name, ['ownedInit']), args, pay, instance: this.id });
    return { ...r, object: r.report.created };
  }

  /** An owned object of this instance. */
  object(objectId) {
    return new JoustObject(this, objectId);
  }

  /** Every owned object of this instance on chain: [{ id, type, value, lovelace }]. */
  async objects() {
    const k = this.contract;
    const out = [];
    for (const u of await k.chain.utxosAt(k.deployment.address)) {
      for (const unit of u.value.keys()) {
        if (!unit.startsWith(k.deployment.policy) || unit.length !== 56 + 58 || !unit.slice(56).startsWith('4f')) continue;
        for (const o of k.x.owned) {
          try {
            const h = k.codec.decode(o.holding, u.inlineDatum);
            if (h.instance === this.id) out.push({ id: unit.slice(56), type: o.simple, value: h.value, lovelace: u.value.get('lovelace') });
          } catch (e) {
            // another owned type
          }
        }
      }
    }
    return out;
  }

  kindOf(name, kinds) {
    return this.contract.meta(name, kinds).kind;
  }
}

export class JoustObject {
  constructor(instance, id) {
    this.instance = instance;
    this.id = id;
  }

  /** An owned action or destroy on this object. */
  call(api, name, args = {}, pay = {}) {
    const k = this.instance.contract;
    const meta = k.meta(name, ['ownedAction', 'ownedDestroy']);
    return k.run(api, { kind: meta.kind, meta, args, pay, instance: this.instance.id, object: this.id });
  }

  /** The object's value, decoded from the chain, or null once closed. */
  async read() {
    const k = this.instance.contract;
    const u = await k.chain.findUnit(k.deployment.address, k.deployment.policy + this.id);
    if (!u) return null;
    for (const o of k.x.owned) {
      try {
        const h = k.codec.decode(o.holding, u.inlineDatum);
        if (h.instance === this.instance.id) return { ref: u.ref, type: o.simple, value: h.value, lovelace: u.value.get('lovelace') };
      } catch (e) {
        // another owned type
      }
    }
    return null;
  }
}
