// joust build: contracts/**/*.java and *.jsc -> build/joust/ (ADR-0015 D9)
//   contracts.json        what the runtime needs: [{ description, script, transitions }]
//   <Contract>.json       the description (CIP-57 + x-joust v2)
//   <Contract>.plutus     the compiled validator (CBOR hex)
//   <Contract>.js/.d.ts   the typed client (joust-gen), importing @julclang/state-client
//   validators.json       the plain JuLC validators (governors, ...): [{ name, purpose, script, params }]
//   <Validator>.plutus    each one's compiled script (CBOR hex; parameters not applied)
//   joust.lock.json       the versions that built it (I7)
//   generated/            the Java each .jsc file lowered to, with its source map (ADR-0018 D12) and its checked IR
//                         (<Contract>.ir.json, D15: what the JavaScript reference interpreter evaluates)
// Beside each .jsc 2 file, <Contract>.abi.json: its ABI manifest, which the project commits; a build whose manifest
// differs, or is missing, fails unless accepted (--accept-abi: src/abi.js), so an encoding change cannot strand
// deployed instances.

import { readFileSync, writeFileSync, mkdirSync, readdirSync, rmSync, statSync, existsSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { generate } from '@julclang/state-client/codegen';
import { buildInWorker, ENGINE_STACK_MB } from './toolchain.js';
import { abiGate } from './abi.js';
import { libraryPackages, lockGate } from './libraries.js';
import { deploymentsShape, scriptDigest } from './deploy.js';

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
  acceptAbi = false, acceptLock = false, traces = false } = {}) {
  const files = sources(resolve(root, src), keep);
  if (!Object.keys(files).length) throw new Error(`no .java or .jsc contracts under ${resolve(root, src)}`);
  // ADR-0018 D14 (M4c): the libraries the project's npm packages ship under joust-lib/ (.jsc, and Java on-chain sources).
  let packages;
  let javaLibraries;
  try {
    ({ packages, javaLibraries } = libraryPackages(root));
  } catch (e) {
    return { ok: false, contracts: [], diagnostics: [{ severity: 'error', message: e.message }] };
  }
  // ADR-0018 D12: joust build --traces keeps the contracts' traces in the scripts (Gradle: -Pjoust.traces).
  if (traces) options = { ...options, 'joust.traces': 'true' };
  const t = Date.now();
  let r;
  try {
    // On a worker with a set stack (ENGINE_STACK_MB), so a cold engine builds what the JVM builds (ADR-0018 J18, r12).
    r = await buildInWorker({ files, options, packages, javaLibraries });
  } catch (e) {
    // The engine's stack ran out past the front end (whose nesting cap keeps it within the stack, ADR-0018 J18): in
    // JuLC's passes, on a contract far larger than a script can be. Web Image surfaces that as a TypeError; the build
    // reports it and fails.
    r = { ok: false, contracts: [], diagnostics: [{ severity: 'error', source: 'toolchain', message: 'internal error: the'
      + ` toolchain engine failed (${e?.message ?? e}); a contract this large may exhaust the engine's stack`
      + ` (JOUST_ENGINE_STACK_MB=${ENGINE_STACK_MB}): build it on Gradle, or split it (please report it, with the file)` }],
      generated: [] };
  }
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
    // ADR-0018 D15 (M1b): the checked IR, which the JavaScript reference interpreter evaluates.
    if (g.ir) writeFileSync(target.replace(/\.java$/, '.ir.json'), g.ir);
  }
  if (!r.ok) return { ok: false, contracts: [], diagnostics: r.diagnostics, ...(abiErrors.length ? { abiErrors } : {}) };
  if (abiErrors.length) return { ok: false, contracts: [], diagnostics: r.diagnostics, abiErrors };
  // ADR-0018 J12: the build names every library it used with its hash; joust.lock pins those from outside the sources.
  const lockErrors = lockGate({ generated: r.generated ?? [], libraries: r.libraries ?? [], root, acceptLock, log });
  for (const e of lockErrors) console.error(e);
  if (lockErrors.length) return { ok: false, contracts: [], diagnostics: r.diagnostics, lockErrors };
  mkdirSync(dir, { recursive: true });
  const bundle = [];
  // ADR-0018 D12 (r12): each .jsc 2 contract's message map (the source map's `messages`): what every message its script
  // can trace means, at which File.jsc:line:col. The runtime names the position when it shows a refusal.
  const messagesOf = {};
  for (const g of r.generated ?? []) {
    if (!g.sourceMap) continue;
    const m = JSON.parse(g.sourceMap);
    if (m.messages) messagesOf[g.javaFile] = { source: g.jscFile, messages: m.messages };
  }
  for (const c of r.contracts) {
    const description = JSON.parse(c.descriptionJson);
    writeFileSync(join(dir, c.name + '.json'), JSON.stringify(description, null, 2) + '\n');
    writeFileSync(join(dir, c.name + '.plutus'), c.scriptCborHex + '\n');
    const { js, dts } = generate(description);
    writeFileSync(join(dir, c.name + '.js'), js);
    writeFileSync(join(dir, c.name + '.d.ts'), dts);
    const map = messagesOf[`${c.packageName.replaceAll('.', '/')}/${c.name}.java`];
    // The script it explains: a map read from an audit file names its script (review r1 S1).
    const messages = map ? { ...map, scriptHash: c.scriptHash } : undefined;
    // Review r3 F9: the compiler that built each script (this package's version, the engine's JOUST and JuLC versions),
    // carried into each deployment record.
    bundle.push({ description: c.descriptionJson, script: c.scriptCborHex, transitions: c.transitions,
      built: { joust: VERSION, engine: r.joust ?? null, julc: r.julc ?? null }, ...(messages ? { messages } : {}) });
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
    joust: VERSION, engine: { joust: r.joust ?? null, julc: r.julc ?? null }, descriptionVersion: 2,
    contracts: Object.fromEntries(r.contracts.map((c) => [`${c.packageName}.${c.name}`, { scriptHash: c.scriptHash }])),
    ...(validators.length ? { validators: Object.fromEntries(validators.map((v) => [`${v.package}.${v.name}`, { purpose: v.purpose, scriptHash: v.scriptHash }])) } : {}),
  }, null, 2) + '\n');
  for (const w of staleDeployments(dir, r.contracts)) log(w);
  log(`built ${r.contracts.length} contract(s) into ${relative(root, dir)} in ${Date.now() - t} ms`);
  return { ok: true, contracts: r.contracts, validators, diagnostics: r.diagnostics };
}

/**
 * ADR-0018 D16 (review r2 S1): recorded deployments (deployments.<network>.json) this build no longer produces, as
 * warnings: a contract built to another script (a migrated .jsc file, a changed contract; by the digest of its script
 * before parameters, so @Params contracts too, else by its policy), a contract no longer built (renamed or removed), and
 * a file that cannot be read. Instances deployed earlier stay on the recorded script.
 */
export function staleDeployments(dir, contracts) {
  const out = [];
  const built = new Map(contracts.map((c) => [`${c.packageName}.${c.name}`, c]));
  const files = existsSync(dir) ? readdirSync(dir).filter((f) => /^deployments\..+\.json$/.test(f)).sort() : [];
  const tail = 'instances deployed earlier stay on that script; to call them, keep the build that made it (joust migrate'
    + ' --in-place keeps the v1 artifacts in build/joust/v1/; joust deploy keeps superseded records) (ADR-0018 D16)';
  for (const f of files) {
    let recorded;
    try {
      recorded = JSON.parse(readFileSync(join(dir, f), 'utf8'));
      const problem = deploymentsShape(recorded);
      if (problem) throw new Error(problem);
    } catch (e) {
      out.push(`warning: ${f} cannot be read (${e.message}): its deployments are not checked against this build; joust deploy keeps it`
        + ' beside the new file');
      continue;
    }
    for (const [title, d] of Object.entries(recorded.contracts ?? {})) {
      const c = built.get(title);
      if (!c) {
        out.push(`warning: ${f} records ${title}, which this build does not produce (renamed or removed): ${tail}`);
        continue;
      }
      const digest = scriptDigest(c.scriptCborHex);
      if (d.scriptDigest) {
        if (d.scriptDigest !== digest) out.push(`warning: ${f} records ${title} with another script than this build's: ${tail}`);
      } else if (d.params !== undefined) {
        out.push(`warning: ${f} records ${title} with params but no script digest (deployed by an earlier JOUST): it cannot be`
          + ' checked against this build; deploy again to record one');
      } else if (d.policy && c.scriptHash) {
        if (d.policy !== c.scriptHash) out.push(`warning: ${f} records ${title} at script ${d.policy}, which this build does not produce (it builds ${c.scriptHash}): ${tail}`);
      } else {
        out.push(`warning: ${f} records ${title} with neither a script digest nor a policy: it cannot be checked against this build`);
      }
    }
  }
  return out;
}
