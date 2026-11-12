// ADR-0015 I7: the runtime refuses descriptions and builds of another (incompatible) version.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkCompatible, checkEngine, engineContracts, MANIFEST_VERSION, RUNTIME_VERSION } from '../src/service.js';

const description = (version) => JSON.stringify({ preamble: { title: 'app.Counter' }, 'x-joust': { version } });

test('a description of version 2, built by a compatible toolchain, is accepted', () => {
  checkCompatible({ description: description(2), built: { joust: RUNTIME_VERSION } });
  checkCompatible({ description: description(2) });   // built by Gradle: no toolchain version
});

test('another description version is refused', () => {
  assert.throws(() => checkCompatible({ description: description(1) }), /description version 1/);
  assert.throws(() => checkCompatible({ description: description(3) }), /description version 3/);
});

test('a build by an incompatible toolchain is refused', () => {
  const [major, minor] = RUNTIME_VERSION.split('.').map(Number);
  const other = major === 0 ? `0.${minor + 1}.0` : `${major + 1}.0.0`;
  assert.throws(() => checkCompatible({ description: description(2), built: { joust: other } }), /built by JOUST/);
  checkCompatible({ description: description(2), built: { joust: `${major}.${minor}.99` } });
});

// ADR-0020 D8 (PR #62 F7): a bundle's program manifests are checked when the runtime is created, with the remedy.
test('contracts with programs need a manifest of the version this engine reads', () => {
  const d = description(2);
  const programs = { increment: '00' };
  assert.throws(() => checkCompatible({ description: d, transitions: programs }), /have no manifest.*joust build/);
  assert.throws(() => checkCompatible({ description: d, transitions: programs, manifest: { manifest: 1 } }),
    /program manifest version 1; this runtime reads version 2/);
  checkCompatible({ description: d, transitions: programs, manifest: { manifest: MANIFEST_VERSION } });
  checkCompatible({ description: d, transitions: {} });   // built with joust.transitions=false: nothing to check
});

test('programs compiled by another JuLC are reported once the engine is loaded, before any call', () => {
  const engine = { joust: { info: () => ({ joust: 'x', julc: '0.1.0-pre21-rc1', manifest: 2 }) } };
  const list = engineContracts([{ description: description(2), script: '', transitions: { a: '00' },
    manifest: { manifest: 2, contract: 'app.Counter', julc: '0.1.0-pre20' } }]);
  assert.throws(() => checkEngine(engine, list), /compiled by JuLC 0\.1\.0-pre20; this engine evaluates with JuLC 0\.1\.0-pre21-rc1.*joust build/);
  checkEngine(engine, engineContracts([{ description: description(2), script: '', transitions: { a: '00' },
    manifest: { manifest: 2, contract: 'app.Counter', julc: '0.1.0-pre21-rc1' } }]));
});
