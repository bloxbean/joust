// Builds the playground page into playground/dist/ (ADR-0024 D6), which a published @julclang/joust ships: an install
// without Vite (the native joust's per-user cache, ADR-0024 Q1) serves it as it is. Run before packing the package:
//   node js/joust/scripts/build-playground.mjs
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { buildPage, PAGE_SOURCE } from '../src/playground/page.js';

const out = join(PAGE_SOURCE, 'dist');
const files = await buildPage({ outDir: out });
if (!existsSync(join(out, 'index.html'))) throw new Error('the playground page built no index.html');
console.log(`playground page: ${Object.keys(files).length} files in ${out}`);
