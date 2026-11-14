// ADR-0021 D4b: one input policy for every file the language server reads.
//   1. lexical confinement first (paths.js): a refused path is never stat'ed, opened, resolved or read;
//   2. symlinks are resolved by hand, one segment at a time: a link's target is read with readlink and checked as text
//      the same way before it is followed (an absolute target, or one leaving the folder, is refused there);
//   3. then the real path must still lie inside the folder (segment comparison), the file is opened non-blocking (a
//      FIFO cannot block the open), checked with fstat ON THE DESCRIPTOR (regular files only), and read up to cap + 1
//      bytes (anything longer is "too large");
//   4. and the file opened must be the file checked (reviews C1 and C11 on #83: a checked file, or one of its
//      directories, can be swapped for a symlink between the check and the open):
//      - where the OS names an open descriptor's file (Linux: /proc/self/fd/N), that name, which no path re-resolution
//        produces, must lie inside the folder: no window is left;
//      - elsewhere (macOS, Windows: Node has no descriptor path and no openat), after the open the path's real path is
//        resolved again, must lie inside the folder and name the descriptor's own file (same device and inode), and the
//        same check runs again after the read. A concurrent process can still win a sequence of precisely timed swaps;
//        it needs write access to the folder, with which it can write the engine file itself (§12 note 16).
//      (A hard link inside the folder is inside the folder.)
// All of it on the protocol thread with asynchronous fs (D2a): engine workers do no file I/O. The fs is injectable, so a
// test can record every call (D12, S14).
import { constants, promises as realFs } from 'node:fs';
import { join } from 'node:path';
import { inside, lexicalSegments } from './paths.js';

const MAX_LINKS = 32;

/**
 * The path the OS gives an open descriptor's file, or null where it gives none. Linux: /proc/self/fd/N names the file the
 * descriptor holds (" (deleted)" when it was unlinked: refused). Not re-resolved from the path the reader opened.
 */
export async function descriptorPath(fd) {
  if (process.platform !== 'linux') return null;
  try {
    const p = await realFs.readlink(`/proc/self/fd/${fd}`);
    return p.endsWith(' (deleted)') ? '' : p;
  } catch {
    return null;
  }
}

/**
 * The calls the reader makes: lstat, readlink, realpath, stat, open (each as fs.promises has it), and descriptorPath(fd)
 * (above; an fs without it gets the portable check).
 */
export function createReader(fs = { ...realFs, descriptorPath }) {
  /**
   * Resolves `rel` (text, relative to the folder `folder`, a real path) to a path inside it, following symlinks under
   * the rules above. `{ path, segments }` or `{ error }` (`refused`, `missing`, `outside`, `loop`).
   */
  async function resolveIn(folder, rel, { base = [] } = {}) {
    const first = lexicalSegments(rel, base);
    if (!first) return { error: 'refused' };
    let queue = first;
    let done = [];
    let links = 0;
    while (queue.length) {
      const [seg, ...rest] = queue;
      const here = join(folder, ...done, seg);
      let st;
      try {
        st = await fs.lstat(here);
      } catch {
        return { error: 'missing' };
      }
      if (st.isSymbolicLink()) {
        if (++links > MAX_LINKS) return { error: 'loop' };
        let target;
        try {
          target = await fs.readlink(here);
        } catch {
          return { error: 'missing' };
        }
        const next = lexicalSegments(target, done);
        if (!next) return { error: 'refused' };
        queue = [...next, ...rest];
        done = [];
        continue;
      }
      done = [...done, seg];
      queue = rest;
      if (queue.length && !st.isDirectory()) return { error: 'missing' };
    }
    const path = join(folder, ...done);
    let real;
    try {
      real = await fs.realpath(path);
    } catch {
      return { error: 'missing' };
    }
    if (!inside(real, folder)) return { error: 'outside' };
    return { path: real, segments: done };
  }

  /**
   * The bytes of `rel` inside `folder`, at most `cap` of them: `{ bytes, path }`, or `{ error }` (the above, `not-a-file`,
   * `too-large`, `unreadable`). Directory symlinks on the way are followed only under the rules above.
   */
  async function read(folder, rel, { cap = 64 * 1024, base = [] } = {}) {
    const r = await resolveIn(folder, rel, { base });
    if (r.error) return r;
    let fh;
    try {
      fh = await fs.open(r.path, constants.O_RDONLY | (constants.O_NONBLOCK ?? 0));
    } catch {
      return { error: 'unreadable' };
    }
    try {
      const st = await fh.stat();
      if (!st.isFile()) return { error: 'not-a-file' };
      if (st.size > cap) return { error: 'too-large' };
      // 4. The descriptor's file is a file inside the folder.
      const named = fs.descriptorPath ? await fs.descriptorPath(fh.fd) : null;
      const anchored = named !== null && named !== undefined;
      if (anchored && (!named || !inside(named, folder))) return { error: named ? 'outside' : 'changed' };
      const samePath = async () => {
        let again;
        try {
          again = await fs.realpath(r.path);
        } catch {
          return 'changed';
        }
        if (!inside(again, folder)) return 'outside';
        let now;
        try {
          now = await fs.stat(again);
        } catch {
          return 'changed';
        }
        return now.dev === st.dev && now.ino === st.ino ? null : 'changed';
      };
      if (!anchored) {
        const e = await samePath();
        if (e) return { error: e };
      }
      const buf = Buffer.alloc(Math.min(cap + 1, st.size + 1));
      let n = 0;
      while (n < buf.length) {
        const { bytesRead } = await fh.read(buf, n, buf.length - n, n);
        if (bytesRead === 0) break;
        n += bytesRead;
      }
      if (n > cap) return { error: 'too-large' };
      if (!anchored) {
        const e = await samePath();
        if (e) return { error: e };
      }
      return { bytes: buf.subarray(0, n), path: r.path };
    } catch {
      return { error: 'unreadable' };
    } finally {
      await fh.close().catch(() => {});
    }
  }

  /** A small JSON object (at most 64 KiB) inside the folder, or undefined. */
  async function readJson(folder, rel, opts) {
    const r = await read(folder, rel, { cap: 64 * 1024, ...opts });
    if (r.error) return undefined;
    try {
      const v = JSON.parse(r.bytes.toString('utf8'));
      return v && typeof v === 'object' && !Array.isArray(v) ? v : undefined;
    } catch {
      return undefined;
    }
  }

  /** Whether `rel` names a regular file inside the folder (resolved under the same rules; nothing read). */
  async function isFile(folder, rel, opts) {
    const r = await resolveIn(folder, rel, opts);
    if (r.error) return false;
    try {
      return (await fs.lstat(r.path)).isFile();
    } catch {
      return false;
    }
  }

  return { resolveIn, read, readJson, isFile };
}
