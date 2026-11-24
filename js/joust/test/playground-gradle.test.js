// ADR-0024 M2: the playground in a Gradle project. The client side of the JVM dev host's launch (the spec, the
// private snapshot, the digest, the exact launch), the contracts read from the snapshot, staleness after a Gradle
// rebuild (I6), and, with a staged Maven repository of this commit and a joust CLI (CI's cli job), the Counter
// walkthrough in a Java and a .jsc Gradle project, compared step by step with the npm walkthrough (I11), and the native
// joust's playground end to end (its per-user cache, Q1).
//   JOUST_PLAYGROUND_GRADLE_REPO  a Maven repository with this commit's modules and plugin (publishAllPublicationsToStagingRepository)
//   JOUST_CLI                     a joust (the native binary, or bin/joust of :julc-state-cli:installDist)
//   JOUST_PLAYGROUND_TARBALLS     this commit's npm tarballs (state-client, joust-runtime, joust with its page built)
//   JOUST_REQUIRE_GRADLE          fail instead of skipping when the first two are missing
import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, statSync,
  symlinkSync, unlinkSync, utimesSync, writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, sep } from 'node:path';
import { crc32 } from 'node:zlib';
import {
  GradleLaunchError, JVM_ARGS, MAIN_CLASS, SNAPSHOT_PREFIX, SPEC_PATH, checkJdk, gradleBuild, gradleSource, isGradleProject,
  launchGradleHost, readHostSpec, snapshotContracts, snapshotLaunch, sweepSnapshots, windowsCommandProblem,
} from '../src/playground/gradle.js';
import { manifestOf, readJar } from '../src/playground/jar.js';
import { install, repo } from './host/client.js';
import { COUNTER, chromiumOrNull, walkthrough } from './playground/walkthrough.js';

const sha = (b) => createHash('sha256').update(b).digest('hex');
const order = (a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b));

/** A stored (uncompressed) zip: what the reader must read; {@code manifest} adds META-INF/MANIFEST.MF. */
function zip(files, { manifest } = {}) {
  const all = { ...(manifest ? { 'META-INF/MANIFEST.MF': manifest } : {}), ...files };
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, content] of Object.entries(all)) {
    const data = Buffer.from(content);
    const n = Buffer.from(name);
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4);
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(n.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6);
    central.writeUInt32LE(crc, 16); central.writeUInt32LE(data.length, 20); central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(n.length, 28); central.writeUInt32LE(offset, 42);
    locals.push(local, n, data);
    centrals.push(central, n);
    offset += 30 + n.length + data.length;
  }
  const cd = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(Object.keys(all).length, 8); end.writeUInt16LE(Object.keys(all).length, 10);
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, cd, end]);
}

const description = (title, pkg, validator) => JSON.stringify({ preamble: { title }, 'x-joust': { package: pkg, validator } });
const PLUTUS = JSON.stringify({ type: 'PlutusScriptV3', cborHex: '4e4d01000033222220051200120011' });

/** The spec joustHostSpec would write for these entries (the protocol's fingerprint format). */
/** A fake JDK: bin/java (a script) and a release file declaring {@code version}. Returns bin/java. */
function fakeJdk(dir, version = '25.0.1') {
  mkdirSync(join(dir, 'bin'), { recursive: true });
  writeFileSync(join(dir, 'bin/java'), '#!/bin/sh\nexit 0\n');
  chmodSync(join(dir, 'bin/java'), 0o755);
  writeFileSync(join(dir, 'release'), `IMPLEMENTOR="test"\nJAVA_VERSION="${version}"\n`);
  return realpathSync(join(dir, 'bin/java'));
}
const JDKS = realpathSync(mkdtempSync(join(tmpdir(), 'joust-pg-jdks-')));
const JAVA = fakeJdk(join(JDKS, 'jdk-25'));
after(() => rmSync(JDKS, { recursive: true, force: true }));

function specFor(entries, java = JAVA) {
  const launch = entries.map((e, ordinal) => {
    const path = realpathSync(e.path);
    if (e.kind === 'jar') return { ordinal, kind: 'jar', path, sha256: sha(readFileSync(path)) };
    const files = {};
    const walk = (d) => {
      for (const n of readdirSync(d)) {
        const p = join(d, n);
        if (statSync(p).isDirectory()) walk(p);
        else files[relative(path, p).split(sep).join('/')] = sha(readFileSync(p));
      }
    };
    walk(path);
    return { ordinal, kind: 'dir', path, files };
  });
  const lines = launch.flatMap((e) => (e.kind === 'jar' ? [`${e.sha256} ${e.ordinal} jar \n`]
    : Object.keys(e.files).sort(order).map((r) => `${e.files[r]} ${e.ordinal} dir ${r}\n`)));
  return { protocol: 1, java, mainClass: MAIN_CLASS, launch, digest: sha(lines.join('')) };
}

/** A fake Gradle project: classes (app.Counter), a sibling jar (lib.Other), a dependency jar; its spec written. */
function fakeProject() {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'joust-pg-gradle-')));
  writeFileSync(join(root, 'settings.gradle'), '');
  writeFileSync(join(root, 'build.gradle'), '');
  writeFileSync(join(root, 'gradlew'), '#!/bin/sh\nexit 0\n');
  chmodSync(join(root, 'gradlew'), 0o755);
  const classes = join(root, 'build/classes/java/main');
  mkdirSync(join(classes, 'app'), { recursive: true });
  mkdirSync(join(classes, 'META-INF/joust'), { recursive: true });
  mkdirSync(join(classes, 'META-INF/plutus'), { recursive: true });
  writeFileSync(join(classes, 'app/CounterValidator.class'), 'class');
  writeFileSync(join(classes, 'app/Counter.class'), 'class');
  writeFileSync(join(classes, 'META-INF/joust/app.Counter.json'), description('app.Counter', 'app', 'CounterValidator'));
  writeFileSync(join(classes, 'META-INF/plutus/CounterValidator.plutus.json'), PLUTUS);
  const sibling = join(root, 'sibling.jar');
  writeFileSync(sibling, zip({ 'lib/OtherValidator.class': 'class', 'META-INF/joust/lib.Other.json': description('lib.Other', 'lib', 'OtherValidator'),
    'META-INF/plutus/OtherValidator.plutus.json': PLUTUS }, { manifest: 'Manifest-Version: 1.0\r\n\r\n' }));
  const dep = join(root, 'dep.jar');
  writeFileSync(dep, zip({ 'dep/Thing.class': 'class' }));
  const entries = [{ kind: 'dir', path: classes }, { kind: 'jar', path: sibling }, { kind: 'jar', path: dep }];
  const writeSpec = (spec = specFor(entries)) => {
    mkdirSync(join(root, 'build/joust'), { recursive: true });
    writeFileSync(join(root, SPEC_PATH), JSON.stringify(spec));
    return spec;
  };
  writeSpec();
  return { root, classes, sibling, dep, entries, writeSpec };
}
const snapshots = (base) => readdirSync(base).filter((n) => n.startsWith('joust-playground-snapshot-'));

describe('ADR-0024 M2: the Gradle launch, client side', () => {
  const made = [];
  const project = () => {
    const p = fakeProject();
    made.push(p.root);
    return p;
  };
  const base = () => {
    const b = realpathSync(mkdtempSync(join(tmpdir(), 'joust-pg-base-')));
    made.push(b);
    return b;
  };
  after(() => {
    for (const d of made) {
      spawnSync('chmod', ['-R', 'u+w', d]);   // the read-only snapshots a test keeps
      rmSync(d, { recursive: true, force: true });
    }
  });

  test('a valid spec: a private read-only snapshot, the digest from the copied bytes, contracts from classes and a sibling jar', () => {
    const p = project();
    const b = base();
    const spec = readHostSpec(p.root);
    const snap = snapshotLaunch(spec, { base: b });
    try {
      assert.equal(snap.digest, spec.digest);
      assert.deepEqual(snap.classPath, [join(snap.dir, '0'), join(snap.dir, '1.jar'), join(snap.dir, '2.jar')]);
      assert.equal(statSync(snap.dir).mode & 0o777, 0o700);
      for (const d of ['0', '0/app', '0/META-INF', '0/META-INF/joust']) assert.equal(statSync(join(snap.dir, d)).mode & 0o777, 0o500, d);
      assert.equal(statSync(join(snap.dir, '0/app/Counter.class')).mode & 0o777, 0o400);
      assert.equal(statSync(join(snap.dir, '1.jar')).mode & 0o777, 0o400);
      assert.deepEqual(readFileSync(join(snap.dir, '1.jar')), readFileSync(p.sibling));
      const contracts = snapshotContracts(snap.entries);
      assert.deepEqual(contracts.map((c) => JSON.parse(c.description).preamble.title), ['app.Counter', 'lib.Other']);
      assert.ok(contracts.every((c) => c.script === JSON.parse(PLUTUS).cborHex));
    } finally {
      snap.dispose();
    }
    assert.deepEqual(snapshots(b), [], 'the snapshot is deleted');
  });

  test('the spec is validated strictly', () => {
    const p = project();
    const good = specFor(p.entries);
    const cases = [
      [{ ...good, extra: 1 }, /unexpected fields extra/],
      [{ ...good, protocol: 2 }, /protocol 2/],
      [{ ...good, mainClass: 'evil.Main' }, /mainClass/],
      [{ ...good, java: 'java' }, /java: expected a normalized absolute path/],
      [{ ...good, java: '/usr/bin/../bin/java' }, /normalized absolute path/],
      [{ ...good, launch: [] }, /nonempty/],
      [{ ...good, launch: [{ ...good.launch[1], ordinal: 1 }] }, /ordinal 1, expected 0/],
      [{ ...good, launch: [{ ...good.launch[1], kind: 'zip' }] }, /unexpected fields|kind/],
      [{ ...good, launch: [{ ...good.launch[0], files: { '../x.class': '0'.repeat(64) } }] }, /not a plain relative path/],
      [{ ...good, launch: [{ ...good.launch[0], files: { 'a//b': '0'.repeat(64) } }] }, /not a plain relative path/],
      [{ ...good, launch: [{ ...good.launch[0], files: { 'a\nb': '0'.repeat(64) } }] }, /not a plain relative path/],
      [{ ...good, launch: [good.launch[0], { ...good.launch[1], sha256: 'A'.repeat(64) }] }, /64 lowercase hex/],
      [{ ...good, digest: sha('another build') }, /does not match the hashes it lists/],
    ];
    for (const [spec, re] of cases) {
      p.writeSpec(spec);
      assert.throws(() => readHostSpec(p.root), (e) => e instanceof GradleLaunchError && re.test(e.message), re.source);
    }
    writeFileSync(join(p.root, SPEC_PATH), 'not json');
    assert.throws(() => readHostSpec(p.root), /not JSON/);
    rmSync(join(p.root, SPEC_PATH));
    assert.throws(() => readHostSpec(p.root), /missing: \.\/gradlew joustHostSpec writes it/);
  });

  test('a file that differs from the spec, or a tampered digest, is refused and leaves no snapshot', () => {
    const p = project();
    const b = base();
    writeFileSync(join(p.classes, 'app/Counter.class'), 'changed after joustHostSpec');
    assert.throws(() => snapshotLaunch(readHostSpec(p.root), { base: b }), /differs from build\/joust\/host-spec\.json.*joustHostSpec again/);
    assert.deepEqual(snapshots(b), []);
    // A jar replaced after the spec.
    const q = project();
    writeFileSync(q.dep, zip({ 'dep/Other.class': 'x' }));
    assert.throws(() => snapshotLaunch(readHostSpec(q.root), { base: b }), /launch entry 2 .* differs/);
    assert.deepEqual(snapshots(b), []);
  });

  test('links are never followed: a linked file, a linked jar, a linked directory on the way', () => {
    const b = base();
    const p = project();
    const target = join(p.root, 'elsewhere.class');
    writeFileSync(target, 'class');
    unlinkSync(join(p.classes, 'app/Counter.class'));
    symlinkSync(target, join(p.classes, 'app/Counter.class'));
    const spec = readHostSpec(p.root);   // the spec still lists the same hash
    assert.throws(() => snapshotLaunch(spec, { base: b }), /cannot be opened as a regular file|not a regular file/);
    const q = project();
    const spec2 = readHostSpec(q.root);
    const real = join(q.root, 'real.jar');
    cpSync(q.dep, real);
    unlinkSync(q.dep);
    symlinkSync(real, q.dep);
    assert.throws(() => snapshotLaunch(spec2, { base: b }), /is a symbolic link/);
    const r = project();
    const spec3 = readHostSpec(r.root);
    const moved = join(r.root, 'moved-app');
    cpSync(join(r.classes, 'app'), moved, { recursive: true });
    rmSync(join(r.classes, 'app'), { recursive: true });
    symlinkSync(moved, join(r.classes, 'app'));
    assert.throws(() => snapshotLaunch(spec3, { base: b }), /app is reached through a link/);
    assert.deepEqual(snapshots(b), []);
  });

  test('a FIFO in place of a file is refused at once, never read', { skip: process.platform === 'win32' && 'no FIFOs' }, () => {
    const b = base();
    const p = project();
    const spec = readHostSpec(p.root);
    unlinkSync(join(p.classes, 'app/Counter.class'));
    const made = spawnSync('mkfifo', [join(p.classes, 'app/Counter.class')]);
    assert.equal(made.status, 0, String(made.stderr));
    const t0 = Date.now();
    assert.throws(() => snapshotLaunch(spec, { base: b }), /not a regular file/);
    assert.ok(Date.now() - t0 < 2000, 'the open did not block');
  });

  test('a jar with a manifest Class-Path is refused', () => {
    const p = project();
    writeFileSync(p.dep, zip({ 'dep/Thing.class': 'class' }, { manifest: 'Manifest-Version: 1.0\r\nClass-Path: ../evil.jar\r\n\r\n' }));
    p.writeSpec();
    assert.throws(() => snapshotLaunch(readHostSpec(p.root), { base: base() }), /manifest Class-Path/);
  });

  test('the script comes from beside its validator class only', () => {
    const p = project();
    // A second class of the validator's simple name in the same entry: the script resource cannot be told apart.
    mkdirSync(join(p.classes, 'other'), { recursive: true });
    writeFileSync(join(p.classes, 'other/CounterValidator.class'), 'class');
    p.writeSpec();
    let snap = snapshotLaunch(readHostSpec(p.root), { base: base() });
    assert.throws(() => snapshotContracts(snap.entries), /holds 2 classes named CounterValidator/);
    snap.dispose();
    // No script beside the class (a copy elsewhere is never taken).
    rmSync(join(p.classes, 'other'), { recursive: true });
    rmSync(join(p.classes, 'META-INF/plutus/CounterValidator.plutus.json'));
    writeFileSync(p.dep, zip({ 'dep/Thing.class': 'class', 'META-INF/plutus/CounterValidator.plutus.json': PLUTUS }));
    p.writeSpec();
    snap = snapshotLaunch(readHostSpec(p.root), { base: base() });
    assert.throws(() => snapshotContracts(snap.entries), /no compiled script META-INF\/plutus\/CounterValidator\.plutus\.json beside app\/CounterValidator\.class/);
    snap.dispose();
    // A description whose title is not its resource name.
    writeFileSync(join(p.classes, 'META-INF/plutus/CounterValidator.plutus.json'), PLUTUS);
    writeFileSync(join(p.classes, 'META-INF/joust/app.Counter.json'), description('app.Evil', 'app', 'CounterValidator'));
    p.writeSpec();
    snap = snapshotLaunch(readHostSpec(p.root), { base: base() });
    assert.throws(() => snapshotContracts(snap.entries), /title differs from its resource/);
    snap.dispose();
  });

  test('a jar entry twice is refused (which copy a class loader takes is not defined)', () => {
    const bytes = zip({ 'a.class': '1' });
    const twice = zip({ 'a.class': '1', 'b.class': '2' });
    // Rename b.class to a.class in both headers (same length).
    const dup = Buffer.from(twice.toString('latin1').replaceAll('b.class', 'a.class'), 'latin1');
    assert.deepEqual(readJar(bytes).names, ['a.class']);
    assert.throws(() => readJar(dup), /a\.class appears twice/);
    // A repeated directory entry carries nothing: kept once (real jars have them, e.g. archunit's).
    const dirs = Buffer.from(zip({ 'p/': '', 'q/': '', 'p/a.class': '1' }).toString('latin1').replaceAll('q/', 'p/'), 'latin1');
    assert.deepEqual(readJar(dirs).names.filter((n) => n === 'p/'), ['p/']);
  });

  test('the JVM host is launched with exactly the protocol\'s arguments, from the project, with the allow-listed environment', () => {
    const p = project();
    const seen = [];
    const spawnHost = (command, args, options) => {
      seen.push({ command, args, options });
      const child = new EventEmitter();
      Object.assign(child, { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), exitCode: null,
        signalCode: null, pid: 1, kill() { child.exitCode = 0; child.emit('exit', 0, null); } });
      return child;
    };
    process.env.JOUST_PLAYGROUND_SECRET_TEST = 'never passed';
    try {
      const host = launchGradleHost({ java: JAVA, classPath: ['/snap/0', '/snap/1.jar'], project: p.root, spawnHost });
      host.close();
    } finally {
      delete process.env.JOUST_PLAYGROUND_SECRET_TEST;
    }
    assert.equal(seen.length, 1);
    assert.equal(seen[0].command, JAVA);
    assert.deepEqual(seen[0].args, [...JVM_ARGS, '-cp', ['/snap/0', '/snap/1.jar'].join(process.platform === 'win32' ? ';' : ':'),
      MAIN_CLASS, '--stdio']);
    assert.deepEqual(JVM_ARGS, ['-XX:+DisplayVMOutputToStderr', '-Xlog:disable', '-Xlog:all=warning:stderr', '--enable-native-access=ALL-UNNAMED']);
    assert.equal(seen[0].options.cwd, p.root);
    assert.ok(Object.keys(seen[0].options.env).every((k) => ['PATH', 'HOME', 'LANG'].includes(k) || k.startsWith('LC_')),
      Object.keys(seen[0].options.env).join(','));
    assert.throws(() => launchGradleHost({ java: join(p.root, 'no-java'), classPath: [], project: p.root, spawnHost }), /does not exist/);
    assert.throws(() => launchGradleHost({ java: JAVA, classPath: ['/a:b'], project: p.root, spawnHost }), /contains the delimiter/);
    assert.equal(seen.length, 1, 'nothing else was started');
  });

  test('I6: after a Gradle rebuild the session\'s build is stale; the next open runs the build again and pins the new one', async () => {
    const p = project();
    const b = base();
    const runs = [];
    const launch = () => ({});
    const source = gradleSource(p.root, { base: b, launch, log: () => {}, gradle: async (tasks) => { runs.push(tasks); p.writeSpec(); } });
    const first = await source.open();
    assert.deepEqual(runs, [], 'the command already ran the build');
    assert.equal(first.kind, 'gradle');
    assert.equal(first.stale(), false);
    const reset = await source.open();      // a reset on the same build: no Gradle run, the same digest
    assert.deepEqual(runs, []);
    assert.equal(reset.digest, first.digest);
    // The user rebuilds (./gradlew build joustHostSpec in another terminal).
    writeFileSync(join(p.classes, 'app/Counter.class'), 'recompiled');
    p.writeSpec();
    assert.equal(first.stale(), true);
    // The user compiles without the spec: stale too, and the next open runs the build (the gates) first.
    writeFileSync(join(p.classes, 'app/Counter.class'), 'recompiled again');
    const next = await source.open();
    assert.deepEqual(runs, [['build', 'joustHostSpec']]);
    assert.notEqual(next.digest, first.digest);
    assert.equal(next.stale(), false);
    for (const s of [first, reset, next]) s.dispose();
    assert.deepEqual(snapshots(b), []);
  });

  test('the wrapper is the build\'s own, found only through trusted directories (the native joust\'s rule)', () => {
    const p = project();
    // A Gradle project without a settings file, below a directory with a settings file and a wrapper.
    const sub = join(p.root, 'app');
    mkdirSync(sub);
    writeFileSync(join(sub, 'build.gradle'), '');
    writeFileSync(join(sub, 'gradlew'), '#!/bin/sh\n');
    chmodSync(join(sub, 'gradlew'), 0o755);
    assert.equal(gradleBuild(sub).gradlew, join(p.root, 'gradlew'), 'a trusted ancestor\'s build');
    // The ancestor belongs to someone else (or is shared): its settings and wrapper are never used.
    const foreign = (d) => d !== p.root;
    // Without a settings file of its own, Gradle would search upwards itself and evaluate the foreign one: refused.
    assert.throws(() => gradleBuild(sub, { isTrusted: foreign }), /no settings\.gradle at or above .*app in a directory you own.*add a settings\.gradle/);
    // With its own settings file, the project is its own build, with its own wrapper.
    writeFileSync(join(sub, 'settings.gradle.kts'), '');
    assert.deepEqual(gradleBuild(sub, { isTrusted: foreign }), { project: sub, buildRoot: sub, gradlew: join(sub, 'gradlew') });
    rmSync(join(sub, 'settings.gradle.kts'));
    // JOUST_SAFE_DIRS: the default trust (this user's directories, or the listed ones) admits the ancestor again.
    assert.equal(gradleBuild(sub, { env: { JOUST_SAFE_DIRS: p.root } }).buildRoot, p.root);
    writeFileSync(join(sub, 'settings.gradle'), '');
    rmSync(join(sub, 'gradlew'));
    assert.throws(() => gradleBuild(sub, { isTrusted: foreign }), /no executable Gradle wrapper at .*app\/gradlew/);
    // A settings file that is a link (or a directory) does not count.
    rmSync(join(sub, 'settings.gradle'));
    symlinkSync(join(p.root, 'settings.gradle'), join(sub, 'settings.gradle'));
    assert.throws(() => gradleBuild(sub, { isTrusted: foreign }), /no settings\.gradle at or above/);
  });

  test('the first session runs only the spec the gated startup build wrote; a bare joustHostSpec since builds again', async () => {
    const p = project();
    const b = base();
    const runs = [];
    const gatedSpec = readFileSync(join(p.root, SPEC_PATH));       // what `build joustHostSpec` wrote at startup
    // Before the page opens, the developer runs `./gradlew joustHostSpec` alone (no checkJscAbi): generation B.
    writeFileSync(join(p.classes, 'app/Counter.class'), 'generation B, never gated');
    p.writeSpec();
    const source = gradleSource(p.root, { base: b, log: () => {}, launch: () => ({}), gatedSpec,
      gradle: async (tasks) => { runs.push(tasks); p.writeSpec(); } });
    const s = await source.open();
    assert.deepEqual(runs, [['build', 'joustHostSpec']], 'the gates ran for the build the session runs');
    assert.equal(s.digest, readHostSpec(p.root).digest);
    // The next open of the same, gated build: no build.
    const again = await source.open();
    assert.deepEqual(runs.length, 1);
    for (const x of [s, again]) x.dispose();
  });

  test('jars outside the build are hashed for I6; only Gradle\'s immutable dependency cache is checked by identity', async () => {
    const p = project();
    const b = base();
    const outside = realpathSync(mkdtempSync(join(tmpdir(), 'joust-pg-localjar-')));
    made.push(outside);
    const local = join(outside, 'local.jar');
    writeFileSync(local, zip({ 'loc/A.class': 'aaaa' }));
    const cached = join(outside, 'caches/modules-2/files-2.1/org.x/lib/1.0/0a1b2c/lib-1.0.jar');
    mkdirSync(dirname(cached), { recursive: true });
    writeFileSync(cached, zip({ 'lib/B.class': 'bbbb' }));
    p.writeSpec(specFor([...p.entries, { kind: 'jar', path: local }, { kind: 'jar', path: cached }]));
    const s = await gradleSource(p.root, { base: b, log: () => {}, launch: () => ({}) }).open();
    assert.equal(s.stale(), false);
    // A local file dependency restored in place: same size, inode and time, other bytes.
    const sameSize = (f, from, to) => {
      const { atime, mtime } = statSync(f);
      writeFileSync(f, readFileSync(f).toString('latin1').replace(from, to), 'latin1');
      utimesSync(f, atime, mtime);
    };
    sameSize(local, 'aaaa', 'AAAA');
    assert.equal(s.stale(), true, 'a local jar outside the build root is hashed');
    sameSize(local, 'AAAA', 'aaaa');
    assert.equal(s.stale(), false);
    // A module in Gradle's cache is immutable: its identity is what is checked (a new time is a change).
    const t = new Date(Date.now() - 60_000);
    utimesSync(cached, t, t);
    assert.equal(s.stale(), true);
    s.dispose();
  });


  test('Windows: a wrapper path cmd.exe would interpret is refused', () => {
    for (const c of ['&', '^', '%', '(', ')', '!', '"', '<', '>', '|']) {
      assert.match(windowsCommandProblem(`C:\\Users\\me\\a${c}b\\gradlew.bat`), /cmd\.exe would interpret/, c);
    }
    assert.equal(windowsCommandProblem('C:\\Users\\me\\my app\\gradlew.bat'), null);
    const p = project();
    const odd = join(p.root, 'a&b');
    mkdirSync(odd);
    writeFileSync(join(odd, 'settings.gradle'), '');
    writeFileSync(join(odd, 'gradlew.bat'), '@echo off\n');
    assert.throws(() => gradleBuild(odd, { platform: 'win32' }), /contains "&", which cmd\.exe would interpret/);
  });

  test('the spec\'s java must be a JDK 25+ (its release file), never any executable', () => {
    assert.equal(checkJdk(JAVA), JAVA);
    assert.throws(() => checkJdk(process.execPath), /is not a JDK's bin\/java/);
    const old = fakeJdk(join(JDKS, 'jdk-21'), '21.0.4');
    assert.throws(() => checkJdk(old), /is Java 21\.0\.4: the dev host needs JDK 25 or later/);
    assert.throws(() => checkJdk(fakeJdk(join(JDKS, 'jdk-8'), '1.8.0_412')), /Java 1\.8\.0_412/);
    const bare = join(JDKS, 'nojdk/bin');
    mkdirSync(bare, { recursive: true });
    writeFileSync(join(bare, 'java'), '#!/bin/sh\n');
    chmodSync(join(bare, 'java'), 0o755);
    assert.throws(() => checkJdk(join(bare, 'java')), /has no release file/);
    // A link to a JDK's java resolves to it (sdkman's current, Homebrew): its real path is what runs.
    const link = join(JDKS, 'current-java');
    symlinkSync(JAVA, link);
    assert.equal(checkJdk(link), JAVA);
    // The session refuses a spec naming another executable before copying anything.
    const p = project();
    const b = base();
    p.writeSpec(specFor(p.entries, process.execPath));
    return assert.rejects(gradleSource(p.root, { base: b, log: () => {}, launch: () => ({}) }).open(), /not a JDK's bin\/java/)
      .then(() => assert.deepEqual(snapshots(b), []));
  });

  test('a FIFO in place of the spec never blocks: refused at launch, stale for a session', { skip: process.platform === 'win32' && 'no FIFOs' }, async () => {
    const p = project();
    const b = base();
    const s = await gradleSource(p.root, { base: b, log: () => {}, launch: () => ({}) }).open();
    unlinkSync(join(p.root, SPEC_PATH));
    assert.equal(spawnSync('mkfifo', [join(p.root, SPEC_PATH)]).status, 0);
    const t0 = Date.now();
    assert.equal(s.stale(), true);
    assert.throws(() => readHostSpec(p.root), /not a regular file/);
    assert.ok(Date.now() - t0 < 2000, 'nothing blocked');
    s.dispose();
  });

  test('I6: a same-size replacement with the old modification time is still a rebuild (contents are compared)', async () => {
    const p = project();
    const b = base();
    const s = await gradleSource(p.root, { base: b, log: () => {}, launch: () => ({}) }).open();
    const f = join(p.classes, 'app/Counter.class');
    const { mtime, atime } = statSync(f);
    writeFileSync(f, 'CLASS');               // same length as 'class'
    utimesSync(f, atime, mtime);
    assert.equal(statSync(f).size, 5);
    assert.equal(s.stale(), true);
    writeFileSync(f, 'class');
    utimesSync(f, atime, mtime);
    assert.equal(s.stale(), false);
    // An added file the spec does not list is a rebuild too.
    writeFileSync(join(p.classes, 'app/Added.class'), 'new');
    assert.equal(s.stale(), true);
    s.dispose();
  });

  test('a rebuild between the reads: the old copy is discarded and the session pins the new build', async () => {
    const p = project();
    const b = base();
    let copies = 0;
    const runs = [];
    const source = gradleSource(p.root, { base: b, log: () => {}, launch: () => ({}),
      gradle: async (tasks) => { runs.push(tasks); p.writeSpec(); },
      onCopied: () => {
        // Gradle publishes generation B while generation A is being copied.
        if (copies++ === 0) {
          writeFileSync(join(p.classes, 'app/Counter.class'), 'generation B');
          p.writeSpec();
        }
      } });
    const specA = readHostSpec(p.root);
    const s = await source.open();
    const specB = readHostSpec(p.root);
    assert.notEqual(specB.digest, specA.digest);
    assert.equal(s.digest, specB.digest, 'the session runs the build it reports as current');
    assert.equal(s.stale(), false);
    assert.equal(copies, 2);
    assert.deepEqual(snapshots(b).length, 1, 'generation A\'s copy is gone');
    s.dispose();
  });

  test('a snapshot directory holding the class-path delimiter is refused', { skip: process.platform === 'win32' && 'no : in Windows names' }, () => {
    const p = project();
    const b = join(base(), 'with:colon');
    mkdirSync(b);
    assert.throws(() => snapshotLaunch(readHostSpec(p.root), { base: b }), /contains the class-path delimiter/);
    assert.deepEqual(snapshots(b), []);
  });

  test('manifests are read as the JDK reads them: any case, no second manifest, no repeated attribute', () => {
    const mf = (text, name = 'META-INF/MANIFEST.MF') => readJar(zip({ [name]: text, 'x.class': 'x' }));
    assert.deepEqual(manifestOf(mf('Manifest-Version: 1.0\r\n\r\n')), { classPath: '', multiRelease: false });
    assert.equal(manifestOf(mf('Manifest-Version: 1.0\r\nclass-path: evil.jar\r\n\r\n', 'meta-inf/manifest.mf')).classPath, 'evil.jar');
    assert.equal(manifestOf(mf('Manifest-Version: 1.0\nClass-Path: a.jar\n b.jar\n\n')).classPath, 'a.jarb.jar');
    assert.throws(() => manifestOf(mf('Manifest-Version: 1.0\r\nClass-Path: \r\nClass-Path: evil.jar\r\n\r\n')), /repeats Class-Path/);
    const two = readJar(zip({ 'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\r\n\r\n', 'META-INF/manifest.mf': 'Class-Path: e.jar\r\n\r\n' }));
    assert.throws(() => manifestOf(two), /more than one manifest/);
    assert.equal(manifestOf(mf('Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n')).multiRelease, true);
    // In the launch: a lower-case manifest's Class-Path is refused like any other.
    const p = project();
    writeFileSync(p.dep, zip({ 'meta-inf/Manifest.MF': 'Manifest-Version: 1.0\r\nCLASS-PATH: ../evil.jar\r\n\r\n', 'dep/Thing.class': 'class' }));
    p.writeSpec();
    assert.throws(() => snapshotLaunch(readHostSpec(p.root), { base: base() }), /manifest Class-Path/);
  });

  test('the central directory holds exactly the records its end record counts, each within it', () => {
    const two = zip({ 'a.class': '1', 'b.class': '2' });
    assert.deepEqual(readJar(two).names, ['a.class', 'b.class']);
    const under = Buffer.from(two);
    under.writeUInt16LE(1, under.length - 14);   // total entries: 1 of 2
    under.writeUInt16LE(1, under.length - 12);
    assert.throws(() => readJar(under), /other records than its end record counts/);
    const past = Buffer.from(two);
    const cd = past.readUInt32LE(past.length - 6);
    past.writeUInt16LE(400, cd + 46 + 7 + 28);   // the second record's name runs past the directory
    assert.throws(() => readJar(past), /a central directory record past its end/);
  });

  test('the zip\'s end record must end the file, and the central directory end where it starts', () => {
    const good = zip({ 'a.class': '1' });
    assert.deepEqual(readJar(good).names, ['a.class']);
    assert.throws(() => readJar(Buffer.concat([good, Buffer.from('trailing')])), /bytes after the end of the archive|not a zip file/);
    const shifted = Buffer.from(good);
    shifted.writeUInt32LE(shifted.readUInt32LE(shifted.length - 6) + 1, shifted.length - 6);   // the central directory's offset
    assert.throws(() => readJar(shifted), /malformed central directory/);
  });

  test('a Multi-Release jar that versions a validator or a JOUST resource is refused; a plain jar\'s versions are ignored', () => {
    const MR = 'Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n';
    const p = project();
    // The validator only in versions/25 of an MR sibling, a base copy in a later entry: refused, not guessed.
    writeFileSync(p.sibling, zip({ 'META-INF/versions/25/app/CounterValidator.class': 'v25' }, { manifest: MR }));
    p.writeSpec();
    let snap = snapshotLaunch(readHostSpec(p.root), { base: base() });
    assert.throws(() => snapshotContracts(snap.entries), /Multi-Release jar versions its validator app\/CounterValidator\.class/);
    snap.dispose();
    writeFileSync(p.sibling, zip({ 'META-INF/versions/25/META-INF/plutus/CounterValidator.plutus.json': PLUTUS }, { manifest: MR }));
    p.writeSpec();
    snap = snapshotLaunch(readHostSpec(p.root), { base: base() });
    assert.throws(() => snapshotContracts(snap.entries), /versions the JOUST resource/);
    snap.dispose();
    // The owner jar's count of classes of the validator's simple name is ValidatorScripts': every entry, versions included.
    writeFileSync(p.sibling, zip({ 'lib/OtherValidator.class': 'class', 'META-INF/versions/25/x/OtherValidator.class': 'v',
      'META-INF/joust/lib.Other.json': description('lib.Other', 'lib', 'OtherValidator'), 'META-INF/plutus/OtherValidator.plutus.json': PLUTUS },
    { manifest: 'Manifest-Version: 1.0\r\n\r\n' }));
    p.writeSpec();
    snap = snapshotLaunch(readHostSpec(p.root), { base: base() });
    assert.throws(() => snapshotContracts(snap.entries), /lib\.Other: the entry holding lib\/OtherValidator\.class holds 2 classes named OtherValidator/);
    snap.dispose();
    // Without Multi-Release the JVM ignores META-INF/versions: so does the playground.
    writeFileSync(p.sibling, zip({ 'META-INF/versions/25/app/CounterValidator.class': 'v25' }, { manifest: 'Manifest-Version: 1.0\r\n\r\n' }));
    p.writeSpec();
    snap = snapshotLaunch(readHostSpec(p.root), { base: base() });
    assert.deepEqual(snapshotContracts(snap.entries).map((c) => JSON.parse(c.description).preamble.title), ['app.Counter']);
    snap.dispose();
  });

  test('this user\'s snapshots a day old whose playground is gone are swept; others are kept', () => {
    const b = base();
    const mk = (name, { daysOld = 2, pid } = {}) => {
      const d = join(b, SNAPSHOT_PREFIX + name);
      mkdirSync(join(d, '0/app'), { recursive: true });
      writeFileSync(join(d, '0/app/X.class'), 'x');
      if (pid !== undefined) writeFileSync(join(d, 'owner.pid'), String(pid));
      chmodSync(join(d, '0/app'), 0o500);
      chmodSync(join(d, '0'), 0o500);
      const t = (Date.now() - daysOld * 24 * 3600 * 1000) / 1000;
      utimesSync(d, t, t);
      return d;
    };
    const dead = mk('dead', { pid: 2 ** 22 + 12345 });
    const orphan = mk('orphan');
    const recent = mk('recent', { daysOld: 0 });
    const live = mk('live', { pid: process.ppid });
    const target = mk('target', { daysOld: 0 });
    symlinkSync(target, join(b, SNAPSHOT_PREFIX + 'link'));
    const removed = sweepSnapshots({ base: b });
    assert.deepEqual(removed.sort(), [dead, orphan].sort());
    for (const d of [recent, live, target, join(b, SNAPSHOT_PREFIX + 'link')]) assert.ok(existsSync(d), d);
  });

  test('a Gradle project needs its own wrapper; npm wins in one directory', () => {
    const p = project();
    assert.equal(isGradleProject(p.root), true);
    // An unrelated package.json (a frontend's) does not make it an npm project (bin/joust.js's rule)...
    writeFileSync(join(p.root, 'package.json'), '{"dependencies": {"vite": "6"}}');
    assert.equal(isGradleProject(p.root), true);
    // ...a dependency on @julclang/joust, or a joust.config.json, does: npm wins in one directory.
    writeFileSync(join(p.root, 'package.json'), '{"devDependencies": {"@julclang/joust": "1.0.0"}}');
    assert.equal(isGradleProject(p.root), false);
    rmSync(join(p.root, 'package.json'));
    writeFileSync(join(p.root, 'joust.config.json'), '{}');
    assert.equal(isGradleProject(p.root), false);
    rmSync(join(p.root, 'joust.config.json'));
    rmSync(join(p.root, 'gradlew'));
    assert.throws(() => gradleBuild(p.root), /no executable Gradle wrapper .* never a gradle from PATH/);
    // A subproject's build root is the nearest settings file's directory.
    const sub = join(p.root, 'contracts');
    mkdirSync(sub);
    writeFileSync(join(sub, 'build.gradle'), '');
    writeFileSync(join(p.root, 'gradlew'), '#!/bin/sh\n');
    chmodSync(join(p.root, 'gradlew'), 0o755);
    assert.deepEqual(gradleBuild(sub), { project: sub, buildRoot: p.root, gradlew: join(p.root, 'gradlew') });
  });
});

// ---- real Gradle projects (CI: the cli job)

const REPO = process.env.JOUST_PLAYGROUND_GRADLE_REPO;
const CLI = process.env.JOUST_CLI;
const missing = (!REPO || !CLI) && 'needs JOUST_PLAYGROUND_GRADLE_REPO (a staged Maven repository of this commit) and JOUST_CLI';
if (missing && process.env.JOUST_REQUIRE_GRADLE) throw new Error(`JOUST_REQUIRE_GRADLE: ${missing}`);
const images = existsSync(join(repo, 'js/joust/engine/joust-toolchain.js.wasm'))
  && existsSync(join(repo, 'js/joust-runtime/engine/joust-runtime.js.wasm'));
const chromium = await chromiumOrNull();
const browserSkip = missing || (!images && 'requires the packaged runtime and toolchain images')
  || (!chromium && 'Playwright\'s Chromium is not installed');

/** A Gradle Counter project from `joust new` (package app: the npm template's contract name), against the staged repository. */
function gradleCounter(lang) {
  const parent = realpathSync(mkdtempSync(join(tmpdir(), 'joust-pg-gradle-real-')));
  const dir = join(parent, `g-${lang}`);
  const r = spawnSync(CLI, ['new', dir, '-t', 'gradle', '-l', lang, '-p', 'app', '--joust-repository', `file://${REPO}`],
    { encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr + r.stdout);
  return { parent, dir };
}
const loud = process.env.JOUST_PLAYGROUND_GRADLE_LOG ? console.log : () => {};

async function npmCounter(kind) {
  const root = install();
  mkdirSync(join(root, 'contracts/app'), { recursive: true });
  if (kind === 'java') cpSync(join(repo, 'js/create-joust/template/contracts/app/Counter.java'), join(root, 'contracts/app/Counter.java'));
  else for (const f of ['Counter.jsc', 'Counter.abi.json']) cpSync(join(repo, 'js/create-joust/variants/jsc/contracts/app', f), join(root, 'contracts/app', f));
  writeFileSync(join(root, 'joust.config.json'), '{ "contracts": "contracts" }');
  return root;
}

async function drive(server, { views }) {
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(String(e.stack ?? e)));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
    await page.goto(server.pairingUrl());
    await page.waitForSelector('body[data-ready=true]', { timeout: 180_000 });
    const transcript = await walkthrough(page, { views });
    assert.deepEqual(errors, []);
    return transcript;
  } finally {
    await browser.close();
  }
}

describe('ADR-0024 M2: the Counter walkthrough in Gradle projects, compared with npm (I11)', { skip: browserSkip }, () => {
  const transcripts = {};
  const cleanup = [];
  after(() => { for (const d of cleanup) rmSync(d, { recursive: true, force: true }); });

  for (const lang of ['java', 'jsc']) {
    test(`Gradle ${lang}: joust playground builds through ./gradlew, runs the JVM host, and the walkthrough completes`, async () => {
      const { parent, dir } = gradleCounter(lang);
      cleanup.push(parent);
      const { playground } = await import('../src/playground/index.js');
      const config = (await import('../src/config.js')).loadConfig(dir);
      await assert.rejects(playground({ config, root: dir, open: false, acceptAbi: true, log: () => {} }),
        /in a Gradle build, accept an ABI or lock change with \.\/gradlew acceptJscAbi/);
      const server = await playground({ config, root: dir, open: false, log: loud });
      const snapshotDirs = [];
      try {
        assert.equal(server.sessions.size, 0, 'no session before the page starts one');
        transcripts[`gradle-${lang}`] = await drive(server, { views: true });
        const session = [...server.sessions.values()][0];
        assert.equal(session.build, 'gradle');
        assert.ok(session.status().capabilities.includes('views'));
        snapshotDirs.push(session.snapshot);
        assert.ok(existsSync(session.snapshot), 'the snapshot is kept while its host runs');
      } finally {
        await server.close();
      }
      for (const d of snapshotDirs) assert.equal(existsSync(d), false, 'the snapshot is deleted when its host exits');
    });
    test(`npm ${lang}: the same walkthrough`, async () => {
      const root = await npmCounter(lang);
      cleanup.push(root);
      const { playground } = await import('../src/playground/index.js');
      const config = (await import('../src/config.js')).loadConfig(root);
      const server = await playground({ config, root, open: false, log: () => {} });
      try {
        transcripts[`npm-${lang}`] = await drive(server, { views: false });
      } finally {
        await server.close();
      }
    });
  }

  test('I11: the four combinations show the same screens and outcomes, step by step', () => {
    const base = transcripts['npm-java'];
    assert.ok(base, 'the npm Java walkthrough ran');
    for (const k of ['npm-jsc', 'gradle-java', 'gradle-jsc']) {
      assert.ok(transcripts[k], `${k} ran`);
      assert.deepEqual(transcripts[k].map((s) => s.step), base.map((s) => s.step));
      for (let i = 0; i < base.length; i++) assert.deepEqual(transcripts[k][i], base[i], `${k} at step "${base[i].step}"`);
    }
    // The steps say what the walkthrough checked.
    const at = (step) => base.find((s) => s.step === step);
    assert.match(at('incremented by 5').state, /"count": "5"/);
    assert.match(at('refused as Bob').history[0], /Bob: reset failed at stage host/);
    assert.equal(at('reset').instances, 0);
  });
});

describe('ADR-0024 M2 I6: a Gradle rebuild during a session', { skip: missing || (!images && 'requires the packaged runtime image') }, () => {
  test('the session is stale and prepares nothing; a new session runs the build and pins the new snapshot', async () => {
    const { parent, dir } = gradleCounter('java');
    try {
      const { playground } = await import('../src/playground/index.js');
      const config = (await import('../src/config.js')).loadConfig(dir);
      const server = await playground({ config, root: dir, open: false, log: loud });
      try {
        const post = (route, body) => fetch(`http://127.0.0.1:${server.port}/api/${route}`, { method: 'POST',
          headers: { 'content-type': 'application/json', 'x-joust-playground': server.token }, body: JSON.stringify(body) })
          .then((r) => r.json());
        const a = (await post('sessions/new', {})).result;
        assert.equal(a.stale, false);
        const contract = (await post('contract', { session: a.session, contract: COUNTER })).result;
        assert.equal(contract.describe.calls.find((c) => c.kind === 'view').served, true);
        // Rebuild with a changed build (one more class), as the user would in another terminal.
        writeFileSync(join(dir, 'src/main/java/app/Rebuilt.java'), 'package app;\n\nfinal class Rebuilt {}\n');
        const r = spawnSync(join(dir, 'gradlew'), ['build', 'joustHostSpec', '-q'], { cwd: dir, encoding: 'utf8' });
        assert.equal(r.status, 0, r.stderr);
        assert.equal((await post('session', { session: a.session })).result.stale, true);
        const refused = await post('prepare', { session: a.session, call: { contract: COUNTER, kind: 'init', method: 'init',
          address: 'addr_test1vz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzerspjrlsz', args: {} } });
        assert.equal(refused.error?.code, 'stale');
        const b = (await post('sessions/new', {})).result;
        assert.equal(b.stale, false);
        assert.notEqual(b.fingerprint, a.fingerprint);
      } finally {
        await server.close();
      }
    } finally {
      rmSync(parent, { recursive: true, force: true });
    }
  });

  test('I10: a changed .jsc interface fails the Gradle build (checkJscAbi); the manifest is untouched', async () => {
    const { parent, dir } = gradleCounter('jsc');
    try {
      const { playground } = await import('../src/playground/index.js');
      const config = (await import('../src/config.js')).loadConfig(dir);
      const abi = join(dir, 'src/main/jsc/app/Counter.abi.json');
      const committed = readFileSync(abi);
      const src = join(dir, 'src/main/jsc/app/Counter.jsc');
      writeFileSync(src, readFileSync(src, 'utf8').replace('action reset() only owner {', 'action reset(to: Int) only owner {')
        .replace('return { ...state, count: 0 }', 'return { ...state, count: to }'));
      await assert.rejects(playground({ config, root: dir, open: false, log: loud }), /the build failed/);
      assert.deepEqual(readFileSync(abi), committed);
    } finally {
      rmSync(parent, { recursive: true, force: true });
    }
  });
});

describe('ADR-0024 Q1: the native joust playground in a Gradle project', { skip: browserSkip || (!process.env.JOUST_PLAYGROUND_TARBALLS
  && 'needs JOUST_PLAYGROUND_TARBALLS (this commit\'s npm tarballs)') }, () => {
  test('installs its own @julclang/joust into the per-user cache once, serves the page, and reuses the cache', async () => {
    const { parent, dir } = gradleCounter('java');
    const home = join(parent, 'cache');
    try {
      const start = async () => {
        // Its own process group: Ctrl-C at a terminal reaches the whole group, and the CLI waits for its child (below).
        const child = spawn(CLI, ['playground', '--no-open'], { cwd: dir, env: { ...process.env, JOUST_PLAYGROUND_HOME: home },
          detached: true });
        let out = '';
        child.stderr.on('data', (b) => { out += b; });
        const url = await new Promise((resolve, reject) => {
          const timer = setTimeout(() => reject(new Error(`no URL in time:\n${out}`)), 15 * 60_000);
          child.stdout.on('data', (b) => {
            out += b;
            const m = /(http:\/\/127\.0\.0\.1:\d+\/#pair=[A-Za-z0-9_-]{32})/.exec(out);
            if (m) { clearTimeout(timer); resolve(m[1]); }
          });
          child.on('exit', (code) => reject(new Error(`joust playground exited (${code}):\n${out}`)));
        });
        const stop = async () => {
          const exited = new Promise((r) => child.once('exit', (code, signal) => r({ code, signal })));
          process.kill(-child.pid, 'SIGINT');
          return exited;
        };
        return { child, url, out: () => out, stop };
      };
      const first = await start();
      try {
        assert.match(first.out(), /installing @julclang\/joust .* \(once\)/);
        const browser = await chromium.launch();
        try {
          const page = await browser.newPage();
          await page.goto(first.url);
          await page.waitForSelector('body[data-ready=true]', { timeout: 180_000 });
          await page.waitForSelector(`[data-contract="${COUNTER}"]`);
        } finally {
          await browser.close();
        }
      } finally {
        assert.deepEqual(await first.stop(), { code: 0, signal: null }, 'Ctrl-C stops the playground and the CLI cleanly');
      }
      const versions = readdirSync(home).filter((n) => !n.startsWith('.'));
      assert.equal(versions.length, 1);
      assert.ok(existsSync(join(home, versions[0], '.complete')));
      const second = await start();
      try {
        assert.doesNotMatch(second.out(), /installing/);
      } finally {
        await second.stop();
      }
    } finally {
      rmSync(parent, { recursive: true, force: true });
    }
  });
});
