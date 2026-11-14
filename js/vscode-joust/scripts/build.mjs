// Builds the extension (ADR-0021 D9): dist/extension.js (the client, with vscode-languageclient), dist/server.js (the
// server entry and vscode-languageserver, one file; also each engine worker's entry), and engine/ (the toolchain engine
// files of the same release's @julclang/joust, with their SHA-256 in engine/engine.json for the provenance check, D11).
//   node scripts/build.mjs [--engine <dir>]   (default: ../joust/engine)
import { copyFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const root = fileURLToPath(new URL('..', import.meta.url));
const require = createRequire(join(root, 'package.json'));
const argv = process.argv.slice(2);
const engineDir = resolve(argv.includes('--engine') ? argv[argv.indexOf('--engine') + 1] : join(root, '..', 'joust', 'engine'));
const version = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version;

// The server's own dependencies come from this package's node_modules (pinned in package.json), whichever directory the
// server sources live in: nothing is resolved at run time, and nothing from js/joust's node_modules.
const pinned = {
  name: 'pinned',
  setup(b) {
    b.onResolve({ filter: /^vscode-languageserver(-textdocument)?(\/.*)?$|^vscode-jsonrpc(\/.*)?$|^vscode-languageserver-protocol(\/.*)?$/ }, (a) => (
      { path: require.resolve(a.path) }));
  },
};

rmSync(join(root, 'dist'), { recursive: true, force: true });
const common = { bundle: true, platform: 'node', format: 'cjs', target: 'node22', sourcemap: false, legalComments: 'inline', logLevel: 'warning' };
await build({ ...common, entryPoints: [join(root, 'src', 'extension.js')], outfile: join(root, 'dist', 'extension.js'), external: ['vscode'] });
await build({
  ...common, entryPoints: [join(root, 'src', 'server-entry.js')], outfile: join(root, 'dist', 'server.js'), plugins: [pinned],
  define: { EXTENSION_VERSION: JSON.stringify(version) },
  // server.js reads its version from import.meta.url only when none is given; the entry gives one.
  logOverride: { 'empty-import-meta': 'silent' },
});

rmSync(join(root, 'engine'), { recursive: true, force: true });
mkdirSync(join(root, 'engine'));
const digests = {};
for (const f of ['joust-toolchain.js', 'joust-toolchain.js.wasm']) {
  copyFileSync(join(engineDir, f), join(root, 'engine', f));
  digests[f] = createHash('sha256').update(readFileSync(join(engineDir, f))).digest('hex');
}
writeFileSync(join(root, 'engine', 'package.json'), '{ "type": "commonjs" }\n');
writeFileSync(join(root, 'engine', 'engine.json'), JSON.stringify({ from: '@julclang/joust engine/', sha256: digests }, null, 2) + '\n');
console.log(`built dist/extension.js, dist/server.js and engine/ (from ${engineDir}) for vscode-joust ${version}`);
mkdirSync(dirname(join(root, 'dist', 'x')), { recursive: true });
