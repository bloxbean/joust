// npm create joust's default app (a Node script) on Yaci DevKit, with each contract language: create-joust writes the
// project, joust build and joust deploy run on it, and `node index.js` creates a counter, increments it, sees the
// contract refuse +0 and closes it. The packages are linked from this repository (no registry).
// Runs when DevKit is up (Yaci Store on :8080).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtempSync, mkdirSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from '../src/build.js';
import { deploy } from '../src/deploy.js';
import { loadConfig } from '../src/config.js';
import { scaffold } from '../../create-joust/src/scaffold.js';

const JS = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const up = await fetch('http://localhost:8080/api/v1/blocks/latest').then((r) => r.ok).catch(() => false);
const skip = up ? false : 'DevKit not reachable';

for (const lang of ['jsc', 'java']) {
  test(`npm create joust (node, ${lang}): build, deploy, node index.js`, { skip, timeout: 600_000 }, async () => {
    const root = join(mkdtempSync(join(tmpdir(), 'joust-create-')), 'app');
    scaffold({ dir: root, template: 'node', lang });
    const link = (name, target) => {
      mkdirSync(dirname(join(root, 'node_modules', name)), { recursive: true });
      symlinkSync(target, join(root, 'node_modules', name));
    };
    link('@julclang/joust', join(JS, 'joust'));
    link('@julclang/joust-runtime', join(JS, 'joust-runtime'));
    link('@julclang/state-client', join(JS, 'state-client'));
    link('@scure/bip39', join(JS, 'joust', 'node_modules', '@scure', 'bip39'));

    assert.ok((await build({ root, src: 'contracts', out: 'build/joust', log: () => {} })).ok);
    await deploy({ root, config: loadConfig(root), log: () => {} });
    const { stdout } = await promisify(execFile)(process.execPath, ['index.js'], { cwd: root, timeout: 300_000 });
    assert.match(stdout, /^created +[0-9a-f]{64} tx [0-9a-f]{64}$/m, stdout);
    assert.match(stdout, /^count +7n$/m, stdout);
    assert.match(stdout, /^refused +increment must be positive$/m, stdout);
    assert.match(stdout, /^closed +tx [0-9a-f]{64}$/m, stdout);
    assert.match(stdout, /^state +null$/m, stdout);
  });
}
