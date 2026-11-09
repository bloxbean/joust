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
// - The MerkleMap mirrors (Aiken's MPF) also use Node's assert, so that is aliased too.
// - The packages use top-level await: es2022 for the dev server's dependency bundling and for builds.
// - The runtime engine runs in a module worker (@julclang/joust-runtime/worker): workers are built as ES modules, with
//   the same aliases and globals (a build bundles workers separately, with their own plugins).
// - The page may import the build's clients (build/joust/*.js) from the project root, outside the page directory.
// - `virtual:joust/network` is what the page runs on: { network, networkId, chain, clock, ttlMs, deployments,
//   contracts, validators, engine: { url, integrity } }.
//   (explorer: the network's block-explorer transaction page, "{tx}" for the hash, or null.)
//   - Under joust dev it reads /joust/network.json and the contracts when the page loads (a save redeploys).
//   - In a build it is inlined, for the network `joust({ network })`, JOUST_NETWORK, Vite's mode when it names a
//     network (vite build --mode preview) or joust.config.json names, from
//     build/joust (run joust deploy first), and the engine's two files are copied into the build at
//     joust/engine-<id>/. A built page is static files: it needs only a chain API, at `chain` (the network's
//     "pageChain" in joust.config.json; "/chain" by default, as joust serve and joust dev proxy it).
//   - The engine's sha384 hashes are pinned in the page's own code (SRI): the worker fetches the script and the Wasm
//     with them, so a browser refuses an engine whose bytes differ, wherever it is served from.
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { realpathSync, readFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { loadConfig } from './config.js';
import { engineFiles, pageTtlMs } from './engine-files.js';
import { nodePolyfills } from 'vite-plugin-node-polyfills';
import inject from '@rollup/plugin-inject';

const require = createRequire(import.meta.url);
const shim = (name) => require.resolve(`vite-plugin-node-polyfills/shims/${name}`).replace(/index\.cjs$/, 'index.js');

const VIRTUAL = 'virtual:joust/network';
const RESOLVED = '\0joust:network';

/** The inlined network module of a build (see above). */
function builtNetwork(ctx, root, name, base, mode) {
  const config = loadConfig(root);
  const network = name ?? process.env.JOUST_NETWORK ?? (config.networks[mode] ? mode : config.network);
  const n = config.networks[network];
  if (!n) ctx.error(`no network ${network} in joust.config.json`);
  const out = resolve(root, config.out);
  const read = (f) => JSON.parse(readFileSync(join(out, f), 'utf8'));
  if (!existsSync(join(out, 'contracts.json'))) ctx.error(`no ${config.out}/contracts.json: run joust build first`);
  const deployments = join(out, `deployments.${network}.json`);
  if (!existsSync(deployments)) ctx.error(`no ${config.out}/deployments.${network}.json: deploy first (joust deploy --network ${network})`);
  const d = read(`deployments.${network}.json`);
  const engine = engineFiles();
  const dir = `joust/engine-${engine.id}`;
  for (const f of [engine.script, engine.wasm]) ctx.emitFile({ type: 'asset', fileName: `${dir}/${f.name}`, source: f.bytes });
  const value = {
    network, networkId: n.networkId ?? d.networkId, chain: n.pageChain ?? '/chain', clock: d.clock ?? n.clock,
    ttlMs: pageTtlMs(config, n), explorer: n.explorer ?? null, deployments: d.contracts ?? {}, contracts: read('contracts.json'),
    validators: existsSync(join(out, 'validators.json')) ? read('validators.json') : [],
    engine: { url: `${base}${dir}/${engine.script.name}`, integrity: { script: engine.script.integrity, wasm: engine.wasm.integrity } },
  };
  return `export default ${JSON.stringify(value)};\n`;
}

// Under joust dev: what the dev server says now (it redeploys on save).
const DEV_NETWORK = `const net = await (await fetch('/joust/network.json', { cache: 'no-store' })).json();
net.contracts = await (await fetch(net.contracts, { cache: 'no-store' })).json();
export default net;
`;

/**
 * @param o.root     the project (joust.config.json, build/joust); default the working directory
 * @param o.network  the network a build is for (default JOUST_NETWORK, else joust.config.json's)
 */
export function joust({ root = process.cwd(), network } = {}) {
  let command = 'serve';
  let mode = 'production';
  let base = '/';
  const buffer = fileURLToPath(new URL('./shims/buffer.js', import.meta.url));   // one Buffer class (see the shim)
  const proc = shim('process');
  // Module aliases only (absolute paths); the globals are injected below, from absolute paths too.
  const polyfills = () => nodePolyfills({ include: ['buffer', 'events', 'util', 'stream', 'process', 'assert'],
    globals: { Buffer: false, global: false, process: false } });
  const globals = () => inject({ Buffer: [buffer, 'Buffer'], process: [proc, 'process'] });
  return [
    polyfills(),
    {
      name: 'joust',
      resolveId: (id) => (id === VIRTUAL ? RESOLVED : null),
      load(id) {
        if (id !== RESOLVED) return null;
        return command === 'build' ? builtNetwork(this, root, network, base, mode) : DEV_NETWORK;
      },
      configResolved(c) {
        base = c.base ?? '/';
      },
      config: (c, env) => (command = env.command, mode = env.mode, {
        define: { global: 'globalThis' },
        esbuild: { target: 'es2022' },
        // Scan workers too (a worker's own dependencies, such as the mirrors', found only when it runs would make Vite
        // bundle again and reload the page): the HTML entries, as by default, and *.worker.js beside them.
        // The polyfills too, so that a source module importing one (node:buffer in the mirrors) is ready up front.
        optimizeDeps: { entries: c.optimizeDeps?.entries ?? ['**/*.html', '**/*.worker.{js,mjs,ts}'],
          include: ['node:buffer'], esbuildOptions: { target: 'es2022', define: { global: 'globalThis' }, inject: [buffer, proc],
          // Vite's dependency scan would mark these absolute paths external, which esbuild refuses for injected files.
          plugins: [{ name: 'joust-shims', setup(b) {
            for (const p of [buffer, proc]) b.onResolve({ filter: new RegExp(`^${p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) }, () => ({ path: p, external: false }));
          } }] } },
        build: { target: 'es2022', rollupOptions: { plugins: [globals()] } },
        // In a worker's bundle, inject after the CommonJS conversion ('post'): an import added to a CommonJS module
        // first (serialize-error uses Buffer) makes it look like an ES module, and its exports are lost.
        worker: { format: 'es', plugins: () => [polyfills(), { ...globals(), enforce: 'post' }] },
        server: { fs: { allow: [...new Set([root, c.root ?? root].flatMap((p) => [p, realpathSync(p)]))] } },
      }),
    },
  ];
}

export default joust;
