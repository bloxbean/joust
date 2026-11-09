// After `astro build`: every internal link in dist/ must resolve to a built page or file. Fails the build otherwise.
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DIST = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../dist');

async function walk(dir, out = []) {
  for (const e of await fs.readdir(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) await walk(p, out);
    else out.push(p);
  }
  return out;
}

const files = await walk(DIST);
const exists = new Set(files.map((f) => '/' + path.relative(DIST, f).split(path.sep).join('/')));
const resolves = (href) => {
  const p = decodeURI(href.split('#')[0].split('?')[0]);
  if (!p || p === '/') return exists.has('/index.html');
  return exists.has(p) || exists.has(p.replace(/\/$/, '') + '/index.html') || exists.has(p + '.html');
};

const broken = [];
for (const f of files.filter((x) => x.endsWith('.html'))) {
  const html = await fs.readFile(f, 'utf8');
  for (const m of html.matchAll(/\shref="(\/[^"]*)"/g)) {
    const href = m[1];
    if (href.startsWith('//') || href.startsWith('/_astro/') || href.startsWith('/pagefind/')) continue;
    if (!resolves(href)) broken.push(`${path.relative(DIST, f)} -> ${href}`);
  }
}
if (broken.length) {
  console.error(`check-links: ${broken.length} broken internal link(s):\n  ` + [...new Set(broken)].join('\n  '));
  process.exit(1);
}
console.log(`check-links: ${files.filter((x) => x.endsWith('.html')).length} pages, every internal link resolves`);
