// Which npm project a command is for, and which @julclang/joust it uses (ADR-0017 D4, D5): the same rules as the
// native joust. From the working directory upwards, the first directory that is an npm project root (joust.config.json,
// or a package.json depending on @julclang/joust) or part of a Gradle build decides; npm wins in one directory. The
// walk never enters a directory owned by another user (JOUST_SAFE_DIRS lists exceptions); where Node reports no uid
// (Windows), it stops at the user's profile directory. Manifests are read only as regular files of at most 64 KiB.
import { closeSync, constants, fstatSync, openSync, readSync, realpathSync, statSync } from 'node:fs';
import { homedir } from 'node:os';
import { delimiter, dirname, isAbsolute, join, resolve, sep } from 'node:path';

export const PACKAGE = '@julclang/joust';
export const MAX_BYTES = 64 * 1024;
export const VERSION_RE = /^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$/;

const isFile = (p) => {
  try {
    return statSync(p).isFile();
  } catch {
    return false;
  }
};

/** The JSON object in `file`, if it is a regular file (never a FIFO or device) of at most 64 KiB that parses. */
export function readSmallJson(file) {
  let fd;
  try {
    // O_NONBLOCK: opening a FIFO must not wait for a writer; fstat then rejects it.
    fd = openSync(file, constants.O_RDONLY | (constants.O_NONBLOCK ?? 0));
    const st = fstatSync(fd);
    if (!st.isFile() || st.size > MAX_BYTES) return undefined;
    const buf = Buffer.alloc(MAX_BYTES + 1);
    const n = readSync(fd, buf, 0, buf.length, 0);
    if (n > MAX_BYTES) return undefined;
    const v = JSON.parse(buf.subarray(0, n).toString('utf8'));
    return v && typeof v === 'object' && !Array.isArray(v) ? v : undefined;
  } catch {
    return undefined;
  } finally {
    if (fd !== undefined) closeSync(fd);
  }
}

export function isNpmRoot(dir) {
  if (isFile(join(dir, 'joust.config.json'))) return true;
  const pkg = readSmallJson(join(dir, 'package.json'));
  return !!pkg && ['dependencies', 'devDependencies'].some((k) => pkg[k] && typeof pkg[k] === 'object' && PACKAGE in pkg[k]);
}

export function isGradle(dir) {
  return ['settings.gradle', 'settings.gradle.kts', 'build.gradle', 'build.gradle.kts'].some((f) => isFile(join(dir, f)));
}

/** Whether the walk may enter `dir`: owned by this user, or listed in JOUST_SAFE_DIRS. */
export function trusted(dir, env = process.env) {
  const safe = (env.JOUST_SAFE_DIRS ?? '').split(delimiter).filter((d) => d && isAbsolute(d)).map((d) => resolve(d));
  if (safe.includes(resolve(dir))) return true;
  if (typeof process.getuid !== 'function') return true;   // Windows: bounded by the profile directory instead
  try {
    return statSync(dir).uid === process.getuid();
  } catch {
    return false;
  }
}

function* upwards(start, env) {
  const stop = typeof process.getuid === 'function' ? undefined : resolve(homedir());
  for (let d = resolve(start); ; d = dirname(d)) {
    if (!trusted(d, env)) return;
    yield d;
    if (d === stop || dirname(d) === d) return;
  }
}

/** `{ kind: 'npm'|'gradle', root }`, or undefined when no project is found. */
export function locate(cwd = process.cwd(), env = process.env) {
  for (const d of upwards(cwd, env)) {
    if (isNpmRoot(d)) return { kind: 'npm', root: d };
    if (isGradle(d)) return { kind: 'gradle', root: d };
  }
  return undefined;
}

/** The project's @julclang/joust as Node resolves it from `root`: `{ dir, bin, version }` (real paths), or undefined. */
export function resolveJoust(root, env = process.env) {
  for (const d of upwards(root, env)) {
    const pkgDir = join(d, 'node_modules', '@julclang', 'joust');
    const pkg = readSmallJson(join(pkgDir, 'package.json'));
    if (!pkg) continue;
    try {
      const real = realpathSync(pkgDir);
      const rel = typeof pkg.bin === 'string' ? pkg.bin : pkg.bin?.joust;
      if (typeof rel !== 'string') return undefined;
      const bin = realpathSync(resolve(real, rel));
      if (!bin.startsWith(real + sep) || !isFile(bin)) return undefined;
      const version = typeof pkg.version === 'string' && VERSION_RE.test(pkg.version) ? pkg.version : 'unknown';
      return { dir: real, bin, version };
    } catch {
      return undefined;
    }
  }
  return undefined;
}
