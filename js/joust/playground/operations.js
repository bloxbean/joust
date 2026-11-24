// Keyed operations (ADR-0016 M3; protocol 1 §Keyed calls), as plain functions so Node tests run what the page runs.
//
// Each keyed operation spends its own designated input, chosen and persisted with its record before the first request;
// every attempt is journaled (its checked hash and expiry) before the body leaves the page, a CIP-30 handoff included.
// Records are keyed by the verified chain (a DevKit's network, magic and genesis start time; an offline session's own
// ledger), never by the build: a rebuild does not hide a pending operation. On DevKit they live in IndexedDB, and a
// write that does not commit stops the call; offline they live in page memory (an offline ledger ends with its page).

export class OperationError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
    this.stage = 'journal';
  }
}

/** The chain a record belongs to: a DevKit's verified identity, or an offline session's ledger. */
export function chainKey(session) {
  if (session.mode === 'devkit') {
    return `devkit:${session.network.networkId}:${session.network.protocolMagic}:${session.chain?.startTime}`;
  }
  return `offline:${session.session}`;
}

export const operationId = (r) => [r.contract, r.method, r.instance ?? '', r.key ?? '', r.payer].join('|');

/** A slot as a safe JSON integer (records may hold bigints: a checked plan's validTo). */
export function safeSlot(v) {
  const n = typeof v === 'bigint' ? Number(v) : typeof v === 'string' && /^\d{1,16}$/.test(v) ? Number(v) : v;
  if (!Number.isSafeInteger(n) || n < 0) throw new OperationError('slot', `not a slot: ${String(v)}`);
  return n;
}

/** The record as the protocol's `call.operation` (JSON-safe, existing records' bigints included). */
export function operationRequest(r) {
  return { designated: r.designated, contract: r.contract, method: r.method, instance: r.instance ?? null, key: r.key ?? null,
    attempts: r.attempts.map(({ txHash, validTo }) => ({ txHash, validTo: safeSlot(validTo) })) };
}

/**
 * What the chain says of a record's attempts (after `mayHaveLanded`, or before choosing a new designation):
 *   closed     an attempt is on the chain: the operation is done
 *   pending    none is, but one can still land (its validTo + settlementSlots is not passed)
 *   ambiguous  none is and none can land any more: the designation went elsewhere, or an attempt landed unseen;
 *              the user must acknowledge before a new designation is chosen
 *   fresh      no attempt was handed out: something else spent the designation; choose a new one
 * {@code landed(txHash)}: true | false (the trusted chain's, or the offline ledger's history).
 */
export async function reconcile(record, { landed, tip, settlementSlots = 0 }) {
  if (!record.attempts.length) return { state: 'fresh' };
  for (const a of record.attempts) if (await landed(a.txHash)) return { state: 'closed', txHash: a.txHash };
  const live = record.attempts.filter((a) => safeSlot(a.validTo) + settlementSlots >= tip);
  return live.length ? { state: 'pending', until: Math.max(...live.map((a) => safeSlot(a.validTo) + settlementSlots)) } : { state: 'ambiguous' };
}

/** The store: page memory, or IndexedDB ({@code idb}) whose every write must commit (else OperationError). */
export function operationStore({ idb = null, name = 'joust-playground-operations' } = {}) {
  const memory = new Map();
  let opening = null;
  const fail = (what, e) => new OperationError('storage', `the keyed operation journal could not ${what}: ${e?.message ?? e ?? 'unknown error'}`);
  const open = () => {
    opening ??= new Promise((resolve, reject) => {
      try {
        const req = idb.open(name, 1);
        req.onupgradeneeded = () => req.result.createObjectStore('operations');
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(fail('be opened', req.error));
        req.onblocked = () => reject(fail('be opened (blocked)', null));
      } catch (e) {
        reject(fail('be opened', e));
      }
    });
    opening.catch(() => { opening = null; });
    return opening;
  };
  const tx = async (mode, fn, what) => {
    const db = await open();
    return new Promise((resolve, reject) => {
      let t;
      let r;
      try {
        t = db.transaction('operations', mode);
        r = fn(t.objectStore('operations'));
      } catch (e) {
        reject(fail(what, e));
        return;
      }
      t.oncomplete = () => resolve(r?.result ?? null);
      t.onerror = () => reject(fail(what, t.error));
      t.onabort = () => reject(fail(what, t.error));
    });
  };
  const plain = (r) => ({ ...r, attempts: r.attempts.map((a) => ({ txHash: a.txHash, validTo: safeSlot(a.validTo) })) });
  return {
    persistent: !!idb,
    async all(chain) {
      const rows = idb ? (await tx('readonly', (st) => st.getAll(), 'be read')) ?? [] : [...memory.values()];
      return rows.filter((r) => r.chain === chain);
    },
    async get(chain, id) { return (await this.all(chain)).find((r) => r.id === id) ?? null; },
    /** Resolves once the record is committed (IndexedDB), or throws. */
    async put(r) {
      const row = plain(r);
      if (idb) await tx('readwrite', (st) => st.put(row, `${row.chain}|${row.id}`), 'record the operation');
      memory.set(`${row.chain}|${row.id}`, row);
      return row;
    },
    async remove(r) {
      if (idb) await tx('readwrite', (st) => st.delete(`${r.chain}|${r.id}`), 'remove the operation');
      memory.delete(`${r.chain}|${r.id}`);
    },
  };
}
