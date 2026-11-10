// The page's record of a keyed operation's designated input (ADR-0016 D7, D11), and the page's form of D7's rule.
//
// Before the first attempt of a keyed call, the page designates one ordinary wallet input and records it; every attempt
// of the operation spends it, so at most one of them can ever land, whatever a service, a provider or a wallet reports.
// The record is persisted (IndexedDB in a browser) and survives a reload. Its lifecycle (M3 review, F78, R-F2, F82):
//   - While any attempt of the operation can still land, judged by the page's own clock (the deployment's pinned slot
//     configuration, less a skew margin, and never later than the chain API's tip: the earlier of the two, which can
//     only delay expiry), every new attempt spends the record's designated input, whatever the chain API says about its
//     spentness or the tip.
//   - Only once every attempt's window has passed by that clock (plus a settlement margin) may the record end: as "it
//     may have landed: check" when the input is gone, as "failed" when it is still there. Without a slot clock a record
//     never expires; only the user's explicit abandon ends it.
//   - An attempt is recorded before the wallet is asked to sign, and a signing error never removes it: keeping it is
//     always safe (retries spend the same input); it only delays expiry.
//   - A confirmation through awaitTx ends the record: a later call is a new operation the application asked for.
// Every read-modify-write of a wallet's record happens under one lock per network (Web Locks across tabs in a browser,
// an in-process mutex otherwise), with a revision check in the store (F79, R-F3).

import { slotClockOf, KEYED_LIMITS } from './keyed.js';

const KEY = 'joust.keyed.pending';

/** A record's revision; records written before revisions existed (never released) count as revision 0 (F89). */
export const revOf = (r) => (Number.isInteger(r?.rev) ? r.rev : 0);

/** A record's revision must be the stored one's next (or 1 for a new record): otherwise another writer moved first. */
export class RecordConflict extends Error {}

/** A store of records in memory (Node, tests; a new store is a fresh page without IndexedDB), optionally seeded. */
export function memoryRetryStore(initial = []) {
  let records = initial.map((r) => ({ ...r, attempts: r.attempts.map((a) => ({ ...a })) }));
  const copy = (r) => ({ ...r, attempts: r.attempts.map((a) => ({ ...a })) });
  return {
    async all() {
      return records.map(copy);
    },
    async put(record) {
      const old = records.find((r) => r.id === record.id);
      if ((old ? revOf(old) + 1 : 1) !== record.rev) throw new RecordConflict(`record ${record.id}: revision ${record.rev} after ${old ? revOf(old) : 'none'}`);
      records = [...records.filter((r) => r.id !== record.id), copy(record)];
    },
    async delete(id, rev) {
      const old = records.find((r) => r.id === id);
      if (old && rev !== undefined && revOf(old) !== rev) throw new RecordConflict(`record ${id}: deleting revision ${rev}, stored ${revOf(old)}`);
      records = records.filter((r) => r.id !== id);
    },
  };
}

/** A store of records in IndexedDB (a browser page): they survive a reload; each write checks the revision in its transaction. */
export function indexedDbRetryStore(name = 'joust') {
  const open = () => new Promise((resolve, reject) => {
    const req = globalThis.indexedDB.open(name, 1);
    req.onupgradeneeded = () => req.result.createObjectStore('keyed', { keyPath: 'id' });
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  const run = async (mode, f) => {
    const db = await open();
    try {
      return await new Promise((resolve, reject) => {
        const t = db.transaction('keyed', mode);
        let result;
        let failure;
        f(t.objectStore('keyed'), (r) => { result = r; }, (e) => { failure = e; t.abort(); });
        t.oncomplete = () => resolve(result);
        t.onerror = () => reject(failure ?? t.error);
        t.onabort = () => reject(failure ?? t.error);
      });
    } finally {
      db.close();
    }
  };
  return {
    all: () => run('readonly', (s, done) => { const q = s.getAll(); q.onsuccess = () => done(q.result ?? []); }),
    put: (record) => run('readwrite', (s, done, fail) => {
      const q = s.get(record.id);
      q.onsuccess = () => {
        const old = q.result;
        if ((old ? revOf(old) + 1 : 1) !== record.rev) return fail(new RecordConflict(`record ${record.id}: revision ${record.rev} after ${old ? revOf(old) : 'none'}`));
        s.put(record);
        done();
      };
    }),
    delete: (id, rev) => run('readwrite', (s, done, fail) => {
      const q = s.get(id);
      q.onsuccess = () => {
        if (q.result && rev !== undefined && revOf(q.result) !== rev) return fail(new RecordConflict(`record ${id}: deleting revision ${rev}`));
        s.delete(id);
        done();
      };
    }),
  };
}

/** IndexedDB where the page has it, else memory. */
export function defaultRetryStore() {
  return globalThis.indexedDB ? indexedDbRetryStore() : memoryRetryStore();
}

/** Thrown at once when a keyed call starts while another is in flight on the network, in this page or another tab. */
export class KeyedCallInProgress extends Error {}

/** The keyed call in flight on each network in this page: { keys, record, inReview, abandon } (F90, F92, F93). */
export const inFlight = new Map();

/**
 * Runs {@code f(flight)} as the only keyed call in flight on {@code networkId}; never queues (M3 review, F90, F92). A call
 * that starts while another is in flight, in this page or (Web Locks, ifAvailable) in another tab of the origin, is
 * refused at once with {@link KeyedCallInProgress}: every call then starts after the previous one's outcome was
 * delivered, so a retry can never be bound, while it waits, to an operation that ends before it runs, and a review can
 * never wait for a call queued behind its own.
 */
export function exclusiveKeyed(networkId, scope, what, f) {
  const name = `joust.keyed:${networkId}`;
  // Reserved synchronously, before any await of the caller or of this function (F95): a call made while another is in
  // flight, even one still reading its wallet, is refused at once.
  if (inFlight.has(networkId)) {
    return Promise.reject(new KeyedCallInProgress(`${what}: another keyed call is in progress on this page; call again when it has finished`));
  }
  const flight = { keys: [], store: scope, record: null, inReview: false, abandon: false };
  inFlight.set(networkId, flight);
  const run = async () => {
    try {
      if (globalThis.navigator?.locks?.request) {
        // The cross-tab lock before anything else, the wallet's reads included (F95).
        return await globalThis.navigator.locks.request(name, { mode: 'exclusive', ifAvailable: true }, (lock) => {
          if (!lock) throw new KeyedCallInProgress(`${what}: another keyed call is in progress in another tab; call again when it has finished`);
          return f(flight);
        });
      }
      return await f(flight);
    } finally {
      if (inFlight.get(networkId) === flight) inFlight.delete(networkId);
    }
  };
  return run();
}

/** The unsettled record of {@code wallet} on {@code networkId}, if any (one per wallet: its keys overlap). */
export async function pendingOf(store, wallet, networkId) {
  const all = await store.all();
  return all.find((r) => r.kind === KEY && r.networkId === networkId && r.keys.some((k) => wallet.keyHashes.includes(k))) ?? null;
}

/** A new record for an operation of {@code wallet}, designating {@code designated} ({ ref, address }). */
export function newRecord(wallet, networkId, designated, what) {
  return {
    kind: KEY,
    id: `${designated.ref}@${Date.now()}`,
    rev: 1,
    networkId,
    keys: [...wallet.keyHashes],
    designated: designated.ref,
    address: designated.address,
    what,
    attempts: [],
    created: Date.now(),
  };
}

/**
 * The margin for the page's own clock: it may run this far ahead of the chain (ms). It is at least what verifyKeyed
 * accepts between the chain API's tip and the page's clock (R-F8), so a fast clock that a matching lying tip makes look
 * right cannot end a record early: a page clock more than this margin off is outside the page's trust assumptions.
 */
export const CLOCK_SKEW_MS = 30_000;

/**
 * Whether {@code record}'s operation is settled (the page's form of D7, above). {@code clock} is the deployment's slot
 * configuration (the page's own clock), validated as verifyKeyed validates it: an invalid clock, or any slot that is
 * not finite, keeps the record pending (F87). {@code wallet} is a wallet view read under the lock. The operation
 * "failed" only when both the wallet and the chain API still show the designated input; otherwise it "may have landed"
 * (R-F7). Returns { settled, outcome: 'pending' | 'failed' | 'may have landed' | 'nothing signed', reason }.
 */
export async function settle(record, chain, { clock, wallet, nowMs = Date.now(), settlementSlots = 2, skewMs, maxTipSkewSlots = KEYED_LIMITS.maxTipSkewSlots } = {}) {
  if (!record.attempts.length) return { settled: true, outcome: 'nothing signed', reason: 'no attempt was handed to the wallet' };
  const pending = (reason) => ({ settled: false, outcome: 'pending', reason });
  // An attempt without a known deadline (null or undefined, R-F14) never expires.
  const lasts = record.attempts.map((a) => (a.validTo === null || a.validTo === undefined ? NaN : Number(a.validTo)));
  if (lasts.some((x) => !Number.isFinite(x))) return pending('an attempt without a deadline never expires');
  const last = Math.max(...lasts);
  const c = slotClockOf({ slotConfig: clock });
  if (!c || !Number.isFinite(nowMs)) return pending('without a valid slot clock the record never expires');
  const margin = BigInt(Math.max(skewMs ?? CLOCK_SKEW_MS, maxTipSkewSlots * Number(c.slotLengthMs)));
  const slotBig = c.zeroSlot + (BigInt(Math.floor(nowMs)) - margin - c.zeroTimeMs) / c.slotLengthMs;
  const slot = Number(slotBig);
  if (!Number.isFinite(slot)) return pending('the page\'s clock gives no finite slot');
  let tip;
  try {
    tip = Number(await chain.tip());
  } catch {
    tip = slot;   // the page's clock alone; the chain API's tip may only delay expiry
  }
  const now = Number.isFinite(tip) ? Math.min(slot, tip) : slot;
  if (now <= last + settlementSlots) {
    return pending(`attempts ${record.attempts.map((a) => a.txHash).join(', ')} may still land (until slot ${last})`);
  }
  let inWallet = !!wallet?.utxos?.has(record.designated);
  let atProvider = false;
  try {
    atProvider = (await chain.utxosAt(record.address)).some((u) => u.ref === record.designated);
  } catch {
    atProvider = false;   // unknown: the cautious answer
  }
  return inWallet && atProvider
    ? { settled: true, outcome: 'failed', reason: 'every attempt\'s window has passed, and the wallet and the chain API both still show the designated input' }
    : { settled: true, outcome: 'may have landed', reason: 'every attempt\'s window has passed and the designated input is gone (by the wallet or the chain API)' };
}

/** Thrown while a keyed operation of the wallet may still land: retry the same call, or abandon it explicitly. */
export class PendingKeyedOperation extends Error {
  constructor(record, cause) {
    super(`a keyed operation of this wallet may still land (attempts ${record.attempts.map((a) => a.txHash).join(', ')};`
      + ` designated input ${record.designated}): retry it (every attempt spends that input) or abandon it`
      + (cause ? `: ${cause.message ?? cause}` : ''));
    this.record = record;
    this.cause = cause;
  }
}
