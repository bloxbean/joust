// Issue #74 (security S7, design F8 on #71): the toolchain engine's build worker has validated limits. Its stack,
// heap and time limit come from JOUST_ENGINE_STACK_MB, JOUST_ENGINE_MEMORY_MB and JOUST_ENGINE_TIMEOUT_S; an invalid
// setting is refused with its name (it was ignored); a build past its time stops the worker and rejects; and the next
// build gets a new worker. buildInWorker is exported, for callers of loadToolchain that need the 8 MB stack.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { engineLimits, buildInWorker, ENGINE_STACK_MB, ENGINE_MEMORY_MB, ENGINE_TIMEOUT_S } from '../src/toolchain.js';
import * as joust from '../src/index.js';
import { build } from '../src/build.js';

const ENGINE = existsSync(fileURLToPath(new URL('../engine/joust-toolchain.js', import.meta.url)));

test('the limits come from the environment, validated; an invalid value names its variable', () => {
  assert.deepEqual(engineLimits({}), { stackMb: ENGINE_STACK_MB, memoryMb: ENGINE_MEMORY_MB, timeoutS: ENGINE_TIMEOUT_S });
  assert.equal(ENGINE_STACK_MB, 8, 'the stack floor (ADR-0018 J18): 8 MB');
  assert.deepEqual(engineLimits({ JOUST_ENGINE_STACK_MB: '16', JOUST_ENGINE_MEMORY_MB: '2048', JOUST_ENGINE_TIMEOUT_S: '30' }),
    { stackMb: 16, memoryMb: 2048, timeoutS: 30 });
  assert.deepEqual(engineLimits({ JOUST_ENGINE_STACK_MB: '' }).stackMb, ENGINE_STACK_MB, 'empty is unset');
  for (const bad of ['abc', '0', '-5', '1.5', '8MB', '0x10', '1e3', '2000']) {
    assert.throws(() => engineLimits({ JOUST_ENGINE_STACK_MB: bad }), new RegExp(`JOUST_ENGINE_STACK_MB=${JSON.stringify(bad).replace(/[.+-]/g, '\\$&')} is not a whole number from 1 to 1024`), bad);
  }
  assert.throws(() => engineLimits({ JOUST_ENGINE_MEMORY_MB: '10' }), /JOUST_ENGINE_MEMORY_MB="10" is not a whole number from 64 to 65536/);
  assert.throws(() => engineLimits({ JOUST_ENGINE_TIMEOUT_S: '0' }), /JOUST_ENGINE_TIMEOUT_S="0" is not a whole number from 1 to 86400/);
  // Exported beside loadToolchain (whose builds run on the caller's thread).
  assert.equal(joust.buildInWorker, buildInWorker);
  assert.equal(typeof joust.loadToolchain, 'function');
  assert.equal(joust.engineLimits, engineLimits);
});

test('joust build refuses an invalid limit before it runs anything', async () => {
  const root = mkdtempSync(join(tmpdir(), 'joust-limits-'));
  mkdirSync(join(root, 'contracts', 'app'), { recursive: true });
  writeFileSync(join(root, 'contracts', 'app', 'C.jsc'), 'jsc 2\npackage app\ncontract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n');
  const before = process.env.JOUST_ENGINE_STACK_MB;
  process.env.JOUST_ENGINE_STACK_MB = 'abc';
  try {
    await assert.rejects(build({ root, log: () => {} }), /JOUST_ENGINE_STACK_MB="abc" is not a whole number/);
  } finally {
    if (before === undefined) delete process.env.JOUST_ENGINE_STACK_MB;
    else process.env.JOUST_ENGINE_STACK_MB = before;
  }
});

const files = { 'app/C.jsc': 'jsc 2\npackage app\ncontract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n'
  + '  action inc(by: Int) {\n    require(by > 0, "positive")\n    return { ...state, n: n + by }\n  }\n}\n' };

test('a build past its time limit stops the worker and rejects; the next build gets a new worker', { skip: ENGINE ? false : 'no engine (js/joust/engine)', timeout: 300_000 }, async () => {
  const t = Date.now();
  // Loading the engine alone takes longer than 50 ms.
  await assert.rejects(buildInWorker({ files, limits: { timeoutS: 0.05 } }), /did not finish within 0.05 s \(JOUST_ENGINE_TIMEOUT_S\): its worker was stopped/);
  assert.ok(Date.now() - t < 30_000, 'rejected at the limit, not when the build ends');
  const r = await buildInWorker({ files });
  assert.equal(r.ok, true, JSON.stringify(r.diagnostics).slice(0, 400));
  assert.deepEqual(r.contracts.map((c) => c.name), ['C']);
});

test('a worker that exceeds its heap limit rejects its build, never the process', { skip: ENGINE ? false : 'no engine (js/joust/engine)', timeout: 300_000 }, async () => {
  await assert.rejects(buildInWorker({ files, limits: { memoryMb: 1 } }), /the toolchain engine's worker (failed|exited)/);
  // The process is still here, and a build with the default limits succeeds.
  const r = await buildInWorker({ files });
  assert.equal(r.ok, true);
});
