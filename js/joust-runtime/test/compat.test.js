// ADR-0015 I7: the runtime refuses descriptions and builds of another (incompatible) version.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkCompatible, RUNTIME_VERSION } from '../src/service.js';

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
