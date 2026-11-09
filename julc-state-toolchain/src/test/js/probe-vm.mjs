// Probe: compile Counter in the engine, then evaluate its increment transition program with the engine's VM.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';

const [engineDir, counterJava] = process.argv.slice(2);
const ready = new Promise((r) => { globalThis.onJulcReady = r; });
createRequire(import.meta.url)(resolve(engineDir, 'joust-toolchain.js'));
const engine = await ready;
const r = engine.joust.build({ files: { 'Counter.java': readFileSync(counterJava, 'utf8') }, options: {} });
const c = r.contracts[0];
console.log('transitions:', Object.keys(c.transitions));
// target = State(5, owner) = Constr 0 [5, bytes28]; args = Constr 0 [3]; shared and ctx unused = Constr 0 []
const target = 'd8799f05581c' + '07'.repeat(28) + 'ff';
const args = 'd8799f03ff';
const unit = 'd87980';
const out = engine.vm.evaluate({
  script: { script: c.transitions.increment },
  args: [target, unit, args, unit].map((v) => ({ format: 'cbor', value: v })),
  target: { language: 'PlutusV3', protocol: 11 },
});
console.log(JSON.stringify(out, (k, v) => (typeof v === 'bigint' ? v.toString() : v)).slice(0, 1200));
