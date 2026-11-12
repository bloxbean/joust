// joust build: contracts/**/*.java and *.jsc -> build/joust/ (ADR-0015 D9)
//   contracts.json        what the runtime needs: [{ description, script, transitions, manifest }]
//   <Contract>.json       the description (CIP-57 + x-joust v2)
//   <Contract>.manifest.json  the transition programs' manifest (ADR-0020 D8), checked by the engine before it runs them
//   <Contract>.plutus     the compiled validator (CBOR hex)
//   <Contract>.js/.d.ts   the typed client (joust-gen), importing @julclang/state-client
//   validators.json       the plain JuLC validators (governors, ...): [{ name, purpose, script, params }]
//   <Validator>.plutus    each one's compiled script (CBOR hex; parameters not applied)
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
    const manifest = c.manifestJson ? JSON.parse(c.manifestJson) : null;
    if (manifest) writeFileSync(join(dir, c.name + '.manifest.json'), c.manifestJson);
    bundle.push({ description: c.descriptionJson, script: c.scriptCborHex, transitions: c.transitions, manifest, built: { joust: VERSION } });
    log(`${c.packageName}.${c.name}: ${c.scriptSizeBytes} bytes, hash ${c.scriptHash || '(parameterised)'}, `
      + `${Object.keys(c.transitions).length} transitions`);
  }
  writeFileSync(join(dir, 'contracts.json'), JSON.stringify(bundle) + '\n');
  // Plain JuLC validators (a governor for @AuthorizedBy, ADR-0009 D3): their scripts, parameters applied at deployment.
  const validators = (r.validators ?? []).map((v) => ({ name: v.name, package: v.packageName, purpose: v.purpose,
    script: v.scriptCborHex, scriptHash: v.scriptHash, params: v.params, built: { joust: VERSION } }));
  for (const v of r.validators ?? []) {
    writeFileSync(join(dir, v.name + '.plutus'), v.scriptCborHex + '\n');
    log(`${v.packageName}.${v.name}: ${v.purpose} validator, ${v.scriptSizeBytes} bytes`
      + (v.params.length ? `, params ${v.params.map((p) => p.name + ': ' + p.type).join(', ')}` : `, hash ${v.scriptHash}`));
  }
  if (validators.length) writeFileSync(join(dir, 'validators.json'), JSON.stringify(validators) + '\n');
  writeFileSync(join(dir, 'joust.lock.json'), JSON.stringify({
    joust: VERSION, descriptionVersion: 2,
    contracts: Object.fromEntries(r.contracts.map((c) => [`${c.packageName}.${c.name}`, { scriptHash: c.scriptHash }])),
    ...(validators.length ? { validators: Object.fromEntries(validators.map((v) => [`${v.package}.${v.name}`, { purpose: v.purpose, scriptHash: v.scriptHash }])) } : {}),
  }, null, 2) + '\n');
  log(`built ${r.contracts.length} contract(s) into ${relative(root, dir)} in ${Date.now() - t} ms`);
  return { ok: true, contracts: r.contracts, validators, diagnostics: r.diagnostics };
}
