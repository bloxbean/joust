// ADR-0015 I3 in Wasm: each prepare the dApp's DevKit run recorded (Counter, Bank), prepared again in the runtime
// engine from its call request and snapshot, over the contracts as the toolchain builds them (descriptions, scripts,
// transition programs), gives the recorded unsigned transaction byte for byte (build/prepare-vectors.json, written by
// DescribedReplayTest).
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
const { contracts, clock, vectors } = JSON.parse(readFileSync(vectorsFile, 'utf8'));
const request = (call, snapshot) => ({
  call, snapshot, contracts,
  zeroSlot: BigInt(clock.zeroSlot), zeroTimeMs: BigInt(clock.startTimeMs), slotLengthMs: BigInt(clock.slotLengthMs),
  ttlMs: 20000n, networkId: 0, mapNodes: {},
});
let same = 0;
const times = [];
for (const v of vectors) {
  const t = performance.now();
  const r = engine.joust.prepare(request(v.call, v.snapshot));
  times.push(performance.now() - t);
  if (r.ok && r.cbor === v.expected) same++;
  else console.log(`DIFF ${v.name}: ${r.ok ? 'different bytes' : r.needKind ? `needs ${r.needKind} ${r.needKey}` : r.error}`);
}
// D5: a read outside the snapshot comes back as a need; the contract's Require as a refusal with its message.
const last = vectors.find((v) => JSON.parse(v.call).method === 'increment');
const need = engine.joust.prepare(request(last.call, '{}'));
const needOk = !need.ok && !!need.needKind;
const zero = JSON.parse(last.call);
zero.args.by = '0';
const refusal = engine.joust.prepare(request(JSON.stringify(zero), last.snapshot));
const refusalOk = !refusal.ok && refusal.error === 'increment must be positive';
console.log(`  empty snapshot: ${needOk ? `needs ${need.needKind}` : 'NOT a need: ' + JSON.stringify(need)}`);
console.log(`  increment by 0: ${refusalOk ? `refused: ${refusal.error}` : 'NOT refused as expected: ' + JSON.stringify(refusal)}`);
const size = statSync(wasm).size;
const gz = gzipSync(readFileSync(wasm)).length;
const mb = (n) => (n / 1048576).toFixed(2) + ' MiB';
const sorted = [...times].sort((a, b) => a - b);
console.log(`I3 in Wasm: ${same}/${vectors.length} recorded prepares byte-identical (Counter, Bank; no contract classes in the engine)`);
console.log(`  image ${mb(size)} (.wasm), ${mb(gz)} gzip; cold start ${cold.toFixed(0)} ms`);
console.log(`  prepare: first ${times[0].toFixed(0)} ms, median ${sorted[sorted.length >> 1].toFixed(0)} ms, max ${sorted[sorted.length - 1].toFixed(0)} ms`);
process.exit(same === vectors.length && needOk && refusalOk ? 0 : 1);
