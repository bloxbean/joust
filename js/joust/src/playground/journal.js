// The DevKit playground's journal (ADR-0024 D4, Q6; the client's duties of ADR-0022 D8, docs/protocol/dev-host-1.md
// "Reservation journal"): every body the playground handed out for signing, until its settlement is final, and the
// high-water mark (the greatest validTo + settlementSlots of those bodies), kept apart from it.
//
// It lives in a per-user JOUST state directory (default ~/.joust/state, JOUST_STATE_DIR overrides it), never in the
// project (I10) and never in DevKit's own files (F19). A chain is keyed by its network id, protocol magic and the
// DevKit's genesis startTime, so a reset DevKit (a new startTime) is a new chain with an empty journal: the old chain's
// entries are never reconciled against it. Directories of earlier chains of the same DevKit (its admin URL and magic)
// are reported once and removed.
//
//   <state>/chains/<networkId>-<protocolMagic>-<startTime>/chain.json          { networkId, protocolMagic, startTime, admin }
//   <state>/chains/<…>/<project key>.journal.json                              { version, project, entries }
//   <state>/chains/<…>/<project key>.high-water.json                           { highWater }
//   <state>/chains/<…>/<project key>.lock                                      the pid of the playground using it
//
// Directories are 0700 and files 0600; every write goes to a temporary file that is renamed over the old one.

import { closeSync, existsSync, fsyncSync, mkdirSync, openSync, readdirSync, readFileSync, renameSync, rmSync, unlinkSync,
  writeSync } from 'node:fs';
import { createHash, randomBytes } from 'node:crypto';
import { homedir } from 'node:os';
import { join } from 'node:path';

export const stateRoot = () => process.env.JOUST_STATE_DIR || join(homedir(), '.joust', 'state');

const isSlot = (n) => Number.isSafeInteger(n) && n >= 0;

function writeAtomic(file, value) {
  const tmp = `${file}.${randomBytes(6).toString('hex')}.tmp`;
  const fd = openSync(tmp, 'wx', 0o600);
  try {
    writeSync(fd, JSON.stringify(value, null, 1) + '\n');
    fsyncSync(fd);
  } finally {
    closeSync(fd);
  }
  renameSync(tmp, file);
}
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

/** The project's key: a hash of its real path (the journal names no project path in its file name). */
export const projectKey = (root) => createHash('sha256').update(root).digest('hex').slice(0, 24);

/** A valid journal entry, or null. */
function entry(e) {
  if (!e || typeof e !== 'object') return null;
  const ok = typeof e.reservation === 'string' && /^[0-9a-f]{64}$/.test(e.txHash ?? '') && /^(?:[0-9a-f]{2})+$/.test(e.cbor ?? '')
    && isSlot(e.validTo) && (e.validFrom === null || e.validFrom === undefined || isSlot(e.validFrom))
    && (e.state === 'prepared' || (e.state === 'shallow' && isSlot(e.slot)));
  return ok ? { reservation: e.reservation, txHash: e.txHash, cbor: e.cbor, validFrom: e.validFrom ?? null, validTo: e.validTo,
    state: e.state, slot: e.state === 'shallow' ? e.slot : null, submitted: e.submitted === true, label: String(e.label ?? '').slice(0, 200) } : null;
}

/**
 * Opens (and locks) the journal of the project at {@code root} (its real path) on {@code chain}.
 *
 * @param o.chain  { networkId, protocolMagic, startTime, admin }
 * @returns { dir, entries, highWater, lost, pruned, record, update, remove, raise, close }
 *   lost: a high-water mark exists but the journal does not (or is unreadable): history was lost, so the host's restore
 *   must not complete until an observed tip passes highWater (the protocol's rule).
 */
export function openJournal({ root, chain, state = stateRoot() }) {
  const { networkId, protocolMagic, startTime, admin } = chain;
  if (![networkId, protocolMagic, startTime].every(isSlot)) throw new Error('journal: the chain needs integer network id, magic and start time');
  const chains = join(state, 'chains');
  mkdirSync(chains, { recursive: true, mode: 0o700 });

  // Earlier chains of this DevKit (same admin API and magic, another startTime): reported once, then removed.
  const pruned = [];
  for (const name of readdirSync(chains)) {
    const meta = readJson(join(chains, name, 'chain.json'));
    if (!meta || meta.admin !== admin || meta.protocolMagic !== protocolMagic || meta.networkId !== networkId
      || meta.startTime === startTime) continue;
    let transactions = 0;
    for (const f of readdirSync(join(chains, name))) {
      if (f.endsWith('.journal.json')) transactions += readJson(join(chains, name, f))?.entries?.length ?? 0;
    }
    pruned.push({ startTime: meta.startTime, transactions });
    rmSync(join(chains, name), { recursive: true, force: true });
  }

  const dir = join(chains, `${networkId}-${protocolMagic}-${startTime}`);
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  if (!existsSync(join(dir, 'chain.json'))) writeAtomic(join(dir, 'chain.json'), { networkId, protocolMagic, startTime, admin });
  const key = projectKey(root);
  const journalFile = join(dir, `${key}.journal.json`);
  const highFile = join(dir, `${key}.high-water.json`);
  const lockFile = join(dir, `${key}.lock`);

  // One playground per project and chain: a second would settle the other's reservations.
  for (let attempt = 0; ; attempt++) {
    try {
      const fd = openSync(lockFile, 'wx', 0o600);
      writeSync(fd, String(process.pid));
      closeSync(fd);
      break;
    } catch (e) {
      if (e.code !== 'EEXIST' || attempt > 0) throw e;
      const pid = Number(readFileSync(lockFile, 'utf8'));
      if (Number.isSafeInteger(pid) && pid > 0 && alive(pid)) {
        throw Object.assign(new Error(`another playground (pid ${pid}) is using this project's journal on this DevKit: stop it first`),
          { code: 'journalLocked' });
      }
      unlinkSync(lockFile);   // a stale lock of a process that is gone
    }
  }

  const high = readJson(highFile);
  let highWater = isSlot(high?.highWater) ? high.highWater : null;
  const raw = readJson(journalFile);
  const lost = highWater !== null && (raw === undefined || !Array.isArray(raw?.entries));
  let entries = Array.isArray(raw?.entries) ? raw.entries.map(entry).filter(Boolean) : [];
  const save = () => writeAtomic(journalFile, { version: 1, project: root, entries });
  let closed = false;

  return {
    dir,
    file: journalFile,
    pruned,
    lost,
    get highWater() {
      return highWater;
    },
    get entries() {
      return entries.map((e) => ({ ...e }));
    },
    find: (reservation) => entries.find((e) => e.reservation === reservation) ?? null,
    /** Raises the high-water mark to {@code slot} (never lowers it) and persists it first. */
    raise(slot) {
      if (!isSlot(slot)) throw new Error('journal: the high-water mark is a slot');
      if (highWater !== null && slot <= highWater) return;
      highWater = slot;
      writeAtomic(highFile, { highWater });
    },
    /** A handed-out body (state prepared). */
    record(e) {
      const v = entry({ ...e, state: e.state ?? 'prepared' });
      if (!v) throw new Error('journal: not a valid entry');
      entries = [...entries.filter((x) => x.reservation !== v.reservation), v];
      save();
    },
    update(reservation, patch) {
      const i = entries.findIndex((e) => e.reservation === reservation);
      if (i < 0) return;
      const v = entry({ ...entries[i], ...patch });
      if (!v) throw new Error('journal: not a valid entry');
      entries = entries.with(i, v);
      save();
    },
    remove(reservation) {
      const before = entries.length;
      entries = entries.filter((e) => e.reservation !== reservation);
      if (entries.length !== before) save();
    },
    /** Replaces the whole journal (after a restore: the host's new reservation ids). */
    replace(list) {
      entries = list.map(entry).filter(Boolean);
      save();
    },
    close() {
      if (closed) return;
      closed = true;
      try {
        if (Number(readFileSync(lockFile, 'utf8')) === process.pid) unlinkSync(lockFile);
      } catch {
        // already gone
      }
    },
  };
}
