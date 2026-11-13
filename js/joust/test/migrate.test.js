// ADR-0018 D16: joust migrate on the npm path, through the toolchain engine (the same Migrate as Gradle's migrateJsc).
// Needs the engine in engine/ (./gradlew :julc-state-toolchain:toolchainWasm, copied as CI does).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { migrate } from '../src/migrate.js';
import { build } from '../src/build.js';

const DSL = fileURLToPath(new URL('../../../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/', import.meta.url));
const skip = existsSync(fileURLToPath(new URL('../engine/joust-toolchain.js', import.meta.url))) ? false : 'no toolchain engine in engine/';

function project() {
  const root = mkdtempSync(join(tmpdir(), 'joust-migrate-'));
  mkdirSync(join(root, 'contracts', 'app'), { recursive: true });
  for (const f of ['Guards.jsc', 'Jar.jsc', 'Counter2.jsc', 'Counter2.abi.json']) cpSync(join(DSL, f), join(root, 'contracts', 'app', f));
  return root;
}

const quiet = () => {
  const lines = [];
  return { lines, log: (l) => lines.push(l), error: (l) => lines.push(l) };
};

test('joust migrate: v1 files into build/joust/migrated, contracts/ unchanged; Jar refused', { skip, timeout: 300_000 }, async () => {
  const root = project();
  const q = quiet();
  const r = await migrate({ root, log: q.log, error: q.error });
  assert.equal(r.ok, false);
  assert.deepEqual(r.refused, ['app/Jar.jsc']);
  assert.ok(q.lines.some((l) => /^error\[JSC1103\]: app\/Jar\.jsc:17:26: 'batched' actions/.test(l)), q.lines.join('\n'));
  // The same translation as the JVM's (examples/dsl's committed migration, renamed there).
  const migrated = readFileSync(join(root, 'build/joust/migrated/app/Guards.jsc'), 'utf8');
  const committed = readFileSync(join(DSL, 'migrated', 'GuardsV2.jsc'), 'utf8')
    .replace('package org.julclang.state.examples.dsl.migrated\n', 'package org.julclang.state.examples.dsl\n').replace('contract GuardsV2 {', 'contract Guards {');
  assert.equal(migrated, committed);
  assert.ok(!existsSync(join(root, 'build/joust/migrated/app/Counter2.jsc')), 'a .jsc 2 file is left alone');
  assert.equal(readFileSync(join(root, 'contracts/app/Guards.jsc'), 'utf8'), readFileSync(join(DSL, 'Guards.jsc'), 'utf8'));
  assert.ok(q.lines.some((l) => /a migrated contract is a new script/.test(l)));
});

test('joust migrate --in-place keeps the v1 text, never overwrites it, and the result builds once its ABI is accepted',
  { skip, timeout: 600_000 }, async () => {
    const root = project();
    const q = quiet();
    const r = await migrate({ root, files: ['contracts/app/Guards.jsc'], inPlace: true, log: q.log, error: q.error });
    assert.ok(r.ok, q.lines.join('\n'));
    assert.match(readFileSync(join(root, 'contracts/app/Guards.jsc'), 'utf8'), /^jsc 2\n/);
    assert.equal(readFileSync(join(root, 'contracts/app/Guards.jsc.v1'), 'utf8'), readFileSync(join(DSL, 'Guards.jsc'), 'utf8'));
    // A second v1 file whose backup exists is refused (JSC1107), and the backup is untouched.
    cpSync(join(DSL, 'Points.jsc'), join(root, 'contracts/app/Points.jsc'));
    writeFileSync(join(root, 'contracts/app/Points.jsc.v1'), 'x');
    const again = await migrate({ root, files: ['contracts/app/Points.jsc'], inPlace: true, log: q.log, error: q.error });
    assert.deepEqual(again.refused, ['app/Points.jsc']);
    assert.ok(q.lines.some((l) => l.startsWith('error[JSC1107]: app/Points.jsc')), q.lines.join('\n'));
    assert.equal(readFileSync(join(root, 'contracts/app/Points.jsc.v1'), 'utf8'), 'x');
    // Build the migrated Guards (Jar and Points stay out): its manifest is accepted, as the migrate output says.
    const keep = (f) => f === 'app/Guards.jsc' || f === 'app/Counter2.jsc';
    let b = await build({ root, keep, log: () => {} });
    assert.equal(b.ok, false, 'a migrated file has no manifest yet (JSC0003)');
    b = await build({ root, keep, acceptAbi: true, log: () => {} });
    assert.ok(b.ok);
    assert.ok(existsSync(join(root, 'contracts/app/Guards.abi.json')));
    // Review r3 F9: the compiler that built each script, carried into each deployment record.
    const built = JSON.parse(readFileSync(join(root, 'build/joust/contracts.json'), 'utf8'))[0].built;
    assert.ok(built.joust && built.engine && built.julc, JSON.stringify(built));
    // Review r4 F13: stamped at build time, never 'dev'.
    assert.ok(built.engine !== 'dev' && built.julc !== 'dev', JSON.stringify(built));
    assert.deepEqual(JSON.parse(readFileSync(join(root, 'build/joust/joust.lock.json'), 'utf8')).engine, { joust: built.engine, julc: built.julc });
  });

// D16, review r2 S1: joust build warns about recorded deployments it does not produce, in every case.
test('a recorded deployment this build does not produce is a warning: another script, renamed or removed, params, unreadable', async () => {
  const { staleDeployments } = await import('../src/build.js');
  const { scriptDigest } = await import('../src/deploy.js');
  const dir = mkdtempSync(join(tmpdir(), 'joust-stale-'));
  const contracts = [{ packageName: 'app', name: 'Counter', scriptHash: 'aa', scriptCborHex: '4e01' },
    { packageName: 'app', name: 'Gov', scriptHash: '', scriptCborHex: '4e02' }];
  assert.deepEqual(staleDeployments(dir, contracts), []);
  const write = (contracts) => writeFileSync(join(dir, 'deployments.devkit.json'), JSON.stringify({ contracts }));
  write({ 'app.Counter': { policy: 'aa', scriptDigest: scriptDigest('4e01') }, 'app.Gov': { params: { x: 1 }, scriptDigest: scriptDigest('4e02') } });
  assert.deepEqual(staleDeployments(dir, contracts), [], 'the same scripts');
  write({ 'app.Counter': { policy: 'bb' } });
  assert.match(staleDeployments(dir, contracts)[0], /records app\.Counter at script bb, which this build does not produce \(it builds aa\)/);
  write({ 'app.Counter': { policy: 'aa', scriptDigest: scriptDigest('4e09') } });
  assert.match(staleDeployments(dir, contracts)[0], /records app\.Counter with another script than this build's/);
  write({ 'app.Gov': { params: { x: 1 }, scriptDigest: scriptDigest('4e03') } });
  assert.match(staleDeployments(dir, contracts)[0], /records app\.Gov with another script/, 'params: by the unapplied script');
  write({ 'app.Gov': { params: { x: 1 } } });
  assert.match(staleDeployments(dir, contracts)[0], /with params but no script digest/);
  write({ 'app.CounterV1': { policy: 'cc' } });
  assert.match(staleDeployments(dir, contracts)[0], /records app\.CounterV1, which this build does not produce \(renamed or removed\)/);
  writeFileSync(join(dir, 'deployments.devkit.json'), '{ "contracts": { "app.Counter": { "policy": "aa" }, } }');
  assert.match(staleDeployments(dir, contracts)[0], /^warning: deployments\.devkit\.json cannot be read/);
});

test('joust deploy merges: a replaced or dropped deployment is kept under "superseded"', async () => {
  const { mergeDeployments } = await import('../src/deploy.js');
  const prev = { network: 'devkit', contracts: { 'app.Counter': { policy: 'aa', address: 'A' }, 'app.Old': { policy: 'oo', address: 'O' },
    'app.Same': { policy: 'ss', address: 'S' } }, superseded: [{ title: 'app.Older', policy: 'xx' }] };
  const next = mergeDeployments(prev, { network: 'devkit', contracts: { 'app.Counter': { policy: 'bb', address: 'B' },
    'app.Same': { policy: 'ss', address: 'S' } } }, 'T');
  assert.deepEqual(Object.keys(next.contracts), ['app.Counter', 'app.Same']);
  assert.deepEqual(next.superseded, [{ title: 'app.Older', policy: 'xx' },
    { title: 'app.Counter', policy: 'aa', address: 'A', supersededAt: 'T', reason: 'redeployed as another script' },
    { title: 'app.Old', policy: 'oo', address: 'O', supersededAt: 'T', reason: 'not deployed by this build' }]);
  assert.equal(mergeDeployments(null, { contracts: { a: { policy: 'p' } } }).superseded, undefined);
});

// Review r2 S2, S3, C2: links and paths.
test('migrate never writes through links, outside migrated/, or over a source', { skip, timeout: 300_000 }, async () => {
  const { symlinkSync } = await import('node:fs');
  const root = project();
  const q = quiet();
  // A dangling .jsc.v1 link: refused (JSC1107), and its target is not created.
  symlinkSync(join(root, 'elsewhere', 'planted.txt'), join(root, 'contracts/app/Guards.jsc.v1'));
  let r = await migrate({ root, files: ['contracts/app/Guards.jsc'], inPlace: true, log: q.log, error: q.error });
  assert.deepEqual(r.refused, ['app/Guards.jsc']);
  assert.ok(q.lines.some((l) => l.startsWith('error[JSC1107]: app/Guards.jsc')), q.lines.join('\n'));
  assert.ok(!existsSync(join(root, 'elsewhere', 'planted.txt')));
  assert.match(readFileSync(join(root, 'contracts/app/Guards.jsc'), 'utf8'), /^package/);
  // A linked source is never replaced in place (JSC1109); its shared target keeps v1.
  mkdirSync(join(root, 'shared'));
  cpSync(join(DSL, 'Points.jsc'), join(root, 'shared', 'Points.jsc'));
  symlinkSync(join(root, 'shared', 'Points.jsc'), join(root, 'contracts/app/Points.jsc'));
  r = await migrate({ root, files: ['contracts/app/Points.jsc'], inPlace: true, log: q.log, error: q.error });
  assert.deepEqual(r.refused, ['app/Points.jsc']);
  assert.ok(q.lines.some((l) => l.startsWith('error[JSC1109]: app/Points.jsc')), q.lines.join('\n'));
  assert.match(readFileSync(join(root, 'shared', 'Points.jsc'), 'utf8'), /^package/);
  // The walker skips a dangling link and does not follow a linked directory.
  symlinkSync(join(root, 'nowhere.jsc'), join(root, 'contracts/app/Dangling.jsc'));
  symlinkSync(join(root, 'shared'), join(root, 'contracts/linked'));
  r = await migrate({ root, log: q.log, error: q.error });
  assert.ok(!existsSync(join(root, 'build/joust/migrated/linked')), 'a linked directory is not followed');
  assert.ok(existsSync(join(root, 'build/joust/migrated/app/Points.jsc')), 'a linked file is read (not written) in default mode');
  // Default mode: a file outside contracts/ is named by its base name, so nothing is written outside migrated/.
  mkdirSync(join(root, 'w', 'proj'), { recursive: true });
  const outside = join(root, 'contracts', 'app', 'Guards.jsc');
  r = await migrate({ root: join(root, 'w', 'proj'), files: ['../../contracts/app/Guards.jsc'], log: q.log, error: q.error });
  assert.ok(r.ok, q.lines.join('\n'));
  assert.ok(existsSync(join(root, 'w', 'proj', 'build/joust/migrated/Guards.jsc')));
  assert.match(readFileSync(outside, 'utf8'), /^package/, 'the source is unchanged');
  // An output directory inside the sources is refused (JSC1108).
  r = await migrate({ root, out: 'contracts/out', log: q.log, error: q.error });
  assert.equal(r.ok, false);
  assert.ok(q.lines.some((l) => l.startsWith('error[JSC1108]')), q.lines.join('\n'));
});

test('migrate --in-place keeps the v1 build\'s artifacts in build/joust/v1/', { skip, timeout: 300_000 }, async () => {
  const root = project();
  mkdirSync(join(root, 'build/joust'), { recursive: true });
  for (const f of ['Guards.json', 'Guards.plutus', 'Guards.js', 'Guards.d.ts', 'deployments.devkit.json']) writeFileSync(join(root, 'build/joust', f), 'v1 ' + f);
  const q = quiet();
  const r = await migrate({ root, files: ['contracts/app/Guards.jsc'], inPlace: true, log: q.log, error: q.error });
  assert.ok(r.ok, q.lines.join('\n'));
  for (const f of ['Guards.json', 'Guards.plutus', 'Guards.js', 'Guards.d.ts', 'deployments.devkit.json']) {
    assert.equal(readFileSync(join(root, 'build/joust/v1', f), 'utf8'), 'v1 ' + f);
  }
  assert.ok(q.lines.some((l) => /build\/joust\/v1/.test(l)));
});

// Review r3.
test('r3 C2, S6: an output alias into the sources, existing or not yet, is refused before anything is written', { skip, timeout: 300_000 }, async () => {
  const { symlinkSync } = await import('node:fs');
  const root = mkdtempSync(join(tmpdir(), 'joust-migrate-r3-'));
  mkdirSync(join(root, 'contracts/a'), { recursive: true });
  mkdirSync(join(root, 'contracts/b'), { recursive: true });
  writeFileSync(join(root, 'contracts/a/C.jsc'), 'package p\ncontract C { state n: Int init() {} action inc() { n += 1 } }\n');
  const other = 'package q\ncontract Other { state n: Int init() {} action dec() { n -= 1 } }\n';
  writeFileSync(join(root, 'contracts/b/C.jsc'), other);
  mkdirSync(join(root, 'build/joust/migrated'), { recursive: true });
  symlinkSync('../../../contracts/b', join(root, 'build/joust/migrated/a'));
  const q = quiet();
  let r = await migrate({ root, files: ['contracts/a/C.jsc'], log: q.log, error: q.error });
  assert.equal(r.ok, false);
  assert.ok(q.lines.some((l) => l.startsWith('error[JSC1108]: a/C.jsc') && /lies in the contracts directory/.test(l)), q.lines.join('\n'));
  assert.equal(readFileSync(join(root, 'contracts/b/C.jsc'), 'utf8'), other);
  // S6: build/joust is a link into the sources, and migrated/ does not exist yet.
  const root2 = mkdtempSync(join(tmpdir(), 'joust-migrate-r3-'));
  mkdirSync(join(root2, 'contracts/gen'), { recursive: true });
  cpSync(join(DSL, 'Guards.jsc'), join(root2, 'contracts/Guards.jsc'));
  mkdirSync(join(root2, 'build'));
  symlinkSync('../contracts/gen', join(root2, 'build/joust'));
  r = await migrate({ root: root2, log: q.log, error: q.error });
  assert.equal(r.ok, false);
  assert.ok(!existsSync(join(root2, 'contracts/gen/migrated')));
});

test('r3 S5: --in-place refuses a hard-linked source and a source under a linked directory', { skip, timeout: 300_000 }, async () => {
  const { linkSync, symlinkSync } = await import('node:fs');
  const root = project();
  mkdirSync(join(root, 'shared'));
  cpSync(join(DSL, 'Points.jsc'), join(root, 'shared/Points.jsc'));
  linkSync(join(root, 'shared/Points.jsc'), join(root, 'contracts/app/Points.jsc'));
  const q = quiet();
  let r = await migrate({ root, files: ['contracts/app/Points.jsc'], inPlace: true, log: q.log, error: q.error });
  assert.deepEqual(r.refused, ['app/Points.jsc']);
  assert.ok(q.lines.some((l) => l.startsWith('error[JSC1109]: app/Points.jsc') && /hard links/.test(l)), q.lines.join('\n'));
  assert.match(readFileSync(join(root, 'shared/Points.jsc'), 'utf8'), /^package/);
  mkdirSync(join(root, 'sharedlib'));
  cpSync(join(DSL, 'Guards.jsc'), join(root, 'sharedlib/Guards.jsc'));
  symlinkSync('../sharedlib', join(root, 'contracts/lib'));
  r = await migrate({ root, files: ['contracts/lib/Guards.jsc'], inPlace: true, log: q.log, error: q.error });
  assert.deepEqual(r.refused, ['lib/Guards.jsc']);
  assert.ok(q.lines.some((l) => l.startsWith('error[JSC1109]: lib/Guards.jsc') && /symbolic link/.test(l)), q.lines.join('\n'));
  assert.match(readFileSync(join(root, 'sharedlib/Guards.jsc'), 'utf8'), /^package/);
  assert.ok(!existsSync(join(root, 'sharedlib/Guards.jsc.v1')));
});

test('r3 C4: a contract declared on the package\'s line keeps its v1 artifacts, by the name the engine read', { skip, timeout: 300_000 }, async () => {
  const root = mkdtempSync(join(tmpdir(), 'joust-migrate-r3-'));
  mkdirSync(join(root, 'contracts/a'), { recursive: true });
  writeFileSync(join(root, 'contracts/a/C.jsc'), 'package p contract C { state n: Int init() {} action inc() { n += 1 } }\n');
  mkdirSync(join(root, 'build/joust'), { recursive: true });
  const files = ['C.json', 'C.plutus', 'C.js', 'C.d.ts', 'contracts.json', 'joust.lock.json', 'deployments.devkit.json'];
  for (const f of files) writeFileSync(join(root, 'build/joust', f), 'v1 ' + f);
  const q = quiet();
  const r = await migrate({ root, inPlace: true, log: q.log, error: q.error });
  assert.ok(r.ok, q.lines.join('\n'));
  for (const f of files) assert.equal(readFileSync(join(root, 'build/joust/v1', f), 'utf8'), 'v1 ' + f, f);
  assert.ok(q.lines.some((l) => /artifacts of C are kept in build\/joust\/v1/.test(l)), q.lines.join('\n'));
});

test('r3 S7: a deployments file of the wrong shape is unreadable, merging is null-safe, and the stale warning says so', async () => {
  const { deploymentsShape, mergeDeployments } = await import('../src/deploy.js');
  const { staleDeployments } = await import('../src/build.js');
  for (const bad of [null, [], 'x', { contracts: null }, { contracts: [] }, { contracts: { a: null } }, { superseded: {} },
    { superseded: [null] }]) {
    assert.ok(deploymentsShape(bad), JSON.stringify(bad));
  }
  assert.equal(deploymentsShape({ contracts: { a: { policy: 'p' } }, superseded: [{ title: 'o' }] }), null);
  assert.deepEqual(mergeDeployments({ contracts: { a: null, b: { policy: 'p' } } }, { contracts: {} }, 'T').superseded,
    [{ title: 'b', policy: 'p', supersededAt: 'T', reason: 'not deployed by this build' }]);
  const dir = mkdtempSync(join(tmpdir(), 'joust-stale-'));
  const contracts = [{ packageName: 'app', name: 'Counter', scriptHash: 'aa', scriptCborHex: '4e01' }];
  for (const bad of ['{ "contracts": null }', '{ "contracts": { "app.Counter": null } }', '{ "superseded": {} }', 'null']) {
    writeFileSync(join(dir, 'deployments.devkit.json'), bad);
    assert.match(staleDeployments(dir, contracts)[0] ?? '', /cannot be read/, bad);
  }
  writeFileSync(join(dir, 'deployments.devkit.json'), '{ "contracts": { "app.Counter": {} } }');
  assert.match(staleDeployments(dir, contracts)[0], /neither a script digest nor a policy/);
});

// Review r4.
test('r4 C5: a hard-linked output target is refused, whichever source it shares; outputs are renamed into place', { skip, timeout: 300_000 }, async () => {
  const { linkSync, statSync: st } = await import('node:fs');
  const root = mkdtempSync(join(tmpdir(), 'joust-migrate-r4-'));
  mkdirSync(join(root, 'contracts/a'), { recursive: true });
  mkdirSync(join(root, 'contracts/b'), { recursive: true });
  const c = 'package p\ncontract C { state n: Int init() {} action inc() { n += 1 } }\n';
  const other = 'package q\ncontract Other { state n: Int init() {} action dec() { n -= 1 } }\n';
  writeFileSync(join(root, 'contracts/a/C.jsc'), c);
  writeFileSync(join(root, 'contracts/b/C.jsc'), other);
  mkdirSync(join(root, 'build/joust/migrated/a'), { recursive: true });
  const target = join(root, 'build/joust/migrated/a/C.jsc');
  const q = quiet();
  for (const source of ['contracts/b/C.jsc', 'contracts/a/C.jsc']) {
    rmSync(target, { force: true });
    linkSync(join(root, source), target);
    const r = await migrate({ root, files: ['contracts/a/C.jsc'], log: q.log, error: q.error });
    assert.equal(r.ok, false, source);
    assert.ok(q.lines.some((l) => l.startsWith('error[JSC1108]: a/C.jsc') && /hard link/.test(l)), q.lines.join('\n'));
    assert.equal(readFileSync(join(root, 'contracts/a/C.jsc'), 'utf8'), c);
    assert.equal(readFileSync(join(root, 'contracts/b/C.jsc'), 'utf8'), other);
  }
  rmSync(target);
  writeFileSync(target, 'old output');
  const before = st(target).ino;
  const r = await migrate({ root, files: ['contracts/a/C.jsc'], log: q.log, error: q.error });
  assert.ok(r.ok, q.lines.join('\n'));
  assert.match(readFileSync(target, 'utf8'), /^jsc 2\n/);
  assert.notEqual(st(target).ino, before, 'a new inode: renamed into place');
});

test('r4 C6: a stale temporary file of the same pid is left alone, and in place still succeeds', { skip, timeout: 300_000 }, async () => {
  const root = mkdtempSync(join(tmpdir(), 'joust-migrate-r4-'));
  mkdirSync(join(root, 'contracts/a'), { recursive: true });
  writeFileSync(join(root, 'contracts/a/C.jsc'), 'package p\ncontract C { state n: Int init() {} action inc() { n += 1 } }\n');
  const stale = join(root, `contracts/a/.C.jsc.${process.pid}.migrating`);
  writeFileSync(stale, 'existing temp data');
  const q = quiet();
  const r = await migrate({ root, files: ['contracts/a/C.jsc'], inPlace: true, log: q.log, error: q.error });
  assert.ok(r.ok, q.lines.join('\n'));
  assert.equal(readFileSync(stale, 'utf8'), 'existing temp data');
  assert.match(readFileSync(join(root, 'contracts/a/C.jsc'), 'utf8'), /^jsc 2\n/);
  const { readdirSync: ls } = await import('node:fs');
  assert.deepEqual(ls(join(root, 'contracts/a')).filter((f) => f.endsWith('.migrating')), [basename(stale)]);
});

test('#80 F6: --in-place refuses a linked contracts/ (any link below the project root), a path with \'..\', not a case variant', { skip, timeout: 300_000 }, async () => {
  const { symlinkSync, realpathSync } = await import('node:fs');
  const base = realpathSync(mkdtempSync(join(tmpdir(), 'joust-migrate-f6-')));
  const proj = join(base, 'proj');
  mkdirSync(proj);
  mkdirSync(join(base, 'shared'));
  cpSync(join(DSL, 'Points.jsc'), join(base, 'shared/Points.jsc'));
  symlinkSync('../shared', join(proj, 'contracts'));
  const q = quiet();
  // By file, and by walking contracts/: refused, the shared file and its backup untouched.
  for (const files of [['contracts/Points.jsc'], []]) {
    const r = await migrate({ root: proj, files, inPlace: true, log: q.log, error: q.error });
    assert.deepEqual(r.refused, ['Points.jsc'], q.lines.join('\n'));
    assert.ok(q.lines.some((l) => l.startsWith('error[JSC1109]: Points.jsc') && /the directory contracts is a symbolic link/.test(l)), q.lines.join('\n'));
    assert.match(readFileSync(join(base, 'shared/Points.jsc'), 'utf8'), /^package/);
    assert.ok(!existsSync(join(base, 'shared/Points.jsc.v1')));
  }
  // Through an alias of the project root, too.
  symlinkSync(proj, join(base, 'alias'));
  let r = await migrate({ root: join(base, 'alias'), files: ['contracts/Points.jsc'], inPlace: true, log: q.log, error: q.error });
  assert.deepEqual(r.refused, ['Points.jsc']);
  // '..' in the path given.
  const plain = join(base, 'plain');
  mkdirSync(join(plain, 'contracts/app'), { recursive: true });
  cpSync(join(DSL, 'Points.jsc'), join(plain, 'contracts/app/Points.jsc'));
  r = await migrate({ root: plain, files: ['contracts/app/../app/Points.jsc'], inPlace: true, log: q.log, error: q.error });
  assert.deepEqual(r.refused, ['app/Points.jsc']);
  assert.ok(q.lines.some((l) => /contains '\.\.'/.test(l)), q.lines.join('\n'));
  // A case variant of a plain directory, where the file system ignores case: migrated.
  if (existsSync(join(plain, 'CONTRACTS/APP/Points.jsc'))) {
    r = await migrate({ root: plain, src: 'CONTRACTS', files: ['CONTRACTS/APP/Points.jsc'], inPlace: true, log: q.log, error: q.error });
    assert.ok(r.ok, q.lines.join('\n'));
    assert.match(readFileSync(join(plain, 'contracts/app/Points.jsc'), 'utf8'), /^jsc 2/);
  }
});
