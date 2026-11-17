// Before `astro build`: no page may hard-code a release version (WRITING.md, "Versions"). JOUST releases often, so a
// number in a page goes stale; pages link to https://github.com/bloxbean/joust/releases or write `<version>`. Fails on
// any prerelease version literal (JOUST's 0.1.0-preN..., and JuLC's and cardano-client-lib's, which a JOUST release
// brings) under src/, unless the line, or the line before it, carries the marker `joust-version-ok` (a deliberate
// mention: in MDX prose `{/* joust-version-ok: why */}`, in a code block a comment in its language).
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src');
const VERSION = /\b\d+\.\d+\.\d+-pre\d[\w.-]*/g;
const MARKER = 'joust-version-ok';
const TEXT = /\.(mdx?|astro|mjs|js|ts|json|html)$/;

async function walk(dir, out = []) {
  for (const e of await fs.readdir(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) await walk(p, out);
    else if (TEXT.test(e.name)) out.push(p);
  }
  return out;
}

const found = [];
for (const f of await walk(SRC)) {
  const lines = (await fs.readFile(f, 'utf8')).split('\n');
  lines.forEach((line, i) => {
    if (line.includes(MARKER) || (i > 0 && lines[i - 1].includes(MARKER))) return;
    for (const m of line.matchAll(VERSION)) found.push(`${path.relative(path.dirname(SRC), f)}:${i + 1}: ${m[0]}`);
  });
}
if (found.length) {
  console.error(`check-versions: ${found.length} hard-coded version(s); link to https://github.com/bloxbean/joust/releases ` +
    `or write <version> (WRITING.md, "Versions"), or mark a deliberate mention with ${MARKER}:\n  ` + found.join('\n  '));
  process.exit(1);
}
console.log('check-versions: no hard-coded release versions in src/');
