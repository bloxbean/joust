// ADR-0015 S0 in Wasm: each recorded Counter call, prepared in the runtime engine from its snapshot, gives the
// recorded unsigned transaction byte for byte (build/s0-vectors.json, written by CounterPrepareTest).
import { readFileSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';
import { gzipSync } from 'node:zlib';

const [engineDir, vectorsFile] = process.argv.slice(2);
const wasm = resolve(engineDir, 'joust-runtime.js.wasm');
const t0 = performance.now();
const ready = new Promise((r) => { globalThis.onJulcReady = r; });
createRequire(import.meta.url)(resolve(engineDir, 'joust-runtime.js'));
const engine = await ready;
const cold = performance.now() - t0;
const vectors = JSON.parse(readFileSync(vectorsFile, 'utf8'));
let same = 0;
const times = [];
for (const v of vectors) {
  const t = performance.now();
  const q = v.request;
  const r = engine.joust.prepareS0({ ...q, zeroSlot: BigInt(q.zeroSlot), zeroTimeMs: BigInt(q.zeroTimeMs),
    slotLengthMs: BigInt(q.slotLengthMs), ttlMs: BigInt(q.ttlMs) });
  times.push(performance.now() - t);
  if (r.ok && r.cbor === v.expected) same++;
  else console.log(`DIFF ${v.name}: ${r.ok ? 'different bytes' : r.needKind ? `needs ${r.needKind} ${r.needKey}` : r.error}`);
}
// D5: a read outside the snapshot comes back as a need, not an error.
const q0 = vectors[vectors.length - 1].request;
const need = engine.joust.prepareS0({ ...q0, snapshot: '{}', zeroSlot: BigInt(q0.zeroSlot), zeroTimeMs: BigInt(q0.zeroTimeMs),
  slotLengthMs: BigInt(q0.slotLengthMs), ttlMs: BigInt(q0.ttlMs) });
const needOk = !need.ok && !!need.needKind;
console.log(`  empty snapshot: ${needOk ? `needs ${need.needKind} ${need.needKey}` : 'NOT a need: ' + JSON.stringify(need)}`);
const size = statSync(wasm).size;
const gz = gzipSync(readFileSync(wasm)).length;
const mb = (n) => (n / 1048576).toFixed(2) + ' MiB';
const sorted = [...times].sort((a, b) => a - b);
console.log(`S0 in Wasm: ${same}/${vectors.length} byte-identical to the DevKit's transactions`);
console.log(`  image ${mb(size)} (.wasm), ${mb(gz)} gzip; cold start ${cold.toFixed(0)} ms`);
console.log(`  prepare: first ${times[0].toFixed(0)} ms, median ${sorted[sorted.length >> 1].toFixed(0)} ms, max ${sorted[sorted.length - 1].toFixed(0)} ms`);
process.exit(same === vectors.length && needOk ? 0 : 1);
