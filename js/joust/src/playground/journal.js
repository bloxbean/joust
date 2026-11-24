// The DevKit playground's journal (ADR-0024 D4, Q6; the client's duties of ADR-0022 D8, docs/protocol/dev-host-1.md
// "Reservation journal"): every body the playground handed out for signing, until its settlement is final, and the
// high-water mark (the greatest validTo + settlementSlots of those bodies), kept apart from it.
//
// It lives in a per-user JOUST state directory (default ~/.joust/state, JOUST_STATE_DIR overrides it), never in the
// project (I10) and never in DevKit's own files (F19). A chain is keyed by its network id, protocol magic and the
// DevKit's genesis startTime, so a reset DevKit (a new startTime) is a new chain with an empty journal: the old chain's
// entries are never reconciled against it. Directories of earlier chains of the same DevKit (its admin URL and magic)
// are reported once and removed, unless one has a lock on it.
//
//   <state>/chains/<networkId>-<protocolMagic>-<startTime>/chain.json      { networkId, protocolMagic, startTime, admin }
//   <state>/chains/<…>/<project key>.journal.json                          { version: 1, project, entries }
//   <state>/chains/<…>/<project key>.high-water.json                       { highWater }
//   <state>/chains/<…>/<project key>.lock                                  the owner's token: "<pid> <random>"
//   <state>/chains/<devkit key>.prune.lock, <devkit key>.notices.json      pruning, one at a time per DevKit
//
// Directories are 0700 and owned by this user (a group- or other-writable one of this user's is made 0700; anyone
// else's is refused); files are 0600, and every write goes to a temporary file that is renamed over the old one, after
// checking that this playground still owns the lock.

import { chmodSync, closeSync, existsSync, fsyncSync, linkSync, lstatSync, mkdirSync, openSync, readdirSync, readFileSync, renameSync,
  rmSync, unlinkSync, writeSync } from 'node:fs';
import { createHash, randomBytes } from 'node:crypto';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { parseTx } from '@julclang/state-client';

import { applyOperation, operationRecord } from './operations.js';

export const stateRoot = () => process.env.JOUST_STATE_DIR || join(homedir(), '.joust', 'state');
export const JOURNAL_VERSION = 1;

const isSlot = (n) => Number.isSafeInteger(n) && n >= 0;
/** A reservation id as the playground accepts it from a host: printable, bounded. */
export const RESERVATION = /^[\x21-\x7e]{1,128}$/;

function writeFile(file, text) {
  const tmp = `${file}.${randomBytes(6).toString('hex')}.tmp`;
  const fd = openSync(tmp, 'wx', 0o600);
  try {
    writeSync(fd, text);
    fsyncSync(fd);
  } finally {
    closeSync(fd);
  }
  return tmp;
}
const writeAtomic = (file, value) => renameSync(writeFile(file, JSON.stringify(value, null, 1) + '\n'), file);
const readJson = (file) => {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch {
    return undefined;
  }
};

function alive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return e.code === 'EPERM';
  }
}

/** M3-S2: a directory of this user's, not writable by others (made 0700 when it is this user's); else refused. */
function privateDir(dir) {
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  const st = lstatSync(dir);
  if (!st.isDirectory()) throw new Error(`journal: ${dir} is not a directory`);
  const uid = process.getuid?.();
  if (uid === undefined) return;   // Windows: the user's profile directory's ACLs apply
  if (st.uid !== uid) throw new Error(`journal: ${dir} belongs to another user: refusing to keep the playground's journal there`);
  if (st.mode & 0o077) chmodSync(dir, 0o700);
}

// ---- the lock: one owner per file, taken atomically, never taken over
//
// The owner's token ("<pid> <random>") is written to a private file first and hard-linked to the lock's name: link
// fails if the name exists, so a lock is never visible without its full token. A lock whose owner is gone is not taken
// over automatically (#179 r2: a takeover cannot be made safe against a delayed contender): it is refused with its path
// and how to remove it, as git does with index.lock. Every journal write checks that the lock still holds this
// owner's token.

function tryLink(lock, token) {
  const tmp = writeFile(lock, token);
  try {
    linkSync(tmp, lock);
    return true;
  } catch (e) {
    if (e.code !== 'EEXIST') throw e;
    return false;
  } finally {
    unlinkSync(tmp);
  }
}
const read = (file) => {
  try {
    return readFileSync(file, 'utf8');
  } catch (e) {
    if (e.code === 'ENOENT') return null;
    throw e;
  }
};
const pidOf = (token) => Number(String(token).split(' ')[0]);
const live = (token) => token !== null && Number.isSafeInteger(pidOf(token)) && pidOf(token) > 0 && alive(pidOf(token));

/**
 * Takes {@code lock} for this process and returns its token. Throws with code 'journalLocked' when a running process
 * holds it, and 'journalStaleLock' (naming the file to remove) when its owner is gone.
 */
export function acquire(lock, what = 'this journal') {
  const token = `${process.pid} ${randomBytes(12).toString('hex')}`;
  if (tryLink(lock, token)) return token;
  const held = read(lock);
  if (held === null && tryLink(lock, token)) return token;   // released meanwhile
  if (live(held)) {
    throw Object.assign(new Error(`another playground (pid ${pidOf(held)}) is using ${what}: stop it first`), { code: 'journalLocked' });
  }
  throw Object.assign(new Error(`the lock of ${what} (${lock}) belongs to pid ${pidOf(held)}, which is no longer running. If no `
    + `playground is using it, remove the lock and start again: rm '${lock}'`), { code: 'journalStaleLock', lock });
}
const owns = (lock, token) => {
  try {
    return readFileSync(lock, 'utf8') === token;
  } catch {
    return false;
  }
};
export function release(lock, token) {
  if (owns(lock, token)) unlinkSync(lock);
}

/** The project's key: a hash of its real path (the journal names no project path in its file name). */
export const projectKey = (root) => createHash('sha256').update(root).digest('hex').slice(0, 24);
const devkitKey = ({ admin, networkId, protocolMagic }) => createHash('sha256').update(`${admin} ${networkId} ${protocolMagic}`)
  .digest('hex').slice(0, 24);

/**
 * A valid journal entry, or null. The body is parsed: its hash and validity bounds must be the ones the entry records.
 */
function entry(e) {
  if (!e || typeof e !== 'object') return null;
  const ok = typeof e.reservation === 'string' && RESERVATION.test(e.reservation) && /^[0-9a-f]{64}$/.test(e.txHash ?? '')
    && /^(?:[0-9a-f]{2})+$/.test(e.cbor ?? '') && isSlot(e.validTo) && (e.validFrom === null || e.validFrom === undefined || isSlot(e.validFrom))
    && (e.state === 'prepared' || (e.state === 'shallow' && isSlot(e.slot)));
  if (!ok) return null;
  let tx;
  try {
    tx = parseTx(e.cbor);
  } catch {
    return null;
  }
  const validFrom = e.validFrom ?? null;
  if (tx.hash !== e.txHash || tx.ttl === null || Number(tx.ttl) !== e.validTo
    || (tx.validFrom === null ? validFrom !== null : Number(tx.validFrom) !== validFrom)) return null;
  return { reservation: e.reservation, txHash: e.txHash, cbor: e.cbor, validFrom, validTo: e.validTo, state: e.state,
    slot: e.state === 'shallow' ? e.slot : null, submitted: e.submitted === true, handedOff: e.handedOff === true,
    label: String(e.label ?? '').slice(0, 200) };
}

/**
 * Opens (and locks) the journal of the project at {@code root} (its real path) on {@code chain}.
 *
 * @param o.chain    { networkId, protocolMagic, startTime, admin }
 * @param o.served   async () => the chain the DevKit serves now ({ protocolMagic, startTime }), asked again under the
 *                   prune lock: a DevKit reset while this playground started never prunes the chain it reset to
 * @returns { dir, file, entries, highWater, lost, pruned, find, raise, record, update, remove, replace, clearNotices, close }
 *   lost: the journal is missing, unreadable or holds an invalid entry while a high-water mark exists (history was
 *   lost: the host's restore must not complete until an observed tip passes highWater); entries is then empty.
 */
export async function openJournal({ root, chain, state = stateRoot(), served = async () => chain }) {
  const { networkId, protocolMagic, startTime, admin } = chain;
  if (![networkId, protocolMagic, startTime].every(isSlot)) throw new Error('journal: the chain needs integer network id, magic and start time');
  privateDir(state);
  const chains = join(state, 'chains');
  privateDir(chains);

  const dir = join(chains, `${networkId}-${protocolMagic}-${startTime}`);
  const key = devkitKey(chain);
  const noticesFile = join(chains, `${key}.notices.json`);
  // Earlier chains of this DevKit (same admin API and magic, another startTime): one pruning at a time per DevKit,
  // after checking the DevKit still serves this chain; a directory with a lock on it is kept; the notice is
  // persisted before anything is deleted (a session that then fails to start still shows it next time).
  const pruneLock = join(chains, `${key}.prune.lock`);
  const pruneToken = acquire(pruneLock, 'this DevKit\'s journals (pruning)');
  try {
    const now = await served();
    if (now.protocolMagic !== protocolMagic || now.startTime !== startTime) {
      throw Object.assign(new Error('the DevKit was reset while the playground started: start it again'), { code: 'devkitReset' });
    }
    const notices = Array.isArray(readJson(noticesFile)) ? readJson(noticesFile) : [];
    for (const name of readdirSync(chains)) {
      const d = join(chains, name);
      const meta = readJson(join(d, 'chain.json'));
      if (!meta || meta.admin !== admin || meta.protocolMagic !== protocolMagic || meta.networkId !== networkId
        || meta.startTime === startTime) continue;
      const files = readdirSync(d);
      // A lock (live, or left by a playground that stopped: never taken over) keeps the chain's journals.
      if (files.some((f) => f.endsWith('.lock'))) continue;
      const transactions = files.filter((f) => f.endsWith('.journal.json')).reduce((n, f) => n + (readJson(join(d, f))?.entries?.length ?? 0), 0);
      notices.push({ startTime: meta.startTime, transactions });
      writeAtomic(noticesFile, notices);
      rmSync(d, { recursive: true, force: true });
    }
    privateDir(dir);
    if (!existsSync(join(dir, 'chain.json'))) writeAtomic(join(dir, 'chain.json'), { networkId, protocolMagic, startTime, admin });
  } finally {
    release(pruneLock, pruneToken);
  }
  const pruned = Array.isArray(readJson(noticesFile)) ? readJson(noticesFile) : [];

  const pkey = projectKey(root);
  const journalFile = join(dir, `${pkey}.journal.json`);
  const highFile = join(dir, `${pkey}.high-water.json`);
  const lockFile = join(dir, `${pkey}.lock`);
  // The page's keyed operation records (operations.js), on this chain, under the same lock.
  const opsFile = join(dir, `${pkey}.operations.json`);
  // One playground per project and chain: a second would settle the other's reservations.
  const token = acquire(lockFile, 'this project\'s journal on this DevKit');

  const high = readJson(highFile);
  let highWater = isSlot(high?.highWater) ? high.highWater : null;
  const raw = readJson(journalFile);
  // Validated as a whole: one bad entry, a wrong version or a missing file with a high-water mark is lost history.
  const parsed = raw?.version === JOURNAL_VERSION && Array.isArray(raw.entries) ? raw.entries.map(entry) : null;
  // #179 r2: each reservation once (two bodies under one id would share one chain observation).
  const intact = parsed !== null && parsed.every(Boolean) && new Set(parsed.map((e) => e.reservation)).size === parsed.length
    && (raw.removedAt === undefined || raw.removedAt === null || isSlot(raw.removedAt))
    && (raw.fencedUntil === undefined || raw.fencedUntil === null || isSlot(raw.fencedUntil));
  const lost = (raw !== undefined || highWater !== null) && !intact;
  // The journal's cur. Every change is written first and taken in memory only once the write succeeded (#179 r4): a
  // failed write changes nothing, so a retry writes again.
  let cur = {
    entries: intact ? parsed : [],
    // The highest chain point at which a body left the journal (final or expired): a chain found below it may let
    // that body land again.
    removedAt: intact && isSlot(raw.removedAt) ? raw.removedAt : null,
    // The one rewind fence (#179 r4): nothing is built until a tip strictly above it; clearing it moves removedAt to
    // the tip that cleared it, in the same write.
    fencedUntil: intact && isSlot(raw.fencedUntil) ? raw.fencedUntil : null,
  };
  let closed = false;
  const mine = () => {
    if (closed || !owns(lockFile, token)) {
      throw Object.assign(new Error('another playground took this project\'s journal on this DevKit'), { code: 'journalTaken' });
    }
  };
  const commit = (patch) => {
    mine();
    const next = { ...cur, ...patch };
    writeAtomic(journalFile, { version: JOURNAL_VERSION, project: root, ...next });
    cur = next;
  };
  const later = (a, b) => (a === null ? b : b === null ? a : Math.max(a, b));

  return {
    dir,
    file: journalFile,
    pruned,
    lost,
    get highWater() {
      return highWater;
    },
    get entries() {
      return cur.entries.map((e) => ({ ...e }));
    },
    get removedAt() {
      return cur.removedAt;
    },
    get fencedUntil() {
      return cur.fencedUntil;
    },
    find: (reservation) => {
      const e = cur.entries.find((x) => x.reservation === reservation);
      return e ? { ...e } : null;
    },
    /** Raises the high-water mark to {@code slot} (never lowers it); written before it is taken. */
    raise(slot) {
      if (!isSlot(slot)) throw new Error('journal: the high-water mark is a slot');
      if (highWater !== null && slot <= highWater) return;
      mine();
      writeAtomic(highFile, { highWater: slot });
      highWater = slot;
    },
    /** A handed-out body (state prepared); its reservation must be new. */
    record(e) {
      const v = entry({ ...e, state: e.state ?? 'prepared' });
      if (!v) throw new Error('journal: not a valid entry');
      if (cur.entries.some((x) => x.reservation === v.reservation)) throw new Error('journal: the reservation is already journaled');
      commit({ entries: [...cur.entries, v] });
    },
    update(reservation, patch) {
      const i = cur.entries.findIndex((e) => e.reservation === reservation);
      if (i < 0) return false;
      const v = entry({ ...cur.entries[i], ...patch });
      if (!v) throw new Error('journal: not a valid entry');
      commit({ entries: cur.entries.with(i, v) });
      return true;
    },
    /** Raises the rewind fence to {@code slot} (never lowers it). Throws if it could not be written. */
    fence(slot) {
      if (!isSlot(slot)) throw new Error('journal: the fence is a slot');
      if (cur.fencedUntil !== null && slot <= cur.fencedUntil) return;
      commit({ fencedUntil: slot });
    },
    /** Clears the fence at a tip strictly above it; removedAt moves to that tip in the same write. */
    clearFence(tipSlot) {
      if (cur.fencedUntil === null || !(tipSlot > cur.fencedUntil)) return false;
      commit({ fencedUntil: null, removedAt: later(cur.removedAt, tipSlot) });
      return true;
    },
    /**
     * Removes a body; {@code atSlot} is the chain point that settled it (final or expired), kept as the removed-history
     * point (a discarded body needs none: it was never signed).
     */
    remove(reservation, atSlot = null) {
      const entries = cur.entries.filter((e) => e.reservation !== reservation);
      if (entries.length === cur.entries.length) return;
      commit({ entries, removedAt: isSlot(atSlot) ? later(cur.removedAt, atSlot) : cur.removedAt });
    },
    /**
     * Replaces the whole journal (after a restore: the host's new reservation ids); nothing changes if one is invalid.
     * {@code atSlot}: the chain point at which history the restore dropped settled, saved in the same write.
     */
    replace(list, atSlot = null) {
      const next = list.map(entry);
      if (!next.every(Boolean) || new Set(next.map((e) => e.reservation)).size !== next.length) {
        throw new Error('journal: the restored entries are not valid');
      }
      commit({ entries: next, removedAt: isSlot(atSlot) ? later(cur.removedAt, atSlot) : cur.removedAt });
    },
    /**
     * The keyed operation records on this chain. No file yet: none. A file that cannot be read, another version or an
     * invalid record fails closed (code storage, naming the file): it is never read as empty, and never overwritten.
     */
    get operations() {
      let text;
      try {
        text = readFileSync(opsFile, 'utf8');
      } catch (e) {
        if (e.code === 'ENOENT') return [];
        throw Object.assign(new Error(`the keyed operations file ${opsFile} cannot be read (${e.code ?? e.message}): fix or move it`), { code: 'storage' });
      }
      let raw;
      try {
        raw = JSON.parse(text);
      } catch {
        raw = null;
      }
      const rows = raw?.version === JOURNAL_VERSION && Array.isArray(raw.operations) ? raw.operations.map(operationRecord) : null;
      if (rows === null || rows.some((r) => r === null)) {
        throw Object.assign(new Error(`the keyed operations file ${opsFile} is not one this playground wrote: fix or move it`), { code: 'storage' });
      }
      return rows;
    },
    /** One step on them (operations.js applyOperation), written atomically before it returns. */
    operate(step) {
      mine();
      const r = applyOperation(this.operations, step);
      writeAtomic(opsFile, { version: JOURNAL_VERSION, operations: r.rows });
      return r.record;
    },
    /** The prune notices were shown: they are cleared. */
    clearNotices() {
      try {
        unlinkSync(noticesFile);
      } catch {
        // none
      }
    },
    close() {
      if (closed) return;
      closed = true;
      release(lockFile, token);
    },
  };
}
