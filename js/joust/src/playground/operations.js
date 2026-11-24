// Keyed operation records (ADR-0016 M3; protocol 1 §Keyed calls), kept by the playground server for its page: in the
// per-chain journal on DevKit (journal.js: beside the reservations, under the same lock, so they survive reloads, tabs
// and restarts of the command whatever the page's origin), in the session's memory offline (an offline ledger ends with
// its session). Every change is one declarative step applied atomically, so a stale tab cannot overwrite newer state:
// it can only add or remove its own attempt, or remove a record.

const HEX64 = /^[0-9a-f]{64}$/;
const REF = /^[0-9a-f]{64}#\d{1,5}$/;
const HEX = /^(?:[0-9a-f]{2}){0,64}$/;
const isSlot = (n) => Number.isSafeInteger(n) && n >= 0;
const text = (v, max = 512) => typeof v === 'string' && v.length > 0 && v.length <= max;

/** A record as the server keeps it, or null if it is not one. */
export function operationRecord(r) {
  if (!r || typeof r !== 'object') return null;
  const ok = text(r.id) && text(r.contract, 256) && text(r.method, 128) && text(r.payer, 256) && REF.test(r.designated ?? '')
    && (r.instance === null || (typeof r.instance === 'string' && HEX.test(r.instance)))
    && (r.key === null || (typeof r.key === 'string' && HEX.test(r.key)))
    && Array.isArray(r.attempts) && r.attempts.length <= 64
    && r.attempts.every((a) => a && HEX64.test(a.txHash ?? '') && isSlot(a.validTo))
    && (r.ambiguous === undefined || typeof r.ambiguous === 'boolean')
    && (r.landed === undefined || r.landed === null || HEX64.test(r.landed));
  if (!ok) return null;
  return { id: r.id, contract: r.contract, method: r.method, instance: r.instance, key: r.key, payer: r.payer, designated: r.designated,
    attempts: r.attempts.map((a) => ({ txHash: a.txHash, validTo: a.validTo })), ambiguous: r.ambiguous === true, landed: r.landed ?? null };
}

/**
 * One step on the records (a list): { action: 'create', record } (kept as it is when one exists: the existing one is
 * returned), 'addAttempt' { id, attempt }, 'removeAttempt' { id, txHash } (only that attempt), 'ambiguous' { id },
 * 'landed' { id, txHash } (kept with its landed attempt until removed), 'remove' { id }, 'release' { id } (removes it
 * only while it has no attempt). Returns { rows, record } (the record after the step, or null), or throws on a
 * malformed step.
 */
export function applyOperation(rows, step) {
  const at = rows.findIndex((r) => r.id === step?.id);
  const bad = (why) => Object.assign(new Error(`operations: ${why}`), { code: 'operation' });
  switch (step?.action) {
    case 'create': {
      const rec = operationRecord(step.record);
      if (!rec || rec.attempts.length) throw bad('not a new record');
      const existing = rows.find((r) => r.id === rec.id);
      if (existing) return { rows, record: existing };
      if (rows.length >= 256) throw bad('too many pending operations');
      if (rows.some((r) => r.designated === rec.designated)) throw bad('that input is designated for another operation');
      return { rows: [...rows, rec], record: rec };
    }
    case 'addAttempt': {
      if (at < 0) throw bad('no such operation');
      const a = step.attempt;
      if (!a || !HEX64.test(a.txHash ?? '') || !isSlot(a.validTo)) throw bad('not an attempt');
      const r = rows[at];
      const next = r.attempts.some((x) => x.txHash === a.txHash) ? r : { ...r, attempts: [...r.attempts, { txHash: a.txHash, validTo: a.validTo }] };
      return { rows: rows.with(at, next), record: next };
    }
    case 'removeAttempt': {
      if (at < 0) return { rows, record: null };
      const r = rows[at];
      const next = { ...r, attempts: r.attempts.filter((x) => x.txHash !== step.txHash) };
      return { rows: rows.with(at, next), record: next };
    }
    case 'ambiguous': {
      if (at < 0) return { rows, record: null };
      const next = { ...rows[at], ambiguous: true };
      return { rows: rows.with(at, next), record: next };
    }
    case 'landed': {
      // An attempt is on the chain: the record is kept with it until the user, having seen it, starts another (remove).
      if (at < 0) return { rows, record: null };
      if (!HEX64.test(step.txHash ?? '')) throw bad('not a transaction hash');
      const next = { ...rows[at], landed: step.txHash };
      return { rows: rows.with(at, next), record: next };
    }
    case 'remove':
      return { rows: at < 0 ? rows : rows.filter((_, i) => i !== at), record: null };
    case 'release':
      // Nothing of the operation left the page (a refused first prepare, a declined review): its designated input is
      // free again. A record with an attempt is kept (that attempt may land).
      if (at < 0 || rows[at].attempts.length) return { rows, record: at < 0 ? null : rows[at] };
      return { rows: rows.filter((_, i) => i !== at), record: null };
    default:
      throw bad('no such step');
  }
}

/** The records in memory (an offline session's). */
export function memoryOperations() {
  let rows = [];
  return {
    all: () => rows.map((r) => ({ ...r, attempts: [...r.attempts] })),
    apply(step) {
      const r = applyOperation(rows, step);
      rows = r.rows;
      return r.record;
    },
  };
}
