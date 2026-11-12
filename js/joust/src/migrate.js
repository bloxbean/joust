// joust migrate (ADR-0018 D16): .jsc v1 files translated to .jsc 2 by the toolchain engine (the same Migrate as Gradle's
// migrateJsc). By default every v1 file under contracts/ is written to build/joust/migrated/<same path>, and contracts/
// does not change; --in-place replaces each v1 file and keeps its text beside it as <Name>.jsc.v1 (never overwritten),
// after keeping the v1 build's artifacts in build/joust/v1/. A migrated contract is a new script: instances deployed
// from the v1 file stay on the v1 script.
import { copyFileSync, existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, realpathSync, statSync, writeFileSync } from 'node:fs';
import { basename, dirname, isAbsolute, join, relative, resolve } from 'node:path';
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

/** The contract a v1 file declares (its build artifacts are build/joust/<Name>.*). */
const contractOf = (text) => /^\s*contract\s+([A-Za-z0-9_]+)/m.exec(text)?.[1];

/**
 * Review r2 S1: before --in-place replaces a v1 file, the v1 build's artifacts of its contract (build/joust/<Name>.json,
 * .plutus, .js, .d.ts, and the deployments files) are copied to build/joust/v1/, which no build writes, so v1 instances
 * stay callable. Returns the copied paths.
 */
function keepV1Artifacts(root, out, name) {
  const dir = resolve(root, out);
  const keep = join(dir, 'v1');
  const copied = [];
  if (!name || !existsSync(dir)) return copied;
  const files = [...['.json', '.plutus', '.js', '.d.ts'].map((x) => name + x),
    ...readdirSync(dir).filter((f) => /^deployments\..+\.json$/.test(f) || f === 'contracts.json' || f === 'joust.lock.json')];
  for (const f of files) {
    const from = join(dir, f);
    if (!existsSync(from) || !lstatSync(from).isFile()) continue;
    mkdirSync(keep, { recursive: true });
    const to = join(keep, f);
    if (present(to) && !f.startsWith(name + '.')) continue;   // an earlier snapshot of the shared files stays
    copyFileSync(from, to);
    copied.push(to);
  }
  return copied;
}

/** Migrates {@code files} (paths, else every .jsc under src); returns { ok, migrated, refused }. */
export async function migrate({ root = process.cwd(), src = 'contracts', out = 'build/joust', files = [], inPlace = false,
  log = console.log, error = console.error } = {}) {
  const srcDir = resolve(root, src);
  const migratedDir = resolve(root, out, 'migrated');
  if (!inPlace && (inside(migratedDir, srcDir) || (existsSync(srcDir) && existsSync(migratedDir)
    && inside(realpathSync(migratedDir), realpathSync(srcDir))))) {
    error(`error[JSC1108]: ${relative(root, migratedDir)} is inside the contracts directory ${relative(root, srcDir) || '.'}: without`
      + ' --in-place, migrate writes outside the sources and never changes them; choose another "out" in joust.config.json');
    return { ok: false, migrated: [], refused: [] };
  }
  const paths = files.length ? files.map((f) => resolve(root, f)) : jscFiles(srcDir);
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
      // Never through a link (review r2 S2): a link's target may be shared, or outside the project.
      if (lstatSync(path).isSymbolicLink()) {
        error(`error[JSC1109]: ${m.file}:1:1: this file is a symbolic link: migrate --in-place never replaces a file through a link;`
          + ' migrate a copy, or the link\'s target in its own project');
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
      const kept = keepV1Artifacts(root, out, contractOf(texts[m.file]));
      writeFileSync(path, m.source);
      for (const n of m.notes) log(`note: ${where(n)}: ${n.message}`);
      log(`migrated ${m.file} to .jsc 2 in place (the v1 text is in ${relative(root, backup)}`
        + `${kept.length ? `; the v1 build's artifacts are kept in ${relative(root, dirname(kept[0]))}` : ''})`);
      migrated.push({ file: m.file, target: path });
      continue;
    }
    const target = resolve(migratedDir, m.file);
    if (!inside(target, migratedDir) || (present(target) && realpathSync(target) === realpathSync(path))) {
      error(`error[JSC1108]: ${m.file}:1:1: the output ${relative(root, target)} would replace a source: without --in-place, migrate`
        + ' writes only under ' + relative(root, migratedDir));
      refused.push(m.file);
      continue;
    }
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, m.source);
    for (const n of m.notes) log(`note: ${where(n)}: ${n.message}`);
    log(`migrated ${m.file} to .jsc 2: ${relative(root, target)}`);
    migrated.push({ file: m.file, target });
  }
  if (migrated.length) log(`warning: ${r.newScript}`);
  if (migrated.length && inPlace) {
    log('warning: the next joust build replaces the v1 artifacts in build/joust (kept in build/joust/v1/); joust deploy keeps'
      + ' superseded deployments in deployments.<network>.json');
  }
  if (!migrated.length && !refused.length) log(`no .jsc v1 file${files.length ? ' among those given' : ` under ${relative(root, srcDir) || '.'}`}`);
  return { ok: refused.length === 0, migrated, refused };
}
