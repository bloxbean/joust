// #121 S5: a file JOUST accepts (joust.lock, an ABI manifest) is written only as a regular file, never through a link: a
// link to a file outside the project would be overwritten, a dangling one would create a file there. A temporary file
// beside it, with its permissions (a new file: 0666 less the umask), is renamed over it.
import { chmodSync, lstatSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { randomBytes } from 'node:crypto';

/** Writes `text` to `file`; null when written, else why not ("is a link: …"). */
export function writeRegular(file, text) {
  let st = null;
  try {
    st = lstatSync(file);
  } catch (e) {
    if (e.code !== 'ENOENT') return `cannot be checked (${e.code})`;
  }
  if (st?.isSymbolicLink()) return 'is a link: replace the link with the file itself, then accept again';
  if (st && !st.isFile()) return 'is not a regular file: replace it with the file itself, then accept again';
  const tmp = join(dirname(file), `.${basename(file)}.${randomBytes(6).toString('hex')}.tmp`);
  try {
    // M2 on #121 (final pass): created private, then given the existing file's mode exactly (a mode passed to the
    // create is filtered by the umask). A new file gets the usual 0666 less the umask.
    writeFileSync(tmp, text, { flag: 'wx', mode: st ? 0o600 : 0o666 });
    if (st) chmodSync(tmp, st.mode & 0o7777);
    renameSync(tmp, file);
  } finally {
    rmSync(tmp, { force: true });
  }
  return null;
}
