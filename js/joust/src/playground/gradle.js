// The playground in a Gradle project (ADR-0024 M2, D1, D3, F11): the client side of the JVM dev host's launch
// (docs/protocol/dev-host-1.md, "Launch and framing", normative for the client).
//
// - `./gradlew build joustHostSpec` writes build/joust/host-spec.json: the project's JDK, the host's main class, the
//   ordered launch entries (directories with each file's SHA-256, jars with theirs) and their digest. Gradle's own gates
//   run there (checkJscAbi in `check`, the lock check before compileJava); the playground never accepts a change.
// - The spec is validated strictly, every entry is copied into a private, read-only snapshot (regular files only, opened
//   without following links and without blocking), each file's hash and the digest are checked against the copied
//   bytes, and the JVM host is launched on the snapshot with exactly the protocol's arguments and environment
//   allow-list. The snapshot is kept until the host exits, then deleted.
// - The page's anchors (descriptions and compiled scripts, client signing rule 2) are read from the same snapshot,
//   sibling jars included, never from the host.
// - A session is stale (I6) once the launch entries or the spec change; a new session runs the build again first.

import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  accessSync, chmodSync, closeSync, constants, existsSync, fstatSync, lstatSync, mkdirSync, mkdtempSync, openSync,
  readdirSync, readFileSync, readSync, realpathSync, rmSync, statSync, writeSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, isAbsolute, join, resolve } from 'node:path';
import { launchHost } from './host.js';
import { manifestClassPath, readJar } from './jar.js';

export const SPEC_PATH = 'build/joust/host-spec.json';
export const MAIN_CLASS = 'org.julclang.state.host.DevHost';
/** The protocol's JVM arguments before the class path, in order. */
export const JVM_ARGS = ['-XX:+DisplayVMOutputToStderr', '-Xlog:disable', '-Xlog:all=warning:stderr',
  '--enable-native-access=ALL-UNNAMED'];
const MAX_FILES = 200_000;
const HEX64 = /^[0-9a-f]{64}$/;
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f]/;

/** A launch spec or snapshot the playground refuses: the message says what to do. */
export class GradleLaunchError extends Error {}
const fail = (m) => { throw new GradleLaunchError(m); };
const sha = (b) => createHash('sha256').update(b).digest('hex');
const utf8Order = (a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b));

// ---- the project

/** A Gradle project directory (a build file or a settings file), unless it is an npm project (npm wins, as the CLI). */
export function isGradleProject(root) {
  if (existsSync(join(root, 'package.json'))) return false;
  return ['build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts'].some((f) => existsSync(join(root, f)));
}

/** The build's root (the nearest directory at or above {@code root} with a settings file) and its wrapper. */
export function gradleBuild(root) {
  const project = realpathSync(root);
  let buildRoot = project;
  for (let d = project; ; d = dirname(d)) {
    if (existsSync(join(d, 'settings.gradle')) || existsSync(join(d, 'settings.gradle.kts'))) {
      buildRoot = d;
      break;
    }
    if (dirname(d) === d) break;
  }
  const gradlew = join(buildRoot, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  try {
    if (!statSync(gradlew).isFile()) throw new Error();
    if (process.platform !== 'win32') accessSync(gradlew, constants.X_OK);
  } catch {
    fail(`no executable Gradle wrapper at ${gradlew}: the playground runs the build's own ./gradlew, never a gradle from PATH`);
  }
  return { project, buildRoot, gradlew };
}

/**
 * Runs the build's wrapper in the project directory (which selects the project), streaming its output to {@code log}.
 * Resolves when it succeeds; a failure rejects with its exit code.
 */
export function runGradle({ project, gradlew }, tasks, { log = console.log } = {}) {
  return new Promise((resolvePromise, reject) => {
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

/** Parses and validates build/joust/host-spec.json (protocol 1): { java, launch, digest, raw }. */
export function readHostSpec(project) {
  const file = join(project, SPEC_PATH);
  let raw;
  try {
    raw = readFileSync(file);
  } catch {
    fail(`${SPEC_PATH} is missing: ./gradlew joustHostSpec writes it (is the JOUST Gradle plugin applied to this project?)`);
  }
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

// ---- the snapshot

/**
 * The bytes of the regular file at {@code path}: opened without following a link at its last component and without
 * blocking (a FIFO or device swapped in for an entry must not stall the launch), then checked to be a regular file.
 */
export function readRegular(path) {
  let fd;
  try {
    fd = openSync(path, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0) | (constants.O_NONBLOCK ?? 0));
  } catch (e) {
    fail(`${path}: cannot be opened as a regular file (${e.code ?? e.message})`);
  }
  try {
    const st = fstatSync(fd);
    if (!st.isFile()) fail(`${path} is not a regular file`);
    const out = Buffer.allocUnsafe(st.size);
    let n = 0;
    while (n < st.size) {
      const r = readSync(fd, out, n, st.size - n, n);
      if (r === 0) break;
      n += r;
    }
    if (n !== st.size) fail(`${path} changed while it was read`);
    return out;
  } finally {
    closeSync(fd);
  }
}

function writeReadOnly(path, bytes) {
  const fd = openSync(path, 'wx', 0o400);
  try {
    let n = 0;
    while (n < bytes.length) n += writeSync(fd, bytes, n, bytes.length - n);
  } finally {
    closeSync(fd);
  }
}

/** The real path of an entry, which must be the path the spec gives (it records real paths; no link is followed). */
function realEntry(e, what) {
  let st;
  try {
    st = lstatSync(e.path);
  } catch {
    fail(`${what}: ${e.path} is missing: run ./gradlew joustHostSpec again`);
  }
  if (st.isSymbolicLink()) fail(`${what}: ${e.path} is a symbolic link`);
  if (e.kind === 'dir' ? !st.isDirectory() : !st.isFile()) fail(`${what}: ${e.path} is not a ${e.kind === 'dir' ? 'directory' : 'regular file'}`);
  if (realpathSync(e.path) !== e.path) fail(`${what}: ${e.path} is not its real path`);
}

const RESOURCE = /^META-INF\/(?:joust\/[^/]+\.json|plutus\/[^/]+\.plutus\.json)$/;
const changed = (what) => fail(`${what} differs from ${SPEC_PATH}: the build changed since joustHostSpec; run ./gradlew joustHostSpec again`);

/**
 * Copies every launch entry into a new private, read-only directory and verifies it against the spec: each file's
 * hash, the entry kinds, no links, the jars' manifests, and the digest recomputed from the copied bytes.
 *
 * @returns { dir, classPath: [paths in order], digest, entries: [{ names: Set, resources: Map<name, Buffer> }], dispose() }
 */
export function snapshotLaunch(spec, { base = tmpdir() } = {}) {
  const dir = realpathSync(mkdtempSync(join(base, 'joust-playground-snapshot-')));
  chmodSync(dir, 0o700);
  const dispose = () => {
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
  };
  try {
    const lines = [];
    const classPath = [];
    const entries = [];
    for (const e of spec.launch) {
      const what = `launch entry ${e.ordinal}`;
      realEntry(e, what);
      const names = new Set();
      const resources = new Map();
      if (e.kind === 'jar') {
        const bytes = readRegular(e.path);
        const h = sha(bytes);
        if (h !== e.sha256) changed(`${what} (${e.path})`);
        const jar = readJar(bytes, e.path);
        if (manifestClassPath(jar)) fail(`${what}: ${e.path} has a manifest Class-Path, which the host refuses`);
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
          const bytes = readRegular(join(e.path, ...rel.split('/')));
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
      entries.push({ names, resources });
    }
    const digest = sha(lines.join(''));
    if (digest !== spec.digest) changed('the snapshot\'s digest');
    return { dir, classPath, digest, entries, dispose };
  } catch (e) {
    dispose();
    throw e;
  }
}

/**
 * The contracts in a verified snapshot, as the npm build's contracts.json has them ({ description: JSON text, script }),
 * read as the JVM host reads them: each description `META-INF/joust/<name>.json` (the first copy in class-path order),
 * and its validator's script from beside the validator's class, in the one entry that holds it, refused when that entry
 * holds another class of the same simple name (ValidatorScripts' rule).
 */
export function snapshotContracts(entries) {
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
      const owner = entries.find((e) => e.names.has(classFile));
      if (!owner) fail(`${title}: its validator ${classFile} is not in the snapshot`);
      const same = [...owner.names].filter((n) => n === `${validator}.class` || n.endsWith(`/${validator}.class`));
      if (same.length > 1) fail(`${title}: the entry holding ${classFile} holds ${same.length} classes named ${validator}, which share one script resource`);
      const plutus = owner.resources.get(`META-INF/plutus/${validator}.plutus.json`);
      if (!plutus) fail(`${title}: no compiled script META-INF/plutus/${validator}.plutus.json beside ${classFile}`);
      let script;
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

/**
 * The shared verifiers' scripts in a verified snapshot (ADR-0024 M4): JOUST's OutputVerifier and MapVerifier, read as
 * the JVM host anchors them, from the julc-state-verifier entry that holds their classes (the script beside the class).
 * A verifier the snapshot lacks is absent: calls that need it are then refused by the page's checks.
 */
export function snapshotVerifiers(entries) {
  const out = {};
  for (const [key, name] of [['outputVerifier', 'OutputVerifier'], ['mapVerifier', 'MapVerifier']]) {
    const owner = entries.find((e) => e.names.has(`org/julclang/state/verifier/${name}.class`));
    const plutus = owner?.resources.get(`META-INF/plutus/${name}.plutus.json`);
    if (!plutus) continue;
    let script;
    try {
      script = JSON.parse(plutus.toString('utf8')).cborHex;
    } catch {
      continue;
    }
    if (typeof script === 'string' && /^(?:[0-9a-f]{2})+$/.test(script)) out[key] = script;
  }
  return out;
}

/** What a rebuild changes, cheaply (no hashing): the spec's bytes and each original entry's names, sizes and mtimes. */
export function launchSignature(project) {
  const h = createHash('sha256');
  let raw;
  try {
    raw = readFileSync(join(project, SPEC_PATH));
  } catch {
    return 'missing';
  }
  h.update(raw);
  let launch = [];
  try {
    launch = JSON.parse(raw.toString('utf8')).launch ?? [];
  } catch {
    return 'malformed';
  }
  for (const e of launch) {
    try {
      const st = lstatSync(e.path);
      h.update(`\n${e.path} ${st.size} ${st.mtimeMs}`);
      if (st.isDirectory()) {
        const walk = (d, rel) => {
          for (const n of readdirSync(d).sort()) {
            const p = join(d, n);
            const s = lstatSync(p);
            if (s.isDirectory()) walk(p, rel + n + '/');
            else h.update(`\n${rel}${n} ${s.size} ${s.mtimeMs}`);
          }
        };
        walk(e.path, '');
      }
    } catch {
      h.update(`\n${e.path} missing`);
    }
  }
  return h.digest('hex');
}

/**
 * Launches the JVM host on a verified snapshot: the spec's JDK, from the project root, with exactly the protocol's
 * arguments, each passed directly to the executable, and the D3 environment allow-list.
 */
export function launchGradleHost({ java, classPath, project, onExit, deadlineMs, spawnHost } = {}) {
  try {
    if (!statSync(java).isFile()) throw new Error();
    if (process.platform !== 'win32') accessSync(java, constants.X_OK);
  } catch {
    fail(`the project's JDK ${java} is not an executable file`);
  }
  return launchHost({ command: java, args: [...JVM_ARGS, '-cp', classPath.join(delimiter), MAIN_CLASS, '--stdio'],
    cwd: project, onExit, deadlineMs, spawnHost });
}

/**
 * The build a Gradle project's sessions run (the shape of host.js's npmSource). The first open uses the build the
 * command just ran; a later open runs `./gradlew build joustHostSpec` again when the launch entries or the spec changed
 * since the previous open (a rebuild: the gates run again), then snapshots the spec it wrote.
 *
 * @param o.built     the command already ran the build (default true)
 * @param o.gradle    (tasks) => Promise, for tests (default runGradle on the project's wrapper)
 */
export function gradleSource(root, { built = true, gradle, log = console.log, base, launch = launchGradleHost } = {}) {
  const build = gradleBuild(root);
  const run = gradle ?? ((tasks) => runGradle(build, tasks, { log }));
  let last = built ? launchSignature(build.project) : null;
  let queue = Promise.resolve();
  const open = async () => {
    if (launchSignature(build.project) !== last) {
      log('joust playground: the build changed: ./gradlew build joustHostSpec');
      await run(['build', 'joustHostSpec']);
    }
    const spec = readHostSpec(build.project);
    const signature = launchSignature(build.project);
    const snap = snapshotLaunch(spec, { base });
    let entries;
    try {
      entries = snapshotContracts(snap.entries);
    } catch (e) {
      snap.dispose();
      throw e;
    }
    last = signature;
    let disposed = false;
    return {
      kind: 'gradle', root: build.project, digest: snap.digest, snapshot: snap.dir, entries, verifiers: snapshotVerifiers(snap.entries),
      stale: () => launchSignature(build.project) !== signature,
      launch: (o) => launch({ java: spec.java, classPath: snap.classPath, project: build.project, ...o }),
      dispose() {
        if (disposed) return;
        disposed = true;
        snap.dispose();
      },
    };
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

