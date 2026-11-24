// Keyed operations (ADR-0016 M3; protocol 1 §Keyed calls), as plain functions so Node tests run what the page runs.
//
// Each keyed operation spends its own designated input, chosen and recorded before the first request; every attempt
// is recorded (its checked hash and expiry) before the body leaves the page, a CIP-30 handoff included. The playground
// server keeps the records (src/playground/operations.js: the chain's journal on DevKit, the session offline), so a
// reload, another tab or a restarted command sees them, and each change is one atomic step: a stale tab can only add or
// remove its own attempt.

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
 * What the chain says of a record's attempts (after `mayHaveLanded`, or before an existing record is used again):
 *   closed     an attempt is final on the chain: the operation is done (the page reports it and starts nothing)
 *   pending    an attempt is included but not final yet, or none is but one can still land, or the observed tip is not
 *              known: wait
 *   ambiguous  none is and none can land by the observed tip: the user must acknowledge before anything new
 *   fresh      no attempt was handed out
 * {@code lookup(txHash)}: { landed, tip } from the trusted chain, the tip observed with that lookup; the decision uses the
 * last tip observed after the lookups (never one read before them).
 */
export async function reconcile(record, { lookup, settlementSlots = 0 }) {
  if (!record.attempts.length) return { state: 'fresh' };
  let tip = null;
  for (const a of record.attempts) {
    const r = await lookup(a.txHash);
    tip = r?.tip ?? null;
    // Closed only when final (past the settlement margin): a shallow inclusion may roll back, so it waits, and is
    // looked up again on the next try (if it disappears, recovery goes on as before it was seen).
    if (r?.landed && r.final !== false) return { state: 'closed', txHash: a.txHash };
    if (r?.landed) return { state: 'pending', until: Number.isSafeInteger(r.slot) ? r.slot + settlementSlots : null, shallow: a.txHash };
  }
  if (tip === null || tip === undefined) return { state: 'pending', until: null };
  const live = record.attempts.filter((a) => safeSlot(a.validTo) + settlementSlots >= tip);
  return live.length ? { state: 'pending', until: Math.max(...live.map((a) => safeSlot(a.validTo) + settlementSlots)) } : { state: 'ambiguous' };
}

/**
 * What a keyed request does with an existing record, from its reconciliation: 'recovered' (an attempt landed, now or
 * recorded earlier: report it, every time, until the user starts another;
 * prepare nothing; another operation needs a separate run), 'reuse' (the same operation, the same designation), or
 * 'ambiguous' (wait for the user's acknowledgement). No existing record: 'new'.
 */
export function decide(existing, rec) {
  if (!existing) return 'new';
  if (existing.landed) return 'recovered';
  if (existing.ambiguous || rec?.state === 'ambiguous') return 'ambiguous';
  if (rec?.state === 'closed') return 'recovered';
  return 'reuse';
}

/** A record nothing of which left the page (closed at review, a refused prepare): its designation may be released. */
export const releasable = (r) => !r.landed && Array.isArray(r.attempts) && r.attempts.length === 0;

/**
 * Why a signer may not make a keyed call, or null. A connected CIP-30 wallet may rotate its change address between
 * tries, which keyed recovery (by operation and payer) cannot follow safely yet: keyed calls are for test identities.
 */
export function keyedRefusal(signer, keyed) {
  return keyed && signer?.cip30 ? 'unsupported: keyed calls from a connected wallet come later; use a test identity' : null;
}

/**
 * When a keyed operation's record is cleared after its call succeeded: offline at once (the ledger has no rollbacks);
 * on DevKit only at final ('final'), never at a shallow inclusion: until then a reload and a retry find the record and
 * reuse its designation and attempt.
 */
export function opDone(onDevkit, succeeded) {
  if (!succeeded) return 'keep';
  return onDevkit ? 'final' : 'clear';
}
