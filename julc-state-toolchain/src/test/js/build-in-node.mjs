// ADR-0015 S1: the toolchain engine compiles contracts in Node (no JDK), with the same outputs as Gradle (I1).
// Usage: node --experimental-wasm-exnref build-in-node.mjs <engine dir> <examples dir> <example>...

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { createRequire } from 'node:module';

const [engineDir, examples, ...modules] = process.argv.slice(2);
const started = Date.now();
const ready = new Promise((r) => { globalThis.onJulcReady = r; });
createRequire(import.meta.url)(resolve(engineDir, 'joust-toolchain.js'));
const engine = await ready;
console.log(`engine ready in ${Date.now() - started} ms (${engine.version ?? ''})`);

const walk = (dir) => readdirSync(dir).flatMap((n) => {
  const p = join(dir, n);
  return statSync(p).isDirectory() ? walk(p) : [p];
});

let failures = 0;
for (const m of modules) {
  const files = {};
  for (const dir of ['src/main/java', 'src/main/jsc']) {
    const root = join(examples, m, dir);
    if (!existsSync(root)) continue;
    for (const p of walk(root)) {
      const text = readFileSync(p, 'utf8');
      if (p.endsWith('.jsc') || (p.endsWith('.java') && (text.includes('@StateContract') || text.includes('@OnchainLibrary')))) {
        files[relative(root, p)] = text;
      }
    }
  }
  const t = Date.now();
  const r = engine.joust.build({ files, options: {} });
  // Review r3 F13: a built engine reports the versions it was built with, never 'dev'.
  if (!r.joust || r.joust === 'dev' || !r.julc || r.julc === 'dev') {
    console.log(`DIFF engine versions: joust ${r.joust}, julc ${r.julc}`);
    failures++;
  }
  const ms = Date.now() - t;
  if (!r.ok) {
    failures++;
    console.log(`FAIL ${m}: ${JSON.stringify(r.diagnostics, (k, v) => (typeof v === "bigint" ? Number(v) : v)).slice(0, 1500)}`);
    continue;
  }
  // ADR-0018 D12: the engine returns the Java each .jsc file lowered to (Gradle's, byte for byte) and its ABI manifest
  // (the committed one).
  for (const g of r.generated ?? []) {
    const gradle = join(examples, m, 'build/generated/sources/jsc/java/main', g.javaFile);
    const javaSame = existsSync(gradle) && readFileSync(gradle, 'utf8') === g.java;
    const committed = join(examples, m, 'src/main/jsc', g.jscFile.replace(/[^/]+\.jsc$/, ''), g.javaFile.split('/').pop().replace(/\.java$/, '.abi.json'));
    const abiSame = !g.abi || (existsSync(committed) && readFileSync(committed, 'utf8') === g.abi);
    if (!javaSame || !abiSame) {
      failures++;
      console.log(`DIFF generated ${g.javaFile}: java:${javaSame} abi:${abiSame}`);
    } else if (g.abi) {
      console.log(`same generated ${g.javaFile} (source map, ABI manifest)`);
    }
  }
  for (const c of r.contracts) {
    const fq = `${c.packageName}.${c.name}`;
    const classes = join(examples, m, 'build/classes/java/main');
    const plutus = JSON.parse(readFileSync(join(classes, `META-INF/plutus/${c.validatorName}.plutus.json`), 'utf8'));
    const description = readFileSync(join(classes, `META-INF/joust/${fq}.json`), 'utf8');
    const source = readFileSync(join(examples, m, 'build/generated/sources/annotationProcessor/java/main', c.packageName.replaceAll('.', '/'), `${c.validatorName}.java`), 'utf8');
    const same = plutus.cborHex === c.scriptCborHex && description === c.descriptionJson && source === c.validatorSource;
    if (!same) failures++;
    console.log(`${same ? 'same' : 'DIFF'} ${fq} ${c.scriptSizeBytes} bytes ${c.scriptHash} (${ms} ms)`
      + (same ? '' : ` script:${plutus.cborHex === c.scriptCborHex} description:${description === c.descriptionJson} source:${source === c.validatorSource}`));
  }
}
console.log(failures ? `FAILURES ${failures}` : 'ALL SAME');
process.exit(failures ? 1 : 0);
