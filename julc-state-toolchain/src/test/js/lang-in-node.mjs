// ADR-0021 M5a in Wasm: the language core's entries (lang.info, lang.analyze) in the toolchain engine.
//   parity  every corpus file's document model from the engine is byte-identical to the JVM's (JscLangTest writes
//           julc-state-dsl/build/lang-parity.json) (D12);
//   L1      for every .jsc 2 corpus file, the model's error is the engine's joust.build's front-end error (code, line,
//           column, message), and an accepted file's warnings are the build's;
//   depth   M1e's nesting cap (ADR-0018 J18) through lang.analyze, on a cold engine, on Node's main thread (about 1 MB,
//           the smallest stack) and on a worker at the server's stackSizeMb (4); the smallest worker stack that still
//           analyses every case at the cap is printed as the margin.
// Usage: node --experimental-wasm-exnref lang-in-node.mjs <engine dir> <lang-parity.json> [--no-build]

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, posix, resolve } from 'node:path';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';
import { fileURLToPath } from 'node:url';

const SERVER_STACK_MB = 4;   // js/joust/src/lsp/host.js ANALYSIS_STACK_MB
const REQUIRED_MARGIN_MB = 1;   // the cap's cases must hold on a quarter of it (C17)

const self = fileURLToPath(import.meta.url);
const load = (dir) => new Promise((ok) => {
  globalThis.onJulcReady = ok;
  createRequire(import.meta.url)(resolve(dir, 'joust-toolchain.js'));
});

const contract = (m) => `jsc 1\npackage t\ncontract D {\n  state { n: Int }\n  init() { return { n: 0 } }\n${m}\n}\n`;
const action = (v) => contract(`  action a(x: Int) { return { ...state, n: ${v} } }`);
const rep = (s, n) => s.repeat(n);
const blocks = (n) => contract(`  action a(x: Int) {\n    ${rep('if x > 0 { ', n)}require(x > 0, "p")${rep(' }', n)}\n    return state\n  }`);
const lambdas = (n) => {
  let s = 'xs.any(a0 => ';
  for (let k = 1; k < n; k++) s += `xs.any(a${k} => a${k - 1} > 0 && `;
  return contract(`  view v(xs: List<Int>): Bool = ${s}true${rep(')', n)}`);
};
const matches = (n) => {
  let s = '';
  for (let k = 0; k < n; k++) s += `match o { Some(v${k}) => v${k} + `;
  return contract(`  action a(o: Option<Int>) { return { ...state, n: ${s}0${rep(', None => 0 }', n)} } }`);
};
// The deepest each construct may nest under the cap (as depth-in-node.mjs), and past it.
const DEPTH = [
  ['parentheses 56', action(rep('(', 56) + 'x' + rep(')', 56)), null],
  ['parentheses 1000', action(rep('(', 1000) + 'x' + rep(')', 1000)), 'JSC0149'],
  ['blocks 25', blocks(25), null],
  ['blocks 200', blocks(200), 'JSC0149'],
  ['lambdas 12', lambdas(12), null],
  ['lambdas 200', lambdas(200), 'JSC0149'],
  ['match 12', matches(12), null],
  ['match 200', matches(200), 'JSC0149'],
  ['value ifs 20', action(rep('if x > 0 { ', 20) + 'x' + rep(' } else { 0 }', 20)), null],
  ['value ifs 200', action(rep('if x > 0 { ', 200) + 'x' + rep(' } else { 0 }', 200)), 'JSC0149'],
];

async function depthCase(dir, i) {
  const e = await load(dir);
  const [, src] = DEPTH[i];
  try {
    const r = e.lang.analyze({ file: 'D.jsc', source: src });
    if (!r.ok) return `failed ${r.error}`;
    const m = JSON.parse(r.model);
    const err = m.diagnostics.find((d) => d.severity === 'error');
    return err ? err.code : 'ok';
  } catch (x) {
    return `threw ${x}`;
  }
}

if (!isMainThread) {
  parentPort.postMessage(await depthCase(workerData.dir, workerData.i));
} else if (process.argv[2] === '--depth-case') {
  const [, , , dir, i, stack] = process.argv;
  const got = stack === 'main' ? await depthCase(dir, Number(i))
    : await new Promise((ok) => {
      const w = new Worker(self, { workerData: { dir, i: Number(i) }, resourceLimits: { stackSizeMb: Number(stack) } });
      w.on('message', ok);
      w.on('error', (e) => ok(`threw ${e}`));
    });
  console.log(got);
  process.exit(0);
} else {
  const [dir, parityFile] = process.argv.slice(2);
  const noBuild = process.argv.includes('--no-build');
  let failures = 0;
  const fail = (m) => { failures++; console.log('FAIL ' + m); };
  const engine = await load(dir);
  const info = JSON.parse(engine.lang.info({}).info);
  if (info.protocol !== 1 || info.modelVersion !== 1) fail(`lang.info ${JSON.stringify(info)}`);

  // 1. Parity with the JVM.
  const parity = JSON.parse(readFileSync(parityFile, 'utf8'));
  // An entry with an id is a file of the examples' project (the integration with ADR-0018 M2c and M4): analysed with the
  // project's files, which JscLangTest writes beside the parity file.
  // The files a project file's relative imports reach, transitively (a build of only those: the rest compile for nothing).
  const reachable = (all, id) => {
    const out = {};
    const todo = [id];
    while (todo.length) {
      const at = todo.pop();
      const text = all[at];
      if (text === undefined || at in out) continue;
      out[at] = text;
      for (const m of text.matchAll(/from\s*"(\.{1,2}\/[^"]+\.jsc)"/g)) todo.push(posix.normalize(posix.join(posix.dirname(at), m[1])));
    }
    return out;
  };
  const modulesFile = join(dirname(parityFile), 'lang-modules.json');
  const modules = existsSync(modulesFile) ? JSON.parse(readFileSync(modulesFile, 'utf8')) : {};
  const request = (p) => (p.id ? { file: p.file, source: p.source, id: p.id, files: modules } : { file: p.file, source: p.source });
  let withProject = 0;
  for (const p of parity) {
    if (p.id) withProject++;
    const r = engine.lang.analyze(request(p));
    if (!r.ok) fail(`[parity] ${p.key}: ${r.error}`);
    else if (r.model !== p.model) fail(`[parity] ${p.key}: the engine's model differs from the JVM's`);
  }
  console.log(`[lang wasm] parity: ${parity.length} document models (${withProject} in their project), JVM and Wasm byte-identical`);

  // 2. L1: the model's front-end diagnostics are the build's.
  if (!noBuild) {
    let n = 0;
    for (const p of parity) {
      if (!p.source.trimStart().startsWith('jsc')) continue;
      // The build builds the file as a file of a project (t/<file> when it is not an example), so the model compared is
      // the server's for a project file: lang.analyze with the same id and files (the integration with M2c and M4; a
      // file on its own says "lowered on its own" where the build names the project).
      const model = p.id ? JSON.parse(p.model) : JSON.parse(engine.lang.analyze({ file: p.file, source: p.source, id: `t/${p.file}`,
        files: { [`t/${p.file}`]: p.source } }).model);
      // A project's file is built with the project (its imports resolve), and only its own diagnostics are compared.
      const files = p.id ? { ...reachable(modules, p.id), [p.id]: p.source } : { [`t/${p.file}`]: p.source };
      const r = engine.joust.build({ files, options: {} });
      const own = p.id ? p.id : `t/${p.file}`;
      const jsc = r.diagnostics.filter((d) => d.source === 'jsc' && d.code !== 'JSC0004' && (!p.id || d.file === own || d.file === undefined));
      const fmt = (d) => `${d.severity.toLowerCase()} ${d.code} ${Number(d.line)}:${Number(d.col)} ${d.message}`;
      const want = jsc.map(fmt);
      const got = model.diagnostics.map(fmt);
      if (JSON.stringify(want) !== JSON.stringify(got)) fail(`[L1] ${p.key}:\n  build ${JSON.stringify(want)}\n  model ${JSON.stringify(got)}`);
      n++;
    }
    console.log(`[lang wasm] L1: ${n} .jsc 2 files, the model's diagnostics are joust.build's front-end diagnostics`);
  }

  // 3. The nesting cap on the smallest stacks, cold.
  const runCase = (i, stack) => {
    try {
      return execFileSync(process.execPath, ['--experimental-wasm-exnref', self, '--depth-case', dir, String(i), String(stack)],
        { encoding: 'utf8', maxBuffer: 1 << 24, stdio: ['ignore', 'pipe', 'ignore'] }).trim().split('\n').pop();
    } catch (e) {
      return `the process failed (${String(e.message).split('\n')[0]})`;   // a stack too small even to load the engine
    }
  };
  for (let i = 0; i < DEPTH.length; i++) {
    const [name, , want] = DEPTH[i];
    for (const stack of ['main', SERVER_STACK_MB]) {
      const got = runCase(i, stack);
      if (got !== (want ?? 'ok')) fail(`[depth] ${name} on ${stack}: ${got}`);
    }
  }
  console.log(`[lang wasm] depth: ${DEPTH.length} cases at and past the cap, cold, on the main thread and a ${SERVER_STACK_MB} MB worker`);
  // The margin: the smallest worker stack on which every case still gives its answer.
  let smallest = null;
  for (const mb of [2, 1, 0.5, 0.25, 0.125]) {
    const ok = DEPTH.every(([, , want], i) => runCase(i, mb) === (want ?? 'ok'));
    if (!ok) break;
    smallest = mb;
  }
  console.log(`[lang wasm] margin: every case holds on a worker stack of ${smallest ?? '(none below 4)'} MB`
    + (smallest ? `, ${SERVER_STACK_MB / smallest}x below the server's ${SERVER_STACK_MB} MB` : ''));
  // M5a's exit: the cap holds "with margin" (review C17 on #83): every case must hold at a quarter of the server's stack;
  // the search above for the smallest stack is a measurement on top.
  if (!(smallest !== null && smallest <= REQUIRED_MARGIN_MB)) {
    fail(`[margin] the nesting cap's cases need more than ${REQUIRED_MARGIN_MB} MB of worker stack (${SERVER_STACK_MB / REQUIRED_MARGIN_MB}x below the server's ${SERVER_STACK_MB} MB)`);
  }
  console.log(failures ? `${failures} lang case(s) failed` : 'ALL SAME: lang.analyze');
  process.exit(failures ? 1 : 0);
}
