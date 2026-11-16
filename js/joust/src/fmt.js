// joust fmt [--check] [<file or dir>...] (ADR-0021 r9 §15): the .jsc formatter, through the toolchain engine's lang.format
// (the language core's one entry, which jsc --fmt and the server's textDocument/formatting call too). A file is read as
// UTF-8 strictly (#120 F1) and formatted as a module of its project (C3: its imports read as the build reads them). It is
// refused, and left as it was, when it does not lex or parse (F4), is not valid UTF-8, or is read-only (F2: never made
// writable); a rewritten file keeps its mode, and is written whole (a temporary file beside it, renamed over it).
// Exit codes (F5): 0 formatted (with --check: already was), 1 not formatted (--check), 2 a file refused, 3 usage.
import { accessSync, chmodSync, constants, lstatSync, readdirSync, readFileSync, realpathSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { randomBytes } from 'node:crypto';
import { loadToolchain } from './toolchain.js';
import { libraryPackages } from './libraries.js';

export const FMT_OK = 0;
export const FMT_NOT_FORMATTED = 1;
export const FMT_REFUSED = 2;
export const FMT_USAGE = 3;

// #120 F1: malformed bytes refuse the file; F9: a leading byte order mark is kept (TextDecoder drops it by default).
const strict = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
/** #120 S5: at most this many files, and characters, are read for a file's imports. */
const IMPORT_FILES = 5000;
const IMPORT_CHARS = 64 * 1024 * 1024;

/**
 * The .jsc files under `paths` (directories walked, node_modules, dot-directories and links skipped), sorted, each with
 * its root; or `{ error }`. #120 S5: a directory that cannot be read is listed in `unreadable` and skipped.
 */
export function jscFiles(paths, { max = Infinity } = {}) {
  const out = [];
  const unreadable = [];
  const walk = (dir, root) => {
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      unreadable.push(dir);
      return;
    }
    for (const e of entries.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))) {
      if (out.length >= max) return;
      const p = join(dir, e.name);
      if (e.isDirectory() && e.name !== 'node_modules' && !e.name.startsWith('.')) walk(p, root);
      else if (e.isFile() && e.name.endsWith('.jsc')) out.push({ file: p, root });
    }
  };
  for (const p of paths) {
    let st;
    try {
      st = lstatSync(p);
    } catch {
      return { error: `${p}: no such file or directory` };
    }
    if (st.isDirectory()) walk(p, p);
    else if (st.isFile()) out.push({ file: p, root: dirname(p) });
    else return { error: `${p}: not a regular file or a directory` };
  }
  return { files: out, unreadable };
}

/**
 * #120 F10: the root the module ids of files in `dir` (a named file's directory, or a directory argument) are relative
 * to, as the build finds it: the contracts directory of the nearest joust.config.json above it (when `dir` is inside
 * it), else the nearest src/<set>/jsc directory (a Gradle source set), else `dir` itself.
 */
export function moduleRootOf(dir) {
  const real = realpathSync(dir);
  for (let d = real; ; d = dirname(d)) {
    try {
      const cfg = JSON.parse(readFileSync(join(d, 'joust.config.json'), 'utf8'));
      const c = realpathSync(resolve(d, typeof cfg.contracts === 'string' ? cfg.contracts : 'contracts'));
      if (real === c || real.startsWith(c + sep)) return c;
    } catch {
      // no project here
    }
    if (basename(d) === 'jsc' && basename(dirname(dirname(d))) === 'src') return d;
    if (dirname(d) === d) break;
  }
  return real;
}

/** The .jsc modules under `root` by id (and the npm packages' libraries as pkg:<package>/<path>), for imports (C3). */
function projectFiles(root, packagesRoot) {
  const files = {};
  const { files: found = [] } = jscFiles([root], { max: IMPORT_FILES });
  let chars = 0;
  for (const { file } of found) {
    try {
      const text = strict.decode(readFileSync(file));
      chars += text.length;
      if (chars > IMPORT_CHARS) break;
      files[relative(root, file).split(sep).join('/')] = text;
    } catch {
      // not a module the build could read either
    }
  }
  if (packagesRoot) {
    try {
      for (const [k, v] of Object.entries(libraryPackages(packagesRoot).packages)) files[`pkg:${k}`] = v;
    } catch {
      // the build reports a broken package
    }
  }
  return files;
}

/**
 * Formats `paths` (or, with `check`, lists the files that are not formatted and writes nothing). `contracts` and
 * `root`: the project's contracts directory (a file under it is a module of it, its npm packages' libraries too) and
 * root. Returns the exit code (above).
 */
export async function fmt({ paths, check = false, engine, root = null, contracts = null, log = console.log, error = console.error } = {}) {
  const found = jscFiles(paths);
  if (found.error) {
    error(`joust fmt: ${found.error}`);
    return FMT_USAGE;
  }
  const api = engine ?? await loadToolchain();
  if (typeof api?.lang?.format !== 'function') {
    error('joust fmt: this toolchain engine has no lang.format (it predates JOUST M5d)');
    return FMT_USAGE;
  }
  const contractsReal = contracts ? (() => { try { return realpathSync(contracts); } catch { return null; } })() : null;
  const projects = new Map();
  const projectOf = (r) => {
    if (!projects.has(r)) projects.set(r, projectFiles(r, r === contractsReal && root ? root : null));
    return projects.get(r);
  };
  let refused = found.unreadable.length;
  for (const d of found.unreadable) error(`${d}: error: cannot be read (skipped)`);
  let changed = 0;
  for (const { file: f, root: given } of found.files) {
    let text;
    try {
      text = strict.decode(readFileSync(f));
    } catch {
      error(`${f}: error: not valid UTF-8 (not formatted)`);
      refused++;
      continue;
    }
    // A file under the project's contracts directory is a module of it; any other, of the directory it was found in.
    const real = realpathSync(f);
    const base = contractsReal && (real === contractsReal || real.startsWith(contractsReal + sep)) ? contractsReal
      : moduleRootOf(given);
    const id = relative(base, real).split(sep).join('/');
    const r = api.lang.format({ source: text, id, files: projectOf(base) });
    const a = r?.ok ? JSON.parse(r.json) : null;
    if (!a) {
      error(`${f}: error: the formatter failed: ${r?.error ?? 'no result'} (not formatted)`);
      refused++;
      continue;
    }
    if (!a.ok) {
      error(`${f}:${a.error.line}:${a.error.col}: error[${a.error.code}] ${a.error.message} (not formatted)`);
      refused++;
      continue;
    }
    if (a.text === text) continue;
    if (!check) {
      try {
        accessSync(f, constants.W_OK);
      } catch {
        error(`${f}: error: read-only (not formatted; it is never made writable)`);
        refused++;
        continue;
      }
    }
    changed++;
    if (check) {
      log(`${f}: not formatted`);
      continue;
    }
    const mode = lstatSync(f).mode & 0o7777;
    const tmp = join(dirname(f), `.${basename(f)}.${randomBytes(6).toString('hex')}.fmt`);
    try {
      writeFileSync(tmp, a.text, { flag: 'wx', mode: 0o600 });
      chmodSync(tmp, mode);
      renameSync(tmp, f);
    } finally {
      rmSync(tmp, { force: true });
    }
    log(`${f}: formatted`);
  }
  log(`joust fmt: ${found.files.length} file(s), ${changed} ${check ? 'not formatted' : 'formatted'}, ${refused} refused`);
  return refused ? FMT_REFUSED : check && changed ? FMT_NOT_FORMATTED : FMT_OK;
}

/**
 * #120 S3: the default target, the configured contracts directory, only when it is inside the project root (real
 * paths): `{ path }` or `{ error }`.
 */
export function defaultTarget(root, contracts) {
  const want = isAbsolute(contracts) ? contracts : resolve(root, contracts);
  let real;
  try {
    real = realpathSync(want);
  } catch {
    return { error: `the contracts directory ${contracts} does not exist` };
  }
  const top = realpathSync(root);
  if (real !== top && !real.startsWith(top + sep)) {
    return { error: `joust.config.json's contracts (${contracts}) is outside the project (${top}): joust fmt formats only inside it; name the files to format them` };
  }
  return { path: want };
}
