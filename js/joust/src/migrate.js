// joust migrate (ADR-0018 D16): .jsc v1 files translated to .jsc 2 by the toolchain engine (the same Migrate as Gradle's
// migrateJsc). By default every v1 file under contracts/ is written to build/joust/migrated/<same path>, and contracts/
// does not change; --in-place replaces each v1 file and keeps its text beside it as <Name>.jsc.v1 (never overwritten),
// after keeping the v1 build's artifacts in build/joust/v1/. A migrated contract is a new script: instances deployed
// from the v1 file stay on the v1 script.
import { copyFileSync, existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, realpathSync, renameSync, rmSync, statSync,
  writeFileSync } from 'node:fs';
import { basename, dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import { loadToolchain } from './toolchain.js';

/** Whether {@code p} exists, a dangling link included (lstat: never follows a link). */
function present(p) {
  try {
    lstatSync(p);
    return true;
  } catch {
    return false;
  }
}

/** Whether {@code child} is {@code parent} or inside it. */
const inside = (child, parent) => {
  const r = relative(parent, child);
  return r === '' || (!r.startsWith('..') && !isAbsolute(r));
};

/**
 * {@code p}'s real path, through its nearest existing ancestor (the rest appended as written), as the JVM's
 * JscMain.resolved does (review r3 S6: a link on the path counts even before the directory exists).
 */
function resolved(p) {
  let e = resolve(p);
  const rest = [];
  while (!present(e)) {
    const up = dirname(e);
    if (up === e) return resolve(p);
    rest.unshift(basename(e));
    e = up;
  }
  let real;
  try {
    real = realpathSync(e);
  } catch {
    real = e;   // a dangling link: its own path
  }
  return join(real, ...rest);
}

/** Whether {@code p} lies in {@code dir}, by path or through any link (resolved at its nearest existing ancestor). */
const within = (p, dir) => inside(resolve(p), resolve(dir)) || (present(dir) && inside(resolved(p), realpathSync(dir)));

/** The .jsc files under {@code dir}: links to directories are not followed, dangling links skipped (review r2 S2). */
function jscFiles(dir) {
  const out = [];
  const walk = (d) => {
    for (const n of readdirSync(d)) {
      const p = join(d, n);
      const s = lstatSync(p);
      if (s.isDirectory()) walk(p);
      else if (p.endsWith('.jsc') && (s.isFile() || (s.isSymbolicLink() && (() => {
        try { return statSync(p).isFile(); } catch { return false; }
      })()))) out.push(p);
    }
  };
  if (existsSync(dir)) walk(dir);
  return out.sort();
}

/**
 * Review r3 S5: why a file may not be replaced in place, or null. It is a plain file of the sources: no symbolic link,
 * no hard link shared with another path, no link on its path under the contracts directory, and its real path lies
 * under the contracts directory's.
 */
function inPlaceProblem(path, srcDir, root, given) {
  // #80 review F6 (#75 on npm): never a path with '..' (resolved lexically here, after a link by the file system).
  if (given !== undefined && given.split(/[\\/]/).includes('..')) {
    return `the path ${given} contains '..': migrate --in-place takes a path without it, so no link on the way can be skipped`;
  }
  const s = lstatSync(path);
  if (s.isSymbolicLink()) return 'this file is a symbolic link: migrate --in-place never replaces a file through a link; migrate a copy, or the link\'s target in its own project';
  if (s.nlink > 1) return `this file has ${s.nlink} hard links: another path shares its contents (another project, still on v1?), so migrate --in-place does not replace it; migrate a copy`;
  if (!inside(path, srcDir)) return `only files under ${basename(srcDir)}/ are migrated in place`;
  for (let d = dirname(path); d !== srcDir && inside(d, srcDir); d = dirname(d)) {
    if (lstatSync(d).isSymbolicLink()) return `the directory ${relative(srcDir, d)} is a symbolic link: migrate --in-place never replaces a file through a link`;
  }
  // #80 review F6 (#75 on npm, as JscMain): every directory below the project root (the working directory, found among
  // the file's ancestors by identity), the contracts directory included, is checked on the physical path.
  const link = linkBelow(dirname(path), root);
  if (link) return `the directory ${link} is a symbolic link (or another kind of link): migrate --in-place never replaces a file through a link`;
  if (!inside(realpathSync(path), realpathSync(srcDir))) return `this file's real path is not under ${basename(srcDir)}/`;
  return null;
}

/**
 * The first directory from `root` (found among `dir`'s ancestors by device and inode, so an alias of it counts) down
 * to `dir` that is a link, relative to `root`; null when there is none, or when `dir` is not below `root` (a file
 * elsewhere is checked from its contracts directory down only). A link is a symbolic link, or a directory whose real
 * path is not in its parent's under its own name, ignoring case (a case-insensitive file system keeps its own spelling).
 */
function linkBelow(dir, root) {
  const id = (p) => { const st = statSync(p); return `${st.dev}:${st.ino}`; };
  const rootId = id(root);
  const chain = [];
  for (let d = resolve(dir); ; d = dirname(d)) {
    chain.unshift(d);
    if (dirname(d) === d) break;
  }
  const at = chain.findIndex((d) => id(d) === rootId);
  if (at < 0) return null;
  let physical = realpathSync(chain[at]);
  for (const d of chain.slice(at + 1)) {
    const name = basename(d);
    const next = join(physical, name);
    const real = lstatSync(next).isSymbolicLink() ? null : realpathSync(next);
    if (real === null || dirname(real) !== physical || basename(real).toLowerCase() !== name.toLowerCase()) {
      return relative(realpathSync(chain[at]), next);
    }
    physical = real;
  }
  return null;
}

/**
 * Review r2 S1, r3 C4: before --in-place replaces a v1 file, the v1 build's artifacts are copied to build/joust/v1/,
 * which no build writes, so v1 instances stay callable: the shared ones (contracts.json, joust.lock.json, the deployments
 * files) always, and the contract's own (<Name>.json, .plutus, .js, .d.ts) by the name the engine read from the v1 file.
 * A copy that fails throws, and the file is not replaced. Returns the copied paths.
 */
function keepV1Artifacts(root, out, name) {
  const dir = resolve(root, out);
  const keep = join(dir, 'v1');
  const copied = [];
  if (!existsSync(dir)) return copied;
  const own = name ? ['.json', '.plutus', '.js', '.d.ts'].map((x) => name + x) : [];
  const shared = readdirSync(dir).filter((f) => /^deployments\..+\.json$/.test(f) || f === 'contracts.json' || f === 'joust.lock.json');
  for (const f of [...own, ...shared]) {
    const from = join(dir, f);
    if (!existsSync(from) || !lstatSync(from).isFile()) continue;
    mkdirSync(keep, { recursive: true });
    const to = join(keep, f);
    if (present(to) && !own.includes(f)) continue;   // an earlier snapshot of the shared files stays
    copyFileSync(from, to);
    copied.push(to);
  }
  return copied;
}

/**
 * Writes {@code text} to a new file beside {@code path} and renames it over {@code path} (review r3 S5, r4 C5): the
 * directory entry is replaced, never the contents of an inode another path shares. The temporary name is unique, and
 * only a file this call created is removed (review r4 C6).
 */
function replace(path, text) {
  const tmp = join(dirname(path), `.${basename(path)}.${process.pid}.${randomBytes(8).toString('hex')}.migrating`);
  let created = false;
  try {
    writeFileSync(tmp, text, { flag: 'wx' });
    created = true;
    renameSync(tmp, path);
    created = false;
  } finally {
    if (created) rmSync(tmp, { force: true });
  }
}

/** The file identity of {@code p} (device and inode), or null. */
function identity(p) {
  try {
    const s = statSync(p);
    return `${s.dev}:${s.ino}`;
  } catch {
    return null;
  }
}

/** Migrates {@code files} (paths, else every .jsc under src); returns { ok, migrated, refused }. */
export async function migrate({ root = process.cwd(), src = 'contracts', out = 'build/joust', files = [], inPlace = false,
  log = console.log, error = console.error } = {}) {
  const srcDir = resolve(root, src);
  const migratedDir = resolve(root, out, 'migrated');
  if (!inPlace && within(migratedDir, srcDir)) {
    error(`error[JSC1108]: ${relative(root, migratedDir)} is inside the contracts directory ${relative(root, srcDir) || '.'}`
      + ' (by path or through a link): without --in-place, migrate writes outside the sources and never changes them; choose'
      + ' another "out" in joust.config.json');
    return { ok: false, migrated: [], refused: [] };
  }
  const paths = files.length ? files.map((f) => resolve(root, f)) : jscFiles(srcDir);
  const given = Object.fromEntries(files.map((f) => [resolve(root, f), f]));
  const missing = paths.filter((p) => !present(p));
  if (missing.length) throw new Error(`no such file: ${missing.map((p) => relative(root, p)).join(', ')}`);
  // Each file by its path under contracts/; a file from elsewhere by its base name, so its output stays in migrated/
  // (review r2 S3).
  const name = (p) => (inside(p, srcDir) && p !== srcDir ? relative(srcDir, p) : basename(p));
  const byName = {};
  const texts = {};
  const migrated = [];
  const refused = [];
  for (const p of paths) {
    const n = name(p);
    if (byName[n]) {
      error(`error[JSC1108]: ${relative(root, p)}: another file given is also named ${n}; migrate them one at a time`);
      refused.push(n);
      continue;
    }
    byName[n] = p;
    try {
      texts[n] = readFileSync(p, 'utf8');
    } catch (e) {
      error(`error: ${relative(root, p)}: ${e.message}`);
      refused.push(n);
      delete byName[n];
    }
  }
  // Review r3 C2: every default-mode target checked before anything is written. A target is refused when, through any
  // link on its path (resolved at its nearest existing ancestor), it lies in the sources or is any source file.
  if (!inPlace) {
    const sources = new Set(paths.filter(present).map((p) => { try { return realpathSync(p); } catch { return p; } }));
    // By identity too (review r4 C5): a hard link has its own path but a source's inode.
    const ids = new Set([...paths, ...jscFiles(srcDir)].map(identity).filter(Boolean));
    let bad = 0;
    for (const n of Object.keys(byName)) {
      const target = resolve(migratedDir, n);
      const real = resolved(target);
      const exists = present(target);
      const why = !inside(target, migratedDir) ? 'is outside build/joust/migrated'
        : within(target, srcDir) ? 'lies in the contracts directory (through a link on its path)'
          : sources.has(real) ? 'is a source file'
            : exists && ids.has(identity(target)) ? 'is a source file (a hard link to it)'
              : exists && lstatSync(target).nlink > 1 ? 'has more than one hard link (another path shares its contents)' : null;
      if (why) {
        error(`error[JSC1108]: ${n}:1:1: the output ${relative(root, target)} ${why}: without --in-place, migrate never changes the`
          + ' sources; nothing was written');
        bad++;
      }
    }
    if (bad) return { ok: false, migrated: [], refused: Object.keys(byName) };
  }
  const engine = await loadToolchain();
  const r = engine.joust.migrate({ files: texts });
  const where = (d) => `${d.file}${d.line > 0 ? ':' + d.line : ''}${d.line > 0 && d.col > 0 ? ':' + d.col : ''}`;
  for (const m of r.files) {
    if (m.skipped) {
      if (files.length) log(`${m.file}: already .jsc 2`);
      continue;
    }
    if (!m.ok) {
      error(`error[${m.error.code}]: ${where(m.error)}: ${m.error.message}`);
      refused.push(m.file);
      continue;
    }
    const path = byName[m.file];
    if (inPlace) {
      const why = inPlaceProblem(path, srcDir, root, given[path]);
      if (why) {
        error(`error[JSC1109]: ${m.file}:1:1: ${why}`);
        refused.push(m.file);
        continue;
      }
      const backup = path + '.v1';
      try {
        writeFileSync(backup, texts[m.file], { flag: 'wx' });   // O_EXCL: refuses an existing file or a dangling link
      } catch (e) {
        if (e.code !== 'EEXIST') throw e;
        error(`error[JSC1107]: ${m.file}:1:1: ${basename(backup)} exists already: migrate --in-place keeps the v1`
          + ' text there and never overwrites it; move it away first');
        refused.push(m.file);
        continue;
      }
      let kept;
      try {
        kept = keepV1Artifacts(root, out, m.contract);
      } catch (e) {
        rmSync(backup, { force: true });
        error(`error: ${m.file}: the v1 build's artifacts could not be kept (${e.message}), so the file was not replaced`);
        refused.push(m.file);
        continue;
      }
      replace(path, m.source);
      for (const n of m.notes) log(`note: ${where(n)}: ${n.message}`);
      log(`migrated ${m.file} to .jsc 2 in place (the v1 text is in ${relative(root, backup)}`
        + `${kept.length ? `; the v1 build's artifacts of ${m.contract} are kept in ${relative(root, dirname(kept[0]))}` : ''})`);
      migrated.push({ file: m.file, target: path, kept });
      continue;
    }
    const target = resolve(migratedDir, m.file);
    mkdirSync(dirname(target), { recursive: true });
    replace(target, m.source);   // a rename over the target: never a write into an existing inode (review r4 C5)
    for (const n of m.notes) log(`note: ${where(n)}: ${n.message}`);
    log(`migrated ${m.file} to .jsc 2: ${relative(root, target)}`);
    migrated.push({ file: m.file, target });
  }
  if (migrated.length) log(`warning: ${r.newScript}`);
  if (migrated.some((x) => x.kept?.length)) {
    log('warning: the next joust build replaces the v1 artifacts in build/joust (kept in build/joust/v1/); joust deploy keeps'
      + ' superseded deployments in deployments.<network>.json');
  }
  if (!migrated.length && !refused.length) log(`no .jsc v1 file${files.length ? ' among those given' : ` under ${relative(root, srcDir) || '.'}`}`);
  return { ok: refused.length === 0, migrated, refused };
}

