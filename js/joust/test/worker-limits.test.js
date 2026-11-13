// Issue #74 (security S7, design F8 on #71) and #80's review (C2, S4, F3): the toolchain engine's build worker has
// validated limits. Its stack, heap and time limit come from JOUST_ENGINE_STACK_MB, JOUST_ENGINE_MEMORY_MB and
// JOUST_ENGINE_TIMEOUT_S, or a caller's `limits`, in the same ranges; an invalid one is refused with its name. The
// worker's effective heap limit is checked against the configured one (an inherited --max-old-space-size overrides it).
// A build's time counts from when the worker starts it; a build past it stops the worker. No worker is left behind.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { engineLimits, buildInWorker, liveWorkers, inheritedHeapFlags, heapProblem, ENGINE_STACK_MB, ENGINE_MEMORY_MB,
  ENGINE_TIMEOUT_S } from '../src/toolchain.js';
import * as joust from '../src/index.js';
import { build } from '../src/build.js';

const ENGINE = existsSync(fileURLToPath(new URL('../engine/joust-toolchain.js', import.meta.url)));
const TOOLCHAIN = new URL('../src/toolchain.js', import.meta.url).href;
const settled = async () => { for (let i = 0; i < 100 && liveWorkers() > 0; i++) await new Promise((r) => setTimeout(r, 50)); };
const quote = (v) => JSON.stringify(v).replace(/[.+-]/g, '\\$&');

test('the limits come from the environment and from the caller, validated in the same ranges', () => {
  assert.deepEqual(engineLimits({}), { stackMb: ENGINE_STACK_MB, memoryMb: ENGINE_MEMORY_MB, timeoutS: ENGINE_TIMEOUT_S });
  assert.equal(ENGINE_STACK_MB, 8, 'the stack floor (ADR-0018 J18): 8 MB');
  assert.deepEqual(engineLimits({ JOUST_ENGINE_STACK_MB: '16', JOUST_ENGINE_MEMORY_MB: '2048', JOUST_ENGINE_TIMEOUT_S: '30' }),
    { stackMb: 16, memoryMb: 2048, timeoutS: 30 });
  assert.deepEqual(engineLimits({ JOUST_ENGINE_STACK_MB: '' }).stackMb, ENGINE_STACK_MB, 'empty is unset');
  for (const bad of ['abc', '0', '-5', '1.5', '8MB', '0x10', '1e3', '2000', '4', '7']) {
    assert.throws(() => engineLimits({ JOUST_ENGINE_STACK_MB: bad }), new RegExp(`JOUST_ENGINE_STACK_MB=${quote(bad)} is not a whole number from 8 to 1024`), bad);
  }
  assert.throws(() => engineLimits({ JOUST_ENGINE_MEMORY_MB: '128' }), /JOUST_ENGINE_MEMORY_MB="128" is not a whole number from 256 to 65536/);
  assert.throws(() => engineLimits({ JOUST_ENGINE_TIMEOUT_S: '0' }), /JOUST_ENGINE_TIMEOUT_S="0" is not a whole number from 1 to 86400/);
  // A caller's limits (S4): the same ranges, named as limits.<key>.
  assert.deepEqual(engineLimits({}, { stackMb: 16, timeoutS: '30' }), { stackMb: 16, memoryMb: ENGINE_MEMORY_MB, timeoutS: 30 });
  for (const [k, v, range] of [['stackMb', 0, '8 to 1024'], ['stackMb', 4, '8 to 1024'], ['timeoutS', 3_000_000, '1 to 86400'],
    ['timeoutS', 'abc', '1 to 86400'], ['timeoutS', 0.05, '1 to 86400'], ['memoryMb', 64, '256 to 65536'], ['memoryMb', -1, '256 to 65536']]) {
    assert.throws(() => engineLimits({}, { [k]: v }), new RegExp(`limits\\.${k}=${quote(v)} is not a whole number from ${range}`), `${k}=${v}`);
    assert.throws(() => buildInWorker({ files: {}, limits: { [k]: v } }), /is not a whole number/, `buildInWorker ${k}=${v}`);
  }
  assert.throws(() => engineLimits({ JOUST_ENGINE_STACK_MB: 'abc' }, { stackMb: 16 }), /JOUST_ENGINE_STACK_MB/, 'the environment is checked too');
  // Exported beside loadToolchain (whose builds run on the caller's thread).
  assert.equal(joust.buildInWorker, buildInWorker);
  assert.equal(typeof joust.loadToolchain, 'function');
  assert.equal(joust.engineLimits, engineLimits);
});

test('the old generation is measured: an inherited old-space flag is refused, a young-generation flag is not (C2, F5)', () => {
  assert.deepEqual(inheritedHeapFlags([], ''), []);
  assert.deepEqual(inheritedHeapFlags(['--max-old-space-size=512'], '--max_old_space_size=8192 --max-semi-space-size=64 --enable-source-maps'),
    ['--max-old-space-size=512', '--max_old_space_size=8192'], 'a semi-space flag is not an old-generation flag');
  // The build worker at memoryMb and the probe at memoryMb + 64: honoured, they differ by 64, whatever the young generation.
  assert.equal(heapProblem(304, 368, 256, []), null);
  assert.equal(heapProblem(448, 512, 256, []), null, '--max-semi-space-size=64: +192 MB of young generation, still the old cap');
  // Overridden, both report the same limit: refused even inside any allowance (Codex r2: 320 against 256 gave 368).
  assert.match(heapProblem(368, 368, 256, ['--max-old-space-size=320']),
    /not have the configured heap \(JOUST_ENGINE_MEMORY_MB=256\).*inherited Node flag --max-old-space-size=320.*set JOUST_ENGINE_MEMORY_MB=320/);
  assert.match(heapProblem(560, 560, 4096, ['--max-old-space-size=512']), /set JOUST_ENGINE_MEMORY_MB=512/);
  assert.match(heapProblem(176, 176, 256, ['--max-old-space-size=128']), /remove the flag$/, 'no advice out of range');
  assert.match(heapProblem(2096, 2096, 256, ['--max-heap-size=2048']), /remove the flag$/);
  assert.equal(heapProblem(304, 304, 256, ['--max-old-space-size=256']), null, 'a flag equal to the configured limit sets that limit');
});

/** Runs buildInWorker in a child Node with these environment additions; returns its JSON line. */
function child(env, files = '{}', limits = '{}', args = []) {
  const script = join(mkdtempSync(join(tmpdir(), 'joust-heap-')), 'child.mjs');
  writeFileSync(script, `import { buildInWorker } from ${JSON.stringify(TOOLCHAIN)};
    try { const r = await buildInWorker({ files: ${files}, limits: ${limits} }); console.log(JSON.stringify({ ok: r.ok })); }
    catch (e) { console.log(JSON.stringify({ error: e.message })); }
    process.exit(0);`);
  return JSON.parse(execFileSync(process.execPath, [...args, script], { encoding: 'utf8', env: { ...process.env, ...env }, timeout: 300_000 }).trim());
}

test('a build under an inherited --max-old-space-size is refused before it runs, near the limit too (C2)', () => {
  for (const [flag, mb] of [['8192', '256'], ['320', '256'], ['260', '256'], ['512', '4096']]) {
    const r = child({ NODE_OPTIONS: `--max-old-space-size=${flag}`, JOUST_ENGINE_MEMORY_MB: mb });
    assert.match(r.error ?? '', new RegExp(`JOUST_ENGINE_MEMORY_MB=${mb}\\).*inherited Node flag --max-old-space-size=${flag}`), `${flag} at ${mb}: ${JSON.stringify(r)}`);
  }
  const direct = child({ JOUST_ENGINE_MEMORY_MB: '256' }, '{}', '{}', ['--max-old-space-size=320']);
  assert.match(direct.error ?? '', /inherited Node flag --max-old-space-size=320/, 'on the command line too');
});

test('a young-generation flag does not trip the check, and a matching old-space flag is accepted (F5)', { skip: ENGINE ? false : 'no engine (js/joust/engine)', timeout: 300_000 }, () => {
  const files = JSON.stringify({ 'app/C.jsc': 'jsc 2\npackage app\ncontract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n' });
  assert.deepEqual(child({ NODE_OPTIONS: '--max-semi-space-size=64', JOUST_ENGINE_MEMORY_MB: '4096' }, files), { ok: true });
  assert.deepEqual(child({ NODE_OPTIONS: '--max-old-space-size=512', JOUST_ENGINE_MEMORY_MB: '512' }, files), { ok: true });
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

test('a build that cannot be sent fails at once and stops its worker (S4)', async () => {
  const t = Date.now();
  await assert.rejects(buildInWorker({ files: { 'a/B.jsc': () => {} }, limits: { timeoutS: 600 } }), /could not be cloned/);
  await settled();
  assert.equal(liveWorkers(), 0, 'no worker keeps the process alive');
  assert.ok(Date.now() - t < 10_000);
});

const small = { 'app/C.jsc': 'jsc 2\npackage app\ncontract C {\n  state { n: Int }\n  init() { return { n: 0 } }\n'
  + '  action inc(by: Int) {\n    require(by > 0, "positive")\n    return { ...state, n: n + by }\n  }\n}\n' };
const chain = (n) => {
  let s = '    if x == 0 { require(x >= 0, "a0") }';
  for (let k = 1; k < n; k++) s += ` else if x == ${k} { require(x != ${k}, "a${k}") }`;
  return { 'app/W.jsc': `jsc 2\npackage app\ncontract W {\n  state { n: Int }\n  init() { return { n: 0 } }\n  action a(x: Int) {\n${s}`
    + ` else { require(x > ${n}, "z") }\n    return { ...state, n: x }\n  }\n}\n` };
};
const skipNoEngine = ENGINE ? false : 'no engine (js/joust/engine)';

test('a build past its time limit stops the worker and rejects; the next build gets a new worker', { skip: skipNoEngine, timeout: 300_000 }, async () => {
  const t = Date.now();
  // A cold engine takes about 13 s on 1,000 branches.
  await assert.rejects(buildInWorker({ files: chain(1000), limits: { timeoutS: 1 } }), /did not finish within 1 s \(JOUST_ENGINE_TIMEOUT_S\): its worker was stopped/);
  assert.ok(Date.now() - t < 8_000, `rejected at the limit, not when the build ends (${Date.now() - t} ms)`);
  const r = await buildInWorker({ files: small });
  assert.equal(r.ok, true, JSON.stringify(r.diagnostics).slice(0, 400));
  assert.deepEqual(r.contracts.map((c) => c.name), ['C']);
});

test('a queued build\'s time counts from its start, not from its wait (F3)', { skip: skipNoEngine, timeout: 300_000 }, async () => {
  const t = Date.now();
  const long = buildInWorker({ files: chain(1000), limits: { timeoutS: 120 } });
  const queued = buildInWorker({ files: small, limits: { timeoutS: 5 } });   // waits about 13 s behind the first
  const [a, b] = await Promise.all([long, queued]);
  assert.equal(a.ok, true);
  assert.equal(b.ok, true);
  assert.ok(Date.now() - t > 5_000, 'the queued build waited longer than its own limit');
});

test('the engine\'s heap is bounded: a build that needs more than the limit is refused; with more, it builds (F3)', { skip: skipNoEngine, timeout: 900_000 }, async () => {
  // 2,500 branches need more than 256 MB of heap; the engine (WasmGC) allocates on the worker's JS heap.
  await assert.rejects(buildInWorker({ files: chain(2500), limits: { memoryMb: 256 } }),
    /reaching memory limit.*\(JOUST_ENGINE_MEMORY_MB=256\)/);
  const r = await buildInWorker({ files: chain(2500), limits: { memoryMb: 1024 } });
  assert.equal(r.ok, true, 'the same build, with a larger limit');
});

test('concurrent builds with different limits leave no worker behind (S4)', { skip: skipNoEngine, timeout: 300_000 }, async () => {
  const [a, b] = await Promise.all([buildInWorker({ files: small, limits: { stackMb: 8 } }), buildInWorker({ files: small, limits: { stackMb: 16 } })]);
  assert.equal(a.ok && b.ok, true);
  await new Promise((r) => setTimeout(r, 500));
  assert.equal(liveWorkers(), 1, 'the replaced worker is stopped once its build settles; the current one stays for later builds');
});
