// The page (page/) under joust dev or vite build: @julclang/joust/vite makes the runtime packages work in browsers.
import { fileURLToPath } from 'node:url';
import { joust } from '@julclang/joust/vite';

const here = (p) => fileURLToPath(new URL(p, import.meta.url));

export default { root: here('page'), build: { outDir: here('dist'), emptyOutDir: true }, plugins: [joust({ root: here('.') })] };
