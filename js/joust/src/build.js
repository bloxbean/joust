// joust build: contracts/**/*.java and *.jsc -> build/joust/ (ADR-0015 D9)
//   contracts.json        what the runtime needs: [{ description, script, transitions }]
//   <Contract>.json       the description (CIP-57 + x-joust v2)
//   <Contract>.plutus     the compiled validator (CBOR hex)
//   <Contract>.js/.d.ts   the typed client (joust-gen), importing @julclang/state-client
//   joust.lock.json       the versions that built it (I7)

import { readFileSync, writeFileSync, mkdirSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { generate } from '@julclang/state-client/codegen';
import { loadToolchain } from './toolchain.js';

export const VERSION = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version;

function sources(dir, keep = () => true) {
  const out = {};
  const walk = (d) => {
    for (const n of readdirSync(d)) {
      const p = join(d, n);
      if (statSync(p).isDirectory()) walk(p);
      else if (p.endsWith('.java') || p.endsWith('.jsc')) {
        const text = readFileSync(p, 'utf8');
        if (keep(relative(dir, p), text)) out[relative(dir, p)] = text;
      }
    }
  };
  if (existsSync(dir)) walk(dir);
  return out;
}

/** Builds {@code root}'s contracts; returns { ok, contracts, diagnostics }. */
export async function build({ root = process.cwd(), src = 'contracts', out = 'build/joust', options = {}, log = console.log, keep } = {}) {
  const files = sources(resolve(root, src), keep);
  if (!Object.keys(files).length) throw new Error(`no .java or .jsc contracts under ${resolve(root, src)}`);
  const t = Date.now();
  const engine = await loadToolchain();
  const r = engine.joust.build({ files, options });
  for (const d of r.diagnostics) {
    const where = d.file ? `${d.file}${d.line > 0 ? ':' + d.line : ''}: ` : '';
    (d.severity === 'error' ? console.error : log)(`${d.severity}: ${where}${d.message}`);
  }
  if (!r.ok) return { ok: false, contracts: [], diagnostics: r.diagnostics };
  const dir = resolve(root, out);
  mkdirSync(dir, { recursive: true });
  const bundle = [];
  for (const c of r.contracts) {
    const description = JSON.parse(c.descriptionJson);
    writeFileSync(join(dir, c.name + '.json'), JSON.stringify(description, null, 2) + '\n');
    writeFileSync(join(dir, c.name + '.plutus'), c.scriptCborHex + '\n');
    const { js, dts } = generate(description);
    writeFileSync(join(dir, c.name + '.js'), js);
    writeFileSync(join(dir, c.name + '.d.ts'), dts);
    bundle.push({ description: c.descriptionJson, script: c.scriptCborHex, transitions: c.transitions, built: { joust: VERSION } });
    log(`${c.packageName}.${c.name}: ${c.scriptSizeBytes} bytes, hash ${c.scriptHash || '(parameterised)'}, `
      + `${Object.keys(c.transitions).length} transitions`);
  }
  writeFileSync(join(dir, 'contracts.json'), JSON.stringify(bundle) + '\n');
  writeFileSync(join(dir, 'joust.lock.json'), JSON.stringify({
    joust: VERSION, descriptionVersion: 2,
    contracts: Object.fromEntries(r.contracts.map((c) => [`${c.packageName}.${c.name}`, { scriptHash: c.scriptHash }])),
  }, null, 2) + '\n');
  log(`built ${r.contracts.length} contract(s) into ${relative(root, dir)} in ${Date.now() - t} ms`);
  return { ok: true, contracts: r.contracts, diagnostics: r.diagnostics };
}
