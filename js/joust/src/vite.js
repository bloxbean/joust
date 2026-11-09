// @julclang/joust/vite: the Vite plugin for a JOUST page (ADR-0015 M4).
//
//   import { joust } from '@julclang/joust/vite';
//   export default { plugins: [joust()] };
//
// - The runtime packages read ledger formats through cardano-js-sdk, whose crypto dependencies (pbkdf2, safe-buffer,
//   readable-stream) expect Node's buffer, events, util, stream and process: those modules are aliased to their
//   browser versions (node-stdlib-browser), and the Buffer and process globals are injected where dependencies use
//   them. crypto is not needed (the SDK uses WebCrypto and libsodium in browsers).
// - Every polyfill is resolved from this package, by absolute path: it works however the project's node_modules are
//   laid out (npm hoisting or not, pnpm).
// - The packages use top-level await: es2022 for the dev server's dependency bundling and for builds.
// - The page may import the build's clients (build/joust/*.js) from the project root, outside the page directory.
import { createRequire } from 'node:module';
import { realpathSync } from 'node:fs';
import { nodePolyfills } from 'vite-plugin-node-polyfills';
import inject from '@rollup/plugin-inject';

const require = createRequire(import.meta.url);
const shim = (name) => require.resolve(`vite-plugin-node-polyfills/shims/${name}`).replace(/index\.cjs$/, 'index.js');

export function joust({ root = process.cwd() } = {}) {
  const buffer = shim('buffer');
  const proc = shim('process');
  return [
    // Module aliases only (absolute paths); the globals are injected below, from absolute paths too.
    nodePolyfills({ include: ['buffer', 'events', 'util', 'stream', 'process'], globals: { Buffer: false, global: false, process: false } }),
    {
      name: 'joust',
      config: (c) => ({
        define: { global: 'globalThis' },
        esbuild: { target: 'es2022' },
        optimizeDeps: { esbuildOptions: { target: 'es2022', define: { global: 'globalThis' }, inject: [buffer, proc],
          // Vite's dependency scan would mark these absolute paths external, which esbuild refuses for injected files.
          plugins: [{ name: 'joust-shims', setup(b) {
            for (const p of [buffer, proc]) b.onResolve({ filter: new RegExp(`^${p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) }, () => ({ path: p, external: false }));
          } }] } },
        build: { target: 'es2022', rollupOptions: { plugins: [inject({ Buffer: [buffer, 'Buffer'], process: [proc, 'process'] })] } },
        server: { fs: { allow: [...new Set([root, c.root ?? root].flatMap((p) => [p, realpathSync(p)]))] } },
      }),
    },
  ];
}

export default joust;
