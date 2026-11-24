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
  symlinkSync, unlinkSync, writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative, sep } from 'node:path';
import { crc32 } from 'node:zlib';
import {
  GradleLaunchError, JVM_ARGS, MAIN_CLASS, SPEC_PATH, gradleBuild, gradleSource, isGradleProject, launchGradleHost,
  readHostSpec, snapshotContracts, snapshotLaunch,
} from '../src/playground/gradle.js';
import { readJar } from '../src/playground/jar.js';
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
function specFor(entries, java = process.execPath) {
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
  after(() => { for (const d of made) rmSync(d, { recursive: true, force: true }); });

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
      const host = launchGradleHost({ java: process.execPath, classPath: ['/snap/0', '/snap/1.jar'], project: p.root, spawnHost });
      host.close();
    } finally {
      delete process.env.JOUST_PLAYGROUND_SECRET_TEST;
    }
    assert.equal(seen.length, 1);
    assert.equal(seen[0].command, process.execPath);
    assert.deepEqual(seen[0].args, [...JVM_ARGS, '-cp', ['/snap/0', '/snap/1.jar'].join(process.platform === 'win32' ? ';' : ':'),
      MAIN_CLASS, '--stdio']);
    assert.deepEqual(JVM_ARGS, ['-XX:+DisplayVMOutputToStderr', '-Xlog:disable', '-Xlog:all=warning:stderr', '--enable-native-access=ALL-UNNAMED']);
    assert.equal(seen[0].options.cwd, p.root);
    assert.ok(Object.keys(seen[0].options.env).every((k) => ['PATH', 'HOME', 'LANG'].includes(k) || k.startsWith('LC_')),
      Object.keys(seen[0].options.env).join(','));
    assert.throws(() => launchGradleHost({ java: join(p.root, 'no-java'), classPath: [], project: p.root, spawnHost }), /not an executable file/);
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

  test('a Gradle project needs its own wrapper; npm wins in one directory', () => {
    const p = project();
    assert.equal(isGradleProject(p.root), true);
    writeFileSync(join(p.root, 'package.json'), '{}');
    assert.equal(isGradleProject(p.root), false);
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
    await page.goto(server.url);
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
            const m = /(http:\/\/127\.0\.0\.1:\d+\/#token=[A-Za-z0-9_-]+)/.exec(out);
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
