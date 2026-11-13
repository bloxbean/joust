// ADR-0018 D9, D14, J12 (M4c): the libraries of an npm project, and joust.lock.
//
// An npm library package ships its on-chain libraries under joust-lib/: .jsc libraries, imported by package specifier
// (import { fee } from "@acme/fees/fees.jsc" is node_modules/@acme/fees/joust-lib/fees.jsc), and Java (JuLC) on-chain
// sources, which java: imports name by class and the toolchain certifies before admitting. joust.lock, committed at the
// project's root, pins every library from outside the project's sources by the SHA-256 of its on-chain sources: a build
// whose library differs from its pin, or is not pinned, fails, unless it is accepted (joust build --accept-lock), which
// writes the lock to be reviewed and committed. Needs nothing but Node.

import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const walk = (dir, out = []) => {
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
};

/** The npm packages under {@code root}/node_modules (scoped ones included): [name, dir]. */
function packagesOf(root) {
  const nm = join(root, 'node_modules');
  if (!existsSync(nm)) return [];
  const out = [];
  for (const n of readdirSync(nm)) {
    if (n.startsWith('.')) continue;
    const p = join(nm, n);
    if (n.startsWith('@')) {
      if (!statSync(p).isDirectory()) continue;
      for (const m of readdirSync(p)) out.push([`${n}/${m}`, join(p, m)]);
    } else {
      out.push([n, p]);
    }
  }
  return out;
}

/**
 * Every library the project's npm packages ship under joust-lib/: { packages: { "<package>/<path>.jsc": text },
 * javaLibraries: { "<package>/<path>.java": text } }.
 */
export function libraryPackages(root) {
  const packages = {};
  const javaLibraries = {};
  for (const [name, dir] of packagesOf(root)) {
    const lib = join(dir, 'joust-lib');
    if (!existsSync(lib) || !statSync(lib).isDirectory()) continue;
    for (const f of walk(lib)) {
      const rel = relative(lib, f).split(sep).join('/');
      if (f.endsWith('.jsc')) packages[`${name}/${rel}`] = readFileSync(f, 'utf8');
      else if (f.endsWith('.java')) javaLibraries[`${name}/${rel}`] = readFileSync(f, 'utf8');
    }
  }
  return { packages, javaLibraries };
}

/** The libraries a build used ({ kind, name, sha256, external }), by name, from the engine's generated files. */
export function librariesUsed(generated) {
  const out = new Map();
  for (const g of generated) for (const l of g.libraries ?? []) out.set(l.name, l);
  return [...out.values()].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
}

/**
 * J12: reports every library the build used, and checks (or, accepting, writes) {@code root}/joust.lock for those from
 * outside the project's sources. Returns the errors, as lines.
 */
export function lockGate({ generated, root, acceptLock = false, log = console.log }) {
  const used = librariesUsed(generated);
  for (const l of used) {
    log(`library ${l.name.replace(/^pkg:/, '')} sha256 ${l.sha256} (${l.external ? (l.kind === 'java' ? 'certified; pinned' : 'pinned')
      : "the project's"})`);
  }
  const now = Object.fromEntries(used.filter((l) => l.external).map((l) => [l.name.replace(/^pkg:/, ''), l.sha256]));
  const file = join(root, 'joust.lock');
  const exists = existsSync(file);
  let pinned = {};
  if (exists) {
    try {
      const j = JSON.parse(readFileSync(file, 'utf8'));
      if (j.lock !== 1 || typeof j.libraries !== 'object') throw new Error('not a version 1 lock');
      pinned = j.libraries;
    } catch (e) {
      if (!acceptLock) return [`error[JSC0732]: joust.lock cannot be read (${e.message}): fix it, or rewrite it with joust build --accept-lock`];
    }
  }
  const same = JSON.stringify(Object.entries(now).sort()) === JSON.stringify(Object.entries(pinned).sort());
  if (same && (exists || !Object.keys(now).length)) return [];
  if (acceptLock === true) {
    const sorted = Object.fromEntries(Object.entries(now).sort());
    writeFileSync(file, JSON.stringify({ lock: 1, libraries: sorted }, null, 2) + '\n');
    log(`joust.lock: ${exists ? 'updated' : 'created'} (${Object.keys(now).length} pinned librar${Object.keys(now).length === 1 ? 'y' : 'ies'}); review and commit it`);
    return [];
  }
  const errors = [];
  for (const [name, sha] of Object.entries(now)) {
    if (!(name in pinned)) {
      errors.push(`error[JSC0731]: the library ${name} (sha256 ${sha}) is not pinned in joust.lock: a library from outside the`
        + ' sources is pinned by the hash of its on-chain sources (ADR-0018 J12); review it, then pin it with joust build --accept-lock'
        + ' and commit joust.lock');
    } else if (pinned[name] !== sha) {
      errors.push(`error[JSC0732]: the library ${name} has other on-chain sources than joust.lock pins (sha256 ${sha}, pinned`
        + ` ${pinned[name]}): its code changed; review the change, then accept it with joust build --accept-lock`);
    }
  }
  for (const name of Object.keys(pinned)) if (!(name in now)) log(`joust.lock: ${name} is pinned but no longer used (accepting removes it)`);
  return errors;
}
