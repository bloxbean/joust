// Before `astro build`: no page may hard-code a release version (WRITING.md, "Versions"). JOUST releases often, so a
// number in a page goes stale; pages link to https://github.com/bloxbean/joust/releases or write `<version>`.
// Fails on, in any text file under src/:
//   - a prerelease version literal (JOUST's 0.1.0-preN..., and JuLC's and cardano-client-lib's, which a release brings);
//   - a final version in the shapes pages use: create-joust@x.y.z, @julclang/<pkg>@x.y.z, joust@x.y.z,
//     <name>Version=x.y.z, version 'x.y.z', "joust": "x.y.z", and Maven coordinates org.julclang:... or
//     com.bloxbean.cardano:...:x.y.z.
// A deliberate mention carries the marker `joust-version-ok: <version>` on the same line: it exempts only the version
// it names, only on that line (in MDX prose `{/* joust-version-ok: 1.2.3-pre4 */}`, in a code block a comment).
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src');
const V = String.raw`\d+\.\d+\.\d+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?`;
const PATTERNS = [
  new RegExp(String.raw`\b\d+\.\d+\.\d+-pre\d[\w.-]*`, 'g'),
  new RegExp(String.raw`(?:\bcreate-joust|@julclang/[a-z-]+|\bjoust)@(${V})`, 'g'),
  new RegExp(String.raw`\b[a-z][A-Za-z]*Version\s*=\s*['"]?(${V})`, 'g'),
  new RegExp(String.raw`\bversion\s+['"](${V})['"]`, 'g'),
  new RegExp(String.raw`"joust"\s*:\s*"(${V})"`, 'g'),
  new RegExp(String.raw`\b(?:org\.julclang|com\.bloxbean\.cardano):[\w.-]+:(${V})`, 'g'),
];
const MARKER = /joust-version-ok:\s*([0-9][0-9A-Za-z.-]*[0-9A-Za-z])/g;
const TEXT = /\.(mdx?|astro|mjs|js|ts|json|html|ya?ml|toml|txt|css)$/;

async function walk(dir, out = []) {
  for (const e of await fs.readdir(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) await walk(p, out);
    else if (TEXT.test(e.name)) out.push(p);
  }
  return out;
}

const found = [];
let exempted = 0;
for (const f of await walk(SRC)) {
  const lines = (await fs.readFile(f, 'utf8')).split('\n');
  lines.forEach((line, i) => {
    const allowed = new Set([...line.matchAll(MARKER)].map((m) => m[1]));
    const hits = new Set();
    for (const re of PATTERNS) for (const m of line.matchAll(re)) hits.add(m[1] ?? m[0]);
    for (const v of hits) {
      if (allowed.has(v)) exempted++;
      else found.push(`${path.relative(path.dirname(SRC), f)}:${i + 1}: ${v}`);
    }
  });
}
if (found.length) {
  console.error(`check-versions: ${found.length} hard-coded version(s); link to https://github.com/bloxbean/joust/releases ` +
    `or write <version> (WRITING.md, "Versions"), or mark a deliberate mention with joust-version-ok: <version>:\n  ` +
    found.join('\n  '));
  process.exit(1);
}
console.log(`check-versions: no hard-coded release versions in src/ (${exempted} exemption(s) by joust-version-ok)`);
