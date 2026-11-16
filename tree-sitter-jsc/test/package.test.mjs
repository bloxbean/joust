// ADR-0021 r10 §16 (M5e) E6: the tree-sitter package is complete, its queries compile against the grammar it ships
// (Helix's translation included), a broken query fails the build, the archive is reproducible, and its parser compiles.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { build, checkQuery, helixQuery } from '../scripts/package.mjs';

test('the package: complete, reproducible, its queries compiled; a broken query fails (E6)', { timeout: 600_000 }, () => {
  const a = build();
  const b = build({ generate: false });
  assert.equal(a.sha256, b.sha256, 'two builds from one commit are byte-identical');
  assert.deepEqual(a.archive, b.archive);
  const top = a.name.replace(/\.tar\.gz$/, '');
  for (const need of ['src/parser.c', 'src/scanner.c', 'src/grammar.json', 'src/node-types.json', 'src/tree_sitter/parser.h',
    'queries/highlights.scm', 'queries/folds.scm', 'neovim/queries/jsc/highlights.scm', 'neovim/queries/jsc/folds.scm',
    'helix/queries/jsc/highlights.scm', 'tree-sitter.json', 'grammar.js', 'LICENSE', 'EDITORS.md']) {
    assert.ok(a.entries.includes(`${top}/${need}`), need);
  }
  assert.ok(Object.values(a.checked).every((n) => n > 0), JSON.stringify(a.checked));
  // Helix's captures: none of nvim-treesitter's names Helix lacks remains.
  const hx = helixQuery(readFileSync(new URL('../queries/highlights.scm', import.meta.url), 'utf8'));
  for (const c of ['@boolean', '@number', '@module', '@keyword.conditional', '@function.call', '@string.escape', '@property ']) {
    assert.ok(!hx.includes(c), c);
  }
  assert.match(hx, /@constructor/);   // not Object.prototype.constructor
  // A query naming a node the grammar lacks fails.
  const dir = mkdtempSync(join(tmpdir(), 'ts-jsc-'));
  writeFileSync(join(dir, 'bad.scm'), '(no_such_node) @x\n');
  assert.throws(() => checkQuery(join(dir, 'bad.scm')), /Invalid node type no_such_node/);
  // Extracted by the system tar, its parser compiles as the editors build it.
  writeFileSync(join(dir, a.name), a.archive);
  const x = spawnSync('tar', ['xzf', a.name], { cwd: dir, encoding: 'utf8' });
  assert.equal(x.status, 0, x.stderr);
  const cc = spawnSync('cc', ['-O1', '-shared', '-fPIC', '-Isrc', 'src/parser.c', 'src/scanner.c', '-o', 'jsc.so'], { cwd: join(dir, top), encoding: 'utf8' });
  if (cc.error) return;   // no C compiler here
  assert.equal(cc.status, 0, cc.stderr);
});
