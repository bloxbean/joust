// A JOUST contract in the page (ADR-0013 D2, D3): builds a call from the bundled description, asks a prepare service
// for the transaction, verifies it (verify.js), lets the page review it, has the CIP-30 wallet sign it, adds the
// witnesses to the original bytes and submits through the wallet. The service never sees the signed transaction.

import { codec, toJson } from './data.js';
import { verifyPrepared } from './verify.js';
import { walletView, assemble } from './wallet.js';

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
    const prepared = await this.service.prepare({
      contract: this.description.preamble.title,
      kind: call.kind,
      method: call.meta.name,
      instance: call.instance ?? null,
      object: call.object ?? null,
      args: toJson(call.args ?? {}),
      pay: toJson({ lovelace: call.pay?.lovelace ?? 0n, assets: call.pay?.assets ?? [] }),
      address: fundingAddress(wallet),
    });
    const [tip, params] = await Promise.all([this.chain.tip(), this.chain.params()]);
    const { problems, report } = await verifyPrepared({
      CSL, description: this.description, deployment: this.deployment, txHex: prepared.cbor, call,
      wallet, chain: { tip, params, utxo: (r) => this.chain.utxo(r) }, limits: this.limits,
    });
    if (problems.length) throw new VerificationError(problems, report);
    // The hash is the page's own (of the body bytes the wallet signs), never the service's claim.
    const txHash = CSL.FixedTransaction.from_hex(prepared.cbor).transaction_hash().to_hex();
    if (this.onReview) await this.onReview({ contract: this.name, call, report, txHash });
    const witnesses = await api.signTx(prepared.cbor, true);
    const signed = assemble(CSL, prepared.cbor, witnesses);
    if (signed.txHash !== txHash) throw new Error('the signed transaction has another hash');
    const submitted = await api.submitTx(signed.cbor);
    if (submitted && submitted !== signed.txHash) throw new Error('the wallet submitted another transaction');
    await this.chain.awaitTx(signed.txHash);
    return { txHash: signed.txHash, report };
  }
}

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
  call(api, name, args = {}, pay = {}) {
    return this.contract.run(api, { kind: this.kindOf(name, ['action', 'destroy']), meta: this.contract.meta(name, ['action', 'destroy']), args, pay, instance: this.id });
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
