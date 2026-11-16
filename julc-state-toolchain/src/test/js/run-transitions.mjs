// ADR-0015 S2 in Wasm: the engine's VM gives the JVM's answer for every transition vector (build/s2-vectors.json).
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';
import { enableExnref } from '../../../../js/joust-runtime/src/exnref.js';
// Wasm exception references: a flag on Node 22, on by default on newer Node (which only warns about the flag).
enableExnref();

const [engineDir, vectorsFile] = process.argv.slice(2);
const ready = new Promise((r) => { globalThis.onJulcReady = r; });
createRequire(import.meta.url)(resolve(engineDir, 'joust-toolchain.js'));
const engine = await ready;
const vectors = JSON.parse(readFileSync(vectorsFile, 'utf8'));
let same = 0;
const t = Date.now();
for (const v of vectors) {
  const r = engine.joust.run({ program: v.program, args: v.args });
  const got = r.ok ? 'ok:' + r.result : 'refused';
  if (got === v.expected) same++;
  else console.log(`DIFF expected ${v.expected.slice(0, 80)} got ${got.slice(0, 80)} ${r.error ?? ''}`);
}
console.log(`S2 in Wasm: ${same}/${vectors.length} identical to the JVM in ${Date.now() - t} ms`);
process.exit(same === vectors.length ? 0 : 1);
