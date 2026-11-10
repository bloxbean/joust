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

const KEY = 'joust.keyed.pending';

/** A record's revision must be the stored one's next (or 1 for a new record): otherwise another writer moved first. */
export class RecordConflict extends Error {}

/** A store of records in memory (Node, tests; a new store is a fresh page without IndexedDB). */
export function memoryRetryStore() {
  let records = [];
  const copy = (r) => ({ ...r, attempts: r.attempts.map((a) => ({ ...a })) });
  return {
    async all() {
      return records.map(copy);
    },
    async put(record) {
      const old = records.find((r) => r.id === record.id);
      if ((old ? old.rev + 1 : 1) !== record.rev) throw new RecordConflict(`record ${record.id}: revision ${record.rev} after ${old?.rev ?? 'none'}`);
      records = [...records.filter((r) => r.id !== record.id), copy(record)];
    },
    async delete(id, rev) {
      const old = records.find((r) => r.id === id);
      if (old && rev !== undefined && old.rev !== rev) throw new RecordConflict(`record ${id}: deleting revision ${rev}, stored ${old.rev}`);
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
        if ((old ? old.rev + 1 : 1) !== record.rev) return fail(new RecordConflict(`record ${record.id}: revision ${record.rev} after ${old?.rev ?? 'none'}`));
        s.put(record);
        done();
      };
    }),
    delete: (id, rev) => run('readwrite', (s, done, fail) => {
      const q = s.get(id);
      q.onsuccess = () => {
        if (q.result && rev !== undefined && q.result.rev !== rev) return fail(new RecordConflict(`record ${id}: deleting revision ${rev}`));
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

const tails = new Map();

/**
 * Runs {@code f} holding the keyed-operation lock of {@code networkId}: Web Locks (every tab of the origin) where the
 * page has them, else an in-process mutex. A wallet's whole keyed call (record, prepare, review, sign, submit) runs
 * under it, so two calls or tabs never act on one record at once (F79, R-F3).
 */
export function withKeyedLock(networkId, f) {
  const name = `joust.keyed:${networkId}`;
  if (globalThis.navigator?.locks?.request) return globalThis.navigator.locks.request(name, { mode: 'exclusive' }, f);
  const prev = tails.get(name) ?? Promise.resolve();
  const run = prev.catch(() => {}).then(f);
  const tail = run.catch(() => {});
  tails.set(name, tail);
  tail.then(() => { if (tails.get(name) === tail) tails.delete(name); });
  return run;
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

/** The margin for the page's own clock: it may run this far ahead of the chain (ms). */
export const CLOCK_SKEW_MS = 30_000;

/**
 * Whether {@code record}'s operation is settled (the page's form of D7, above). {@code clock} is the deployment's
 * slot configuration (the page's own clock); {@code wallet} the wallet's view (its UTxOs count as unspent too).
 * Returns { settled, outcome: 'pending' | 'failed' | 'may have landed' | 'nothing signed', reason }.
 */
export async function settle(record, chain, { clock, wallet, nowMs = Date.now(), settlementSlots = 2, skewMs = CLOCK_SKEW_MS } = {}) {
  if (!record.attempts.length) return { settled: true, outcome: 'nothing signed', reason: 'no attempt was handed to the wallet' };
  const lasts = record.attempts.map((a) => (a.validTo === null || a.validTo === undefined ? Infinity : Number(a.validTo)));
  const last = Math.max(...lasts);
  let slot = null;
  try {
    if (clock && Number(clock.slotLengthMs) > 0) {
      slot = Number(clock.zeroSlot) + Math.floor((nowMs - skewMs - Number(clock.zeroTimeMs)) / Number(clock.slotLengthMs));
    }
  } catch {
    slot = null;
  }
  if (slot === null || !Number.isFinite(last)) {
    return { settled: false, outcome: 'pending', reason: 'without the page\'s slot clock (or an attempt without a deadline) the record never expires' };
  }
  let tip = Infinity;
  try {
    tip = Number(await chain.tip());
  } catch {
    tip = Infinity;   // the page's clock alone; the chain API's tip may only delay expiry
  }
  const now = Math.min(slot, Number.isFinite(tip) ? tip : slot);
  if (now <= last + settlementSlots) {
    return { settled: false, outcome: 'pending', reason: `attempts ${record.attempts.map((a) => a.txHash).join(', ')} may still land (until slot ${last})` };
  }
  let unspent = !!wallet?.utxos?.has(record.designated);
  try {
    unspent = unspent || (await chain.utxosAt(record.address)).some((u) => u.ref === record.designated);
  } catch {
    // unknown: report it as possibly landed, the cautious answer
  }
  return unspent
    ? { settled: true, outcome: 'failed', reason: 'every attempt\'s window has passed with the designated input unspent' }
    : { settled: true, outcome: 'may have landed', reason: 'every attempt\'s window has passed and the designated input is gone' };
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
