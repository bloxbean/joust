// joust fmt [--check] [<file or dir>...] (ADR-0021 r9 §15): the .jsc formatter, through the toolchain engine's lang.format
// (the language core's one entry, which jsc --fmt and the server's textDocument/formatting call too). A file that does not
// lex or parse is reported at its first error and left as it was (F4); a file is written only when its text changes, all
// at once (a temporary file beside it, renamed over it).
import { lstatSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { randomBytes } from 'node:crypto';
import { loadToolchain } from './toolchain.js';

/** The .jsc files under `paths` (directories walked, node_modules and links skipped), sorted; or `{ error }`. */
export function jscFiles(paths) {
  const out = [];
  const walk = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true }).sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))) {
      const p = join(dir, e.name);
      if (e.isDirectory() && e.name !== 'node_modules' && !e.name.startsWith('.')) walk(p);
      else if (e.isFile() && e.name.endsWith('.jsc')) out.push(p);
    }
  };
  for (const p of paths) {
    let st;
    try {
      st = lstatSync(p);
    } catch {
      return { error: `${p}: no such file or directory` };
    }
    if (st.isDirectory()) walk(p);
    else if (st.isFile()) out.push(p);
    else return { error: `${p}: not a regular file or a directory` };
  }
  return { files: out };
}

/**
 * Formats `paths` (or, with `check`, lists the files that are not formatted and writes nothing). Returns the exit code:
 * 0 when every file is formatted (with check: already was), 1 when one is refused or (with check) not formatted, 2 when
 * a path is not a file or a directory or the engine cannot format.
 */
export async function fmt({ paths, check = false, engine, log = console.log, error = console.error } = {}) {
  const found = jscFiles(paths);
  if (found.error) {
    error(`joust fmt: ${found.error}`);
    return 2;
  }
  const api = engine ?? await loadToolchain();
  if (typeof api?.lang?.format !== 'function') {
    error('joust fmt: this toolchain engine has no lang.format (it predates JOUST M5d)');
    return 2;
  }
  let refused = 0;
  let changed = 0;
  for (const f of found.files) {
    const text = readFileSync(f, 'utf8');
    const r = api.lang.format({ source: text });
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
    changed++;
    if (check) {
      log(`${f}: not formatted`);
      continue;
    }
    const tmp = join(dirname(f), `.${basename(f)}.${randomBytes(6).toString('hex')}.fmt`);
    try {
      writeFileSync(tmp, a.text, { flag: 'wx' });
      renameSync(tmp, f);
    } finally {
      rmSync(tmp, { force: true });
    }
    log(`${f}: formatted`);
  }
  log(`joust fmt: ${found.files.length} file(s), ${changed} ${check ? 'not formatted' : 'formatted'}, ${refused} refused`);
  return refused || (check && changed) ? 1 : 0;
}
