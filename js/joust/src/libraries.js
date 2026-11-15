// ADR-0018 D9, D14, J12 (M4c): the libraries of an npm project, and joust.lock.
//
// An npm library package ships its on-chain libraries under joust-lib/: .jsc libraries, imported by package specifier
// (import { fee } from "@acme/fees/fees.jsc" is node_modules/@acme/fees/joust-lib/fees.jsc), and Java (JuLC) on-chain
// sources, which java: imports name by class and the toolchain certifies before admitting. joust.lock, committed at the
// project's root, pins every library from outside the project's sources by the SHA-256 of its on-chain sources: a build
// whose library differs from its pin, or is not pinned, fails, unless it is accepted (joust build --accept-lock), which
// writes the lock to be reviewed and committed. Needs nothing but Node.

import { existsSync, lstatSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

// Review r2 (#82 S4): links are never followed. A link under joust-lib/ could make files outside the package (the
// project's own contracts, say) package sources, or loop.
const walk = (dir, out = []) => {
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    const st = lstatSync(p);
    if (st.isSymbolicLink()) continue;
    if (st.isDirectory()) walk(p, out);
    else if (st.isFile()) out.push(p);
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
      if (!lstatSync(p).isDirectory()) continue;
      for (const m of readdirSync(p)) out.push([`${n}/${m}`, join(p, m)]);
    } else {
      out.push([n, p]);
    }
  }
  return out;
}

/**
 * Every library the project's npm packages ship under joust-lib/: { packages: { "<package>/<path>.jsc": text },
 * javaLibraries: { "<path>.java": text } }. A Java source is keyed by its path inside joust-lib/ (its package path), as a
 * jar's META-INF/plutus-sources keys it on Gradle, so a library's pin does not depend on the npm package's name (review
 * r2, #82 F5); two packages shipping one path are refused (they would be one class twice).
 */
export function libraryPackages(root) {
  const packages = {};
  const javaLibraries = {};
  const javaFrom = {};
  for (const [name, dir] of packagesOf(root)) {
    const lib = join(dir, 'joust-lib');
    if (!existsSync(lib) || !lstatSync(lib).isDirectory()) continue;
    for (const f of walk(lib)) {
      const rel = relative(lib, f).split(sep).join('/');
      if (f.endsWith('.jsc')) packages[`${name}/${rel}`] = readFileSync(f, 'utf8');
      else if (f.endsWith('.java')) {
        if (rel in javaLibraries) {
          throw new Error(`error[JSC0709]: the npm packages ${javaFrom[rel]} and ${name} both ship joust-lib/${rel}: one Java class twice`);
        }
        javaLibraries[rel] = readFileSync(f, 'utf8');
        javaFrom[rel] = name;
      }
    }
  }
  return { packages, javaLibraries };
}

/**
 * The libraries a build used ({ kind, name, sha256, external }), by name: from the engine's generated files, and the
 * Java libraries hand-written Java contracts import ({@code libraries}, review r2 #82 C7).
 */
export function librariesUsed(generated, libraries = []) {
  const out = new Map();
  for (const l of libraries) out.set(l.name, l);
  for (const g of generated) for (const l of g.libraries ?? []) out.set(l.name, l);
  return [...out.values()].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
}

/**
 * J12's decision (ADR-0021 r10 E1: `joust build` and the editor's on-save build both decide through it): the libraries
 * a build used against a lock. `lock` is null when there is no joust.lock, `{ text }` with its contents, or
 * `{ unreadable: reason }` when it cannot be read. Returns `{ now, errors: [{ code, name, sha256, pinned, line }],
 * unused, lockError }`: `now` the pins the build needs (external libraries by name), `errors` one per library not pinned
 * (JSC0731) or pinned with another hash (JSC0732), `unused` pins no library needs (not errors), and `lockError` a lock
 * that cannot be read (JSC0732, then no other error).
 */
export function lockDecision({ used, lock }) {
  const now = Object.fromEntries(used.filter((l) => l.external).map((l) => [l.name.replace(/^pkg:/, ''), l.sha256]));
  let pinned = {};
  if (lock) {
    try {
      if (lock.unreadable) throw new Error(lock.unreadable);
      const j = JSON.parse(lock.text);
      if (!j || j.lock !== 1 || typeof j.libraries !== 'object' || j.libraries === null) throw new Error('not a version 1 lock');
      pinned = j.libraries;
    } catch (e) {
      const line = `error[JSC0732]: joust.lock cannot be read (${e.message}): fix it, or rewrite it with joust build --accept-lock`;
      return { now, errors: [], unused: [], lockError: { code: 'JSC0732', message: e.message, line } };
    }
  }
  const errors = [];
  for (const [name, sha] of Object.entries(now)) {
    if (!Object.prototype.hasOwnProperty.call(pinned, name)) {
      errors.push({ code: 'JSC0731', name, sha256: sha, pinned: null, line: `error[JSC0731]: the library ${name} (sha256 ${sha}) is not pinned in joust.lock: a library from outside the`
        + ' sources is pinned by the hash of its on-chain sources (ADR-0018 J12); review it, then pin it with joust build --accept-lock'
        + ' and commit joust.lock' });
    } else if (pinned[name] !== sha) {
      errors.push({ code: 'JSC0732', name, sha256: sha, pinned: pinned[name], line: `error[JSC0732]: the library ${name} has other on-chain sources than joust.lock pins (sha256 ${sha}, pinned`
        + ` ${pinned[name]}): its code changed; review the change, then accept it with joust build --accept-lock` });
    }
  }
  const unused = Object.keys(pinned).filter((name) => !(name in now));
  return { now, errors, unused, lockError: null };
}

/**
 * J12: reports every library the build used, and checks (or, accepting, writes) {@code root}/joust.lock for those from
 * outside the project's sources. Returns the errors, as lines.
 */
export function lockGate({ generated, libraries = [], root, acceptLock = false, log = console.log }) {
  const used = librariesUsed(generated, libraries);
  // Review r2 (#79 F4): a java: import is certified statically; its per-function vectors are the library's own tests.
  const label = (l) => (!l.external ? "the project's" : l.kind === 'java' ? 'statically certified; pinned'
    : l.kind === 'java-source' ? 'imported by a Java contract; pinned' : 'pinned');
  for (const l of used) log(`library ${l.name.replace(/^pkg:/, '')} sha256 ${l.sha256} (${label(l)})`);
  const file = join(root, 'joust.lock');
  const exists = existsSync(file);
  let lock = null;
  if (exists) {
    try {
      lock = { text: readFileSync(file, 'utf8') };
    } catch (e) {
      lock = { unreadable: e.message };
    }
  }
  const d = lockDecision({ used, lock });
  if (d.lockError && !acceptLock) return [d.lockError.line];
  // Accepting rewrites a lock that cannot be read (#121 F4: also when nothing needs pinning, as JSC0732 advises); one
  // that pins exactly what the build needs is left as it is.
  if (!d.errors.length && !d.unused.length && !d.lockError) return [];
  if (acceptLock === true) {
    const sorted = Object.fromEntries(Object.entries(d.now).sort());
    writeFileSync(file, JSON.stringify({ lock: 1, libraries: sorted }, null, 2) + '\n');
    log(`joust.lock: ${exists ? 'updated' : 'created'} (${Object.keys(d.now).length} pinned librar${Object.keys(d.now).length === 1 ? 'y' : 'ies'}); review and commit it`);
    return [];
  }
  for (const name of d.unused) log(`joust.lock: ${name} is pinned but no longer used (accepting removes it)`);
  return d.errors.map((e) => e.line);
}
