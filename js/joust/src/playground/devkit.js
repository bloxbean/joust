// A DevKit playground session (ADR-0024 D4, D5, D7 steps 6–7; M3): the same dev host as offline, over Yaci DevKit.
//
// The chain is the network's Yaci Store (the Blockfrost-style API at the network's "chain"; the provider is trusted,
// #169). The session answers the host's chain/read from it at one chain point, supplies `now` from the network's slot
// clock, submits signed bodies through it, and settles each handed-out body from observed tips: confirmed (shallow)
// at its inclusion slot, then final once the tip passes inclusion + settlementSlots; expired once the tip passes
// validTo + settlementSlots unseen; rolled back when an included transaction leaves the chain. Before the page can
// sign a body, the body is in the per-user journal and the high-water mark covers it (ADR-0022 D8, journal.js); a new
// session restores what the journal holds.
//
// Only a Yaci DevKit is accepted (I4): a network with an admin API and network id 0. Mainnet can never be selected;
// public testnets are not part of v1 (Q3).

import { blockfrostProvider, chainOf, parseTx, plutusScriptHash } from '@julclang/state-client';
import { addressParts, ledgerTx, rewardAddress } from '@julclang/state-client/ledger';
import { HostError, launchNpmHost, npmFingerprint } from './host.js';
import { LIMITS, StageError, snapshotOutput } from './session.js';
import { openJournal, stateRoot } from './journal.js';

/** Slots after inclusion before a DevKit transaction counts as final (a single-node devnet does not fork). */
export const SETTLEMENT_SLOTS = 10;
/** The validity window the playground asks for on DevKit: long enough for a person to review and a wallet to sign. */
export const DEFAULT_TTL_MS = 60_000;
/** At most this much from the faucet per request. */
export const MAX_TOPUP_ADA = 10_000n;
const ADA = 1_000_000n;

/**
 * Throws unless {@code raw} (a network of joust.config.json) is a DevKit the playground may use (I4): an admin API and
 * network id 0, read through Yaci Store's API. Mainnet is refused whatever else the entry says.
 */
export function checkDevkitNetwork(raw, name) {
  if (!raw) throw new Error(`joust playground: no network ${name} in joust.config.json`);
  if (raw.networkId === 1 || raw.mainnet === true || /mainnet/i.test(name)) {
    throw new Error(`joust playground: ${name} is mainnet; the playground never runs on mainnet`);
  }
  if (!raw.admin) {
    throw new Error(`joust playground: ${name} has no DevKit admin API. DevKit mode runs only on a Yaci DevKit; public testnets are`
      + ' not supported in this version');
  }
  if (raw.networkId !== 0) throw new Error(`joust playground: ${name} has network id ${raw.networkId}; a DevKit has network id 0`);
  if (raw.provider && raw.provider !== 'blockfrost') {
    throw new Error(`joust playground: ${name} reads the chain through ${raw.provider}; DevKit mode reads it through Yaci Store's API`
      + ' ("chain"), which serves the inclusion slots settlement needs');
  }
}

/** The DevKit's identity from its admin API: { networkId, protocolMagic, startTime (s), admin }. */
export async function devkitIdentity(admin, { fetchImpl = globalThis.fetch.bind(globalThis) } = {}) {
  const r = await fetchImpl(admin.replace(/\/$/, '') + '/local-cluster/api/admin/devnet');
  if (!r.ok) throw new Error(`DevKit admin API: ${r.status}`);
  const d = await r.json();
  const protocolMagic = Number(d.protocolMagic);
  const startTime = Number(d.startTime ?? d.genesis?.startTime);
  if (!Number.isSafeInteger(protocolMagic) || protocolMagic < 0 || !Number.isSafeInteger(startTime) || startTime < 0) {
    throw new Error('DevKit admin API: no protocolMagic or startTime');
  }
  // Mainnet's magic is never a DevKit's (I4), whatever its admin API says.
  if (protocolMagic === 764824073) throw new Error('DevKit admin API: mainnet\'s protocol magic');
  return { networkId: 0, protocolMagic, startTime, admin: admin.replace(/\/$/, '') };
}

/** One CBOR byte string's payload (hex), or null when {@code hex} is not exactly one definite byte string. */
function bstr(hex) {
  const h = parseInt(hex.slice(0, 2), 16);
  if (!(h >= 0x40 && h <= 0x5b)) return null;
  const extra = h & 0x1f;
  const width = extra < 24 ? 0 : extra === 24 ? 1 : extra === 25 ? 2 : extra === 26 ? 4 : 8;
  const len = width === 0 ? extra : Number(BigInt('0x' + hex.slice(2, 2 + width * 2)));
  const body = hex.slice(2 + width * 2);
  return body.length === len * 2 ? body : null;
}
const wrap = (hex) => {
  const n = hex.length / 2;
  const head = n < 24 ? (0x40 + n).toString(16) : n < 0x100 ? '58' + n.toString(16).padStart(2, '0')
    : n < 0x10000 ? '59' + n.toString(16).padStart(4, '0') : '5a' + n.toString(16).padStart(8, '0');
  return head + hex;
};

/**
 * A Plutus script as the protocol's script read answers it ({ type, cborHex }, cborHex double-wrapped as CCL's
 * PlutusScript carries it), from a provider's script CBOR (single- or double-wrapped), or null when no language gives
 * {@code hash} (as cardano-client-lib's PlutusUtil.getPlutusScript decides).
 */
export function plutusScriptOf(hash, cbor) {
  if (typeof cbor !== 'string' || !/^(?:[0-9a-f]{2})+$/i.test(cbor)) return null;
  const hex = cbor.toLowerCase();
  const inner = bstr(hex);
  const cborHex = inner !== null && bstr(inner) !== null ? hex : wrap(hex);
  const single = bstr(cborHex);
  for (const [v, type] of [[1, 'PlutusScriptV1'], [2, 'PlutusScriptV2'], [3, 'PlutusScriptV3']]) {
    if (plutusScriptHash(single, v) === hash) return { type, cborHex };
  }
  return null;
}

/**
 * The chain of a DevKit session over Yaci Store (a Blockfrost-style API): what the session reads, submits and settles
 * with. Tests pass an object of the same shape.
 */
export function yaciChain(net, { fetchImpl } = {}) {
  const p = blockfrostProvider(net.chain, { projectId: net.projectId, fetchImpl });
  return {
    provider: p,
    async tip() {
      const t = await p.tip();
      return { slot: t.slot, hash: t.hash, height: t.height };
    },
    utxos: (address) => p.utxos(address),
    output: (ref) => p.output(ref),
    protocolParams: () => p.protocolParams(),
    async script(hash) {
      return plutusScriptOf(hash, await p.scriptCbor(hash));
    },
    /** true, false, or undefined (Yaci Store cannot tell: the read is then unsupported). */
    async stakeRegistered(stakeAddress) {
      return (await p.account(stakeAddress)).registered;
    },
    assetHistory: (unit) => p.assetHistory(unit),
    async redeemers(txHash) {
      const list = await p.get(`/txs/${txHash}/redeemers`, { allow404: true });
      if (list === null) return null;
      return list.map((r) => ({ purpose: r.purpose === 'withdrawal' ? 'reward' : r.purpose, index: r.tx_index,
        scriptHash: r.script_hash, dataHash: r.redeemer_data_hash }));
    },
    async datum(hash) {
      return (await p.get(`/scripts/datum/${hash}/cbor`, { allow404: true }))?.cbor ?? null;
    },
    submit: (cbor) => p.submit(cbor, { retries: 3 }),
    /** The slot of the block that holds {@code hash}, or null (not on chain). */
    async txSlot(hash) {
      const t = await p.get(`/txs/${hash}`, { allow404: true });
      return Number.isSafeInteger(t?.slot) ? t.slot : null;
    },
    /** Waits (bounded) until Yaci Store's address indexes show the transaction, so the next read sees its outputs. */
    async indexed(hash) {
      await p.awaitTx(hash, { timeoutMs: 30_000 }).catch(() => {});
    },
    walletChain: () => chainOf(p),
  };
}

/**
 * The host's reverse reads answered from a DevKit chain at one chain point (docs/protocol/dev-host-1.md, "Reverse
 * reads"): the tip is read before and after each answer, and the answer is read again when the chain moved meanwhile.
 */
export function chainReads(chain, { networkId = 0 } = {}) {
  async function answer(q, tip) {
    switch (q.kind) {
      case 'tip':
        return { tip, value: tip };
      case 'protocolParams':
        return { tip, value: await chain.protocolParams() };
      case 'utxos': {
        if (q.cursor !== undefined) return { tip, absent: true };
        const list = await chain.utxos(q.address);
        return list.length ? { tip, value: list.map(snapshotOutput) } : { tip, absent: true };
      }
      case 'output': {
        const u = await chain.output(q.ref);
        return u ? { tip, value: snapshotOutput(u) } : { tip, absent: true };
      }
      case 'script': {
        const s = await chain.script(q.hash);
        return s ? { tip, value: s } : { tip, absent: true };
      }
      case 'stakeRegistration': {
        let unknown = false;
        for (const type of ['script', 'key']) {
          const r = await chain.stakeRegistered(rewardAddress({ type, hash: q.credential }, networkId));
          if (r === true) return { tip, value: { registered: true } };
          if (r !== false) unknown = true;
        }
        return unknown ? { tip, unsupported: true } : { tip, value: { registered: false } };
      }
      case 'assetHistory': {
        if (q.cursor !== undefined) return { tip, absent: true };
        const h = await chain.assetHistory(q.unit);
        return h.length ? { tip, value: h.map((txHash) => ({ txHash })) } : { tip, absent: true };
      }
      case 'redeemers': {
        const r = await chain.redeemers(q.txHash);
        return r === null ? { tip, absent: true } : { tip, value: r };
      }
      case 'datum': {
        const d = await chain.datum(q.hash);
        return d ? { tip, value: d.toLowerCase() } : { tip, absent: true };
      }
      default:
        return { tip, unsupported: true };
    }
  }
  return async function read(q) {
    for (let attempt = 0; attempt < 8; attempt++) {
      const before = await chain.tip();
      const a = await answer(q, before);
      const after = await chain.tip();
      if (after.slot === before.slot && after.hash === before.hash) return a;
    }
    throw new Error('the chain kept moving: no stable chain point for this read');
  };
}

const sum = (utxos) => utxos.reduce((n, u) => n + BigInt(u.value.find?.((a) => a.unit === 'lovelace')?.quantity ?? 0), 0n);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Starts a DevKit session for the npm project at {@code root} (built: build/joust/contracts.json).
 *
 * @param o.net          the resolved network (attach()): chain, admin, clock
 * @param o.identity     devkitIdentity(net.admin) (default: read now)
 * @param o.chain        yaciChain(net) (default)
 * @param o.state        the per-user state directory (default ~/.joust/state)
 * @param o.ttlSlots     the validity window asked for (default 60 s of slots)
 * @param o.nowSlot      () => the current slot (default: the network's clock)
 * @param o.topUp        (address, ada) => funds it from the faucet (default devkitTopUp)
 * @param o.identityBase the DevKit mnemonic's first account index the page's test identities use (default 1)
 */
export async function devkitSession({ id, root, net, identity, chain, state = stateRoot(), ttlSlots, settlementSlots = SETTLEMENT_SLOTS,
  nowSlot, topUp, pollMs = 1000, identityBase = 1, launch = launchNpmHost, onEnd = () => {}, log = () => {} }) {
  const clock = net.clock;
  identity ??= await devkitIdentity(net.admin);
  chain ??= yaciChain(net);
  ttlSlots ??= Math.max(1, Math.ceil(DEFAULT_TTL_MS / clock.slotLengthMs));
  nowSlot ??= () => clock.zeroSlot + Math.floor((Date.now() - clock.zeroTimeMs) / clock.slotLengthMs);
  topUp ??= async (address, ada) => {
    const { devkitTopUp } = await import('@julclang/joust-runtime');
    await devkitTopUp(address, ada, net.admin, { chain: chain.walletChain?.() });
  };
  const network = { networkId: 0, protocolMagic: identity.protocolMagic, mainnet: false };
  const time = { maxTtlSlots: Math.max(ttlSlots, 300), settlementSlots, maxNowAheadSlots: 120 };

  const fp = npmFingerprint(root);
  const entries = JSON.parse(fp.contracts.toString('utf8'));
  const journal = openJournal({ root: fp.root, chain: identity, state });
  const read = chainReads(chain);
  let host;
  try {
    host = launch({ root: fp.root, onExit: (reason) => onEnd(reason) });
  } catch (e) {
    journal.close();
    throw e;
  }
  let lastNow = 0;
  const now = () => (lastNow = Math.max(lastNow, nowSlot()));
  let tip = null;                 // the latest tip the watcher saw
  const status = new Map();       // txHash -> { status, slot, message, label }
  const submitted = [];
  let watcher = null;
  let closed = false;

  const fail = async (e) => {
    clearInterval(watcher);
    await host.close().catch(() => {});
    journal.close();
    throw e;
  };
  const forward = async (method, params) => {
    if (!host.alive) throw new StageError('session', 'hostEnded', `the dev host ended (${host.ended}): start a new session`);
    try {
      return await host.call(method, params, { read });
    } catch (e) {
      if (e instanceof HostError) throw new StageError('host', e.code, e.message, { kind: e.kind, sites: e.sites, retryAtSlot: e.retryAtSlot });
      throw e;
    }
  };

  // ---- initialize, then restore what the journal holds (ADR-0022 D8: after a restart)
  let init;
  let restored = 0;
  try {
    const restoring = journal.entries.length > 0 || journal.lost;
    init = await host.call('initialize', { protocol: [1], network, clock, time, limits: LIMITS,
      build: { snapshot: fp.root, digest: fp.digest }, restoring });
    if (init.fingerprint !== fp.digest) {
      throw new StageError('session', 'fingerprint', 'the dev host loaded another build than the one the playground hashed: start again');
    }
    if (restoring) restored = await restore();
  } catch (e) {
    return fail(e);
  }

  async function restore() {
    // History lost: do not complete the restore until an observed tip strictly passes the high-water mark.
    if (journal.lost) {
      const bound = journal.highWater;
      const wait = ((bound - (await chain.tip()).slot) + 1) * clock.slotLengthMs;
      if (wait > 5 * 60_000) {
        throw new StageError('session', 'journalLost', `this project's DevKit journal is missing; start again after slot ${bound + 1}`);
      }
      log(`the journal is missing: waiting until the chain passes slot ${bound}`);
      while ((await chain.tip()).slot <= bound) await sleep(Math.min(1000, clock.slotLengthMs));
    }
    const t = await chain.tip();
    // What may still land or still needs its depth margin, as the chain shows it now.
    const keep = [];
    for (const e of journal.entries) {
      const slot = await chain.txSlot(e.txHash);
      if (slot !== null && slot >= (e.validFrom ?? 0) && slot < e.validTo) {
        if (t.slot - slot <= settlementSlots) keep.push({ ...e, state: 'shallow', slot });
      } else if (e.validTo > t.slot) {
        keep.push({ ...e, state: 'prepared', slot: null });
      }
    }
    const r = await forward('restore', { transactions: keep.map((e) => (e.state === 'shallow'
      ? { cbor: e.cbor, state: 'shallow', slot: e.slot } : { cbor: e.cbor, state: 'prepared' })), complete: true });
    journal.replace(keep.map((e, i) => ({ ...e, reservation: r.reservations[i] })));
    for (const e of keep) status.set(e.txHash, { status: e.state === 'shallow' ? 'included' : e.submitted ? 'submitted' : 'handedOut', slot: e.slot, label: e.label });
    return keep.length;
  }

  // ---- settlement from observed tips (D7 step 7)
  async function settle(e, params) {
    const r = await forward('settled', { reservation: e.reservation, txHash: e.txHash, ...params });
    const s = r?.state;
    const st = status.get(e.txHash) ?? { label: e.label };
    if (s === 'shallow') {
      journal.update(e.reservation, { state: 'shallow', slot: params.slot });
      status.set(e.txHash, { ...st, status: 'included', slot: params.slot });
    } else if (s === 'prepared') {
      journal.update(e.reservation, { state: 'prepared', slot: null });
      status.set(e.txHash, { ...st, status: params.status === 'rolledBack' ? 'rolledBack' : st.status, slot: null });
    } else if (s === 'confirmed') {
      journal.remove(e.reservation);
      status.set(e.txHash, { ...st, status: 'final', slot: params.slot ?? st.slot });
    } else if (s === 'expired' || s === 'discarded') {
      journal.remove(e.reservation);
      status.set(e.txHash, { ...st, status: s, slot: null });
    }
    return s;
  }
  let ticking = false;
  async function tick() {
    if (ticking || closed || !host.alive) return;
    ticking = true;
    try {
      tip = await chain.tip();
      for (const e of journal.entries) {
        const slot = await chain.txSlot(e.txHash);
        const fresh = (await chain.tip());
        tip = fresh.slot >= tip.slot ? fresh : tip;
        if (e.state === 'prepared') {
          if (slot !== null && slot >= (e.validFrom ?? 0) && slot < e.validTo && slot <= tip.slot) {
            await chain.indexed(e.txHash);
            await settle(e, { status: 'confirmed', slot, tip: tip.slot });
          } else if (slot === null && tip.slot > e.validTo + settlementSlots) {
            await settle(e, { status: 'expired', tip: tip.slot });
          }
        } else if (e.state === 'shallow') {
          if (slot !== e.slot) await settle(e, { status: 'rolledBack', tip: tip.slot });
          else if (tip.slot > e.slot + settlementSlots) await settle(e, { status: 'confirmed', slot, tip: tip.slot });
        }
      }
    } catch (err) {
      log(`playground (devkit): settlement: ${err.message ?? err}`);
    } finally {
      ticking = false;
    }
  }
  watcher = setInterval(tick, pollMs);
  watcher.unref?.();

  const stale = () => {
    try {
      return npmFingerprint(fp.root).digest !== fp.digest;
    } catch {
      return true;
    }
  };
  /** Before the page can sign it: the body in the journal, the high-water mark over it (ADR-0022 D8). */
  const handOut = (prepared, label) => {
    if (!prepared?.reservation) return;
    const tx = parseTx(prepared.cbor);
    if (tx.hash !== prepared.txHash || tx.ttl === null) {
      // Never handed out: the page could not check it anyway.
      throw new StageError('verification', 'body', 'the dev host\'s body has another hash or no upper validity bound');
    }
    const validTo = Number(tx.ttl);
    journal.raise(validTo + settlementSlots);
    journal.record({ reservation: prepared.reservation, txHash: tx.hash, cbor: prepared.cbor, validTo,
      validFrom: tx.validFrom === null ? null : Number(tx.validFrom), state: 'prepared', label });
    status.set(tx.hash, { status: 'handedOut', slot: null, label });
  };
  const guardStale = () => {
    if (stale()) throw new StageError('session', 'stale', 'the project was rebuilt: start a new session to use the new build');
  };
  let pruned = journal.pruned;
  if (pruned.length) log(`playground (devkit): removed the journal of ${pruned.length} earlier DevKit chain(s): ${pruned.map((p) => `started ${p.startTime}, ${p.transactions} transaction(s)`).join('; ')}`);

  return {
    id,
    mode: 'devkit',
    root: fp.root,
    fingerprint: fp.digest,
    entries,
    host,
    init,
    stale,
    journal,
    get ended() {
      return host.alive ? null : host.ended;
    },
    get attacksEnabled() {
      return false;
    },
    set attacksEnabled(_v) {
      throw new StageError('session', 'devkit', 'DevKit runs no attack suite: its node checks every transaction');
    },
    status() {
      const shown = pruned;
      pruned = [];   // reported once
      return { session: id, mode: 'devkit', network: { ...network, name: net.name ?? 'devkit' },
        chain: { startTime: identity.startTime, admin: identity.admin }, fingerprint: fp.digest, slot: now(), tip: tip?.slot ?? null,
        clock, ttlSlots, settlementSlots, identityBase, stale: stale(), ended: host.alive ? null : host.ended, attacks: false,
        capabilities: init.capabilities ?? [], versions: init.versions ?? null, restored, pruned: shown,
        contracts: (init.contracts ?? []).map((c) => ({ name: c.name, served: c.served !== false, unsupported: c.unsupported ?? null })) };
    },
    describe: (() => {
      const cache = new Map();
      return async (contract) => {
        if (!cache.has(contract)) cache.set(contract, await forward('describe', { contract }));
        return cache.get(contract);
      };
    })(),
    deployment: (contract) => forward('deployment', { now: now(), contract }),
    prerequisites: (contract) => forward('prerequisites', { now: now(), contract }),
    async publish(contract, prerequisite, address) {
      guardStale();
      const n = now();
      const r = await forward('publish', { now: n, ttlSlots, contract, prerequisite, address });
      if (r?.prepared) handOut(r.prepared, `publish ${prerequisite}`);
      return { ...r, now: n, ttlSlots };
    },
    async prepare(call) {
      guardStale();
      const n = now();
      const r = await forward('prepare', { now: n, ttlSlots, call });
      handOut(r.prepared, `${call.method}`);
      return { ...r, now: n, ttlSlots };
    },
    view: (contract, instance, view = null, args = {}) => forward('view', { now: now(), contract, instance, view, args }),
    instances: (contract, limit = 50) => forward('instances', { now: now(), contract, limit }),
    objects: (contract, instance, limit = 50) => forward('objects', { now: now(), contract, instance, limit }),
    page: (method, listing, after, limit = 50) => forward(method, { listing, after, limit }),
    decode: (contract, datum) => forward('decode', { contract, datum }),
    /** A body declined at review, before it was signed (ADR-0022 D8): the host releases its inputs. */
    async discard(reservation) {
      const e = journal.find(reservation);
      if (!e) throw new StageError('session', 'unknownReservation', 'no such reservation in this session');
      if (e.submitted) throw new StageError('session', 'submitted', 'a submitted body is settled by the chain, never discarded');
      await settle(e, { status: 'discarded', tip: (await chain.tip()).slot });
      return {};
    },
    /**
     * Submits a signed body through Yaci Store (D7 step 6). A node refusal is shown at stage ledger; the body is not
     * discarded (it was handed out: only its expiry releases its inputs).
     */
    async submit(reservation, cbor) {
      if (!host.alive) throw new StageError('session', 'hostEnded', `the dev host ended (${host.ended}): start a new session`);
      const e = journal.find(reservation);
      if (!e) throw new StageError('session', 'unknownReservation', 'no such reservation in this session');
      let hash;
      try {
        hash = ledgerTx(cbor).hash;
      } catch (err) {
        throw new StageError('ledger', 'malformed', 'not a transaction: ' + err.message);
      }
      if (hash !== e.txHash) throw new StageError('session', 'otherBody', 'the signed transaction is not the reserved body');
      // A window that starts ahead (within the host's limits): submit at its start.
      for (const until = Date.now() + 120_000; e.validFrom !== null && nowSlot() < e.validFrom && Date.now() < until;) await sleep(250);
      journal.update(reservation, { submitted: true });
      const st = status.get(hash) ?? { label: e.label };
      try {
        await chain.submit(cbor);
      } catch (err) {
        status.set(hash, { ...st, status: 'refused', message: String(err.message ?? err).slice(0, 2000) });
        throw new StageError('ledger', 'nodeRefused', String(err.message ?? err).slice(0, 2000),
          { note: 'its inputs stay reserved until the body expires' });
      }
      status.set(hash, { ...st, status: 'submitted', slot: null });
      const entry = { txHash: hash, status: 'submitted', validTo: e.validTo, settlementSlots };
      submitted.push({ txHash: hash, label: e.label });
      return entry;
    },
    /** A submitted (or handed-out) body's settlement as the session last observed it (I7). */
    txStatus(txHash) {
      const s = status.get(txHash);
      if (!s) throw new StageError('session', 'unknownTransaction', 'no such transaction in this session');
      return { txHash, ...s, tip: tip?.slot ?? null };
    },
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
    /** Tops an identity's address up from the DevKit's faucet (D5), then waits until the chain shows it. */
    async fund(address, lovelace) {
      let parts = null;
      try {
        parts = addressParts(address);
      } catch {
        // below
      }
      if (!parts || parts.networkId !== 0) throw new StageError('session', 'address', 'not an address on the DevKit network');
      const q = BigInt(lovelace);
      if (q <= 0n || q % ADA !== 0n || q / ADA > MAX_TOPUP_ADA) throw new StageError('session', 'amount', 'top up 1 to 10,000 whole ADA');
      const before = sum(await chain.utxos(address));
      try {
        await topUp(address, Number(q / ADA));
      } catch (err) {
        throw new StageError('session', 'faucet', `the DevKit faucet did not pay: ${err.message ?? err}`);
      }
      for (const until = Date.now() + 60_000; Date.now() < until; await sleep(500)) {
        if (sum(await chain.utxos(address)) > before) return null;
      }
      throw new StageError('session', 'faucet', 'the top-up did not show on the chain within 60 s');
    },
    advance() {
      throw new StageError('session', 'devkit', 'time on DevKit is the network\'s own: it cannot be advanced');
    },
    history: () => [...submitted],
    /** One settlement pass now (tests). */
    tick,
    async close() {
      if (closed) return;
      closed = true;
      clearInterval(watcher);
      await host.close().catch(() => {});
      journal.close();
    },
  };
}
