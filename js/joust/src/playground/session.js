// A playground session (ADR-0024 D2): one mode, one chain, one build and one dev-host process.
//
// Offline (D4): the chain is a memoryLedger. The session answers the host's chain/read from it, supplies `now` (the
// ledger's slot) and the ledger's clock, submits to the ledger, and reports each applied transaction to the host as
// confirmed at its inclusion slot (settlementSlots 0: the ledger has no rollbacks, so it is final at once).
//
// The session never builds a transaction (D1) and never holds a key (D5): the page signs.

import { createHash } from 'node:crypto';
import { memoryLedger, LedgerRefusal } from '../test/offline.js';
import { addressParts, ledgerTx, rewardAddress } from '@julclang/state-client/ledger';
import { HostError, launchNpmHost, npmFingerprint } from './host.js';

export const OFFLINE_NETWORK = { networkId: 0, protocolMagic: 42, mainnet: false };
const TIME = { maxTtlSlots: 900, settlementSlots: 0, maxNowAheadSlots: 900 };
const LIMITS = { callDeadlineMs: 60_000, readsPerCall: 256, readBytesPerCall: 32 * 1024 * 1024 };
/** The validity window the playground asks for (slots). */
export const TTL_SLOTS = 120;
/** At most this much from nowhere per funding request (offline identities, D5): 1,000,000 ADA. */
export const MAX_FUND = 1_000_000_000_000n;

/** A failure the page shows at its stage (D7): host, ledger, session. */
export class StageError extends Error {
  constructor(stage, code, message, extra = {}) {
    super(message);
    this.stage = stage;
    this.code = code;
    Object.assign(this, extra);
  }
  toJSON() {
    const { stage, code, message, ...rest } = this;
    return { stage, code, message, ...rest };
  }
}

const sha = (s) => createHash('sha256').update(s).digest('hex');
const snapshotOutput = (u) => ({ tx_hash: u.txHash, output_index: u.index, address: u.address,
  amount: u.value.map((a) => ({ unit: a.unit, quantity: String(a.quantity) })),
  data_hash: u.datumHash ?? null, inline_datum: u.inlineDatum ?? null, reference_script_hash: u.referenceScriptHash ?? null });

/**
 * The host's reverse reads answered from a memoryLedger (D4), in the protocol's encoding
 * (docs/protocol/dev-host-1.md, "Reverse reads"). Map-history reads (redeemers, datum) are unsupported offline: only a
 * map call that needs a history rebuild is refused (unsupported/read:<kind>).
 */
export function ledgerReads(ledger, { networkId = 0 } = {}) {
  const tip = () => ({ slot: ledger.slot, hash: sha('slot' + ledger.slot), height: ledger.slot });
  return async function read(q) {
    const t = tip();
    try {
      return await answer(q, t);
    } catch (e) {
      // Every result carries a tip, a failed read's too (else the host takes it as inconsistentRead).
      if (e && typeof e === 'object' && !e.tip) e.tip = t;
      throw e;
    }
  };
  async function answer(q, t) {
    switch (q.kind) {
      case 'tip':
        return { tip: t, value: t };
      case 'protocolParams':
        return { tip: t, value: await ledger.protocolParams() };
      case 'utxos': {
        if (q.cursor !== undefined) return { tip: t, absent: true };
        const list = await ledger.utxos(q.address);
        return list.length ? { tip: t, value: list.map(snapshotOutput) } : { tip: t, absent: true };
      }
      case 'output': {
        const u = await ledger.output(q.ref);
        return u ? { tip: t, value: snapshotOutput(u) } : { tip: t, absent: true };
      }
      case 'script': {
        const s = typeof ledger.script === 'function' ? await ledger.script(q.hash) : null;
        return s ? { tip: t, value: s } : { tip: t, absent: true };
      }
      case 'stakeRegistration': {
        // The credential is a 28-byte hash of either kind: registered when either reward address is.
        let registered = false;
        for (const type of ['script', 'key']) {
          if ((await ledger.account(rewardAddress({ type, hash: q.credential }, networkId))).registered) registered = true;
        }
        return { tip: t, value: { registered } };
      }
      case 'assetHistory': {
        if (q.cursor !== undefined) return { tip: t, absent: true };
        const h = await ledger.assetHistory(q.unit);
        return h.length ? { tip: t, value: h.map((txHash) => ({ txHash })) } : { tip: t, absent: true };
      }
      default:
        return { tip: t, unsupported: true };
    }
  }
}

/**
 * Starts an offline session for the npm project at {@code root} (its build must exist: build/joust/contracts.json).
 *
 * @param o.engine   the runtime engine (loadEngine()), for the ledger's script evaluation
 * @param o.ledger   keep this ledger (a new session on a rebuilt project, D2); default a new one
 * @param o.attacks  run the attack suite on every script transaction (default true, Q7)
 */
export async function offlineSession({ id, root, engine, ledger, attacks = true, launch = launchNpmHost, onEnd = () => {} }) {
  const fp = npmFingerprint(root);
  const entries = JSON.parse(fp.contracts.toString('utf8'));
  const chain = ledger ?? memoryLedger({ engine, attacks });
  const read = ledgerReads(chain);
  const host = launch({ root: fp.root, onExit: (reason) => onEnd(reason) });
  const reservations = new Map();   // reservation id -> { txHash, validTo, kind }
  const submitted = [];             // { txHash, slot, attacks }
  let init;
  try {
    init = await host.call('initialize', { protocol: [1], network: OFFLINE_NETWORK, clock: chain.clock, time: TIME,
      limits: LIMITS, build: { snapshot: fp.root, digest: fp.digest }, restoring: false });
  } catch (e) {
    await host.close().catch(() => {});
    throw e;
  }
  if (init.fingerprint !== fp.digest) {
    await host.close();
    throw new StageError('session', 'fingerprint', 'the dev host loaded another build than the one the playground hashed: start again');
  }
  const describes = new Map();

  const live = () => {
    if (!host.alive) throw new StageError('session', 'hostEnded', `the dev host ended (${host.ended}): start a new session`);
  };
  /** Whether the project's build changed since this session started (I6). */
  const stale = () => {
    try {
      return npmFingerprint(fp.root).digest !== fp.digest;
    } catch {
      return true;
    }
  };
  const forward = async (method, params) => {
    live();
    try {
      return await host.call(method, params, { read });
    } catch (e) {
      if (e instanceof HostError) throw new StageError('host', e.code, e.message, { kind: e.kind, sites: e.sites, retryAtSlot: e.retryAtSlot });
      throw e;
    }
  };
  const remember = (prepared, kind) => {
    if (prepared?.reservation) reservations.set(prepared.reservation, { txHash: prepared.txHash, validTo: prepared.validTo, kind });
  };

  return {
    id,
    mode: 'offline',
    root: fp.root,
    fingerprint: fp.digest,
    entries,
    ledger: chain,
    host,
    init,
    stale,
    get ended() {
      return host.alive ? null : host.ended;
    },
    get slot() {
      return chain.slot;
    },
    get attacksEnabled() {
      return chain.attacksEnabled ?? attacks;
    },
    set attacksEnabled(v) {
      if ('attacksEnabled' in chain) chain.attacksEnabled = !!v;
    },
    /** The dev host's process id (tests check that it is the only process a session starts). */
    get hostPid() {
      return host.pid;
    },
    status() {
      return { session: id, mode: 'offline', network: OFFLINE_NETWORK, fingerprint: fp.digest, slot: chain.slot,
        clock: chain.clock, stale: stale(), ended: host.alive ? null : host.ended, attacks: this.attacksEnabled,
        capabilities: init.capabilities ?? [], versions: init.versions ?? null,
        contracts: (init.contracts ?? []).map((c) => ({ name: c.name, served: c.served === true, unsupported: c.unsupported ?? null })) };
    },
    async describe(contract) {
      if (!describes.has(contract)) describes.set(contract, await forward('describe', { contract }));
      return describes.get(contract);
    },
    deployment: (contract) => forward('deployment', { now: chain.slot, contract }),
    prerequisites: (contract) => forward('prerequisites', { now: chain.slot, contract }),
    async publish(contract, prerequisite, address) {
      if (stale()) throw new StageError('session', 'stale', 'the project was rebuilt: start a new session to use the new build');
      // The page checks against the `now` the host was given, not a later one (another tab may move the clock).
      const now = chain.slot;
      const r = await forward('publish', { now, ttlSlots: TTL_SLOTS, contract, prerequisite, address });
      if (r?.prepared) remember(r.prepared, 'publish');
      return { ...r, now, ttlSlots: TTL_SLOTS };
    },
    async prepare(call) {
      if (stale()) throw new StageError('session', 'stale', 'the project was rebuilt: start a new session to use the new build');
      const now = chain.slot;
      const r = await forward('prepare', { now, ttlSlots: TTL_SLOTS, call });
      remember(r.prepared, 'call');
      return { ...r, now, ttlSlots: TTL_SLOTS };
    },
    view: (contract, instance, view = null, args = {}) => forward('view', { now: chain.slot, contract, instance, view, args }),
    instances: (contract, limit = 50) => forward('instances', { now: chain.slot, contract, limit }),
    objects: (contract, instance, limit = 50) => forward('objects', { now: chain.slot, contract, instance, limit }),
    page: (method, listing, after, limit = 50) => forward(method, { listing, after, limit }),
    decode: (contract, datum) => forward('decode', { contract, datum }),
    /** A declined review: the host releases the body's inputs (ADR-0022 D8). */
    async discard(reservation) {
      const r = reservations.get(reservation);
      if (!r) throw new StageError('session', 'unknownReservation', 'no such reservation in this session');
      reservations.delete(reservation);
      return forward('settled', { reservation, txHash: r.txHash, status: 'discarded', tip: chain.slot });
    },
    /**
     * Submits a signed transaction to the ledger (D7 step 6). Its body must be the reserved one. Applied: confirmed at
     * its inclusion slot (final, settlementSlots 0). Refused by the ledger: discarded to the host, since this ledger
     * will never apply that body (it is never resubmitted), so its inputs are free again.
     */
    async submit(reservation, cbor) {
      live();
      const r = reservations.get(reservation);
      if (!r) throw new StageError('session', 'unknownReservation', 'no such reservation in this session');
      let hash;
      try {
        hash = ledgerTx(cbor).hash;
      } catch (e) {
        throw new StageError('ledger', 'malformed', 'not a transaction: ' + e.message);
      }
      if (hash !== r.txHash) throw new StageError('session', 'otherBody', 'the signed transaction is not the reserved body');
      const before = chain.attacks().length;
      try {
        await chain.submit(cbor);
      } catch (e) {
        if (!(e instanceof LedgerRefusal)) throw e;
        reservations.delete(reservation);
        await forward('settled', { reservation, txHash: hash, status: 'discarded', tip: chain.slot }).catch(() => {});
        throw new StageError('ledger', e.rule, e.message, { rule: e.rule, notModelled: e.rule === 'NotModelled' });
      }
      // The ledger applied it: record that first, so a host that fails to take the settlement cannot hide an applied
      // transaction (a retried increment would apply twice).
      const slot = typeof chain.inclusion === 'function' ? chain.inclusion(hash) : chain.slot - 1;
      reservations.delete(reservation);
      const ran = chain.attacks().slice(before).filter((a) => a.tx === hash);
      const entry = { txHash: hash, slot, state: null, attacks: ran.length,
        acceptedAttacks: ran.filter((a) => !a.refused).map(({ name, invariant, detail }) => ({ name, invariant, detail })) };
      submitted.push(entry);
      try {
        const settled = await forward('settled', { reservation, txHash: hash, status: 'confirmed', slot, tip: chain.slot });
        entry.state = settled?.state ?? null;
      } catch (e) {
        entry.settlement = String(e.message ?? e).slice(0, 500);
      }
      return entry;
    },
    /** Outputs at an address, in the protocol's snapshot encoding (the page's wallets and checks read these). */
    async utxos(address) {
      return (await chain.utxos(address)).map(snapshotOutput);
    },
    async outputs(refs) {
      const out = {};
      for (const r of refs) {
        const u = await chain.output(r);
        if (u) out[r] = snapshotOutput(u);
      }
      return out;
    },
    protocolParams: () => chain.protocolParams(),
    /** Whether a script credential's stake is registered (the trusted chain's answer, for the publication checks). */
    async registered(scriptHash) {
      return (await chain.account(rewardAddress({ type: 'script', hash: scriptHash }, 0))).registered === true;
    },
    /** Funds an identity's address from nowhere (offline only, D5). */
    fund(address, lovelace) {
      const parts = (() => {
        try {
          return addressParts(address);
        } catch {
          return null;
        }
      })();
      if (!parts || parts.networkId !== OFFLINE_NETWORK.networkId) throw new StageError('session', 'address', 'not an address on the offline network');
      const q = BigInt(lovelace);
      if (q <= 0n || q > MAX_FUND) throw new StageError('session', 'amount', 'fund between 1 lovelace and 1,000,000 ADA');
      return chain.fund(address, q);
    },
    advance(ms) {
      const n = Number(ms);
      if (!Number.isSafeInteger(n) || n <= 0 || n > 366 * 24 * 3600 * 1000) throw new StageError('session', 'time', 'advance by 1 ms to a year');
      chain.advance(n);
      return chain.slot;
    },
    history: () => [...submitted],
    close: () => host.close(),
  };
}
