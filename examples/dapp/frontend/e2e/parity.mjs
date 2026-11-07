// Runs the vectors WasmParityTest wrote (the JVM's results) through the contracts' Wasm image and compares the
// bytes. Usage: node --experimental-wasm-exnref e2e/parity.mjs <wasm dir> <vectors.json>

import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

const [dir, file] = process.argv.slice(2);
const ready = new Promise((r) => { globalThis.onJoustWasm = r; });
createRequire(import.meta.url)(dir + '/joust.js');
const wasm = await ready;
const vectors = JSON.parse(readFileSync(file, 'utf8'));
let same = 0;
const started = Date.now();
for (const v of vectors) {
  const got = wasm.step(v.contract, v.method, v.target, v.shared, v.args, v.ctx);
  if (got === v.expected) same++;
  else console.log(`DIFF ${v.contract}.${v.method}\n  jvm  ${v.expected.slice(0, 200)}\n  wasm ${got.slice(0, 200)}`);
}
console.log(`PARITY ${same}/${vectors.length} in ${Date.now() - started} ms`);
process.exit(same === vectors.length ? 0 : 1);
