// ADR-0018 J18 (r12; review S2 on #66, r1 F2, S4 on #71) on the Web Image. Every case runs on a COLD engine, as
// `joust build` meets it: each in a fresh Node process. Each builds, or is refused with the nesting cap's code at a
// .jsc position, with the JVM's outcome (JscV2DepthBuildTest):
//   main:   on Node's main thread, default stack (about 1 MB, the smallest host): the front end's cap holds there;
//   worker: on a worker with `joust build`'s stack (js/joust/src/toolchain.js ENGINE_STACK_MB, 8 MB), where a cold
//           engine also compiles contracts past the front end as the JVM does (JuLC's passes recurse on the term).
// Usage: node depth-in-node.mjs <engine dir>   (runs every case, each in a child process)

import { resolve } from 'node:path';
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';
import { fileURLToPath } from 'node:url';
import { enableExnref } from '../../../../js/joust-runtime/src/exnref.js';
// Wasm exception references: a flag on Node 22, on by default on newer Node (which only warns about the flag).
enableExnref();

const ENGINE_STACK_MB = 8;
const [engineDir, caseIndex, where] = isMainThread ? process.argv.slice(2) : [workerData.engineDir, workerData.caseIndex, 'worker'];

const contract = (m) => `jsc 2\npackage t\ncontract D {\n  state { n: Int }\n  init() { return { n: 0 } }\n${m}\n}\n`;
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
const sum = (n) => {
  let s = 'x';
  for (let k = 1; k < n; k++) s += `${k % 3 === 0 ? ' - ' : ' + '}${k}`;
  return action(s);
};
const elseIfs = (n) => {
  let s = '    if x == 0 { require(x >= 0, "a0") }';
  for (let k = 1; k < n; k++) s += ` else if x == ${k} { require(x != ${k}, "a${k}") }`;
  s += ` else { require(x > ${n}, "z") }`;
  return contract(`  action a(x: Int) {\n${s}\n    return { ...state, n: x }\n  }`);
};
const elseIfValues = (n) => {
  let s = 'if x == 0 { 100 }';
  for (let k = 1; k < n; k++) s += ` else if x == ${k} { ${100 + k} }`;
  return action(`${s} else { 7 }`);
};
const types = (n) => {
  let t = '';
  for (let k = 0; k < n; k++) t += `type T${k} = { f: ${k === n - 1 ? 'Int' : 'T' + (k + 1)} }\n`;
  return `jsc 2\npackage t\n${t}contract D {\n  state { n: Int }\n  init() { return { n: 0 } }\n}\n`;
};

const wrapped = (named, wrappers) => {
  let t = '';
  for (let k = 0; k < named; k++) t += `type T${k} = { f: ${rep('List<', wrappers)}${k === named - 1 ? 'Key' : 'T' + (k + 1)}${rep('>', wrappers)} }\n`;
  return `jsc 2\npackage t\n${t}contract D {\n  state { n: Int }\n  init() { return { n: 0 } }\n  action a(v: T0) { require(v == v, "same")\n return state }\n}\n`;
};
const recursive = (n) => {
  let s = 'if x == 0 { 0 }';
  for (let k = 1; k < n; k++) s += ` else if x == ${k} { ${k} }`;
  return contract(`  view v(x: Int): Int = f(x)\n  fn f(x: Int): Int = ${s} else { f(x - 1) }`);
};
const chain = (n, name) => {
  let s = '    if x == 0 { require(x >= 0, "a0") }';
  for (let k = 1; k < n; k++) s += ` else if x == ${k} { require(x != ${k}, "a${k}") }`;
  return `  action ${name}(x: Int) {\n${s} else { require(x > ${n}, "z") }\n    return { ...state, n: x }\n  }`;
};
const manyChains = (chains, n) => contract(Array.from({ length: chains }, (_, i) => chain(n, 'a' + i)).join('\n'));

const cases = [
  ['parentheses 56', action(rep('(', 56) + 'x' + rep(')', 56)), 'ok'],
  ['parentheses 1000', action(rep('(', 1000) + 'x' + rep(')', 1000)), 'JSC0149'],
  ['blocks 25', blocks(25), 'ok'],
  ['blocks 200', blocks(200), 'JSC0149'],
  ['lambdas 12', lambdas(12), 'ok'],
  ['lambdas 200', lambdas(200), 'JSC0149'],
  ['match 12', matches(12), 'ok'],
  ['match 200', matches(200), 'JSC0149'],
  ['value ifs 20', action(rep('if x > 0 { ', 20) + 'x' + rep(' } else { 0 }', 20)), 'ok'],
  ['value ifs 200', action(rep('if x > 0 { ', 200) + 'x' + rep(' } else { 0 }', 200)), 'JSC0149'],
  ['sum of 5000', sum(5000), 'ok'],
  ['&& of 3000', contract('  view v(x: Int): Bool = x > 0' + rep(' && x > 1', 2999)), 'ok'],
  ['else-if statements 200', elseIfs(200), 'ok'],
  ['types 4 x 60 wrappers', wrapped(4, 60), 'JSC0340'],
  ['recursive chain of 9', recursive(9), 'ok'],
  // Past the front end: JuLC's passes recurse on the compiled term; a cold engine on 1 MB stopped at about 250 branches.
  ['else-if statements 1000', elseIfs(1000), 'ok', 'worker'],
  ['else-if statements 4 x 100', manyChains(4, 100), 'ok', 'worker'],
  ['else-if values 300', elseIfValues(300), 'ok', 'worker'],
  ['else-if values 1000', elseIfValues(1000), 'ok', 'worker'],
  ['/ chain 200', action('x' + rep(' / 2', 200)), 'JSC0150'],
  ['types 100', types(100), 'JSC0340'],
];


function run(i) {
  const engine = (() => {
    const ready = new Promise((r) => { globalThis.onJulcReady = r; });
    createRequire(import.meta.url)(resolve(engineDir, 'joust-toolchain.js'));
    return ready;
  })();
  return engine.then((e) => {
    const [, src, want] = cases[i];
    let got;
    try {
      const r = e.joust.build({ files: { 't/D.jsc': src }, options: {} });
      const error = r.diagnostics.find((d) => d.severity === 'error' || d.severity === 'ERROR');
      got = r.ok ? 'ok' : `${error?.code ?? 'no code'}${error && error.file === 't/D.jsc' && Number(error.line) > 0 ? '' : ' (not located)'}`;
      if (!r.ok && want === 'ok') got += ` ${String(error?.message).slice(0, 200)}`;
    } catch (x) {
      got = `threw ${x}`;
    }
    return got;
  });
}

if (!isMainThread) {
  parentPort.postMessage(await run(Number(caseIndex)));
} else if (caseIndex !== undefined) {
  // One case, cold, on this process's main thread or on a worker with joust build's stack.
  const got = where === 'worker'
    ? await new Promise((ok, fail) => {
      const w = new Worker(fileURLToPath(import.meta.url), { workerData: { engineDir, caseIndex }, resourceLimits: { stackSizeMb: ENGINE_STACK_MB } });
      w.on('message', ok);
      w.on('error', (e) => ok(`threw ${e}`));
    })
    : await run(Number(caseIndex));
  console.log(got);
  process.exit(0);
} else {
  let failures = 0;
  for (let i = 0; i < cases.length; i++) {
    const [name, , want, only] = cases[i];
    for (const host of only ? [only] : ['main', 'worker']) {
      const t = Date.now();
      const got = execFileSync(process.execPath, [fileURLToPath(import.meta.url), engineDir, String(i), host],
        { encoding: 'utf8', maxBuffer: 1 << 24 }).trim().split('\n').pop();
      const pass = got === want;
      if (!pass) failures++;
      console.log(`${pass ? 'same' : 'FAIL'} [depth wasm, cold, ${host}] ${name.padEnd(28)} ${got} in ${Date.now() - t} ms`);
    }
  }
  console.log(failures ? `${failures} depth case(s) differ` : `ALL SAME: ${cases.length} depth cases, each on a cold engine`);
  process.exit(failures ? 1 : 0);
}
