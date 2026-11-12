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

// D16: both CLIs warn when a recorded deployment's script is not the one the build produces.
test('a recorded deployment whose script this build does not produce is a warning', async () => {
  const { staleDeployments } = await import('../src/build.js');
  const dir = mkdtempSync(join(tmpdir(), 'joust-stale-'));
  const contracts = [{ packageName: 'app', name: 'Counter', scriptHash: 'aa' }, { packageName: 'app', name: 'Gov', scriptHash: '' }];
  assert.deepEqual(staleDeployments(dir, contracts), []);
  writeFileSync(join(dir, 'deployments.devkit.json'), JSON.stringify({ contracts: {
    'app.Counter': { policy: 'bb' }, 'app.Gov': { policy: 'cc' }, 'app.Old': { policy: 'dd' } } }));
  const w = staleDeployments(dir, contracts);
  assert.equal(w.length, 1);
  assert.match(w[0], /^warning: deployments\.devkit\.json records app\.Counter at script bb, which this build does not produce \(it builds aa\)/);
  writeFileSync(join(dir, 'deployments.devkit.json'), JSON.stringify({ contracts: { 'app.Counter': { policy: 'aa' } } }));
  assert.deepEqual(staleDeployments(dir, contracts), []);
});
