// ADR-0018 J18 (r12, review S2 on #66) on the Web Image: each case builds, or is refused with the nesting cap's code at
// a .jsc position, on a Node with its default stack (about 1 MB: the worker stack `joust build` and editors run on).
// Before the cap, joust.build failed with an opaque TypeError at 250 nested parentheses. The cases and outcomes equal
// JscV2DepthBuildTest's on the JVM.
// Usage: node --experimental-wasm-exnref depth-in-node.mjs <engine dir>

import { resolve } from 'node:path';
import { createRequire } from 'node:module';

const [engineDir] = process.argv.slice(2);
const ready = new Promise((r) => { globalThis.onJulcReady = r; });
createRequire(import.meta.url)(resolve(engineDir, 'joust-toolchain.js'));
const engine = await ready;

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
  // JuLC's own passes (past the front end) recurse on the compiled term: on a 1 MB stack the Web Image compiles about
  // 250 statement branches in one contract (the JVM, 1,000 and more). The front end has no limit of its own here.
  ['else-if statements 200', elseIfs(200), 'ok'],
  ['else-if values 1000', elseIfValues(1000), 'ok'],
  ['/ chain 200', action('x' + rep(' / 2', 200)), 'JSC0150'],
  ['types 100', types(100), 'JSC0340'],
];

let failures = 0;
for (const [name, src, want] of cases) {
  const t = Date.now();
  let got;
  try {
    const r = engine.joust.build({ files: { 't/D.jsc': src }, options: {} });
    const error = r.diagnostics.find((d) => d.severity === 'error' || d.severity === 'ERROR');
    got = r.ok ? 'ok' : `${error?.code ?? 'no code'}${error && error.file === 't/D.jsc' && Number(error.line) > 0 ? '' : ' (not located)'}`;
    if (!r.ok && want === 'ok') got += ` ${String(error?.message).slice(0, 200)}`;
  } catch (e) {
    got = `threw ${e}`;
  }
  const pass = got === want;
  if (!pass) failures++;
  console.log(`${pass ? 'same' : 'FAIL'} [depth wasm] ${name.padEnd(24)} ${got} in ${Date.now() - t} ms`);
}
console.log(failures ? `${failures} depth case(s) differ` : `ALL SAME: ${cases.length} depth cases`);
process.exit(failures ? 1 : 0);
