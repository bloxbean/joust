// ADR-0015 S2b in Wasm: MerkleMap read discovery through the engine's VM (build/s2b-vectors.json, from
// ReadDiscoveryTest). Round 1 reports the JVM's first reads in joust:read traces; the final round (every read proven)
// gives the JVM's result and reads nothing new.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve, join, relative } from 'node:path';
import { createRequire } from 'node:module';
import { enableExnref } from '../../../../js/joust-runtime/src/exnref.js';
// Wasm exception references: a flag on Node 22, on by default on newer Node (which only warns about the flag).
enableExnref();

const [engineDir, vectorsFile, tokenSources] = process.argv.slice(2);
const ready = new Promise((r) => { globalThis.onJulcReady = r; });
createRequire(import.meta.url)(resolve(engineDir, 'joust-toolchain.js'));
const engine = await ready;
const vectors = JSON.parse(readFileSync(vectorsFile, 'utf8'));
const reads = (traces) => (traces ?? []).filter((t) => t.startsWith('joust:read ')).map((t) => t.split(' ')[2] ?? '');
let same = 0;
const t = Date.now();
for (const v of vectors) {
  const first = engine.joust.run({ program: v.program, args: v.round1Args });
  const last = engine.joust.run({ program: v.program, args: v.args });
  const got = last.ok ? 'ok:' + last.result : 'refused';
  const r1 = JSON.stringify(reads(first.traces));
  if (got === v.expected && r1 === JSON.stringify(v.round1Reads) && reads(last.traces).length === 0) same++;
  else console.log(`DIFF ${v.name}: result ${got === v.expected}, round 1 reads ${r1} vs ${JSON.stringify(v.round1Reads)}, final reads ${reads(last.traces)}`);
}
// The engine compiles the same transition programs (with the discovery variant) as the JVM toolchain.
let built = true;
if (tokenSources) {
  const files = {};
  const walk = (d) => readdirSync(d).forEach((n) => {
    const p = join(d, n);
    if (statSync(p).isDirectory()) walk(p);
    else if (p.endsWith('.java')) { const text = readFileSync(p, 'utf8'); if (text.includes('@StateContract')) files[relative(tokenSources, p)] = text; }
  });
  walk(tokenSources);
  const b = engine.joust.build({ files, options: {} });
  const programs = b.contracts?.[0]?.transitions ?? {};
  const want = Object.fromEntries(vectors.map((v) => [v.name.split(' ')[0], v.program]));
  built = Object.entries(want).every(([m, p]) => programs[m] === p);
  console.log(`  transitions compiled in Wasm ${built ? 'identical to' : 'DIFFERENT from'} the JVM toolchain's: ${Object.keys(want).join(', ')}`);
}
console.log(`S2b in Wasm: ${same}/${vectors.length} token calls as on the JVM (round 1 reads, final result) in ${Date.now() - t} ms`);
process.exit(same === vectors.length && built ? 0 : 1);
