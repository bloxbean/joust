// joust build: contracts/**/*.java and *.jsc -> build/joust/ (ADR-0015 D9)
//   contracts.json        what the runtime needs: [{ description, script, transitions }]
//   <Contract>.json       the description (CIP-57 + x-joust v2)
//   <Contract>.plutus     the compiled validator (CBOR hex)
//   <Contract>.js/.d.ts   the typed client (joust-gen), importing @julclang/state-client
//   validators.json       the plain JuLC validators (governors, ...): [{ name, purpose, script, params }]
//   <Validator>.plutus    each one's compiled script (CBOR hex; parameters not applied)
//   joust.lock.json       the versions that built it (I7)
//   generated/            the Java each .jsc file lowered to, with its source map (ADR-0018 D12)
// Beside each .jsc 2 file, <Contract>.abi.json: its ABI manifest, which the project commits; a build whose manifest
// differs, or is missing, fails unless accepted (--accept-abi: src/abi.js), so an encoding change cannot strand
// deployed instances.

import { readFileSync, writeFileSync, mkdirSync, readdirSync, rmSync, statSync, existsSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { generate } from '@julclang/state-client/codegen';
import { loadToolchain } from './toolchain.js';
import { abiGate } from './abi.js';

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
export async function build({ root = process.cwd(), src = 'contracts', out = 'build/joust', options = {}, log = console.log, keep,
  acceptAbi = false } = {}) {
  const files = sources(resolve(root, src), keep);
  if (!Object.keys(files).length) throw new Error(`no .java or .jsc contracts under ${resolve(root, src)}`);
  const t = Date.now();
  const engine = await loadToolchain();
  const r = engine.joust.build({ files, options });
  for (const d of r.diagnostics) {
    const where = d.file ? `${d.file}${d.line > 0 ? ':' + d.line : ''}${d.line > 0 && d.col > 0 ? ':' + d.col : ''}: ` : '';
    // A .jsc 2 diagnostic has a stable code (ADR-0018 D12): error[JSC0502].
    (d.severity === 'error' ? console.error : log)(`${d.severity}${d.code ? '[' + d.code + ']' : ''}: ${where}${d.message}`);
  }
  const dir = resolve(root, out);
  // ADR-0018 D12: the ABI gate first. Accepting is for a build that succeeded only.
  const refused = new Set();
  const abiErrors = abiGate({ generated: r.generated ?? [], srcDir: src, root, acceptAbi: r.ok && acceptAbi, log, refused });
  for (const e of abiErrors) console.error(e);
  // Then the generated Java and its source map, on disk as on the Gradle path (also when the build failed), from a
  // clean directory. Java whose manifest was refused is not written, so nothing can compile it (security S9).
  rmSync(join(dir, 'generated'), { recursive: true, force: true });
  for (const g of r.generated ?? []) {
    if (refused.has(g.javaFile)) continue;
    const target = join(dir, 'generated', g.javaFile);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, g.java);
    if (g.sourceMap) writeFileSync(target.replace(/\.java$/, '.jscmap.json'), g.sourceMap);
  }
  if (!r.ok) return { ok: false, contracts: [], diagnostics: r.diagnostics, ...(abiErrors.length ? { abiErrors } : {}) };
  if (abiErrors.length) return { ok: false, contracts: [], diagnostics: r.diagnostics, abiErrors };
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
