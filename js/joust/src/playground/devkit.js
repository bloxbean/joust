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

import { blockfrostProvider, parseTx, plutusScriptHash } from '@julclang/state-client';
import { addressParts, ledgerTx, rewardAddress } from '@julclang/state-client/ledger';
import { HostError, launchNpmHost, npmSource } from './host.js';
import { LIMITS, StageError, snapshotOutput } from './session.js';
import { openJournal, RESERVATION, stateRoot } from './journal.js';

/** Slots after inclusion before a DevKit transaction counts as final (a single-node devnet does not fork). */
export const SETTLEMENT_SLOTS = 10;
/** The validity window the playground asks for on DevKit: long enough for a person to review and a wallet to sign. */
export const DEFAULT_TTL_MS = 60_000;
/** At most this much from the faucet per request. */
export const MAX_TOPUP_ADA = 10_000n;
/** The faucet tops an address up only while it holds less than this (D5). */
export const TOPUP_BELOW = 100_000_000n;
/** Mainnet's and the public testnets' magics (preprod 1, preview 2, sanchonet 4): never a DevKit's (I4). */
export const PUBLIC_MAGICS = new Set([764824073, 1, 2, 4]);
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

/**
 * The DevKit's identity from its admin API: { networkId, protocolMagic, startTime (s), slotLengthMs, stabilityWindow
 * (3k/f slots, the horizon a validity window must stay within), admin }.
 */
export async function devkitIdentity(admin, { fetchImpl = globalThis.fetch.bind(globalThis) } = {}) {
  const r = await fetchImpl(admin.replace(/\/$/, '') + '/local-cluster/api/admin/devnet');
  if (!r.ok) throw new Error(`DevKit admin API: ${r.status}`);
  const d = await r.json();
  const protocolMagic = Number(d.protocolMagic);
  const startTime = Number(d.startTime ?? d.genesis?.startTime);
  if (!Number.isSafeInteger(protocolMagic) || protocolMagic < 0 || !Number.isSafeInteger(startTime) || startTime < 0) {
    throw new Error('DevKit admin API: no protocolMagic or startTime');
  }
  // Mainnet's and the public testnets' magics are never a DevKit's (I4, M3-S1), whatever its admin API says.
  if (PUBLIC_MAGICS.has(protocolMagic)) throw new Error(`DevKit admin API: protocol magic ${protocolMagic} is a public network's`);
  const slotLengthMs = Math.round(Number(d.slotLength ?? 1) * 1000);
  if (!Number.isSafeInteger(slotLengthMs) || slotLengthMs <= 0) throw new Error('DevKit admin API: no slotLength');
  const k = Number(d.securityParam);
  const f = Number(d.activeSlotsCoeff);
  // D-3: 3k/f slots; 300 when the admin API does not say.
  const stabilityWindow = Number.isFinite(k) && k > 0 && Number.isFinite(f) && f > 0 && f <= 1 ? Math.floor((3 * k) / f) : 300;
  return { networkId: 0, protocolMagic, startTime, slotLengthMs, stabilityWindow, admin: admin.replace(/\/$/, '') };
}

/** D-1: the provider serves the admin API's chain: its genesis has the same magic and start time. */
export function sameChain(identity, genesis) {
  return !!genesis && genesis.networkMagic === identity.protocolMagic && genesis.systemStart === identity.startTime;
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
    /** Yaci Store's genesis: { networkMagic, systemStart (s) }. */
    async genesis() {
      const g = await p.get('/genesis');
      return { networkMagic: Number(g?.network_magic), systemStart: Number(g?.system_start) };
    },
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

const refOf = (i) => (typeof i === 'string' ? i : i.ref ?? `${i.txHash}#${i.index}`);
const sum = (utxos) => utxos.reduce((n, u) => n + BigInt(u.value.find?.((a) => a.unit === 'lovelace')?.quantity ?? 0), 0n);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** One operation at a time per key (a reservation, an address): later callers wait for the earlier ones. */
function keyedQueue() {
  const tails = new Map();
  return (key, fn) => {
    const prev = tails.get(key) ?? Promise.resolve();
    const run = prev.then(fn, fn);
    const tail = run.catch(() => {});
    tails.set(key, tail);
    tail.then(() => { if (tails.get(key) === tail) tails.delete(key); });
    return run;
  };
}

/**
 * Starts a DevKit session for the project at {@code root}: an npm project (built: build/joust/contracts.json) or, with
 * {@code source} a gradleSource (gradle.js), a Gradle project's verified snapshot, as offline sessions do.
 *
 * Settlement is the journal's and the chain's (I7): what the page is shown comes from inclusion slots and tips read at
 * one chain point, never from the host's reply. The host is told each event and its reply checked against what the
 * chain shows; a host that disagrees is fenced (it builds nothing more; a new session restores the journal into a
 * fresh host). A DevKit reset (another genesis, or a tip that goes back) ends the session.
 *
 * @param o.source       the build the session pins (default npmSource(root))
 * @param o.net          the resolved network (attach()): chain, admin
 * @param o.identity     devkitIdentity(net.admin) (default: read now); the session's clock is derived from it
 * @param o.chain        yaciChain(net) (default)
 * @param o.state        the per-user state directory (default ~/.joust/state)
 * @param o.ttlSlots     the validity window asked for (default 60 s of slots; at most the stability window, D-3)
 * @param o.clock        the slot clock (default: the identity's genesis start and slot length)
 * @param o.nowSlot      () => the current slot (default: from the clock)
 * @param o.topUp        (address, ada) => funds it from the faucet (default the admin API's faucet only)
 * @param o.identityBase the DevKit mnemonic's first account index the page's test identities use (default 1)
 */
export async function devkitSession({ id, root, net, identity, chain, state = stateRoot(), ttlSlots, settlementSlots = SETTLEMENT_SLOTS,
  clock, nowSlot, topUp, pollMs = 1000, identityBase = 1, launch = launchNpmHost, source = npmSource(root, { launch }), onEnd = () => {},
  log = () => {} }) {
  identity ??= await devkitIdentity(net.admin);
  chain ??= yaciChain(net);
  // D-1: the provider must serve the admin API's chain before anything is journaled, funded or submitted.
  const verify = async () => {
    const g = await chain.genesis();
    if (!sameChain(identity, g)) {
      throw new StageError('session', 'otherChain', `the chain API (${net.chain}) serves another chain (magic ${g?.networkMagic}, start `
        + `${g?.systemStart}) than the DevKit's admin API (magic ${identity.protocolMagic}, start ${identity.startTime})`);
    }
  };
  await verify();
  // The slot clock of the verified identity (its genesis start and slot length); tests may give another.
  clock ??= { zeroSlot: 0, zeroTimeMs: identity.startTime * 1000, slotLengthMs: identity.slotLengthMs ?? net.clock.slotLengthMs };
  const window = identity.stabilityWindow ?? 300;
  ttlSlots = Math.min(ttlSlots ?? Math.max(1, Math.ceil(DEFAULT_TTL_MS / clock.slotLengthMs)), window);
  nowSlot ??= () => clock.zeroSlot + Math.floor((Date.now() - clock.zeroTimeMs) / clock.slotLengthMs);
  // The admin API's faucet only: never a payment the server signs (it could spend an identity's outputs, #179 P1-8).
  topUp ??= async (address, ada) => {
    const { devkitTopUp } = await import('@julclang/joust-runtime');
    await devkitTopUp(address, ada, net.admin);
  };
  const network = { networkId: 0, protocolMagic: identity.protocolMagic, mainnet: false };
  const time = { maxTtlSlots: window, settlementSlots, maxNowAheadSlots: 120 };

  const fp = await source.open();
  const entries = fp.entries;
  let journal;
  try {
    journal = await openJournal({ root: fp.root, chain: identity, state, served: async () => {
      const g = await chain.genesis();
      return { protocolMagic: g.networkMagic, startTime: g.systemStart };
    } });
  } catch (e) {
    fp.dispose();
    if (e.code === 'devkitReset' || e.code === 'journalLocked') throw new StageError('session', e.code, e.message);
    throw e;
  }
  const read = chainReads(chain);
  let host;
  try {
    host = fp.launch({ onExit: (reason) => { fp.dispose(); onEnd(reason); } });
  } catch (e) {
    fp.dispose();
    journal.close();
    throw e;
  }
  let lastNow = 0;
  const now = () => (lastNow = Math.max(lastNow, nowSlot()));
  let tip = null;                 // the latest chain point a settlement pass read
  // txHash -> { label, chain: null | 'included' | 'final' | 'expired' | 'rolledBack' | 'discarded', slot,
  //             transport: null | 'pending' | 'accepted' | 'refused' | 'unknown', message }
  const status = new Map();
  const submitted = [];
  const serial = keyedQueue();
  const topUps = new Map();
  let watcher = null;
  let closed = false;
  let fenced = null;              // why the host builds nothing more
  let rewoundUntil = null;        // a rewound chain: nothing is built until the tip passes this slot (#179 r2)
  const waits = new Set();        // pending validity waits, cancelled on reset or close
  let ended = null;               // why the session ended (a DevKit reset)

  const observed = (txHash, patch) => status.set(txHash, { label: '', chain: null, slot: null, transport: null, ...status.get(txHash), ...patch });
  const fence = (why) => {
    if (fenced) return;
    fenced = why;
    log(`playground (devkit): the dev host is fenced: ${why}`);
  };
  const live = () => {
    if (ended) throw new StageError('session', 'reset', ended);
    if (!host.alive) throw new StageError('session', 'hostEnded', `the dev host ended (${host.ended}): start a new session`);
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
  const fail = async (e) => {
    clearInterval(watcher);
    await host.close().catch(() => {});
    journal.close();
    throw e;
  };
  /** Tells the host a settlement event; a reply other than {@code expected} (or a refusal) fences it. Never throws. */
  async function inform(e, params, expected) {
    try {
      const r = await forward('settled', { reservation: e.reservation, txHash: e.txHash, ...params });
      if (!expected.includes(r?.state)) fence(`it answered ${r?.state} to ${params.status} of ${e.txHash.slice(0, 16)}…, where the chain shows ${expected.join(' or ')}`);
    } catch (err) {
      fence(`it refused ${params.status} of ${e.txHash.slice(0, 16)}…: ${err.message ?? err}`);
    }
  }
  /** Every journaled body's inclusion slot, read between two equal chain points (#179 P1-6); null if the chain kept moving. */
  async function observe() {
    for (let attempt = 0; attempt < 5; attempt++) {
      const t1 = await chain.tip();
      const seen = new Map();   // keyed by the transaction hash queried (#179 r2)
      for (const e of journal.entries) if (!seen.has(e.txHash)) seen.set(e.txHash, await chain.txSlot(e.txHash));
      const t2 = await chain.tip();
      if (t1.slot === t2.slot && t1.hash === t2.hash) return { tip: t1, seen };
    }
    return null;
  }
  const within = (slot, e, t) => slot !== null && slot >= (e.validFrom ?? 0) && slot < e.validTo && slot <= t;

  // ---- initialize, then restore what the journal holds (ADR-0022 D8: after a restart)
  let init;
  let restored = 0;
  try {
    if (journal.lost && journal.highWater === null) {
      throw new StageError('session', 'journalCorrupt', `the journal ${journal.file} is unreadable and has no high-water mark: move it `
        + 'aside once the bodies it held have expired');
    }
    const restoring = journal.entries.length > 0 || journal.lost;
    init = await host.call('initialize', { protocol: [1], network, clock, time, limits: LIMITS,
      build: { snapshot: fp.snapshot, digest: fp.digest }, restoring });
    if (init.fingerprint !== fp.digest) {
      throw new StageError('session', 'fingerprint', 'the dev host loaded another build than the one the playground hashed: start again');
    }
    if (restoring) restored = await restore();
    await rewindFence();
  } catch (e) {
    return fail(e);
  }

  /**
   * #179 r2: bodies that left the journal (final or expired) left it at a chain point; a chain of the same genesis now
   * below that point may let them land again, so nothing is built until the tip passes the high-water mark.
   */
  async function rewindFence() {
    const at = journal.removedAt;
    if (at === null) return;
    const t = (await chain.tip()).slot;
    if (t < at) {
      rewoundUntil = Math.max(journal.highWater ?? at, at);
      log(`playground (devkit): the chain is at slot ${t}, below slot ${at} where settled bodies left the journal: nothing is `
        + `built until it passes slot ${rewoundUntil}`);
    }
  }

  async function restore() {
    // History lost (missing or invalid): do not complete the restore until an observed tip strictly passes the mark.
    if (journal.lost) {
      const bound = journal.highWater;
      const wait = ((bound - (await chain.tip()).slot) + 1) * clock.slotLengthMs;
      if (wait > 5 * 60_000) {
        throw new StageError('session', 'journalLost', `this project's DevKit journal is missing or invalid; start again after slot ${bound + 1}`);
      }
      log(`the journal is missing or invalid: waiting until the chain passes slot ${bound}`);
      while ((await chain.tip()).slot <= bound) await sleep(Math.min(1000, clock.slotLengthMs));
    }
    const o = await observe();
    if (!o) throw new StageError('session', 'chainMoving', 'the chain kept moving while the journal was read: start again');
    const t = o.tip.slot;
    // What may still land or still needs its depth margin, as the chain shows it now (one entry per body).
    const keep = [];
    const bodies = new Map();   // txHash -> its flags, across duplicates (a handoff or a submission is never forgotten)
    for (const e of journal.entries) {
      const f = bodies.get(e.txHash) ?? { submitted: false, handedOff: false };
      bodies.set(e.txHash, { submitted: f.submitted || e.submitted, handedOff: f.handedOff || e.handedOff });
    }
    const done = new Set();
    for (const raw of journal.entries) {
      if (done.has(raw.txHash)) continue;
      done.add(raw.txHash);
      const e = { ...raw, ...bodies.get(raw.txHash) };
      const slot = o.seen.get(e.txHash);
      if (within(slot, e, t)) {
        if (t - slot <= settlementSlots) keep.push({ ...e, state: 'shallow', slot });
        else observed(e.txHash, { label: e.label, chain: 'final', slot });
      } else if (e.validTo > t) {
        keep.push({ ...e, state: 'prepared', slot: null });
      } else {
        observed(e.txHash, { label: e.label, chain: 'expired' });
      }
    }
    const r = await forward('restore', { transactions: keep.map((e) => (e.state === 'shallow'
      ? { cbor: e.cbor, state: 'shallow', slot: e.slot } : { cbor: e.cbor, state: 'prepared' })), complete: true });
    const ids = r?.reservations;
    // #179 P1-4: one valid, distinct id per restored body, or the journal stays as it was.
    if (!Array.isArray(ids) || ids.length !== keep.length || !ids.every((x) => typeof x === 'string' && RESERVATION.test(x))
      || new Set(ids).size !== ids.length) {
      throw new StageError('host', 'restore', 'the dev host\'s restore did not give one distinct reservation per body: the journal is kept');
    }
    journal.replace(keep.map((e, i) => ({ ...e, reservation: ids[i] })));
    for (const e of keep) {
      observed(e.txHash, { label: e.label, chain: e.state === 'shallow' ? 'included' : null, slot: e.slot,
        transport: e.submitted ? 'unknown' : null });
    }
    return keep.length;
  }

  // ---- settlement from observed chain points (D7 step 7)
  function reset(why) {
    ended = `the DevKit was reset (${why}): start a new session`;
    clearInterval(watcher);
    for (const w of waits) w();
    log(`playground (devkit): ${ended}`);
    host.close().catch(() => {});
  }
  let ticking = false;
  async function tick() {
    if (ticking || closed || ended || !host.alive) return;
    ticking = true;
    try {
      // D-2: another genesis, or a tip below the last one read, is a reset DevKit: this session's chain is gone.
      const g = await chain.genesis();
      if (!sameChain(identity, g)) return reset('its genesis changed');
      const o = await observe();
      if (!o) return;
      if (tip && (o.tip.slot < tip.slot || (o.tip.slot === tip.slot && o.tip.hash !== tip.hash))) return reset('its tip went back');
      tip = o.tip;
      const t = o.tip.slot;
      if (rewoundUntil !== null && t > rewoundUntil) rewoundUntil = null;
      // 1. Inclusions first, each told at its own slot (no other body crosses a threshold before its own inclusion
      //    is known: #179 P1-5), oldest first.
      const indexing = [];
      const landed = journal.entries.filter((e) => e.state === 'prepared' && within(o.seen.get(e.txHash), e, t))
        .sort((a, b) => o.seen.get(a.txHash) - o.seen.get(b.txHash));
      for (const e of landed) {
        const slot = o.seen.get(e.txHash);
        journal.update(e.reservation, { state: 'shallow', slot });
        await inform(e, { status: 'confirmed', slot, tip: slot }, settlementSlots === 0 ? ['shallow', 'confirmed'] : ['shallow']);
        // D-5: the host knows at once; the page is told once Yaci Store's indexes show the outputs (bounded, after the
        // rest of this pass).
        indexing.push(chain.indexed(e.txHash).catch(() => {}).then(() => {
          const now = status.get(e.txHash)?.chain;
          if (now === null || now === undefined || now === 'rolledBack') observed(e.txHash, { label: e.label, chain: 'included', slot });
        }));
      }
      // 2. An inclusion the chain no longer shows: back to prepared.
      for (const e of journal.entries.filter((x) => x.state === 'shallow' && o.seen.get(x.txHash) !== x.slot
        && !landed.some((l) => l.reservation === x.reservation))) {
        journal.update(e.reservation, { state: 'prepared', slot: null });
        observed(e.txHash, { chain: 'rolledBack', slot: null });
        await inform(e, { status: 'rolledBack', tip: t }, ['prepared']);
      }
      // 3. Depth and expiry at the chain point read.
      for (const e of journal.entries) {
        if (e.state === 'shallow' && t > e.slot + settlementSlots) {
          journal.remove(e.reservation, t);
          observed(e.txHash, { chain: 'final', slot: e.slot });
          await inform(e, { status: 'confirmed', slot: e.slot, tip: t }, ['confirmed']);
        } else if (e.state === 'prepared' && o.seen.get(e.txHash) === null && t > e.validTo + settlementSlots) {
          journal.remove(e.reservation, t);
          observed(e.txHash, { chain: 'expired', slot: null });
          await inform(e, { status: 'expired', tip: t }, ['expired']);
        }
      }
      await Promise.all(indexing);
    } catch (err) {
      log(`playground (devkit): settlement: ${err.message ?? err}`);
    } finally {
      ticking = false;
    }
  }
  watcher = setInterval(tick, pollMs);
  watcher.unref?.();

  /** Whether the project's build changed since this session started (I6). */
  const stale = () => fp.stale();
  /**
   * Before the page can sign it (ADR-0022 D8): the body in the journal under a valid, new reservation, and the
   * high-water mark over it. A body that cannot be journaled is never returned, and its host is fenced (#179 P1-4).
   */
  const handOut = (prepared, label, { now: n, ttl }) => {
    const tx = (() => {
      try {
        return parseTx(prepared?.cbor);
      } catch {
        return null;
      }
    })();
    const reservation = prepared?.reservation;
    const validTo = tx?.ttl === null || tx?.ttl === undefined ? null : Number(tx.ttl);
    const validFrom = tx?.validFrom === null || tx?.validFrom === undefined ? null : Number(tx.validFrom);
    const refs = tx ? new Set([...tx.inputs, ...tx.collateral].map(refOf)) : new Set();
    const held = journal.entries;
    const overlap = held.find((e) => {
      const b = parseTx(e.cbor);
      return [...b.inputs, ...b.collateral].some((x) => refs.has(refOf(x)));
    });
    const problem = !tx ? 'no transaction' : tx.hash !== prepared.txHash ? 'another hash than its txHash'
      : typeof reservation !== 'string' || !RESERVATION.test(reservation) ? 'no valid reservation'
        : journal.find(reservation) ? 'a reservation already journaled'
          // #179 r2: the window the request asked for, decoded from the body, before anything is written.
          : validTo === null ? 'no upper validity bound'
            : validTo <= n || validTo > n + ttl ? `a validity bound ${validTo} outside the asked window (${n}, ${n + ttl}]`
              : validFrom !== null && validFrom >= validTo ? 'an empty validity window'
                : held.some((e) => e.txHash === tx.hash) ? 'the body of a reservation already journaled'
                  : overlap ? `an input or collateral that the journaled body ${overlap.txHash.slice(0, 16)}… already spends` : null;
    if (problem) {
      fence(`it gave a body with ${problem}`);
      throw new StageError('host', 'reservation', `the dev host gave a body with ${problem}: it was not handed out`);
    }
    journal.raise(validTo + settlementSlots);
    journal.record({ reservation, txHash: tx.hash, cbor: prepared.cbor, validTo, validFrom, state: 'prepared', label });
    observed(tx.hash, { label, chain: null, slot: null, transport: null });
  };
  const guardBuild = () => {
    if (rewoundUntil !== null) {
      throw new StageError('session', 'rewound', `the chain was rewound below bodies that already settled: nothing is built until it `
        + `passes slot ${rewoundUntil}`);
    }
    if (fenced) throw new StageError('session', 'fenced', `the dev host disagrees with the chain (${fenced}): start a new session`);
    if (stale()) throw new StageError('session', 'stale', 'the project was rebuilt: start a new session to use the new build');
  };
  let pruned = journal.pruned;
  if (pruned.length) log(`playground (devkit): removed the journal of ${pruned.length} earlier DevKit chain(s): ${pruned.map((p) => `started ${p.startTime}, ${p.transactions} transaction(s)`).join('; ')}`);
  const reservationOf = (reservation) => {
    const e = journal.find(reservation);
    if (!e) throw new StageError('session', 'unknownReservation', 'no such reservation in this session');
    return e;
  };

  return {
    id,
    mode: 'devkit',
    build: fp.kind,
    snapshot: fp.snapshot,
    root: fp.root,
    fingerprint: fp.digest,
    entries,
    verifiers: fp.verifiers ?? null,
    host,
    init,
    stale,
    journal,
    get ended() {
      return ended ?? (host.alive ? null : host.ended);
    },
    get fenced() {
      return fenced;
    },
    get attacksEnabled() {
      return false;
    },
    set attacksEnabled(_v) {
      throw new StageError('session', 'devkit', 'DevKit runs no attack suite: its node checks every transaction');
    },
    /** The dev host's process id (tests check that it is the only process a session starts). */
    get hostPid() {
      return host.pid;
    },
    status() {
      const shown = pruned;
      if (shown.length) {
        pruned = [];   // reported once
        journal.clearNotices();
      }
      return { session: id, mode: 'devkit', network: { ...network, name: net.name ?? 'devkit' },
        chain: { startTime: identity.startTime, admin: identity.admin }, fingerprint: fp.digest, slot: now(), tip: tip?.slot ?? null,
        clock, ttlSlots, settlementSlots, identityBase, stale: stale(), ended: this.ended, fenced, rewoundUntil, attacks: false,
        capabilities: init.capabilities ?? [], versions: init.versions ?? null, restored, pruned: shown,
        contracts: (init.contracts ?? []).map((c) => ({ name: c.name, served: c.served === true, unsupported: c.unsupported ?? null })) };
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
      guardBuild();
      const n = now();
      const r = await forward('publish', { now: n, ttlSlots, contract, prerequisite, address });
      if (r?.prepared) handOut(r.prepared, `publish ${prerequisite}`, { now: n, ttl: ttlSlots });
      return { ...r, now: n, ttlSlots };
    },
    async prepare(call) {
      guardBuild();
      const n = now();
      const r = await forward('prepare', { now: n, ttlSlots, call });
      handOut(r?.prepared, `${call.method}`, { now: n, ttl: ttlSlots });
      return { ...r, now: n, ttlSlots };
    },
    view: (contract, instance, view = null, args = {}) => forward('view', { now: now(), contract, instance, view, args }),
    instances: (contract, limit = 50) => forward('instances', { now: now(), contract, limit }),
    objects: (contract, instance, limit = 50) => forward('objects', { now: now(), contract, instance, limit }),
    page: (method, listing, after, limit = 50) => forward(method, { listing, after, limit }),
    decode: (contract, datum) => forward('decode', { contract, datum }),
    /**
     * A body declined at the page's review, before any wallet had it (ADR-0022 D8): the host releases its inputs. A body
     * handed to a CIP-30 wallet or submitted is never discarded. One operation per reservation at a time.
     */
    discard: (reservation) => serial(reservation, async () => {
      const e = reservationOf(reservation);
      if (e.submitted || e.handedOff) {
        throw new StageError('session', 'handedOut', 'a body a wallet had or the node saw is settled by the chain, never discarded');
      }
      // A tip the chain gave (#179 r2): never the wall clock, which could expire others; none: kept, retry later.
      let t;
      try {
        t = (await chain.tip()).slot;
      } catch (err) {
        throw new StageError('session', 'chainUnavailable', `the chain gave no tip (${err.message ?? err}): the body is kept; discard again`);
      }
      journal.remove(reservation);
      observed(e.txHash, { chain: 'discarded' });
      await inform(e, { status: 'discarded', tip: t }, ['discarded']);
      return {};
    }),
    /** Before a CIP-30 wallet sees the body (#179 P1-3): from now on it is never discarded. */
    handOff: (reservation) => serial(reservation, async () => {
      reservationOf(reservation);
      journal.update(reservation, { handedOff: true });
      return {};
    }),
    /**
     * Submits a signed body through Yaci Store (D7 step 6), at most one operation per reservation at a time (the
     * window's start included). The submission is recorded before it is sent; its transport outcome never overrides
     * what the chain shows (#179 P1-7). A node refusal is stage ledger and the body is not discarded (it was handed
     * out: only its expiry releases its inputs); an unclear outcome (no answer) is followed on the chain.
     */
    submit: (reservation, cbor) => serial(reservation, async () => {
      live();
      const e = reservationOf(reservation);
      let hash;
      try {
        hash = ledgerTx(cbor).hash;
      } catch (err) {
        throw new StageError('ledger', 'malformed', 'not a transaction: ' + err.message);
      }
      if (hash !== e.txHash) throw new StageError('session', 'otherBody', 'the signed transaction is not the reserved body');
      // A window that starts ahead (within the host's limits): submit at its start; a reset or close cancels the wait.
      if (e.validFrom !== null && nowSlot() < e.validFrom) {
        await new Promise((ok, no) => {
          const cancel = () => { clearInterval(poll); waits.delete(cancel); no(new StageError('session', 'closed', 'the session ended before the window opened')); };
          const poll = setInterval(() => {
            if (nowSlot() >= e.validFrom) { clearInterval(poll); waits.delete(cancel); ok(); }
          }, 250);
          poll.unref?.();
          waits.add(cancel);
          setTimeout(cancel, 120_000).unref?.();
        });
      }
      // Right before sending (#179 r2): the session is live, the DevKit still serves its chain, the reservation is still
      // journaled and its window is open.
      if (closed) throw new StageError('session', 'closed', 'the session ended');
      live();
      await verify().catch((err) => {
        reset('its genesis changed');
        throw err;
      });
      const still = journal.find(reservation);
      if (!still) throw new StageError('session', 'unknownReservation', 'the reservation settled meanwhile');
      const at = nowSlot();
      if (at >= still.validTo || (still.validFrom !== null && at < still.validFrom)) {
        throw new StageError('session', 'window', `slot ${at} is outside the body's window: it was not sent`);
      }
      journal.update(reservation, { submitted: true });
      observed(hash, { label: e.label, transport: 'pending' });
      submitted.push({ txHash: hash, label: e.label });
      const out = { txHash: hash, status: 'submitted', validTo: e.validTo, settlementSlots };
      try {
        await chain.submit(cbor);
      } catch (err) {
        const message = String(err.message ?? err).slice(0, 2000);
        // The node answered (an HTTP 4xx from the submit API): refused. No answer, or the API's own failure: unclear.
        if (/^submit failed: 4\d\d\b/.test(message)) {
          observed(hash, { transport: 'refused', message });
          // #179 r2: a refusal of a body the chain already holds (an earlier submission landed) is not a failure.
          let slot = null;
          try {
            slot = await chain.txSlot(hash);
          } catch {
            // as refused
          }
          const seen = status.get(hash)?.chain;
          if (slot !== null || seen === 'included' || seen === 'final') {
            return { ...out, status: seen === 'final' ? 'final' : 'included', slot: status.get(hash)?.slot ?? slot, transport: 'refused', message };
          }
          throw new StageError('ledger', 'nodeRefused', message, { note: 'its inputs stay reserved until the body expires' });
        }
        observed(hash, { transport: 'unknown', message });
        return { ...out, transport: 'unknown', note: `the submission's outcome is unclear (${message}): the playground follows the chain` };
      }
      observed(hash, { transport: 'accepted' });
      return { ...out, transport: 'accepted' };
    }),
    /** A body's settlement as the chain shows it (I7), with the submission's transport outcome beside it. */
    txStatus(txHash) {
      const s = status.get(txHash);
      if (!s) throw new StageError('session', 'unknownTransaction', 'no such transaction in this session');
      const shown = s.chain && s.chain !== 'rolledBack' ? s.chain : s.transport === 'refused' ? 'refused'
        : s.chain === 'rolledBack' ? 'rolledBack' : s.transport ? 'submitted' : 'handedOut';
      return { txHash, status: shown, slot: s.slot, transport: s.transport, message: s.message ?? null, label: s.label,
        tip: tip?.slot ?? null, fenced, ended };
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
    /**
     * Whether a script credential's stake is registered, for the publication checks: true, false, or 'unknown' (Yaci
     * Store cannot tell; the checker accepts that for a contract's own registration, never for a verifier's).
     */
    async registered(scriptHash) {
      const r = await chain.stakeRegistered(rewardAddress({ type: 'script', hash: scriptHash }, 0));
      return r === true || r === false ? r : 'unknown';
    },
    /**
     * Tops an identity's address up from the DevKit's faucet (D5) while it holds less than 100 ADA, then waits until
     * the chain shows it; one top-up per address at a time (#179 P1-8). Answers { toppedUp }.
     */
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
      if (topUps.has(address)) return topUps.get(address);
      const run = (async () => {
        live();
        const before = sum(await chain.utxos(address));
        if (before >= TOPUP_BELOW) return { toppedUp: false };
        try {
          await topUp(address, Number(q / ADA));
        } catch (err) {
          throw new StageError('session', 'faucet', `the DevKit faucet did not pay: ${err.message ?? err}`);
        }
        for (const until = Date.now() + 60_000; Date.now() < until; await sleep(500)) {
          if (sum(await chain.utxos(address)) > before) return { toppedUp: true };
        }
        throw new StageError('session', 'faucet', 'the top-up did not show on the chain within 60 s');
      })().finally(() => topUps.delete(address));
      topUps.set(address, run);
      return run;
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
      for (const w of waits) w();
      await host.close().catch(() => {});
      journal.close();
    },
  };
}
