// ADR-0021 L10, measured as the server runs the engine (EngineHost: a worker, the model's JSON posted back and parsed on
// the protocol thread, i.e. INCLUDING the model's export across the engine boundary):
//   - analysis latency, median and p95 over 100 warm calls, for Ledger.jsc and the 509- and 1,889-line files made from it
//     (L10: p95 at most 50 ms for the 1,889-line file);
//   - the process's RSS with one analysis worker (L10: at most 600 MB), with two analysis workers, and with two analysis
//     workers and a build worker after three Ledger builds (D4's largest configuration; L10: at most 1.2 GB).
// Usage: node js/joust/test/lsp/measure-l10.mjs [--check]   (--check: fail past CI's looser bounds, 250 ms p95 (about 3x
// what CI's runner measured) and the RSS bounds + 50%, since CI hardware differs; the strict figures are what the PR reports).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Worker } from 'node:worker_threads';
import { EngineHost } from '../../src/lsp/host.js';
import { ENGINE, HAS_ENGINE } from './client.js';

if (!HAS_ENGINE) {
  console.log('skipped: no engine in js/joust/engine');
  process.exit(0);
}
const check = process.argv.includes('--check');
const repo = fileURLToPath(new URL('../../../../', import.meta.url));
const ledger = readFileSync(join(repo, 'examples/dsl/src/main/jsc/org/julclang/state/examples/dsl/Ledger.jsc'), 'utf8');
const grow = (n) => {
  let fns = '';
  for (let k = 0; k < n; k++) fns += `fn extra${k}(x: Int): Int {\n  const y = x + ${k}\n  const z = y * 2\n  z - x\n}\n\n`;
  return ledger.replace(/\ncontract /, `\n${fns}contract `);
};
const files = [['Ledger.jsc', ledger], ['Ledger509.jsc', grow(70)], ['Ledger1889.jsc', grow(300)]];
const mb = () => Math.round(process.memoryUsage().rss / 1048576);
const pct = (xs, p) => [...xs].sort((a, b) => a - b)[Math.min(xs.length - 1, Math.floor(xs.length * p))];

const host = new EngineHost({ workerEntry: new URL('../../src/lsp/worker.js', import.meta.url), analysisMs: 10000 });
const engine = { file: join(ENGINE, 'joust-toolchain.js'), id: 'measure', source: 'bundled' };
const out = { latency: {}, rss: {} };
await host.info('a', engine);
for (const [name, src] of files) {
  const lines = src.split('\n').length;
  const ts = [];
  for (let k = 0; k < 110; k++) {
    const t = performance.now();
    const r = await host.analyze('a', engine, { file: 'Ledger.jsc', source: src + ' '.repeat(k) });
    JSON.parse(r.model);
    if (k >= 10) ts.push(performance.now() - t);
  }
  out.latency[name] = { lines, median: +pct(ts, 0.5).toFixed(2), p95: +pct(ts, 0.95).toFixed(2), modelChars: (await host.analyze('a', engine, { file: 'Ledger.jsc', source: src })).model.length };
}
out.rss.oneAnalysisWorker = mb();
await host.info('b', { ...engine, id: 'measure-b' });
await host.analyze('b', { ...engine, id: 'measure-b' }, { file: 'Ledger.jsc', source: ledger });
out.rss.twoAnalysisWorkers = mb();
// A build worker as M5b will start it (joust build's: 8 MB stack, D2a's 384 MB heap), three Ledger builds.
const build = new Worker(new URL('../../src/toolchain-worker.js', import.meta.url), { resourceLimits: { stackSizeMb: 8, maxOldGenerationSizeMb: 384 } });
const builds = [];
for (let k = 0; k < 3; k++) {
  const t = performance.now();
  // The build worker's protocol since #80 (M1h): it says it is ready, then that a build started, then the result; only
  // the result answers (the integration of M1h with M5a).
  const r = await new Promise((resolve, reject) => {
    const on = (m) => {
      if (m?.id !== k || (m.result === undefined && m.error === undefined)) return;
      build.off('message', on);
      resolve(m);
    };
    build.on('message', on);
    build.once('error', reject);
    build.postMessage({ id: k, files: { 'org/julclang/state/examples/dsl/Ledger.jsc': ledger }, options: {}, dir: ENGINE });
  });
  if (r.error || !r.result?.ok) throw new Error(`build failed: ${r.error ?? JSON.stringify(r.result?.diagnostics)}`);
  builds.push(Math.round(performance.now() - t));
}
out.rss.twoAnalysisWorkersAndABuildWorker = mb();
out.builds = builds;
await build.terminate();
host.dispose();
console.log(JSON.stringify(out, null, 2));

const bound = (v, strict, loose) => (check ? v <= loose : true) && { v, strict, withinStrict: v <= strict };
const results = [
  // CI's runner measured 86.7 ms (run 43350031856); its bound is set about 3x above that, to catch regressions without
  // flaking on a noisy runner (#83 review F9). L10's strict 50 ms is for §1's reference machine.
  ['p95 (1,889 lines)', bound(out.latency['Ledger1889.jsc'].p95, 50, 250)],
  ['RSS, one analysis worker (MB)', bound(out.rss.oneAnalysisWorker, 600, 900)],
  ['RSS, two analysis workers and a build worker (MB)', bound(out.rss.twoAnalysisWorkersAndABuildWorker, 1229, 1800)],
];
let failed = 0;
for (const [what, r] of results) {
  if (!r) {
    failed++;
    console.log(`FAIL ${what}: past CI's bound`);
  } else console.log(`${r.withinStrict ? 'within' : 'OVER'} L10: ${what} ${r.v} (L10 ${r.strict})`);
}
process.exitCode = failed ? 1 : 0;   // not process.exit: a piped stdout would be cut short
