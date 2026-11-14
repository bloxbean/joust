// Review round r8 on #83:
//   S16  install publishes a copy already marked as used: no collector can take it before put's touch;
//   C33  a moved-aside copy is deleted only when proven safe (its bytes fail their digest, or a valid copy is in place);
//   C34  the C31 races, made deterministic: each test fails with its protection removed.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, promises as fsp, readdirSync, readFileSync, renameSync } from 'node:fs';
import { basename, join } from 'node:path';
import { createStore, ENGINE_JS, ENGINE_WASM } from '../../src/lsp/select.js';
import { STUB, tmp } from './client.js';

const stubBytes = (tag) => [Buffer.from(readFileSync(join(STUB, ENGINE_JS), 'utf8').replaceAll('test-stub', `test-stub-${tag}`)),
  readFileSync(join(STUB, ENGINE_WASM))];
const deadPid = () => Number(execFileSync(process.execPath, ['-e', 'process.stdout.write(String(process.pid))'], { encoding: 'utf8' }));
const HEX = /^[0-9a-f]{64}$/;

test('S16: a copy is published already marked as used; a collector running right after the rename leaves it', async () => {
  const storage = tmp('joust-lsp-store-');
  const engines = join(storage, 'engines');
  // B: another window that keeps no copies beyond the fresh ones, and records what it claims.
  const claimed = [];
  const b = createStore(storage, { maxCopies: 0, recentMs: 60_000, fs: { ...fsp,
    rename: async (from, to) => {
      if (HEX.test(basename(String(from))) && basename(String(to)).startsWith('.evicted-')) claimed.push(basename(String(from)));
      return fsp.rename(from, to);
    } } });
  let seen;
  let published = false;
  const a = createStore(storage, { maxCopies: 10, recentMs: 0, fs: { ...fsp,
    rename: async (from, to) => {
      const r = await fsp.rename(from, to);
      if (!published && basename(String(from)).startsWith('.tmp-') && HEX.test(basename(String(to)))) {
        published = true;
        seen = existsSync(join(String(to), '.used'));
        const [jb, wb] = stubBytes('s16-b');
        await b.put(jb, wb);   // B collects between A's rename and A's touch
      }
      return r;
    } } });
  const [ja, wa] = stubBytes('s16-a');
  const c = await a.put(ja, wa);
  assert.equal(published, true);
  assert.equal(seen, true, 'the copy carries .used when it appears');
  assert.deepEqual(claimed.filter((n) => n === c.id), [], 'B never claimed the fresh copy');
  assert.ok(existsSync(c.file));
});
