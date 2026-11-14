// Issue #74 (security S7, design F8 on #71) and #80's review (C2, S4, F3): the toolchain engine's build worker has
// validated limits. Its stack, heap and time limit come from JOUST_ENGINE_STACK_MB, JOUST_ENGINE_MEMORY_MB and
// JOUST_ENGINE_TIMEOUT_S, or a caller's `limits`, in the same ranges; an invalid one is refused with its name. The
// worker's effective heap limit is checked against the configured one (an inherited --max-old-space-size overrides it).
// A build's time counts from when the worker starts it; a build past it stops the worker. No worker is left behind.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { engineLimits, buildInWorker, liveWorkers, setEngineHostForTests, stripHeapFlags, hostEnv, parseNodeOptions, ENGINE_STACK_MB, ENGINE_MEMORY_MB,
  ENGINE_TIMEOUT_S } from '../src/toolchain.js';
import * as joust from '../src/index.js';
import { heapProblem, probeHeapLimit } from '../src/toolchain-heap.js';
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

test('the old generation is decided from measurements only: three workers 64 MB apart (C2, F5, C7, C8)', async () => {
  // Honoured, the build worker and its probes at -64 and +64 MB step by 64, whatever the young generation.
  assert.equal(heapProblem(304, 368, 240, 256), null);
  assert.equal(heapProblem(448, 512, 384, 256), null, '--max-semi-space-size=64: +192 MB of young generation, still the old cap');
  // Set elsewhere (an old-space flag of any value, the configured one included), the three are equal: refused.
  assert.match(heapProblem(368, 368, 368, 256), /not have the configured heap \(JOUST_ENGINE_MEMORY_MB=256\).*measured 368, 368 and 368 MB at 192, 256 and 320 MB/);
  assert.match(heapProblem(304, 304, 304, 256), /set elsewhere/, 'equal to the configured value is not provable by measurement');
  assert.match(heapProblem(304, 368, 304, 256), /set elsewhere/, 'both steps must hold');
  // A probe that exits, or fails, before it reports rejects (C10): never a promise left pending.
  await assert.rejects(probeHeapLimit(256, { code: 'process.exit(0)' }), /the heap probe exited \(code 0\) before it reported/);
  await assert.rejects(probeHeapLimit(256, { code: 'throw new Error("boom")' }), /boom/);
  assert.ok((await probeHeapLimit(256)) >= 256);
});

/** Runs a script in a child Node with these environment additions and arguments; returns its JSON line. */
function child(env, body, args = []) {
  const script = join(mkdtempSync(join(tmpdir(), 'joust-heap-')), 'child.mjs');
  writeFileSync(script, `import { buildInWorker } from ${JSON.stringify(TOOLCHAIN)};
    try { ${body} } catch (e) { console.log(JSON.stringify({ error: e.message })); }
    process.exit(0);`);
  return JSON.parse(execFileSync(process.execPath, [...args, script], { encoding: 'utf8', env: { ...process.env, ...env }, timeout: 600_000 }).trim());
}
const buildCall = (files, limits = '{}') => `const r = await buildInWorker({ files: ${files}, limits: ${limits} }); console.log(JSON.stringify({ ok: r.ok }));`;

test('inherited Node heap flags never reach the engine: the configured heap holds under them (C2, C7, C8)', { skip: ENGINE ? false : 'no engine (js/joust/engine)', timeout: 900_000 }, () => {
  const big = JSON.stringify(chain(2500));
  const small = JSON.stringify(chain(3));
  // 2,500 branches need more than 256 MB: under a large inherited old-space flag, and with NODE_OPTIONS rewritten to
  // claim the configured value before the build (Codex r3's C7), the engine still runs out at 256 MB.
  const oom = /reaching memory limit.*\(JOUST_ENGINE_MEMORY_MB=256\)/;
  const r1 = child({ NODE_OPTIONS: '--max-old-space-size=8192', JOUST_ENGINE_MEMORY_MB: '256' }, buildCall(big));
  assert.match(r1.error ?? '', oom, JSON.stringify(r1));
  const r2 = child({ NODE_OPTIONS: '--max-old-space-size=8192', JOUST_ENGINE_MEMORY_MB: '256' },
    `process.env.NODE_OPTIONS = '--max-old-space-size=256'; ${buildCall(big)}`);
  assert.match(r2.error ?? '', oom, JSON.stringify(r2));
  // The flag settings Codex r3's C8 found refused, now all built (the engine runs with the configured heap regardless).
  for (const [env, args] of [[{ NODE_OPTIONS: '--max-old-space-size=320' }, ['--max-old-space-size=256']], [{}, ['--max-old-space-size=320', '--max-old-space-size=256']],
    [{ NODE_OPTIONS: '--max-old-space-size="256"' }, []], [{ NODE_OPTIONS: '--max-semi-space-size=64' }, []], [{ NODE_OPTIONS: '--max-old-space-size=8192' }, []]]) {
    assert.deepEqual(child({ ...env, JOUST_ENGINE_MEMORY_MB: '256' }, buildCall(small), args), { ok: true }, JSON.stringify([env, args]));
  }
});

test('NODE_OPTIONS is read as Node reads it, its heap flags stripped by their decoded names, the rest kept exactly (C14, S12)', () => {
  // Node's tokenizer (src/node_options.cc ParseNodeOptionsEnvVar): only a space outside quotes splits; quotes toggle
  // anywhere and are dropped; inside quotes a backslash takes the next character.
  assert.deepEqual(parseNodeOptions('--max-"old-space-size"=256'), ['--max-old-space-size=256']);
  assert.deepEqual(parseNodeOptions('""--max-old-space-size=256'), ['--max-old-space-size=256']);
  assert.deepEqual(parseNodeOptions('"--max-\\old-space-size=256"'), ['--max-old-space-size=256']);
  assert.deepEqual(parseNodeOptions('--conditions=a\t--require'), ['--conditions=a\t--require'], 'a tab does not split');
  assert.deepEqual(parseNodeOptions('--conditions=a\n--import'), ['--conditions=a\n--import'], 'nor does a line feed');
  assert.deepEqual(parseNodeOptions('  --require  "./a b.mjs"  '), ['--require', './a b.mjs']);
  assert.throws(() => parseNodeOptions('"--require'), /unterminated string/);
  assert.throws(() => parseNodeOptions('"--require\\'), /invalid escape/);
  // Codex r5's three survivors are stripped; the tab and line-feed arguments are kept whole.
  for (const v of ['--max-"old-space-size"=256', '""--max-old-space-size=256', '"--max-\\old-space-size=256"', '--max_old_space_size=8',
    '-max-heap-size=9', '--max-old-space-size 512']) {
    assert.equal(stripHeapFlags(v), '', v);
  }
  for (const v of ['--conditions=a\t--require', '--conditions=a\n--import', '--require ./.pnp.cjs --import "./a b.mjs" --max-semi-space-size=64',
    '--title="a \\"quoted\\" \\\\ name"']) {
    assert.deepEqual(parseNodeOptions(stripHeapFlags(v)), parseNodeOptions(v), `kept exactly: ${JSON.stringify(v)}`);
  }
  assert.deepEqual(parseNodeOptions(stripHeapFlags('--require ./.pnp.cjs --max-old-space-size=8192 --import "./a b.mjs"')),
    ['--require', './.pnp.cjs', '--import', './a b.mjs']);
  assert.equal(stripHeapFlags(undefined), '');
});

test('NODE_OPTIONS case variants: all of them on Windows, only the exact key elsewhere (C12, S13)', () => {
  const win = (env) => hostEnv(env, 'win32');
  const posix = (env) => hostEnv(env, 'linux');
  const parsed = (env) => ({ ...env, ...(env.NODE_OPTIONS !== undefined ? { NODE_OPTIONS: parseNodeOptions(env.NODE_OPTIONS) } : {}) });
  // Windows reads the environment ignoring case: every variant goes, and one NODE_OPTIONS comes back, stripped.
  assert.deepEqual(parsed(win({ PATH: '/x', Node_Options: '--max-old-space-size=256 --require ./h.cjs', node_options: '--import x' })),
    { PATH: '/x', NODE_OPTIONS: ['--require', './h.cjs'] });
  assert.deepEqual(parsed(win({ node_options: '--import y', NODE_OPTIONS: '--require ./p.cjs --max-heap-size=9' })), { NODE_OPTIONS: ['--require', './p.cjs'] });
  assert.deepEqual(win({ Node_Options: '--max-old-space-size=256' }), {}, 'nothing left: no NODE_OPTIONS at all');
  // Elsewhere a node_options is an ordinary variable: kept as it is, never promoted to NODE_OPTIONS.
  assert.deepEqual(posix({ node_options: '--require ./evil.cjs', PATH: '/x' }), { node_options: '--require ./evil.cjs', PATH: '/x' });
  assert.deepEqual(parsed(posix({ node_options: 'x', NODE_OPTIONS: '--max-old-space-size=1 --require a' })), { node_options: 'x', NODE_OPTIONS: ['--require', 'a'] });
});

test('a NODE_OPTIONS preload reaches the engine host, its heap flag does not (F10)', { skip: ENGINE ? false : 'no engine (js/joust/engine)', timeout: 300_000 }, () => {
  const dir = mkdtempSync(join(tmpdir(), 'joust-preload-'));
  const marker = join(dir, 'ran.txt');
  writeFileSync(join(dir, 'preload.cjs'), `require('node:fs').appendFileSync(${JSON.stringify(marker)}, process.argv[1] + '\\n');`);
  const r = child({ NODE_OPTIONS: `--require ${join(dir, 'preload.cjs')} --max-old-space-size=8192`, JOUST_ENGINE_MEMORY_MB: '256' },
    buildCall(JSON.stringify(chain(3))));
  assert.deepEqual(r, { ok: true });
  assert.ok(readFileSync(marker, 'utf8').split('\n').some((l) => l.endsWith('toolchain-host.js')), 'the preload ran in the host');
});

test('a fork the OS refuses (descriptors exhausted) is not counted as a live host (C13)', { skip: process.platform === 'win32' ? 'POSIX only: ulimit and /dev/null' : false }, () => {
  const script = join(mkdtempSync(join(tmpdir(), 'joust-emfile-')), 'emfile.mjs');
  writeFileSync(script, `import fs from 'node:fs';
    import { buildInWorker, liveWorkers } from ${JSON.stringify(TOOLCHAIN)};
    const fds = [];
    try { for (;;) fds.push(fs.openSync('/dev/null', 'r')); } catch {}
    let error = null;
    try { await buildInWorker({ files: {}, limits: { timeoutS: 5 } }); } catch (e) { error = e.message; }
    for (const fd of fds) fs.closeSync(fd);
    await new Promise((r) => setTimeout(r, 200));
    console.log(JSON.stringify({ error, live: liveWorkers() }));
    process.exit(0);`);
  const out = JSON.parse(execFileSync('/bin/sh', ['-c', `ulimit -n 128; "${process.execPath}" "${script}"`], { encoding: 'utf8', timeout: 60_000 }).trim());
  assert.match(out.error ?? '', /EMFILE/, JSON.stringify(out));
  assert.equal(out.live, 0, JSON.stringify(out));
});

test('a fork of a missing Node fails each time, leaves nothing live, and the next good host is used (C13, C15)', { timeout: 300_000 }, async () => {
  const missing = join(tmpdir(), 'joust-no-such-node', process.platform === 'win32' ? 'node.exe' : 'node');
  try {
    for (let i = 0; i < 3; i++) {
      setEngineHostForTests(null, { execPath: missing });
      await assert.rejects(buildInWorker({ files: {}, limits: { timeoutS: 5 } }), /ENOENT/, `attempt ${i}`);
      await settled();
      assert.equal(liveWorkers(), 0, `attempt ${i}`);
    }
  } finally {
    setEngineHostForTests(null);
  }
  // Recovery: the next build gets JOUST's own host and builds (with the engine; without it, the host still starts and
  // the build reaches the worker, which reports the missing engine).
  if (ENGINE) {
    const r = await buildInWorker({ files: small });
    assert.equal(r.ok, true);
  } else {
    await assert.rejects(buildInWorker({ files: small, limits: { timeoutS: 30 } }), /joust-toolchain|engine|Cannot find/);
  }
  assert.equal(liveWorkers(), 1, 'one host, kept for later builds');
});

test('a host that does not start, or exits before it reports, rejects its builds and leaves nothing running (C10)', async () => {
  // The silent host is ended by a 1 s startup limit; the others get 10 s, so a slow process start under load is not
  // mistaken for the failure they show.
  for (const [host, why, timeoutS] of [['silent', /did not start within 1 s/, 1], ['exits', /host exited \(code 0\) before it answered/, 10],
    ['probe-exits', /heap probe failed: the heap probe exited \(code 0\) before it reported/, 10]]) {
    setEngineHostForTests(new URL(`./hosts/${host}.mjs`, import.meta.url));
    try {
      const t = Date.now();
      await assert.rejects(buildInWorker({ files: {}, limits: { timeoutS } }), why, host);
      assert.ok(Date.now() - t < 10_000, host);
      await settled();
      assert.equal(liveWorkers(), 0, `${host}: nothing left running`);
    } finally {
      setEngineHostForTests(null);
    }
  }
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
