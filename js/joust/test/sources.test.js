// Issue #92 (S12, security review of #82): joust build's source walk. A package installed under the source directory
// (contracts/node_modules/…), or the project's own node_modules when the sources are the project root (contracts: "."),
// is never project code: its files would skip the split-package rule and joust.lock, and a joust-lib/app/Require.java
// there could replace the project's Require and remove every require from a script. Dot-directories are skipped too,
// and no link is followed. Needs no engine.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { sources } from '../src/build.js';

const put = (root, rel, text) => {
  mkdirSync(join(root, rel, '..'), { recursive: true });
  writeFileSync(join(root, rel), text);
};

test('sources: node_modules, dot-directories and links under the source directory are never project code (#92 S12)', () => {
  const dir = mkdtempSync(join(tmpdir(), 'joust-sources-'));
  const contracts = join(dir, 'contracts');
  put(contracts, 'app/Counter.jsc', 'jsc 2\npackage app\n');
  put(contracts, 'app/Require.java', 'package app; public final class Require {}');
  // The issue's case: a package installed under contracts/ ships a Require of the project's package.
  put(contracts, 'node_modules/evil/joust-lib/app/Require.java', 'package app; public final class Require { /* evil */ }');
  put(contracts, 'node_modules/@scope/pkg/joust-lib/app/Other.jsc', 'jsc 2\nlibrary app\n');
  put(contracts, 'app/node_modules/deep/X.jsc', 'jsc 2\npackage app\n');
  put(contracts, '.cache/app/Hidden.jsc', 'jsc 2\npackage app\n');
  put(contracts, 'app/.idea/Y.java', 'package app; class Y {}');
  // Links: to a file outside, and to a directory outside, are not followed.
  put(dir, 'outside/app/Linked.jsc', 'jsc 2\npackage app\n');
  symlinkSync(join(dir, 'outside/app/Linked.jsc'), join(contracts, 'app/Linked.jsc'));
  symlinkSync(join(dir, 'outside'), join(contracts, 'shared'));
  const files = sources(contracts);
  assert.deepEqual(Object.keys(files).sort(), ['app/Counter.jsc', 'app/Require.java']);
  assert.doesNotMatch(files['app/Require.java'], /evil/);
  // contracts: "." — the project root is the source directory: its node_modules is a package tree, not sources.
  put(dir, 'node_modules/evil/joust-lib/app/Require.java', 'package app; public final class Require { /* evil */ }');
  const root = sources(dir);
  assert.ok(!Object.keys(root).some((k) => k.includes('node_modules')), Object.keys(root).join(', '));
  assert.ok(!Object.keys(root).some((k) => k.startsWith('contracts/shared') || k === 'contracts/app/Linked.jsc'), Object.keys(root).join(', '));
  assert.deepEqual(Object.keys(root).filter((k) => k.endsWith('Require.java')), ['contracts/app/Require.java']);
});
