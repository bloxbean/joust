// joust migrate (ADR-0018 D16): .jsc v1 files translated to .jsc 2 by the toolchain engine (the same Migrate as Gradle's
// migrateJsc). By default every v1 file under contracts/ is written to build/joust/migrated/<same path>, and contracts/
// does not change; --in-place replaces each v1 file and keeps its text beside it as <Name>.jsc.v1 (never overwritten).
// A migrated contract is a new script: instances deployed from the v1 file stay on the v1 script.
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { loadToolchain } from './toolchain.js';

function jscFiles(dir) {
  const out = [];
  const walk = (d) => {
    for (const n of readdirSync(d)) {
      const p = join(d, n);
      if (statSync(p).isDirectory()) walk(p);
      else if (p.endsWith('.jsc')) out.push(p);
    }
  };
  if (existsSync(dir)) walk(dir);
  return out.sort();
}

/** Migrates {@code files} (paths, else every .jsc under src); returns { ok, migrated, refused }. */
export async function migrate({ root = process.cwd(), src = 'contracts', out = 'build/joust', files = [], inPlace = false,
  log = console.log, error = console.error } = {}) {
  const srcDir = resolve(root, src);
  const paths = files.length ? files.map((f) => resolve(root, f)) : jscFiles(srcDir);
  const missing = paths.filter((p) => !existsSync(p));
  if (missing.length) throw new Error(`no such file: ${missing.map((p) => relative(root, p)).join(', ')}`);
  // Each file by its path under contracts/ (else under the project), as joust build names it.
  const name = (p) => (p.startsWith(srcDir + '/') ? relative(srcDir, p) : relative(root, p));
  const byName = Object.fromEntries(paths.map((p) => [name(p), p]));
  const engine = await loadToolchain();
  const r = engine.joust.migrate({ files: Object.fromEntries(paths.map((p) => [name(p), readFileSync(p, 'utf8')])) });
  const migrated = [];
  const refused = [];
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
    const backup = path + '.v1';
    if (inPlace && existsSync(backup)) {
      error(`error[JSC1107]: ${m.file}:1:1: ${relative(dirname(path), backup)} exists already: migrate --in-place keeps the v1`
        + ' text there and never overwrites it; move it away first');
      refused.push(m.file);
      continue;
    }
    const target = inPlace ? path : resolve(root, out, 'migrated', m.file);
    if (inPlace) {
      writeFileSync(backup, readFileSync(path, 'utf8'));
    } else {
      mkdirSync(dirname(target), { recursive: true });
    }
    writeFileSync(target, m.source);
    for (const n of m.notes) log(`note: ${where(n)}: ${n.message}`);
    log(`migrated ${m.file} to .jsc 2: ${relative(root, target)}${inPlace ? ` (the v1 text is in ${relative(root, backup)})` : ''}`);
    migrated.push({ file: m.file, target });
  }
  if (migrated.length) log(r.newScript);
  if (!migrated.length && !refused.length) log(`no .jsc v1 file${files.length ? ' among those given' : ` under ${relative(root, srcDir) || '.'}`}`);
  return { ok: refused.length === 0, migrated, refused };
}
