// The playground in a Gradle project (ADR-0024 M2, D1, D3, F11): the client side of the JVM dev host's launch
// (docs/protocol/dev-host-1.md, "Launch and framing", normative for the client).
//
// - `./gradlew build joustHostSpec` writes build/joust/host-spec.json: the project's JDK, the host's main class, the
//   ordered launch entries (directories with each file's SHA-256, jars with theirs) and their digest. Gradle's own gates
//   run there (checkJscAbi in `check`, the lock check before compileJava); the playground never accepts a change. The
//   wrapper is the build's own, found only through directories this user trusts (the native joust's rule).
// - The spec is read once (a bounded regular file, never through a link, never blocking) and validated strictly; the
//   JDK it names must be a JDK 25+ (its release file). Every entry is copied into a private, read-only snapshot (regular
//   files only, opened without following links and without blocking), each file's hash and the digest are checked
//   against the copied bytes, and the JVM host is launched on the snapshot with exactly the protocol's arguments and
//   environment allow-list. The snapshot is kept until the host exits, then deleted.
// - The page's anchors (descriptions and compiled scripts, client signing rule 2) are read from the same snapshot,
//   sibling jars included, never from the host.
// - Freshness (I6) is decided against the exact spec bytes and manifest the copy was verified against: the spec's bytes,
//   every launch directory's inventory and contents, and each jar (hashed when it is the build's own, by its file
//   identity when it is a dependency in Gradle's cache). A build that changed while it was copied is copied again.

import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  accessSync, chmodSync, closeSync, constants, fstatSync, lstatSync, mkdirSync, mkdtempSync, openSync, readdirSync,
  readSync, realpathSync, rmSync, statSync, writeFileSync, writeSync,
} from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { basename, delimiter, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { SETTINGS_FILES, isGradle, isNpmRoot, trusted } from '../project.js';
import { launchHost } from './host.js';
import { manifestOf, readJar } from './jar.js';

export const SPEC_PATH = 'build/joust/host-spec.json';
export const MAIN_CLASS = 'org.julclang.state.host.DevHost';
/** The protocol's JVM arguments before the class path, in order. */
export const JVM_ARGS = ['-XX:+DisplayVMOutputToStderr', '-Xlog:disable', '-Xlog:all=warning:stderr',
  '--enable-native-access=ALL-UNNAMED'];
/** The JDK the dev host needs (ADR-0022 D3). */
export const MIN_JAVA = 25;
export const SNAPSHOT_PREFIX = 'joust-playground-snapshot-';
const MAX_FILES = 200_000;
const MAX_SPEC = 64 * 1024 * 1024;
const DAY_MS = 24 * 3600 * 1000;
const HEX64 = /^[0-9a-f]{64}$/;
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f]/;

/** A launch spec or snapshot the playground refuses: the message says what to do. */
export class GradleLaunchError extends Error {}
const fail = (m, extra = {}) => { throw Object.assign(new GradleLaunchError(m), extra); };
const sha = (b) => createHash('sha256').update(b).digest('hex');
const utf8Order = (a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b));
const within = (root, path) => {
  const rel = relative(root, path);
  return !(isAbsolute(rel) || rel === '..' || rel.startsWith('..' + sep));
};

// ---- the project

/**
 * A Gradle project directory (a build or settings file), unless it is an npm project: joust.config.json or a
 * package.json depending on @julclang/joust (npm wins in one directory, as the native joust and bin/joust.js decide).
 * An unrelated package.json (a frontend's) does not make a Gradle project an npm one.
 */
export function isGradleProject(root) {
  return !isNpmRoot(root) && isGradle(root);
}

/**
 * Why Windows' cmd.exe would read {@code path} as more than a path (its metacharacters), or null. The wrapper is run as
 * `cmd.exe /d /c <gradlew.bat> <tasks>` there, so such a path is refused rather than quoted.
 */
export function windowsCommandProblem(path) {
  const m = /[&^%()!"<>|]/.exec(path);
  return m ? `${path} contains ${JSON.stringify(m[0])}, which cmd.exe would interpret: move the project to a path without & ^ % ( ) ! " < > |` : null;
}

const present = (p) => {
  try {
    lstatSync(p);
    return true;
  } catch {
    return false;
  }
};
const othersWrite = (st) => (st.mode & 0o022) !== 0;
/** {@code s} as one POSIX shell word: single-quoted, an embedded quote closed, escaped and reopened. */
export const shellQuote = (s) => `'${s.replaceAll("'", "'\\''")}'`;
const listed = (dir, env) => (env.JOUST_SAFE_DIRS ?? '').split(delimiter).filter((d) => d && isAbsolute(d)).map((d) => resolve(d))
  .includes(resolve(dir));

/**
 * The build's root and its wrapper, checked so that no other account can change what Gradle runs:
 * - The root is the nearest directory at or above {@code root} holding a settings file (any of SETTINGS_FILES, in
 *   Gradle 9's order; a link counts, Gradle follows it), walking only through this user's directories (or those
 *   listed in JOUST_SAFE_DIRS), never to the filesystem root (the native joust's Locator.gradleBuildRoot). Gradle
 *   searches upwards itself, so a build without its own settings file is refused, and one whose settings file sits in
 *   a directory the walk may not enter is named.
 * - Exactly one settings candidate, a regular file (not a link); the wrapper a regular, executable file (not a link).
 * - Unless the root is listed in JOUST_SAFE_DIRS: the settings file and the wrapper are this user's (or root's) and not
 *   writable by others. Each directory walked (the project up to the root) that is not itself listed is not writable
 *   by others either. Every such path is named in one refusal, with one chmod line to fix them all (umask 002).
 */
export function gradleBuild(root, { env = process.env, isTrusted = trusted, platform = process.platform } = {}) {
  const project = realpathSync(root);
  const stop = typeof process.getuid === 'function' ? undefined : resolve(homedir());
  const walked = [];
  let buildRoot = null;
  let stoppedAt = null;
  for (let d = project; dirname(d) !== d; d = dirname(d)) {
    if (!isTrusted(d, env)) {
      stoppedAt = d;
      break;
    }
    walked.push(d);
    if (SETTINGS_FILES.some((f) => present(join(d, f)))) {
      buildRoot = d;
      break;
    }
    if (d === stop) break;
  }
  if (!buildRoot) {
    if (stoppedAt && SETTINGS_FILES.some((f) => present(join(stoppedAt, f)))) {
      let owner = '';
      try {
        owner = ` (owned by uid ${lstatSync(stoppedAt).uid})`;
      } catch {
        // unreadable
      }
      fail(`${stoppedAt} holds the build's settings file but is not yours${owner}: list it in JOUST_SAFE_DIRS if you trust it`);
    }
    fail(`no settings.gradle at or above ${project} in a directory you own (or list in JOUST_SAFE_DIRS): Gradle would look`
      + ' further up for one itself; add a settings.gradle to the build');
  }
  // Gradle follows links, so a link (or more than one candidate) is refused.
  const candidates = SETTINGS_FILES.map((f) => {
    try {
      return { f, st: lstatSync(join(buildRoot, f)) };
    } catch {
      return null;
    }
  }).filter(Boolean);
  if (candidates.length > 1) fail(`${buildRoot} has ${candidates.map((c) => c.f).join(' and ')}: keep one`);
  const settings = join(buildRoot, candidates[0].f);
  if (!candidates[0].st.isFile() || candidates[0].st.isSymbolicLink()) {
    fail(`${settings} is not a regular file (a link, say): Gradle would evaluate whatever it points to`);
  }
  const gradlew = join(buildRoot, platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  let wrapper;
  try {
    wrapper = lstatSync(gradlew);
  } catch {
    fail(`no executable Gradle wrapper at ${gradlew}: the playground runs the build's own ./gradlew, never a gradle from PATH`);
  }
  if (wrapper.isSymbolicLink()) fail(`${gradlew} is a link: the playground runs the build's own wrapper, a regular file, not what a link points to`);
  try {
    if (!wrapper.isFile()) throw new Error();
    if (platform !== 'win32') accessSync(gradlew, constants.X_OK);
  } catch {
    fail(`no executable Gradle wrapper at ${gradlew}: the playground runs the build's own ./gradlew, never a gradle from PATH`);
  }
  if (platform === 'win32') {
    const problem = windowsCommandProblem(gradlew);
    if (problem) fail(problem);
  } else {
    // Listing the root exempts the settings file and the wrapper; each directory walked is checked unless it is listed
    // itself (listing the root does not vouch for the directories below it).
    const rootListed = listed(buildRoot, env);
    if (!rootListed) {
      for (const [file, st] of [[settings, candidates[0].st], [gradlew, wrapper]]) {
        const ownerOk = typeof process.getuid !== 'function' || st.uid === process.getuid() || st.uid === 0;
        if (!ownerOk) fail(`${file} belongs to another user (uid ${st.uid}): the build's settings and wrapper must be yours, or list ${buildRoot} in JOUST_SAFE_DIRS`);
      }
    }
    const dirs = walked.filter((d) => !listed(d, env) && othersWrite(statSync(d)));
    const files = rootListed ? [] : [[settings, candidates[0].st], [gradlew, wrapper]].filter(([, st]) => othersWrite(st)).map(([f]) => f);
    const writable = [...dirs, ...files];
    if (writable.length) {
      const toList = [...new Set([...dirs, ...(files.length ? [buildRoot] : [])])];
      fail(`${writable.join(', ')} ${writable.length > 1 ? 'are' : 'is'} writable by others, so another account could change`
        + ` what Gradle runs. Fix (on a umask 002 system, say): chmod go-w ${writable.map(shellQuote).join(' ')}`
        + ` (or list ${toList.join(' and ')} in JOUST_SAFE_DIRS)`);
    }
  }
  return { project, buildRoot, gradlew };
}

/**
 * Runs the build's wrapper in the project directory (which selects the project), streaming its output to {@code log}.
 * Resolves when it succeeds; a failure rejects with its exit code.
 */
export function runGradle({ project, gradlew }, tasks, { log = console.log } = {}) {
  return new Promise((resolvePromise, reject) => {
    if (process.platform === 'win32' && windowsCommandProblem(gradlew)) {
      reject(new GradleLaunchError(windowsCommandProblem(gradlew)));
      return;
    }
    const [cmd, args] = process.platform === 'win32' ? [process.env.ComSpec ?? 'cmd.exe', ['/d', '/c', gradlew, ...tasks]]
      : [gradlew, tasks];
    const child = spawn(cmd, args, { cwd: project, stdio: ['ignore', 'pipe', 'pipe'] });
    const lines = (b) => { for (const l of String(b).split(/\r?\n/)) if (l.trim()) log(`  gradle: ${l}`); };
    child.stdout.on('data', lines);
    child.stderr.on('data', lines);
    child.on('error', (e) => reject(new GradleLaunchError(`could not run ${gradlew}: ${e.message}`)));
    child.on('exit', (code, signal) => (code === 0 ? resolvePromise()
      : reject(new GradleLaunchError(`./gradlew ${tasks.join(' ')} failed (${signal ?? code}): see its output above`))));
  });
}

// ---- reading files

/**
 * The bytes and identity of the regular file at {@code path}: opened without following a link at its last component and
 * without blocking (a FIFO or device swapped in must not stall the event loop), then checked to be a regular file of
 * at most {@code max} bytes.
 */
export function readRegularFile(path, { max = Infinity } = {}) {
  let fd;
  try {
    fd = openSync(path, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0) | (constants.O_NONBLOCK ?? 0));
  } catch (e) {
    fail(`${path}: cannot be opened as a regular file (${e.code ?? e.message})`, { errno: e.code });
  }
  try {
    const st = fstatSync(fd);
    if (!st.isFile()) fail(`${path} is not a regular file`);
    if (st.size > max) fail(`${path} is larger than ${max} bytes`);
    const out = Buffer.allocUnsafe(st.size);
    let n = 0;
    while (n < st.size) {
      const r = readSync(fd, out, n, st.size - n, n);
      if (r === 0) break;
      n += r;
    }
    if (n !== st.size) fail(`${path} changed while it was read`);
    return { bytes: out, stat: { size: st.size, mtimeMs: st.mtimeMs, ino: st.ino, dev: st.dev } };
  } finally {
    closeSync(fd);
  }
}
export const readRegular = (path, o) => readRegularFile(path, o).bytes;

function writeReadOnly(path, bytes) {
  const fd = openSync(path, 'wx', 0o400);
  try {
    let n = 0;
    while (n < bytes.length) n += writeSync(fd, bytes, n, bytes.length - n);
  } finally {
    closeSync(fd);
  }
}

// ---- the spec

const keysAre = (o, keys, what) => {
  if (!o || typeof o !== 'object' || Array.isArray(o)) fail(`${what}: expected an object`);
  const extra = Object.keys(o).filter((k) => !keys.includes(k));
  const missing = keys.filter((k) => !(k in o));
  if (extra.length || missing.length) fail(`${what}: unexpected fields ${[...extra, ...missing.map((m) => '-' + m)].join(', ')}`);
};
function checkRel(rel, what) {
  if (typeof rel !== 'string' || !rel || CONTROL.test(rel) || rel.includes('\\') || rel.startsWith('/')
    || rel.split('/').some((s) => s === '' || s === '.' || s === '..')) fail(`${what}: the file name ${JSON.stringify(rel)} is not a plain relative path`);
}
function checkAbsolute(p, what) {
  if (typeof p !== 'string' || !isAbsolute(p) || CONTROL.test(p) || resolve(p) !== p) fail(`${what}: expected a normalized absolute path`);
}

/** The spec's own digest lines (the fingerprint format), from the hashes it lists. */
function digestOf(entries) {
  const lines = [];
  for (const e of entries) {
    if (e.kind === 'jar') lines.push(`${e.sha256} ${e.ordinal} jar \n`);
    else for (const rel of Object.keys(e.files).sort(utf8Order)) lines.push(`${e.files[rel]} ${e.ordinal} dir ${rel}\n`);
  }
  return sha(lines.join(''));
}

/** The spec's exact bytes, read once as a bounded regular file (never a FIFO, a device or a link). */
export function readSpecBytes(project) {
  try {
    return readRegular(join(project, SPEC_PATH), { max: MAX_SPEC });
  } catch (e) {
    if (e.errno === 'ENOENT') fail(`${SPEC_PATH} is missing: ./gradlew joustHostSpec writes it (is the JOUST Gradle plugin applied to this project?)`);
    throw e;
  }
}

/** Validates the bytes of build/joust/host-spec.json (protocol 1): { java, launch, digest, raw }. */
export function parseHostSpec(raw) {
  let spec;
  try {
    spec = JSON.parse(raw.toString('utf8'));
  } catch {
    fail(`${SPEC_PATH} is not JSON`);
  }
  keysAre(spec, ['protocol', 'java', 'mainClass', 'launch', 'digest'], SPEC_PATH);
  if (spec.protocol !== 1) fail(`${SPEC_PATH}: protocol ${JSON.stringify(spec.protocol)} (this playground speaks protocol 1)`);
  if (spec.mainClass !== MAIN_CLASS) fail(`${SPEC_PATH}: mainClass must be ${MAIN_CLASS}`);
  checkAbsolute(spec.java, `${SPEC_PATH}: java`);
  if (typeof spec.digest !== 'string' || !HEX64.test(spec.digest)) fail(`${SPEC_PATH}: digest must be 64 lowercase hex digits`);
  if (!Array.isArray(spec.launch) || !spec.launch.length) fail(`${SPEC_PATH}: launch must be a nonempty array`);
  let files = 0;
  spec.launch.forEach((e, i) => {
    const what = `${SPEC_PATH}: launch[${i}]`;
    keysAre(e, e?.kind === 'jar' ? ['ordinal', 'kind', 'path', 'sha256'] : ['ordinal', 'kind', 'path', 'files'], what);
    if (e.ordinal !== i) fail(`${what}: ordinal ${JSON.stringify(e.ordinal)}, expected ${i}`);
    if (e.kind !== 'dir' && e.kind !== 'jar') fail(`${what}: kind must be dir or jar`);
    checkAbsolute(e.path, what);
    if (e.kind === 'jar') {
      if (typeof e.sha256 !== 'string' || !HEX64.test(e.sha256)) fail(`${what}: sha256 must be 64 lowercase hex digits`);
      files++;
    } else {
      if (!e.files || typeof e.files !== 'object' || Array.isArray(e.files)) fail(`${what}: files must be an object`);
      for (const [rel, h] of Object.entries(e.files)) {
        checkRel(rel, what);
        if (typeof h !== 'string' || !HEX64.test(h)) fail(`${what}: the hash of ${rel} must be 64 lowercase hex digits`);
        files++;
      }
    }
  });
  if (files > MAX_FILES) fail(`${SPEC_PATH}: more than ${MAX_FILES} files`);
  if (digestOf(spec.launch) !== spec.digest) fail(`${SPEC_PATH}: its digest does not match the hashes it lists`);
  return { java: spec.java, launch: spec.launch, digest: spec.digest, raw };
}

/** Reads and validates build/joust/host-spec.json. */
export const readHostSpec = (project) => parseHostSpec(readSpecBytes(project));

/**
 * The real path of the spec's {@code java}, which must be a JDK's bin/java (bin/java.exe on Windows) whose release file
 * declares Java 25 or later (ADR-0022 D3): not any executable the spec names.
 */
export function checkJdk(java, { platform = process.platform } = {}) {
  let real;
  try {
    real = realpathSync(java);
  } catch {
    fail(`the project's JDK ${java} does not exist`);
  }
  const exe = platform === 'win32' ? 'java.exe' : 'java';
  if (basename(real) !== exe || basename(dirname(real)) !== 'bin') fail(`the spec's java ${java} is not a JDK's bin/${exe}`);
  try {
    if (!statSync(real).isFile()) throw new Error();
    if (platform !== 'win32') accessSync(real, constants.X_OK);
  } catch {
    fail(`the project's JDK ${java} is not an executable file`);
  }
  const home = dirname(dirname(real));
  let release;
  try {
    release = readRegular(join(home, 'release'), { max: 64 * 1024 }).toString('utf8');
  } catch {
    fail(`${home} has no release file: the spec's java is not a JDK`);
  }
  const m = /^JAVA_VERSION="([^"]+)"/m.exec(release);
  const parts = m ? m[1].split(/[.+_-]/) : [];
  const major = parts[0] === '1' ? Number(parts[1]) : Number(parts[0]);
  if (!(major >= MIN_JAVA)) {
    fail(`the project's JDK ${home} is Java ${m ? m[1] : '(unknown)'}: the dev host needs JDK ${MIN_JAVA} or later (ADR-0022 D3);`
      + ' set the project\'s Java toolchain to 25');
  }
  return real;
}

// ---- the snapshot

/** The real path of an entry, which must be the path the spec gives (it records real paths; no link is followed). */
function realEntry(e, what) {
  let st;
  try {
    st = lstatSync(e.path);
  } catch {
    fail(`${what}: ${e.path} is missing: run ./gradlew joustHostSpec again`, { changed: true });
  }
  if (st.isSymbolicLink()) fail(`${what}: ${e.path} is a symbolic link`);
  if (e.kind === 'dir' ? !st.isDirectory() : !st.isFile()) fail(`${what}: ${e.path} is not a ${e.kind === 'dir' ? 'directory' : 'regular file'}`);
  if (realpathSync(e.path) !== e.path) fail(`${what}: ${e.path} is not its real path`);
}

const RESOURCE = /^META-INF\/(?:joust\/[^/]+\.json|plutus\/[^/]+\.plutus\.json)$/;
const changed = (what) => fail(`${what} differs from ${SPEC_PATH}: the build changed since joustHostSpec; run ./gradlew joustHostSpec again`,
  { changed: true });

function unlock(dir) {
  try {
    const open = (d) => {
      chmodSync(d, 0o700);
      for (const n of readdirSync(d)) {
        const p = join(d, n);
        if (lstatSync(p).isDirectory()) open(p);
      }
    };
    open(dir);
  } catch {
    // removed below as far as possible
  }
  rmSync(dir, { recursive: true, force: true });
}

/**
 * Copies every launch entry into a new private, read-only directory and verifies it against the spec: each file's
 * hash, the entry kinds, no links, the jars' manifests, and the digest recomputed from the copied bytes.
 *
 * @returns { dir, classPath: [paths in order], digest,
 *            entries: [{ names: Set, resources: Map<name, Buffer>, multiRelease, stat }], dispose() }
 */
export function snapshotLaunch(spec, { base = tmpdir() } = {}) {
  const dir = realpathSync(mkdtempSync(join(base, SNAPSHOT_PREFIX)));
  chmodSync(dir, 0o700);
  const dispose = () => unlock(dir);
  try {
    // One class-path entry each: a path holding the platform's delimiter would split into others.
    if (dir.includes(delimiter)) fail(`the snapshot directory ${dir} contains the class-path delimiter ${JSON.stringify(delimiter)}: set TMPDIR to a path without it`);
    // The owner's pid: a sweep by another playground leaves a snapshot whose host still runs (sweepSnapshots).
    writeFileSync(join(dir, 'owner.pid'), String(process.pid), { mode: 0o400 });
    const lines = [];
    const classPath = [];
    const entries = [];
    for (const e of spec.launch) {
      const what = `launch entry ${e.ordinal}`;
      realEntry(e, what);
      const names = new Set();
      const resources = new Map();
      let multiRelease = false;
      let stat = null;
      if (e.kind === 'jar') {
        const read = readRegularFile(e.path);
        const bytes = read.bytes;
        stat = read.stat;
        const h = sha(bytes);
        if (h !== e.sha256) changed(`${what} (${e.path})`);
        const jar = readJar(bytes, e.path);
        const manifest = manifestOf(jar, e.path);
        if (manifest.classPath) fail(`${what}: ${e.path} has a manifest Class-Path, which the host refuses`);
        multiRelease = manifest.multiRelease;
        for (const n of jar.names) {
          names.add(n);
          if (RESOURCE.test(n)) resources.set(n, jar.read(n));
        }
        const target = join(dir, `${e.ordinal}.jar`);
        writeReadOnly(target, bytes);
        lines.push(`${h} ${e.ordinal} jar \n`);
        classPath.push(target);
      } else {
        const target = join(dir, String(e.ordinal));
        mkdirSync(target, { mode: 0o700 });
        const made = new Set([target]);
        for (const rel of Object.keys(e.files).sort(utf8Order)) {
          const parent = rel.includes('/') ? rel.slice(0, rel.lastIndexOf('/')) : '';
          // No link in the middle of the path either: the file's directory must be its own real path.
          if (parent && realpathSync(join(e.path, parent)) !== join(e.path, ...parent.split('/'))) fail(`${what}: ${parent} is reached through a link`);
          let bytes;
          try {
            bytes = readRegular(join(e.path, ...rel.split('/')));
          } catch (err) {
            if (err.errno === 'ENOENT') changed(`${what}: ${rel}`);
            throw err;
          }
          const h = sha(bytes);
          if (h !== e.files[rel]) changed(`${what}: ${rel}`);
          const out = join(target, ...rel.split('/'));
          const outDir = dirname(out);
          if (!made.has(outDir)) {
            mkdirSync(outDir, { recursive: true, mode: 0o700 });
            made.add(outDir);
          }
          writeReadOnly(out, bytes);
          names.add(rel);
          if (RESOURCE.test(rel)) resources.set(rel, bytes);
          lines.push(`${h} ${e.ordinal} dir ${rel}\n`);
        }
        classPath.push(target);
        // Read-only from the bottom up: the host may read the snapshot, nothing writes it.
        const lock = (d) => {
          for (const n of readdirSync(d)) {
            const p = join(d, n);
            if (lstatSync(p).isDirectory()) lock(p);
          }
          chmodSync(d, 0o500);
        };
        lock(target);
      }
      entries.push({ names, resources, multiRelease, stat });
    }
    const digest = sha(lines.join(''));
    if (digest !== spec.digest) changed('the snapshot\'s digest');
    return { dir, classPath, digest, entries, dispose };
  } catch (e) {
    dispose();
    throw e;
  }
}

const VERSIONED = /^META-INF\/versions\/\d+\/(.+)$/;
/**
 * Gradle's dependency cache, as this user's Gradle uses it (GRADLE_USER_HOME, else ~/.gradle), resolved: its modules
 * are immutable once written. Null when there is none.
 */
export function moduleCache(env = process.env) {
  try {
    return realpathSync(join(env.GRADLE_USER_HOME || join(homedir(), '.gradle'), 'caches/modules-2/files-2.1'));
  } catch {
    return null;
  }
}

/**
 * The contracts in a verified snapshot, as the npm build's contracts.json has them ({ description: JSON text, script }),
 * read as the JVM host reads them: each description `META-INF/joust/<name>.json` (the first copy in class-path order),
 * and its validator's script from beside the validator's class, in the one entry that holds it, refused when that entry
 * holds another class of the same simple name (ValidatorScripts' rule). A Multi-Release jar that versions a JOUST
 * resource or a validator class is refused: which copy the JVM loads depends on its version (KISS: not resolved here).
 */
export function snapshotContracts(entries) {
  for (const e of entries) {
    if (!e.multiRelease) continue;
    for (const n of e.names) {
      const v = VERSIONED.exec(n)?.[1];
      if (v && /^META-INF\/(?:joust|plutus)\//.test(v)) fail(`a Multi-Release jar versions the JOUST resource ${n}, which the playground does not resolve`);
    }
  }
  const out = [];
  const seen = new Set();
  for (const entry of entries) {
    for (const [name, bytes] of entry.resources) {
      if (!/^META-INF\/joust\/[^/]+\.json$/.test(name) || seen.has(name)) continue;
      seen.add(name);
      let desc;
      try {
        desc = JSON.parse(bytes.toString('utf8'));
      } catch {
        fail(`${name} in the snapshot is not JSON`);
      }
      const x = desc?.['x-joust'];
      if (!x) continue;
      const title = desc.preamble?.title;
      if (name !== `META-INF/joust/${title}.json`) fail(`${name}: the contract's title differs from its resource`);
      const validator = x.validator;
      const pkg = x.package ?? '';
      if (typeof validator !== 'string' || !/^[A-Za-z_$][\w$]*$/.test(validator)
        || typeof pkg !== 'string' || !/^(?:[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)?$/.test(pkg)) fail(`${name}: no validator class`);
      const classFile = (pkg ? pkg.replace(/\./g, '/') + '/' : '') + validator + '.class';
      for (const e of entries) {
        if (e.multiRelease && [...e.names].some((n) => VERSIONED.exec(n)?.[1] === classFile)) {
          fail(`${title}: a Multi-Release jar versions its validator ${classFile}, which the playground does not resolve`);
        }
      }
      const owner = entries.find((e) => e.names.has(classFile));
      if (!owner) fail(`${title}: its validator ${classFile} is not in the snapshot`);
      // As ValidatorScripts.Container.classesNamed counts them: every entry of that simple name, META-INF/versions included.
      const same = [...owner.names].filter((n) => n === `${validator}.class` || n.endsWith(`/${validator}.class`));
      if (same.length > 1) fail(`${title}: the entry holding ${classFile} holds ${same.length} classes named ${validator}, which share one script resource`);
      const plutus = owner.resources.get(`META-INF/plutus/${validator}.plutus.json`);
      if (!plutus) fail(`${title}: no compiled script META-INF/plutus/${validator}.plutus.json beside ${classFile}`);
      let script;
      // JSON.parse keeps the last of a repeated key, another reader may keep the first: one cborHex only.
      if ((plutus.toString('utf8').match(/"cborHex"/g) ?? []).length !== 1) fail(`${title}: its compiled script names cborHex other than once`);
      try {
        script = JSON.parse(plutus.toString('utf8')).cborHex;
      } catch {
        fail(`${title}: its compiled script is not JSON`);
      }
      if (typeof script !== 'string' || !/^(?:[0-9a-f]{2})+$/.test(script)) fail(`${title}: its compiled script has no cborHex`);
      out.push({ description: bytes.toString('utf8'), script });
    }
  }
  return out;
}

// ---- freshness (I6)

/** Every non-directory name under {@code dir}, relative, sorted as the spec sorts them (links and FIFOs included). */
function inventory(dir) {
  const out = [];
  const walk = (d, rel) => {
    for (const n of readdirSync(d)) {
      const p = join(d, n);
      if (lstatSync(p).isDirectory()) walk(p, rel + n + '/');
      else out.push(rel + n);
    }
  };
  walk(dir, '');
  return out.sort(utf8Order);
}

/**
 * Whether the live build is still exactly the one a snapshot was verified against: the spec's bytes, each launch
 * directory's inventory and contents (hashed, not by size and time), each jar of the build itself (under its root)
 * hashed, and each jar hashed too, except a module in Gradle's dependency cache (immutable), checked by its file identity
 * at the copy. Any doubt (a file gone, a link, a FIFO) counts as changed.
 */
export function isCurrent({ project, buildRoot, raw, spec, entries, cache = moduleCache() }) {
  try {
    if (!readSpecBytes(project).equals(raw)) return false;
    for (const [i, e] of spec.launch.entries()) {
      const st = lstatSync(e.path);
      if (st.isSymbolicLink()) return false;
      if (e.kind === 'dir') {
        const names = inventory(e.path);
        const want = Object.keys(e.files).sort(utf8Order);
        if (names.length !== want.length || names.some((n, k) => n !== want[k])) return false;
        for (const rel of want) if (sha(readRegular(join(e.path, ...rel.split('/')))) !== e.files[rel]) return false;
      } else if (!cache || within(buildRoot, e.path) || !within(cache, e.path)) {
        // The build's own jars, local file dependencies, composite builds' outputs, anything cache-shaped elsewhere: hashed.
        if (sha(readRegular(e.path)) !== e.sha256) return false;
      } else {
        // A module in Gradle's dependency cache (files-2.1/<group>/<module>/<version>/<sha1>/): immutable once written.
        const r = entries[i].stat;
        if (!r || st.size !== r.size || st.mtimeMs !== r.mtimeMs || st.ino !== r.ino || st.dev !== r.dev) return false;
      }
    }
    return true;
  } catch {
    return false;
  }
}

/**
 * Removes this user's snapshots older than a day whose playground is gone (a hard kill leaves one behind): only
 * directories (never a link) named as a snapshot, owned by this user, with no live owner process.
 */
export function sweepSnapshots({ base = tmpdir(), now = Date.now(), maxAgeMs = DAY_MS } = {}) {
  let names = [];
  try {
    names = readdirSync(base).filter((n) => n.startsWith(SNAPSHOT_PREFIX));
  } catch {
    return [];
  }
  const removed = [];
  for (const n of names) {
    const p = join(base, n);
    try {
      const st = lstatSync(p);
      if (!st.isDirectory() || st.isSymbolicLink()) continue;
      if (typeof process.getuid === 'function' && st.uid !== process.getuid()) continue;
      if (now - st.mtimeMs < maxAgeMs) continue;
      let pid = null;
      try {
        pid = Number(readRegular(join(p, 'owner.pid'), { max: 32 }).toString('utf8'));
      } catch {
        // no owner recorded
      }
      if (pid && pid !== process.pid) {
        try {
          process.kill(pid, 0);
          continue;    // its playground still runs
        } catch {
          // gone
        }
      }
      unlock(p);
      removed.push(p);
    } catch {
      // another sweep, or not ours
    }
  }
  return removed;
}

// ---- the host

/**
 * Launches the JVM host on a verified snapshot: the spec's JDK (checked, by its real path), from the project root, with
 * exactly the protocol's arguments, each passed directly to the executable, and the D3 environment allow-list.
 */
export function launchGradleHost({ java, classPath, project, onExit, deadlineMs, spawnHost } = {}) {
  const jdk = checkJdk(java);
  const bad = classPath.find((p) => p.includes(delimiter));
  if (bad) fail(`the class-path entry ${bad} contains the delimiter ${JSON.stringify(delimiter)}`);
  return launchHost({ command: jdk, args: [...JVM_ARGS, '-cp', classPath.join(delimiter), MAIN_CLASS, '--stdio'],
    cwd: project, onExit, deadlineMs, spawnHost });
}

/**
 * The build a Gradle project's sessions run (the shape of host.js's npmSource). The first open uses the build the
 * command just ran; a later open runs `./gradlew build joustHostSpec` again when the previous build is no longer current
 * (a rebuild: the gates run again). An open whose build changed while it was copied, or whose launch directories hold
 * other files than the spec lists, runs the build (once) and copies again.
 *
 * @param o.built     the command already ran the build (default true)
 * @param o.gatedSpec the spec's bytes right after that gated build (default: read now). A first open whose spec differs
 *                    (a bare `./gradlew joustHostSpec` since, which skips the gates) runs the build again.
 * @param o.gradle    (tasks) => Promise, for tests (default runGradle on the project's wrapper)
 * @param o.onCopied  () => void after each copy, for tests (a rebuild between the reads)
 */
export function gradleSource(root, { built = true, gatedSpec, gradle, log = console.log, base, launch = launchGradleHost,
  onCopied = () => {}, env, isTrusted } = {}) {
  const build = gradleBuild(root, { env, isTrusted });
  const cache = moduleCache(env);
  // The build root and its settings are resolved and checked again right before every wrapper run: a branch switch
  // may have removed the settings file since (Gradle would then search further up itself).
  const run = async (tasks) => {
    const now = gradleBuild(root, { env, isTrusted });
    return gradle ? gradle(tasks) : runGradle(now, tasks, { log });
  };
  sweepSnapshots({ base });
  const specNow = () => {
    try {
      return readSpecBytes(build.project);
    } catch {
      return null;
    }
  };
  // The spec bytes the last gated build (`build joustHostSpec`) wrote: a session runs only those.
  let gated = built ? (gatedSpec ?? specNow()) : null;
  let previous = null;     // () => whether the last opened build is still current
  let first = true;
  let queue = Promise.resolve();
  const open = async () => {
    let needBuild = first ? !built : !(previous?.() ?? false);
    first = false;
    let ran = false;
    for (let attempt = 0; attempt < 4; attempt++) {
      if (needBuild) {
        log('joust playground: the build changed: ./gradlew build joustHostSpec');
        await run(['build', 'joustHostSpec']);
        gated = specNow();
        needBuild = false;
        ran = true;
      }
      const raw = readSpecBytes(build.project);
      // Another spec than the gated build wrote (a bare joustHostSpec since): build again, gates included.
      if (!gated || !raw.equals(gated)) {
        needBuild = true;
        continue;
      }
      const spec = parseHostSpec(raw);
      const java = checkJdk(spec.java);
      let snap;
      try {
        snap = snapshotLaunch(spec, { base });
      } catch (e) {
        if (!e.changed) throw e;
        needBuild = !ran;
        continue;
      }
      onCopied();
      let entries;
      try {
        entries = snapshotContracts(snap.entries);
      } catch (e) {
        snap.dispose();
        throw e;
      }
      const current = () => isCurrent({ project: build.project, buildRoot: build.buildRoot, raw, spec, entries: snap.entries, cache });
      // Published only if the build is still the one copied: else a rebuild landed between the reads (copied again).
      if (!current()) {
        snap.dispose();
        needBuild = !ran;
        continue;
      }
      previous = current;
      let disposed = false;
      return {
        kind: 'gradle', root: build.project, digest: snap.digest, snapshot: snap.dir, entries,
        stale: () => !current(),
        launch: (o) => launch({ java, classPath: snap.classPath, project: build.project, ...o }),
        dispose() {
          if (disposed) return;
          disposed = true;
          snap.dispose();
        },
      };
    }
    fail('the build kept changing while the playground copied it: let Gradle finish, then start a new session');
  };
  return {
    kind: 'gradle',
    build,
    open() {
      const r = queue.then(open);
      queue = r.catch(() => {});
      return r;
    },
  };
}
