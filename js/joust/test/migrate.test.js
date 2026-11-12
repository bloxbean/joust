// ADR-0018 D16: joust migrate on the npm path, through the toolchain engine (the same Migrate as Gradle's migrateJsc).
// Needs the engine in engine/ (./gradlew :julc-state-toolchain:toolchainWasm, copied as CI does).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
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
