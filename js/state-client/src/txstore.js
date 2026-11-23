// The transactions a page signed and submitted itself, by hash (ADR-0015 D7): the page's chain view reads an input
// from its producing transaction's CBOR (verifiedOutput), and where the provider serves none (Ogmios; DevKit's Yaci
// Store unless store.transaction.save-cbor=true) the transactions the page submitted are the ones it can still read.
// They are as authentic as a provider's CBOR: the body hashes to the reference's transaction id, which chain.js
// checks on every read. The store only keeps them across reloads (IndexedDB in a browser, like retries.js keeps keyed
// records) or across chain views of one process (Node), bounded in number and age. Losing them is always safe: the
// page then refuses what it cannot authenticate.

export const TX_STORE_LIMITS = {
  maxEntries: 512,                       // about 8 MB at the 16 KB transaction size limit
  maxAgeMs: 30 * 24 * 60 * 60 * 1000,    // 30 days
};

/** A store in memory: { get(hash) -> cbor | null, put(hash, cbor) }, the oldest dropped beyond the bounds. */
export function memoryTxStore(limits = {}) {
  const { maxEntries, maxAgeMs } = { ...TX_STORE_LIMITS, ...limits };
  const entries = new Map();     // hash -> { cbor, at }, in insertion order
  const prune = (now) => {
    for (const [h, e] of entries) if (now - e.at > maxAgeMs) entries.delete(h);
    while (entries.size > maxEntries) entries.delete(entries.keys().next().value);
  };
  return {
    async get(hash, now = Date.now()) {
      const e = entries.get(hash);
      if (!e) return null;
      if (now - e.at > maxAgeMs) {
        entries.delete(hash);
        return null;
      }
      return e.cbor;
    },
    async put(hash, cbor, now = Date.now()) {
      entries.delete(hash);
      entries.set(hash, { cbor, at: now });
      prune(now);
    },
    get size() {
      return entries.size;
    },
  };
}

/** A store in IndexedDB (a browser page): the page's transactions survive a reload; the oldest dropped beyond the bounds. */
export function indexedDbTxStore(name = 'joust.transactions', limits = {}) {
  const { maxEntries, maxAgeMs } = { ...TX_STORE_LIMITS, ...limits };
  const open = () => new Promise((resolve, reject) => {
    const req = globalThis.indexedDB.open(name, 1);
    req.onupgradeneeded = () => req.result.createObjectStore('txs', { keyPath: 'hash' }).createIndex('at', 'at');
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  const run = async (mode, f) => {
    const db = await open();
    try {
      return await new Promise((resolve, reject) => {
        const t = db.transaction('txs', mode);
        let result = null;
        f(t.objectStore('txs'), (r) => { result = r; });
        t.oncomplete = () => resolve(result);
        t.onerror = () => reject(t.error);
        t.onabort = () => reject(t.error);
      });
    } finally {
      db.close();
    }
  };
  return {
    get: (hash, now = Date.now()) => run('readonly', (s, done) => {
      const q = s.get(hash);
      q.onsuccess = () => done(q.result && now - q.result.at <= maxAgeMs ? q.result.cbor : null);
    }),
    put: (hash, cbor, now = Date.now()) => run('readwrite', (s) => {
      s.put({ hash, cbor, at: now });
      // Beyond the bounds, the oldest go: by age, then by number.
      const count = s.count();
      count.onsuccess = () => {
        let excess = count.result - maxEntries;
        const cursor = s.index('at').openCursor();
        cursor.onsuccess = () => {
          const c = cursor.result;
          if (!c) return;
          if (excess > 0 || now - c.value.at > maxAgeMs) {
            c.delete();
            excess--;
            c.continue();
          }
        };
      };
    }),
  };
}

// One store per process where there is no IndexedDB (Node): every chain view of the process shares it.
let processStore = null;

/** IndexedDB where the page has it, else the process's memory store. */
export function defaultTxStore() {
  if (globalThis.indexedDB) return indexedDbTxStore();
  processStore ??= memoryTxStore();
  return processStore;
}
