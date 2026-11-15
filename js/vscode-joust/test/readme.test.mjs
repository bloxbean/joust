// #121 MF2 (final pass): the README ships in the .vsix (the Marketplace and Open VSX page): every command a user can run
// (package.json's contributes.commands, less those hidden from the palette) is in its Commands table, and it describes
// the current milestones, not M5a alone.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
const readme = readFileSync(new URL('../README.md', import.meta.url), 'utf8');

test('every command a user can run is in the README\'s Commands table', () => {
  const hidden = new Set((pkg.contributes.menus?.commandPalette ?? []).filter((m) => m.when === 'false').map((m) => m.command));
  const shown = pkg.contributes.commands.filter((c) => !hidden.has(c.command));
  assert.ok(shown.length >= 10, shown.length);
  const table = readme.slice(readme.indexOf('## Commands'), readme.indexOf('\n## ', readme.indexOf('## Commands') + 3));
  for (const c of shown) assert.ok(table.includes(`| ${c.category}: ${c.title} |`), `${c.category}: ${c.title} is not in the README's Commands table`);
});

test('the README describes M5a–M5e: formatting, the build on save with the ABI and joust.lock checks, completion, rename', () => {
  assert.ok(!/milestone M5a\)/.test(readme) && !/comes in M5b/.test(readme), 'no M5a-only statements');
  for (const k of ['Formatting', 'joust fmt', 'build on save', 'joust.lock', 'Completion', 'Rename Symbol', 'Hover', 'Quick fixes']) {
    assert.ok(readme.toLowerCase().includes(k.toLowerCase()), k);
  }
  // #126 (MF6): completion adds an import only for a standard-library row; another file's name is offered once imported.
  assert.ok(readme.includes('names in scope (an imported name included), fields, and the standard library (choosing a row adds its\n  import)'));
  assert.ok(!/names from other files/.test(readme));
});
