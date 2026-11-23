// The playground page's files (ADR-0024 D6): one static bundle shipped in @julclang/joust. A published package carries
// it prebuilt in playground/dist/; from a source checkout it is built in memory with Vite (and @julclang/joust/vite's
// browser polyfills for cardano-js-sdk), never written into the project (I10): Vite's dependency cache goes to a
// temporary directory.

import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

export const PAGE_SOURCE = fileURLToPath(new URL('../../playground/', import.meta.url));
const DIST = join(PAGE_SOURCE, 'dist');

function readTree(dir) {
  const out = {};
  const walk = (d) => {
    for (const name of readdirSync(d)) {
      const p = join(d, name);
      if (statSync(p).isDirectory()) walk(p);
      else out[relative(dir, p).split(sep).join('/')] = readFileSync(p);
    }
  };
  walk(dir);
  return out;
}

/** Builds the page with Vite into memory: { 'index.html': Buffer, 'assets/…': Buffer }. */
export async function buildPage({ outDir } = {}) {
  let vite;
  try {
    vite = await import('vite');
  } catch {
    throw new Error('the playground page is not prebuilt in this package and Vite is not installed: npm install --save-dev vite');
  }
  const { joust } = await import('../vite.js');
  const cacheDir = mkdtempSync(join(tmpdir(), 'joust-playground-vite-'));
  try {
    const result = await vite.build({
      root: PAGE_SOURCE, base: './', configFile: false, logLevel: 'error', cacheDir, publicDir: false,
      plugins: [joust({ root: PAGE_SOURCE })],
      build: { outDir: outDir ?? join(cacheDir, 'dist'), write: !!outDir, emptyOutDir: true, target: 'es2022', minify: true,
        modulePreload: { polyfill: false }, assetsInlineLimit: 0, chunkSizeWarningLimit: 100_000 },
    });
    if (outDir) return readTree(outDir);
    const files = {};
    for (const r of [result].flat()) {
      for (const o of r.output) files[o.fileName] = Buffer.from(o.type === 'asset' ? o.source : o.code);
    }
    return files;
  } finally {
    rmSync(cacheDir, { recursive: true, force: true });
  }
}

/** The page: the prebuilt bundle when the package has one, else built now. */
export async function pageFiles() {
  if (existsSync(join(DIST, 'index.html'))) return readTree(DIST);
  return buildPage();
}
