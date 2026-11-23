// A JOUST contract in the page (ADR-0013 D2, D3): builds a call from the bundled description, asks a prepare service
// for the transaction, verifies it (verify.js), lets the page review it, has the CIP-30 wallet sign it, adds the
// witnesses to the original bytes and submits through the wallet. The service never sees the signed transaction.

import { codec, toJson } from './data.js';
import { verifyPrepared } from './verify.js';
import { walletView, assemble } from './wallet.js';
import { planOf, planDifference } from './plan.js';
import { verifyIntent, verifyCancel, verifyBatch } from './intents.js';
import { verifyPurpose, purposeOf } from './purposes.js';
import { verifyRegistration, rewardAddressOf } from './governors.js';
import { txHash as hashOf, poolKeyHash } from './ledger.js';
import { decodeData } from './pdata.js';
import { verifyKeyed, isKeyedCall, keyedTypeOf, keyIdOf, listDatum, compareKeys, KEYED_LIMITS } from './keyed.js';
import { defaultRetryStore, scopeOf, pendingOf, newRecord, settle, exclusiveKeyed, inFlight, KeyedCallInProgress, PendingKeyedOperation, revOf } from './retries.js';
import { DEFAULT_LIMITS } from './verify.js';

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
    if (!r.ok) {
      const e = new Error(body.error ?? `${path}: ${r.status}`);
      if (Number.isSafeInteger(body.retryAtSlot) && body.retryAtSlot >= 0) e.retryAtSlot = body.retryAtSlot;
      throw e;
    }
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
/**
 * What a request says about the wallet: its funding address, and the UTxOs the wallet itself reports there (CIP-30
 * getUtxos). A runtime engine builds from exactly those, so it never picks an input the wallet does not know (chain
 * APIs behind a load balancer can lag one another; a real wallet keeps its own index).
 */
export function fromWallet(wallet) {
  const address = fundingAddress(wallet);
  const walletUtxos = [];
  for (const [ref, u] of wallet.utxos) {
    if (u.address !== address) continue;
    const [txHash, index] = ref.split('#');
    walletUtxos.push({ txHash, index: Number(index), address, value: [...u.value].map(([unit, q]) => ({ unit, quantity: String(q) })),
      datumHash: u.datumHash ?? null, inlineDatum: u.inlineDatum ?? null, referenceScriptHash: null });
  }
  return { address, walletUtxos };
}

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

/**
 * A call's governor: {@code call.governor = { governor: { script, hash } (runtime.ensureGovernor), signers: [CIP-30 api] }}.
 * The signers are keys the governor requires; the caller's wallet may be one (it signs anyway).
 */
async function governorOf(CSL, call, wallet, named, networkId) {
  if (!call.meta.policies?.authorizedBy?.length) throw new Error(`${call.meta.name} takes no governor`);
  const g = call.governor.governor ?? call.governor;
  if (!g?.script || !/^[0-9a-f]{56}$/.test(g?.hash ?? '')) throw new Error('a governor is { script, hash } (runtime.ensureGovernor)');
  const apis = [];
  const keys = [];
  const signerKeys = [];
  for (const a of call.governor.signers ?? []) {
    const v = await walletView(CSL, a);
    if (v.networkId !== networkId) throw new Error('a governor signer is on another network');
    const k = v.keyHashes[0];
    if (!k) throw new Error('a governor signer without a payment key');
    if (signerKeys.includes(k)) throw new Error('a governor signer named twice');
    signerKeys.push(k);
    if (wallet.keyHashes.includes(k) || named.includes(k)) continue;   // signs already
    apis.push(a);
    keys.push(k);
  }
  return { script: g.script, hash: g.hash, apis, keys, signerKeys };
}

/** A method that others sign for: @Quorum, or @AuthorizedBy (a key credential: the key's wallet co-signs). */
export const signedByOthers = (meta) => !!meta.policies?.quorum || !!meta.policies?.authorizedBy?.length;

export class JoustContract {
  /**
   * @param o.CSL          (optional, ignored: earlier versions read transactions with it)
   * @param o.description  the contract's description, bundled with the page (never fetched from the service)
   * @param o.deployment   { policy, address, networkId, referenceScripts }, bundled with the page
   * @param o.service      a prepare service (httpService(url))
   * @param o.chain        chain access (blockfrostChain(url))
   * @param o.limits       verifier limits (verify.js DEFAULT_LIMITS)
   * @param o.onReview     async (review) => void: shown before the wallet signs; throw to cancel
   * @param o.portable     removed (ADR-0020 §12.3): a client given one is refused, so a page that asked for the
   *                       in-page recomputation does not silently go without it
   */
  constructor(o) {
    // Fails closed (ADR-0020 §12.3): the option asked for a check that no longer exists.
    if (o.portable != null) {
      throw new Error('@julclang/state-client: the portable option was removed; the page verifier still checks every'
        + ' transaction; remove the option');
    }
    const { portable, ...rest } = o;
    Object.assign(this, { limits: {}, ...rest });
    this.codec = codec(o.description);
    this.x = o.description['x-joust'];
  }

  /** The reward address of the contract's stake credential (a stakeSelf contract's funds are staked to it). */
  get rewardAddress() {
    return rewardAddressOf(this.deployment.policy, this.deployment.networkId);
  }

  /**
   * Registers the contract's stake credential (ADR-0010; once per deployment; anyone may), paid by {@code api}: the
   * transaction is checked to be exactly that registration before the wallet signs. Resolves to its hash, or null when
   * the chain refuses it as registered already.
   */
  async registerStake(api) {
    const { CSL } = this;
    const wallet = await walletView(CSL, api);
    if (wallet.networkId !== this.deployment.networkId) throw new Error('the wallet is on another network');
    if (this.chain.registered && await this.chain.registered(this.rewardAddress) === true) return null;
    const p = await this.service.prepare({ contract: this.description.preamble.title, kind: 'registerStake', ...fromWallet(wallet) });
    const [tip, params] = await Promise.all([this.chain.tip(), this.chain.params()]);
    const { problems } = verifyRegistration({ CSL, txHex: p.cbor, governor: { hash: this.deployment.policy }, wallet,
      chain: { tip, params }, networkId: this.deployment.networkId, limits: this.limits });
    if (problems.length) throw new VerificationError(problems, {});
    const signed = assemble(CSL, p.cbor, await api.signTx(p.cbor, true));
    try {
      await api.submitTx(signed.cbor);
    } catch (e) {
      if (/StakeKeyRegistered|already registered|StakeKeyAlreadyRegistered/i.test(e.message ?? String(e))) return null;
      throw e;
    }
    await this.chain.awaitTx(signed.txHash);
    return signed.txHash;
  }

  /** Delegates the contract's stake to {@code pool} (key hash hex or pool1 bech32): its @Certify handler decides. */
  delegate(api, pool, opts = {}) {
    const hex = poolKeyHash(pool);
    if (!/^[0-9a-f]{56}$/.test(hex)) throw new Error('a pool id: 28 bytes hex, or pool1 bech32');
    return this.runPurpose(api, 'delegate', { pool: hex }, opts);
  }

  /**
   * Withdraws the contract's rewards (the whole balance, read from the chain unless {@code opts.amount} gives it)
   * through its @Withdraw handler, which decides where they go; {@code opts.args} are the handler's arguments.
   */
  async collect(api, opts = {}) {
    const amount = BigInt(opts.amount ?? await this.chain.rewards(this.rewardAddress));
    return this.runPurpose(api, 'collect', { amount, args: opts.args ?? {} }, opts);
  }

  /** Prepares, verifies (verifyPurpose), signs (the wallet and its co-signers), submits and confirms a purpose call. */
  async runPurpose(api, op, what, opts) {
    const { CSL } = this;
    const meta = purposeOf(this.description, op === 'delegate' ? 'certify' : 'withdraw');
    if (!meta) throw new Error(`${this.name} has no ${op === 'delegate' ? '@Certify' : '@Withdraw'} handler`);
    const wallet = await walletView(CSL, api);
    if (wallet.networkId !== this.deployment.networkId) throw new Error('the wallet is on another network');
    const co = await cosignersOf(CSL, opts.cosigners, wallet, this.deployment.networkId);
    const prepared = await this.service.prepare({
      contract: this.description.preamble.title, kind: op, ...fromWallet(wallet),
      ...(op === 'delegate' ? { pool: what.pool } : { amount: String(what.amount), args: toJson(what.args) }),
      ...(co.keys.length ? { cosigners: co.keys } : {}),
    });
    const [tip, params] = await Promise.all([this.chain.tip(), this.chain.params()]);
    const { problems, report } = await verifyPurpose({ CSL, description: this.description, deployment: this.deployment,
      txHex: prepared.cbor, wallet, chain: { tip, params, utxo: (r) => this.chain.utxo(r) }, op, ...what,
      cosignerKeys: co.keys, limits: this.limits });
    report.plan = planOf(CSL, prepared.cbor);
    if (prepared.plan) {
      const d = planDifference(prepared.plan, report.plan);
      if (d) problems.push('the service\'s plan does not describe its transaction: ' + d);
    }
    if (problems.length) throw new VerificationError(problems, report);
    const txHash = hashOf(prepared.cbor);
    if (this.onReview) await this.onReview({ contract: this.name, call: { kind: op, meta, ...what }, report, txHash });
    const witnesses = [await api.signTx(prepared.cbor, true)];
    for (const c of co.apis) witnesses.push(await c.signTx(prepared.cbor, true));
    const signed = assemble(CSL, prepared.cbor, ...witnesses);
    if (signed.txHash !== txHash) throw new Error('the signed transaction has another hash');
    const submitted = await api.submitTx(signed.cbor);
    if (submitted && submitted !== signed.txHash) throw new Error('the wallet submitted another transaction');
    await this.chain.awaitTx(signed.txHash);
    return { txHash: signed.txHash, report };
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
    // A keyed call, or a keyed contract's creation (ADR-0016 D11): its own verifier and the designated-input record.
    if (isKeyedCall(this.description, call)) return this.runKeyed(api, call, call.opts ?? {});
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
    // A script governor (@AuthorizedBy with a script credential, ADR-0009 D3): its applied script, and the wallets of
    // the keys it requires itself (the caller's own counts; others sign the same transaction).
    const gov = call.governor ? await governorOf(CSL, call, wallet, co.keys, this.deployment.networkId) : null;
    call = { ...call, cosignerKeys: [...co.keys, ...(gov?.keys ?? [])], ...(gov ? { governor: { hash: gov.hash } } : {}) };
    const prepared = await this.service.prepare({
      contract: this.description.preamble.title,
      kind: call.kind,
      method: call.meta.name,
      instance: call.instance ?? null,
      object: call.object ?? null,
      args: toJson(call.args ?? {}),
      pay: toJson({ lovelace: call.pay?.lovelace ?? 0n, assets: call.pay?.assets ?? [] }),
      ...fromWallet(wallet),
      ...(co.keys.length ? { cosigners: co.keys } : {}),
      ...(gov ? { governor: { script: gov.script, signers: gov.signerKeys } } : {}),
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
    if (problems.length) throw new VerificationError(problems, report);
    // The hash is the page's own (of the body bytes the wallet signs), never the service's claim.
    const txHash = hashOf(prepared.cbor);
    if (this.onReview) await this.onReview({ contract: this.name, call, report, txHash });
    const witnesses = [await api.signTx(prepared.cbor, true)];
    for (const c of [...co.apis, ...(gov?.apis ?? [])]) witnesses.push(await c.signTx(prepared.cbor, true));
    const signed = assemble(CSL, prepared.cbor, ...witnesses);
    if (signed.txHash !== txHash) throw new Error('the signed transaction has another hash');
    const submitted = await api.submitTx(signed.cbor);
    if (submitted && submitted !== signed.txHash) throw new Error('the wallet submitted another transaction');
    await this.chain.awaitTx(signed.txHash);
    return { txHash: signed.txHash, report };
  }
}

/** Waits until the chain's tip reaches {@code slot} (at most {@code maxMs}). */
async function untilSlot(chain, slot, maxMs = 15 * 60_000) {
  const until = Date.now() + maxMs;
  while (await chain.tip() < slot) {
    if (Date.now() > until) throw new Error(`the chain did not reach slot ${slot}`);
    await new Promise((r) => setTimeout(r, 1000));
  }
}

/**
 * The wallet input a keyed operation designates (ADR-0016 D7, D11): an ordinary (lovelace-only, no datum) UTxO of at
 * least 5 ADA at the address the engine builds from (the funding address), the largest.
 */
export function designatedFor(wallet) {
  const address = fundingAddress(wallet);
  let best = null;
  for (const [ref, u] of wallet.utxos) {
    if (u.address !== address || u.value.size !== 1 || u.datumHash || u.inlineDatum) continue;
    const l = u.value.get('lovelace') ?? 0n;
    if (l < 5_000_000n) continue;
    if (!best || l > best.lovelace) best = { ref, address, lovelace: l };
  }
  return best;
}

/**
 * ADR-0016 D11: a keyed call (insert, keyed action, removal, retire) or a keyed contract's creation. Before the first
 * attempt of an operation the page designates a wallet input and records it (retryStore: IndexedDB in a browser, so it
 * survives a reload); it signs only transactions that spend it, so at most one attempt of the operation can land. A
 * call while the wallet's record is unsettled is a retry of that operation (it must spend the same input), unless the
 * user abandons it ({@code opts.abandon}, or {@link JoustContract#abandonKeyed}). A link window that must start later
 * (a cooldown) is prepared again when the engine says it may be; a window starting ahead is submitted at its start.
 */
JoustContract.prototype.runKeyed = async function runKeyed(api, call, opts = {}) {
  const { CSL } = this;
  for (const a of call.meta.args) if (!(a.name in (call.args ?? {}))) throw new Error(`${call.meta.name}: missing argument ${a.name}`);
  if (call.meta.payable === false && call.pay?.lovelace && BigInt(call.pay.lovelace) !== 0n) throw new Error(`${call.meta.name} is not payable`);
  if (call.governor) throw new Error(`${call.meta.name}: a governor on a keyed call is not supported by the page yet`);
  const args = {};
  for (const a of call.meta.args) args[a.name] = this.codec.decode(a.schema, this.codec.encode(a.schema, call.args[a.name], a.name));
  call = { ...call, args };
  const networkId = this.deployment.networkId;
  const store = this.retryStore ?? (this.retryStore = defaultRetryStore());
  // One set of effective limits for verification and settlement (F91, R-F15).
  const limits = { ...DEFAULT_LIMITS, ...KEYED_LIMITS, ...this.limits };
  // The only keyed call in flight on the network (never queued; F90, F92), reserved here, synchronously, before the
  // first await (the wallet's reads, the co-signers'; F95): the whole lifecycle, in this page and across its tabs.
  return exclusiveKeyed(networkId, store, call.meta.name, async (flight) => {
    const wallet = await walletView(CSL, api);
    if (wallet.networkId !== networkId) throw new Error('the wallet is on another network');
    flight.keys = [...wallet.keyHashes];
    const co = await cosignersOf(CSL, call.cosigners, wallet, networkId);
    if (co.apis.length && !signedByOthers(call.meta)) throw new Error(`${call.meta.name} takes no co-signers`);
    return this.runKeyedLocked(api, call, opts, { co, store, limits, networkId, flight });
  });
};

/** {@link #runKeyed}'s body, holding the network's keyed-operation lock. */
JoustContract.prototype.runKeyedLocked = async function runKeyedLocked(api, call, opts, { co, store, limits, networkId, flight }) {
  const { CSL } = this;
  // The wallet as it is now, under the lock (R-F7): a view from before a wait could be stale.
  const wallet = await walletView(CSL, api);
  if (wallet.networkId !== networkId) throw new Error('the wallet is on another network');
  // The operation's record (the page's form of D7, retries.js): an unsettled one makes this call a retry of it.
  flight.keys = [...wallet.keyHashes];
  let rec = await pendingOf(store, wallet, networkId);
  if (rec && opts.abandon === true) {
    await store.delete(rec.id, revOf(rec));   // the user's explicit choice: a new operation (an earlier attempt may still land)
    rec = null;
  }
  const mayHaveLanded = (r) => `the previous keyed operation of this wallet (${r.what?.method ?? 'a keyed call'}) has ended:`
    + ` every attempt's window has passed and its designated input ${r.designated} is gone, so one of its attempts`
    + ` (${r.attempts.map((a) => a.txHash).join(', ')}) may have landed; check, then acknowledge it (abandonKeyed, or`
    + ' opts.abandon) before a new keyed call';
  // An ended operation that may have landed stays as a tombstone until it is acknowledged (F90): an application that
  // retries blindly on any error cannot start a new operation behind the user's back.
  if (rec?.ended) throw new Error(mayHaveLanded(rec));
  if (rec) {
    const s = await settle(rec, this.chain, { clock: this.deployment.slotConfig, wallet, maxTipSkewSlots: limits.maxTipSkewSlots });
    if (s.settled) {
      const what = `the previous keyed operation of this wallet (${rec.what?.method ?? 'a keyed call'})`;
      // A call that ends an operation never starts another (R-F7): the application decides on the next call.
      if (s.outcome === 'may have landed') {
        await store.put({ ...rec, rev: revOf(rec) + 1, ended: 'may have landed', endedAt: Date.now() });
        throw new Error(mayHaveLanded(rec));
      }
      await store.delete(rec.id, revOf(rec));
      if (s.outcome === 'failed') {
        throw new Error(`${what} failed: every attempt's window has passed and the wallet and the chain API both still show`
          + ` its designated input ${rec.designated}, so none of its attempts can land; call again to start a new operation`);
      }
      rec = null;   // nothing was handed to the wallet: nothing of it can land
    }
  }
  if (!rec) {
    const d = designatedFor(wallet);
    if (!d) throw new Error('the wallet has no ordinary (ADA-only) UTxO of at least 5 ADA to designate for a keyed call (ADR-0016 D7)');
    rec = newRecord(wallet, networkId, d, { contract: this.name, method: call.meta.name, instance: call.instance ?? null, key: call.key ?? null });
    await store.put(rec);
  }
  flight.record = rec.id;
  // A record no attempt was handed out for may go: nothing of it can land.
  const forget = async () => {
    if (!rec.attempts.length) await store.delete(rec.id, revOf(rec));
  };

  let prepared;
  let report;
  try {
    for (let i = 0; ; i++) {
      try {
        prepared = await this.service.prepare({
          contract: this.description.preamble.title, kind: call.kind, method: call.meta.name,
          instance: call.instance ?? null, object: call.key ?? null, args: toJson(call.args ?? {}),
          pay: toJson({ lovelace: call.pay?.lovelace ?? 0n, assets: [] }), ...fromWallet(wallet),
          designated: rec.designated, pageLimits: [limits.maxStartSlots, limits.maxValiditySlots],
          ...(co.keys.length ? { cosigners: co.keys } : {}),
        });
        break;
      } catch (e) {
        // A link window that starts beyond the page's start limit (a cooldown; ADR-0016 D7 step 5): wait, prepare again.
        // The slot is the service's structured field: never read from the message, whose text a contract can forge
        // (ADR-0020 §12.1).
        const slot = e?.retryAtSlot;
        if (!Number.isSafeInteger(slot) || slot < 0 || i >= 16) throw e;
        await untilSlot(this.chain, slot);
      }
    }
    const [tip, params] = await Promise.all([this.chain.tip(), this.chain.params()]);
    const v = await verifyKeyed({
      description: this.description, deployment: this.deployment, txHex: prepared.cbor,
      call: { ...call, designated: rec.designated, cosignerKeys: co.keys }, wallet, limits: this.limits,
      chain: { tip, params, utxo: (r) => this.chain.utxo(r), verifiedOutput: this.chain.verifiedOutput ? (r) => this.chain.verifiedOutput(r) : undefined },
    });
    report = v.report;
    report.plan = planOf(CSL, prepared.cbor);
    if (prepared.plan) {
      const d = planDifference(prepared.plan, report.plan);
      if (d) v.problems.push('the service\'s plan does not describe its transaction: ' + d);
    } else if (this.requirePlan !== false) {
      v.problems.push('the service sent no plan (a JOUST service always does; pass requirePlan: false for another backend)');
    }
    if (v.problems.length) throw new VerificationError(v.problems, report);
  } catch (e) {
    await forget();
    throw e;
  }
  const txHash = hashOf(prepared.cbor);
  if (this.onReview) {
    // In review: an abandonKeyed of this wallet from the review is recorded here, and honoured whether the callback
    // then returns or throws: the record is deleted under its revision, signed attempts included (F93, R-F13).
    flight.inReview = true;
    let threw = false;
    let failure;
    try {
      await this.onReview({ contract: this.name, call, report, txHash });
    } catch (e) {
      threw = true;   // whatever was thrown, a falsy value included (F96)
      failure = e;
    } finally {
      flight.inReview = false;
    }
    if (flight.abandon) {
      await store.delete(rec.id, revOf(rec));
      throw new Error(`${call.meta.name}: abandoned during review` + (rec.attempts.length
        ? `; an earlier signed attempt (${rec.attempts.map((a) => a.txHash).join(', ')}) may still land` : ''));
    }
    if (threw) {
      await forget();
      throw failure;
    }
  }
  // Recorded before the wallet is asked to sign, and never removed on a signing error (F82): an error does not prove
  // that no signature escaped, and keeping the attempt only delays the record's expiry (retries spend the same input).
  rec = { ...rec, rev: revOf(rec) + 1, attempts: [...rec.attempts, { txHash, validFrom: report.plan.validFrom ?? null, validTo: report.plan.validTo ?? null }] };
  await store.put(rec);
  let signed;
  try {
    const witnesses = [await api.signTx(prepared.cbor, true)];
    for (const c of co.apis) witnesses.push(await c.signTx(prepared.cbor, true));
    signed = assemble(CSL, prepared.cbor, ...witnesses);
    if (signed.txHash !== txHash) throw new Error('the signed transaction has another hash');
  } catch (e) {
    throw new PendingKeyedOperation(rec, e);
  }
  try {
    // A link window may start a little ahead (at most the page's start limit): submitted once it has started.
    if (report.plan.validFrom) await untilSlot(this.chain, Number(report.plan.validFrom));
    const submitted = await api.submitTx(signed.cbor);
    if (submitted && submitted !== signed.txHash) throw new Error('the wallet submitted another transaction');
    await this.chain.awaitTx(signed.txHash);
  } catch (e) {
    throw new PendingKeyedOperation(rec, e);   // the record stays: a retry must spend the same input
  }
  // Confirmed: the operation happened; a later call is a new operation the application asked for (ADR M3 notes).
  await store.delete(rec.id, revOf(rec));
  return { txHash: signed.txHash, report, ...(call.kind === 'keyedInit' ? { key: report.created } : {}) };
};

/**
 * Abandons the wallet's unsettled keyed operation (the user's explicit choice; an earlier attempt may still land), or
 * acknowledges one that has ended and may have landed (its tombstone). Returns the record it removed, or null.
 */
JoustContract.prototype.abandonKeyed = function abandonKeyed(api) {
  return this.endKeyed(api, { acknowledgeOnly: false });
};

/**
 * Removes the wallet's record: any (abandon), or only a tombstone (acknowledge; R-F16). The slot is checked
 * synchronously at entry, before the wallet's reads (F95): during the review of this wallet's own call, through a
 * store over the same storage (scope) holding its record (F97, F98), an abandon is recorded for that call to honour; any other call in flight refuses this.
 */
JoustContract.prototype.endKeyed = function endKeyed(api, { acknowledgeOnly }) {
  const networkId = this.deployment.networkId;
  const store = this.retryStore ?? (this.retryStore = defaultRetryStore());
  const what = acknowledgeOnly ? 'acknowledgeKeyed' : 'abandonKeyed';
  const flight = inFlight.get(networkId);
  if (flight) {
    return (async () => {
      const busy = () => new KeyedCallInProgress(`${what}: another keyed call is in progress on this page; call again when it has finished`);
      if (acknowledgeOnly || !flight.inReview) throw busy();
      const wallet = await walletView(this.CSL, api);
      // Still the same call, in review, of this wallet, with its record in this client's storage: the same scope (a
      // seeded copy of the records is not; F98) and the same record (F95, F97).
      if (inFlight.get(networkId) !== flight || !flight.inReview || !flight.keys.some((k) => wallet.keyHashes.includes(k))) throw busy();
      if (scopeOf(store) !== flight.scope) throw busy();
      const rec = (await store.all()).find((r) => r.id === flight.record) ?? null;
      if (!rec || inFlight.get(networkId) !== flight || !flight.inReview) throw busy();
      flight.abandon = true;
      return rec;
    })();
  }
  return exclusiveKeyed(networkId, store, what, async (own) => {
    const wallet = await walletView(this.CSL, api);
    own.keys = [...wallet.keyHashes];
    const rec = await pendingOf(store, wallet, networkId);
    if (!rec) return null;
    if (acknowledgeOnly && !rec.ended) {
      throw new Error('acknowledgeKeyed: the wallet\'s keyed operation is still pending (it may still land); retry it, or'
        + ' abandonKeyed to drop it explicitly');
    }
    await store.delete(rec.id, revOf(rec));
    return rec;
  });
};

/**
 * Prepares one intent operation (ADR-0004; ADR-0015 M3), verifies it with {@code verify}, reviews, signs, submits and
 * confirms it. {@code request} is the service request (kind intent, cancelIntent or batch).
 */
JoustContract.prototype.runIntentOp = async function runIntentOp(api, request, verify, call) {
  const { CSL } = this;
  const wallet = await walletView(CSL, api);
  if (wallet.networkId !== this.deployment.networkId) throw new Error('the wallet is on another network');
  const { address, walletUtxos } = fromWallet(wallet);
  const prepared = await this.service.prepare({ contract: this.description.preamble.title, ...request, address, walletUtxos });
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
  const txHash = hashOf(prepared.cbor);
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
    return this.contract.run(api, { kind: this.kindOf(name, ['action', 'destroy']), meta: this.contract.meta(name, ['action', 'destroy']), args, pay, instance: this.id, cosigners: opts.cosigners, governor: opts.governor });
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

  /** The list of keyed type {@code type} (its simple name) in this instance (ADR-0016). */
  keyed(type) {
    return new JoustKeyedList(this, type);
  }
}

/**
 * The list of one keyed type in one instance (ADR-0016 D7): lookup by key, the walk from the head, inserts. Every
 * result the chain API gives is validated (address, token, constructor, instance, the key's binding: I51); "not found"
 * means the API returned nothing, never proven absence.
 */
export class JoustKeyedList {
  constructor(instance, type) {
    this.instance = instance;
    this.contract = instance.contract;
    this.type = keyedTypeOf(this.contract.x, type);
  }

  /** The unit of {@code key}'s token (hex key; "" is the head). */
  unit(key) {
    return this.contract.deployment.policy + keyIdOf(this.instance.id, this.type.typeId, key);
  }

  /** {@code u} validated as a UTxO of this list, decoded; or null. */
  validate(u) {
    const k = this.contract;
    if (u.address !== k.deployment.address || !u.inlineDatum) return null;
    let d;
    try {
      d = listDatum(this.type, decodeData(u.inlineDatum));
    } catch (e) {
      return null;
    }
    if (!d || d.instance !== this.instance.id) return null;
    const unit = this.unit(d.key);
    const units = [...u.value.keys()].filter((x) => x !== 'lovelace');
    if (units.length !== 1 || units[0] !== unit || u.value.get(unit) !== 1n) return null;
    let value = null;
    if (d.kind === 'node') {
      try {
        value = k.codec.decode({ $ref: '#/definitions/' + this.type.type }, d.value);
      } catch (e) {
        return null;
      }
    }
    return { ref: u.ref, key: d.key, kind: d.kind, retired: d.kind === 'retired', next: d.next, linkedAt: d.linkedAt, value,
      lovelace: u.value.get('lovelace') };
  }

  /** The object of {@code key} (hex): { ref, key, retired, value, lovelace, ... }, or null when the API returned nothing. */
  async find(key) {
    const len = key.length / 2;
    if (!/^([0-9a-f]{2})+$/.test(key) || len > this.type.maxKeyBytes) throw new Error(`${this.type.simple}: a key is 1 to ${this.type.maxKeyBytes} bytes (hex)`);
    const u = await this.contract.chain.findUnit(this.contract.deployment.address, this.unit(key));
    const v = u ? this.validate(u) : null;
    return v && v.key === key && v.kind !== 'head' ? v : null;
  }

  /** The key is taken (live or retired). */
  async exists(key) {
    return (await this.find(key)) !== null;
  }

  /**
   * Every key in list order, walking from the head: [{ key, retired }]. A missing link, a key that does not increase,
   * a key held twice or a node off the walk is an inconsistent snapshot (I47 forbids them on chain).
   */
  async keys() {
    const all = new Map();
    for (const u of await this.contract.chain.utxosAt(this.contract.deployment.address)) {
      const v = this.validate(u);
      if (!v) continue;
      if (all.has(v.key)) throw new Error(`${this.type.simple}: inconsistent snapshot, key ${v.key} held twice`);
      all.set(v.key, v);
    }
    if (!all.has('')) throw new Error(`${this.type.simple}: the list head of this instance is missing`);
    const out = [];
    let at = '';
    while (all.get(at).next !== '') {
      const next = all.get(at).next;
      const n = all.get(next);
      if (!n) throw new Error(`${this.type.simple}: inconsistent snapshot, the walk misses ${next}`);
      if (compareKeys(next, at) <= 0) throw new Error(`${this.type.simple}: inconsistent snapshot, keys do not increase`);
      out.push({ key: n.key, retired: n.retired, value: n.value });
      at = next;
    }
    if (out.length + 1 !== all.size) throw new Error(`${this.type.simple}: inconsistent snapshot, ${all.size - out.length - 1} nodes off the walk`);
    return out;
  }

  /** Inserts with the @OwnedInit {@code method}; returns { txHash, key, report }. */
  insert(api, method, args = {}, pay = {}, opts = {}) {
    const k = this.contract;
    const meta = k.meta(method, ['keyedInit']);
    if (meta.keyed !== this.type.simple) throw new Error(`${method} creates a ${meta.keyed}, not a ${this.type.simple}`);
    return k.run(api, { kind: 'keyedInit', meta, args, pay, instance: this.instance.id, opts });
  }

  /** The object of {@code key} (hex). */
  object(key) {
    return new JoustKeyedObject(this, key);
  }
}

/** One keyed object: its keyed actions, removing exit and retiring exit (ADR-0016 D6, D5, D10). */
export class JoustKeyedObject {
  constructor(list, key) {
    this.list = list;
    this.key = key;
  }

  /** A keyed action, removal or retire. {@code opts.cosigners}: a @Quorum call's co-signers; {@code opts.abandon}. */
  call(api, name, args = {}, pay = {}, opts = {}) {
    const k = this.list.contract;
    const meta = k.meta(name, ['keyedAction', 'keyedRemove', 'keyedRetire']);
    if (meta.keyed !== this.list.type.simple) throw new Error(`${name} is a ${meta.keyed} call, not a ${this.list.type.simple} one`);
    return k.run(api, { kind: meta.kind, meta, args, pay, instance: this.list.instance.id, key: this.key, cosigners: opts.cosigners, opts });
  }

  /** The object, from the chain: { ref, key, retired, value, lovelace }, or null. */
  read() {
    return this.list.find(this.key);
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

/**
 * Acknowledges the wallet's ended keyed operation that may have landed (its tombstone); refuses while it is still
 * pending (R-F16): abandonKeyed is the explicit way to drop a live operation.
 */
JoustContract.prototype.acknowledgeKeyed = function acknowledgeKeyed(api) {
  return this.endKeyed(api, { acknowledgeOnly: true });
};
