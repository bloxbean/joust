// The page's record of a keyed operation's designated input (ADR-0016 D7, D11). Before the first attempt of a keyed
// call, the page designates one ordinary wallet UTxO and records it; every attempt of the operation must spend it, so
// at most one of them can ever land, whatever a service, a provider or a wallet reports. The record is persisted
// (IndexedDB in a browser) until the operation is settled (an attempt landed, or the input was spent elsewhere, or every
// attempt's window has passed with the input unspent) or the user abandons it, so it survives a reload: after one,
// any keyed call of that wallet must spend the recorded input until then.

const KEY = 'joust.keyed.pending';

/** A store of records in memory (Node, tests; a new store is a fresh page without IndexedDB). */
export function memoryRetryStore() {
  let records = [];
  return {
    async all() {
      return records.map((r) => ({ ...r, attempts: [...r.attempts] }));
    },
    async put(record) {
      records = [...records.filter((r) => r.id !== record.id), { ...record, attempts: [...record.attempts] }];
    },
    async delete(id) {
      records = records.filter((r) => r.id !== id);
    },
  };
}

/** A store of records in IndexedDB (a browser page): they survive a reload. */
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
        const r = f(t.objectStore('keyed'));
        t.oncomplete = () => resolve(r?.result);
        t.onerror = () => reject(t.error);
        t.onabort = () => reject(t.error);
      });
    } finally {
      db.close();
    }
  };
  return {
    all: () => run('readonly', (s) => s.getAll()).then((x) => x ?? []),
    put: (record) => run('readwrite', (s) => s.put(record)),
    delete: (id) => run('readwrite', (s) => s.delete(id)),
  };
}

/** IndexedDB where the page has it, else memory. */
export function defaultRetryStore() {
  return globalThis.indexedDB ? indexedDbRetryStore() : memoryRetryStore();
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
 * Whether {@code record}'s operation is settled, by the chain: the designated input spent (an attempt landed, or
 * something else spent it: no attempt can land now), or unspent once the chain's tip has passed every attempt's window
 * by {@code settlementSlots} (none landed; none can). Returns { settled, reason }.
 */
export async function settle(record, chain, { settlementSlots = 2 } = {}) {
  if (!record.attempts.length) return { settled: true, reason: 'no attempt was signed' };
  const unspent = (await chain.utxosAt(record.address)).some((u) => u.ref === record.designated);
  if (!unspent) return { settled: true, reason: 'the designated input is spent' };
  const tip = await chain.tip();
  const last = Math.max(...record.attempts.map((a) => (a.validTo === null || a.validTo === undefined ? Infinity : Number(a.validTo))));
  if (Number.isFinite(last) && tip > last + settlementSlots) return { settled: true, reason: 'every attempt expired unspent' };
  return { settled: false, reason: `attempts ${record.attempts.map((a) => a.txHash).join(', ')} may still land` };
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
